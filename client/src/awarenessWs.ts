import * as decoding from 'lib0/decoding'
import * as encoding from 'lib0/encoding'
import * as syncProtocol from 'y-protocols/sync'
import * as Y from 'yjs'
import {
  applyAwarenessUpdate,
  encodeAwarenessUpdate,
  removeAwarenessStates,
  type Awareness,
} from 'y-protocols/awareness'
import { DEFAULT_SYNC_WS_URL, getClientConfig } from './config'

export const COLLAB_SESSION_ID_KEY = 'collab_session_id'

const UUID_V4_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

/**
 * Standard WebSocket readyState constants (RFC 6455).
 */
export const WS_CONNECTING = 0
export const WS_OPEN = 1
export const WS_CLOSING = 2
export const WS_CLOSED = 3

/**
 * T-20 Close codes (docs/api-contracts.md & sync-service).
 */
/** A removed session that was already connected (V2.3, V2.18). Terminal: never retry. */
export const CLOSE_REMOVED = 4403
export const CLOSE_UNKNOWN_ROOM = 4404
export const CLOSE_UNKNOWN_DOC = 4405
export const CLOSE_NOT_JOINED = 4406
export const CLOSE_ROOM_CLOSED = 4410
/** The Host regenerated the link (V2.18). Terminal for the old id: rejoin only with the new link. */
export const CLOSE_LINK_REGENERATED = 4411

/** Default join-handshake timeout in milliseconds. */
export const DEFAULT_JOIN_TIMEOUT_MS = 5000

class MockCloseEvent extends Event {
  code: number
  reason: string
  constructor(type: string, init?: { code?: number; reason?: string }) {
    super(type)
    this.code = init?.code ?? 1000
    this.reason = init?.reason ?? ''
  }
}

export const SafeCloseEvent =
  typeof CloseEvent !== 'undefined'
    ? CloseEvent
    : (MockCloseEvent as unknown as typeof CloseEvent)

/**
 * Generates a valid UUID v4 compliant with RFC 4122 (T-06 §3.3).
 */
export function generateUuidV4(): string {
  if (typeof crypto !== 'undefined') {
    if (typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID()
    }
    if (typeof crypto.getRandomValues === 'function') {
      const bytes = new Uint8Array(16)
      crypto.getRandomValues(bytes)
      bytes[6] = (bytes[6] & 0x0f) | 0x40
      bytes[8] = (bytes[8] & 0x3f) | 0x80
      const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
    }
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/**
 * Retrieves the stable session ID from localStorage or creates and stores a new UUID v4 (T-06 §3.3).
 */
export function getCollabSessionId(
  storage: Storage | null = typeof localStorage !== 'undefined' ? localStorage : null,
): string {
  if (!storage) {
    return generateUuidV4()
  }
  try {
    const existing = storage.getItem(COLLAB_SESSION_ID_KEY)
    if (existing && UUID_V4_REGEX.test(existing)) {
      return existing
    }
    const newId = generateUuidV4()
    storage.setItem(COLLAB_SESSION_ID_KEY, newId)
    return newId
  } catch {
    return generateUuidV4()
  }
}

/**
 * Builds the Control WebSocket URL (T-20 / T-17 Section 6.1):
 * ws://localhost:8081/ws/rooms/{room_id}?session_id={session_id}
 */
export function buildControlWebSocketUrl(
  syncWsUrl: string = DEFAULT_SYNC_WS_URL,
  roomId: string,
  sessionId?: string,
): string {
  const base = syncWsUrl.replace(/\/+$/, '')
  const session = sessionId ?? getCollabSessionId()
  return `${base}/${encodeURIComponent(roomId)}?session_id=${encodeURIComponent(session)}`
}

/**
 * Builds the Document WebSocket URL (T-20 / T-17 Section 6.1):
 * ws://localhost:8081/ws/rooms/{room_id}/docs/{doc_id}?session_id={session_id}
 */
export function buildDocumentWebSocketUrl(
  syncWsUrl: string = DEFAULT_SYNC_WS_URL,
  roomId: string,
  docId = 'main',
  sessionId?: string,
): string {
  const base = syncWsUrl.replace(/\/+$/, '')
  const session = sessionId ?? getCollabSessionId()
  return `${base}/${encodeURIComponent(roomId)}/docs/${encodeURIComponent(docId)}?session_id=${encodeURIComponent(session)}`
}

/**
 * Backward-compatible alias for buildControlWebSocketUrl.
 */
export function buildRoomWebSocketUrl(
  syncWsUrl: string = DEFAULT_SYNC_WS_URL,
  roomId: string,
  sessionId?: string,
): string {
  return buildControlWebSocketUrl(syncWsUrl, roomId, sessionId)
}

/** Wire binary message type constants matching y-websocket and T-06 §3.4/§3.5 / T-20 */
export const MESSAGE_SYNC = 0
export const MESSAGE_AWARENESS = 1
export const MESSAGE_AUTH = 2
export const MESSAGE_QUERY_AWARENESS = 3

/**
 * Encodes an awareness update message with message type 1 prefix (T-06 §3.5 Rule 1).
 */
export function encodeAwarenessMessage(
  awareness: Awareness,
  clients: number[],
): Uint8Array {
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, MESSAGE_AWARENESS)
  encoding.writeVarUint8Array(
    encoder,
    encodeAwarenessUpdate(awareness, clients),
  )
  return encoding.toUint8Array(encoder)
}

/**
 * Encodes a query-awareness frame containing single byte 3 (T-06 §3.5 Rule 6).
 */
export function encodeQueryAwarenessMessage(): Uint8Array {
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, MESSAGE_QUERY_AWARENESS)
  return encoding.toUint8Array(encoder)
}

