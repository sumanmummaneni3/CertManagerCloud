-- ============================================================
-- V4 — Org Invitations (multi-MSP member management)
-- ============================================================

CREATE TABLE org_invitations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email      VARCHAR(255) NOT NULL,
    role       user_role NOT NULL DEFAULT 'MEMBER',
    token      VARCHAR(255) NOT NULL UNIQUE,
    used       BOOLEAN NOT NULL DEFAULT FALSE,
    invited_by UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invitations_org_id ON org_invitations(org_id);
CREATE INDEX idx_invitations_email  ON org_invitations(email);
CREATE INDEX idx_invitations_token  ON org_invitations(token);
