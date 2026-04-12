package com.codecatalyst.config;

import com.codecatalyst.security.AgentAuthFilter;
import com.codecatalyst.security.JwtAuthenticationFilter;
import com.codecatalyst.service.OAuth2UserService;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2UserService oAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    private final ApplicationContext applicationContext;

    @Value("${app.dev-mode:true}")
    private boolean devMode;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          OAuth2UserService oAuth2UserService,
                          OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler,
                          ApplicationContext applicationContext) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.oAuth2UserService       = oAuth2UserService;
        this.oAuth2SuccessHandler    = oAuth2SuccessHandler;
        this.applicationContext      = applicationContext;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AgentAuthFilter agentAuthFilter = applicationContext.getBean(AgentAuthFilter.class);

        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.INCLUDE).permitAll()
                // Agent
                .requestMatchers("/api/v1/agent/ca-cert").permitAll()
                .requestMatchers("/api/v1/agent/register").permitAll()
                .requestMatchers("/agent/download").permitAll()
                .requestMatchers("/agent/version").permitAll()
                // Auth — always public
                .requestMatchers("/api/v1/auth/config").permitAll()
                .requestMatchers("/api/v1/auth/logout").permitAll()
                .requestMatchers("/api/v1/auth/dev-token").permitAll()
                .requestMatchers("/api/v1/auth/invite/**").permitAll()
                // OAuth2
                .requestMatchers("/oauth2/**").permitAll()
                .requestMatchers("/login/oauth2/**").permitAll()
                // Protected API — role checks via @PreAuthorize
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(agentAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (!devMode) {
            http.oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(ui -> ui.oidcUserService(oAuth2UserService))
                .successHandler(oAuth2SuccessHandler));
        } else {
            http.oauth2Login(oauth2 -> oauth2.disable());
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