/**
 * Encodes a Yjs sync step 1 message (MESSAGE_SYNC = 0, followed by syncStep1) (FR2 / US-02).
 */
export function encodeSyncStep1Message(doc: Y.Doc): Uint8Array {
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, MESSAGE_SYNC)
  syncProtocol.writeSyncStep1(encoder, doc)
  return encoding.toUint8Array(encoder)
}

/**
 * Encodes a Yjs sync step 2 message (MESSAGE_SYNC = 0, followed by syncStep2) (FR2 / US-02).
 */
export function encodeSyncStep2Message(doc: Y.Doc): Uint8Array {
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, MESSAGE_SYNC)
  syncProtocol.writeSyncStep2(encoder, doc)
  return encoding.toUint8Array(encoder)
}

/**
 * Encodes a Yjs document update message (MESSAGE_SYNC = 0, followed by update) (FR2 / US-02).
 */
export function encodeDocUpdateMessage(update: Uint8Array): Uint8Array {
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, MESSAGE_SYNC)
  syncProtocol.writeUpdate(encoder, update)
  return encoding.toUint8Array(encoder)
}

export interface HandleIncomingSyncFrameOptions {
  replyWithReverseSyncStep1?: boolean
}

/**
 * Dispatches an incoming binary sync frame into the local Y.Doc instance and returns any reply frames (FR2 / US-02).
 * Implements an explicitly supported multi-message decoding loop to process concatenated sync messages cleanly.
 */
export function handleIncomingSyncFrame(
  doc: Y.Doc,
  data: Uint8Array | ArrayBuffer,
  origin: unknown,
  options?: HandleIncomingSyncFrameOptions,
): Uint8Array[] {
  const uint8 = data instanceof ArrayBuffer ? new Uint8Array(data) : data
  if (!uint8 || uint8.length === 0) return []

  const decoder = decoding.createDecoder(uint8)
  const messageType = decoding.readVarUint(decoder)
  if (messageType !== MESSAGE_SYNC) return []

  const replies: Uint8Array[] = []
  while (decoding.hasContent(decoder)) {
    const replyEncoder = encoding.createEncoder()
    encoding.writeVarUint(replyEncoder, MESSAGE_SYNC)
    const syncType = syncProtocol.readSyncMessage(decoder, replyEncoder, doc, origin)

    if (encoding.length(replyEncoder) > 1) {
      replies.push(encoding.toUint8Array(replyEncoder))
    }

    if (syncType === syncProtocol.messageYjsSyncStep1 && options?.replyWithReverseSyncStep1) {
      // Send reverse syncStep1 as its OWN distinct, correctly-prefixed MESSAGE_SYNC frame
      const reverseEncoder = encoding.createEncoder()
      encoding.writeVarUint(reverseEncoder, MESSAGE_SYNC)
      syncProtocol.writeSyncStep1(reverseEncoder, doc)
      replies.push(encoding.toUint8Array(reverseEncoder))
    }
  }

  return replies
}

type WebSocketSendData = Parameters<WebSocket['send']>[0]

function sendBinary(
  ws: { send: (data: WebSocketSendData) => void },
  data: Uint8Array,
): void {
  ws.send(data as unknown as WebSocketSendData)
}

/**
 * Dispatches an incoming binary message from the WebSocket relay into the local Awareness instance.
 */
export function handleIncomingAwarenessFrame(
  awareness: Awareness,
  data: Uint8Array | ArrayBuffer,
  ws: { send: (msg: WebSocketSendData) => void; readyState?: number },
): void {
  const uint8 = data instanceof ArrayBuffer ? new Uint8Array(data) : data
  if (!uint8 || uint8.length === 0) return

  const decoder = decoding.createDecoder(uint8)
  const messageType = decoding.readVarUint(decoder)

  if (messageType === MESSAGE_AWARENESS) {
    const update = decoding.readVarUint8Array(decoder)
    applyAwarenessUpdate(awareness, update, ws)
  } else if (messageType === MESSAGE_QUERY_AWARENESS) {
    // Reply to query with all known active participant states (T-06 §3.5 Rule 6)
    if (awareness.getLocalState() !== null) {
      const response = encodeAwarenessMessage(
        awareness,
        Array.from(awareness.getStates().keys()),
      )
      if (!('readyState' in ws) || ws.readyState === WS_OPEN) {
        sendBinary(ws, response)
      }
    }
  }
}

