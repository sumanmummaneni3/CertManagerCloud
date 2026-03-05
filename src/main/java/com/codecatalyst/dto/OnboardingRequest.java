package com.codecatalyst.dto;

import com.codecatalyst.enums.UserRole;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * Single request body for the onboarding flow:
 * creates the Organisation, first Admin User, first Target, and
 * initialises the org keystore — all in one atomic call.
 *
 * The keystorePassword is accepted as a char[] and cleared after use.
 * It is never logged or stored.
 */
@Data
public class OnboardingRequest {

    // ── Organisation ─────────────────────────────────────────────────────────
    @NotBlank(message = "Organisation name is required")
    @Size(max = 255)
    private String orgName;

    // ── Admin user ────────────────────────────────────────────────────────────
    @NotBlank(message = "Admin email is required")
    @Email(message = "Must be a valid email address")
    @Size(max = 320)
    private String adminEmail;

    private UserRole adminRole = UserRole.ADMIN;

    // ── First target ──────────────────────────────────────────────────────────
    @NotBlank(message = "Target host is required")
    @Size(max = 253)
    private String targetHost;

    @NotNull(message = "Target port is required")
    @Min(value = 1, message = "Port must be between 1 and 65535")
    @Max(value = 65535, message = "Port must be between 1 and 65535")
    private Integer targetPort;

    private boolean targetPrivate = false;

    // ── Keystore ──────────────────────────────────────────────────────────────
    /**
     * Password for the new org keystore.
     * Supplied by the user and used only to initialise the keystore file.
     * Never stored or logged.
     */
    @NotBlank(message = "Keystore password is required")
    @Size(min = 8, message = "Keystore password must be at least 8 characters")
    private String keystorePassword;
}
