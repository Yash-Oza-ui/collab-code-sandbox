package com.collabcode.syncservice.handler;

import com.collabcode.syncservice.room.RoomRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * WebSocket relay handler (T-02).
 *
 * <p>Contract reference: docs/api-contracts.md sections 3.1 – 3.6.
 *
 * <h2>What this class does</h2>
 * <ul>
 *   <li>Validates the room id (path segment) and session_id (query param) on
 *       connect; closes with 4400 if either is malformed.
 *   <li>Stores the room id and the client-supplied session_id as session
 *       attributes so FR9.6 (Sprint 2) can use them without changing the
 *       connection shape.
 *   <li>On binary message: forwards bytes <em>unchanged</em> to every other
 *       session in the same room (rule 1). Awareness frames (first byte == 1)
 *       are also echoed back to the sender so a lone client stays connected
 *       (tested with y-websocket 3.1.0, see contract section 3.5 rule 1).
 *   <li>Text frames from clients are ignored in v0 (contract section 3.4).
 *   <li>Closes with 1009 if a binary frame exceeds 1 MiB (rule 3).
 *   <li>Removes sessions from the registry on close or transport error.
 * </ul>
 *
 * <h2>What this class does NOT do</h2>
 * <ul>
 *   <li>Parse or modify Yjs message contents (constraint in T-02 issue).
 *   <li>Log message contents (they contain user code — NFR10 spirit).
 *   <li>Keep any document state (Sprint 1 known limitation, see README).
 * </ul>
 */
public class RelayHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RelayHandler.class);

    // --- Validation patterns (T-06, section 3.2 and 3.3) ---

    /** Room id: 1–64 chars from A-Z a-z 0-9 _ - */
    private static final Pattern ROOM_ID_OK = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** UUID v4: version nibble = 4, variant nibble in [89ab]. */
    private static final Pattern UUID_V4_OK = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    /** Maximum binary frame size: 1 MiB (contract section 3.5 rule 3). */
    private static final int MAX_BINARY_BYTES = 1024 * 1024;

    /** First byte of a y-websocket awareness frame (section 3.5 rule 1). */
    public static final byte AWARENESS_BYTE = 1;

    /** Attribute key: validated room id stored per session. */
    public static final String ATTR_ROOM_ID = "roomId";

    /** Attribute key: stable client-supplied session id stored per session. */
    public static final String ATTR_SESSION_ID = "sessionId";

    private final RoomRegistry registry;

    public RelayHandler(RoomRegistry registry) {
        this.registry = registry;
    }

    // -----------------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) {
            session.close(new CloseStatus(4400, "Missing URI"));
            return;
        }

        // --- Validate room id (path: /ws/rooms/{roomId}) ---
        String roomId = extractRoomId(uri.getPath());
        if (roomId == null || !ROOM_ID_OK.matcher(roomId).matches()) {
            log.warn("Closing {}: malformed room id", session.getId());
            session.close(new CloseStatus(4400, "Malformed room id"));
            return;
        }

        // --- Validate session_id (query: ?session_id=<UUID v4>) ---
        String clientSessionId = queryParam(uri.getQuery(), "session_id");
        if (clientSessionId == null || !UUID_V4_OK.matcher(clientSessionId).matches()) {
            log.warn("Closing {}: session_id missing or not a UUID v4", session.getId());
            session.close(new CloseStatus(4400, "session_id missing or not a UUID"));
            return;
        }

        // Store on session for later use (broadcast, FR9.6 in Sprint 2)
        session.getAttributes().put(ATTR_ROOM_ID, roomId);
        session.getAttributes().put(ATTR_SESSION_ID, clientSessionId);

        registry.add(roomId, session);
        log.info("Connected wsSession={} room={}", session.getId(), roomId);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session,
                                      @NonNull CloseStatus status) {
        registry.remove(session);
        log.info("Closed wsSession={} room={} status={}",
                session.getId(), session.getAttributes().get(ATTR_ROOM_ID), status);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session,
                                     @NonNull Throwable ex) {
        registry.remove(session);
        log.warn("Transport error wsSession={}: {}", session.getId(), ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // Message handling
    // -----------------------------------------------------------------------

    @Override
    protected void handleBinaryMessage(@NonNull WebSocketSession session,
                                       @NonNull BinaryMessage message) throws Exception {
        byte[] bytes = toByteArray(message.getPayload());

        // Rule 3: reject frames larger than 1 MiB
        if (bytes.length > MAX_BINARY_BYTES) {
            log.warn("Binary frame too large ({} bytes) — closing wsSession={}", bytes.length, session.getId());
            session.close(new CloseStatus(1009, "Binary frame exceeds 1 MiB"));
            return;
        }

        String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);
        if (roomId == null) {
            return; // connection was rejected but message arrived before close propagated
        }

        boolean isAwareness = bytes.length > 0 && bytes[0] == AWARENESS_BYTE;

        // Rule 1a: forward to every OTHER session in the room
        List<WebSocketSession> others = registry.getOthers(roomId, session);
        for (WebSocketSession target : others) {
            sendBinaryBytes(target, bytes);
        }

        // Rule 1b: echo awareness frames back to sender so a lone client
        // keeps receiving frames and does not reconnect after ~30 s.
        if (isAwareness) {
            sendBinaryBytes(session, bytes);
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session,
                                     @NonNull TextMessage message) {
        // Contract section 3.4: clients send no text frames in v0. Ignore silently.
        log.debug("Ignoring unexpected text frame from wsSession={}", session.getId());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Send {@code bytes} as a binary frame to {@code target}, synchronized on
     * the session to prevent concurrent-send errors (WebSocketSession is not
     * thread-safe for sending).
     */
    private void sendBinaryBytes(WebSocketSession target, byte[] bytes) {
        try {
            if (target.isOpen()) {
                synchronized (target) {
                    target.sendMessage(new BinaryMessage(bytes));
                }
            }
        } catch (IOException e) {
            log.warn("Send failed to wsSession={}: {}", target.getId(), e.getMessage());
        }
    }

    /**
     * Extract the room id from the WebSocket URI path.
     * Path must be {@code /ws/rooms/<roomId>}.
     */
    private static String extractRoomId(String path) {
        if (path == null) return null;
        final String PREFIX = "/ws/rooms/";
        if (!path.startsWith(PREFIX)) return null;
        String remainder = path.substring(PREFIX.length());
        // Strip any trailing path segments (defensive; the contract has none)
        int slash = remainder.indexOf('/');
        return slash >= 0 ? remainder.substring(0, slash) : remainder;
    }

    /** Parse a single named parameter from a URL query string. */
    private static String queryParam(String query, String name) {
        if (query == null || query.isBlank()) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0]) && !kv[1].isBlank()) {
                return kv[1];
            }
        }
        return null;
    }

    /** Copy a ByteBuffer to a fresh byte array without consuming its position. */
    private static byte[] toByteArray(ByteBuffer buf) {
        ByteBuffer view = buf.duplicate();
        view.rewind();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }
}
