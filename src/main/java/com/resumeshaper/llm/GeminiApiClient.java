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

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiApiClient {

    // ── Redis quota-block TTLs ────────────────────────────────────────────────
    private static final String QUOTA_KEY_PREFIX  = "gemini:quota:blocked:";
    private static final long   RPM_BLOCK_TTL_SEC = 65;
    private static final long   RPD_BLOCK_TTL_SEC = 86_400;

    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(502, 503, 504);

    private static final int LATEX_MAX_OUTPUT_TOKENS = 16_000;
    private static final int MIN_LATEX_LENGTH        = 1_500;

    // Minimum length to treat a raw response as a plausible LaTeX document
    private static final int MIN_LATEX_FIX_LENGTH = 500;

    private final AppProperties        appProperties;
    private final ObjectMapper         objectMapper;
    private final StringRedisTemplate  redisTemplate;
    private final GeminiKeyPoolService keyPool;

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
    // Public API  (keyId = null → single-key mode, uses app.gemini.api-key)
    // ─────────────────────────────────────────────────────────────────────────

    public String generate(String keyId, String systemInstruction, String userPrompt) {
        return executeWithFallback(keyId,
                model -> doCall(keyId, model, systemInstruction, userPrompt,
                        "text/plain", appProperties.getGemini().getMaxOutputTokens()));
    }

    public Map<String, Object> generateJson(String keyId, String systemInstruction, String userPrompt) {
        String raw = executeWithFallback(keyId,
                model -> doCall(keyId, model, systemInstruction, userPrompt,
                        "application/json", appProperties.getGemini().getMaxOutputTokens()));
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
                    raw.length(), raw.substring(Math.max(0, raw.length() - 300)));

            // ── Fallback: Gemini sometimes returns raw LaTeX instead of JSON
            // when asked to fix a compile error. If it looks like LaTeX, wrap it
            // so extractLatexFix() can pick it up via the "fixedLatex" key.
            if (raw.contains("\\documentclass") && raw.length() > MIN_LATEX_FIX_LENGTH) {
                log.warn("generateJson: raw response looks like LaTeX — wrapping as fixedLatex");

                // Extract clean LaTeX: try delimiters first, then documentclass scan
                String latex = extractBetween(raw, "<<<LATEX>>>", "<<<END_LATEX>>>");
                if (latex == null || latex.isBlank()) {
                    int start = raw.indexOf("\\documentclass");
                    int end   = raw.lastIndexOf("\\end{document}");
                    if (start != -1 && end != -1 && end > start) {
                        latex = raw.substring(start, end + "\\end{document}".length()).strip();
                    }
                }
                if (latex == null || latex.isBlank()) {
                    latex = raw; // last resort: pass the whole thing
                }

                return Map.of("fixedLatex", latex);
            }

            throw new AppException("Failed to parse LLM response", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public String generateLatex(String keyId, String systemInstruction, String userPrompt) {
        return executeWithFallback(keyId, model -> {
            String raw = doCall(keyId, model, systemInstruction, userPrompt,
                    "text/plain", LATEX_MAX_OUTPUT_TOKENS);

            log.debug("model={} project={} LaTeX raw: len={} tail='{}'",
                    model, keyId != null ? keyId : "default", raw.length(),
                    raw.substring(Math.max(0, raw.length() - 100)));

            String latex = extractBetween(raw, "<<<LATEX>>>", "<<<END_LATEX>>>");

            if (latex == null || latex.isBlank()) {
                log.warn("model={} project={} — delimiter missing, attempting fallback scan",
                        model, keyId != null ? keyId : "default");
                int start = raw.indexOf("\\documentclass");
                int end   = raw.lastIndexOf("\\end{document}");
                if (start != -1 && end != -1 && end > start) {
                    latex = raw.substring(start, end + "\\end{document}".length()).strip();
                }
            }

            if (latex == null || latex.isBlank()) {
                log.warn("model={} project={} no LaTeX found — raw preview: {}",
                        model, keyId != null ? keyId : "default",
                        raw.substring(0, Math.min(500, raw.length())));
                throw new InvalidModelResponseException(
                        "No LaTeX delimiters or \\documentclass found. rawLen=" + raw.length());
            }

            if (latex.length() < MIN_LATEX_LENGTH) {
                log.warn("model={} project={} LaTeX too short ({}) — raw preview: {}",
                        model, keyId != null ? keyId : "default", latex.length(),
                        raw.substring(0, Math.min(500, raw.length())));
                throw new InvalidModelResponseException(
                        "LaTeX too short to be a complete resume. latexLen=" + latex.length());
            }

            return latex;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Model rotation loop
    // ─────────────────────────────────────────────────────────────────────────

    private String executeWithFallback(String keyId, ModelCall fn) {
        List<String> models = appProperties.getGemini().getModels();
        String project = keyId != null ? keyId : "default";

        if (models == null || models.isEmpty()) {
            throw new GeminiQuotaExhaustedException();
        }

        for (String model : models) {
            String blockKey = blockKey(keyId, model);

            if (Boolean.TRUE.equals(redisTemplate.hasKey(blockKey))) {
                Long ttl = redisTemplate.getExpire(blockKey);
                log.info("Skipping model={} project={} (quota-blocked, {}s remaining)",
                        model, project, ttl);
                continue;
            }

            try {
                String result = fn.call(model);
                log.info("Gemini OK: project={} model={}", project, model);
                return result;

            } catch (InvalidModelResponseException ex) {
                log.warn("model={} project={} invalid response — rotating: {}",
                        model, project, ex.getMessage());

            } catch (WebClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 429) {
                    handleQuota429(keyId, model, blockKey, ex.getResponseBodyAsString());
                } else if (TRANSIENT_STATUSES.contains(status)) {
                    log.warn("Gemini transient {} for model={} project={} — rotating",
                            status, model, project);
                } else {
                    log.error("Gemini API error {} for model={} project={} — {}",
                            status, model, project, ex.getResponseBodyAsString());
                    throw new AppException("LLM service error: HTTP " + status, HttpStatus.BAD_GATEWAY);
                }

            } catch (WebClientRequestException ex) {
                log.warn("Gemini network error for model={} project={} — rotating: {}",
                        model, project, ex.getMessage());

            } catch (AppException ex) {
                throw ex;

            } catch (Exception ex) {
                if (isTimeoutException(ex)) {
                    log.warn("Gemini timeout for model={} project={} — rotating", model, project);
                } else {
                    log.error("Unexpected error calling model={} project={}", model, project, ex);
                    throw new AppException("LLM service error: " + ex.getMessage(),
                            HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }

        log.error("All models exhausted: project={} — throwing GeminiQuotaExhaustedException", project);
        throw new GeminiQuotaExhaustedException();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Quota tracking
    // ─────────────────────────────────────────────────────────────────────────

    private String blockKey(String keyId, String model) {
        String kid = (keyId != null) ? keyId : "default";
        return QUOTA_KEY_PREFIX + kid + ":" + model;
    }

    private void handleQuota429(String keyId, String model, String blockKey, String errorBody) {
        boolean isRpd = isRpdLimit(errorBody);
        long    ttl   = isRpd ? RPD_BLOCK_TTL_SEC : RPM_BLOCK_TTL_SEC;
        String  type  = isRpd ? "RPD" : "RPM";

        redisTemplate.opsForValue().set(blockKey, type, Duration.ofSeconds(ttl));
        log.warn("model={} project={} hit {} quota → blocked {}s, rotating",
                model, keyId != null ? keyId : "default", type, ttl);
    }

    private boolean isRpdLimit(String errorBody) {
        if (errorBody == null) return false;
        String lower = errorBody.toLowerCase();
        return lower.contains("per day") || lower.contains("per-day")
                || lower.contains("daily") || lower.contains("quota_exceeded");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP call
    // ─────────────────────────────────────────────────────────────────────────

    private String doCall(String keyId,
                          String model,
                          String systemInstruction,
                          String userPrompt,
                          String mimeType,
                          int    maxOutputTokens) {

        AppProperties.Gemini cfg = appProperties.getGemini();

        String resolvedApiKey = keyPool.resolveApiKey(keyId);

        Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", systemInstruction != null ? systemInstruction : ""))
                ),
                "contents", List.of(Map.of(
                        "role",  "user",
                        "parts", List.of(Map.of("text", userPrompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens",  maxOutputTokens,
                        "temperature",      "application/json".equals(mimeType) ? 0.2 : 0.3,
                        "responseMimeType", mimeType
                )
        );

        String url = "/models/" + model + ":generateContent?key=" + resolvedApiKey;

        log.debug("Gemini request: model={} project={} mimeType={} maxTokens={} promptLen={}",
                model, keyId != null ? keyId : "default", mimeType, maxOutputTokens,
                userPrompt.length());

        String raw = webClient.post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .block();

        return extractText(raw, model, keyId);
    }

    private String extractText(String responseBody, String model, String keyId) {
        try {
            JsonNode root  = objectMapper.readTree(responseBody);
            JsonNode parts = root.at("/candidates/0/content/parts");

            if (parts.isMissingNode() || !parts.isArray() || parts.isEmpty()) {
                log.error("model={} project={} no parts: {}", model,
                        keyId != null ? keyId : "default",
                        responseBody.substring(0, Math.min(500, responseBody.length())));
                throw new AppException("Unexpected LLM response format", HttpStatus.BAD_GATEWAY);
            }

            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                JsonNode text = part.get("text");
                if (text != null) sb.append(text.asText());
            }

            String result = sb.toString().strip();
            if (result.isBlank()) {
                log.error("model={} project={} all parts empty", model,
                        keyId != null ? keyId : "default");
                throw new AppException("LLM returned empty response", HttpStatus.BAD_GATEWAY);
            }

            return result;

        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("model={} project={} cannot parse response: {}", model,
                    keyId != null ? keyId : "default",
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

    @FunctionalInterface
    private interface ModelCall { String call(String model); }

    private static class InvalidModelResponseException extends RuntimeException {
        InvalidModelResponseException(String msg) { super(msg); }
    }
}