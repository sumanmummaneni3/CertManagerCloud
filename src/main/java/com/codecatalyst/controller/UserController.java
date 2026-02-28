package com.codecatalyst.controller;

import com.codecatalyst.entity.User;
import com.codecatalyst.enums.UserRole;
import com.codecatalyst.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService service;

    @GetMapping
    public List<User> getAll(@PathVariable UUID orgId,
                             @RequestParam(required = false) UserRole role) {
        return role != null ? service.findByOrgAndRole(orgId, role) : service.findByOrg(orgId);
    }

    @GetMapping("/{id}")
    public User getById(@PathVariable UUID orgId, @PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    public ResponseEntity<User> create(@PathVariable UUID orgId, @Valid @RequestBody User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(orgId, user));
    }

    @PutMapping("/{id}")
    public User update(@PathVariable UUID orgId, @PathVariable UUID id,
                       @Valid @RequestBody User user) {
        return service.update(id, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
