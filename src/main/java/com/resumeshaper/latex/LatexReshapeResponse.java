package com.resumeshaper.latex;

import com.resumeshaper.ats.ATSReport;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response returned to the frontend after the full LaTeX reshape pipeline completes.
 *
 * FIX 5: Added contentFlags, atsGapKeywords, profileTypeDetected, atsReport
 * — all were saved to DB but never sent to frontend. Now surfaced.
 *
 * Frontend behaviour:
 *  1. Paste shapedLatex into the left editor panel
 *  2. Render pdfUrl in the right panel — already compiled, loads instantly
 *  3. Show atsScoreBefore → atsScoreAfter delta
 *  4. Show contentFlags as "Things only YOU can fix" in the side panel
 *  5. Show atsGapKeywords as "Add these manually" suggestions
 *  6. Show atsReport breakdown (keyword/section/format/verb scores)
 */
@Getter
@Builder
public class LatexReshapeResponse {

    /** Job ID — used for polling and re-reshape */
    private UUID jobId;

    /**
     * Final reshaped + validated LaTeX source.
     * Guaranteed to compile via Tectonic.
     */
    private String shapedLatex;

    /**
     * Pre-signed S3 URL for the already-compiled PDF.
     * Valid for 60 minutes. Frontend renders directly — no re-compile needed.
     */
    private String pdfUrl;

    /** ATS score of original resume (rules-based, deterministic). 0-100. */
    private Integer atsScoreBefore;

    /** ATS score of reshaped resume (LLM adversarial scorer). 0-100. */
    private Integer atsScoreAfter;

    /**
     * Human-readable list of changes made by the LLM.
     * Shown in the editor sidebar changesLog section.
     */
    private List<String> changesLog;

    /** How many Tectonic compile attempts were needed (1-3). */
    private int compileAttempts;

    // ── FIX 5: New fields surfaced from DB ────────────────────────────────

    /**
     * Career stage detected by the planner.
     * Values: STUDENT_FRESHER | EARLY_CAREER | MID_SENIOR | CAREER_SWITCHER
     * Frontend can show a contextual badge or message based on this.
     */
    private String profileTypeDetected;

    /**
     * JD keywords completely absent from the resume with no injectable basis.
     * Frontend shows these as: "Add these keywords manually to boost your score"
     * Example: ["Kubernetes", "CI/CD", "Terraform"]
     */
    private List<String> atsGapKeywords;

    /**
     * Per-bullet improvement suggestions from the planner.
     * Each entry: { "section": "...", "bullet": "...", "suggestion": "..." }
     * Frontend shows as: "Things only YOU can fix" panel.
     * Non-blocking — purely advisory.
     */
    private List<Map<String, String>> contentFlags;

    /**
     * Detailed rules-based ATS breakdown for the original resume.
     * Breakdown: keywordScore, sectionScore, formatScore, verbScore.
     * Frontend can show a score breakdown chart in the side panel.
     */
    private ATSReport atsReport;
}