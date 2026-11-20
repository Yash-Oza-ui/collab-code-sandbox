import { describe, expect, it, vi } from 'vitest'
import * as encoding from 'lib0/encoding'
import * as syncProtocol from 'y-protocols/sync'
import * as Y from 'yjs'
import { createAwareness, getExistingUserColors, setUserAwareness } from './awareness'
import {
  CLOSE_LINK_REGENERATED,
  CLOSE_NOT_JOINED,
  CLOSE_REMOVED,
  CLOSE_UNKNOWN_DOC,
  CLOSE_UNKNOWN_ROOM,
  COLLAB_SESSION_ID_KEY,
  MESSAGE_AWARENESS,
  MESSAGE_QUERY_AWARENESS,
  MESSAGE_SYNC,
  buildControlWebSocketUrl,
  buildDocumentWebSocketUrl,
  buildRoomWebSocketUrl,
  connectAwarenessWs,
  encodeAwarenessMessage,
  encodeDocUpdateMessage,
  encodeQueryAwarenessMessage,
  encodeSyncStep1Message,
  encodeSyncStep2Message,
  generateUuidV4,
  getCollabSessionId,
  handleIncomingSyncFrame,
} from './awarenessWs'
import { USER_COLORS, pickUserColor } from './userColor'

interface RelayRoomSession {
  sessionId: string
  displayName: string
  role: string
  canEdit: boolean
  canRun: boolean
}

/**
 * High-fidelity in-memory WebSocket Relay replicating sync-service T-20 architecture:
 * - ControlHandler.java: /ws/rooms/{roomId}?session_id=<UUID>
 *   Handles { type: 'join', display_name }, replies with 'joined', broadcasts 'roster'.
 * - DocumentHandler.java: /ws/rooms/{roomId}/docs/{docId}?session_id=<UUID>
 *   Rejects with close code 4406 if opened before join on control socket.
 *   Forwards binary Yjs/Awareness frames within the same room and docId.
 *   Echoes awareness frames (first byte == 1) back to sender (lone-client heartbeat).
 */
class TestRelay {
  rooms = new Map<string, Map<string, RelayRoomSession>>()
  controlClients = new Map<TestWebSocket, { roomId: string; sessionId: string }>()
  docClients = new Map<TestWebSocket, { roomId: string; docId: string; sessionId: string }>()
  refuseNames = new Set<string>()
  roomModes = new Map<string, 'pair' | 'open'>()
  roomModules = new Map<string, Set<string>>()
  ignoreJoins = false
  autoOpen = true

  createClient(url: string, autoOpen?: boolean): TestWebSocket {
    return new TestWebSocket(url, this, autoOpen ?? this.autoOpen)
  }

  handleOpen(ws: TestWebSocket): void {
    const parsed = this.parseUrl(ws.url)
    if (!parsed) {
      ws.close(4400, 'Malformed URL')
      return
    }

    if (parsed.type === 'control') {
      this.controlClients.set(ws, { roomId: parsed.roomId, sessionId: parsed.sessionId })
      if (!this.rooms.has(parsed.roomId)) {
        this.rooms.set(parsed.roomId, new Map())
      }
    } else {
      // Document socket: reject if join has not completed on control socket (T-20 / T-17 V2.5)
      const room = this.rooms.get(parsed.roomId)
      const member = room?.get(parsed.sessionId)
      if (!member) {
        ws.close(CLOSE_NOT_JOINED, 'Not joined')
        return
      }

      // 4405: doc_id validation (Pair mode must be 'main', Open mode must be registered module)
      const mode = this.roomModes.get(parsed.roomId) ?? 'pair'
      if (mode === 'pair') {
        if (parsed.docId !== 'main') {
          ws.close(CLOSE_UNKNOWN_DOC, 'Unknown doc_id')
          return
        }
      } else {
        const modules = this.roomModules.get(parsed.roomId)
        if (!modules || !modules.has(parsed.docId)) {
          ws.close(CLOSE_UNKNOWN_DOC, 'Unknown module_id')
          return
        }
      }

      this.docClients.set(ws, {
        roomId: parsed.roomId,
        docId: parsed.docId,
        sessionId: parsed.sessionId,
      })
    }
  }

  handleTextMessage(ws: TestWebSocket, text: string): void {
    const meta = this.controlClients.get(ws)
    if (!meta) return // Document sockets ignore text frames (T-20)

    try {
      const msg = JSON.parse(text)
      if (msg.type === 'join') {
        if (this.ignoreJoins) {
          // Intentionally do not answer join request to test client join-handshake timeout
          return
        }

        if (this.refuseNames.has(msg.display_name)) {
          ws.receiveText(
            JSON.stringify({
              type: 'join-refused',
              reason: 'NAME_TAKEN',
              message: 'That display name is already in use in this room.',
            }),
          )
          ws.close(1000, 'Join refused')
          return
        }

        const room = this.rooms.get(meta.roomId) ?? new Map()
        this.rooms.set(meta.roomId, room)

        const session: RelayRoomSession = {
          sessionId: meta.sessionId,
          displayName: msg.display_name,
          role: 'Guest',
          canEdit: true,
          canRun: true,
        }
        room.set(meta.sessionId, session)

        const roomMode = this.roomModes.get(meta.roomId) ?? 'pair'

        // 1. Reply to joining client with 'joined'
        ws.receiveText(
          JSON.stringify({
            type: 'joined',
            member_id: meta.sessionId,
            role: session.role,
            mode: roomMode,
            permissions: { can_edit: true, can_run: true },
          }),
        )

        // 2. Broadcast roster to all control clients in this room
        const roster = {
          type: 'roster',
          members: Array.from(room.values()).map((m) => ({
            member_id: m.sessionId,
            display_name: m.displayName,
            role: m.role,
            permissions: { can_edit: m.canEdit, can_run: m.canRun },
          })),
        }
        const rosterJson = JSON.stringify(roster)
        for (const [client, clientMeta] of this.controlClients.entries()) {
          if (clientMeta.roomId === meta.roomId && client.readyState === TestWebSocket.OPEN) {
            client.receiveText(rosterJson)
          }
        }
      }
    } catch {
      // ignore invalid json
    }
  }

  handleBinaryMessage(ws: TestWebSocket, data: Uint8Array): void {
    const meta = this.docClients.get(ws)
    if (!meta) return // Control sockets ignore binary frames (T-20)

    // Edit permission check matching DocumentHandler.java (CR13)
    const isDocChange = data.length >= 2 && data[0] === 0 && (data[1] === 1 || data[1] === 2)
    if (isDocChange) {
      const roomSessions = this.rooms.get(meta.roomId)
      const session = roomSessions?.get(meta.sessionId)
      if (session && !session.canEdit) {
        // Drop frame and send edit-rejected on control socket
        for (const [ctrlClient, ctrlMeta] of this.controlClients.entries()) {
          if (ctrlMeta.roomId === meta.roomId && ctrlMeta.sessionId === meta.sessionId) {
            ctrlClient.receiveText(
              JSON.stringify({
                type: 'edit-rejected',
                doc_id: meta.docId,
                reason: 'no_edit_access',
              }),
            )
          }
        }
        return
      }
    }

    const isAwareness = data.length > 0 && data[0] === 1

    // Forward to other document clients on same room and docId
    for (const [client, clientMeta] of this.docClients.entries()) {
      if (
        client !== ws &&
        clientMeta.roomId === meta.roomId &&
        clientMeta.docId === meta.docId &&
        client.readyState === TestWebSocket.OPEN
      ) {
        client.receiveBinary(data)
      }
    }

    // Echo awareness frames back to sender (lone-client heartbeat / DocumentHandler)
    if (isAwareness && ws.readyState === TestWebSocket.OPEN) {
      ws.receiveBinary(data)
    }
  }

  handleClose(ws: TestWebSocket): void {
    this.controlClients.delete(ws)
    this.docClients.delete(ws)
  }

  private parseUrl(url: string) {
    const u = new URL(url.replace(/^ws/, 'http'))
    const sessionId = u.searchParams.get('session_id') || ''
    const docMatch = u.pathname.match(/\/ws\/rooms\/([^/]+)\/docs\/([^/]+)$/)
    if (docMatch) {
      return { type: 'doc' as const, roomId: docMatch[1], docId: docMatch[2], sessionId }
    }
    const controlMatch = u.pathname.match(/\/ws\/rooms\/([^/]+)$/)
    if (controlMatch) {
      return { type: 'control' as const, roomId: controlMatch[1], sessionId }
    }
    return null
  }
}

class MockCloseEvent extends Event {
  code: number
  reason: string
  wasClean: boolean
  constructor(type: string, init?: { code?: number; reason?: string; wasClean?: boolean }) {
    super(type)
    this.code = init?.code ?? 1000
    this.reason = init?.reason ?? ''
    this.wasClean = init?.wasClean ?? true
  }
}

class MockMessageEvent extends Event {
  data: unknown
  constructor(type: string, init?: { data?: unknown }) {
    super(type)
    this.data = init?.data
  }
}

const SafeCloseEvent =
  typeof CloseEvent !== 'undefined'
    ? CloseEvent
    : (MockCloseEvent as unknown as typeof CloseEvent)

