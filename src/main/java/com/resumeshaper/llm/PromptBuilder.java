package com.resumeshaper.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Centralises every prompt so they're easy to iterate and version.
 *
 * Key changes from v1:
 *  - systemInstruction: removed "exactly ONE page" and "ruthlessly cut"
 *  - reshapePrompt: injects actual preserved content from parsedResume
 *    directly into the prompt — LLM sees the exact lines and puts them back
 *  - reshapePrompt schema: added achievements[] field
 */
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // JD Merge logic (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private String buildEffectiveJD(String roleLabel, String userJD) {
        String systemJD = JobDescriptions.getDefault(roleLabel);
        boolean hasUserJD = userJD != null && !userJD.isBlank();

        if (!hasUserJD) return systemJD;

        return """
                [SYSTEM DEFAULT JD — weight 50%%]
                %s

                [USER-PROVIDED JD — weight 50%%]
                %s

                Instructions for merging:
                Give EQUAL weight (50%%) to each JD above when extracting skills,
                keywords, responsibilities, and tone. Do NOT favour one over the other.
                Union all skills/keywords from both; for conflicting seniority/tone,
                prefer the user-provided JD as it reflects the specific target company.
                """.formatted(systemJD.strip(), userJD.strip());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // System instruction
    // KEY FIX: removed "Exactly ONE page" and "ruthlessly cut low-value content"
    // ─────────────────────────────────────────────────────────────────────────

    public String systemInstruction() {
        return """
                You are an expert resume editor and ATS optimization specialist.
                Your job is to REFINE and STRENGTHEN resumes — not gut them.

                ══ WHAT YOU MUST NEVER DO ══
                - Never remove achievements, competitive stats, awards, or rankings
                - Never remove any education entry — keep ALL levels (10th, 12th, degree)
                - Never remove certifications, licenses, or professional credentials
                - Never remove projects that are present in the original
                - Never invent experience, metrics, or skills not in the original resume
                - Never drop existing skills — add to them, never subtract

                ══ WHAT YOU MUST DO ══
                - Rewrite bullet points: strong action verb + what you built/did + measurable result
                - Inject missing JD keywords naturally into existing bullets
                - Keep every section that exists in the original
                - Organize skills by category (Languages, Frameworks, Databases, Tools, etc.)
                - Write a tight 2-3 line summary that is specific to the target role

                ══ PAGE LENGTH ══
                - One page is a preference, not a hard constraint
                - A strong resume with achievements, projects, and education uses the space it needs
                - Never sacrifice legitimate content to hit a page count

                Always respond with valid JSON only. No markdown fences. No commentary.
                """;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 1: JD Analysis (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    public String jdAnalysisPrompt(String userJD, String roleLabel) {
        String effectiveJD = buildEffectiveJD(roleLabel, userJD);

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
                """.formatted(roleLabel, effectiveJD);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 2: Gap analysis (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    @SneakyThrows
    public String gapAnalysisPrompt(Map<String, Object> parsedResume,
                                    Map<String, Object> jdAnalysis,
                                    String roleLabel) {
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
                """.formatted(
                roleLabel,
                objectMapper.writeValueAsString(parsedResume),
                objectMapper.writeValueAsString(jdAnalysis));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 3: Reshape
    // KEY FIX: preservation block injected at the top with actual content,
    // achievements[] added to output schema
    // ─────────────────────────────────────────────────────────────────────────

    @SneakyThrows
    public String reshapePrompt(Map<String, Object> parsedResume,
                                Map<String, Object> jdAnalysis,
                                Map<String, Object> gapAnalysis,
                                String roleLabel) {
        return """
                Rewrite this resume for the role: "%s"

                %s

                ══ REWRITE RULES ══
                1. Rewrite experience bullets: action verb + what you built + metric/outcome
                   Good:  "Reduced API response time by 40%% using Redis caching"
                   Bad:   "Worked on performance improvements"
                2. Inject missing JD keywords naturally into existing bullets — do not force them
                3. Summary: 2-3 punchy lines, role-specific, leads with your strongest signal
                4. Skills: keep ALL original skills, add missing JD-relevant ones, group by category
                5. ATS-safe output only: no tables, columns, or graphics

                ══ ORIGINAL RESUME ══
                %s

                ══ JD ANALYSIS ══
                %s

                ══ GAP ANALYSIS ══
                %s

                Return JSON with this exact schema:
                {
                  "name": "string",
                  "contact": {
                    "email": "string",
                    "phone": "string",
                    "linkedin": "string",
                    "github": "string",
                    "location": "string"
                  },
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
                    {
                      "institution": "string",
                      "degree": "string",
                      "year": "string",
                      "score": "string"
                    }
                  ],
                  "certifications": ["cert1", ...],
                  "projects": [
                    {
                      "name": "string",
                      "description": "string",
                      "tech": ["t1", ...],
                      "link": "string"
                    }
                  ],
                  "achievements": ["achievement with platform/context and metric", ...],
                  "changesLog": ["Change description", ...]
                }
                """.formatted(
                roleLabel,
                buildPreservationBlock(parsedResume),
                objectMapper.writeValueAsString(parsedResume),
                objectMapper.writeValueAsString(jdAnalysis),
                objectMapper.writeValueAsString(gapAnalysis));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 4: ATS Score (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    @SneakyThrows
    public String atsScorePrompt(Map<String, Object> shapedResume,
                                 Map<String, Object> jdAnalysis) {
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
                    "summary":    { "score": 0-100, "feedback": "string" },
                    "skills":     { "score": 0-100, "feedback": "string" },
                    "experience": { "score": 0-100, "feedback": "string" },
                    "education":  { "score": 0-100, "feedback": "string" }
                  },
                  "matchedKeywords": ["kw1", ...],
                  "missingKeywords": ["kw1", ...],
                  "improvements": ["tip1", ...]
                }
                """.formatted(
                objectMapper.writeValueAsString(shapedResume),
                objectMapper.writeValueAsString(jdAnalysis));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 5: Score original (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    @SneakyThrows
    public String originalScorePrompt(Map<String, Object> parsedResume,
                                      Map<String, Object> jdAnalysis) {
        return """
                Score this ORIGINAL (unmodified) resume against the JD for ATS compatibility.
                Be strict — this is the baseline "before" score.

                Original Resume:
                %s

                JD Analysis:
                %s

                Return JSON with only: { "overallScore": 0-100 }
                """.formatted(
                objectMapper.writeValueAsString(parsedResume),
                objectMapper.writeValueAsString(jdAnalysis));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Preservation block
    //
    // Pulls actual content lines out of parsedResume.sections and pastes them
    // into the prompt directly. The LLM sees real content ("LeetCode: Rating
    // 1665...") not abstract rules ("don't remove achievements"). Concrete
    // content is far harder to accidentally drop than abstract instructions.
    //
    // This block is placed BEFORE the resume JSON so the LLM sees the
    // constraints before it processes the data.
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String buildPreservationBlock(Map<String, Object> parsedResume) {
        Object sectionsObj = parsedResume.get("sections");
        if (!(sectionsObj instanceof Map<?, ?> rawMap)) return "";

        Map<String, List<String>> sections = (Map<String, List<String>>) rawMap;

        List<String> achievements = findSection(sections,
                "ACHIEVEMENTS", "AWARDS", "HONORS", "ACCOMPLISHMENTS");
        List<String> education    = findSection(sections,
                "EDUCATION");
        List<String> certs        = findSection(sections,
                "CERTIFICATIONS", "CERTIFICATION", "LICENSES");

        // If nothing to preserve, skip the block entirely
        if (achievements.isEmpty() && education.isEmpty() && certs.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════════╗\n");
        sb.append("║  PROTECTED CONTENT — COPY THESE INTO YOUR JSON OUTPUT        ║\n");
        sb.append("║  These lines are taken directly from the original resume.    ║\n");
        sb.append("║  Every single item below must appear in your output JSON.    ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");

        if (!achievements.isEmpty()) {
            sb.append("ACHIEVEMENTS → copy each item into the achievements[] array:\n");
            achievements.forEach(line -> sb.append("  • ").append(line).append("\n"));
            sb.append("\n");
        }

        if (!education.isEmpty()) {
            sb.append("EDUCATION → copy ALL entries into the education[] array.\n");
            sb.append("Include every level: 10th grade, 12th grade, bachelor's — everything.\n");
            education.forEach(line -> sb.append("  • ").append(line).append("\n"));
            sb.append("\n");
        }

        if (!certs.isEmpty()) {
            sb.append("CERTIFICATIONS → copy each item into the certifications[] array:\n");
            certs.forEach(line -> sb.append("  • ").append(line).append("\n"));
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Tries candidates in order. First does an exact key match,
     * then a contains-match (so "TECHNICAL SKILLS" matches "SKILLS").
     */
    private List<String> findSection(Map<String, List<String>> sections,
                                     String... candidates) {
        for (String candidate : candidates) {
            List<String> exact = sections.get(candidate);
            if (exact != null && !exact.isEmpty()) return exact;

            for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
                if (entry.getKey().contains(candidate)
                        && !entry.getValue().isEmpty()) {
                    return entry.getValue();
                }
            }
        }
        return List.of();
    }
}