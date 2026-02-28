package com.codecatalyst.controller;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.service.OrganizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService service;

    @GetMapping
    public List<Organization> getAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Organization getById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    public ResponseEntity<Organization> create(@Valid @RequestBody Organization org) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(org));
    }

    @PutMapping("/{id}")
    public Organization update(@PathVariable UUID id, @Valid @RequestBody Organization org) {
        return service.update(id, org);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