const SafeMessageEvent =
  typeof MessageEvent !== 'undefined'
    ? MessageEvent
    : (MockMessageEvent as unknown as typeof MessageEvent)

class TestWebSocket {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3

  readonly CONNECTING = 0
  readonly OPEN = 1
  readonly CLOSING = 2
  readonly CLOSED = 3

  readyState = TestWebSocket.CONNECTING
  binaryType = 'arraybuffer'
  url: string

  onopen: ((event: Event) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null

  private listeners: Record<string, Set<(event: Event) => void>> = {}
  private relay: TestRelay
  autoOpen: boolean

  constructor(url: string, relay: TestRelay, autoOpen = true) {
    this.url = url
    this.relay = relay
    this.autoOpen = autoOpen
    if (this.autoOpen) {
      queueMicrotask(() => {
        if (this.readyState === TestWebSocket.CONNECTING) {
          this.triggerOpen()
        }
      })
    }
  }

  triggerOpen(): void {
    if (this.readyState === TestWebSocket.CONNECTING) {
      this.readyState = TestWebSocket.OPEN
      this.relay.handleOpen(this)
      if (this.readyState === TestWebSocket.OPEN) {
        const ev = new Event('open')
        this.onopen?.(ev)
        this.dispatchEvent('open', ev)
      }
    }
  }

  addEventListener(
    type: string,
    listener: (event: Event) => void,
    options?: { once?: boolean },
  ): void {
    if (!this.listeners[type]) this.listeners[type] = new Set()
    if (options?.once) {
      const onceWrapper = (e: Event) => {
        this.removeEventListener(type, onceWrapper)
        listener(e)
      }
      this.listeners[type].add(onceWrapper)
    } else {
      this.listeners[type].add(listener)
    }
  }

  removeEventListener(type: string, listener: (event: Event) => void): void {
    this.listeners[type]?.delete(listener)
  }

  private dispatchEvent(type: string, event: Event): void {
    this.listeners[type]?.forEach((fn) => fn(event))
  }

  send(data: unknown): void {
    if (this.readyState !== TestWebSocket.OPEN) return
    if (typeof data === 'string') {
      this.relay.handleTextMessage(this, data)
    } else if (data instanceof Uint8Array || data instanceof ArrayBuffer) {
      const uint8 = data instanceof ArrayBuffer ? new Uint8Array(data) : data
      this.relay.handleBinaryMessage(this, uint8)
    }
  }

  receiveText(text: string): void {
    if (this.readyState !== TestWebSocket.OPEN) return
    const ev = new SafeMessageEvent('message', { data: text })
    this.onmessage?.(ev as MessageEvent)
    this.dispatchEvent('message', ev)
  }

  receiveBinary(data: Uint8Array): void {
    if (this.readyState !== TestWebSocket.OPEN) return
    const buffer = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength)
    const ev = new SafeMessageEvent('message', { data: buffer })
    this.onmessage?.(ev as MessageEvent)
    this.dispatchEvent('message', ev)
  }

  close(code = 1000, reason = ''): void {
    if (this.readyState === TestWebSocket.CLOSED) return
    this.readyState = TestWebSocket.CLOSED
    this.relay.handleClose(this)
    const ev = new SafeCloseEvent('close', { code, reason })
    this.onclose?.(ev as CloseEvent)
    this.dispatchEvent('close', ev)
  }

  triggerError(): void {
    const ev = new Event('error')
    this.onerror?.(ev)
    this.dispatchEvent('error', ev)
  }
}

