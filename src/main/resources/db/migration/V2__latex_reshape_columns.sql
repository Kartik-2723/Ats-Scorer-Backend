-- V2__latex_reshape_columns.sql
-- Adds LaTeX reshape pipeline columns to resume_jobs

-- ── Input type ───────────────────────────────────────────────
-- Tracks whether the user uploaded a PDF or raw LaTeX
ALTER TABLE resume_jobs
    ADD COLUMN IF NOT EXISTS input_type VARCHAR(10);
-- Values: 'PDF' | 'LATEX'

-- ── Raw LaTeX ────────────────────────────────────────────────
-- Stores the original uploaded LaTeX (if inputType=LATEX)
-- OR the LLM-converted LaTeX from PDF extraction (if inputType=PDF)
-- Kept for audit and retry purposes
ALTER TABLE resume_jobs
    ADD COLUMN IF NOT EXISTS raw_latex TEXT;

-- ── Shaped LaTeX ─────────────────────────────────────────────
-- Final reshaped + Tectonic-validated LaTeX returned to frontend
ALTER TABLE resume_jobs
    ADD COLUMN IF NOT EXISTS shaped_latex TEXT;

-- ── Compiled PDF S3 key ──────────────────────────────────────
-- S3 key of the Tectonic-compiled PDF (pre-compiled in backend
-- so frontend gets instant PDF render on first load via Redis cache)
ALTER TABLE resume_jobs
    ADD COLUMN IF NOT EXISTS compiled_pdf_key TEXT;

-- ── Compile attempt counter ──────────────────────────────────
-- Tracks how many Tectonic attempts were made (max 3: 1 + 2 retries)
ALTER TABLE resume_jobs
    ADD COLUMN IF NOT EXISTS latex_compile_attempts INTEGER NOT NULL DEFAULT 0;

-- ── Updated valid statuses (comment reference) ───────────────
-- PENDING | PARSING | ANALYZING | RESHAPING | SCORING | DONE | FAILED
-- CONVERTING | RESHAPING_LATEX | COMPILING | FIX_RETRY