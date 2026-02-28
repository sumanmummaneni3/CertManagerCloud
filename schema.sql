-- ============================================================
-- Database Schema Creation Script
-- ============================================================

-- Enable UUID extension (recommended for cloud-based PKs)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================
-- ENUM Types
-- ============================================================

CREATE TYPE user_role AS ENUM ('ADMIN', 'READ_ONLY');

-- ============================================================
-- Table: organization
-- ============================================================

CREATE TABLE organization (
    id                  UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    name                VARCHAR(255)    NOT NULL,
    keystore_location   TEXT            NOT NULL,
    api_key             VARCHAR(512)    NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ============================================================
-- Table: "user"
-- Note: "user" is a reserved word in PostgreSQL, hence quoted
-- ============================================================

CREATE TABLE "user" (
    id          UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID            NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    email       VARCHAR(320)    NOT NULL UNIQUE,
    role        user_role       NOT NULL DEFAULT 'READ_ONLY',
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_org_id ON "user"(org_id);

-- ============================================================
-- Table: target
-- ============================================================

CREATE TABLE target (
    id          UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID            NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    host        VARCHAR(253)    NOT NULL,
    port        INTEGER         NOT NULL CHECK (port BETWEEN 1 AND 65535),
    is_private  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, host, port)
);

CREATE INDEX idx_target_org_id ON target(org_id);

-- ============================================================
-- Table: certificate_record
-- ============================================================

CREATE TABLE certificate_record (
    id                  UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    target_id           UUID            NOT NULL REFERENCES target(id) ON DELETE CASCADE,
    org_id              UUID            NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    common_name         VARCHAR(255)    NOT NULL,
    issuer              VARCHAR(255)    NOT NULL,
    expiry_date         DATE            NOT NULL,
    client_org_name     VARCHAR(255)    NULL,
    division_name       VARCHAR(255)    NULL,
    status              VARCHAR(50)     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cert_target_id  ON certificate_record(target_id);
CREATE INDEX idx_cert_org_id     ON certificate_record(org_id);
CREATE INDEX idx_cert_expiry     ON certificate_record(expiry_date);

-- ============================================================
-- Auto-update updated_at via trigger function
-- ============================================================

CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER set_updated_at BEFORE UPDATE ON organization
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER set_updated_at BEFORE UPDATE ON "user"
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER set_updated_at BEFORE UPDATE ON target
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER set_updated_at BEFORE UPDATE ON certificate_record
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();
