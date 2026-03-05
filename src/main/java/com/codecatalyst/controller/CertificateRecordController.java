package com.codecatalyst.controller;

import com.codecatalyst.entity.CertificateRecord;
import com.codecatalyst.security.OrgSecurityService;
import com.codecatalyst.service.CertificateRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
@RequiredArgsConstructor
public class CertificateRecordController {

    private final CertificateRecordService service;
    private final OrgSecurityService       orgSecurity;

    @GetMapping("/certificates")
    public List<CertificateRecord> getByOrg(
            @PathVariable UUID orgId,
            @RequestParam(required = false, defaultValue = "0") int expiringWithinDays) {
        orgSecurity.assertOrgAccess(orgId);
        return expiringWithinDays > 0
                ? service.findExpiring(orgId, expiringWithinDays)
                : service.findByOrg(orgId);
    }

    @GetMapping("/targets/{targetId}/certificates")
    public List<CertificateRecord> getByTarget(@PathVariable UUID orgId,
                                               @PathVariable UUID targetId) {
        orgSecurity.assertOrgAccess(orgId);
        return service.findByTarget(targetId);
    }

    @GetMapping("/certificates/{id}")
    public CertificateRecord getById(@PathVariable UUID orgId, @PathVariable UUID id) {
        orgSecurity.assertOrgAccess(orgId);
        return service.findById(id);
    }

    @DeleteMapping("/certificates/{id}")
    public void delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        orgSecurity.assertOrgAccess(orgId);
        service.delete(id);
    }
}
