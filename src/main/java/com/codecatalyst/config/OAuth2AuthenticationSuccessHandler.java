package com.codecatalyst.config;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.Subscription;
import com.codecatalyst.entity.User;
import com.codecatalyst.enums.SubscriptionStatus;
import com.codecatalyst.enums.UserRole;
import com.codecatalyst.repository.OrganizationRepository;
import com.codecatalyst.repository.SubscriptionRepository;
import com.codecatalyst.repository.UserRepository;
import com.codecatalyst.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Value("${app.base-url:http://localhost:3000}")
    private String baseUrl;

    /** Comma-separated list of Google emails that receive PLATFORM_ADMIN role. */
    @Value("${app.platform-admin.emails:}")
    private List<String> platformAdminEmails;

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String email = oidcUser.getEmail();
        String name  = oidcUser.getFullName();
        String sub   = oidcUser.getSubject();

        boolean isPlatformAdmin = platformAdminEmails.contains(email);

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            if (isPlatformAdmin) {
                // Platform admins do NOT belong to any MSP org; create a sentinel org.
                Organization adminOrg = Organization.builder()
                        .name("__platform_admin__").slug("__platform_admin__").build();
                orgRepository.save(adminOrg);
                subscriptionRepository.save(Subscription.builder()
                        .organization(adminOrg).maxCertificateQuota(0)
                        .status(SubscriptionStatus.ACTIVE).build());
                log.info("Bootstrap: created PLATFORM_ADMIN user for {}", email);
                return userRepository.save(User.builder()
                        .organization(adminOrg).email(email).name(name)
                        .role(UserRole.PLATFORM_ADMIN).googleSub(sub).build());
            }

            // Regular MSP org user — auto-provision org + subscription.
            Organization org = Organization.builder()
                    .name(email.split("@")[0] + "'s Org").build();
            orgRepository.save(org);
            subscriptionRepository.save(Subscription.builder()
                    .organization(org).maxCertificateQuota(10)
                    .status(SubscriptionStatus.TRIAL).build());
            return userRepository.save(User.builder()
                    .organization(org).email(email).name(name)
                    .role(UserRole.ADMIN).googleSub(sub).build());
        });

        // Keep role in sync with the allowlist (allows promoting/demoting without DB edits).
        UserRole expectedRole = isPlatformAdmin ? UserRole.PLATFORM_ADMIN : user.getRole();
        if (user.getRole() != expectedRole) {
            user.setRole(expectedRole);
            userRepository.save(user);
            log.info("Role updated to {} for {}", expectedRole, email);
        }

        String token = jwtTokenProvider.generateToken(user);
        getRedirectStrategy().sendRedirect(request, response, baseUrl + "/?token=" + token);
    }
}
