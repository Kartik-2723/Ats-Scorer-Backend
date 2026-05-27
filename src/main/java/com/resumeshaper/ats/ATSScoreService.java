package com.resumeshaper.ats;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Rule-based ATS scorer — deterministic, zero LLM cost.
 *
 * FIX 4: Refactored to accept plain text strings instead of Map<String, Object>.
 * The LaTeX pipeline never produces a parsed JSON map — it works with raw text.
 *
 * Used to produce atsScoreBefore (original resume text, rules-based).
 * The LLM adversarial scorer produces atsScoreAfter separately.
 *
 * Final score = keywordWeight * keywordScore
 *             + sectionWeight * sectionScore
 *             + formatWeight  * formatScore
 *             + verbWeight    * verbScore       ← new dimension
 */
@Slf4j
@Service
public class ATSScoreService {

    // Weights (must sum to 1.0)
    private static final double KEYWORD_WEIGHT = 0.40;
    private static final double SECTION_WEIGHT = 0.25;
    private static final double FORMAT_WEIGHT  = 0.20;
    private static final double VERB_WEIGHT    = 0.15;   // new: action verb strength

    // Core sections — presence boosts score
    private static final List<String> CORE_SECTIONS = List.of(
            "summary", "objective", "skills", "experience", "education"
    );
    private static final List<String> BONUS_SECTIONS = List.of(
            "certifications", "projects", "contact", "achievements",
            "leadership", "publications"
    );

    // Strong action verbs — used in verb score
    private static final Set<String> STRONG_VERBS = Set.of(
            "led", "built", "developed", "engineered", "architected", "designed",
            "launched", "delivered", "managed", "owned", "drove", "created",
            "implemented", "optimised", "optimized", "scaled", "reduced", "increased",
            "improved", "automated", "deployed", "migrated", "integrated", "spearheaded",
            "established", "streamlined", "negotiated", "mentored", "collaborated",
            "generated", "achieved", "exceeded", "accelerated", "transformed"
    );

    // Weak verb patterns — penalised
    private static final List<String> WEAK_VERB_PATTERNS = List.of(
            "was responsible for", "helped with", "assisted in",
            "worked on", "involved in", "participated in",
            "responsible for", "duties included"
    );

    // Format red flags
    private static final List<String> FORMAT_PENALTIES = List.of(
            "\\begin{table}", "\\begin{tabular}", "\\includegraphics",
            "header", "footer"
    );

    // Regex to find numbers/metrics in text
    private static final Pattern METRIC_PATTERN =
            Pattern.compile("\\d+[%xX]?|\\$\\d+|\\d+[kKmMbB]");

    /**
     * Score a plain-text resume against a list of JD keywords.
     *
     * FIX 4: accepts plain text — works for both PDF-extracted text
     * and LaTeX-stripped text. No Map dependency.
     *
     * @param resumeText  plain text of the resume (LaTeX stripped or PDF extracted)
     * @param jdKeywords  keywords from the JD analysis (from planner's atsGapKeywords
     *                    + mustBoldKeywords combined)
     * @return ATSReport with detailed breakdown
     */
    public ATSReport score(String resumeText, List<String> jdKeywords) {
        if (resumeText == null || resumeText.isBlank()) {
            log.warn("ATSScoreService received blank resumeText — returning zero score");
            return ATSReport.builder()
                    .overallScore(0).keywordScore(0).sectionScore(0)
                    .formatScore(0).verbScore(0)
                    .matchedKeywords(List.of()).missingKeywords(new ArrayList<>(jdKeywords))
                    .presentSections(List.of()).formatIssues(List.of())
                    .build();
        }

        String lower = resumeText.toLowerCase();

        // ── Keyword score ─────────────────────────────────────────────────────
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String kw : jdKeywords) {
            if (kw != null && !kw.isBlank()) {
                if (lower.contains(kw.toLowerCase())) {
                    matched.add(kw);
                } else {
                    missing.add(kw);
                }
            }
        }

        double keywordScore = jdKeywords.isEmpty() ? 75.0 :
                (double) matched.size() / jdKeywords.size() * 100;

        // ── Section score ─────────────────────────────────────────────────────
        int sectionScore = 0;
        List<String> presentSections = new ArrayList<>();

        for (String section : CORE_SECTIONS) {
            if (lower.contains(section)) {
                sectionScore += 16;   // 5 core × 16 = 80 max from core
                presentSections.add(section);
            }
        }
        for (String section : BONUS_SECTIONS) {
            if (lower.contains(section)) {
                sectionScore = Math.min(100, sectionScore + 4);
                presentSections.add(section);
            }
        }

