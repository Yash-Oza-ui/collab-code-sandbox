package com.collabcode.syncservice.handler;

import com.collabcode.syncservice.room.JoinOutcome;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMessages;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Control-socket handler (T-20, Bhavya part).
 *
 * <p>Path: {@code /ws/rooms/{room_id}?session_id=<UUID v4>}
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Reject connections to unknown rooms with close code 4404.</li>
 *   <li>Process the first text frame from a client as a {@code join} message.</li>
 *   <li>Reply with {@code joined} (role, mode, preset, permissions) or
 *       {@code join-refused} (e.g. NAME_TAKEN).</li>
 *   <li>Broadcast a {@code roster} update to the whole room after a successful join.</li>
 *   <li>Accept only text frames; binary frames on the control socket are ignored
 *       (document edits belong on the document socket).</li>
 * </ul>
 *
 * <h2>What this class does NOT do</h2>
 * <ul>
 *   <li>Forward Yjs binary frames (that is the document socket's job).</li>
 *   <li>Log message contents or document content (NFR10).</li>
 * </ul>
 */
public class ControlHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ControlHandler.class);

    // --- Close codes (T-20) ---

    /** Unknown room id — the room does not exist on this server. */
    public static final int CLOSE_UNKNOWN_ROOM = 4404;

    /** Room is closed / destroyed. */
    public static final int CLOSE_ROOM_CLOSED = 4410;

    // Shared validation patterns (same rules as T-02 / RelayHandler)
    static final Pattern ROOM_ID_OK = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    static final Pattern UUID_V4_OK = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    // Path pattern for the control socket: /ws/rooms/{roomId}
    private static final Pattern CONTROL_PATH = Pattern.compile("^/ws/rooms/([^/]+)$");

    /** Attribute key: validated room id. Shared with DocumentHandler. */
    public static final String ATTR_ROOM_ID = "roomId";

    /** Attribute key: stable client-supplied session id. Shared with DocumentHandler. */
    public static final String ATTR_SESSION_ID = "sessionId";

    /** Attribute key: true once the client has completed a successful join. */
    static final String ATTR_JOINED = "joined";

    private final RoomStore roomStore;
    private final RoomService roomService;
    private final RoomRegistry registry;
    private final ObjectMapper objectMapper;

    public ControlHandler(RoomStore roomStore,
                          RoomService roomService,
                          RoomRegistry registry) {
        this(roomStore, roomService, registry, new ObjectMapper());
    }

    public ControlHandler(RoomStore roomStore,
                          RoomService roomService,
                          RoomRegistry registry,
                          ObjectMapper objectMapper) {
        this.roomStore = roomStore;
        this.roomService = roomService;
        this.registry = registry;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
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

        // Validate room id from path
        String path = uri.getPath();
        Matcher m = CONTROL_PATH.matcher(path == null ? "" : path);
        if (!m.matches()) {
            log.warn("Control: closing {}: path does not match control-socket pattern", session.getId());
            session.close(new CloseStatus(4400, "Malformed path"));
            return;
        }
        String roomId = m.group(1);
        if (!ROOM_ID_OK.matcher(roomId).matches()) {
            log.warn("Control: closing {}: malformed room id", session.getId());
            session.close(new CloseStatus(4400, "Malformed room id"));
            return;
        }

        // Validate session_id
        String clientSessionId = queryParam(uri.getQuery(), "session_id");
        if (clientSessionId == null || !UUID_V4_OK.matcher(clientSessionId).matches()) {
            log.warn("Control: closing {}: session_id missing or not a UUID v4", session.getId());
            session.close(new CloseStatus(4400, "session_id missing or not a UUID v4"));
            return;
        }

        // 4404: unknown room — the v0 create-on-connect behaviour is removed in T-20
        if (!roomStore.exists(roomId)) {
            log.warn("Control: closing {}: room {} does not exist (4404)", session.getId(), roomId);
            session.close(new CloseStatus(CLOSE_UNKNOWN_ROOM, "Room not found"));
            return;
        }

        session.getAttributes().put(ATTR_ROOM_ID, roomId);
        session.getAttributes().put(ATTR_SESSION_ID, clientSessionId);
        session.getAttributes().put(ATTR_JOINED, false);

        registry.add(roomId, session);
        log.info("Control: connected wsSession={} room={}", session.getId(), roomId);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session,
                                      @NonNull CloseStatus status) {
        registry.remove(session);
        log.info("Control: closed wsSession={} room={} status={}",
                session.getId(), session.getAttributes().get(ATTR_ROOM_ID), status);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session,
                                     @NonNull Throwable ex) {
        registry.remove(session);
        log.warn("Control: transport error wsSession={}: {}", session.getId(), ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // Message handling — control socket accepts text frames only
    // -----------------------------------------------------------------------

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session,
                                     @NonNull TextMessage message) throws Exception {
        String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);
        if (roomId == null) {
            return; // rejected connection; message arrived before close propagated
        }

        Boolean alreadyJoined = (Boolean) session.getAttributes().get(ATTR_JOINED);

        if (Boolean.TRUE.equals(alreadyJoined)) {
            // After join, additional text control messages are silently ignored for now
            // (further messages such as role changes are handled in US-35, US-43).
            log.debug("Control: ignoring post-join text frame from wsSession={}", session.getId());
            return;
        }

        // --- Process the first text frame as a join message ---
        handleJoin(session, roomId, message.getPayload());
    }

    // -----------------------------------------------------------------------
    // Join processing
    // -----------------------------------------------------------------------

    private void handleJoin(WebSocketSession session, String roomId, String rawText) throws Exception {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawText);
        } catch (Exception e) {
            sendText(session, objectMapper.writeValueAsString(
                    RoomMessages.joinRefused("INVALID_MESSAGE", "Join message is not valid JSON")));
            closeSessionQuietly(session);
            return;
        }

        JsonNode typeNode = root.get("type");
        if (typeNode == null || !"join".equals(typeNode.asText())) {
            sendText(session, objectMapper.writeValueAsString(
                    RoomMessages.joinRefused("INVALID_MESSAGE",
                            "First message must be a 'join' frame")));
            closeSessionQuietly(session);
            return;
        }

        JsonNode nameNode = root.get("display_name");
        if (nameNode == null || nameNode.asText().isBlank()) {
            sendText(session, objectMapper.writeValueAsString(
                    RoomMessages.joinRefused("INVALID_MESSAGE", "display_name is required")));
            closeSessionQuietly(session);
            return;
        }
        String displayName = nameNode.asText().trim();

        // owner_token is optional — present for the Host only
        String ownerToken = root.has("owner_token") ? root.get("owner_token").asText(null) : null;

        String clientSessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);

        JoinOutcome outcome = roomService.join(roomId, clientSessionId, displayName, ownerToken);

        if (!outcome.isJoined()) {
            String reason = outcome.getRefusedReason();
            String humanMessage = "NAME_TAKEN".equals(reason)
                    ? "The display name '" + displayName + "' is already taken in this room."
                    : "Join refused: " + reason;
            sendText(session, objectMapper.writeValueAsString(
                    RoomMessages.joinRefused(reason, humanMessage)));
            log.info("Control: join refused room={} reason={}", roomId, reason);

            // Contract V2.3 (review 2): close the control socket immediately after
            // join-refused, EXCEPT for APPROVAL_PENDING where it must remain open
            // until the server later sends join-approved or join-declined.
            if (!"APPROVAL_PENDING".equals(reason)) {
                closeSessionQuietly(session);
            }
            return;
        }

        // Successful join
        session.getAttributes().put(ATTR_JOINED, true);
        Member member = outcome.getMember();
        Room room = roomStore.get(roomId);

        // 1. Reply to the joining session with 'joined'
        sendText(session, objectMapper.writeValueAsString(RoomMessages.joined(room, member)));

        // 2. Broadcast updated roster to every session in the room (including the new member)
        String rosterJson = objectMapper.writeValueAsString(RoomMessages.roster(room));
        for (WebSocketSession target : registry.getAll(roomId)) {
            sendText(target, rosterJson);
        }

        log.info("Control: joined room={} member={} role={}",
                roomId, member.getMemberId(), member.getRole());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Send a text frame to {@code target}, synchronized to prevent concurrent-send errors.
     * Never logs the payload (NFR10).
     */
    void sendText(WebSocketSession target, String text) {
        try {
            if (target.isOpen()) {
                synchronized (target) {
                    target.sendMessage(new TextMessage(text));
                }
            }
        } catch (IOException e) {
            log.warn("Control: send failed to wsSession={}: {}", target.getId(), e.getMessage());
        }
    }

    /**
     * Close {@code session} with {@link CloseStatus#NORMAL}, swallowing any
     * {@link IOException} that the WebSocket container may throw when the
     * connection is already half-closed (the message was sent; the frame is
     * flushed before this call).
     */
    private void closeSessionQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.NORMAL);
        } catch (IOException ex) {
            log.warn("Control: failed to close session after join-refused wsSession={}: {}",
                    session.getId(), ex.getMessage());
        }
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
}
