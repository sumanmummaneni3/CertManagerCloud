package com.codecatalyst.controller;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.Subscription;
import com.codecatalyst.entity.User;
import com.codecatalyst.enums.SubscriptionStatus;
import com.codecatalyst.enums.UserRole;
import com.codecatalyst.repository.OrganizationRepository;
import com.codecatalyst.repository.SubscriptionRepository;
import com.codecatalyst.repository.UserRepository;
import com.codecatalyst.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class DevAuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Value("${app.dev-mode:true}")
    private boolean devMode;

    @PostMapping("/dev-token")
    public ResponseEntity<?> devToken(
            @RequestParam(defaultValue = "admin@certguard.local") String email) {

        if (!devMode) {
            return ResponseEntity.status(403).body(Map.of("error", "Dev mode is disabled"));
        }

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            // Create org without slug to avoid unique constraint conflicts
            Organization org = Organization.builder()
                    .name(email.split("@")[0] + "'s Org")
                    .build();
            orgRepository.save(org);

            subscriptionRepository.save(Subscription.builder()
                    .organization(org)
                    .maxTargets(100)
                    .status(SubscriptionStatus.ACTIVE)
                    .build());

            return userRepository.save(User.builder()
                    .organization(org)
                    .email(email)
                    .name("Dev User")
                    .role(UserRole.ADMIN)
                    .build());
        });

        String token = jwtTokenProvider.generateToken(
                user.getId(),
                user.getOrganization().getId(),
                user.getEmail(),
                user.getRole().name());

        log.info("Dev token issued for: {}", email);

        return ResponseEntity.ok(Map.of(
                "token",  token,
                "orgId",  user.getOrganization().getId(),
                "email",  email,
                "role",   user.getRole().name()
        ));
    }
}
