package com.codecatalyst.security;

import com.codecatalyst.entity.User;
import com.codecatalyst.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates the JWT Bearer token on every request and populates the SecurityContext.
 *
 * Header:  Authorization: Bearer <jwt>
 *
 * On success: sets authentication with the User as principal and ROLE_ADMIN
 *             or ROLE_READ_ONLY as the granted authority.
 * On missing/invalid token: passes through unauthenticated — Spring Security
 *             authorization rules reject the request at the correct layer.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService     jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token  = authHeader.substring(BEARER_PREFIX.length());
        Claims claims = jwtService.tryParse(token);

        if (claims == null) {
            log.debug("Invalid or expired JWT from {}", request.getRemoteAddr());
            chain.doFilter(request, response);
            return;
        }

        UUID userId = jwtService.extractUserId(claims);
        Optional<User> userOpt = userRepository.findById(userId);

        if (userOpt.isEmpty()) {
            log.warn("JWT references unknown user {}", userId);
            chain.doFilter(request, response);
            return;
        }

        User user = userOpt.get();

        // Guard against stale tokens after org reassignment
        UUID tokenOrgId = jwtService.extractOrgId(claims);
        if (!user.getOrganization().getId().equals(tokenOrgId)) {
            log.warn("JWT org mismatch for user {}", userId);
            chain.doFilter(request, response);
            return;
        }

        SimpleGrantedAuthority authority =
                new SimpleGrantedAuthority("ROLE_" + user.getRole().name());

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, null, List.of(authority));

        SecurityContextHolder.getContext().setAuthentication(auth);
        log.debug("Authenticated '{}' role {} org {}",
                user.getEmail(), user.getRole(), user.getOrganization().getId());

        chain.doFilter(request, response);
    }
}
