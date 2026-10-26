package com.collabcode.execution.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for execution-service (T-06 section 2).
 *
 * execution-service sends CORS headers allowing CLIENT_ORIGIN.
 * The default is http://localhost:3000 (the client dev port from T-06 section 1).
 * Override with the CLIENT_ORIGIN environment variable in production.
 *
 * Only /v1/** is exposed to the browser; /actuator/** is internal.
 */
@Configuration
public class CorsConfig {

    /** Browser origin allowed by CORS (T-06 section 2, shared settings). */
    @Value("${app.cors.allowed-origin:http://localhost:3000}")
    private String allowedOrigin;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/v1/**")
                        .allowedOrigins(allowedOrigin)
                        .allowedMethods("POST", "OPTIONS")
                        .allowedHeaders("Content-Type")
                        .maxAge(3600);
            }
        };
    }
}
