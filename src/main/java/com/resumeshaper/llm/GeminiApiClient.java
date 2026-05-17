package com.resumeshaper.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumeshaper.common.exception.AppException;
import com.resumeshaper.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for the Gemini generateContent API.
 * Keeps prompt construction out of this class — see PromptBuilder.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiApiClient {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private WebClient buildClient() {
        AppProperties.Gemini cfg = appProperties.getGemini();
        return WebClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    /**
     * Send a single user prompt and return the text response.
     *
     * @param systemInstruction optional system-level instruction
     * @param userPrompt        the user turn content
     * @return model response text
     */
    public String generate(String systemInstruction, String userPrompt) {
        AppProperties.Gemini cfg = appProperties.getGemini();

        Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", systemInstruction != null ? systemInstruction : ""))
                ),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userPrompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens", cfg.getMaxOutputTokens(),
                        "temperature", 0.3,
                        "responseMimeType", "text/plain"
                )
        );

        String url = "/models/" + cfg.getModel() + ":generateContent?key=" + cfg.getApiKey();

        try {
            String raw = buildClient().post()
                    .uri(url)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .block();

            return extractText(raw);

        } catch (WebClientResponseException ex) {
            log.error("Gemini API error {} – {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new AppException("LLM service error: " + ex.getMessage(), HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * Same as generate() but requests a JSON object back (sets responseMimeType).
     */
    public Map<String, Object> generateJson(String systemInstruction, String userPrompt) {
        AppProperties.Gemini cfg = appProperties.getGemini();

        Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", systemInstruction != null ? systemInstruction : ""))
                ),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userPrompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens", cfg.getMaxOutputTokens(),
                        "temperature", 0.2,
                        "responseMimeType", "application/json"
                )
        );

        String url = "/models/" + cfg.getModel() + ":generateContent?key=" + cfg.getApiKey();

        try {
            String raw = buildClient().post()
                    .uri(url)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .block();

            String text = extractText(raw);
            // Strip possible markdown fences
            text = text.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(text, Map.class);
            return parsed;

        } catch (WebClientResponseException ex) {
            log.error("Gemini JSON error {} – {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new AppException("LLM service error: " + ex.getMessage(), HttpStatus.BAD_GATEWAY);
        } catch (Exception ex) {
            log.error("Failed to parse Gemini JSON response", ex);
            throw new AppException("Failed to parse LLM response", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── Helper ───────────────────────────────────────────────

    private String extractText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.at("/candidates/0/content/parts/0/text").asText();
        } catch (Exception ex) {
            log.error("Cannot extract text from Gemini response: {}", responseBody);
            throw new AppException("Unexpected LLM response format", HttpStatus.BAD_GATEWAY);
        }
    }
}
