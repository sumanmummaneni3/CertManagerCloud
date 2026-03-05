package com.codecatalyst.controller;

import com.codecatalyst.dto.FetchAllResponse;
import com.codecatalyst.dto.FetchCertificateRequest;
import com.codecatalyst.entity.CertificateRecord;
import com.codecatalyst.security.OrgSecurityService;
import com.codecatalyst.service.CertificateFetchService;
import com.codecatalyst.service.CertificateFetchService.FetchResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
@RequiredArgsConstructor
public class CertificateFetchController {

    private final CertificateFetchService fetchService;
    private final OrgSecurityService      orgSecurity;

    @PostMapping("/targets/{targetId}/fetch-certificate")
    public ResponseEntity<CertificateRecord> fetchForTarget(
            @PathVariable UUID orgId,
            @PathVariable UUID targetId,
            @Valid @RequestBody FetchCertificateRequest request) {
        orgSecurity.assertOrgAccess(orgId);
        char[] password = request.getKeystorePassword().toCharArray();
        return ResponseEntity.ok(fetchService.fetchAndStore(orgId, targetId, password));
    }

    @PostMapping("/fetch-certificates")
    public ResponseEntity<FetchAllResponse> fetchAllForOrg(
            @PathVariable UUID orgId,
            @Valid @RequestBody FetchCertificateRequest request) {
        orgSecurity.assertOrgAccess(orgId);
        char[] password = request.getKeystorePassword().toCharArray();
        List<FetchResult> results = fetchService.fetchAllForOrg(orgId, password);
        long succeeded = results.stream().filter(FetchResult::success).count();
        return ResponseEntity.ok(FetchAllResponse.builder()
                .totalTargets(results.size())
                .succeeded((int) succeeded)
                .failed((int) (results.size() - succeeded))
                .results(results)
                .build());
    }
}
