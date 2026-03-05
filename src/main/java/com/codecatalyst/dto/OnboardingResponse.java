package com.codecatalyst.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Response returned after a successful onboarding.
 * Contains IDs and key fields from all created entities.
 * The keystore password and api_key are never echoed back.
 */
@Data
@Builder
public class OnboardingResponse {

    private UUID   orgId;
    private String orgName;
    private String keystoreLocation;

    private UUID   userId;
    private String adminEmail;
    private String adminRole;

    private UUID   targetId;
    private String targetHost;
    private int    targetPort;

    private String message;
}
