package com.collabcode.syncservice.handler;

import com.collabcode.syncservice.module.ModuleService;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Document-socket handler (T-20, Bhavya part).
 *
 * <p>Path: {@code /ws/rooms/{room_id}/docs/{doc_id}?session_id=<UUID v4>}
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Carries Yjs binary frames only (text frames are ignored).</li>
 *   <li>Rejects connections to unknown rooms with close code 4404.</li>
 *   <li>Rejects connections before join with close code 4406 (T-17 V2.5).</li>
 *   <li>Enforces document isolation: frames are only forwarded to sessions
 *       subscribed to the exact same {@code doc_id}.</li>
 *   <li>Enforces the v0 relay rules:
 *     <ul>
 *       <li>Forward bytes unchanged to every other session on the same document.</li>
 *       <li>Echo awareness frames (first byte == 0x01) back to the sender.</li>
 *       <li>Close with 1009 if a binary frame exceeds 1 MiB.</li>
 *     </ul>
 *   </li>
 *   <li>Checks edit permission (CR13) by inspecting only the first two bytes of
 *       each binary frame without parsing the document:
 *     <ul>
 *       <li>Byte 0 == {@code 0}: Yjs sync message.</li>
 *       <li>Byte 1 == {@code 1} (step 2) or {@code 2} (update): carries document changes.</li>
 *       <li>Byte 1 == {@code 0} (step 1): state vector exchange / request for state (read-only).</li>
 *       <li>If the sending member's {@code canEdit} flag is false, the frame is
 *           dropped and an {@code edit-rejected} text frame is sent on the
 *           <em>control</em> channel with the T-17 reason ({@code no_edit_access},
 *           {@code not_module_writer}, or {@code observer}).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>Yjs byte values (y-protocols 1.x / T-17 Section V2.12)</h2>
 * <pre>
 *   Byte 0 = message type
 *     0  sync
 *     1  awareness
 *     3  query awareness
 *   Byte 1 (when Byte 0 == 0, i.e. sync)
 *     0  sync step 1 (request for state — read-only, always forwarded)
 *     1  sync step 2 (missing updates — carries document changes)
 *     2  sync update (incremental update — carries document changes)
 * </pre>
 *
 * <h2>What this class does NOT do</h2>
 * <ul>
 *   <li>Parse or modify the Yjs document content (CR13).</li>
 *   <li>Log message contents or document content (NFR10).</li>
 * </ul>
 */
