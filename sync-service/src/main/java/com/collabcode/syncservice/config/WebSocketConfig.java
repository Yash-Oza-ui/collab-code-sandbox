package com.collabcode.syncservice.config;

import com.collabcode.syncservice.handler.ControlHandler;
import com.collabcode.syncservice.handler.DocumentHandler;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket configuration for sync-service (T-02, T-20).
 *
 * <p>T-20 splits the single relay path into two separate sockets:
 * <ul>
 *   <li><b>Control socket</b>: {@code /ws/rooms/{room_id}?session_id=} —
 *       carries text messages only (join, joined, join-refused, roster, edit-rejected).</li>
 *   <li><b>Document socket</b>: {@code /ws/rooms/{room_id}/docs/{doc_id}?session_id=} —
 *       carries Yjs binary frames only, with the v0 relay rules (awareness echo,
 *       1 MiB limit) plus the edit-permission check (CR13).</li>
 * </ul>
 *
 * <p>Origin enforcement: only requests from {@code CLIENT_ORIGIN} are allowed
 * through the WebSocket handshake. {@code *} is accepted in integration tests;
 * never use {@code *} in production.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** Browser origin allowed to upgrade to WebSocket (contract section 2). */
    @Value("${CLIENT_ORIGIN:http://localhost:3000}")
    private String clientOrigin;

    private final RoomStore roomStore;
    private final RoomService roomService;
    private final RoomRegistry roomRegistry;

    public WebSocketConfig(RoomStore roomStore,
                           RoomService roomService,
                           RoomRegistry roomRegistry) {
        this.roomStore = roomStore;
        this.roomService = roomService;
        this.roomRegistry = roomRegistry;
    }

    // -----------------------------------------------------------------------
    // Handler beans
    // -----------------------------------------------------------------------

    @Bean
    public ControlHandler controlHandler() {
        return new ControlHandler(roomStore, roomService, roomRegistry);
    }

    @Bean
    public DocumentHandler documentHandler() {
        // Both handlers share the registry, but DocumentHandler explicitly uses
        // the control-socket view to route edit-rejected notifications.
        return new DocumentHandler(roomStore, roomService, roomRegistry, roomRegistry,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    // -----------------------------------------------------------------------
    // WebSocket handler registration
    // -----------------------------------------------------------------------

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Document socket — more specific path MUST be registered first so
        // Spring matches /ws/rooms/{id}/docs/{doc} before /ws/rooms/{id}
        registry.addHandler(documentHandler(), "/ws/rooms/*/docs/*")
                .setAllowedOriginPatterns(clientOrigin);

        // Control socket
        registry.addHandler(controlHandler(), "/ws/rooms/*")
                .setAllowedOriginPatterns(clientOrigin);
    }

    // -----------------------------------------------------------------------
    // Container buffer sizes
    // -----------------------------------------------------------------------

    /**
     * Raise the JSR-356 container buffer sizes above Tomcat's default (~8 KB).
     * Without this, Yjs binary frames larger than ~8 KB are rejected before the
     * handler even sees them (contract section 3.5 rule 3).
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainerConfig() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(1024 * 1024);       // 1 MiB
        container.setMaxTextMessageBufferSize(4 * 1024 * 1024);     // 4 MiB
        return container;
    }
}