describe('awarenessWs helper functions', () => {
  it('generates valid RFC 4122 UUID v4 strings', () => {
    const id = generateUuidV4()
    expect(id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })

  it('manages stable session id in storage (T-06 §3.3)', () => {
    const memoryStorage: Record<string, string> = {}
    const mockStorage: Storage = {
      getItem: (key: string) => memoryStorage[key] ?? null,
      setItem: (key: string, val: string) => {
        memoryStorage[key] = val
      },
      removeItem: (key: string) => {
        delete memoryStorage[key]
      },
      clear: () => {
        for (const k of Object.keys(memoryStorage)) delete memoryStorage[k]
      },
      key: (i: number) => Object.keys(memoryStorage)[i] ?? null,
      get length() {
        return Object.keys(memoryStorage).length
      },
    }

    const firstId = getCollabSessionId(mockStorage)
    expect(firstId).toBeTruthy()
    expect(mockStorage.getItem(COLLAB_SESSION_ID_KEY)).toBe(firstId)

    const secondId = getCollabSessionId(mockStorage)
    expect(secondId).toBe(firstId)
  })

  it('builds control and document WebSocket URLs per T-20 / T-17', () => {
    const controlUrl = buildControlWebSocketUrl(
      'ws://localhost:8081/ws/rooms',
      'demo-room-42',
      '00000000-0000-4000-8000-000000000001',
    )
    expect(controlUrl).toBe(
      'ws://localhost:8081/ws/rooms/demo-room-42?session_id=00000000-0000-4000-8000-000000000001',
    )

    const docUrl = buildDocumentWebSocketUrl(
      'ws://localhost:8081/ws/rooms',
      'demo-room-42',
      'main',
      '00000000-0000-4000-8000-000000000001',
    )
    expect(docUrl).toBe(
      'ws://localhost:8081/ws/rooms/demo-room-42/docs/main?session_id=00000000-0000-4000-8000-000000000001',
    )

    // Backward compatible alias
    expect(buildRoomWebSocketUrl('ws://localhost:8081/ws/rooms', 'demo-room-42', '00000000-0000-4000-8000-000000000001')).toBe(controlUrl)
  })

  it('encodes awareness and query frames with correct prefix bytes', () => {
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    const awarenessFrame = encodeAwarenessMessage(awareness, [awareness.clientID])
    expect(awarenessFrame[0]).toBe(MESSAGE_AWARENESS)

    const queryFrame = encodeQueryAwarenessMessage()
    expect(queryFrame[0]).toBe(MESSAGE_QUERY_AWARENESS)
    expect(queryFrame.length).toBe(1)
  })
})

describe('T-20 WebSocket connection and multi-client awareness synchronization', () => {
  it('rejects document socket with close code 4406 if join handshake has not completed', async () => {
    const relay = new TestRelay()
    const docUrl = buildDocumentWebSocketUrl(
      'ws://localhost:8081/ws/rooms',
      'unjoined-room',
      'main',
      '99999999-9999-4999-8999-999999999999',
    )

    let closeCode = 0
    const rawWs = relay.createClient(docUrl)
    rawWs.onclose = (ev) => {
      closeCode = ev.code
    }

    await new Promise((r) => setTimeout(r, 15))
    expect(closeCode).toBe(CLOSE_NOT_JOINED)
  })

  it('completes control join handshake and synchronizes Alice and Bob presence over document socket', async () => {
    const relay = new TestRelay()

    // 1. Client A setup (Alice joins room)
    const docA = new Y.Doc()
    const awarenessA = createAwareness(docA)
    const connA = connectAwarenessWs({
      roomId: 'test-sync-room',
      displayName: 'Alice',
      awareness: awarenessA,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '11111111-1111-4111-8111-111111111111',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
    })

    // Wait for control open, join exchange, and doc socket open
    await new Promise((r) => setTimeout(r, 20))

    // Client A enters display name "Alice" and receives color
    const existingColorsA = getExistingUserColors(awarenessA)
    const colorA = pickUserColor(existingColorsA, docA.clientID)
    setUserAwareness(awarenessA, { name: 'Alice', color: colorA })

    // Give time for awareness frame propagation
    await new Promise((r) => setTimeout(r, 15))

    // 2. Client B setup (Bob joins the same room)
    const docB = new Y.Doc()
    const awarenessB = createAwareness(docB)
    const connB = connectAwarenessWs({
      roomId: 'test-sync-room',
      displayName: 'Bob',
      awareness: awarenessB,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '22222222-2222-4222-8222-222222222222',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
    })

    // Give time for connection open, join handshake, and doc socket query awareness exchange
    await new Promise((r) => setTimeout(r, 30))

    // Client B enters display name "Bob" and receives distinct color
    const existingColorsB = getExistingUserColors(awarenessB)
    expect(existingColorsB).toContain(colorA)

    const colorB = pickUserColor(existingColorsB, docB.clientID)
    setUserAwareness(awarenessB, { name: 'Bob', color: colorB })

    // Give time for Bob's frame to reach Alice
    await new Promise((r) => setTimeout(r, 25))

    // 3. Inspect Client A's awareness states
    const statesInA = Array.from(awarenessA.getStates().values())
    const aliceInA = statesInA.find((s) => s.user?.name === 'Alice')
    const bobInA = statesInA.find((s) => s.user?.name === 'Bob')

    expect(aliceInA?.user).toEqual({ name: 'Alice', color: colorA })
    expect(bobInA?.user).toEqual({ name: 'Bob', color: colorB })

    // Inspect Client B's awareness states
    const statesInB = Array.from(awarenessB.getStates().values())
    const aliceInB = statesInB.find((s) => s.user?.name === 'Alice')
    const bobInB = statesInB.find((s) => s.user?.name === 'Bob')

    expect(aliceInB?.user).toEqual({ name: 'Alice', color: colorA })
    expect(bobInB?.user).toEqual({ name: 'Bob', color: colorB })

    // Verify colours are distinct
    expect(colorA).not.toBe(colorB)
    expect(USER_COLORS).toContain(colorA)
    expect(USER_COLORS).toContain(colorB)

    // Teardown Client B connection cleanly
    connB.destroy()
    await new Promise((r) => setTimeout(r, 15))

    // Verify Client B state is removed from Client A
    const statesAfterDisconnect = Array.from(awarenessA.getStates().values())
    const bobAfterLeave = statesAfterDisconnect.find((s) => s.user?.name === 'Bob')
    expect(bobAfterLeave).toBeUndefined()

    connA.destroy()
  })

  it('handles join-refused without opening document socket, and allows reconnecting on subsequent join', async () => {
    const relay = new TestRelay()
    relay.refuseNames.add('TakenAlice')

    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    let refusedReason = ''
    let joined = false

    const conn = connectAwarenessWs({
      roomId: 'test-refusal-room',
      displayName: 'TakenAlice',
      awareness,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '33333333-3333-4333-8333-333333333333',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
      onJoinRefused: (msg) => {
        refusedReason = msg.reason
      },
      onJoined: () => {
        joined = true
      },
    })

    await new Promise((r) => setTimeout(r, 20))

    // Refusal received, document socket was NEVER opened
    expect(refusedReason).toBe('NAME_TAKEN')
    expect(joined).toBe(false)
    expect(conn.docWs).toBeNull()

    // Retry with an available name
    conn.join('ValidAlice')
    await new Promise((r) => setTimeout(r, 30))

    // Joined successfully and document socket opened
    expect(joined).toBe(true)
    expect(conn.docWs).not.toBeNull()

    conn.destroy()
  })

  it('triggers onClose and onError on control socket failures without opening document socket', async () => {
    const relay = new TestRelay()
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    let errorTriggered = false
    let closeTriggered = false

    let clientWs: TestWebSocket | null = null
    const conn = connectAwarenessWs({
      roomId: 'test-error-room',
      awareness,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '44444444-4444-4444-8444-444444444444',
      WebSocketClass: function (url: string) {
        clientWs = relay.createClient(url)
        return clientWs
      } as unknown as typeof WebSocket,
      onError: () => {
        errorTriggered = true
      },
      onClose: () => {
        closeTriggered = true
      },
    })

    await new Promise((r) => setTimeout(r, 10))
    const controlTestWs = conn.controlWs as unknown as TestWebSocket
    expect(controlTestWs).not.toBeNull()

    // Trigger error and premature close on control socket
    controlTestWs.triggerError()
    controlTestWs.close(4404, 'Room not found')

    await new Promise((r) => setTimeout(r, 10))

    expect(errorTriggered).toBe(true)
    expect(closeTriggered).toBe(true)
    expect(conn.docWs).toBeNull()

    conn.destroy()
  })

  it('triggers onJoinTimeout when control socket does not respond within joinTimeoutMs', async () => {
    const relay = new TestRelay()
    relay.ignoreJoins = true // server silently drops join requests

    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    let timedOut = false

    const conn = connectAwarenessWs({
      roomId: 'test-timeout-room',
      displayName: 'Alice',
      awareness,
      joinTimeoutMs: 30, // 30ms bounded timeout for test
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '55555555-5555-4555-8555-555555555555',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
      onJoinTimeout: () => {
        timedOut = true
      },
    })

    await new Promise((r) => setTimeout(r, 60))

    expect(timedOut).toBe(true)
    expect(conn.docWs).toBeNull()

    conn.destroy()
  })

  it('cancels joinTimeout upon receiving joined response', async () => {
    const relay = new TestRelay()
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    let timedOut = false
    let joined = false

    const conn = connectAwarenessWs({
      roomId: 'test-cancel-timeout-room',
      displayName: 'Bob',
      awareness,
      joinTimeoutMs: 100,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '66666666-6666-4666-8666-666666666666',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
      onJoined: () => {
        joined = true
      },
      onJoinTimeout: () => {
        timedOut = true
      },
    })

    await new Promise((r) => setTimeout(r, 20))
    expect(joined).toBe(true)

    // Wait beyond timeout threshold
    await new Promise((r) => setTimeout(r, 120))
    expect(timedOut).toBe(false)

    conn.destroy()
  })

  it('Pair mode strictly preserves docId main', async () => {
    const relay = new TestRelay()
    relay.roomModes.set('test-pair-room', 'pair')

    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    let openedDocWsUrl = ''

    const conn = connectAwarenessWs({
      roomId: 'test-pair-room',
      displayName: 'PairUser',
      awareness,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '77777777-7777-4777-8777-777777777777',
      WebSocketClass: function (url: string) {
        const client = relay.createClient(url)
        if (url.includes('/docs/')) {
          openedDocWsUrl = url
        }
        return client
      } as unknown as typeof WebSocket,
    })

    await new Promise((r) => setTimeout(r, 30))
    expect(openedDocWsUrl).toContain('/docs/main')
    expect(conn.docWs).not.toBeNull()

    conn.destroy()
  })

  it('Open mode falls back to an approved module when the requested docId is unknown', async () => {
    const relay = new TestRelay()
    relay.roomModes.set('test-open-room', 'open')
    relay.roomModules.set('test-open-room', new Set(['mod_registered_101']))

    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      json: async () => [
        {
          module_id: 'mod_registered_101',
          is_entry: true,
          pending: false,
        },
      ],
    })))

    const docUnregistered = new Y.Doc()
    const awarenessUnregistered = createAwareness(docUnregistered)
    let openedDocWsUrl = ''
    let docCloseCode = 0

    const connUnregistered = connectAwarenessWs({
      roomId: 'test-open-room',
      displayName: 'User1',
      docId: 'mod_unknown_999',
      awareness: awarenessUnregistered,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '88888888-8888-4888-8888-888888888888',
      WebSocketClass: function (url: string) {
        const client = relay.createClient(url)
        if (url.includes('/docs/')) {
          openedDocWsUrl = url
        }
        return client
      } as unknown as typeof WebSocket,
      onDocClose: (ev) => {
        docCloseCode = ev.code
      },
    })

    await new Promise((r) => setTimeout(r, 30))

    // An unknown requested module must fall back to the approved entry module.
    expect(openedDocWsUrl).toContain('/docs/mod_registered_101')
    expect(connUnregistered.docWs).not.toBeNull()
    expect(docCloseCode).toBe(0)

    expect([...relay.docClients.values()].map((entry) => entry.docId))
      .toContain('mod_registered_101')

    connUnregistered.destroy()
    awarenessUnregistered.destroy()
    docUnregistered.destroy()

    // An explicitly requested registered module should also connect successfully.
    const docRegistered = new Y.Doc()
    const awarenessRegistered = createAwareness(docRegistered)
    let registeredDocWsUrl = ''

    const connRegistered = connectAwarenessWs({
      roomId: 'test-open-room',
      displayName: 'User2',
      docId: 'mod_registered_101',
      awareness: awarenessRegistered,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '99999999-9999-4999-8999-999999999999',
      WebSocketClass: function (url: string) {
        const client = relay.createClient(url)
        if (url.includes('/docs/')) {
          registeredDocWsUrl = url
        }
        return client
      } as unknown as typeof WebSocket,
    })

    await new Promise((r) => setTimeout(r, 30))

    expect(registeredDocWsUrl).toContain('/docs/mod_registered_101')
    expect(connRegistered.docWs).not.toBeNull()

    connRegistered.destroy()
    awarenessRegistered.destroy()
    docRegistered.destroy()

    vi.unstubAllGlobals()
  })

  it('queues join request when socket is CONNECTING and sends exactly one join message upon open', async () => {
    const relay = new TestRelay()
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    let joinSentCount = 0

    const conn = connectAwarenessWs({
      roomId: 'test-single-join-room',
      awareness,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '10101010-1010-4010-8010-101010101010',
      WebSocketClass: function (url: string) {
        const client = relay.createClient(url)
        const originalSend = client.send.bind(client)
        client.send = (data: unknown) => {
          if (typeof data === 'string') {
            try {
              const parsed = JSON.parse(data)
              if (parsed.type === 'join') {
                joinSentCount += 1
              }
            } catch {
              // ignore
            }
          }
          originalSend(data)
        }
        return client
      } as unknown as typeof WebSocket,
    })

    // Immediately call join while socket is still CONNECTING
    expect(conn.controlWs?.readyState).toBe(TestWebSocket.CONNECTING)
    conn.join('Grace')

    // Call join again while still CONNECTING (duplicate prevention)
    conn.join('Grace')

    await new Promise((r) => setTimeout(r, 40))

    expect(joinSentCount).toBe(1)
    expect(conn.controlWs?.readyState).toBe(TestWebSocket.OPEN)

    conn.destroy()
  })

  it('deduplicates multiple join calls while handshake is active', async () => {
    const relay = new TestRelay()
    relay.ignoreJoins = true // Keep handshake in flight
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    let joinSentCount = 0

    const conn = connectAwarenessWs({
      roomId: 'test-dedup-active-room',
      awareness,
      joinTimeoutMs: 200,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '20202020-2020-4020-8020-202020202020',
      WebSocketClass: function (url: string) {
        const client = relay.createClient(url)
        const originalSend = client.send.bind(client)
        client.send = (data: unknown) => {
          if (typeof data === 'string') {
            try {
              const parsed = JSON.parse(data)
              if (parsed.type === 'join') {
                joinSentCount += 1
              }
            } catch {
              // ignore
            }
          }
          originalSend(data)
        }
        return client
      } as unknown as typeof WebSocket,
    })

    // Wait until socket is open
    await new Promise((r) => setTimeout(r, 20))
    expect(conn.controlWs?.readyState).toBe(TestWebSocket.OPEN)

    // Call join multiple times while waiting for server response
    conn.join('Heidi')
    conn.join('Heidi')
    conn.join('Heidi')

    await new Promise((r) => setTimeout(r, 20))

    // Exactly one join was sent over WebSocket
    expect(joinSentCount).toBe(1)

    conn.destroy()
  })

  it('closes and nulls old socket on timeout, and creates a fresh WebSocket connection on retry', async () => {
    const relay = new TestRelay()
    relay.ignoreJoins = true // Drops initial attempt
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    let timeoutFired = false
    let joinedFired = false

    const conn = connectAwarenessWs({
      roomId: 'test-fresh-retry-room',
      awareness,
      joinTimeoutMs: 35,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '30303030-3030-4030-8030-303030303030',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
      onJoinTimeout: () => {
        timeoutFired = true
      },
      onJoined: () => {
        joinedFired = true
      },
    })

    await new Promise((r) => setTimeout(r, 10))
    const firstSocket = conn.controlWs as unknown as TestWebSocket
    expect(firstSocket).not.toBeNull()

    // Trigger initial join
    conn.join('Ivan')

    // Wait for timeout
    await new Promise((r) => setTimeout(r, 60))
    expect(timeoutFired).toBe(true)

    // Previous socket was closed and nulled
    expect(firstSocket.readyState).toBe(TestWebSocket.CLOSED)
    expect(conn.controlWs).toBeNull()

    // Server is now responsive
    relay.ignoreJoins = false

    // Retry join
    conn.join('Ivan')

    const secondSocket = conn.controlWs as unknown as TestWebSocket
    expect(secondSocket).not.toBeNull()
    expect(secondSocket).not.toBe(firstSocket) // Fresh instance!

    await new Promise((r) => setTimeout(r, 40))
    expect(joinedFired).toBe(true)
    expect(secondSocket.readyState).toBe(TestWebSocket.OPEN)

    conn.destroy()
  })

  it('ignores late server responses from expired generation after timeout', async () => {
    const relay = new TestRelay()
    relay.ignoreJoins = true
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    let timeoutFired = false
    let lateJoinedCallbackInvoked = false

    const conn = connectAwarenessWs({
      roomId: 'test-late-response-room',
      awareness,
      joinTimeoutMs: 25,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '40404040-4040-4040-8040-404040404040',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
      onJoinTimeout: () => {
        timeoutFired = true
      },
      onJoined: () => {
        lateJoinedCallbackInvoked = true
      },
    })

    await new Promise((r) => setTimeout(r, 10))
    const staleSocket = conn.controlWs as unknown as TestWebSocket
    conn.join('Judy')

    // Wait for timeout to fire
    await new Promise((r) => setTimeout(r, 50))
    expect(timeoutFired).toBe(true)

    // Simulate late response from server arriving on old socket after timeout
    staleSocket.receiveText(
      JSON.stringify({
        type: 'joined',
        member_id: '40404040-4040-4040-8040-404040404040',
        role: 'Guest',
        mode: 'pair',
        permissions: { can_edit: true, can_run: true },
      }),
    )

    // The late response must NOT invoke onJoined or alter state
    expect(lateJoinedCallbackInvoked).toBe(false)
    expect(conn.docWs).toBeNull()

    conn.destroy()
  })

  it('triggers onJoinTimeout when WebSocket stays in CONNECTING indefinitely', () => {
    vi.useFakeTimers()
    try {
      const relay = new TestRelay()
      relay.autoOpen = false // Socket stays CONNECTING indefinitely

      const doc = new Y.Doc()
      const awareness = createAwareness(doc)
      let timedOut = false

      const conn = connectAwarenessWs({
        roomId: 'test-connecting-timeout',
        awareness,
        joinTimeoutMs: 100,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
        onJoinTimeout: () => {
          timedOut = true
        },
      })

      const initialSocket = conn.controlWs as unknown as TestWebSocket
      expect(initialSocket.readyState).toBe(TestWebSocket.CONNECTING)

      conn.join('Alice')

      // Advance by 50ms (before timeout)
      vi.advanceTimersByTime(50)
      expect(timedOut).toBe(false)
      expect(initialSocket.readyState).toBe(TestWebSocket.CONNECTING)

      // Advance past timeout deadline (100ms total)
      vi.advanceTimersByTime(51)
      expect(timedOut).toBe(true)

      // Old socket was cleanly closed and nulled
      expect(initialSocket.readyState).toBe(TestWebSocket.CLOSED)
      expect(conn.controlWs).toBeNull()

      conn.destroy()
    } finally {
      vi.useRealTimers()
    }
  })

  it('preserves the original deadline when WebSocket opens just before deadline', () => {
    vi.useFakeTimers()
    try {
      const relay = new TestRelay()
      relay.autoOpen = false // Manual open control
      relay.ignoreJoins = true // Server never responds

      const doc = new Y.Doc()
      const awareness = createAwareness(doc)
      let timedOut = false

      const conn = connectAwarenessWs({
        roomId: 'test-deadline-not-restarted',
        awareness,
        joinTimeoutMs: 100,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
        onJoinTimeout: () => {
          timedOut = true
        },
      })

      const socket = conn.controlWs as unknown as TestWebSocket
      conn.join('Bob')

      // Advance to 80ms (20ms before 100ms timeout)
      vi.advanceTimersByTime(80)
      expect(timedOut).toBe(false)
      expect(socket.readyState).toBe(TestWebSocket.CONNECTING)

      // Socket opens at 80ms and sends join request
      socket.triggerOpen()
      expect(socket.readyState).toBe(TestWebSocket.OPEN)

      // Advance by another 19ms (99ms total): timeout still hasn't fired
      vi.advanceTimersByTime(19)
      expect(timedOut).toBe(false)

      // Advance by 2ms (101ms total): timeout MUST fire because deadline was NOT restarted!
      vi.advanceTimersByTime(2)
      expect(timedOut).toBe(true)

      conn.destroy()
    } finally {
      vi.useRealTimers()
    }
  })

  it('creates a fresh WebSocket connection on retry after CONNECTING timeout', () => {
    vi.useFakeTimers()
    try {
      const relay = new TestRelay()
      relay.autoOpen = false // Socket 1 stays CONNECTING

      const doc = new Y.Doc()
      const awareness = createAwareness(doc)
      let timedOut = false
      let joined = false

      const conn = connectAwarenessWs({
        roomId: 'test-retry-after-connecting-timeout',
        awareness,
        joinTimeoutMs: 100,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '33333333-3333-4333-8333-333333333333',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
        onJoinTimeout: () => {
          timedOut = true
        },
        onJoined: () => {
          joined = true
        },
      })

      const firstSocket = conn.controlWs as unknown as TestWebSocket
      conn.join('Charlie')

      // Timeout fires while in CONNECTING
      vi.advanceTimersByTime(101)
      expect(timedOut).toBe(true)
      expect(firstSocket.readyState).toBe(TestWebSocket.CLOSED)
      expect(conn.controlWs).toBeNull()

      // Relay will now allow socket to open and respond
      relay.autoOpen = true

      // User retries
      conn.join('Charlie')

      const secondSocket = conn.controlWs as unknown as TestWebSocket
      expect(secondSocket).not.toBeNull()
      expect(secondSocket).not.toBe(firstSocket) // Fresh instance!

      // Second socket connects
      secondSocket.triggerOpen()
      expect(joined).toBe(true)
      expect(secondSocket.readyState).toBe(TestWebSocket.OPEN)

      conn.destroy()
    } finally {
      vi.useRealTimers()
    }
  })

  it('encodes sync step 1 message with MESSAGE_SYNC prefix (0x00)', () => {
    const doc = new Y.Doc()
    doc.getText('monaco').insert(0, 'init')
    const frame = encodeSyncStep1Message(doc)
    expect(frame[0]).toBe(MESSAGE_SYNC)
    expect(frame.length).toBeGreaterThan(1)
    doc.destroy()
  })

  it('encodes document update message with MESSAGE_SYNC prefix (0x00)', () => {
    const doc = new Y.Doc()
    let updateBytes: Uint8Array | null = null
    doc.on('update', (u) => {
      updateBytes = u
    })
    doc.getText('monaco').insert(0, 'test')
    expect(updateBytes).not.toBeNull()
    const frame = encodeDocUpdateMessage(updateBytes!)
    expect(frame[0]).toBe(MESSAGE_SYNC)
    expect(frame.length).toBeGreaterThan(1)
    doc.destroy()
  })

  it('handles incoming sync frame, applying updates to local Y.Doc', () => {
    const docA = new Y.Doc()
    const docB = new Y.Doc()
    try {
      let updateFromA: Uint8Array | null = null
      docA.on('update', (u) => {
        updateFromA = u
      })
      docA.getText('monaco').insert(0, 'content from A')

      const frame = encodeDocUpdateMessage(updateFromA!)
      const replies = handleIncomingSyncFrame(docB, frame, 'test-origin')
      expect(replies).toEqual([]) // regular updates do not require a reply
      expect(docB.getText('monaco').toString()).toBe('content from A')
    } finally {
      docA.destroy()
      docB.destroy()
    }
  })

  it('synchronizes document edits between two clients through the WebSocket relay (US-02 / FR2)', async () => {
    const relay = new TestRelay()
    const docA = new Y.Doc()
    const docB = new Y.Doc()
    const awarenessA = createAwareness(docA)
    const awarenessB = createAwareness(docB)

    try {
      const connA = connectAwarenessWs({
        roomId: 'test-sync-relay-room',
        displayName: 'Alice',
        doc: docA,
        awareness: awarenessA,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      const connB = connectAwarenessWs({
        roomId: 'test-sync-relay-room',
        displayName: 'Bob',
        doc: docB,
        awareness: awarenessB,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      await new Promise((r) => setTimeout(r, 30))

      // Client A types into editor text
      docA.getText('monaco').insert(0, 'Hello from Alice')

      await new Promise((r) => setTimeout(r, 30))

      // Client B receives Alice's edits over the relay
      expect(docB.getText('monaco').toString()).toBe('Hello from Alice')

      // Client B replies
      docB.getText('monaco').insert(16, ' and Bob!')

      await new Promise((r) => setTimeout(r, 30))

      // Both documents match exactly
      expect(docA.getText('monaco').toString()).toBe('Hello from Alice and Bob!')
      expect(docB.getText('monaco').toString()).toBe('Hello from Alice and Bob!')

      connA.destroy()
      connB.destroy()
    } finally {
      docA.destroy()
      docB.destroy()
    }
  })

  it('merges simultaneous edits from two clients without losing either contribution (US-02 / FR2)', async () => {
    const relay = new TestRelay()
    const docA = new Y.Doc()
    const docB = new Y.Doc()
    const awarenessA = createAwareness(docA)
    const awarenessB = createAwareness(docB)

    try {
      const connA = connectAwarenessWs({
        roomId: 'test-concurrent-merge-room',
        displayName: 'Alice',
        doc: docA,
        awareness: awarenessA,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      const connB = connectAwarenessWs({
        roomId: 'test-concurrent-merge-room',
        displayName: 'Bob',
        doc: docB,
        awareness: awarenessB,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      await new Promise((r) => setTimeout(r, 30))

      // Set initial base text
      docA.getText('monaco').insert(0, 'SHARED_BASE')
      await new Promise((r) => setTimeout(r, 30))
      expect(docB.getText('monaco').toString()).toBe('SHARED_BASE')

      // Both users type simultaneously at the same offset
      docA.getText('monaco').insert(11, 'AAAA')
      docB.getText('monaco').insert(11, 'BBBB')

      await new Promise((r) => setTimeout(r, 50))

      const textA = docA.getText('monaco').toString()
      const textB = docB.getText('monaco').toString()

      // Acceptance Criteria 2: Connected clients reach the same document state
      expect(textA).toBe(textB)

      // Acceptance Criteria 1: Neither user's edit was overwritten or lost
      expect(textA).toContain('SHARED_BASE')
      expect(textA).toContain('AAAA')
      expect(textA).toContain('BBBB')

      connA.destroy()
      connB.destroy()
    } finally {
      docA.destroy()
      docB.destroy()
    }
  })

  it('merges simultaneous edits from three clients converging to identical text (US-02 manual test scenario)', async () => {
    const relay = new TestRelay()
    const docA = new Y.Doc()
    const docB = new Y.Doc()
    const docC = new Y.Doc()
    const awarenessA = createAwareness(docA)
    const awarenessB = createAwareness(docB)
    const awarenessC = createAwareness(docC)

    try {
      const connA = connectAwarenessWs({
        roomId: 'test-three-clients-room',
        displayName: 'Alice',
        doc: docA,
        awareness: awarenessA,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      const connB = connectAwarenessWs({
        roomId: 'test-three-clients-room',
        displayName: 'Bob',
        doc: docB,
        awareness: awarenessB,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      const connC = connectAwarenessWs({
        roomId: 'test-three-clients-room',
        displayName: 'Charlie',
        doc: docC,
        awareness: awarenessC,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '33333333-3333-4333-8333-333333333333',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      await new Promise((r) => setTimeout(r, 40))

      // 3 windows type simultaneously
      docA.getText('monaco').insert(0, 'LINE_A\n')
      docB.getText('monaco').insert(0, 'LINE_B\n')
      docC.getText('monaco').insert(0, 'LINE_C\n')

      await new Promise((r) => setTimeout(r, 60))

      const textA = docA.getText('monaco').toString()
      const textB = docB.getText('monaco').toString()
      const textC = docC.getText('monaco').toString()

      // All three converge to the same text
      expect(textA).toBe(textB)
      expect(textB).toBe(textC)

      // All contributions preserved
      expect(textA).toContain('LINE_A')
      expect(textA).toContain('LINE_B')
      expect(textA).toContain('LINE_C')

      connA.destroy()
      connB.destroy()
      connC.destroy()
    } finally {
      docA.destroy()
      docB.destroy()
      docC.destroy()
    }
  })

  it('preserves document isolation between different module IDs in Open mode (FR2 / T-17 V2.5)', async () => {
    const relay = new TestRelay()
    relay.roomModes.set('test-open-room', 'open')
    relay.roomModules.set('test-open-room', new Set(['module_alpha', 'module_beta']))

    const docAlpha = new Y.Doc()
    const docBeta = new Y.Doc()
    const awarenessAlpha = createAwareness(docAlpha)
    const awarenessBeta = createAwareness(docBeta)

    try {
      const connAlpha = connectAwarenessWs({
        roomId: 'test-open-room',
        docId: 'module_alpha',
        displayName: 'Alice',
        doc: docAlpha,
        awareness: awarenessAlpha,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      const connBeta = connectAwarenessWs({
        roomId: 'test-open-room',
        docId: 'module_beta',
        displayName: 'Bob',
        doc: docBeta,
        awareness: awarenessBeta,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      await new Promise((r) => setTimeout(r, 30))

      // User edits module_alpha
      docAlpha.getText('monaco').insert(0, 'def alpha(): pass')

      await new Promise((r) => setTimeout(r, 30))

      // module_beta must NOT receive edits from module_alpha
      expect(docAlpha.getText('monaco').toString()).toBe('def alpha(): pass')
      expect(docBeta.getText('monaco').toString()).toBe('')

      connAlpha.destroy()
      connBeta.destroy()
    } finally {
      docAlpha.destroy()
      docBeta.destroy()
    }
  })

  it('preserves room isolation so edits in one room never affect another room (US-02 manual test scenario 6)', async () => {
    const relay = new TestRelay()
    const docRoom1 = new Y.Doc()
    const docRoom2 = new Y.Doc()
    const awarenessRoom1 = createAwareness(docRoom1)
    const awarenessRoom2 = createAwareness(docRoom2)

    try {
      const conn1 = connectAwarenessWs({
        roomId: 'room-isolation-1',
        displayName: 'Alice',
        doc: docRoom1,
        awareness: awarenessRoom1,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      const conn2 = connectAwarenessWs({
        roomId: 'room-isolation-2',
        displayName: 'Bob',
        doc: docRoom2,
        awareness: awarenessRoom2,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      await new Promise((r) => setTimeout(r, 30))

      docRoom1.getText('monaco').insert(0, 'SECRET_IN_ROOM_1')

      await new Promise((r) => setTimeout(r, 30))

      expect(docRoom1.getText('monaco').toString()).toBe('SECRET_IN_ROOM_1')
      expect(docRoom2.getText('monaco').toString()).toBe('')

      conn1.destroy()
      conn2.destroy()
    } finally {
      docRoom1.destroy()
      docRoom2.destroy()
    }
  })

  it('allows a newly joining client to catch up on the existing document state via syncStep1', async () => {
    const relay = new TestRelay()
    const docA = new Y.Doc()
    const docB = new Y.Doc()
    const awarenessA = createAwareness(docA)
    const awarenessB = createAwareness(docB)

    try {
      const connA = connectAwarenessWs({
        roomId: 'test-late-joiner-room',
        displayName: 'Alice',
        doc: docA,
        awareness: awarenessA,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      await new Promise((r) => setTimeout(r, 30))

      // Alice creates initial content before Bob joins
      docA.getText('monaco').insert(0, 'Pre-existing document content')

      // Bob joins late with an empty document
      const connB = connectAwarenessWs({
        roomId: 'test-late-joiner-room',
        displayName: 'Bob',
        doc: docB,
        awareness: awarenessB,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      await new Promise((r) => setTimeout(r, 40))

      // Bob's doc caught up to Alice's existing content
      expect(docB.getText('monaco').toString()).toBe('Pre-existing document content')

      connA.destroy()
      connB.destroy()
    } finally {
      docA.destroy()
      docB.destroy()
    }
  })

  it('encodes sync step 2 message with MESSAGE_SYNC prefix (0x00)', () => {
    const doc = new Y.Doc()
    doc.getText('monaco').insert(0, 'step2-content')
    const frame = encodeSyncStep2Message(doc)
    expect(frame[0]).toBe(MESSAGE_SYNC)
    expect(frame.length).toBeGreaterThan(1)
    doc.destroy()
  })

  it('decodes multiple concatenated sync messages in a frame via multi-message loop', () => {
    const docA = new Y.Doc()
    const docB = new Y.Doc()
    try {
      docA.getText('monaco').insert(0, 'from_A')
      docB.getText('monaco').insert(0, 'from_B')

      // Concatenate syncStep2 and syncStep1 in a single MESSAGE_SYNC frame
      const encoder = encoding.createEncoder()
      encoding.writeVarUint(encoder, MESSAGE_SYNC)
      syncProtocol.writeSyncStep2(encoder, docA)
      syncProtocol.writeSyncStep1(encoder, docA)
      const concatenatedFrame = encoding.toUint8Array(encoder)

      const replies = handleIncomingSyncFrame(docB, concatenatedFrame, 'test')
      // Must decode both: syncStep2 is applied to docB, and syncStep1 generates a syncStep2 reply for A
      expect(docB.getText('monaco').toString()).toContain('from_A')
      expect(replies.length).toBe(1)
      expect(replies[0][0]).toBe(MESSAGE_SYNC)
    } finally {
      docA.destroy()
      docB.destroy()
    }
  })

  it('emits reverse syncStep1 as its own distinct MESSAGE_SYNC frame when requested', () => {
    const doc = new Y.Doc()
    try {
      const step1Encoder = encoding.createEncoder()
      encoding.writeVarUint(step1Encoder, MESSAGE_SYNC)
      syncProtocol.writeSyncStep1(step1Encoder, doc)
      const incomingStep1 = encoding.toUint8Array(step1Encoder)

      const replies = handleIncomingSyncFrame(doc, incomingStep1, 'test', {
        replyWithReverseSyncStep1: true,
      })

      // Frame 1 is syncStep2; Frame 2 is reverse syncStep1 (each has its own MESSAGE_SYNC prefix)
      expect(replies.length).toBe(2)
      expect(replies[0][0]).toBe(MESSAGE_SYNC)
      expect(replies[1][0]).toBe(MESSAGE_SYNC)
    } finally {
      doc.destroy()
    }
  })

  it('converges pre-existing divergent edits between two clients without requiring subsequent edits (US-02 review regression)', async () => {
    const relay = new TestRelay()
    const docA = new Y.Doc()
    const docB = new Y.Doc()
    const awarenessA = createAwareness(docA)
    const awarenessB = createAwareness(docB)

    try {
      // Both clients have pre-existing, divergent local edits BEFORE establishing connection
      docA.getText('monaco').insert(0, 'Alice Pre-Existing Text; ')
      docB.getText('monaco').insert(0, 'Bob Pre-Existing Text; ')

      const connA = connectAwarenessWs({
        roomId: 'test-divergent-pre-existing-room',
        displayName: 'Alice',
        doc: docA,
        awareness: awarenessA,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      const connB = connectAwarenessWs({
        roomId: 'test-divergent-pre-existing-room',
        displayName: 'Bob',
        doc: docB,
        awareness: awarenessB,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      // Allow the bidirectional initial handshake to complete
      await new Promise((r) => setTimeout(r, 60))

      const textA = docA.getText('monaco').toString()
      const textB = docB.getText('monaco').toString()

      // Both clients must converge to the exact same text with NO subsequent edits triggered
      expect(textA).toBe(textB)
      expect(textA).toContain('Alice Pre-Existing Text; ')
      expect(textA).toContain('Bob Pre-Existing Text; ')

      connA.destroy()
      connB.destroy()
    } finally {
      docA.destroy()
      docB.destroy()
    }
  })

  it('converges a late joiner with pre-existing local changes and an active host (US-02 review regression)', async () => {
    const relay = new TestRelay()
    const docHost = new Y.Doc()
    const docLate = new Y.Doc()
    const awarenessHost = createAwareness(docHost)
    const awarenessLate = createAwareness(docLate)

    try {
      // Host has pre-existing content and connects first
      docHost.getText('monaco').insert(0, 'Host Active Code; ')

      const connHost = connectAwarenessWs({
        roomId: 'test-late-divergent-room',
        displayName: 'HostAlice',
        doc: docHost,
        awareness: awarenessHost,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      await new Promise((r) => setTimeout(r, 30))

      // Late joiner also has independent offline edits
      docLate.getText('monaco').insert(0, 'Late Joiner Offline Code; ')

      // Late joiner connects
      const connLate = connectAwarenessWs({
        roomId: 'test-late-divergent-room',
        displayName: 'LateBob',
        doc: docLate,
        awareness: awarenessLate,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      await new Promise((r) => setTimeout(r, 60))

      const textHost = docHost.getText('monaco').toString()
      const textLate = docLate.getText('monaco').toString()

      // Both converge without further user input
      expect(textHost).toBe(textLate)
      expect(textHost).toContain('Host Active Code; ')
      expect(textHost).toContain('Late Joiner Offline Code; ')

      connHost.destroy()
      connLate.destroy()
    } finally {
      docHost.destroy()
      docLate.destroy()
    }
  })

  it('converges three clients with divergent pre-existing edits under controlled message ordering (US-02 review regression)', async () => {
    const relay = new TestRelay()
    const docA = new Y.Doc()
    const docB = new Y.Doc()
    const docC = new Y.Doc()
    const awarenessA = createAwareness(docA)
    const awarenessB = createAwareness(docB)
    const awarenessC = createAwareness(docC)

    try {
      docA.getText('monaco').insert(0, 'Part A; ')
      docB.getText('monaco').insert(0, 'Part B; ')
      docC.getText('monaco').insert(0, 'Part C; ')

      // Connect sequentially in controlled stages
      const connA = connectAwarenessWs({
        roomId: 'test-three-divergent-room',
        displayName: 'Alice',
        doc: docA,
        awareness: awarenessA,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })
      await new Promise((r) => setTimeout(r, 25))

      const connB = connectAwarenessWs({
        roomId: 'test-three-divergent-room',
        displayName: 'Bob',
        doc: docB,
        awareness: awarenessB,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })
      await new Promise((r) => setTimeout(r, 25))

      const connC = connectAwarenessWs({
        roomId: 'test-three-divergent-room',
        displayName: 'Charlie',
        doc: docC,
        awareness: awarenessC,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '33333333-3333-4333-8333-333333333333',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })
      await new Promise((r) => setTimeout(r, 60))

      const textA = docA.getText('monaco').toString()
      const textB = docB.getText('monaco').toString()
      const textC = docC.getText('monaco').toString()

      // All three must converge to the exact same text containing all parts
      expect(textA).toBe(textB)
      expect(textB).toBe(textC)
      expect(textA).toContain('Part A; ')
      expect(textA).toContain('Part B; ')
      expect(textA).toContain('Part C; ')

      connA.destroy()
      connB.destroy()
      connC.destroy()
    } finally {
      docA.destroy()
      docB.destroy()
      docC.destroy()
    }
  })

  it('enforces Pair-mode edit permissions by dropping doc changes from unauthorized participants and sending edit-rejected (US-02 / CR13)', async () => {
    const relay = new TestRelay()
    const docHost = new Y.Doc()
    const docGuest = new Y.Doc()
    const awarenessHost = createAwareness(docHost)
    const awarenessGuest = createAwareness(docGuest)

    let editRejectedReason: string | null = null

    try {
      const connHost = connectAwarenessWs({
        roomId: 'test-perm-room',
        displayName: 'HostAlice',
        ownerToken: 'host-secret-token',
        doc: docHost,
        awareness: awarenessHost,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '11111111-1111-4111-8111-111111111111',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
      })

      const connGuest = connectAwarenessWs({
        roomId: 'test-perm-room',
        displayName: 'GuestBob',
        doc: docGuest,
        awareness: awarenessGuest,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        sessionId: '22222222-2222-4222-8222-222222222222',
        WebSocketClass: function (url: string) {
          return relay.createClient(url)
        } as unknown as typeof WebSocket,
        onEditRejected: (msg) => {
          editRejectedReason = (msg.reason as string) ?? null
        },
      })

      await new Promise((r) => setTimeout(r, 40))

      // Simulate Host setting Pair mode with guest having NO edit permissions
      const guestSession = relay.rooms.get('test-perm-room')?.get('22222222-2222-4222-8222-222222222222')
      if (guestSession) {
        guestSession.canEdit = false
      }

      // Guest attempts to send an unauthorized document edit
      docGuest.getText('monaco').insert(0, 'UNAUTHORIZED_CHANGE')

      await new Promise((r) => setTimeout(r, 40))

      // Host must NOT have received the unauthorized change
      expect(docHost.getText('monaco').toString()).toBe('')

      // Guest must have received edit-rejected notification
      expect(editRejectedReason).toBe('no_edit_access')

      connHost.destroy()
      connGuest.destroy()
    } finally {
      docHost.destroy()
      docGuest.destroy()
    }
  })
  it('delivers a run-result control frame to every connected participant (US-08 / T-17 section 3.6)', async () => {
    const relay = new TestRelay()
    const docA = new Y.Doc()
    const docB = new Y.Doc()
    const received: Record<string, string[]> = { A: [], B: [] }
    const connections: Array<{ destroy: () => void }> = []

    try {
      for (const [label, doc, name, sessionId] of [
        ['A', docA, 'Alice', '11111111-1111-4111-8111-111111111111'],
        ['B', docB, 'Bob', '22222222-2222-4222-8222-222222222222'],
      ] as const) {
        connections.push(
          connectAwarenessWs({
            roomId: 'test-run-result-room',
            displayName: name,
            doc,
            awareness: createAwareness(doc),
            syncWsUrl: 'ws://localhost:8081/ws/rooms',
            sessionId,
            WebSocketClass: function (url: string) {
              return relay.createClient(url)
            } as unknown as typeof WebSocket,
            onRunResult: (msg) => {
              received[label].push(msg.request_id)
            },
          }),
        )
      }

      await new Promise((r) => setTimeout(r, 40))

      const frame = JSON.stringify({
        type: 'run-result',
        request_id: '0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10',
        room_id: 'test-run-result-room',
        stdout: 'hello\n',
      })
      for (const [client, meta] of relay.controlClients.entries()) {
        if (meta.roomId === 'test-run-result-room') client.receiveText(frame)
      }

      await new Promise((r) => setTimeout(r, 10))

      expect(received.A).toEqual(['0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10'])
      expect(received.B).toEqual(['0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10'])
    } finally {
      for (const c of connections) c.destroy()
      docA.destroy()
      docB.destroy()
    }
  })
})


describe('US-04 offline Yjs recovery', () => {
  it('preserves offline local edits and exchanges them after reconnect', async () => {
    vi.useFakeTimers()
    try {
      const relay = new TestRelay()
      const roomId = 'us04-offline-edits'
      const makeSocket = function (url: string) { return relay.createClient(url) } as unknown as typeof WebSocket
      const docA = new Y.Doc()
      const docB = new Y.Doc()
      const awarenessA = createAwareness(docA)
      const awarenessB = createAwareness(docB)
      const a = connectAwarenessWs({
        roomId, awareness: awarenessA, doc: docA,
        sessionId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
        syncWsUrl: 'ws://localhost:8081/ws/rooms', WebSocketClass: makeSocket
      })
      const b = connectAwarenessWs({
        roomId, awareness: awarenessB, doc: docB,
        sessionId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
        syncWsUrl: 'ws://localhost:8081/ws/rooms', WebSocketClass: makeSocket
      })
      try {
        a.join('Alice')
        b.join('Bob')

        await vi.waitFor(() => {
          expect(a.docWs?.readyState).toBe(WebSocket.OPEN)
          expect(b.docWs?.readyState).toBe(WebSocket.OPEN)
        })

        docA.getText('content').insert(0, 'initial ')

        await vi.waitFor(() => {
          expect(docB.getText('content').toString()).toBe('initial ')
        })
          ; (a.controlWs as unknown as TestWebSocket).close(1006, 'Network lost')
        docA.getText('content').insert(8, 'offline ')
        docB.getText('content').insert(8, 'remote')
        await vi.advanceTimersByTimeAsync(1000)
        expect(docA.getText('content').toString()).toBe(docB.getText('content').toString())
        expect(docA.getText('content').toString()).toContain('offline ')
        expect(docA.getText('content').toString()).toContain('remote')
      } finally {
        a.destroy()
        b.destroy()
        awarenessA.destroy()
        awarenessB.destroy()
        docA.destroy()
        docB.destroy()
      }
    } finally { vi.useRealTimers() }
  })
})

describe('US-04 reconnect lifecycle', () => {
  it('rejoins with the same session ID after control socket drops and opens document only after joined', async () => {
    vi.useFakeTimers()
    try {
      const relay = new TestRelay()
      const doc = new Y.Doc()
      const awareness = createAwareness(doc)
      const sessionId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
      const attempts: number[] = []
      let recovered = 0
      const conn = connectAwarenessWs({
        roomId: 'us04-reconnect', awareness, doc, sessionId,
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        WebSocketClass: function (url: string) { return relay.createClient(url) } as unknown as typeof WebSocket,
        onReconnecting: (n) => attempts.push(n),
        onReconnected: () => { recovered++ },
      })
      conn.join('Alice')
      await vi.runAllTicks()
      const first = conn.controlWs as unknown as TestWebSocket
      expect(conn.docWs).not.toBeNull()
      first.close(1006, 'Network lost')
      expect(attempts).toEqual([1])
      expect(conn.docWs).toBeNull()
      await vi.advanceTimersByTimeAsync(1000)
      expect(conn.controlWs).not.toBe(first)
      expect(conn.docWs).not.toBeNull()
      expect(recovered).toBe(1)
      expect(relay.rooms.get('us04-reconnect')?.get(sessionId)?.displayName).toBe('Alice')
      conn.destroy()
    } finally { vi.useRealTimers() }
  })

  it('stops automatic retries on a terminal close code', async () => {
    vi.useFakeTimers()
    try {
      const relay = new TestRelay()
      const doc = new Y.Doc()
      const awareness = createAwareness(doc)
      let attempts = 0
      const conn = connectAwarenessWs({
        roomId: 'us04-terminal', awareness, doc,
        sessionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        WebSocketClass: function (url: string) { return relay.createClient(url) } as unknown as typeof WebSocket,
        onReconnecting: () => { attempts++ },
      })
      conn.join('Alice')
      await vi.runAllTicks()
      const first = conn.controlWs as unknown as TestWebSocket
      first.close(4403, 'Forbidden')
      await vi.advanceTimersByTimeAsync(120000)
      expect(attempts).toBe(0)
      expect(conn.controlWs).toBeNull()
      conn.destroy()
    } finally { vi.useRealTimers() }
  })

  it('retries a timed-out reconnect handshake instead of becoming stuck', async () => {
    vi.useFakeTimers()
    try {
      const relay = new TestRelay()
      const doc = new Y.Doc()
      const awareness = createAwareness(doc)
      const attempts: number[] = []
      const conn = connectAwarenessWs({
        roomId: 'us04-timeout', awareness, doc,
        joinTimeoutMs: 100,
        sessionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        WebSocketClass: function (url: string) { return relay.createClient(url) } as unknown as typeof WebSocket,
        onReconnecting: (n) => attempts.push(n),
      })
      conn.join('Alice')
      await vi.runAllTicks()
      relay.ignoreJoins = true
        ; (conn.controlWs as unknown as TestWebSocket).close(1006, 'Network lost')
      await vi.advanceTimersByTimeAsync(1000)
      expect(attempts).toEqual([1])
      await vi.advanceTimersByTimeAsync(100)
      expect(attempts).toEqual([1, 2])
      relay.ignoreJoins = false
      await vi.advanceTimersByTimeAsync(2000)
      expect(conn.docWs).not.toBeNull()
      conn.destroy()
    } finally { vi.useRealTimers() }
  })
})

describe('US-04 Open-mode registered module recovery', () => {
  it('reopens inactive module sockets after control reconnect without losing their Yjs state', async () => {
    vi.useFakeTimers()
    const relay = new TestRelay()
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      json: async () => [
        { module_id: 'module_alpha', is_entry: true, pending: false },
        { module_id: 'module_beta', pending: false },
      ],
    })))
    const roomId = 'us04-multi-module'
    relay.roomModes.set(roomId, 'open')
    relay.roomModules.set(roomId, new Set(['module_alpha', 'module_beta']))
    const makeSocket = function (url: string) { return relay.createClient(url) } as unknown as typeof WebSocket
    const active = new Y.Doc()
    const inactive = new Y.Doc()
    const awareness = createAwareness(active)
    const modules = new Map([['module_beta', inactive]])
    const conn = connectAwarenessWs({
      roomId, docId: 'module_alpha', doc: active, moduleDocs: modules, awareness,
      sessionId: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
      syncWsUrl: 'ws://localhost:8081/ws/rooms', WebSocketClass: makeSocket,
    })
    try {
      conn.join('Alice')
      await vi.waitFor(() => {
        expect(conn.docWs?.readyState).toBe(WebSocket.OPEN)
        expect(conn.moduleSockets.get('module_beta')?.readyState).toBe(WebSocket.OPEN)
      })
      ;(conn.controlWs as unknown as TestWebSocket).close(1006, 'Network lost')
      inactive.getText('content').insert(0, 'offline module edit')
      await vi.advanceTimersByTimeAsync(1000)
      expect(conn.moduleSockets.get('module_beta')?.readyState).toBe(WebSocket.OPEN)
      expect(inactive.getText('content').toString()).toBe('offline module edit')
      expect(relay.docClients.size).toBe(2)
    } finally {
      conn.destroy()
      awareness.destroy()
      active.destroy()
      inactive.destroy()
      vi.unstubAllGlobals()
      vi.useRealTimers()
    }
  })
})

describe('US-04 duplicate joined regression', () => {
  it('opens exactly one document socket when the relay repeats joined', async () => {
    const relay = new TestRelay()
    const roomId = 'us04-duplicate-joined'
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    const conn = connectAwarenessWs({
      roomId, doc, awareness,
      sessionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
    })
    try {
      conn.join('Alice')
      await vi.waitFor(() => expect(conn.docWs?.readyState).toBe(WebSocket.OPEN))
      const initialDocSocket = conn.docWs
      const control = conn.controlWs as unknown as TestWebSocket
      control.receiveText(JSON.stringify({
        type: 'joined', member_id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        role: 'Guest', mode: 'pair', permissions: { can_edit: true, can_run: true },
      }))
      await new Promise((resolve) => setTimeout(resolve, 10))
      expect(conn.docWs).toBe(initialDocSocket)
      expect(relay.docClients.size).toBe(1)
    } finally {
      conn.destroy()
      awareness.destroy()
      doc.destroy()
    }
  })
})

describe('US-04 approved module lifecycle', () => {
  it('discovers all approved modules and closes revoked sockets on reconnect', async () => {
    const relay = new TestRelay()
    const roomId = 'us04-approved-lifecycle'
    relay.roomModes.set(roomId, 'open')
    relay.roomModules.set(roomId, new Set(['module_alpha', 'module_beta', 'module_gamma']))
    const approved = new Set(['module_alpha', 'module_beta', 'module_gamma'])
    const fetchMock = vi.fn(async () => ({
      ok: true,
      json: async () => [...approved].map((module_id) => ({
        module_id, is_entry: module_id === 'module_alpha', pending: false,
      })),
    }))
    vi.stubGlobal('fetch', fetchMock)
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    const conn = connectAwarenessWs({
      roomId, doc, awareness,
      sessionId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
    })
    try {
      conn.join('Alice')
      await vi.waitFor(() => {
        expect(conn.docWs?.readyState).toBe(WebSocket.OPEN)
        expect(conn.moduleSockets.get('module_beta')?.readyState).toBe(WebSocket.OPEN)
        expect(conn.moduleSockets.get('module_gamma')?.readyState).toBe(WebSocket.OPEN)
      })
      const revokedSocket = conn.moduleSockets.get('module_gamma')!
      approved.delete('module_gamma')
      relay.roomModules.get(roomId)!.delete('module_gamma')
      ;(conn.controlWs as unknown as TestWebSocket).close(1006, 'Network lost')
      await vi.waitFor(() => {
        expect(fetchMock).toHaveBeenCalledTimes(2)
        expect(conn.docWs?.readyState).toBe(WebSocket.OPEN)
        expect(conn.moduleSockets.get('module_beta')?.readyState).toBe(WebSocket.OPEN)
        expect(conn.moduleSockets.has('module_gamma')).toBe(false)
        expect(revokedSocket.readyState).toBe(WebSocket.CLOSED)
      }, { timeout: 4000 })
      expect(relay.docClients.size).toBe(2)
    } finally {
      conn.destroy()
      awareness.destroy()
      doc.destroy()
      vi.unstubAllGlobals()
    }
  })

  it('rejects an unapproved resolveDocId and opens the approved entry module', async () => {
    const relay = new TestRelay()
    const roomId = 'us04-resolver-validation'
    relay.roomModes.set(roomId, 'open')
    relay.roomModules.set(roomId, new Set(['module_alpha']))
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      json: async () => [{ module_id: 'module_alpha', is_entry: true, pending: false }],
    })))
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    const conn = connectAwarenessWs({
      roomId, doc, awareness,
      sessionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
      resolveDocId: async () => 'module_not_approved',
    })
    try {
      conn.join('Alice')
      await vi.waitFor(() => expect(conn.docWs?.readyState).toBe(WebSocket.OPEN))
      expect([...relay.docClients.values()].map((entry) => entry.docId)).toEqual(['module_alpha'])
    } finally {
      conn.destroy()
      awareness.destroy()
      doc.destroy()
      vi.unstubAllGlobals()
    }
  })
})

describe('US-04 Open-mode missing module regression', () => {
  it('does not open main document when no registered module is available', async () => {
    const relay = new TestRelay()
    const roomId = 'us04-open-no-modules'

    relay.roomModes.set(roomId, 'open')
    relay.roomModules.set(roomId, new Set())

    const doc = new Y.Doc()
    const awareness = createAwareness(doc)

    const conn = connectAwarenessWs({
      roomId,
      doc,
      awareness,
      sessionId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
      resolveDocId: async () => null,
    })

    try {
      conn.join('Alice')

      await vi.waitFor(() => {
        expect(conn.controlWs?.readyState).toBe(WebSocket.OPEN)
      })

      await new Promise((resolve) => setTimeout(resolve, 20))

      expect(conn.docWs).toBeNull()
      expect(relay.docClients.size).toBe(0)
    } finally {
      conn.destroy()
      awareness.destroy()
      doc.destroy()
    }
  })
})

describe('US-28 / US-54 control-socket signals (V2.18, V2.19)', () => {
  it('names the terminal close codes of the contract', () => {
    expect(CLOSE_REMOVED).toBe(4403)
    expect(CLOSE_UNKNOWN_ROOM).toBe(4404)
    expect(CLOSE_LINK_REGENERATED).toBe(4411)
  })

  it.each([4403, 4404, 4410, 4411])('does not auto-reconnect after close %i', async (code) => {
    vi.useFakeTimers()
    try {
      const relay = new TestRelay()
      const doc = new Y.Doc()
      const awareness = createAwareness(doc)
      let attempts = 0
      const closes: number[] = []
      const conn = connectAwarenessWs({
        roomId: `us28-terminal-${code}`, awareness, doc,
        sessionId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        WebSocketClass: function (url: string) { return relay.createClient(url) } as unknown as typeof WebSocket,
        onReconnecting: () => { attempts++ },
        onClose: (event) => { closes.push(event.code) },
      })
      conn.join('Alice')
      await vi.runAllTicks()
      ;(conn.controlWs as unknown as TestWebSocket).close(code, 'terminal')
      await vi.advanceTimersByTimeAsync(120000)

      expect(closes).toEqual([code])
      expect(attempts).toBe(0)
      expect(conn.controlWs).toBeNull()
      expect(conn.docWs).toBeNull()
      conn.destroy()
    } finally { vi.useRealTimers() }
  })

  it('still reconnects after an ordinary network loss', async () => {
    vi.useFakeTimers()
    try {
      const relay = new TestRelay()
      const doc = new Y.Doc()
      const awareness = createAwareness(doc)
      const attempts: number[] = []
      const conn = connectAwarenessWs({
        roomId: 'us28-network-loss', awareness, doc,
        sessionId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        WebSocketClass: function (url: string) { return relay.createClient(url) } as unknown as typeof WebSocket,
        onReconnecting: (n) => attempts.push(n),
      })
      conn.join('Alice')
      await vi.runAllTicks()
      ;(conn.controlWs as unknown as TestWebSocket).close(1006, 'Network lost')
      expect(attempts).toEqual([1])
      conn.destroy()
    } finally { vi.useRealTimers() }
  })

  it('does not rejoin after join-refused USER_BLOCKED', async () => {
    vi.useFakeTimers()
    try {
      const relay = new TestRelay()
      const doc = new Y.Doc()
      const awareness = createAwareness(doc)
      let attempts = 0
      const refused: string[] = []
      const conn = connectAwarenessWs({
        roomId: 'us28-blocked', awareness, doc,
        sessionId: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
        syncWsUrl: 'ws://localhost:8081/ws/rooms',
        WebSocketClass: function (url: string) { return relay.createClient(url) } as unknown as typeof WebSocket,
        onReconnecting: () => { attempts++ },
        onJoinRefused: (msg) => refused.push(msg.reason),
      })
      conn.join('Alice')
      await vi.runAllTicks()
      const control = conn.controlWs as unknown as TestWebSocket
      control.onmessage?.(new MessageEvent('message', {
        data: JSON.stringify({ type: 'join-refused', reason: 'USER_BLOCKED', message: 'blocked' }),
      }))
      control.close(1000, 'refused')
      await vi.advanceTimersByTimeAsync(120000)

      expect(refused).toEqual(['USER_BLOCKED'])
      expect(attempts).toBe(0)
      conn.destroy()
    } finally { vi.useRealTimers() }
  })

  it('delivers host-granted to onHostGranted with the new token', async () => {
    const relay = new TestRelay()
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    const granted: Array<{ member_id: string; owner_token: string }> = []
    const conn = connectAwarenessWs({
      roomId: 'us54-granted', awareness, doc,
      sessionId: '12121212-1212-4121-8121-121212121212',
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      WebSocketClass: function (url: string) { return relay.createClient(url) } as unknown as typeof WebSocket,
      onHostGranted: (msg) => granted.push({ member_id: msg.member_id, owner_token: msg.owner_token }),
    })
    conn.join('Alice')
    await new Promise((r) => setTimeout(r, 20))
    ;(conn.controlWs as unknown as TestWebSocket).onmessage?.(new MessageEvent('message', {
      data: JSON.stringify({ type: 'host-granted', member_id: 'm_7c22a1', owner_token: 'new-token' }),
    }))

    expect(granted).toEqual([{ member_id: 'm_7c22a1', owner_token: 'new-token' }])
    conn.destroy()
  })
})
