package com.codecatalyst.controller;

import com.codecatalyst.entity.CertificateRecord;
import com.codecatalyst.service.CertificateRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
@RequiredArgsConstructor
public class CertificateRecordController {

    private final CertificateRecordService service;

    // GET /organizations/{orgId}/certificates
    @GetMapping("/certificates")
    public List<CertificateRecord> getByOrg(@PathVariable UUID orgId,
                                            @RequestParam(required = false, defaultValue = "0") int expiringWithinDays) {
        return expiringWithinDays > 0
                ? service.findExpiring(orgId, expiringWithinDays)
                : service.findByOrg(orgId);
    }

    // GET /organizations/{orgId}/targets/{targetId}/certificates
    @GetMapping("/targets/{targetId}/certificates")
    public List<CertificateRecord> getByTarget(@PathVariable UUID orgId,
                                               @PathVariable UUID targetId) {
        return service.findByTarget(targetId);
    }

    // GET /organizations/{orgId}/certificates/{id}
    @GetMapping("/certificates/{id}")
    public CertificateRecord getById(@PathVariable UUID orgId, @PathVariable UUID id) {
        return service.findById(id);
    }

    // POST /organizations/{orgId}/targets/{targetId}/certificates
    @PostMapping("/targets/{targetId}/certificates")
    public ResponseEntity<CertificateRecord> create(@PathVariable UUID orgId,
                                                    @PathVariable UUID targetId,
                                                    @Valid @RequestBody CertificateRecord record) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(orgId, targetId, record));
    }

    @PutMapping("/certificates/{id}")
    public CertificateRecord update(@PathVariable UUID orgId, @PathVariable UUID id,
                                    @Valid @RequestBody CertificateRecord record) {
        return service.update(id, record);
    }

    @DeleteMapping("/certificates/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
