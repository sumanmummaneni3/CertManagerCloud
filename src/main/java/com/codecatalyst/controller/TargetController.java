package com.codecatalyst.controller;

import com.codecatalyst.entity.Target;
import com.codecatalyst.service.TargetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/targets")
@RequiredArgsConstructor
public class TargetController {

    private final TargetService service;

    @GetMapping
    public List<Target> getAll(@PathVariable UUID orgId) {
        return service.findByOrg(orgId);
    }

    @GetMapping("/{id}")
    public Target getById(@PathVariable UUID orgId, @PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    public ResponseEntity<Target> create(@PathVariable UUID orgId, @Valid @RequestBody Target target) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(orgId, target));
    }

    @PutMapping("/{id}")
    public Target update(@PathVariable UUID orgId, @PathVariable UUID id,
                         @Valid @RequestBody Target target) {
        return service.update(id, target);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
