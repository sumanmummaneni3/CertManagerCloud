package com.codecatalyst.security;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.User;
import com.codecatalyst.enums.UserRole;
import com.codecatalyst.repository.OrganizationRepository;
import com.codecatalyst.repository.UserRepository;
import com.codecatalyst.service.KeystoreService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/**
 * Invoked by Spring Security after a successful Google OAuth2 / OIDC login.
 *
 * Auto-provisioning rules:
 *   - First login ever for this Google account → create Organisation + User (ADMIN)
 *   - Subsequent logins → update email/name in case they changed in Google, return existing user
 *   - The organisation name defaults to "<DisplayName>'s Organisation" and can be
 *     renamed later via PUT /api/v1/organizations/{id}
 *
 * After provisioning, a JWT is issued and returned as JSON so the client
 * (SPA / mobile app) can store it and use it on subsequent API calls.
 *
 * Response body:
 * {
 *   "token":   "<jwt>",
 *   "orgId":   "<uuid>",
 *   "userId":  "<uuid>",
 *   "email":   "user@gmail.com",
 *   "role":    "ADMIN"
 * }
 */
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final UserRepository         userRepository;
    private final OrganizationRepository organizationRepository;
    private final KeystoreService        keystoreService;
    private final JwtService             jwtService;

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest  request,
                                        HttpServletResponse response,
                                        Authentication      authentication)
            throws IOException {

        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();

        String googleSub  = oidcUser.getSubject();
        String email      = oidcUser.getEmail();
        String name       = oidcUser.getFullName();

        // ── Look up or provision the user ─────────────────────────────────────
        User user = userRepository.findByGoogleSub(googleSub)
                .map(existing -> updateProfile(existing, email, name))
                .orElseGet(() -> provision(googleSub, email, name));

        // ── Issue JWT and return as JSON ───────────────────────────────────────
        String token = jwtService.issue(user);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"token\":\"%s\",\"orgId\":\"%s\",\"userId\":\"%s\",\"email\":\"%s\",\"role\":\"%s\"}",
                token,
                user.getOrganization().getId(),
                user.getId(),
                user.getEmail(),
                user.getRole().name()
        ));
    }

    /** Update mutable Google profile fields on subsequent logins. */
    private User updateProfile(User user, String email, String name) {
        user.setEmail(email);
        user.setName(name);
        User saved = userRepository.save(user);
        log.info("Returning user '{}' logged in (org: {})", email,
                saved.getOrganization().getId());
        return saved;
    }

    /**
     * First-time provisioning:
     *   1. Create Organisation with a placeholder keystore path
     *   2. Create keystore file — password is derived from the org UUID so it is
     *      deterministic and never needs to be stored (regenerable from the UUID)
     *   3. Update the org with the real keystore path
     *   4. Create User as ADMIN (first user in org always becomes admin)
     */
    private User provision(String googleSub, String email, String name) {
        log.info("First login — provisioning new org and admin user for '{}'", email);

        // 1. Create org
        String orgName = (name != null ? name : email.split("@")[0]) + "'s Organisation";
        Organization org = Organization.builder()
                .name(orgName)
                .keystoreLocation("pending")
                .build();
        org = organizationRepository.save(org);

        // 2. Create keystore — password derived from org UUID (no user-supplied password needed)
        char[] ksPassword = deriveKeystorePassword(org.getId().toString());
        String ksPath = keystoreService.createKeystoreForOrg(org.getId(), ksPassword);

        // 3. Persist keystore path
        org.setKeystoreLocation(ksPath);
        organizationRepository.save(org);

        // 4. Create admin user
        User user = User.builder()
                .organization(org)
                .googleSub(googleSub)
                .email(email)
                .name(name)
                .role(UserRole.ADMIN)
                .build();
        User saved = userRepository.save(user);

        log.info("Provisioned org '{}' ({}) with admin '{}'",
                org.getName(), org.getId(), email);
        return saved;
    }

    /**
     * Derive a deterministic keystore password from the org UUID.
     * This means the password never needs to be stored anywhere — it can always
     * be re-derived from the org ID when the keystore needs to be opened
     * (e.g. when storing a fetched certificate).
     *
     * Note: for higher security this derivation could use PBKDF2 with a
     * server-side master secret stored in an env variable / Vault.
     */
    private char[] deriveKeystorePassword(String orgId) {
        return ("ks-" + orgId).toCharArray();
    }
}
