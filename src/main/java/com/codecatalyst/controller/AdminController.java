package com.codecatalyst.controller;

import com.codecatalyst.dto.request.UpdateQuotaRequest;
import com.codecatalyst.dto.response.SubscriptionResponse;
import com.codecatalyst.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/admin", produces = "application/json")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminController {

    private final AdminService adminService;

    /**
     * List all organizations with their subscription and current target count.
     * GET /api/v1/admin/orgs
     */
    @GetMapping("/orgs")
    public ResponseEntity<List<SubscriptionResponse>> listAllOrgs() {
        return ResponseEntity.ok(adminService.listAllOrgs());
    }

    /**
     * Get the subscription details for a specific organization.
     * GET /api/v1/admin/orgs/{orgId}/subscription
     */
    @GetMapping("/orgs/{orgId}/subscription")
    public ResponseEntity<SubscriptionResponse> getOrgSubscription(@PathVariable UUID orgId) {
        return ResponseEntity.ok(adminService.getOrgSubscription(orgId));
    }

    /**
     * Update the target quota (maxTargets) for a specific organization.
     * PUT /api/v1/admin/orgs/{orgId}/subscription/quota
     * Body: { "maxTargets": 25 }
     */
    @PutMapping("/orgs/{orgId}/subscription/quota")
    public ResponseEntity<SubscriptionResponse> updateQuota(
            @PathVariable UUID orgId,
            @Valid @RequestBody UpdateQuotaRequest request) {
        return ResponseEntity.ok(adminService.updateQuota(orgId, request));
    }
}
