package com.resumeshaper.latex;

import com.resumeshaper.llm.JobDescriptions;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LatexPromptBuilder {

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 1 — Planner
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * System instruction for the planner call.
     * Purely analytical — no LaTeX rules, no compilation constraints.
     * Kept separate from latexSystemInstruction() intentionally.
     */
    public String plannerSystemInstruction() {
        return """
                You are an expert resume strategist and ATS optimization specialist.
                Your job is to ANALYSE a resume and produce a structured JSON plan
                that will guide a separate LaTeX formatting step.

                You do NOT write LaTeX. You do NOT rewrite content.
                You ONLY analyse, discover, rank, and flag.

                ══ PROFILE TYPE DEFINITIONS ══
                STUDENT_FRESHER  — currently in college OR <1 year experience, no full-time roles
                EARLY_CAREER     — 0–2 years full-time experience
                MID_SENIOR       — 3+ years full-time experience
                CAREER_SWITCHER  — prior background clearly mismatches the target role

                ══ SECTION DISCOVERY RULES ══
                1. Find EVERY section in the resume — do not skip any
                2. Preserve section names EXACTLY as they appear in the resume
                3. Common sections include but are not limited to:
                   Summary, Objective, Experience, Projects, Technical Skills,
                   Skills, Education, Achievements, Certifications,
                   Positions of Responsibility, Leadership, Volunteer Work,
                   Publications, Extra Curriculars, Hobbies, Awards,
                   Courses, Training, Languages, Interests
                4. A section with content is a section — never drop it

                ══ SECTION RANKING RULES ══
                1. rankedSections must contain EXACTLY the same sections as discoveredSections
                2. No section may be added or removed — only reordered
                3. Rank by ATS impact for the given role category and profile type:

                   Engineering roles (Software/Frontend/Backend/Full Stack/DevOps/Mobile/QA/Security/Cloud):
                     STUDENT_FRESHER  → Projects > Skills > Achievements > Certifications > Leadership/Positions > Education
                     EARLY_CAREER     → Projects > Experience > Skills > Achievements > Education
                     MID_SENIOR       → Experience > Projects > Skills > Achievements > Education

                   Data / AI-ML roles (Data Engineer/Scientist, ML Engineer, AI/LLM Engineer):
                     STUDENT_FRESHER  → Projects > Skills > Certifications > Education > Achievements > Leadership/Positions
                     EARLY_CAREER     → Projects > Experience > Skills > Education > Certifications
                     MID_SENIOR       → Experience > Projects > Skills > Education > Certifications

                   Product roles (Product Manager, Scrum Master):
                     Any profile      → Experience > Leadership/Positions > Projects > Skills > Education > Achievements

                   Design roles (Product Designer/UX, UI Designer):
                     Any profile      → Projects > Skills > Experience > Education > Achievements

                   Business roles (Business Analyst):
                     Any profile      → Experience > Skills > Education > Achievements > Certifications

                   Marketing/Sales roles (Marketing Manager, Sales Engineer):
                     Any profile      → Experience > Achievements > Skills > Education > Certifications

                   Finance/HR/Content roles (Finance Analyst, HR Manager, Technical Writer):
                     Any profile      → Education > Certifications > Experience > Skills > Achievements

                4. OVERRIDE RULE: If a section has unusually strong content for the profile
                   (e.g. STUDENT_FRESHER with 5+ leadership roles), move it up regardless of defaults
                5. Summary/Objective always stays directly below the Header when present
                6. Education always stays at or near the bottom UNLESS Finance/HR/Content role

                ══ KEYWORD RULES ══
                mustBoldKeywords   — appear in BOTH resume content AND JD → bold these
                injectableKeywords — in JD but missing from resume; injectable ONLY if
                                     existing resume content logically implies the skill
                                     (e.g. candidate uses Docker → "containerization" injectable)
                                     NEVER inject if no basis exists in resume
                atsGapKeywords     — in JD, absent from resume, NOT injectable
                                     (no existing basis) → surface to user as manual suggestions

                ══ CONTENT FLAG RULES ══
                Flag bullets that:
                - Could have a metric but don't ("Built backend service" → flag for "add N req/sec or user count")
                - Use weak verbs ("Was responsible for" → suggest "Led", "Engineered", "Architected")
                - Are vague without specifics ("Improved performance" → "by what %? in what context?")
                Maximum 8 flags total. Prioritise the most impactful improvements.
                """;
    }

    /**
     * Planner prompt — Phase 1 of the pipeline.
     * Returns structured JSON plan, no LaTeX.
     */
    public String resumePlannerPrompt(String extractedText,
                                      String roleLabel,
                                      String roleCategory,
                                      String jdText) {
        String effectiveJD = buildEffectiveJD(roleLabel, jdText);

        return """
                Analyse this resume for the role: "%s" (Category: "%s")

                ══ EXTRACTED RESUME TEXT ══
                (Lines prefixed [HEADING] = section titles, [BOLD] = important labels,
                 [BULLET] = bullet points. Plain lines = body text.)

                %s

                ══ TARGET JOB DESCRIPTION ══
                %s

                ══ YOUR TASK ══
                1. Detect profile type (STUDENT_FRESHER / EARLY_CAREER / MID_SENIOR / CAREER_SWITCHER)
                2. Discover ALL sections — include even small or unusual ones
                3. Rank sections by ATS impact for this specific role + profile type
                4. Identify keywords to bold, inject, and flag as gaps
                5. Flag up to 8 bullets with content improvement suggestions

                ══ OUTPUT FORMAT ══
                Return ONLY this JSON — no markdown fences, no extra fields:
                {
                  "profileType": "STUDENT_FRESHER",
                  "discoveredSections": ["Summary", "Projects", "Technical Skills", "Achievements", "Education"],
                  "rankedSections": ["Summary", "Projects", "Technical Skills", "Achievements", "Education"],
                  "mustBoldKeywords": ["Spring Boot", "Keycloak", "OAuth2"],
                  "injectableKeywords": ["microservices", "JWT"],
                  "atsGapKeywords": ["Kubernetes", "CI/CD"],
                  "contentFlags": [
                    {
                      "section": "Projects",
                      "bullet": "Built backend services in Spring Boot",
                      "suggestion": "Add metric — N requests/sec, user count, or latency improvement"
                    }
                  ]
                }
                """.formatted(roleLabel, roleCategory, extractedText, effectiveJD);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 2 — LaTeX system instruction (execution only, no ordering decisions)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * System instruction for the reshape call.
     * Section order is provided dynamically in each prompt — NOT hardcoded here.
     * This instruction handles ONLY compilation safety and formatting rules.
     */
    public String latexSystemInstruction() {
        return """
                You are an expert LaTeX resume engineer and ATS optimization specialist.
                You produce ONLY valid LaTeX source code that compiles with Tectonic (pdflatex-compatible).

                ══ TECTONIC COMPILATION RULES (NEVER VIOLATE) ══
                1. Use ONLY these safe packages: fontenc, lmodern, geometry, titlesec,
                   enumitem, hyperref, tabularx, xcolor, array, parskip
                2. Never use: fontspec, xunicode, xltxtra, unicode-math, luacode,
                   or any XeLaTeX/LuaLaTeX-only package
                3. No Unicode characters in LaTeX source — use ASCII or LaTeX escapes:
                   é → \\'e  |  ñ → \\~n  |  • → \\textbullet  |  — → ---
                4. Every \\begin{itemize} must include options inline:
                   \\begin{itemize}[leftmargin=14pt,noitemsep,topsep=1pt]
                   NEVER use \\setlist globally
                5. Every \\section or \\section* must be defined before use
                6. All braces must be balanced — every { has a matching }
                7. No \\input or \\include — everything in one file
                8. End document with \\end{document} as the very last line

                ══ RESHAPE EXECUTION RULES ══
                1. Section order is given explicitly in each prompt — follow it EXACTLY
                2. Bold these with \\textbf{}: the mustBoldKeywords list given in each prompt,
                   plus any numbers, metrics, certification names, company names, percentages
                3. Inject injectableKeywords naturally into existing bullets — do NOT add new bullets
                4. Bullet points: action verb + what was built + outcome/metric
                5. Keep ALL content from original — remove nothing
                6. Compress spacing:
                   \\titlespacing*{\\section}{0pt}{6pt}{3pt}
                   itemize: leftmargin=14pt, noitemsep, topsep=1pt
                7. Header: name in large font, contact info on one line with | separators

                ══ SECTION ORDER IS DYNAMIC — NEVER HARDCODE IT ══
                The section order for every resume is provided in the prompt.
                Do not assume any fixed order. Follow the rankedSections list precisely.
                """;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt 1: PDF text → LaTeX reshape (plan-aware)
    // ─────────────────────────────────────────────────────────────────────────

    public String pdfToLatexReshapePrompt(String extractedText,
                                          ResumePlan plan,
                                          String roleLabel,
                                          String jdText) {
        String effectiveJD = buildEffectiveJD(roleLabel, jdText);

        return """
                Convert the following extracted resume text into a complete, ATS-optimised
                LaTeX resume for the role: "%s"
                Candidate profile type: %s

                ══ EXTRACTED RESUME TEXT ══
                (Lines prefixed [HEADING] = section titles, [BOLD] = important labels,
                 [BULLET] = bullet points. Plain lines = body text.)

                %s

                ══ TARGET JOB DESCRIPTION ══
                %s

                ══ SECTION ORDER — FOLLOW EXACTLY, DO NOT DEVIATE ══
                %s

                ══ PRESERVATION AUDIT — VERIFY BEFORE OUTPUTTING ══
                The following %d sections were discovered in the original resume.
                Your LaTeX output MUST contain a \\section{} block for EVERY one of them.
                Missing even one section is a critical failure.
                Discovered sections: %s

                ══ KEYWORD INSTRUCTIONS ══
                Wrap these keywords in \\textbf{} wherever they appear naturally in content:
                Bold keywords: %s

                Inject these keywords naturally into existing bullets (do NOT add new bullets):
                Injectable keywords: %s

                ══ YOUR TASK ══
                1. Reconstruct the full resume as valid Tectonic-compilable LaTeX
                2. Follow section order above EXACTLY — Header first, then ranked sections
                3. Apply ALL reshape rules from your system instruction
                4. Preserve every fact, project, achievement, education entry, and
                   leadership/responsibility entry — remove NOTHING
                5. Escape ALL special LaTeX characters in content:
                   & → \\& | %% → \\%% | $ → \\$ | # → \\# | _ → \\_ | ^ → \\^{}
                   ~ → \\textasciitilde{} | < → \\textless{} | > → \\textgreater{}
                6. Before finalising output, count your \\section{} blocks — must equal %d

                ══ OUTPUT FORMAT ══
                Return ONLY this JSON — no markdown fences, no extra fields:
                {
                  "shapedLatex": "...complete \\\\documentclass...\\\\end{document} source...",
                  "changesLog": ["change1", "change2"]
                }
                """.formatted(
                roleLabel,
                plan.getProfileType(),
                extractedText,
                effectiveJD,
                formatSectionList(plan.getRankedSections()),
                plan.getDiscoveredSections().size(),
                String.join(", ", plan.getDiscoveredSections()),
                formatKeywordList(plan.getMustBoldKeywords()),
                formatKeywordList(plan.getInjectableKeywords()),
                plan.getDiscoveredSections().size()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt 2: LaTeX → reshape (user uploaded .tex directly, plan-aware)
    // ─────────────────────────────────────────────────────────────────────────

    public String latexReshapePrompt(String rawLatex,
                                     ResumePlan plan,
                                     String roleLabel,
                                     String jdText) {
        String effectiveJD = buildEffectiveJD(roleLabel, jdText);

        return """
                Reshape the following LaTeX resume for the role: "%s"
                Candidate profile type: %s

                ══ ORIGINAL LATEX ══
                %s

                ══ TARGET JOB DESCRIPTION ══
                %s

                ══ SECTION ORDER — FOLLOW EXACTLY, DO NOT DEVIATE ══
                %s

                ══ PRESERVATION AUDIT — VERIFY BEFORE OUTPUTTING ══
                The following %d sections were discovered in the original resume.
                Your LaTeX output MUST contain a \\section{} block for EVERY one of them.
                Missing even one section is a critical failure.
                Discovered sections: %s

                ══ KEYWORD INSTRUCTIONS ══
                Wrap these keywords in \\textbf{} wherever they appear naturally in content:
                Bold keywords: %s

                Inject these keywords naturally into existing bullets (do NOT add new bullets):
                Injectable keywords: %s

                ══ YOUR TASK ══
                1. Apply ALL reshape rules from your system instruction
                2. Follow section order above EXACTLY
                3. Bold all numbers, metrics, and bold keywords with \\textbf{}
                4. Strengthen bullet verbs and inject injectable keywords naturally
                5. Fix any spacing issues (tight itemize options, compressed section spacing)
                6. Preserve ALL content — same projects, education, achievements,
                   leadership roles, positions of responsibility — remove NOTHING
                7. Output must be valid Tectonic-compilable LaTeX
                8. Before finalising output, count your \\section{} blocks — must equal %d

                ══ OUTPUT FORMAT ══
                Return ONLY this JSON — no markdown fences, no extra fields:
                {
                  "shapedLatex": "...complete \\\\documentclass...\\\\end{document} source...",
                  "changesLog": ["change1", "change2"]
                }
                """.formatted(
                roleLabel,
                plan.getProfileType(),
                rawLatex,
                effectiveJD,
                formatSectionList(plan.getRankedSections()),
                plan.getDiscoveredSections().size(),
                String.join(", ", plan.getDiscoveredSections()),
                formatKeywordList(plan.getMustBoldKeywords()),
                formatKeywordList(plan.getInjectableKeywords()),
                plan.getDiscoveredSections().size()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt 3: Fix LaTeX syntax errors — plan-aware, escalating strategy
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param attempt  compile attempt number (2 = standard fix, 3 = aggressive fix)
     * @param plan     used to enforce section preservation during fix
     */
    public String latexFixPrompt(String brokenLatex,
                                 String compilerLog,
                                 ResumePlan plan,
                                 int attempt) {

        String sectionConstraints = buildSectionConstraints(plan);
        String strategyBlock      = attempt >= 3
                ? aggressiveFixStrategy()
                : standardFixStrategy();

        return """
                Fix the LaTeX syntax errors in the source below so it compiles with Tectonic.

                ══ TECTONIC ERROR LOG ══
                %s

                ══ BROKEN LATEX SOURCE ══
                %s

                ══ FIX STRATEGY (Attempt %d of 3) ══
                %s

                ══ SECTION PRESERVATION — DO NOT VIOLATE WHILE FIXING ══
                %s

                ══ OUTPUT FORMAT — THIS OVERRIDES ALL OTHER INSTRUCTIONS ══
                You MUST return ONLY this exact JSON structure. No other keys.
                The key is "fixedLatex" — NOT "shapedLatex", NOT "latex", NOT anything else.
                No markdown fences. No commentary. No changesLog. No scores.

                { "fixedLatex": "...complete fixed \\\\documentclass...\\\\end{document} here..." }
                """.formatted(compilerLog, brokenLatex, attempt,
                strategyBlock, sectionConstraints);
    }

    private String standardFixStrategy() {
        return """
                This is a STANDARD fix attempt. Rules:
                1. Read the error log — identify the exact line and error type
                2. Fix ONLY the syntax errors — do NOT change any content, wording,
                   section order, bold decisions, or formatting choices
                3. Common fixes:
                   - Unbalanced braces: count { and } on each line
                   - Missing \\end{...}: check every \\begin has a matching \\end
                   - Undefined control sequence: replace with a safe equivalent
                   - Unicode in source: replace with LaTeX escape (é → \\'e, — → ---)
                   - Missing package: add to preamble (only Tectonic-safe packages)
                   - Runaway argument: check for unescaped & %% $ # _ ^
                4. Return the COMPLETE fixed LaTeX file — not just the fixed section
                """;
    }

    private String aggressiveFixStrategy() {
        return """
                This is the FINAL fix attempt (attempt 3). Two previous attempts failed.
                You are now authorised to simplify problematic constructs to ensure compilation.
                Rules:
                1. Fix ALL syntax errors identified in the error log
                2. You MAY simplify constructs that repeatedly cause errors:
                   - Complex tabularx → simple itemize or plain text
                   - Problematic special characters → safe LaTeX equivalents
                   - Broken custom commands → inline the formatting directly
                   - Problematic package → remove and replicate effect with safe packages
                3. You MUST NOT:
                   - Change, remove, or reorder any content (text, bullets, facts, dates)
                   - Drop any section (see section preservation constraints below)
                   - Change section order
                   - Remove any bold formatting from keywords
                4. The goal: a simpler but complete, correct, compilable resume
                5. Return the COMPLETE fixed LaTeX file
                """;
    }

    private String buildSectionConstraints(ResumePlan plan) {
        if (plan == null || plan.getRankedSections() == null) {
            return "Preserve all sections present in the broken source.";
        }
        return """
                Section order must remain EXACTLY:
                %s

                All %d of these sections must be present in your output:
                %s

                Do NOT reorder, merge, rename, or remove any section while fixing syntax.
                """.formatted(
                formatSectionList(plan.getRankedSections()),
                plan.getDiscoveredSections().size(),
                String.join(", ", plan.getDiscoveredSections())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt 4: ATS scoring — unchanged, called AFTER successful compile
    // ─────────────────────────────────────────────────────────────────────────

    public String atsScorePrompt(String extractedOriginalText,
                                 String shapedLatex,
                                 String roleLabel) {
        return """
                Score these two resumes for ATS compatibility against the role: "%s"

                ══ ORIGINAL RESUME TEXT ══
                %s

                ══ SHAPED LATEX (reshaped version) ══
                %s

                Score both versions. Consider: keyword density, strong action verbs,
                quantified metrics, relevant skills, section completeness.

                Return ONLY this JSON — no markdown fences:
                {
                  "atsScoreBefore": 0-100,
                  "atsScoreAfter": 0-100
                }
                """.formatted(roleLabel, extractedOriginalText, shapedLatex);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Formats ranked sections as a numbered list for the prompt.
     * e.g. "1. Summary\n2. Projects\n3. Technical Skills\n..."
     */
    private String formatSectionList(List<String> sections) {
        if (sections == null || sections.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            sb.append(i + 1).append(". ").append(sections.get(i)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Formats a keyword list as a comma-separated string.
     * Returns "(none)" if empty so the prompt is never blank.
     */
    private String formatKeywordList(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return "(none)";
        return String.join(", ", keywords);
    }

    /**
     * Builds the effective JD for reshape and planner prompts.
     * If user provided a JD it is merged 50/50 with the system default.
     */
    private String buildEffectiveJD(String roleLabel, String userJD) {
        String systemJD   = JobDescriptions.getDefault(roleLabel);
        boolean hasUserJD = userJD != null && !userJD.isBlank();

        if (!hasUserJD) return systemJD;

        return """
                [SYSTEM DEFAULT JD — weight 50%%]
                %s

                [USER-PROVIDED JD — weight 50%%]
                %s

                Merge both equally: union all skills/keywords,
                prefer user JD for seniority/tone conflicts.
                """.formatted(systemJD.strip(), userJD.strip());
    }
}