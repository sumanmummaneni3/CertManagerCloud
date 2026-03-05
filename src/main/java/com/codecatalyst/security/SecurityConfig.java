package com.codecatalyst.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration.
 *
 * Auth flow:
 *   1. Client redirects user to GET /oauth2/authorization/google
 *   2. Google authenticates and redirects back to /login/oauth2/code/google
 *   3. OAuth2LoginSuccessHandler provisions/updates user, issues JWT, returns JSON
 *   4. Client stores JWT and sends on all future requests as:
 *        Authorization: Bearer <token>
 *   5. JwtAuthFilter validates token and populates SecurityContext
 *
 * RBAC matrix:
 * ┌────────────────────────────────────────────┬─────────┬───────────┐
 * │ Endpoint                                   │ ADMIN   │ READ_ONLY │
 * ├────────────────────────────────────────────┼─────────┼───────────┤
 * │ GET  /oauth2/authorization/google          │ public  │ public    │
 * │ GET  /api/v1/me                            │ ✅      │ ✅        │
 * │ GET  /api/v1/organizations/**              │ ✅      │ ✅        │
 * │ POST /-*-/fetch-certificate(s)             │ ✅      │ ❌        │
 * │ POST/PUT/DELETE /api/v1/organizations/**   │ ✅      │ ❌        │
 * └────────────────────────────────────────────┴─────────┴───────────┘
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter              jwtAuthFilter;
    private final OAuth2LoginSuccessHandler  oAuth2LoginSuccessHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Stateless — JWT carries all state, no server-side sessions needed
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)

            // ── OAuth2 Login ──────────────────────────────────────────────────
            // Exposes GET /oauth2/authorization/google  (redirect to Google)
            //         GET /login/oauth2/code/google     (Google callback)
            .oauth2Login(oauth -> oauth
                .successHandler(oAuth2LoginSuccessHandler)
                .failureHandler((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"status\":401,\"title\":\"OAuth2 Login Failed\"," +
                        "\"detail\":\"" + exception.getMessage() + "\"}");
                })
            )

            // ── JWT filter — runs before every protected request ──────────────
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

            // ── Authorization rules ───────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth

                // OAuth2 callback and Google redirect — must be public
                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()

                // Current user profile — any authenticated user
                .requestMatchers(HttpMethod.GET, "/api/v1/me").hasAnyRole("ADMIN", "READ_ONLY")

                // All reads — both roles
                .requestMatchers(HttpMethod.GET, "/api/v1/organizations/**")
                    .hasAnyRole("ADMIN", "READ_ONLY")

                // Certificate fetching (write operation) — ADMIN only
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/organizations/**/fetch-certificate",
                        "/api/v1/organizations/**/fetch-certificates")
                    .hasRole("ADMIN")

                // All other writes — ADMIN only
                .requestMatchers(HttpMethod.POST,   "/api/v1/organizations/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT,    "/api/v1/organizations/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/organizations/**").hasRole("ADMIN")

                .anyRequest().authenticated()
            )

            // ── Custom JSON errors — no HTML redirects ────────────────────────
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write(
                        "{\"status\":401,\"title\":\"Unauthorized\"," +
                        "\"detail\":\"Missing or invalid Bearer token\"}");
                })
                .accessDeniedHandler((req, res, e) -> {
                    res.setStatus(403);
                    res.setContentType("application/json");
                    res.getWriter().write(
                        "{\"status\":403,\"title\":\"Forbidden\"," +
                        "\"detail\":\"Your role does not permit this operation\"}");
                })
            );

        return http.build();
    }
}
