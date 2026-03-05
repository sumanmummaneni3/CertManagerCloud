package com.codecatalyst.controller;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.security.OrgSecurityService;
import com.codecatalyst.service.OrganizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService service;
    private final OrgSecurityService  orgSecurity;

    /** Get the caller's own organisation only — no cross-tenant access. */
    @GetMapping("/{id}")
    public Organization getById(@PathVariable UUID id) {
        orgSecurity.assertOrgAccess(id);
        return service.findById(id);
    }

    /** Update own organisation name. */
    @PutMapping("/{id}")
    public Organization update(@PathVariable UUID id,
                               @Valid @RequestBody Organization org) {
        orgSecurity.assertOrgAccess(id);
        return service.update(id, org);
    }
}
