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
const WS_CONNECTING = 0
const WS_OPEN = 1

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
 * Builds the WebSocket connection URL per T-06 §3.1:
 * ws://localhost:8081/ws/rooms/{room_id}?session_id={session_id}
 */
export function buildRoomWebSocketUrl(
  syncWsUrl: string = DEFAULT_SYNC_WS_URL,
  roomId: string,
  sessionId?: string,
): string {
  const base = syncWsUrl.replace(/\/+$/, '')
  const session = sessionId ?? getCollabSessionId()
  return `${base}/${encodeURIComponent(roomId)}?session_id=${encodeURIComponent(session)}`
}

/** Wire message type constants matching y-websocket and T-06 §3.4/§3.5 */
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
 * Dispatches an incoming binary message from the WebSocket relay into the local Awareness instance.
 */
export function handleIncomingAwarenessFrame(
  awareness: Awareness,
  data: Uint8Array | ArrayBuffer,
  ws: { send: (msg: unknown) => void; readyState?: number },
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
        ws.send(response)
      }
    }
  }
}

export interface ConnectAwarenessWsOptions {
  roomId: string
  awareness: Awareness
  syncWsUrl?: string
  sessionId?: string
  WebSocketClass?: typeof WebSocket
}

export interface AwarenessWsConnection {
  ws: WebSocket | null
  destroy: () => void
}

/**
 * Connects an Awareness instance to the sync-service WebSocket relay (T-02 / T-06 / US-05).
 * Synchronizes display names, assigned colours, and presence frames across room participants.
 */
export function connectAwarenessWs(
  options: ConnectAwarenessWsOptions,
): AwarenessWsConnection {
  const {
    roomId,
    awareness,
    syncWsUrl = getClientConfig().syncWsUrl,
    sessionId = getCollabSessionId(),
    WebSocketClass = typeof WebSocket !== 'undefined' ? WebSocket : undefined,
  } = options

  if (!WebSocketClass) {
    return {
      ws: null,
      destroy: () => {},
    }
  }

  const url = buildRoomWebSocketUrl(syncWsUrl, roomId, sessionId)
  const ws = new WebSocketClass(url)
  ws.binaryType = 'arraybuffer'

  const onAwarenessUpdate = (
    {
      added,
      updated,
      removed,
    }: { added: number[]; updated: number[]; removed: number[] },
    origin: unknown,
  ) => {
    // Avoid re-transmitting frames we received from this websocket
    if (origin === ws) return
    const changedClients = added.concat(updated).concat(removed)
    if (ws.readyState === WS_OPEN && changedClients.length > 0) {
      ws.send(encodeAwarenessMessage(awareness, changedClients))
    }
  }

  awareness.on('update', onAwarenessUpdate)

  ws.onopen = () => {
    // Contract Rule 6: query awareness from existing participants upon connecting
    ws.send(encodeQueryAwarenessMessage())

    // Advertise local state if already present
    if (awareness.getLocalState() !== null) {
      ws.send(encodeAwarenessMessage(awareness, [awareness.clientID]))
    }
  }

  ws.onmessage = (event: MessageEvent) => {
    if (event.data instanceof ArrayBuffer || event.data instanceof Uint8Array) {
      handleIncomingAwarenessFrame(awareness, event.data, ws)
    }
  }

  const destroy = () => {
    awareness.off('update', onAwarenessUpdate)
    if (ws.readyState === WS_OPEN) {
      try {
        removeAwarenessStates(awareness, [awareness.clientID], ws)
        ws.send(encodeAwarenessMessage(awareness, [awareness.clientID]))
      } catch {
        // ignore send error on teardown
      }
      ws.close()
    } else if (ws.readyState === WS_CONNECTING) {
      ws.close()
    }
  }

  return { ws, destroy }
}
