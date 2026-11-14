import { describe, expect, it } from 'vitest'
import * as Y from 'yjs'
import { createAwareness, getExistingUserColors, setUserAwareness } from './awareness'
import {
  CLOSE_NOT_JOINED,
  COLLAB_SESSION_ID_KEY,
  MESSAGE_AWARENESS,
  MESSAGE_QUERY_AWARENESS,
  buildControlWebSocketUrl,
  buildDocumentWebSocketUrl,
  buildRoomWebSocketUrl,
  connectAwarenessWs,
  encodeAwarenessMessage,
  encodeQueryAwarenessMessage,
  generateUuidV4,
  getCollabSessionId,
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

  createClient(url: string): TestWebSocket {
    return new TestWebSocket(url, this)
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

        // 1. Reply to joining client with 'joined'
        ws.receiveText(
          JSON.stringify({
            type: 'joined',
            member_id: meta.sessionId,
            role: session.role,
            mode: 'COLLAB',
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

  constructor(url: string, relay: TestRelay) {
    this.url = url
    this.relay = relay
    queueMicrotask(() => {
      if (this.readyState === TestWebSocket.CONNECTING) {
        this.readyState = TestWebSocket.OPEN
        this.relay.handleOpen(this)
        if (this.readyState === TestWebSocket.OPEN) {
          const ev = new Event('open')
          this.onopen?.(ev)
          this.dispatchEvent('open', ev)
        }
      }
    })
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
})
