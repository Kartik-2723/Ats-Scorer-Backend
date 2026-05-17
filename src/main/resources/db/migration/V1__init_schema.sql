-- V1__init_schema.sql
-- Resume Shaper – initial database schema

-- ── Extensions ──────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── Users ───────────────────────────────────────────────────
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255),
    avatar_url    TEXT,
    provider      VARCHAR(50)  NOT NULL,   -- GOOGLE | GITHUB | EMAIL
    provider_id   VARCHAR(255),
    role          VARCHAR(50)  NOT NULL DEFAULT 'USER',  -- USER | ADMIN
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);

-- ── Guest Sessions ───────────────────────────────────────────
-- Stored in Redis (TTL 24h), but we keep a lightweight DB row
-- so we can claim the session later and link to a real user.
CREATE TABLE guest_sessions (
    token         VARCHAR(64) PRIMARY KEY,
    claimed_by    UUID REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ NOT NULL
);

-- ── Resume Jobs ──────────────────────────────────────────────
-- One row per "shape" request (guest or authenticated)
CREATE TABLE resume_jobs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID REFERENCES users(id) ON DELETE CASCADE,
    guest_token       VARCHAR(64) REFERENCES guest_sessions(token),
    -- Role targeting
    role_label        VARCHAR(255) NOT NULL,
    role_category     VARCHAR(100),
    is_custom_role    BOOLEAN NOT NULL DEFAULT FALSE,
    -- Job description
    jd_text           TEXT,
    -- File references (S3 keys)
    original_file_key TEXT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    shaped_file_key   TEXT,
    -- Parsed & shaped data (JSONB for flexibility)
    parsed_resume     JSONB,
    shaped_resume     JSONB,
    jd_analysis       JSONB,
    -- ATS scoring
    ats_score_before  INTEGER,
    ats_score_after   INTEGER,
    ats_report        JSONB,
    -- Pipeline state
    status            VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    -- PENDING | PARSING | ANALYZING | RESHAPING | SCORING | DONE | FAILED
    error_message     TEXT,
    starred           BOOLEAN NOT NULL DEFAULT FALSE,
    -- Timestamps
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_owner CHECK (user_id IS NOT NULL OR guest_token IS NOT NULL)
);

CREATE INDEX idx_jobs_user    ON resume_jobs(user_id);
CREATE INDEX idx_jobs_guest   ON resume_jobs(guest_token);
CREATE INDEX idx_jobs_status  ON resume_jobs(status);
CREATE INDEX idx_jobs_created ON resume_jobs(created_at DESC);

-- ── Resume Versions ──────────────────────────────────────────
-- Every re-shape of an existing job creates a new version row.
CREATE TABLE resume_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES resume_jobs(id) ON DELETE CASCADE,
    version_number  INTEGER NOT NULL,
    shaped_resume   JSONB NOT NULL,
    ats_score       INTEGER,
    shaped_file_key TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (job_id, version_number)
);

CREATE INDEX idx_versions_job ON resume_versions(job_id);

-- ── Auto-update updated_at ───────────────────────────────────
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_jobs_updated_at
    BEFORE UPDATE ON resume_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();