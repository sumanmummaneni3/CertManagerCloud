package com.codecatalyst.controller;

import com.codecatalyst.dto.request.CreateTargetRequest;
import com.codecatalyst.dto.request.UpdateTargetRequest;
import com.codecatalyst.dto.response.ScanJobResponse;
import com.codecatalyst.dto.response.TargetResponse;
import com.codecatalyst.security.TenantContext;
import com.codecatalyst.service.AgentService;
import com.codecatalyst.service.SslScannerService;
import com.codecatalyst.service.TargetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/targets")
@RequiredArgsConstructor
public class TargetController {

    private final TargetService targetService;
    private final SslScannerService sslScannerService;
    private final AgentService agentService;

    @GetMapping
    public ResponseEntity<Page<TargetResponse>> list(Pageable pageable) {
        return ResponseEntity.ok(targetService.listTargets(TenantContext.getOrgId(), pageable));
    }

    @PostMapping
    public ResponseEntity<TargetResponse> create(@Valid @RequestBody CreateTargetRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(targetService.createTarget(TenantContext.getOrgId(), req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TargetResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateTargetRequest req) {
        return ResponseEntity.ok(targetService.updateTarget(TenantContext.getOrgId(), id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        targetService.deleteTarget(TenantContext.getOrgId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/scan")
    public ResponseEntity<Map<String, String>> scan(@PathVariable UUID id) {
        String result = targetService.triggerScan(TenantContext.getOrgId(), id, sslScannerService, agentService);
        return ResponseEntity.ok(Map.of("message", result));
    }

    /**
     * Poll for the latest scan job status for a target.
     * UI uses this to show PENDING → CLAIMED → COMPLETED transition after triggering a scan.
     */
    @GetMapping("/{id}/scan-status")
    public ResponseEntity<?> scanStatus(@PathVariable UUID id) {
        return ResponseEntity.ok(targetService.getLatestScanStatus(TenantContext.getOrgId(), id));
    }
}