/** Why a member may not run (V2.20). `null` or absent when they may. */
export type RunDeniedReason = 'observer' | 'run_revoked' | 'host_only'

export interface MemberPermissions {
  /** Pair mode only; Open mode takes write access from module state. */
  can_edit?: boolean
  can_run: boolean
  run_denied_reason?: RunDeniedReason | null
}

export interface JoinedMessage {
  type: 'joined'
  member_id: string
  role: string
  mode: string
  preset?: string
  ai_enabled?: boolean
  permissions?: MemberPermissions
  [key: string]: unknown
}

export interface JoinRefusedMessage {
  type: 'join-refused'
  reason: string
  message: string
  [key: string]: unknown
}

export interface RosterMember {
  member_id: string
  display_name: string
  role: string
  permissions?: MemberPermissions
}

export interface RoomStateMessage {
  type: 'room-state'
  ai_enabled: boolean
  [key: string]: unknown
}

export interface RosterMessage {
  type: 'roster'
  members: RosterMember[]
  /** Host-only running is on (V2.20): only the Host can run. Absent from older servers. */
  host_only_run?: boolean
  ai_enabled?: boolean
  [key: string]: unknown
}

/**
 * Sent only to the new Host after a transfer (V2.19). Carries the new owner token: store it,
 * never log or display it.
 */
export interface HostGrantedMessage {
  type: 'host-granted'
  member_id: string
  owner_token: string
  [key: string]: unknown
}

export interface EditRejectedMessage {
  type: 'edit-rejected'
  doc_id?: string
  reason?: string
  [key: string]: unknown
}

export interface RunResultMessage {
  type: 'run-result'
  request_id: string
  room_id: string
  [key: string]: unknown
}

export interface AnalysisResultMessage {
  type: 'analysis-result'
  request_id: string
  analysis_id: string
  room_id: string
  task: string
  status: 'ok' | 'fallback' | 'error'
  ai_step: {
    status: 'ok' | 'skipped'
    error_code: string | null
    [key: string]: unknown
  }
  result?: Record<string, unknown>
  message: string
  [key: string]: unknown
}

export interface ModuleUpdatedMessage {
  type: 'module-updated'
  module_id: string
  name: string
  owner_member_id: string
  writers?: string[]
  runnable?: boolean
  is_entry?: boolean
  version?: number
  pending?: boolean
  deleted?: boolean
  [key: string]: unknown
}

export interface ConnectAwarenessWsOptions {
  roomId: string
  displayName?: string
  ownerToken?: string
  docId?: string
  resolveDocId?: (joined: JoinedMessage) => Promise<string | null> | string | null
  awareness: Awareness
  /**
   * Optional active Y.Doc instance to synchronize over the Document Socket (US-02 / FR2).
   */
  doc?: Y.Doc
  /** Registered Open-mode module documents retained across transport reconnects. */
  moduleDocs?: ReadonlyMap<string, Y.Doc>
  syncWsUrl?: string
  sessionId?: string
  WebSocketClass?: typeof WebSocket
  joinTimeoutMs?: number
  onJoined?: (msg: JoinedMessage) => void
  onJoinRefused?: (msg: JoinRefusedMessage) => void
  onRoster?: (msg: RosterMessage) => void
  onRoomState?: (msg: RoomStateMessage) => void
  /** The Host role was handed to this member (V2.19); the message holds the new owner token. */
  onHostGranted?: (msg: HostGrantedMessage) => void
  onEditRejected?: (msg: EditRejectedMessage) => void
  /** A run finished in this room (T-17 section 3.6); delivered to every participant. */
  onRunResult?: (msg: RunResultMessage) => void
  /** An AI analysis completed or fell back in this room (T-18 / US-78). */
  onAnalysisResult?: (msg: AnalysisResultMessage) => void
  /** A module was created, approved, updated or deleted in Open mode (V2.6, US-39). */
  onModuleUpdated?: (msg: ModuleUpdatedMessage) => void
  onError?: (event: Event) => void
  onClose?: (event: CloseEvent) => void
  onDocError?: (event: Event) => void
  onDocClose?: (event: CloseEvent) => void
  onJoinTimeout?: () => void
  onReconnecting?: (attempt: number) => void
  onReconnected?: () => void
}

export interface AwarenessWsConnection {
  controlWs: WebSocket | null
  docWs: WebSocket | null
  /** Additional Open-mode module transports, indexed by opaque module ID. */
  moduleSockets: ReadonlyMap<string, WebSocket>
  join: (displayName: string, ownerToken?: string) => void
  switchDoc?: (newDocId: string, newDoc?: Y.Doc) => void
  destroy: () => void
}

