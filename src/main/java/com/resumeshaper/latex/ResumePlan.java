package com.resumeshaper.latex;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Structured output from the planner LLM call (Phase 1).
 *
 * Produced by LatexPromptBuilder.resumePlannerPrompt() and parsed in
 * LatexReshapeOrchestrator before the reshape call (Phase 2).
 *
 * profileType     — candidate career stage, drives section weight defaults
 * discoveredSections — EVERY section found in the raw resume, exact names,
 *                      none may be dropped during reshape
 * rankedSections  — same list reordered for ATS impact given role + profileType
 * mustBoldKeywords   — present in both resume and JD → bold with \textbf{}
 * injectableKeywords — missing from resume but justified by existing content
 *                      → inject naturally into relevant bullets
 * atsGapKeywords  — missing from resume and NOT injectable (no basis)
 *                   → surface to frontend as "add these manually"
 * contentFlags    — per-bullet suggestions for metrics / stronger verbs
 *                   → stored in DB, optionally shown in UI
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResumePlan {

    /**
     * STUDENT_FRESHER  — in college / no real work experience
     * EARLY_CAREER     — 0–2 years experience
     * MID_SENIOR       — 3+ years experience
     * CAREER_SWITCHER  — background mismatches target role
     */
    private String profileType;

    /**
     * Every section discovered in the raw resume.
     * Names preserved exactly as they appear (e.g. "Positions of Responsibility").
     * Reshape prompt uses this list for preservation audit.
     */
    private List<String> discoveredSections;

    /**
     * Same sections as discoveredSections, reordered by ATS priority
     * for the given roleLabel + roleCategory + profileType.
     * No section may be added or removed vs discoveredSections.
     */
    private List<String> rankedSections;

    /**
     * Keywords that appear in both the resume content and the JD.
     * These should be wrapped in \textbf{} in the LaTeX output.
     */
    private List<String> mustBoldKeywords;

    /**
     * Keywords missing from resume but injectable because the candidate's
     * existing content justifies them (e.g. they use Docker → "containerization"
     * can be injected). NEVER fabricate experience.
     */
    private List<String> injectableKeywords;

    /**
     * Keywords in the JD but completely absent from the resume with no
     * justifiable basis for injection. Saved to DB and surfaced to frontend
     * as manual improvement suggestions.
     */
    private List<String> atsGapKeywords;

    /**
     * Per-bullet content improvement flags.
     * Non-blocking — stored for UI display, never affect LaTeX compilation.
     */
    private List<ContentFlag> contentFlags;

    // ─────────────────────────────────────────────────────────────────────────

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ContentFlag {
        /** Section name the flagged bullet belongs to (e.g. "Projects") */
        private String section;
        /** Short excerpt of the bullet being flagged */
        private String bullet;
        /** Concrete suggestion e.g. "Add metric — N req/sec or user count" */
        private String suggestion;
    }
}