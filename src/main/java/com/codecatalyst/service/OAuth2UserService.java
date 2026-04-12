package com.codecatalyst.service;

import com.codecatalyst.entity.OrgInvitation;
import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.Subscription;
import com.codecatalyst.entity.User;
import com.codecatalyst.enums.SubscriptionStatus;
import com.codecatalyst.enums.UserRole;
import com.codecatalyst.repository.OrgInvitationRepository;
import com.codecatalyst.repository.OrganizationRepository;
import com.codecatalyst.repository.SubscriptionRepository;
import com.codecatalyst.repository.UserRepository;
import com.codecatalyst.security.CertGuardUserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2UserService extends OidcUserService {

    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;

    /** Comma-separated list of Google emails that receive PLATFORM_ADMIN role. */
    @Value("${app.platform-admin.emails:}")
    private List<String> platformAdminEmails;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(request);
        String email = oidcUser.getEmail();
        String sub   = oidcUser.getSubject();
        String name  = oidcUser.getFullName();

        boolean isPlatformAdmin = platformAdminEmails.contains(email);

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            if (isPlatformAdmin) {
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

            Organization org = Organization.builder()
                    .name(email.split("@")[0] + "'s Org").slug(null).build();
            orgRepository.save(org);
            subscriptionRepository.save(Subscription.builder()
                    .organization(org).maxCertificateQuota(10)
                    .status(SubscriptionStatus.TRIAL).build());
            return userRepository.save(User.builder()
                    .organization(org).email(email).name(name)
                    .role(UserRole.ADMIN).googleSub(sub).build());
        });

        // Sync role with allowlist so a simple env-var change takes effect on next login.
        UserRole expectedRole = isPlatformAdmin ? UserRole.PLATFORM_ADMIN : user.getRole();
        if (user.getRole() != expectedRole) {
            user.setRole(expectedRole);
            log.info("Role synced to {} for {}", expectedRole, email);
        }

        user.setGoogleSub(sub);
        if (name != null) user.setName(name);
        userRepository.save(user);

        return CertGuardUserPrincipal.create(user, oidcUser.getAttributes(),
                oidcUser.getIdToken(), oidcUser.getUserInfo());
    }
}