/**
 * Connects to sync-service following the T-20 two-socket protocol:
 * 1. Opens Control Socket (/ws/rooms/{roomId}?session_id={sessionId}).
 * 2. Sends { type: 'join', display_name, owner_token? } and awaits { type: 'joined' }.
 * 3. Opens Document Socket (/ws/rooms/{roomId}/docs/{docId}?session_id={sessionId}).
 * 4. Relays Yjs Awareness frames (MESSAGE_AWARENESS=1, MESSAGE_QUERY_AWARENESS=3) over the Document Socket.
 */
export function connectAwarenessWs(
  options: ConnectAwarenessWsOptions,
): AwarenessWsConnection {
  const {
    roomId,
    displayName,
    ownerToken,
    docId = 'main',
    resolveDocId,
    awareness,
    doc,
    moduleDocs,
    syncWsUrl = getClientConfig().syncWsUrl,
    sessionId = getCollabSessionId(),
    WebSocketClass = typeof WebSocket !== 'undefined' ? WebSocket : undefined,
    joinTimeoutMs = DEFAULT_JOIN_TIMEOUT_MS,
    onJoined,
    onJoinRefused,
    onRoster,
    onRoomState,
    onHostGranted,
    onEditRejected,
    onRunResult,
    onAnalysisResult,
    onModuleUpdated,
    onError,
    onClose,
    onDocError,
    onDocClose,
    onJoinTimeout,
    onReconnecting,
    onReconnected,
  } = options

  if (!WebSocketClass) {
    return {
      controlWs: null,
      docWs: null,
      moduleSockets: new Map(),
      join: () => {},
      destroy: () => {},
    }
  }

  let controlWs: WebSocket | null = null
  let docWs: WebSocket | null = null
  let activeDocId: string | undefined = docId
  let currentActiveDoc: Y.Doc | undefined = doc
  let lastJoinedMsg: JoinedMessage | null = null
  const moduleSockets = new Map<string, WebSocket>()
  // Production RoomPage does not supply moduleDocs. Own inactive module docs here
  // so every approved Open-mode module can participate in Yjs resynchronization.
  const managedModuleDocs = new Map<string, Y.Doc>()
  const getModuleDoc = (id: string) => moduleDocs?.get(id) ?? managedModuleDocs.get(id)
  const allModuleDocs = () => new Map([...managedModuleDocs, ...(moduleDocs ?? new Map<string, Y.Doc>())])
  const moduleUpdateHandlers = new Map<string, (update: Uint8Array, origin: unknown) => void>()
  const closeModuleSocket = (id: string) => {
    const socket = moduleSockets.get(id)
    if (!socket) return
    const moduleDoc = getModuleDoc(id)
    const handler = moduleUpdateHandlers.get(id)
    if (moduleDoc && handler) moduleDoc.off('update', handler)
    moduleUpdateHandlers.delete(id)
    moduleSockets.delete(id)
    socket.onopen = null
    socket.onmessage = null
    socket.onclose = null
    socket.onerror = null
    if (socket.readyState === WS_OPEN || socket.readyState === WS_CONNECTING) socket.close()
  }
  const closeModuleSockets = () => {
    for (const id of [...moduleSockets.keys()]) closeModuleSocket(id)
  }

  /** Open every explicitly registered local Y.Doc, not just the editor's active module. */
  const openModuleSockets = (activeId: string) => {
    if (isDestroyed) return
    for (const [id, moduleDoc] of allModuleDocs()) {
      if (!id || id === 'main' || id === activeId || moduleSockets.has(id)) continue
      const socket = new WebSocketClass!(buildDocumentWebSocketUrl(syncWsUrl, roomId, id, sessionId))
      socket.binaryType = 'arraybuffer'
      moduleSockets.set(id, socket)
      let reverseSyncSent = false
      const handler = (update: Uint8Array, origin: unknown) => {
        if (origin !== socket && socket.readyState === WS_OPEN) {
          sendBinary(socket, encodeDocUpdateMessage(update))
        }
      }
      moduleUpdateHandlers.set(id, handler)
      moduleDoc.on('update', handler)
      socket.onopen = () => {
        if (isDestroyed || moduleSockets.get(id) !== socket) return
        sendBinary(socket, encodeSyncStep1Message(moduleDoc))
        sendBinary(socket, encodeSyncStep2Message(moduleDoc))
      }
      socket.onmessage = (event: MessageEvent) => {
        if (moduleSockets.get(id) !== socket) return
        if (!(event.data instanceof ArrayBuffer || event.data instanceof Uint8Array)) return
        const bytes = event.data instanceof ArrayBuffer ? new Uint8Array(event.data) : event.data
        if (!bytes.length || decoding.readVarUint(decoding.createDecoder(bytes)) !== MESSAGE_SYNC) return
        const replies = handleIncomingSyncFrame(moduleDoc, bytes, socket, {
          replyWithReverseSyncStep1: !reverseSyncSent,
        })
        if (replies.length > 1) reverseSyncSent = true
        for (const reply of replies) if (socket.readyState === WS_OPEN) sendBinary(socket, reply)
      }
      socket.onerror = (event: Event) => onDocError?.(event)
      socket.onclose = (event: CloseEvent) => {
        if (moduleSockets.get(id) !== socket || isDestroyed) return
        moduleDoc.off('update', handler)
        moduleUpdateHandlers.delete(id)
        moduleSockets.delete(id)
        onDocClose?.(event)
        if (terminalCodes.has(event.code)) {
          terminal = true
          reconnecting = false
          clearReconnectTimer()
        } else {
          scheduleReconnect()
        }
      }
    }
  }
  let isDestroyed = false
  let activeGeneration = 0
  let joinedGeneration = -1
  let pendingJoin: { name: string; token?: string } | null = null
  let activeHandshake: { name: string; token?: string } | null = null
  let joinTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectAttempts = 0
  let hasJoined = false
  let reconnecting = false
  let waitingForDoc = false
  let terminal = false
  let lastJoin: { name: string; token?: string } | null =
    displayName ? { name: displayName, token: ownerToken } : null
  // A retry on any of these can never succeed (V2.18, "Client reconnect").
  const terminalCodes = new Set([CLOSE_REMOVED, CLOSE_UNKNOWN_ROOM, CLOSE_ROOM_CLOSED, CLOSE_LINK_REGENERATED])

  const clearReconnectTimer = () => {
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  const clearJoinTimer = () => {
    if (joinTimer) {
      clearTimeout(joinTimer)
      joinTimer = null
    }
  }

  const handleTimeout = (timedOutGen: number) => {
    if (isDestroyed || timedOutGen !== activeGeneration) return

    // 1. Invalidate current attempt so late responses are ignored
    activeGeneration += 1

    // 2. Clear timer and pending state
    clearJoinTimer()
    pendingJoin = null
    activeHandshake = null

    // 3. Cleanly close previous control socket
    if (controlWs) {
      const oldWs = controlWs
      controlWs = null
      try {
        oldWs.onopen = null
        oldWs.onmessage = null
        oldWs.onerror = null
        oldWs.onclose = null
        if (oldWs.readyState === WS_OPEN || oldWs.readyState === WS_CONNECTING) {
          oldWs.close(1000, 'Join timeout')
        }
      } catch {
        // ignore
      }
    }

    // 4. A reconnect handshake timeout must retry rather than stall forever.
    if (hasJoined && !terminal) scheduleReconnect()
    else onJoinTimeout?.()
  }

  function scheduleReconnect() {
    if (isDestroyed || terminal || !hasJoined || !lastJoin || reconnectTimer !== null) return
    reconnecting = true
    waitingForDoc = false
    reconnectAttempts += 1
    onReconnecting?.(reconnectAttempts)
    const delay = Math.min(1000 * 2 ** Math.min(reconnectAttempts - 1, 5), 30000)
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      if (isDestroyed || terminal) return
      closeDocumentSocket()
      if (controlWs) {
        const old = controlWs
        controlWs = null
        old.onopen = null
        old.onmessage = null
        old.onerror = null
        old.onclose = null
        if (old.readyState === WS_OPEN || old.readyState === WS_CONNECTING) old.close()
      }
      controlWs = initControlSocket()
      startJoinTimer(activeGeneration)
    }, delay)
  }

  const startJoinTimer = (gen: number) => {
    clearJoinTimer()
    if (joinTimeoutMs > 0) {
      joinTimer = setTimeout(() => {
        if (isDestroyed || gen !== activeGeneration) return
        handleTimeout(gen)
      }, joinTimeoutMs)
    }
  }

  const sendJoin = (ws: WebSocket, name: string, gen: number, token?: string) => {
    if (isDestroyed || gen !== activeGeneration) return
    if (ws.readyState === WS_OPEN) {
      activeHandshake = { name, token }
      lastJoin = { name, token }
      const payload: Record<string, string> = {
        type: 'join',
        display_name: name,
      }
      if (token) {
        payload.owner_token = token
      }
      try {
        ws.send(JSON.stringify(payload))
      } catch {
        clearJoinTimer()
        activeHandshake = null
      }
    }
  }

  // Document socket awareness & Yjs sync listeners
  let onDocAwarenessUpdate:
    | ((
        update: { added: number[]; updated: number[]; removed: number[] },
        origin: unknown,
      ) => void)
    | null = null
  let onDocUpdate: ((update: Uint8Array, origin: unknown) => void) | null = null

  const closeDocumentSocket = () => {
    closeModuleSockets()
    if (onDocAwarenessUpdate) {
      awareness.off('update', onDocAwarenessUpdate)
      onDocAwarenessUpdate = null
    }
    if (currentActiveDoc && onDocUpdate) {
      currentActiveDoc.off('update', onDocUpdate)
      onDocUpdate = null
    }
    const old = docWs
    docWs = null
    if (old) {
      old.onopen = null
      old.onmessage = null
      old.onerror = null
      old.onclose = null
      if (old.readyState === WS_OPEN || old.readyState === WS_CONNECTING) {
        old.close()
      }
    }
  }

  // 2. Open Document Socket upon receiving 'joined'
  const openDocumentSocket = async (joinedMsg?: JoinedMessage) => {
    if (isDestroyed || docWs) return

    let resolvedDocId = activeDocId ?? docId
    const roomMode = joinedMsg?.mode ? String(joinedMsg.mode).toLowerCase() : undefined

    if (roomMode === 'pair') {
      resolvedDocId = 'main'
    } else if (roomMode === 'open') {
      let approvedIds: Set<string> | null = null
      // Refresh the approved module list after every successful control join.
      // Never open document sockets before the server confirms membership.
      try {
        const httpBase = syncWsUrl.replace(/^ws/, 'http').replace(/\/ws\/rooms.*$/, '')
        const res = await fetch(`${httpBase}/v1/rooms/${encodeURIComponent(roomId)}/modules`, {
          headers: { 'X-Session-Id': sessionId },
        })
        if (!res.ok) throw new Error('Could not retrieve approved modules')
        const modules = (await res.json()) as Array<{
          module_id: string
          is_entry?: boolean
          pending?: boolean
        }>
        if (!Array.isArray(modules)) throw new Error('Invalid module response')
        const approved = modules.filter((m) => m.module_id && !m.pending)
        approvedIds = new Set(approved.map((m) => m.module_id))
        // Close revoked sockets and detach listeners before destroying their docs.
        for (const id of [...moduleSockets.keys()]) {
          if (!approvedIds.has(id)) closeModuleSocket(id)
        }
        for (const [id, existing] of managedModuleDocs) {
          if (!approvedIds.has(id)) {
            managedModuleDocs.delete(id)
            existing.destroy()
          }
        }
        for (const module of approved) {
          if (!moduleDocs?.has(module.module_id) && !managedModuleDocs.has(module.module_id)) {
            managedModuleDocs.set(module.module_id, new Y.Doc())
          }
        }
        if (!approvedIds.has(resolvedDocId)) {
          resolvedDocId = approved.find((m) => m.is_entry)?.module_id ??
            approved[0]?.module_id ?? 'main'
        }
      } catch {
        // Do not guess additional module IDs when the approved list is unavailable.
        // A known explicitly selected module may still reconnect.
      }
      if (resolveDocId && joinedMsg) {
        try {
          const customId = await resolveDocId(joinedMsg)
          if (customId && approvedIds?.has(customId)) resolvedDocId = customId
        } catch {
          // Keep the server-approved entry module.
        }
      }
      // Never connect an Open-mode document that the server did not approve.
      if (approvedIds && !approvedIds.has(resolvedDocId)) resolvedDocId = ''
    } else if (!resolvedDocId) {
      resolvedDocId = 'main'
    }

    if (
      !resolvedDocId ||
      (roomMode === 'open' && resolvedDocId === 'main')
    ) {
      waitingForDoc = false
      onDocClose?.(
        new SafeCloseEvent('close', {
          code: CLOSE_UNKNOWN_DOC,
          reason: 'No registered module document found for Open room',
        }),
      )
      return
    }

    if (isDestroyed || docWs) return

    const docUrl = buildDocumentWebSocketUrl(syncWsUrl, roomId, resolvedDocId, sessionId)
    docWs = new WebSocketClass(docUrl)
    docWs.binaryType = 'arraybuffer'
    if (roomMode === 'open') openModuleSockets(resolvedDocId)

    docWs.onerror = (event: Event) => {
      onDocError?.(event)
    }

    const openedSocket = docWs
    docWs.onclose = (event: CloseEvent) => {
      if (docWs !== openedSocket || isDestroyed) return
      closeDocumentSocket()
      onDocClose?.(event)
      if (terminalCodes.has(event.code)) {
        terminal = true
        reconnecting = false
        clearReconnectTimer()
      } else {
        scheduleReconnect()
      }
    }

    onDocAwarenessUpdate = (
      {
        added,
        updated,
        removed,
      }: { added: number[]; updated: number[]; removed: number[] },
      origin: unknown,
    ) => {
      if (origin === docWs) return
      const changedClients = added.concat(updated).concat(removed)
      if (docWs && docWs.readyState === WS_OPEN && changedClients.length > 0) {
        sendBinary(docWs, encodeAwarenessMessage(awareness, changedClients))
      }
    }

    if (currentActiveDoc) {
      onDocUpdate = (update: Uint8Array, origin: unknown) => {
        if (origin === docWs) return
        if (docWs && docWs.readyState === WS_OPEN) {
          sendBinary(docWs, encodeDocUpdateMessage(update))
        }
      }
      currentActiveDoc.on('update', onDocUpdate)
    }

    awareness.on('update', onDocAwarenessUpdate)

    let hasSentReverseSync = false

    docWs.onopen = () => {
      if (isDestroyed || docWs !== openedSocket) return
      waitingForDoc = false
      // 1. Send sync step 1 and step 2 to exchange initial state bidirectionally (US-02 / FR2)
      if (currentActiveDoc) {
        sendBinary(docWs!, encodeSyncStep1Message(currentActiveDoc))
        sendBinary(docWs!, encodeSyncStep2Message(currentActiveDoc))
      }

      // 2. Contract Rule 6: query awareness from existing participants upon connecting
      sendBinary(docWs!, encodeQueryAwarenessMessage())

      // 3. Advertise local state if already present
      if (awareness.getLocalState() !== null) {
        sendBinary(docWs!, encodeAwarenessMessage(awareness, [awareness.clientID]))
      }
      // The transport has reopened and the initial sync frames were sent.
      // This does not guarantee that the remote state has already converged.
      if (reconnecting) {
        reconnecting = false
        reconnectAttempts = 0
        onReconnected?.()
      }
    }

    docWs.onmessage = (event: MessageEvent) => {
      if (event.data instanceof ArrayBuffer || event.data instanceof Uint8Array) {
        const uint8 = event.data instanceof ArrayBuffer ? new Uint8Array(event.data) : event.data
        if (!uint8 || uint8.length === 0) return

        const decoder = decoding.createDecoder(uint8)
        const messageType = decoding.readVarUint(decoder)

        if (messageType === MESSAGE_SYNC) {
          if (currentActiveDoc && docWs) {
            const allowReverse = !hasSentReverseSync
            const replies = handleIncomingSyncFrame(currentActiveDoc, uint8, docWs, {
              replyWithReverseSyncStep1: allowReverse,
            })
            if (allowReverse && replies.length > 1) {
              hasSentReverseSync = true
            }
            for (const reply of replies) {
              if (docWs.readyState === WS_OPEN) {
                sendBinary(docWs, reply)
              }
            }
          }
        } else if (messageType === MESSAGE_AWARENESS || messageType === MESSAGE_QUERY_AWARENESS) {
          handleIncomingAwarenessFrame(awareness, uint8, docWs!)
        }
      }
    }
  }

  const initControlSocket = (): WebSocket | null => {
    if (isDestroyed) return null

    activeGeneration += 1
    const gen = activeGeneration
    clearJoinTimer()

    const controlUrl = buildControlWebSocketUrl(syncWsUrl, roomId, sessionId)
    const ws = new WebSocketClass(controlUrl)

    ws.onopen = () => {
      if (isDestroyed || gen !== activeGeneration) return
      if (pendingJoin) {
        const { name, token } = pendingJoin
        pendingJoin = null
        sendJoin(ws, name, gen, token)
      } else if (lastJoin) {
        sendJoin(ws, lastJoin.name, gen, lastJoin.token)
      }
    }

    ws.onmessage = (event: MessageEvent) => {
      if (isDestroyed || gen !== activeGeneration) return
      if (typeof event.data === 'string') {
        try {
          const msg = JSON.parse(event.data) as { type?: string }
          if (msg.type === 'joined') {
            // Only one document handshake per control-socket generation, even
            // if the relay repeats 'joined' before the async module fetch ends.
            if (joinedGeneration === gen) return
            joinedGeneration = gen
            hasJoined = true
            clearReconnectTimer()
            clearJoinTimer()
            pendingJoin = null
            activeHandshake = null
            lastJoinedMsg = msg as JoinedMessage
            onJoined?.(msg as JoinedMessage)
            waitingForDoc = true
            void openDocumentSocket(msg as JoinedMessage).catch(() => {
              if (!isDestroyed && waitingForDoc) scheduleReconnect()
            })
          } else if (msg.type === 'join-refused') {
            // A definitive refusal must not cause an automatic join loop.
            terminal = true
            reconnecting = false
            clearReconnectTimer()
            clearJoinTimer()
            pendingJoin = null
            activeHandshake = null
            onJoinRefused?.(msg as JoinRefusedMessage)
          } else if (msg.type === 'roster') {
            onRoster?.(msg as RosterMessage)
          } else if (msg.type === 'room-state') {
            onRoomState?.(msg as RoomStateMessage)
          } else if (msg.type === 'host-granted') {
            onHostGranted?.(msg as HostGrantedMessage)
          } else if (msg.type === 'edit-rejected') {
            onEditRejected?.(msg as EditRejectedMessage)
          } else if (msg.type === 'run-result') {
            onRunResult?.(msg as RunResultMessage)
          } else if (msg.type === 'analysis-result') {
            onAnalysisResult?.(msg as AnalysisResultMessage)
          } else if (msg.type === 'module-updated') {
            onModuleUpdated?.(msg as ModuleUpdatedMessage)
          }
        } catch {
          // ignore non-json control frames
        }
      }
    }

    ws.onerror = (event: Event) => {
      if (isDestroyed || gen !== activeGeneration) return
      clearJoinTimer()
      pendingJoin = null
      activeHandshake = null
      onError?.(event)
    }

    ws.onclose = (event: CloseEvent) => {
      if (isDestroyed || gen !== activeGeneration) return
      clearJoinTimer()
      pendingJoin = null
      activeHandshake = null
      if (controlWs === ws) {
        controlWs = null
      }
      closeDocumentSocket()
      onClose?.(event)
      if (terminalCodes.has(event.code)) {
        terminal = true
        reconnecting = false
        clearReconnectTimer()
      } else {
        scheduleReconnect()
      }
    }

    return ws
  }

  controlWs = initControlSocket()
  if (displayName) {
    activeHandshake = { name: displayName, token: ownerToken }
    startJoinTimer(activeGeneration)
  }

  const join = (name: string, token?: string) => {
    if (isDestroyed) return

    // Prevent duplicate join attempts while a request is already queued or active.
    if (activeHandshake) return

    // V2.3 closes the control socket after join-refused. A retry must never
    // send another join on that refused socket, even if its close event is
    // still in flight. Reset terminal state only for an explicit user retry.
    if (terminal) {
      terminal = false
      reconnecting = false
      clearReconnectTimer()
      closeDocumentSocket()
      const refusedSocket = controlWs
      controlWs = null
      if (refusedSocket) {
        refusedSocket.onopen = null
        refusedSocket.onmessage = null
        refusedSocket.onerror = null
        refusedSocket.onclose = null
        if (refusedSocket.readyState === WS_OPEN || refusedSocket.readyState === WS_CONNECTING) {
          refusedSocket.close()
        }
      }
    }

    activeHandshake = { name, token }
    const activeControlWs = controlWs
    if (!activeControlWs || activeControlWs.readyState === WS_CLOSED || activeControlWs.readyState === WS_CLOSING) {
      // Reconnect with a fresh WebSocket attempt
      pendingJoin = { name, token }
      controlWs = initControlSocket()
      startJoinTimer(activeGeneration)
      return
    }

    if (activeControlWs.readyState === WS_CONNECTING) {
      // Queue request: ws.onopen will send it exactly once without duplicate listeners
      pendingJoin = { name, token }
      startJoinTimer(activeGeneration)
      return
    }

    if (activeControlWs.readyState === WS_OPEN) {
      pendingJoin = null
      startJoinTimer(activeGeneration)
      sendJoin(activeControlWs, name, activeGeneration, token)
    }
  }

  const destroy = () => {
    isDestroyed = true
    reconnecting = false
    clearReconnectTimer()
    activeGeneration += 1
    pendingJoin = null
    activeHandshake = null
    clearJoinTimer()
    if (docWs) {
      const activeDocWs = docWs
      docWs = null
      if (activeDocWs.readyState === WS_OPEN) {
        try {
          removeAwarenessStates(awareness, [awareness.clientID], activeDocWs)
          sendBinary(activeDocWs, encodeAwarenessMessage(awareness, [awareness.clientID]))
        } catch {
          // ignore send error during closing
        }
        activeDocWs.close()
      } else if (activeDocWs.readyState === WS_CONNECTING) {
        activeDocWs.close()
      }
    }

    closeDocumentSocket()
    for (const moduleDoc of managedModuleDocs.values()) moduleDoc.destroy()
    managedModuleDocs.clear()

    if (controlWs) {
      const activeControlWs = controlWs
      controlWs = null
      try {
        activeControlWs.onopen = null
        activeControlWs.onmessage = null
        activeControlWs.onerror = null
        activeControlWs.onclose = null
        if (activeControlWs.readyState === WS_OPEN || activeControlWs.readyState === WS_CONNECTING) {
          activeControlWs.close()
        }
      } catch {
        // ignore
      }
    }
  }

  const switchDoc = (newDocId: string, newDoc?: Y.Doc) => {
    if (isDestroyed || !newDocId) return
    if (newDocId === activeDocId && (newDoc === undefined || newDoc === currentActiveDoc)) return
    activeDocId = newDocId
    if (newDoc) {
      currentActiveDoc = newDoc
    } else {
      currentActiveDoc = getModuleDoc(newDocId) ?? doc
    }
    if (moduleSockets.has(newDocId)) {
      closeModuleSocket(newDocId)
    }
    closeDocumentSocket()
    if (hasJoined) {
      waitingForDoc = true
      void openDocumentSocket(lastJoinedMsg ?? undefined).catch(() => {
        if (!isDestroyed && waitingForDoc) scheduleReconnect()
      })
    }
  }

  return {
    get controlWs() {
      return controlWs
    },
    get docWs() {
      return docWs
    },
    moduleSockets,
    join,
    switchDoc,
    destroy,
  }
}
