package com.resumeshaper.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumeshaper.common.exception.AppException;
import com.resumeshaper.common.exception.GeminiQuotaExhaustedException;
import com.resumeshaper.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gemini API client with:
 *  - Config read from AppProperties (app.gemini.*) — never self-bound via @ConfigurationProperties
 *  - Redis-backed per-model quota tracking (survives restarts, shared across instances)
 *  - Model rotation on 429 (RPD/RPM), 502, 503, 504, network timeouts,
 *    AND invalid/truncated model responses (InvalidModelResponseException)
 *  - generate()       → raw text
 *  - generateJson()   → parses response as JSON Map
 *  - generateLatex()  → extracts LaTeX between delimiters; extraction runs INSIDE
 *                       the fallback loop so a bad response from model N rotates to N+1
 *
 * FIX: generateLatex() now uses LATEX_MAX_OUTPUT_TOKENS (16 000) instead of the
 * shared cfg.getMaxOutputTokens() (typically 8 192).  A full resume LaTeX file is
 * 8 000–15 000 chars; the old budget caused truncation, missing delimiters, and an
 * unnecessary model rotation on every run.
 *
 * FIX: raw response preview (first 500 chars) is now logged whenever
 * InvalidModelResponseException is thrown, so delimiter failures are diagnosable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiApiClient {

    // ── Redis quota tracking keys / TTLs ──────────────────────────────────────

    private static final String QUOTA_KEY_PREFIX  = "gemini:quota:blocked:";
    private static final long   RPM_BLOCK_TTL_SEC = 65;
    private static final long   RPD_BLOCK_TTL_SEC = 86_400;

    // ── Transient HTTP errors — rotate, do NOT fail the job ──────────────────

    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(502, 503, 504);

    // ── Token budgets ─────────────────────────────────────────────────────────
    // LaTeX generation needs a much larger output budget than JSON calls.
    // A complete resume LaTeX file is 8 000–15 000 chars ≈ 3 000–6 000 tokens.
    // Using 16 000 gives headroom for verbose templates and long work histories.

    private static final int LATEX_MAX_OUTPUT_TOKENS = 16_000;

    // ── Minimum plausible LaTeX size — anything shorter is treated as truncated ─
    // A real resume LaTeX file is 3 000–8 000 chars. 1 500 is a very conservative floor.

    private static final int MIN_LATEX_LENGTH = 1_500;

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final AppProperties       appProperties;
    private final ObjectMapper        objectMapper;
    private final StringRedisTemplate redisTemplate;

    private WebClient webClient;

    @PostConstruct
    private void init() {
        webClient = WebClient.builder()
                .baseUrl(appProperties.getGemini().getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        log.info("GeminiApiClient initialised — baseUrl={} models={}",
                appProperties.getGemini().getBaseUrl(),
                appProperties.getGemini().getModels());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Raw text generation.
     */
    public String generate(String systemInstruction, String userPrompt) {
        return executeWithFallback(
                model -> doCall(model, systemInstruction, userPrompt,
                        "text/plain", appProperties.getGemini().getMaxOutputTokens())
        );
    }

    /**
     * Call Gemini and parse response as JSON Map.
     * Used for: planner, content rewrite, ATS scoring, fix prompts.
     */
    public Map<String, Object> generateJson(String systemInstruction, String userPrompt) {
        String raw = executeWithFallback(
                model -> doCall(model, systemInstruction, userPrompt,
                        "application/json", appProperties.getGemini().getMaxOutputTokens())
        );

        try {
            raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.copy()
                    .configure(com.fasterxml.jackson.core.JsonParser.Feature
                            .ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
                    .readValue(raw, Map.class);
            return parsed;

        } catch (Exception ex) {
            log.error("Failed to parse Gemini JSON response. rawLen={} tail='{}'",
                    raw.length(),
                    raw.substring(Math.max(0, raw.length() - 300)));
            throw new AppException("Failed to parse LLM response",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Call Gemini for LaTeX generation.
     *
     * KEY DESIGN: LaTeX extraction runs INSIDE executeWithFallback's lambda.
     * If a model returns a response without valid LaTeX (truncated, no delimiters,
     * too short), InvalidModelResponseException is thrown — which the rotation loop
     * catches and uses to try the next model instead of failing the job.
     *
     * FIX: Uses LATEX_MAX_OUTPUT_TOKENS (16 000) instead of the shared JSON budget
     * (typically 8 192). This eliminates the truncation-driven model rotations that
     * were adding ~70s of latency to every job.
     *
     * Extraction order:
     *   1. <<<LATEX>>> ... <<<END_LATEX>>> delimiters
     *   2. \documentclass ... \end{document} scan (fallback)
     *   3. Response too short → InvalidModelResponseException → rotate
     */
    public String generateLatex(String systemInstruction, String userPrompt) {
        return executeWithFallback(model -> {
            // FIX: pass LATEX_MAX_OUTPUT_TOKENS explicitly — not the shared JSON budget
            String raw = doCall(model, systemInstruction, userPrompt,
                    "text/plain", LATEX_MAX_OUTPUT_TOKENS);

            log.debug("model={} LaTeX raw response: len={} tail='{}'",
                    model, raw.length(),
                    raw.substring(Math.max(0, raw.length() - 100)));

            // ── Primary: delimiter extraction ──────────────────────────────
            String latex = extractBetween(raw, "<<<LATEX>>>", "<<<END_LATEX>>>");

            // ── Fallback: \documentclass...\end{document} scan ────────────
            if (latex == null || latex.isBlank()) {
                log.warn("model={} — delimiter <<<LATEX>>> missing, attempting fallback scan",
                        model);
                int start = raw.indexOf("\\documentclass");
                int end   = raw.lastIndexOf("\\end{document}");
                if (start != -1 && end != -1 && end > start) {
                    latex = raw.substring(start, end + "\\end{document}".length()).strip();
                }
            }

            // ── Validate: no LaTeX found at all ───────────────────────────
            if (latex == null || latex.isBlank()) {
                // FIX: log raw preview so delimiter failures are diagnosable
                log.warn("model={} no LaTeX found — raw preview (first 500): {}",
                        model, raw.substring(0, Math.min(500, raw.length())));
                throw new InvalidModelResponseException(
                        "No LaTeX delimiters or \\documentclass found. rawLen=" + raw.length());
            }

            // ── Validate: response suspiciously short (truncated) ─────────
            if (latex.length() < MIN_LATEX_LENGTH) {
                log.warn("model={} LaTeX too short ({}), likely truncated — raw preview (first 500): {}",
                        model, latex.length(), raw.substring(0, Math.min(500, raw.length())));
                throw new InvalidModelResponseException(
                        "LaTeX response too short to be a complete resume. " +
                                "latexLen=" + latex.length() + " (min=" + MIN_LATEX_LENGTH + ")");
            }

            return latex;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Model rotation loop
    // ─────────────────────────────────────────────────────────────────────────

    private String executeWithFallback(ModelCall fn) {
        List<String> models = appProperties.getGemini().getModels();

        if (models == null || models.isEmpty()) {
            log.error("No Gemini models configured under app.gemini.models — check application.yml");
            throw new GeminiQuotaExhaustedException();
        }

        for (String model : models) {
            String blockKey = QUOTA_KEY_PREFIX + model;

            if (Boolean.TRUE.equals(redisTemplate.hasKey(blockKey))) {
                Long ttl = redisTemplate.getExpire(blockKey);
                log.info("Skipping model={} (quota-blocked in Redis, {}s remaining)", model, ttl);
                continue;
            }

            try {
                String result = fn.call(model);
                log.debug("model={} responded OK", model);
                return result;

            } catch (InvalidModelResponseException ex) {
                // Bad content from model (no LaTeX, truncated, etc.) — rotate, not a quota issue
                log.warn("model={} returned invalid/incomplete response — rotating to next model: {}",
                        model, ex.getMessage());

            } catch (WebClientResponseException ex) {
                int status = ex.getStatusCode().value();

                if (status == 429) {
                    handleQuota429(model, blockKey, ex.getResponseBodyAsString());
                } else if (TRANSIENT_STATUSES.contains(status)) {
                    log.warn("Gemini transient {} for model={} — rotating to next model",
                            status, model);
                } else {
                    log.error("Gemini API error {} for model={} — {}",
                            status, model, ex.getResponseBodyAsString());
                    throw new AppException("LLM service error: HTTP " + status,
                            HttpStatus.BAD_GATEWAY);
                }

            } catch (WebClientRequestException ex) {
                log.warn("Gemini network error for model={} — rotating to next model: {}",
                        model, ex.getMessage());

            } catch (AppException ex) {
                // Our own business errors — propagate immediately, don't rotate
                throw ex;

            } catch (Exception ex) {
                if (isTimeoutException(ex)) {
                    log.warn("Gemini timeout for model={} — rotating to next model", model);
                } else {
                    log.error("Unexpected error calling model={}", model, ex);
                    throw new AppException("LLM service error: " + ex.getMessage(),
                            HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }

        log.error("All {} Gemini models exhausted (quota-blocked or bad responses)",
                models.size());
        throw new GeminiQuotaExhaustedException();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Quota tracking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inspect 429 error body to distinguish RPD (daily) from RPM (per-minute).
     * RPD → block 24h. RPM → block 65s.
     */
    private void handleQuota429(String model, String blockKey, String errorBody) {
        boolean isRpd = isRpdLimit(errorBody);
        long    ttl   = isRpd ? RPD_BLOCK_TTL_SEC : RPM_BLOCK_TTL_SEC;
        String  type  = isRpd ? "RPD" : "RPM";

        redisTemplate.opsForValue().set(blockKey, type, Duration.ofSeconds(ttl));
        log.warn("model={} hit {} quota limit → blocked for {}s, rotating to next model",
                model, type, ttl);
    }

    private boolean isRpdLimit(String errorBody) {
        if (errorBody == null) return false;
        String lower = errorBody.toLowerCase();
        return lower.contains("per day")
                || lower.contains("per-day")
                || lower.contains("daily")
                || lower.contains("quota_exceeded");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP call
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Core HTTP call to Gemini.
     *
     * @param maxOutputTokens caller-specified token budget.
     *   generateJson()  → cfg.getMaxOutputTokens()  (typically 8 192 — JSON is small)
     *   generateLatex() → LATEX_MAX_OUTPUT_TOKENS    (16 000 — full resume LaTeX)
     *   generate()      → cfg.getMaxOutputTokens()  (raw text, variable)
     */
    private String doCall(String model,
                          String systemInstruction,
                          String userPrompt,
                          String mimeType,
                          int    maxOutputTokens) {

        AppProperties.Gemini cfg = appProperties.getGemini();

        Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text",
                                systemInstruction != null ? systemInstruction : ""))
                ),
                "contents", List.of(Map.of(
                        "role",  "user",
                        "parts", List.of(Map.of("text", userPrompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens",  maxOutputTokens,          // FIX: caller-controlled
                        "temperature",      "application/json".equals(mimeType) ? 0.2 : 0.3,
                        "responseMimeType", mimeType
                )
        );

        String url = "/models/" + model + ":generateContent?key=" + cfg.getApiKey();

        log.debug("Gemini request: model={} mimeType={} maxTokens={} promptLen={}",
                model, mimeType, maxOutputTokens, userPrompt.length());

        String raw = webClient.post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .block();

        return extractText(raw, model);
    }

    /**
     * Concatenates all text parts from a Gemini response.
     * Thinking models return multiple parts (reasoning + answer) — concatenating
     * all ensures we always get the complete answer text.
     */
    private String extractText(String responseBody, String model) {
        try {
            JsonNode root  = objectMapper.readTree(responseBody);
            JsonNode parts = root.at("/candidates/0/content/parts");

            if (parts.isMissingNode() || !parts.isArray() || parts.isEmpty()) {
                log.error("model={} — no parts in response: {}", model,
                        responseBody.substring(0, Math.min(500, responseBody.length())));
                throw new AppException("Unexpected LLM response format",
                        HttpStatus.BAD_GATEWAY);
            }

            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                JsonNode text = part.get("text");
                if (text != null) sb.append(text.asText());
            }

            String result = sb.toString().strip();
            if (result.isBlank()) {
                log.error("model={} — all parts empty. response: {}", model,
                        responseBody.substring(0, Math.min(500, responseBody.length())));
                throw new AppException("LLM returned empty response", HttpStatus.BAD_GATEWAY);
            }

            return result;

        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("model={} — cannot parse response: {}", model,
                    responseBody.substring(0, Math.min(500, responseBody.length())));
            throw new AppException("Unexpected LLM response format", HttpStatus.BAD_GATEWAY);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String extractBetween(String text, String open, String close) {
        int start = text.indexOf(open);
        int end   = text.indexOf(close);
        if (start == -1 || end == -1 || end <= start) return null;
        return text.substring(start + open.length(), end).strip();
    }

    private boolean isTimeoutException(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.util.concurrent.TimeoutException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal types
    // ─────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ModelCall {
        String call(String model);
    }

    /**
     * Thrown when a model returns HTTP 200 but the response content is invalid
     * (no LaTeX delimiters, truncated, too short, etc.).
     * Caught by executeWithFallback to rotate to the next model — NOT propagated.
     */
    private static class InvalidModelResponseException extends RuntimeException {
        InvalidModelResponseException(String msg) {
            super(msg);
        }
    }
}