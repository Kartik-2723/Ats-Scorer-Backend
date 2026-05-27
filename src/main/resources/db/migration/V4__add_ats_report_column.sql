-- V4__add_ats_report_column.sql
-- FIX 4: Adds ats_report JSONB column to store rules-based ATS breakdown
-- for the original resume. Surfaced to frontend as score breakdown chart.

ALTER TABLE resume_jobs
    ADD COLUMN IF NOT EXISTS ats_report jsonb;

COMMENT ON COLUMN resume_jobs.ats_report IS
    'Rules-based ATS breakdown for original resume: {overallScore, keywordScore, '
    'sectionScore, formatScore, verbScore, matchedKeywords, missingKeywords, '
    'presentSections, formatIssues}. Set by ATSScoreService before reshape.';