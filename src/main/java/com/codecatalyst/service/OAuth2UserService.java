package com.codecatalyst.service;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.Subscription;
import com.codecatalyst.entity.User;
import com.codecatalyst.enums.SubscriptionStatus;
import com.codecatalyst.enums.UserRole;
import com.codecatalyst.repository.OrganizationRepository;
import com.codecatalyst.repository.SubscriptionRepository;
import com.codecatalyst.repository.UserRepository;
import com.codecatalyst.security.CertGuardUserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2UserService extends OidcUserService {

    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(request);
        String email = oidcUser.getEmail();
        String sub   = oidcUser.getSubject();
        String name  = oidcUser.getFullName();

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            Organization org = Organization.builder()
                    .name(email.split("@")[0] + "'s Org").slug(null).build();
            orgRepository.save(org);

            Subscription subscription = Subscription.builder()
                    .organization(org).maxTargets(10).status(SubscriptionStatus.TRIAL).build();
            subscriptionRepository.save(subscription);

            User newUser = User.builder()
                    .organization(org).email(email).name(name)
                    .role(UserRole.ADMIN).googleSub(sub).build();
            return userRepository.save(newUser);
        });

        user.setGoogleSub(sub);
        if (name != null) user.setName(name);
        userRepository.save(user);

        return CertGuardUserPrincipal.create(user, oidcUser.getAttributes(), oidcUser.getIdToken(), oidcUser.getUserInfo());
    }
}
