-- ============================================================
-- V5 — Add SUPER_ADMIN platform role
-- ============================================================

ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'SUPER_ADMIN';
