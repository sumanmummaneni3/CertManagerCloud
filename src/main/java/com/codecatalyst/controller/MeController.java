package com.codecatalyst.controller;

import com.codecatalyst.entity.User;
import com.codecatalyst.security.OrgSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Returns the currently authenticated user's profile.
 * Useful for the frontend to know who is logged in and what org/role they have.
 *
 * GET /api/v1/me
 *   Authorization: Bearer <token>
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final OrgSecurityService orgSecurityService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> me() {
        User user = orgSecurityService.currentUser();
        return ResponseEntity.ok(Map.of(
                "userId",  user.getId(),
                "email",   user.getEmail(),
                "name",    user.getName() != null ? user.getName() : "",
                "role",    user.getRole().name(),
                "orgId",   user.getOrganization().getId(),
                "orgName", user.getOrganization().getName()
        ));
    }
}
