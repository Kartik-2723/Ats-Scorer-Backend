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

    private static final String QUOTA_KEY_PREFIX  = "gemini:quota:blocked:";
    private static final long   RPM_BLOCK_TTL_SEC = 65;
    private static final long   RPD_BLOCK_TTL_SEC = 86_400;

    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(502, 503, 504);

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
    }

    // ── Public API ────────────────────────────────────────────
    public String generate(String systemInstruction, String userPrompt) {
        return executeWithFallback(
                model -> doCall(model, systemInstruction, userPrompt, "text/plain")
        );
    }

    public Map<String, Object> generateJson(String systemInstruction, String userPrompt) {
        String raw = executeWithFallback(
                model -> doCall(model, systemInstruction, userPrompt, "application/json")
        );
        try {
            raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();

            // Jackson rejects \d, \b (outside JSON escapes), \l, \t in LaTeX commands etc.
            // ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER treats \X as literal X for any X —
            // which is exactly what we want: \documentclass → documentclass in the value.
            // We use a copy so the shared ObjectMapper bean is not mutated.
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.copy()
                    .configure(com.fasterxml.jackson.core.JsonParser.Feature
                            .ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
                    .readValue(raw, Map.class);
            return parsed;
        } catch (Exception ex) {
            log.error("Failed to parse Gemini JSON response", ex);
            throw new AppException("Failed to parse LLM response", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── Fallback loop ─────────────────────────────────────────

    private String executeWithFallback(ModelCall fn) {
        List<String> models = appProperties.getGemini().getModels();

        for (String model : models) {
            String blockKey = QUOTA_KEY_PREFIX + model;

            if (Boolean.TRUE.equals(redisTemplate.hasKey(blockKey))) {
                log.info("Skipping model={} (quota-blocked)", model);
                continue;
            }

            try {
                String result = fn.call(model);
                log.debug("model={} responded OK", model);
                return result;

            } catch (WebClientResponseException ex) {
                int status = ex.getStatusCode().value();

                if (status == 429) {
                    handleQuota429(model, blockKey, ex.getResponseBodyAsString());
                } else if (TRANSIENT_STATUSES.contains(status)) {
                    log.warn("Gemini transient error {} for model={} – skipping to next model", status, model);
                } else {
                    log.error("Gemini API error {} for model={} – {}", status, model,
                            ex.getResponseBodyAsString());
                    throw new AppException("LLM service error: " + ex.getMessage(), HttpStatus.BAD_GATEWAY);
                }

            } catch (WebClientRequestException ex) {
                log.warn("Gemini network error for model={} – skipping to next model: {}",
                        model, ex.getMessage());

            } catch (AppException ex) {
                throw ex;
            } catch (Exception ex) {
                if (isTimeoutException(ex)) {
                    log.warn("Gemini timeout for model={} – skipping to next model", model);
                } else {
                    log.error("Unexpected error calling model={}", model, ex);
                    throw new AppException("LLM service error: " + ex.getMessage(),
                            HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }

        log.error("All Gemini models exhausted (quota-blocked or transient errors)");
        throw new GeminiQuotaExhaustedException();
    }

    private void handleQuota429(String model, String blockKey, String errorBody) {
        boolean isRpd = isRpdLimit(errorBody);
        long    ttl   = isRpd ? RPD_BLOCK_TTL_SEC : RPM_BLOCK_TTL_SEC;
        String  type  = isRpd ? "RPD" : "RPM";

        redisTemplate.opsForValue().set(blockKey, type, Duration.ofSeconds(ttl));
        log.warn("model={} hit {} quota → blocked for {}s, trying next model", model, type, ttl);
    }

    private boolean isRpdLimit(String errorBody) {
        if (errorBody == null) return false;
        String lower = errorBody.toLowerCase();
        return lower.contains("per-day") || lower.contains("daily");
    }

    // ── HTTP call ─────────────────────────────────────────────

    private String doCall(String model, String systemInstruction,
                          String userPrompt, String mimeType) {
        AppProperties.Gemini cfg = appProperties.getGemini();

        Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text",
                                systemInstruction != null ? systemInstruction : ""))
                ),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userPrompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens",  cfg.getMaxOutputTokens(),
                        "temperature",      "application/json".equals(mimeType) ? 0.2 : 0.3,
                        "responseMimeType", mimeType
                )
        );

        String url = "/models/" + model + ":generateContent?key=" + cfg.getApiKey();

        String raw = webClient.post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .block();

        return extractText(raw);
    }

    private String extractText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.at("/candidates/0/content/parts/0/text").asText();
        } catch (Exception ex) {
            log.error("Cannot extract text from Gemini response: {}", responseBody);
            throw new AppException("Unexpected LLM response format", HttpStatus.BAD_GATEWAY);
        }
    }

    // ── Internal functional interface ─────────────────────────

    @FunctionalInterface
    private interface ModelCall {
        String call(String model);
    }

    private boolean isTimeoutException(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.util.concurrent.TimeoutException) return true;
            cause = cause.getCause();
        }
        return false;
    }
}