package com.collabcode.syncservice.config;

import com.collabcode.syncservice.handler.ControlHandler;
import com.collabcode.syncservice.handler.DocumentHandler;
import com.collabcode.syncservice.module.ModuleService;
import com.collabcode.syncservice.room.HostDepartureManager;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    @Value("${CLIENT_ORIGIN:http://localhost:3000}")
    private String clientOrigin;

    /**
     * The per-edit write-permission check is disabled ONLY when this property is
     * true AND the {@code loadtest} Spring profile is active (T-22 CR13 baseline).
     * Production never runs the {@code loadtest} profile, so the check cannot be
     * turned off there even if this property is set by accident.
     */
    @Value("${app.loadtest.edit-check-disabled:false}")
    private boolean editCheckDisableRequested;

    private final Environment environment;

    private final RoomStore roomStore;
    private final RoomService roomService;
    private final ModuleService moduleService;
    private final RoomRegistry roomRegistry;
    private final HostDepartureManager hostDepartures;

    public WebSocketConfig(RoomStore roomStore,
                           RoomService roomService,
                           ModuleService moduleService,
                           RoomRegistry roomRegistry,
                           HostDepartureManager hostDepartures,
                           Environment environment) {
        this.environment = environment;
        this.roomStore = roomStore;
        this.roomService = roomService;
        this.moduleService = moduleService;
        this.roomRegistry = roomRegistry;
        this.hostDepartures = hostDepartures;
    }

    // -----------------------------------------------------------------------
    // Handler beans
    // -----------------------------------------------------------------------

    @Bean
    public ControlHandler controlHandler() {
        ControlHandler handler = new ControlHandler(roomStore, roomService, roomRegistry);
        handler.setHostDepartureManager(hostDepartures);
        return handler;
    }

    @Bean
    public DocumentHandler documentHandler() {
        // CR13 (T-22): the edit-check bypass is enabled ONLY under the isolated
        // "loadtest" profile; production never runs it, so the check cannot be
        // disabled accidentally there.
        boolean editCheckDisabled = editCheckDisableRequested
                && environment.acceptsProfiles(Profiles.of("loadtest"));
        if (editCheckDisabled) {
            log.warn("LOAD-TEST MODE: the per-edit write-permission check is DISABLED "
                    + "(loadtest profile + app.loadtest.edit-check-disabled=true). "
                    + "This is a test-only CR13 baseline and must NEVER run in production.");
        }
        // Both handlers share the registry, but DocumentHandler explicitly uses
        // the control-socket view to route edit-rejected notifications.
        return new DocumentHandler(roomStore, roomService, moduleService, roomRegistry, roomRegistry,
                new com.fasterxml.jackson.databind.ObjectMapper(), editCheckDisabled);
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
