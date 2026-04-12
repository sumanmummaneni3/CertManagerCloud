package com.codecatalyst.enums;

/**
 * PLATFORM_ADMIN  – Anthropic/operator-level; can modify quota for any org.
 * ADMIN           – Org-level admin; manages users, targets, agents within own org.
 * MEMBER          – Standard org member.
 * VIEWER          – Read-only org member.
 */
public enum UserRole { PLATFORM_ADMIN, SUPER_ADMIN, ADMIN, MEMBER, VIEWER }
