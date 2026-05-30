package com.resumeshaper.latex;

import com.resumeshaper.llm.JobDescriptions;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LatexPromptBuilder {

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 1 — Planner
    // ─────────────────────────────────────────────────────────────────────────

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
                                     MAXIMUM 5 injectable keywords — be selective, not exhaustive
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
    // PHASE 2a — Content Rewriter
    // ─────────────────────────────────────────────────────────────────────────

    public String contentRewriteSystemInstruction() {
        return """
                You are an expert resume writer and ATS optimization specialist.
                Your job is to REWRITE resume content into a clean structured format.

                You do NOT write LaTeX. You do NOT add any markup or formatting codes.
                You ONLY improve wording, inject keywords, strengthen bullets, and
                produce structured plain-text JSON that a LaTeX generator will format.

                ══ REWRITE RULES ══
                1. Every bullet must start with a strong action verb
                   (Built, Engineered, Designed, Led, Optimised, Deployed, Developed, etc.)
                2. Add or preserve metrics wherever they exist in the original
                3. Keep ALL content — remove nothing, add no new bullet points
                4. Inject injectable keywords naturally into EXISTING bullets only
                   - Max 2 occurrences per injectable keyword across the entire document
                   - Only inject if the bullet already implies the skill
                   - If injection makes a bullet sound like a keyword list, skip it
                5. Do NOT use LaTeX syntax, HTML, markdown, or any special markup
                6. Preserve exact section names from the discovered sections list
                7. For entries with multiple sub-items (e.g. job roles, projects):
                   - "heading" = the title line (role, company, dates OR project name, tech, dates)
                   - "bullets" = the bullet points under that heading

                ══ CAREER SWITCHER RULE ══
                If profileType is CAREER_SWITCHER:
                - Lead every bullet with the transferable skill or outcome
                - De-emphasise industry-specific jargon that won't land for the target role
                - Reframe experience toward where the candidate is going, not where they came from
                """;
    }

    public String contentRewritePrompt(String resumeText,
                                       ResumePlan plan,
                                       String roleLabel,
                                       String jdText) {
        String effectiveJD = buildEffectiveJD(roleLabel, jdText);
        String careerBlock = buildCareerSwitcherBlock(plan.getProfileType(), roleLabel);

        return """
                Rewrite the resume content for the role: "%s"
                Profile type: %s

                %s

                ══ RESUME TEXT ══
                (Lines prefixed [HEADING] = section titles, [BOLD] = important labels,
                 [BULLET] = bullet points. Plain lines = body text.)

                %s

                ══ TARGET JOB DESCRIPTION ══
                %s

                ══ SECTION ORDER — USE THIS EXACT ORDER IN YOUR OUTPUT ══
                %s

                ══ KEYWORD INJECTION RULES ══
                Inject these into existing bullets naturally (max 2x each across document):
                Injectable keywords: %s

                These keywords already appear in the resume — ensure they are present
                in the relevant bullets (do NOT add new bullets to include them):
                Existing keywords to preserve: %s

                ══ YOUR TASK ══
                1. Rewrite every bullet: action verb + what was built/done + outcome/metric
                2. Inject injectable keywords naturally — quality over keyword density
                3. Preserve ALL original content — facts, dates, names, metrics, education
                4. Use section names EXACTLY as listed in the section order above
                5. Output sections in the EXACT order listed above

                ══ OUTPUT FORMAT ══
                Return ONLY this JSON — no markdown fences, no LaTeX, no extra fields:
                {
                  "header": {
                    "name": "Candidate Full Name",
                    "email": "email@example.com",
                    "phone": "+91-XXXXXXXXXX",
                    "links": ["linkedin.com/in/handle", "github.com/handle"]
                  },
                  "sections": [
                    {
                      "title": "exact section name as discovered",
                      "entries": [
                        {
                          "heading": "Entry title line (role/company/dates OR project name/tech/dates)",
                          "bullets": [
                            "Rewritten bullet starting with action verb",
                            "Another bullet with metric or outcome"
                          ]
                        }
                      ]
                    }
                  ],
                  "changesLog": ["Injected containerization into Projects bullet", "Strengthened 3 weak verbs"]
                }

                IMPORTANT: sections in your JSON must follow the section order above EXACTLY.
                Every section from the discovered list must appear in your output.
                Required sections (%d total): %s
                """.formatted(
                roleLabel,
                plan.getProfileType(),
                careerBlock,
                resumeText,
                effectiveJD,
                formatSectionList(plan.getRankedSections()),
                formatKeywordList(plan.getInjectableKeywords()),
                formatKeywordList(plan.getMustBoldKeywords()),
                plan.getDiscoveredSections().size(),
                String.join(", ", plan.getDiscoveredSections())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 2b — LaTeX system instruction + generator
    // ─────────────────────────────────────────────────────────────────────────

    public String latexSystemInstruction() {
        return """
                You are an expert LaTeX resume engineer.
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

                ══ FORMATTING RULES ══
                1. Compress spacing:
                   \\titlespacing*{\\section}{0pt}{6pt}{3pt}
                   itemize: leftmargin=14pt, noitemsep, topsep=1pt
                2. Header: name in large font, contact info on one line with | separators
                3. Bold keywords and metrics with \\textbf{} as instructed in each prompt
                4. Bullet points come from the structured JSON — do NOT rephrase or reorder them
                5. Escape ALL special LaTeX characters in content:
                   & → \\& | %% → \\%% | $ → \\$ | # → \\# | _ → \\_ | ^ → \\^{}
                   ~ → \\textasciitilde{} | < → \\textless{} | > → \\textgreater{}

                ══ SECTION ORDER IS DYNAMIC — NEVER HARDCODE IT ══
                The section order is provided in every prompt. Follow it precisely.
                """;
    }

    public String latexGeneratePrompt(String structuredContentJson,
                                      ResumePlan plan,
                                      String roleLabel) {
        return """
                Convert this structured resume content into valid Tectonic-compilable LaTeX.
                Role: "%s" | Profile: %s

                ══ STRUCTURED CONTENT (JSON) ══
                This is the complete resume content. Render every section and every bullet
                exactly as given — do NOT rephrase, reorder bullets, or drop any entry.

                %s

                ══ SECTION ORDER — FOLLOW EXACTLY, DO NOT DEVIATE ══
                %s

                ══ BOLD THESE KEYWORDS with \\textbf{} ══
                Wrap each of these in \\textbf{} wherever they appear in the content.
                Also bold all numbers, percentages, and metrics automatically.
                Keywords: %s

                ══ KEYWORD FREQUENCY CAP ══
                Each keyword may be wrapped in \\textbf{} AT MOST 3 times total.
                After the 3rd occurrence, leave it unbolded.

                ══ PRESERVATION AUDIT — VERIFY BEFORE OUTPUTTING ══
                Your LaTeX output MUST contain a \\section{} block for EVERY one of these sections.
                Missing even one section is a critical failure.
                Required sections (%d total): %s

                ══ YOUR TASK ══
                1. Build a complete \\documentclass..\\end{document} LaTeX file
                2. Follow section order above EXACTLY — Header first, then ranked sections
                3. Apply ALL formatting rules from your system instruction
                4. Before finalising, count your \\section{} blocks — must equal %d
                5. Every \\begin{itemize} must have [leftmargin=14pt,noitemsep,topsep=1pt]

                ══ OUTPUT FORMAT ══
                Output ONLY the LaTeX source between these exact delimiters.
                No explanation, no markdown fences, nothing before <<<LATEX>>> or after <<<END_LATEX>>>.

                <<<LATEX>>>
                \\documentclass[10pt,a4paper]{article}
                ... complete LaTeX source ...
                \\end{document}
                <<<END_LATEX>>>
                """.formatted(
                roleLabel,
                plan.getProfileType(),
                structuredContentJson,
                formatSectionList(plan.getRankedSections()),
                formatKeywordList(plan.getMustBoldKeywords()),
                plan.getDiscoveredSections().size(),
                String.join(", ", plan.getDiscoveredSections()),
                plan.getDiscoveredSections().size()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fix loop
    // ─────────────────────────────────────────────────────────────────────────

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
    // STAGE 7 — Fit to 1 page prompts
    // ─────────────────────────────────────────────────────────────────────────
    public String spacingExpandPrompt(String latex) {
        return """
                The compiled LaTeX resume is shorter than one full page.
                Adjust spacing ONLY to fill the page better. Do NOT change any content.

                ══ ALLOWED CHANGES ══
                - Increase \\vspace{} between sections (max 8pt)
                - Increase \\titlespacing* top/bottom values slightly
                - Increase itemize topsep from 1pt to 3pt
                - Add \\medskip between entries if space allows
                - Increase geometry top/bottom margins slightly (max 1in total)

                ══ NOT ALLOWED ══
                - Change any text, bullets, keywords, or facts
                - Add new content or sections
                - Change font sizes
                - Change section order

                ══ LATEX SOURCE ══
                %s

                ══ OUTPUT FORMAT ══
                Output ONLY between delimiters — no explanation:
                <<<LATEX>>>
                ... adjusted latex ...
                <<<END_LATEX>>>
                """.formatted(latex);
    }

    public String sizingCompressPrompt(String latex, double pageCount) {
        return """
                The compiled LaTeX resume is %.1f pages. It MUST fit on exactly 1 page.
                Compress sizing and spacing to fit. Do NOT remove any content.

                ══ ALLOWED CHANGES (apply ALL that are needed) ══
                - Reduce \\documentclass font size to 9pt or 9.5pt
                - Reduce geometry margins (minimum: top=0.4in, bottom=0.4in, left=0.5in, right=0.5in)
                - Reduce \\titlespacing* to {0pt}{3pt}{1pt}
                - Reduce itemize topsep to 0pt, noitemsep
                - Reduce \\vspace{} between sections to 2pt
                - Add \\setlength{\\parskip}{0pt} to kill paragraph spacing

                ══ NOT ALLOWED ══
                - Remove any bullet points, sections, or text
                - Change any content, keywords, or facts
                - Use font sizes below 9pt
                - Use margins below 0.4in

                ══ LATEX SOURCE ══
                %s

                ══ OUTPUT FORMAT ══
                <<<LATEX>>>
                ... compressed latex ...
                <<<END_LATEX>>>
                """.formatted(pageCount, latex);
    }

    public String contentTrimPrompt(String latex, double pageCount) {
        return """
                The compiled LaTeX resume is %.1f pages. Spacing and sizing fixes were insufficient.
                You MUST trim content to fit exactly 1 page. This is the last resort.

                ══ TRIM PRIORITY — remove in this order ══
                1. Hobbies, Interests, Extra Curriculars section entirely (lowest ATS value)
                2. "References available on request" lines
                3. Objective/Summary section if it is generic (under 2 strong sentences)
                4. Duplicate or near-duplicate bullets within the same entry
                5. The WEAKEST bullet in each section — least specific, no metric, no impact
                   (remove at most 1 bullet per section)
                6. Reduce any entry with 4+ bullets to 3 bullets (keep the strongest 3)

                ══ NEVER REMOVE ══
                - Education section
                - Any bullet with a specific metric (%%, $, ms, req/sec, users, etc.)
                - Any bullet containing a bold keyword
                - Contact information or dates

                ══ AFTER TRIMMING — also apply maximum spacing compression ══
                - Font: 9pt, margins: 0.45in all sides
                - titlespacing: {0pt}{2pt}{1pt}, topsep: 0pt, parskip: 0pt

                ══ LATEX SOURCE ══
                %s

                ══ OUTPUT FORMAT ══
                <<<LATEX>>>
                ... trimmed and compressed latex ...
                <<<END_LATEX>>>
                """.formatted(pageCount, latex);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ATS scoring
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

                Penalise heavily for:
                - Same keyword repeated 3+ times (-5 pts each offence)
                - Bullets that don't start with action verbs
                - Missing metrics on experience bullets
                - Capitalisation errors mid-sentence

                Return ONLY this JSON — no markdown fences:
                {
                  "atsScoreBefore": 0-100,
                  "atsScoreAfter": 0-100
                }
                """.formatted(roleLabel, extractedOriginalText, shapedLatex);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CAREER_SWITCHER block
    // ─────────────────────────────────────────────────────────────────────────

    private String buildCareerSwitcherBlock(String profileType, String roleLabel) {
        if (!"CAREER_SWITCHER".equals(profileType)) return "";

        return """
                ══ CAREER SWITCHER STRATEGY — THIS OVERRIDES DEFAULT REWRITE BEHAVIOUR ══
                This candidate is transitioning careers. Their background does not directly
                match the target role: "%s". Apply these rules with high priority:

                1. SUMMARY SECTION — rewrite completely:
                   - Lead with transferable skills that map directly to the target role
                   - Frame their narrative toward where they are going, not where they came from
                   - Do NOT mention "career change" or "transitioning" explicitly
                   - Maximum 3 sentences — punchy, confident, forward-looking

                2. EXPERIENCE BULLETS — reframe each bullet:
                   - Lead with the transferable skill or outcome, not the job title or industry
                   - De-emphasise industry-specific jargon that won't resonate with target role

                3. SKILLS SECTION — reorder:
                   - Put transferable and target-role-relevant skills first
                   - Move domain-specific skills that don't transfer to the bottom

                4. INJECTABLE KEYWORDS — prioritise aggressively:
                   - For career switchers, injectable keywords bridge the gap
                   - Inject all provided injectable keywords if ANY basis exists in the resume
                   - Still respect the 2-occurrence frequency cap per keyword

                """.formatted(roleLabel);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String formatSectionList(List<String> sections) {
        if (sections == null || sections.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            sb.append(i + 1).append(". ").append(sections.get(i)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private String formatKeywordList(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return "(none)";
        return String.join(", ", keywords);
    }

    private String buildEffectiveJD(String roleLabel, String userJD) {
        String  systemJD  = JobDescriptions.getDefault(roleLabel);
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