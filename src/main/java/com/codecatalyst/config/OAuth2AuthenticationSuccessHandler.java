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

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String email = oidcUser.getEmail();
        String name  = oidcUser.getFullName();
        String sub   = oidcUser.getSubject();

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            Organization org = Organization.builder()
                    .name(email.split("@")[0] + "'s Org").build();
            orgRepository.save(org);
            subscriptionRepository.save(Subscription.builder()
                    .organization(org).maxTargets(10).status(SubscriptionStatus.TRIAL).build());
            return userRepository.save(User.builder()
                    .organization(org).email(email).name(name)
                    .role(UserRole.ADMIN).googleSub(sub).build());
        });

        String token = jwtTokenProvider.generateToken(user);
        getRedirectStrategy().sendRedirect(request, response,
                baseUrl + "/?token=" + token);
    }
}
