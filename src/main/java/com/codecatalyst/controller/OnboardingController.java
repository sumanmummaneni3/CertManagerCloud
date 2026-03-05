package com.codecatalyst.controller;

import com.codecatalyst.dto.OnboardingRequest;
import com.codecatalyst.dto.OnboardingResponse;
import com.codecatalyst.service.OrganizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Handles the initial organisation onboarding flow.
 *
 * POST /api/v1/onboard
 *   Accepts org name, admin user, first target, and keystore password in one call.
 *   Creates the Organisation, User, Target and initialises the org-specific keystore.
 *   The keystore password is NEVER stored — it is used only to initialise the file.
 */
@RestController
@RequestMapping("/api/v1/onboard")
@RequiredArgsConstructor
public class OnboardingController {

    private final OrganizationService organizationService;

    @PostMapping
    public ResponseEntity<OnboardingResponse> onboard(@Valid @RequestBody OnboardingRequest request) {
        OnboardingResponse response = organizationService.onboard(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
