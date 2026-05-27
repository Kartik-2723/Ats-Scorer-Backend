package com.resumeshaper.resume;

/**
 * Pipeline status for both the old JSON-reshape flow and the new LaTeX flow.
 *
 * Old flow:  PENDING → PARSING → ANALYZING → RESHAPING → SCORING → DONE | FAILED
 *
 * New LaTeX flow:
 *   PDF input:   PENDING → CONVERTING → RESHAPING_LATEX → COMPILING → FITTING_PAGE → DONE | FAILED
 *   LaTeX input: PENDING → RESHAPING_LATEX → COMPILING → FITTING_PAGE → DONE | FAILED
 *   On compile error: COMPILING → FIX_RETRY → COMPILING → FITTING_PAGE → DONE | FAILED
 */
public enum JobStatus {

    // ── Shared ──────────────────────────────────────────────
    PENDING,
    DONE,
    FAILED,

    // ── Old JSON-reshape pipeline ────────────────────────────
    PARSING,
    ANALYZING,
    RESHAPING,
    SCORING,

    // ── New LaTeX pipeline ───────────────────────────────────
    /** PDF text extraction via PDFBox (only for PDF input) */
    CONVERTING,

    /** LLM call: PDF-text → LaTeX + reshape, OR existing LaTeX → reshape */
    RESHAPING_LATEX,

    /** Tectonic compile attempt (up to 3 total) */
    COMPILING,

    /** LLM fix-on-error: syntax fix only, no content changes */
    FIX_RETRY,

    /** Fit resume to exactly 1 page: spacing → sizing → content trim */
    FITTING_PAGE
}