import { describe, expect, it } from 'vitest'
import * as Y from 'yjs'
import { createAwareness, getExistingUserColors, setUserAwareness } from './awareness'
import {
  COLLAB_SESSION_ID_KEY,
  MESSAGE_AWARENESS,
  MESSAGE_QUERY_AWARENESS,
  buildRoomWebSocketUrl,
  connectAwarenessWs,
  encodeAwarenessMessage,
  encodeQueryAwarenessMessage,
  generateUuidV4,
  getCollabSessionId,
} from './awarenessWs'
import { USER_COLORS, pickUserColor } from './userColor'

/**
 * High-fidelity in-memory WebSocket Relay replicating sync-service RelayHandler.java:
 * - Rule 1a: Forwards binary frames to all other clients in the room.
 * - Rule 1b: Echoes awareness frames (first byte == 1) back to sender.
 * - Rule 6: Forwards query-awareness frames (first byte == 3) to other peers.
 */
class TestRelay {
  clients = new Set<TestWebSocket>()

  createClient(url: string): TestWebSocket {
    const ws = new TestWebSocket(url, this)
    this.clients.add(ws)
    return ws
  }

  removeClient(ws: TestWebSocket): void {
    this.clients.delete(ws)
  }

  broadcast(sender: TestWebSocket, data: ArrayBuffer | Uint8Array): void {
    const uint8 = data instanceof ArrayBuffer ? new Uint8Array(data) : data
    if (uint8.length === 0) return

    const isAwareness = uint8[0] === 1

    // Rule 1a: forward to others
    for (const client of this.clients) {
      if (client !== sender) {
        client.receive(uint8)
      }
    }

    // Rule 1b: echo awareness frames back to sender
    if (isAwareness) {
      sender.receive(uint8)
    }
  }
}

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

  private relay: TestRelay

  constructor(url: string, relay: TestRelay) {
    this.url = url
    this.relay = relay
    queueMicrotask(() => {
      if (this.readyState === TestWebSocket.CONNECTING) {
        this.readyState = TestWebSocket.OPEN
        this.onopen?.(new Event('open'))
      }
    })
  }

  send(data: unknown): void {
    if (this.readyState !== TestWebSocket.OPEN) return
    if (data instanceof Uint8Array || data instanceof ArrayBuffer) {
      this.relay.broadcast(this, data)
    }
  }

  receive(data: Uint8Array): void {
    if (this.readyState !== TestWebSocket.OPEN) return
    const buffer = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength)
    this.onmessage?.(new MessageEvent('message', { data: buffer }))
  }

  close(): void {
    if (this.readyState === TestWebSocket.CLOSED) return
    this.readyState = TestWebSocket.CLOSED
    this.relay.removeClient(this)
    this.onclose?.(new CloseEvent('close'))
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

  it('builds standard room WebSocket URL per T-06 §3.1', () => {
    const url = buildRoomWebSocketUrl(
      'ws://localhost:8081/ws/rooms',
      'demo-room-42',
      '00000000-0000-4000-8000-000000000001',
    )
    expect(url).toBe(
      'ws://localhost:8081/ws/rooms/demo-room-42?session_id=00000000-0000-4000-8000-000000000001',
    )
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

describe('multi-client awareness synchronization over WebSocket relay', () => {
  it('synchronizes Alice and Bob presence, allocating distinct colours across peers', async () => {
    const relay = new TestRelay()

    // 1. Client A setup (Alice)
    const docA = new Y.Doc()
    const awarenessA = createAwareness(docA)
    const connA = connectAwarenessWs({
      roomId: 'test-sync-room',
      awareness: awarenessA,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '11111111-1111-4111-8111-111111111111',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
    })

    // Wait for connection to open
    await new Promise((r) => setTimeout(r, 10))

    // Client A enters display name "Alice" and receives color
    const existingColorsA = getExistingUserColors(awarenessA)
    const colorA = pickUserColor(existingColorsA, docA.clientID)
    setUserAwareness(awarenessA, { name: 'Alice', color: colorA })

    // Give time for awareness frame propagation
    await new Promise((r) => setTimeout(r, 10))

    // 2. Client B setup (Bob joins the same room)
    const docB = new Y.Doc()
    const awarenessB = createAwareness(docB)
    const connB = connectAwarenessWs({
      roomId: 'test-sync-room',
      awareness: awarenessB,
      syncWsUrl: 'ws://localhost:8081/ws/rooms',
      sessionId: '22222222-2222-4222-8222-222222222222',
      WebSocketClass: function (url: string) {
        return relay.createClient(url)
      } as unknown as typeof WebSocket,
    })

    // Give time for connection open + query awareness exchange
    await new Promise((r) => setTimeout(r, 20))

    // Verify Client B already observed Alice's presence before choosing name
    const existingColorsB = getExistingUserColors(awarenessB)
    expect(existingColorsB).toContain(colorA)

    // Client B enters display name "Bob" and receives color
    const colorB = pickUserColor(existingColorsB, docB.clientID)
    setUserAwareness(awarenessB, { name: 'Bob', color: colorB })

    // Give time for Bob's frame to reach Alice
    await new Promise((r) => setTimeout(r, 20))

    // 3. Assertions requested in Review Point 2:
    // - Both clients receive and observe the other's name and colour in their Awareness states
    // - Their assigned colours are distinct

    // Inspect Client A's awareness states
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

    // Teardown connections cleanly
    connB.destroy()
    await new Promise((r) => setTimeout(r, 10))

    // Verify Client B state is removed from Client A
    const statesAfterDisconnect = Array.from(awarenessA.getStates().values())
    const bobAfterLeave = statesAfterDisconnect.find((s) => s.user?.name === 'Bob')
    expect(bobAfterLeave).toBeUndefined()

    connA.destroy()
  })
})

