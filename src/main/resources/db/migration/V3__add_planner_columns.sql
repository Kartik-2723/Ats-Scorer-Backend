-- ─────────────────────────────────────────────────────────────────────────────
-- Migration: Add planner output columns to resume_jobs
--
-- New columns:
--   profile_type_detected  — candidate career stage from planner LLM
--                            (STUDENT_FRESHER / EARLY_CAREER / MID_SENIOR / CAREER_SWITCHER)
--   ats_gap_keywords       — JD keywords absent from resume, surfaced to frontend
--                            as manual improvement suggestions (jsonb string array)
--   content_flags          — per-bullet improvement suggestions from planner
--                            (jsonb array of {section, bullet, suggestion})
--
-- All columns are nullable — existing jobs have no planner data and that is fine.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE resume_jobs
    ADD COLUMN IF NOT EXISTS profile_type_detected VARCHAR(30),
    ADD COLUMN IF NOT EXISTS ats_gap_keywords      JSONB,
    ADD COLUMN IF NOT EXISTS content_flags         JSONB;

COMMENT ON COLUMN resume_jobs.profile_type_detected IS
    'Candidate career stage detected by planner LLM: STUDENT_FRESHER | EARLY_CAREER | MID_SENIOR | CAREER_SWITCHER';

COMMENT ON COLUMN resume_jobs.ats_gap_keywords IS
    'JD keywords completely absent from resume with no injectable basis. Surfaced to frontend as manual suggestions.';

COMMENT ON COLUMN resume_jobs.content_flags IS
    'Per-bullet content improvement suggestions from planner: [{section, bullet, suggestion}]. Non-blocking, for UI display only.';