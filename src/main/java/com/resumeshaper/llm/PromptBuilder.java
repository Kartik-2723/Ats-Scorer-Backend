package com.resumeshaper.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Centralises every prompt so they're easy to iterate and version.
 * All prompts instruct the model to return JSON matching a documented schema.
 */
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final ObjectMapper objectMapper;

    // ── System instruction (shared) ───────────────────────────

    public String systemInstruction() {
        return """
            You are an expert resume writer and ATS optimization specialist.
            You reshape resumes to be:
            - Exactly ONE page
            - Highly relevant to the target role and job description
            - Rich with measurable achievements and strong action verbs
            - ATS-optimized: keyword-dense, clean formatting, no tables/graphics
            - Concise and impactful — remove fluff, keep value
            Always respond with valid JSON only, no markdown fences, no commentary.
            """;
    }

    // ── Stage 1: JD Analysis ─────────────────────────────────

    @SneakyThrows
    public String jdAnalysisPrompt(String jdText, String roleLabel) {
        return """
            Analyze this job description for the role: "%s"

            JD:
            ---
            %s
            ---

            Return JSON with this exact schema:
            {
              "requiredSkills": ["skill1", ...],
              "niceToHaveSkills": ["skill1", ...],
              "keywords": ["kw1", ...],
              "tone": "formal | startup | technical | creative",
              "seniority": "junior | mid | senior | lead",
              "industryDomain": "string",
              "coreResponsibilities": ["resp1", ...],
              "softSkills": ["skill1", ...]
            }
            """.formatted(roleLabel, jdText);
    }

    // ── Stage 2: Gap analysis ─────────────────────────────────

    @SneakyThrows
    public String gapAnalysisPrompt(Map<String, Object> parsedResume,
                                     Map<String, Object> jdAnalysis,
                                     String roleLabel) {
        String resumeJson = objectMapper.writeValueAsString(parsedResume);
        String jdJson     = objectMapper.writeValueAsString(jdAnalysis);
        return """
            Compare this candidate's resume against the JD analysis for role: "%s"

            Resume:
            %s

            JD Analysis:
            %s

            Return JSON:
            {
              "matchedSkills": ["skill1", ...],
              "missingSkills": ["skill1", ...],
              "weakBullets": ["bullet text that needs improvement", ...],
              "strengthAreas": ["area1", ...],
              "gapScore": 0-100,
              "recommendations": ["rec1", ...]
            }
            """.formatted(roleLabel, resumeJson, jdJson);
    }

    // ── Stage 3: Reshape ─────────────────────────────────────

    @SneakyThrows
    public String reshapePrompt(Map<String, Object> parsedResume,
                                 Map<String, Object> jdAnalysis,
                                 Map<String, Object> gapAnalysis,
                                 String roleLabel) {
        String resumeJson = objectMapper.writeValueAsString(parsedResume);
        String jdJson     = objectMapper.writeValueAsString(jdAnalysis);
        String gapJson    = objectMapper.writeValueAsString(gapAnalysis);

        return """
            Rewrite this resume for the role: "%s"

            Rules:
            1. ONE page maximum — ruthlessly cut low-value content
            2. Rewrite bullets to lead with strong action verbs and include metrics
            3. Integrate missing keywords naturally from the JD analysis
            4. Keep only relevant experience; trim irrelevant roles to 1-2 lines or remove
            5. Summary should be 2-3 lines, punchy, role-specific
            6. Skills section must list the matched + relevant skills first
            7. Format must be ATS-safe: no tables, columns, graphics, headers/footers

            Original Resume:
            %s

            JD Analysis:
            %s

            Gap Analysis:
            %s

            Return JSON with this schema:
            {
              "name": "string",
              "contact": { "email": "string", "phone": "string", "linkedin": "string", "location": "string" },
              "summary": "string",
              "skills": ["skill1", ...],
              "experience": [
                {
                  "company": "string",
                  "title": "string",
                  "startDate": "string",
                  "endDate": "string",
                  "bullets": ["action verb + impact + metric", ...]
                }
              ],
              "education": [
                { "institution": "string", "degree": "string", "year": "string" }
              ],
              "certifications": ["cert1", ...],
              "projects": [
                { "name": "string", "description": "string", "tech": ["t1", ...] }
              ],
              "changesLog": ["Change 1 description", ...]
            }
            """.formatted(roleLabel, resumeJson, jdJson, gapJson);
    }

    // ── Stage 4: ATS Score ────────────────────────────────────

    @SneakyThrows
    public String atsScorePrompt(Map<String, Object> shapedResume,
                                  Map<String, Object> jdAnalysis) {
        String resumeJson = objectMapper.writeValueAsString(shapedResume);
        String jdJson     = objectMapper.writeValueAsString(jdAnalysis);

        return """
            Score this resume against the JD for ATS compatibility.

            Shaped Resume:
            %s

            JD Analysis:
            %s

            Return JSON:
            {
              "overallScore": 0-100,
              "keywordScore": 0-100,
              "sectionScore": 0-100,
              "formatScore": 0-100,
              "breakdown": {
                "summary": { "score": 0-100, "feedback": "string" },
                "skills":  { "score": 0-100, "feedback": "string" },
                "experience": { "score": 0-100, "feedback": "string" },
                "education": { "score": 0-100, "feedback": "string" }
              },
              "matchedKeywords": ["kw1", ...],
              "missingKeywords": ["kw1", ...],
              "improvements": ["tip1", ...]
            }
            """.formatted(resumeJson, jdJson);
    }

    // ── Stage 5: Score original (before) ─────────────────────

    @SneakyThrows
    public String originalScorePrompt(Map<String, Object> parsedResume,
                                       Map<String, Object> jdAnalysis) {
        String resumeJson = objectMapper.writeValueAsString(parsedResume);
        String jdJson     = objectMapper.writeValueAsString(jdAnalysis);

        return """
            Score this ORIGINAL (unmodified) resume against the JD for ATS compatibility.
            Be strict — this is the baseline "before" score.

            Original Resume:
            %s

            JD Analysis:
            %s

            Return JSON with only: { "overallScore": 0-100 }
            """.formatted(resumeJson, jdJson);
    }
}
