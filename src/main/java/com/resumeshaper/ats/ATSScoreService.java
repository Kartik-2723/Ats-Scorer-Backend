package com.resumeshaper.ats;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Rule-based ATS scorer that runs client-side (no LLM cost).
 * Used as a fast sanity-check score; the LLM also produces a richer score.
 *
 * Final score = keywordWeight * keywordScore
 *             + sectionWeight * sectionScore
 *             + formatWeight  * formatScore
 */
@Slf4j
@Service
public class ATSScoreService {

    // Weights (must sum to 1.0)
    private static final double KEYWORD_WEIGHT  = 0.50;
    private static final double SECTION_WEIGHT  = 0.30;
    private static final double FORMAT_WEIGHT   = 0.20;

    // Sections that boost ATS score when present
    private static final List<String> CORE_SECTIONS = List.of(
            "summary", "skills", "experience", "education"
    );
    private static final List<String> BONUS_SECTIONS = List.of(
            "certifications", "projects", "contact"
    );

    // Format red flags (penalise if found)
    private static final List<String> FORMAT_PENALTIES = List.of(
            "table", "column", "header", "footer", "graphic", "image", "chart"
    );

    /**
     * Score a shaped resume JSON against a JD analysis JSON.
     *
     * @param shapedResume  map produced by the LLM reshape stage
     * @param jdAnalysis    map produced by the LLM JD analysis stage
     * @return ATSReport with detailed breakdown
     */
    public ATSReport score(Map<String, Object> shapedResume,
                            Map<String, Object> jdAnalysis) {

        // ── Keyword score ─────────────────────────────────────
        List<String> jdKeywords   = getList(jdAnalysis, "keywords");
        List<String> jdSkills     = getList(jdAnalysis, "requiredSkills");
        Set<String> allKeywords   = new HashSet<>();
        allKeywords.addAll(jdKeywords);
        allKeywords.addAll(jdSkills);

        String resumeText = extractText(shapedResume).toLowerCase();
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String kw : allKeywords) {
            if (resumeText.contains(kw.toLowerCase())) {
                matched.add(kw);
            } else {
                missing.add(kw);
            }
        }

        double keywordScore = allKeywords.isEmpty() ? 80 :
                (double) matched.size() / allKeywords.size() * 100;

        // ── Section score ─────────────────────────────────────
        int sectionScore = 0;
        List<String> presentSections = new ArrayList<>();

        for (String section : CORE_SECTIONS) {
            if (hasSection(shapedResume, section)) {
                sectionScore += 20;
                presentSections.add(section);
            }
        }
        for (String section : BONUS_SECTIONS) {
            if (hasSection(shapedResume, section)) {
                sectionScore = Math.min(100, sectionScore + 5);
                presentSections.add(section);
            }
        }

        // ── Format score ──────────────────────────────────────
        int formatScore = 100;
        List<String> formatIssues = new ArrayList<>();

        for (String penalty : FORMAT_PENALTIES) {
            if (resumeText.contains(penalty)) {
                formatScore -= 10;
                formatIssues.add("Potential ATS issue: '" + penalty + "' detected");
            }
        }
        formatScore = Math.max(0, formatScore);

        // Word count check (ideal: 400–700 words for 1 page)
        int wordCount = resumeText.split("\\s+").length;
        if (wordCount < 200) {
            formatScore -= 10;
            formatIssues.add("Resume may be too sparse (" + wordCount + " words)");
        } else if (wordCount > 900) {
            formatScore -= 10;
            formatIssues.add("Resume may exceed one page (" + wordCount + " words)");
        }
        formatScore = Math.max(0, formatScore);

        // ── Overall ───────────────────────────────────────────
        int overall = (int) Math.round(
                keywordScore * KEYWORD_WEIGHT +
                sectionScore * SECTION_WEIGHT +
                formatScore  * FORMAT_WEIGHT
        );

        return ATSReport.builder()
                .overallScore(overall)
                .keywordScore((int) Math.round(keywordScore))
                .sectionScore(sectionScore)
                .formatScore(formatScore)
                .matchedKeywords(matched)
                .missingKeywords(missing)
                .presentSections(presentSections)
                .formatIssues(formatIssues)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> getList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private boolean hasSection(Map<String, Object> resume, String section) {
        Object v = resume.get(section);
        if (v == null) return false;
        if (v instanceof String s)     return !s.isBlank();
        if (v instanceof List<?> l)    return !l.isEmpty();
        if (v instanceof Map<?,?> m)   return !m.isEmpty();
        return true;
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> resume) {
        StringBuilder sb = new StringBuilder();
        for (Object v : resume.values()) {
            if (v instanceof String s)   sb.append(s).append(" ");
            else if (v instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String si) sb.append(si).append(" ");
                    else if (item instanceof Map<?,?> m) {
                        sb.append(extractText((Map<String, Object>) m));
                    }
                }
            } else if (v instanceof Map<?,?> m) {
                sb.append(extractText((Map<String, Object>) m));
            }
        }
        return sb.toString();
    }
}
