package com.collabcode.syncservice.config;

import com.collabcode.syncservice.handler.ControlHandler;
import com.collabcode.syncservice.handler.DocumentHandler;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
 * <p>Two separate {@link RoomRegistry} beans are declared so that the control
 * registry and the document registry track their own sessions independently.
 * The {@link DocumentHandler} receives both registries: its own for binary relay,
 * and the control registry so it can route {@code edit-rejected} to the correct
 * control session.
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
    private final ObjectMapper objectMapper;

    public WebSocketConfig(RoomStore roomStore,
                           RoomService roomService,
                           ObjectMapper objectMapper) {
        this.roomStore = roomStore;
        this.roomService = roomService;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // Registry beans — one per socket type so each tracks its own sessions
    // -----------------------------------------------------------------------

    /**
     * Registry for control-socket sessions ({@code /ws/rooms/{room_id}}).
     * Named explicitly so Spring can inject it by qualifier where needed.
     */
    @Bean("controlRegistry")
    public RoomRegistry controlRegistry() {
        return new RoomRegistry();
    }

    /**
     * Registry for document-socket sessions ({@code /ws/rooms/{room_id}/docs/{doc_id}}).
     */
    @Bean("documentRegistry")
    public RoomRegistry documentRegistry() {
        return new RoomRegistry();
    }

    /**
     * The legacy single-registry bean kept for the existing
     * {@link com.collabcode.syncservice.internal.InternalMessageController}
     * and any other code that injects {@link RoomRegistry} without a qualifier.
     * It now delegates to the control registry (internal broadcasts go to control sessions).
     */
    @Bean
    @Primary
    public RoomRegistry roomRegistry() {
        return controlRegistry();
    }

    // -----------------------------------------------------------------------
    // Handler beans
    // -----------------------------------------------------------------------

    @Bean
    public ControlHandler controlHandler() {
        return new ControlHandler(roomStore, roomService, controlRegistry(), objectMapper);
    }

    @Bean
    public DocumentHandler documentHandler() {
        return new DocumentHandler(
                roomStore, roomService,
                documentRegistry(),   // doc-socket registry (for binary relay)
                controlRegistry(),    // control registry (for edit-rejected delivery)
                objectMapper);
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
