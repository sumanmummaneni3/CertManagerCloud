package com.codecatalyst.controller;

import com.codecatalyst.dto.response.OrgResponse;
import com.codecatalyst.security.TenantContext;
import com.codecatalyst.service.OrgService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/org", produces = "application/json")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;

    @GetMapping
    public ResponseEntity<OrgResponse> getOrg() {
        return ResponseEntity.ok(orgService.getOrg(TenantContext.getOrgId()));
    }

    @PutMapping("/name")
    public ResponseEntity<OrgResponse> updateName(@RequestParam String name) {
        return ResponseEntity.ok(orgService.updateName(TenantContext.getOrgId(), name));
    }
}