public class DocumentHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentHandler.class);

    // --- Close codes (T-17 / T-20) ---

    /** Unknown room id — the room does not exist on this server (T-17 V2.2). */
    public static final int CLOSE_UNKNOWN_ROOM = 4404;

    /** Unknown doc_id / module_id on the document socket (T-17 V2.5). */
    public static final int CLOSE_UNKNOWN_DOC = 4405;

    /** Not joined — document socket opened before join on control socket (T-17 V2.5). */
    public static final int CLOSE_NOT_JOINED = 4406;

    /** Room is closed / destroyed (FR31.2, T-17 V2.5). */
    public static final int CLOSE_ROOM_CLOSED = 4410;

    // --- Yjs byte values (y-protocols 1.x / T-17 Section V2.12) ---

    /** Yjs message type: sync (0). */
    public static final byte YJS_SYNC = 0;

    /** Yjs sync sub-type: step 1 (0) — state vector exchange / request for state. */
    public static final byte YJS_SYNC_STEP1 = 0;

    /** Yjs sync sub-type: step 2 (1) — carries document changes. */
    public static final byte YJS_SYNC_STEP2 = 1;

    /** Yjs sync sub-type: update (2) — carries document changes. */
    public static final byte YJS_SYNC_UPDATE = 2;

    /** Yjs message type: awareness (1). */
    public static final byte AWARENESS_BYTE = 1;

    /** Maximum binary frame size: 1 MiB (contract section 3.5 rule 3). */
    private static final int MAX_BINARY_BYTES = 1024 * 1024;

    // --- Validation patterns (same as T-02) ---
    private static final Pattern ROOM_ID_OK = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern DOC_ID_OK  = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern UUID_V4_OK = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    /** Path pattern for the document socket: /ws/rooms/{roomId}/docs/{docId} */
    private static final Pattern DOC_PATH =
            Pattern.compile("^/ws/rooms/([^/]+)/docs/([^/]+)$");

    /** Attribute key: validated room id. */
    public static final String ATTR_ROOM_ID = "roomId";

    /** Attribute key: doc id extracted from the URL path. */
    public static final String ATTR_DOC_ID = "docId";

    /** Attribute key: stable client-supplied session id. */
    public static final String ATTR_SESSION_ID = "sessionId";

    private final RoomStore roomStore;
    private final RoomService roomService;
    private final ModuleService moduleService;
    private final RoomRegistry registry;
    private final ObjectMapper objectMapper;

    /**
     * The registry that holds the <em>control</em> sockets. The document handler
     * needs it so it can send the {@code edit-rejected} text frame to the sender's
     * control session (the control socket is in the control registry, which is
     * the same {@link RoomRegistry} bean but logically refers to control sessions).
     */
    private final RoomRegistry controlRegistry;

    public DocumentHandler(RoomStore roomStore,
                           RoomService roomService,
                           ModuleService moduleService,
                           RoomRegistry registry) {
        this(roomStore, roomService, moduleService, registry, registry, new ObjectMapper());
    }

    public DocumentHandler(RoomStore roomStore,
                           RoomService roomService,
                           ModuleService moduleService,
                           RoomRegistry registry,
                           ObjectMapper objectMapper) {
        this(roomStore, roomService, moduleService, registry, registry, objectMapper);
    }

    public DocumentHandler(RoomStore roomStore,
                           RoomService roomService,
                           ModuleService moduleService,
                           RoomRegistry registry,
                           RoomRegistry controlRegistry,
                           ObjectMapper objectMapper) {
        this.roomStore = roomStore;
        this.roomService = roomService;
        this.moduleService = moduleService;
        this.registry = registry;
        this.controlRegistry = controlRegistry != null ? controlRegistry : registry;
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

        String path = uri.getPath();
        Matcher m = DOC_PATH.matcher(path == null ? "" : path);
        if (!m.matches()) {
            log.warn("Doc: closing {}: path does not match document-socket pattern", session.getId());
            session.close(new CloseStatus(4400, "Malformed path"));
            return;
        }
        String roomId = m.group(1);
        String docId  = m.group(2);

        if (!ROOM_ID_OK.matcher(roomId).matches()) {
            log.warn("Doc: closing {}: malformed room id", session.getId());
            session.close(new CloseStatus(4400, "Malformed room id"));
            return;
        }
        if (!DOC_ID_OK.matcher(docId).matches()) {
            log.warn("Doc: closing {}: malformed doc id", session.getId());
            session.close(new CloseStatus(4400, "Malformed doc id"));
            return;
        }

        String clientSessionId = queryParam(uri.getQuery(), "session_id");
        if (clientSessionId == null || !UUID_V4_OK.matcher(clientSessionId).matches()) {
            log.warn("Doc: closing {}: session_id missing or not a UUID v4", session.getId());
            session.close(new CloseStatus(4400, "session_id missing or not a UUID v4"));
            return;
        }

        // 4404: unknown room — create-on-connect is removed in T-20
        Room room = roomStore.get(roomId);
        if (room == null) {
            log.warn("Doc: closing {}: room {} does not exist (4404)", session.getId(), roomId);
            session.close(new CloseStatus(CLOSE_UNKNOWN_ROOM, "Room not found"));
            return;
        }

        // 4410: closed room (FR31.2, T-17 V2.5)
        if (room.isClosed()) {
            log.warn("Doc: closing {}: room {} is closed (4410)", session.getId(), roomId);
            session.close(new CloseStatus(CLOSE_ROOM_CLOSED, "Room is closed"));
            return;
        }

        // 4406: document socket opened before join on control socket (T-17 V2.5)
        if (roomService.findMemberBySession(roomId, clientSessionId) == null) {
            log.warn("Doc: closing {}: session {} has not joined room {} (4406)",
                    session.getId(), clientSessionId, roomId);
            session.close(new CloseStatus(CLOSE_NOT_JOINED, "Not joined"));
            return;
        }

        // 4405: doc_id validation (contract V2.5)
        //   Pair mode  — only 'main' is a valid doc_id.
        //   Open mode  — doc_id is a module_id; it must have been registered in the room
        //                before the socket is opened.
        if (room.getMode() == RoomMode.PAIR) {
            if (!"main".equals(docId)) {
                log.warn("Doc: closing {}: doc_id '{}' is not 'main' in Pair room {} (4405)",
                        session.getId(), docId, roomId);
                session.close(new CloseStatus(CLOSE_UNKNOWN_DOC, "Unknown doc_id"));
                return;
            }
        } else {
            // Open mode: validate against the authoritative, approved module state.
            if (!moduleService.isActive(roomId, docId)) {
                log.warn("Doc: closing {}: module_id '{}' not registered in Open room {} (4405)",
                        session.getId(), docId, roomId);
                session.close(new CloseStatus(CLOSE_UNKNOWN_DOC, "Unknown module_id"));
                return;
            }
        }

        session.getAttributes().put(ATTR_ROOM_ID, roomId);
        session.getAttributes().put(ATTR_DOC_ID, docId);
        session.getAttributes().put(ATTR_SESSION_ID, clientSessionId);

        registry.add(roomId, session);
        log.info("Doc: connected wsSession={} room={} doc={}", session.getId(), roomId, docId);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session,
                                      @NonNull CloseStatus status) {
        registry.remove(session);
        log.info("Doc: closed wsSession={} room={} status={}",
                session.getId(), session.getAttributes().get(ATTR_ROOM_ID), status);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session,
                                     @NonNull Throwable ex) {
        registry.remove(session);
        log.warn("Doc: transport error wsSession={}: {}", session.getId(), ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // Message handling — document socket carries binary Yjs frames only
    // -----------------------------------------------------------------------

    @Override
    protected void handleBinaryMessage(@NonNull WebSocketSession session,
                                       @NonNull BinaryMessage message) throws Exception {
        byte[] bytes = toByteArray(message.getPayload());

        // Rule: reject frames > 1 MiB (contract section 3.5 rule 3)
        if (bytes.length > MAX_BINARY_BYTES) {
            log.warn("Doc: binary frame too large ({} bytes) — closing wsSession={}", bytes.length, session.getId());
            session.close(new CloseStatus(1009, "Binary frame exceeds 1 MiB"));
            return;
        }

        String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);
        String docId  = (String) session.getAttributes().get(ATTR_DOC_ID);
        if (roomId == null) {
            return; // connection was rejected; message arrived before close propagated
        }

        // --- Edit permission check (CR13) ---
        // Inspect only the first two bytes; never parse the document content.
        if (isDocumentChange(bytes)) {
            String clientSessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);
            Member member = roomService.findMemberBySession(roomId, clientSessionId);

            Room room = roomStore.get(roomId);
            boolean canEdit = room != null && room.getMode() == RoomMode.OPEN
                    ? moduleService.canEdit(roomId, docId, member)
                    : member != null && member.isCanEdit();
            if (!canEdit) {
                // Determine rejection reason according to T-17 Section V2.12
                String reason;
                if (member != null && member.getRole() == Role.OBSERVER) {
                    reason = "observer";
                } else if (room != null && room.getMode() == RoomMode.OPEN) {
                    reason = "not_module_writer";
                } else {
                    reason = "no_edit_access";
                }

                log.debug("Doc: edit-rejected room={} doc={} reason={} — session not permitted",
                        roomId, docId, reason);
                sendEditRejected(session, roomId, docId, clientSessionId, reason);
                return;
            }
        }

        // --- v0 relay rules with document-level isolation ---
        boolean isAwareness = bytes.length > 0 && bytes[0] == AWARENESS_BYTE;

        // Forward only to sessions in the SAME room subscribed to the SAME doc_id
        List<WebSocketSession> others = registry.getOthers(roomId, session);
        for (WebSocketSession target : others) {
            Object targetDocId = target.getAttributes().get(ATTR_DOC_ID);
            if (docId != null && docId.equals(targetDocId)) {
                sendBinaryBytes(target, bytes);
            }
        }

        // Echo awareness frames back to the sender (lone-client heartbeat)
        if (isAwareness) {
            sendBinaryBytes(session, bytes);
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session,
                                     @NonNull TextMessage message) {
        // The document socket carries binary Yjs frames only. Text frames are silently ignored.
        log.debug("Doc: ignoring unexpected text frame from wsSession={}", session.getId());
    }

    // -----------------------------------------------------------------------
    // Edit permission check (CR13)
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the first two bytes of the frame identify it as a
     * Yjs document change (sync step-2 or update), without parsing the document (CR13).
     *
     * <p>Contract reference: docs/api-contracts.md section V2.12:
     * <ul>
     *   <li>Byte 0 = 0, Byte 1 = 0: Sync step 1 (state request) — forwarded (NOT a change)</li>
     *   <li>Byte 0 = 0, Byte 1 = 1: Sync step 2 (missing updates) — carries document changes</li>
     *   <li>Byte 0 = 0, Byte 1 = 2: Sync update (incremental update) — carries document changes</li>
     *   <li>Byte 0 = 1: Awareness — forwarded (NOT a document change)</li>
     *   <li>Byte 0 = 3: Query awareness — forwarded (NOT a document change)</li>
     * </ul>
     */
    public static boolean isDocumentChange(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return false;
        }
        return bytes[0] == YJS_SYNC && (bytes[1] == YJS_SYNC_STEP2 || bytes[1] == YJS_SYNC_UPDATE);
    }

    /**
     * Send an {@code edit-rejected} text frame to the sender's <em>control</em> session.
     *
     * <p>The message is sent on the control channel (not on the document channel), so the
     * client can show the user a meaningful notification without confusing the Yjs protocol.
     * The frame is never forwarded to other sessions.
     */
    private void sendEditRejected(WebSocketSession docSession,
                                   String roomId,
                                   String docId,
                                   String clientSessionId,
                                   String reason) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "edit-rejected");
        msg.put("doc_id", docId);
        msg.put("reason", reason);

        try {
            String json = objectMapper.writeValueAsString(msg);

            // Deliver on the control session for this client (looked up by session_id)
            WebSocketSession controlSession =
                    controlRegistry.findBySessionId(roomId, clientSessionId);
            if (controlSession != null && controlSession.isOpen()) {
                synchronized (controlSession) {
                    controlSession.sendMessage(new TextMessage(json));
                }
            } else {
                // Contract: the document socket carries ONLY binary Yjs frames.
                // If the control session is unavailable, log and drop silently
                // (do NOT send a text frame via the document socket).
                log.warn("Doc: control session unavailable for wsSession={}; edit-rejected dropped",
                        docSession.getId());
            }
        } catch (IOException e) {
            log.warn("Doc: failed to send edit-rejected to wsSession={}: {}", docSession.getId(), e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void sendBinaryBytes(WebSocketSession target, byte[] bytes) {
        try {
            if (target.isOpen()) {
                synchronized (target) {
                    target.sendMessage(new BinaryMessage(bytes));
                }
            }
        } catch (IOException e) {
            log.warn("Doc: send failed to wsSession={}: {}", target.getId(), e.getMessage());
        }
    }

    private static byte[] toByteArray(ByteBuffer buf) {
        ByteBuffer view = buf.duplicate();
        view.rewind();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

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