        // ── Format score ──────────────────────────────────────────────────────
        int formatScore = 100;
        List<String> formatIssues = new ArrayList<>();

        for (String penalty : FORMAT_PENALTIES) {
            if (lower.contains(penalty.toLowerCase())) {
                formatScore -= 10;
                formatIssues.add("Potential ATS issue: '" + penalty + "' detected");
            }
        }

        // Word count check (ideal: 300–800 words for 1 page)
        int wordCount = lower.split("\\s+").length;
        if (wordCount < 200) {
            formatScore -= 15;
            formatIssues.add("Resume may be too sparse (" + wordCount + " words)");
        } else if (wordCount > 1000) {
            formatScore -= 10;
            formatIssues.add("Resume may exceed one page (" + wordCount + " words)");
        }

        // Metric density — at least 3 numbers/metrics is a good sign
        long metricCount = METRIC_PATTERN.matcher(resumeText).results().count();
        if (metricCount < 3) {
            formatScore -= 10;
            formatIssues.add("Few quantified metrics detected (" + metricCount + ") — add numbers");
        }

        formatScore = Math.max(0, Math.min(100, formatScore));

        // ── Verb score ────────────────────────────────────────────────────────
        int verbScore = 100;

        // Penalise weak verb patterns
        for (String weak : WEAK_VERB_PATTERNS) {
            if (lower.contains(weak)) {
                verbScore -= 12;
                formatIssues.add("Weak phrasing detected: \"" + weak + "\"");
            }
        }

        // Reward strong action verbs (presence of at least 5 unique strong verbs)
        long strongVerbCount = STRONG_VERBS.stream()
                .filter(v -> lower.contains(" " + v + " ") || lower.contains("\n" + v + " "))
                .count();
        if (strongVerbCount < 3) {
            verbScore -= 20;
            formatIssues.add("Few strong action verbs detected — strengthen bullet openings");
        } else if (strongVerbCount >= 7) {
            verbScore = Math.min(100, verbScore + 5);  // small reward for strong verb variety
        }

        verbScore = Math.max(0, Math.min(100, verbScore));

        // ── Overall ───────────────────────────────────────────────────────────
        int overall = (int) Math.round(
                keywordScore * KEYWORD_WEIGHT +
                        sectionScore * SECTION_WEIGHT +
                        formatScore  * FORMAT_WEIGHT  +
                        verbScore    * VERB_WEIGHT
        );
        overall = Math.max(0, Math.min(100, overall));

        log.debug("ATSScoreService: overall={} keyword={} section={} format={} verb={} " +
                        "matched={} missing={} metrics={}",
                overall, (int) keywordScore, sectionScore, formatScore, verbScore,
                matched.size(), missing.size(), metricCount);

        return ATSReport.builder()
                .overallScore(overall)
                .keywordScore((int) Math.round(keywordScore))
                .sectionScore(sectionScore)
                .formatScore(formatScore)
                .verbScore(verbScore)
                .matchedKeywords(matched)
                .missingKeywords(missing)
                .presentSections(presentSections)
                .formatIssues(formatIssues)
                .build();
    }

    // ── Utility: strip LaTeX commands from source to get scoreable plain text ──

    /**
     * Strips LaTeX markup from source leaving only human-readable content.
     * Used when scoring a LaTeX file: stripLatex(shapedLatex) → plain text → score().
     */
    public static String stripLatex(String latex) {
        if (latex == null) return "";
        return latex
                // Remove preamble (everything before \begin{document})
                .replaceAll("(?s).*?\\\\begin\\{document\\}", "")
                // Remove \end{document}
                .replace("\\end{document}", "")
                // Remove common commands but keep their arguments
                .replaceAll("\\\\textbf\\{([^}]*)\\}", "$1")
                .replaceAll("\\\\textit\\{([^}]*)\\}", "$1")
                .replaceAll("\\\\emph\\{([^}]*)\\}", "$1")
                .replaceAll("\\\\href\\{[^}]*\\}\\{([^}]*)\\}", "$1")
                .replaceAll("\\\\section\\*?\\{([^}]*)\\}", "\n$1\n")
                .replaceAll("\\\\subsection\\*?\\{([^}]*)\\}", "\n$1\n")
                // Remove remaining commands
                .replaceAll("\\\\[a-zA-Z]+\\*?(\\{[^}]*\\})*", " ")
                // Remove LaTeX special chars
                .replaceAll("[{}\\[\\]]", " ")
                // Collapse whitespace
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}