package com.resumeshaper.latex;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * Response returned to the frontend after the full latex reshape pipeline completes.
 *
 * Frontend behaviour:
 *  1. Paste shapedLatex into the left editor panel
 *  2. Render pdfUrl in the right panel — PDF is already compiled + cached in Redis,
 *     so it loads instantly (no second compile needed)
 *  3. Show atsScoreBefore → atsScoreAfter delta to the user
 *  4. Re-compile button in editor → POST /api/latex/compile (existing endpoint)
 *     which hits Redis cache for unchanged latex or re-runs Tectonic for edits
 */
@Getter
@Builder
public class LatexReshapeResponse {

    /** Job ID — used for polling (/api/latex/reshape/{jobId}/status) */
    private UUID jobId;

    /**
     * Final reshaped + validated LaTeX source.
     * Guaranteed to compile via Tectonic (compiled once already in backend).
     */
    private String shapedLatex;

    /**
     * Pre-signed S3 URL for the already-compiled PDF.
     * Valid for 60 minutes (configured in AppProperties).
     * Frontend should use this directly — no re-compile needed on first load.
     */
    private String pdfUrl;

    /** ATS score of the original resume (before reshape). 0-100. */
    private Integer atsScoreBefore;

    /** ATS score of the reshaped resume (after reshape). 0-100. */
    private Integer atsScoreAfter;

    /**
     * Human-readable list of changes made by the LLM.
     * Examples: "Bolded all metrics", "Reordered: Projects moved above Skills".
     * Shown in the editor sidebar.
     */
    private List<String> changesLog;

    /** How many Tectonic compile attempts were needed (1-3). For transparency. */
    private int compileAttempts;
}