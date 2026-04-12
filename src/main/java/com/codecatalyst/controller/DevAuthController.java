package com.codecatalyst.controller;

import com.codecatalyst.entity.OrgInvitation;
import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.Subscription;
import com.codecatalyst.entity.User;
import com.codecatalyst.enums.SubscriptionStatus;
import com.codecatalyst.enums.UserRole;
import com.codecatalyst.repository.OrganizationRepository;
import com.codecatalyst.repository.SubscriptionRepository;
import com.codecatalyst.repository.UserRepository;
import com.codecatalyst.security.JwtTokenProvider;
import com.codecatalyst.dto.request.AcceptInviteRequest;
import com.codecatalyst.service.InvitationService;
import jakarta.validation.Valid;
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
    private final InvitationService invitationService;

    @Value("${app.dev-mode:true}")
    private boolean devMode;

    @GetMapping("/config")
    public ResponseEntity<?> authConfig() {
        return ResponseEntity.ok(Map.of("devMode", devMode));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @PostMapping("/dev-token")
    public ResponseEntity<?> devToken(
            @RequestParam(defaultValue = "admin@certguard.local") String email,
            @RequestParam(defaultValue = "ADMIN") String role) {

        if (!devMode) {
            return ResponseEntity.status(403).body(Map.of("error", "Dev mode is disabled"));
        }

        UserRole userRole;
        try {
            userRole = UserRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown role: " + role));
        }

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            Organization org = Organization.builder().name(email.split("@")[0] + "'s Org").build();
            orgRepository.save(org);
            int quota = (userRole == UserRole.PLATFORM_ADMIN) ? 0 : 10;
            subscriptionRepository.save(Subscription.builder()
                    .organization(org).maxCertificateQuota(quota)
                    .status(SubscriptionStatus.ACTIVE).build());
            return userRepository.save(User.builder()
                    .organization(org).email(email).name("Dev User").role(userRole).build());
        });

        String token = jwtTokenProvider.generateToken(
                user.getId(), user.getOrganization().getId(), user.getEmail(), user.getRole().name());

        log.info("Dev token issued for: {} (role={})", email, user.getRole());
        return ResponseEntity.ok(Map.of(
                "token", token,
                "orgId", user.getOrganization().getId(),
                "email", email,
                "role",  user.getRole().name(),
                "name",  user.getName() != null ? user.getName() : email
        ));
    }

    // ── Invite OTP flow (works in both dev and prod) ───────────────────────

    /**
     * Step 1: Validate invite token and send OTP.
     * Called when user lands on /invite?token=<raw>.
     * Returns the email so the frontend can pre-fill it.
     */
    @PostMapping("/invite/validate")
    public ResponseEntity<?> validateInvite(@RequestParam String token) {
        try {
            String email = invitationService.validateInviteAndSendOtp(token);
            return ResponseEntity.ok(Map.of(
                "email", email,
                "message", devMode
                    ? "OTP logged to server console (dev mode)"
                    : "OTP sent to " + email));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Step 2: Submit email + OTP to complete invite acceptance.
     * Issues a JWT on success.
     */
    @PostMapping("/invite/accept")
    public ResponseEntity<?> acceptInvite(@Valid @RequestBody AcceptInviteRequest req) {
        try {
            Map<String, String> result = invitationService.acceptInvite(
                    req.getToken(), req.getEmail(), req.getOtp());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
