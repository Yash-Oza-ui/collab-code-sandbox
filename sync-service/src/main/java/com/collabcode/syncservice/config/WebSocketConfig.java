package com.collabcode.syncservice.config;

import com.collabcode.syncservice.handler.RelayHandler;
import com.collabcode.syncservice.room.RoomRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket configuration for sync-service (T-02).
 *
 * <p>Registers the relay handler at {@code /ws/rooms/**} and tunes the
 * JSR-356 container buffer sizes to match the contract
 * (docs/api-contracts.md section 3.5):
 * <ul>
 *   <li>Binary messages: up to 1 MiB (rule 3).
 *   <li>Text messages:   up to 4 MiB (rule 4 — run results carry stdout/stderr).
 * </ul>
 *
 * <p>Origin enforcement: only requests from {@code CLIENT_ORIGIN} are allowed
 * through the WebSocket handshake (contract section 2). {@code *} is accepted
 * here for integration tests; never use {@code *} in production.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** Browser origin allowed to upgrade to WebSocket (contract section 2). */
    @Value("${CLIENT_ORIGIN:http://localhost:3000}")
    private String clientOrigin;

    private final RoomRegistry roomRegistry;

    public WebSocketConfig(RoomRegistry roomRegistry) {
        this.roomRegistry = roomRegistry;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(relayHandler(), "/ws/rooms/**")
                // setAllowedOriginPatterns handles both specific origins and "*"
                .setAllowedOriginPatterns(clientOrigin);
    }

    @Bean
    public WebSocketHandler relayHandler() {
        return new RelayHandler(roomRegistry);
    }

    /**
     * Raise the JSR-356 container buffer sizes above Tomcat's default (~8 KB).
     * Without this, Yjs binary frames larger than ~8 KB are rejected before
     * RelayHandler even sees them (contract section 3.5 rule 3).
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainerConfig() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(1024 * 1024);       // 1 MiB
        container.setMaxTextMessageBufferSize(4 * 1024 * 1024);     // 4 MiB
        return container;
    }
}
