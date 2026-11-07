import * as decoding from 'lib0/decoding'
import * as encoding from 'lib0/encoding'
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
export const CLOSE_UNKNOWN_ROOM = 4404
export const CLOSE_UNKNOWN_DOC = 4405
export const CLOSE_NOT_JOINED = 4406
export const CLOSE_ROOM_CLOSED = 4410

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

export interface JoinedMessage {
  type: 'joined'
  member_id: string
  role: string
  mode: string
  preset?: string
  permissions?: {
    can_edit: boolean
    can_run: boolean
  }
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
  permissions?: {
    can_edit: boolean
    can_run: boolean
  }
}

export interface RosterMessage {
  type: 'roster'
  members: RosterMember[]
  [key: string]: unknown
}

export interface ConnectAwarenessWsOptions {
  roomId: string
  displayName?: string
  ownerToken?: string
  docId?: string
  awareness: Awareness
  syncWsUrl?: string
  sessionId?: string
  WebSocketClass?: typeof WebSocket
  onJoined?: (msg: JoinedMessage) => void
  onJoinRefused?: (msg: JoinRefusedMessage) => void
  onRoster?: (msg: RosterMessage) => void
}

export interface AwarenessWsConnection {
  controlWs: WebSocket | null
  docWs: WebSocket | null
  join: (displayName: string, ownerToken?: string) => void
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
    awareness,
    syncWsUrl = getClientConfig().syncWsUrl,
    sessionId = getCollabSessionId(),
    WebSocketClass = typeof WebSocket !== 'undefined' ? WebSocket : undefined,
    onJoined,
    onJoinRefused,
    onRoster,
  } = options

  if (!WebSocketClass) {
    return {
      controlWs: null,
      docWs: null,
      join: () => {},
      destroy: () => {},
    }
  }

  let docWs: WebSocket | null = null
  let isDestroyed = false

  // 1. Establish Control Socket connection
  const controlUrl = buildControlWebSocketUrl(syncWsUrl, roomId, sessionId)
  const controlWs = new WebSocketClass(controlUrl)

  const sendJoin = (name: string, token?: string) => {
    if (controlWs.readyState === WS_OPEN) {
      const payload: Record<string, string> = {
        type: 'join',
        display_name: name,
      }
      if (token) {
        payload.owner_token = token
      }
      controlWs.send(JSON.stringify(payload))
    }
  }

  controlWs.onopen = () => {
    if (displayName) {
      sendJoin(displayName, ownerToken)
    }
  }

  // Document socket awareness update listener
  let onDocAwarenessUpdate:
    | ((
        update: { added: number[]; updated: number[]; removed: number[] },
        origin: unknown,
      ) => void)
    | null = null

  // 2. Open Document Socket upon receiving 'joined'
  const openDocumentSocket = () => {
    if (isDestroyed) return
    const docUrl = buildDocumentWebSocketUrl(syncWsUrl, roomId, docId, sessionId)
    docWs = new WebSocketClass(docUrl)
    docWs.binaryType = 'arraybuffer'

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

    awareness.on('update', onDocAwarenessUpdate)

    docWs.onopen = () => {
      // Contract Rule 6: query awareness from existing participants upon connecting
      sendBinary(docWs!, encodeQueryAwarenessMessage())

      // Advertise local state if already present
      if (awareness.getLocalState() !== null) {
        sendBinary(docWs!, encodeAwarenessMessage(awareness, [awareness.clientID]))
      }
    }

    docWs.onmessage = (event: MessageEvent) => {
      if (event.data instanceof ArrayBuffer || event.data instanceof Uint8Array) {
        handleIncomingAwarenessFrame(awareness, event.data, docWs!)
      }
    }
  }

  controlWs.onmessage = (event: MessageEvent) => {
    if (typeof event.data === 'string') {
      try {
        const msg = JSON.parse(event.data) as { type?: string }
        if (msg.type === 'joined') {
          onJoined?.(msg as JoinedMessage)
          openDocumentSocket()
        } else if (msg.type === 'join-refused') {
          onJoinRefused?.(msg as JoinRefusedMessage)
        } else if (msg.type === 'roster') {
          onRoster?.(msg as RosterMessage)
        }
      } catch {
        // ignore non-json control frames
      }
    }
  }

  const join = (name: string, token?: string) => {
    if (controlWs.readyState === WS_OPEN) {
      sendJoin(name, token)
    } else if (typeof controlWs.addEventListener === 'function') {
      controlWs.addEventListener(
        'open',
        () => {
          sendJoin(name, token)
        },
        { once: true },
      )
    } else {
      const prevOnOpen = controlWs.onopen
      controlWs.onopen = (ev: Event) => {
        prevOnOpen?.call(controlWs, ev)
        sendJoin(name, token)
      }
    }
  }

  const destroy = () => {
    isDestroyed = true
    if (onDocAwarenessUpdate) {
      awareness.off('update', onDocAwarenessUpdate)
      onDocAwarenessUpdate = null
    }

    if (docWs) {
      if (docWs.readyState === WS_OPEN) {
        try {
          removeAwarenessStates(awareness, [awareness.clientID], docWs)
          sendBinary(docWs, encodeAwarenessMessage(awareness, [awareness.clientID]))
        } catch {
          // ignore send error during closing
        }
        docWs.close()
      } else if (docWs.readyState === WS_CONNECTING) {
        docWs.close()
      }
      docWs = null
    }

    if (controlWs.readyState === WS_OPEN || controlWs.readyState === WS_CONNECTING) {
      controlWs.close()
    }
  }

  return {
    get controlWs() {
      return controlWs
    },
    get docWs() {
      return docWs
    },
    join,
    destroy,
  }
}
