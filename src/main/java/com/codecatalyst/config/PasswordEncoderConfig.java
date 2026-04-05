package com.codecatalyst.config;

import com.codecatalyst.repository.AgentRepository;
import com.codecatalyst.security.AgentAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AgentAuthFilter agentAuthFilter(AgentRepository agentRepository,
                                            BCryptPasswordEncoder passwordEncoder) {
        return new AgentAuthFilter(agentRepository, passwordEncoder);
    }
}
