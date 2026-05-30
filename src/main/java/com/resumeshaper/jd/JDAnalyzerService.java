package com.resumeshaper.jd;

import com.resumeshaper.llm.GeminiApiClient;
import com.resumeshaper.llm.PromptBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
@Service
@RequiredArgsConstructor
public class JDAnalyzerService {

    private final GeminiApiClient gemini;
    private final PromptBuilder prompts;

    public Map<String, Object> analyze(String jdText, String roleLabel) {
        return gemini.generateJson(
                null,
                prompts.systemInstruction(),
                prompts.jdAnalysisPrompt(jdText, roleLabel)
        );
    }
}
