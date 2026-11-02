package com.collabcode.syncservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the room REST endpoints (T-17 V2.1, contract section 2).
 *
 * <p>Only {@code /v1/**} is exposed to the browser; {@code /internal/**} and
 * {@code /actuator/**} are not. The allowed origin is the same
 * {@code CLIENT_ORIGIN} used by the WebSocket origin check
 * ({@link WebSocketConfig}), default {@code http://localhost:3000}.
 */
@Configuration
public class RoomCorsConfig {

    @Value("${CLIENT_ORIGIN:http://localhost:3000}")
    private String clientOrigin;

    @Bean
    public WebMvcConfigurer roomCorsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/v1/**")
                        .allowedOrigins(clientOrigin)
                        .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("Content-Type", "X-Session-Id", "X-Owner-Token")
                        .maxAge(3600);
            }
        };
    }
}
