// @vitest-environment happy-dom
import { act } from 'react'
import type { RefObject } from 'react'
import { createRoot } from 'react-dom/client'
import type { Awareness } from 'y-protocols/awareness'
import type * as Y from 'yjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'
import { USER_COLORS } from './userColor'

vi.mock('@monaco-editor/react', () => ({
  default: () => <div data-testid="mock-monaco-editor" />,
}))

vi.mock('y-monaco', () => ({
  MonacoBinding: class {
    destroy() {}
  },
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

class MockCloseEvent extends Event {
  code: number
  reason: string
  constructor(type: string, init?: { code?: number; reason?: string }) {
    super(type)
    this.code = init?.code ?? 1000
    this.reason = init?.reason ?? ''
  }
}

const SafeCloseEvent =
  typeof CloseEvent !== 'undefined'
    ? CloseEvent
    : (MockCloseEvent as unknown as typeof CloseEvent)

interface RelayMember {
  sessionId: string
  displayName: string
}

class T20Relay {
  rooms = new Map<string, Map<string, RelayMember>>()
  controlSockets = new Map<RelaySocket, { roomId: string; sessionId: string }>()
  docSockets = new Map<RelaySocket, { roomId: string; docId: string; sessionId: string }>()
  refuseNames = new Set<string>()
  roomModes = new Map<string, 'pair' | 'open'>()
  roomModules = new Map<string, Set<string>>()
  ignoreJoins = false
  autoOpen = true

  parseUrl(url: string) {
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

  handleOpen(ws: RelaySocket) {
    const parsed = this.parseUrl(ws.url)
    if (!parsed) {
      ws.close(4400, 'Malformed URL')
      return
    }
    if (parsed.type === 'control') {
      this.controlSockets.set(ws, { roomId: parsed.roomId, sessionId: parsed.sessionId })
      if (!this.rooms.has(parsed.roomId)) {
        this.rooms.set(parsed.roomId, new Map())
      }
    } else {
      const room = this.rooms.get(parsed.roomId)
      if (!room?.has(parsed.sessionId)) {
        ws.close(4406, 'Not joined')
        return
      }

      // 4405: doc_id validation (Pair mode must be 'main', Open mode must be registered module)
      const mode = this.roomModes.get(parsed.roomId) ?? 'pair'
      if (mode === 'pair') {
        if (parsed.docId !== 'main') {
          ws.close(4405, 'Unknown doc_id')
          return
        }
      } else {
        const modules = this.roomModules.get(parsed.roomId)
        if (!modules || !modules.has(parsed.docId)) {
          ws.close(4405, 'Unknown module_id')
          return
        }
      }

      this.docSockets.set(ws, {
        roomId: parsed.roomId,
        docId: parsed.docId,
        sessionId: parsed.sessionId,
      })
    }
  }

  handleText(ws: RelaySocket, text: string) {
    const meta = this.controlSockets.get(ws)
    if (!meta) return
    try {
      const msg = JSON.parse(text)
      if (msg.type === 'join') {
        if (this.ignoreJoins) {
          // Intentionally do not reply to test client join-handshake timeout
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
        room.set(meta.sessionId, {
          sessionId: meta.sessionId,
          displayName: msg.display_name,
        })
        const roomMode = this.roomModes.get(meta.roomId) ?? 'pair'
        ws.receiveText(
          JSON.stringify({
            type: 'joined',
            member_id: meta.sessionId,
            role: 'Guest',
            mode: roomMode,
            permissions: { can_edit: true, can_run: true },
          }),
        )
      }
    } catch {
      // ignore
    }
  }

  handleBinary(ws: RelaySocket, data: Uint8Array) {
    const meta = this.docSockets.get(ws)
    if (!meta) return
    const isAwareness = data.length > 0 && data[0] === 1

    for (const [client, clientMeta] of this.docSockets.entries()) {
      if (
        client !== ws &&
        clientMeta.roomId === meta.roomId &&
        clientMeta.docId === meta.docId &&
        client.readyState === 1
      ) {
        client.receiveBinary(data)
      }
    }

    if (isAwareness && ws.readyState === 1) {
      ws.receiveBinary(data)
    }
  }

  handleClose(ws: RelaySocket) {
    this.controlSockets.delete(ws)
    this.docSockets.delete(ws)
  }
}

class RelaySocket {
  readyState = 0
  binaryType = 'arraybuffer'
  url: string
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onclose: ((e: CloseEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  private listeners: Record<string, Set<(e: Event) => void>> = {}
  protected relay: T20Relay
  autoOpen: boolean

  constructor(url: string, relay: T20Relay, autoOpen = true) {
    this.url = url
    this.relay = relay
    this.autoOpen = autoOpen
    if (this.autoOpen) {
      queueMicrotask(() => {
        if (this.readyState === 0) {
          this.triggerOpen()
        }
      })
    }
  }

  triggerOpen() {
    if (this.readyState === 0) {
      this.readyState = 1
      this.relay.handleOpen(this)
      if (this.readyState === 1) {
        const ev = new Event('open')
        this.onopen?.(ev)
        this.dispatchEvent('open', ev)
      }
    }
  }

  addEventListener(type: string, listener: (e: Event) => void, options?: { once?: boolean }) {
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

  removeEventListener(type: string, listener: (e: Event) => void) {
    this.listeners[type]?.delete(listener)
  }

  dispatchEvent(type: string, ev: Event) {
    this.listeners[type]?.forEach((fn) => fn(ev))
  }

  send(data: unknown) {
    if (data instanceof Uint8Array || data instanceof ArrayBuffer) {
      const uint8 = data instanceof ArrayBuffer ? new Uint8Array(data) : data
      this.relay.handleBinary(this, uint8)
      return
    }
    setTimeout(() => {
      if (this.readyState !== 1) return
      if (typeof data === 'string') {
        this.relay.handleText(this, data)
      }
    }, 10)
  }

  receiveText(text: string) {
    if (this.readyState !== 1) return
    const ev = new MessageEvent('message', { data: text })
    this.onmessage?.(ev)
    this.dispatchEvent('message', ev)
  }

  receiveBinary(data: Uint8Array) {
    if (this.readyState !== 1) return
    const buf = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength)
    const ev = new MessageEvent('message', { data: buf })
    this.onmessage?.(ev)
    this.dispatchEvent('message', ev)
  }

  close(code = 1000, reason = '') {
    if (this.readyState === 3) return
    this.readyState = 3
    this.relay.handleClose(this)
    const ev = new SafeCloseEvent('close', { code, reason })
    this.onclose?.(ev as CloseEvent)
    this.dispatchEvent('close', ev)
  }
}

function createRelaySocketClass(relay: T20Relay, autoOpen?: boolean): typeof WebSocket {
  return class extends RelaySocket {
    constructor(url: string) {
      super(url, relay, autoOpen ?? relay.autoOpen)
    }
  } as unknown as typeof WebSocket
}

describe('RoomPage display-name and colour presence join lifecycle', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let originalPathname = '/'
  let relay: T20Relay

  beforeEach(() => {
    relay = new T20Relay()
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/room-demo')
  })

  afterEach(async () => {
    if (root) {
      await act(async () => {
        root?.unmount()
      })
      root = null
    }
    window.history.replaceState({}, '', originalPathname)
    container?.remove()
    container = null
  })

  it('requires a display name before rendering the room editor', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RoomPage />)
    })

    expect(container.textContent).toContain('Choose your display name')
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()
  })

  it('shows an invalid-link error when the room ID is missing', async () => {
    window.history.replaceState({}, '', '/room/')
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RoomPage />)
    })

    expect(container.textContent).toContain('Invalid room link')
    expect(container.querySelector('[role="alert"]')).not.toBeNull()
  })

  it('rejects room IDs containing invalid characters', async () => {
    window.history.replaceState({}, '', '/room/invalid.room')
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RoomPage />)
    })

    expect(container.textContent).toContain('Invalid room link')
  })

  it('rejects room IDs longer than 64 characters', async () => {
    window.history.replaceState({}, '', `/room/${'a'.repeat(65)}`)
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RoomPage />)
    })

    expect(container.textContent).toContain('Invalid room link')
  })

  it('successful join: keeps editor hidden until joined, then displays editor, badge, and awareness', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    const awarenessRef: RefObject<Awareness | null> = { current: null }
    let acceptedName: string | undefined
    let acceptedColor: string | undefined

    await act(async () => {
      root?.render(
        <RoomPage
          awarenessRef={awarenessRef}
          onDisplayNameAccepted={(name, color) => {
            acceptedName = name
            acceptedColor = color
          }}
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '10000000-0000-4000-8000-000000000001',
          }}
        />,
      )
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    // Editor hidden initially
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()

    // Submitting display name
    await act(async () => {
      input.value = 'Alice'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    // Allow mock WebSocket microtask to cycle and joined message to process
    await act(async () => {
      await new Promise((r) => setTimeout(r, 45))
    })

    // Editor is now visible
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).not.toBeNull()

    // Header displays display name and user colour dot
    const badge = container.querySelector('[data-testid="room-user-badge"]')
    expect(badge).not.toBeNull()
    expect(badge?.textContent).toContain('You: Alice')

    const dot = container.querySelector<HTMLSpanElement>('[data-testid="room-user-color-dot"]')
    expect(dot).not.toBeNull()
    expect(dot?.style.backgroundColor).toBeTruthy()
    expect(USER_COLORS).toContain(dot?.style.backgroundColor)

    // Callback invoked
    expect(acceptedName).toBe('Alice')
    expect(acceptedColor).toBe(dot?.style.backgroundColor)

    // Awareness local state initialized with name and color
    const awareness = awarenessRef.current
    expect(awareness).not.toBeNull()
    const localUser = (awareness?.getLocalState() as { user?: { name: string; color: string } })?.user
    expect(localUser?.name).toBe('Alice')
    expect(localUser?.color).toBe(acceptedColor)
  })

  it('refused join: keeps editor hidden on join-refused, shows error alert, and allows retry', async () => {
    relay.refuseNames.add('DuplicateName')

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(
        <RoomPage
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '20000000-0000-4000-8000-000000000002',
          }}
        />,
      )
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    // Submit name that will be refused by relay
    await act(async () => {
      input.value = 'DuplicateName'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await act(async () => {
      await new Promise((r) => setTimeout(r, 35))
    })

    // Editor MUST remain hidden
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()

    // Server refusal message displayed to user
    const alert = container.querySelector('[role="alert"]')
    expect(alert).not.toBeNull()
    expect(alert?.textContent).toContain('That display name is already in use')

    // User can retry with a different name
    await act(async () => {
      input.value = 'ValidUniqueName'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await act(async () => {
      await new Promise((r) => setTimeout(r, 35))
    })

    // Editor is now accessible!
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).not.toBeNull()
    expect(container.textContent).toContain('You: ValidUniqueName')
  })

  it('connection failure: keeps editor hidden and communicates failure if socket drops before join', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(
        <RoomPage
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '30000000-0000-4000-8000-000000000003',
          }}
        />,
      )
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    // Start join
    await act(async () => {
      input.value = 'Bob'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    // Simulate premature server drop before join completes
    const controlSocket = Array.from(relay.controlSockets.keys())[0]
    expect(controlSocket).toBeDefined()
    await act(async () => {
      controlSocket?.close(1006, 'Abnormal closure')
    })

    // Editor must NOT render
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()

    // Error alert must be shown
    const alert = container.querySelector('[role="alert"]')
    expect(alert).not.toBeNull()
    expect(alert?.textContent).toContain('Connection closed before joining')
  })

  it('assigns a distinct colour when awareness already contains other participant colours', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    const awarenessRef: RefObject<Awareness | null> = { current: null }

    await act(async () => {
      root?.render(
        <RoomPage
          awarenessRef={awarenessRef}
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '40000000-0000-4000-8000-000000000004',
          }}
        />,
      )
    })

    // Pre-populate awareness with other users before this client joins
    const awareness = awarenessRef.current
    expect(awareness).not.toBeNull()

    // Remote peer 1 takes color index 0
    awareness?.setLocalStateField('user', { name: 'Peer1', color: USER_COLORS[0] })
    // Change clientID of that state to represent remote peer
    const remoteStates = new Map(awareness?.getStates())
    remoteStates.set(999, { user: { name: 'Peer1', color: USER_COLORS[0] } })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    await act(async () => {
      input.value = 'Charlie'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await act(async () => {
      await new Promise((r) => setTimeout(r, 45))
    })

    const dot = container.querySelector<HTMLSpanElement>('[data-testid="room-user-color-dot"]')
    expect(dot).not.toBeNull()
    // Assigned colour should not be Peer1's colour (USER_COLORS[0])
    expect(dot?.style.backgroundColor).not.toBe(USER_COLORS[0])
  })

  it('synchronizes Alice and Bob presence and removes Bob on disconnect', async () => {
    const roomId = 'multi-client-room'
    window.history.replaceState({}, '', `/room/${roomId}`)

    const containerA = document.createElement('div')
    const containerB = document.createElement('div')
    document.body.appendChild(containerA)
    document.body.appendChild(containerB)

    let rootA: ReturnType<typeof createRoot> | null = createRoot(containerA)
    let rootB: ReturnType<typeof createRoot> | null = createRoot(containerB)

    const awarenessRefA: RefObject<Awareness | null> = { current: null }
    const awarenessRefB: RefObject<Awareness | null> = { current: null }

    // Render Alice
    await act(async () => {
      rootA?.render(
        <RoomPage
          awarenessRef={awarenessRefA}
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '11111111-1111-4111-8111-111111111111',
          }}
        />,
      )
    })

    // Alice joins
    const inputA = containerA.querySelector<HTMLInputElement>('#display-name')!
    const formA = containerA.querySelector<HTMLFormElement>('form')!
    await act(async () => {
      inputA.value = 'Alice'
      formA.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await act(async () => {
      await new Promise((r) => setTimeout(r, 35))
    })

    // Render Bob
    await act(async () => {
      rootB?.render(
        <RoomPage
          awarenessRef={awarenessRefB}
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '22222222-2222-4222-8222-222222222222',
          }}
        />,
      )
    })

    // Bob joins
    const inputB = containerB.querySelector<HTMLInputElement>('#display-name')!
    const formB = containerB.querySelector<HTMLFormElement>('form')!
    await act(async () => {
      inputB.value = 'Bob'
      formB.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    // Allow awareness updates to propagate through relay
    await act(async () => {
      await new Promise((r) => setTimeout(r, 60))
    })

    const statesA = Array.from(awarenessRefA.current?.getStates().values() ?? [])
    const statesB = Array.from(awarenessRefB.current?.getStates().values() ?? [])

    const aliceInA = statesA.find((s) => s.user?.name === 'Alice')
    const bobInA = statesA.find((s) => s.user?.name === 'Bob')
    const aliceInB = statesB.find((s) => s.user?.name === 'Alice')
    const bobInB = statesB.find((s) => s.user?.name === 'Bob')

    expect(aliceInA?.user?.name).toBe('Alice')
    expect(bobInA?.user?.name).toBe('Bob')
    expect(aliceInB?.user?.name).toBe('Alice')
    expect(bobInB?.user?.name).toBe('Bob')

    // Assigned colours are distinct
    expect(aliceInA?.user?.color).not.toBe(bobInA?.user?.color)
    expect(aliceInB?.user?.color).toBe(aliceInA?.user?.color)
    expect(bobInB?.user?.color).toBe(bobInA?.user?.color)

    // Disconnect Bob (unmount rootB)
    await act(async () => {
      rootB?.unmount()
      rootB = null
    })

    await act(async () => {
      await new Promise((r) => setTimeout(r, 35))
    })

    // Bob's presence is removed from Alice's awareness
    const statesAfterBobLeave = Array.from(awarenessRefA.current?.getStates().values() ?? [])
    const bobAfterLeave = statesAfterBobLeave.find((s) => s.user?.name === 'Bob')
    expect(bobAfterLeave).toBeUndefined()

    // Unmount Alice
    await act(async () => {
      rootA?.unmount()
      rootA = null
    })
    containerA.remove()
    containerB.remove()
  })

  it('cleans up window.awareness and destroys instance when unmounted', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    const awarenessRef: RefObject<Awareness | null> = { current: null }

    await act(async () => {
      root?.render(
        <RoomPage
          awarenessRef={awarenessRef}
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '50000000-0000-4000-8000-000000000005',
          }}
        />,
      )
    })

    const awareness = awarenessRef.current
    expect(awareness).not.toBeNull()
    expect((window as unknown as { awareness?: Awareness }).awareness).toBe(awareness)

    await act(async () => {
      root?.unmount()
      root = null
    })

    expect((window as unknown as { awareness?: Awareness }).awareness).toBeUndefined()
    expect(awarenessRef.current).toBeNull()
  })

  it('join timeout and recoverable retry: unblocks form after timeout and allows retry', async () => {
    relay.ignoreJoins = true // Control socket connects, but server hangs / drops join

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(
        <RoomPage
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '60000000-0000-4000-8000-000000000006',
            joinTimeoutMs: 35, // 35ms timeout for testing
          }}
        />,
      )
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    // User submits name
    await act(async () => {
      input.value = 'Dennis'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    // While waiting for server: button is disabled, loading state shown
    const submitBtn = container.querySelector<HTMLButtonElement>('button')!
    expect(submitBtn.disabled).toBe(true)
    expect(submitBtn.textContent).toBe('Joining room...')
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()

    // Wait for timeout to fire (35ms timeout + grace period)
    await act(async () => {
      await new Promise((r) => setTimeout(r, 60))
    })

    // Form is now unblocked and shows timeout alert
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()
    const alert = container.querySelector('[role="alert"]')
    expect(alert).not.toBeNull()
    expect(alert?.textContent).toContain('Join request timed out')

    const retryBtn = container.querySelector<HTMLButtonElement>('button')!
    expect(retryBtn.disabled).toBe(false)
    expect(retryBtn.textContent).toBe('Join room')

    // Typed name was preserved for retry
    const retryInput = container.querySelector<HTMLInputElement>('#display-name')!
    expect(retryInput.value).toBe('Dennis')

    // Server is now responsive again
    relay.ignoreJoins = false

    // User retries
    await act(async () => {
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await act(async () => {
      await new Promise((r) => setTimeout(r, 35))
    })

    // Retry succeeded: Editor becomes visible
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).not.toBeNull()
    expect(container.textContent).toContain('You: Dennis')
  })

  it('Open mode awareness synchronization: synchronizes Alice and Bob over registered module docId', async () => {
    const roomId = 'open-sync-room'
    window.history.replaceState({}, '', `/room/${roomId}`)
    relay.roomModes.set(roomId, 'open')
    relay.roomModules.set(roomId, new Set(['mod_calc_1']))

    const containerA = document.createElement('div')
    const containerB = document.createElement('div')
    document.body.appendChild(containerA)
    document.body.appendChild(containerB)

    let rootA: ReturnType<typeof createRoot> | null = createRoot(containerA)
    let rootB: ReturnType<typeof createRoot> | null = createRoot(containerB)

    const awarenessRefA: RefObject<Awareness | null> = { current: null }
    const awarenessRefB: RefObject<Awareness | null> = { current: null }

    await act(async () => {
      rootA?.render(
        <RoomPage
          docId="mod_calc_1"
          awarenessRef={awarenessRefA}
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '71111111-1111-4111-8111-111111111111',
          }}
        />,
      )
    })

    const inputA = containerA.querySelector<HTMLInputElement>('#display-name')!
    const formA = containerA.querySelector<HTMLFormElement>('form')!
    await act(async () => {
      inputA.value = 'Alice'
      formA.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await act(async () => {
      await new Promise((r) => setTimeout(r, 35))
    })

    await act(async () => {
      rootB?.render(
        <RoomPage
          docId="mod_calc_1"
          awarenessRef={awarenessRefB}
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '72222222-2222-4222-8222-222222222222',
          }}
        />,
      )
    })

    const inputB = containerB.querySelector<HTMLInputElement>('#display-name')!
    const formB = containerB.querySelector<HTMLFormElement>('form')!
    await act(async () => {
      inputB.value = 'Bob'
      formB.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await act(async () => {
      await new Promise((r) => setTimeout(r, 60))
    })

    // Both clients are on Open-mode module document mod_calc_1
    const statesA = Array.from(awarenessRefA.current?.getStates().values() ?? [])
    const statesB = Array.from(awarenessRefB.current?.getStates().values() ?? [])

    const aliceInA = statesA.find((s) => s.user?.name === 'Alice')
    const bobInA = statesA.find((s) => s.user?.name === 'Bob')
    const aliceInB = statesB.find((s) => s.user?.name === 'Alice')
    const bobInB = statesB.find((s) => s.user?.name === 'Bob')

    expect(aliceInA?.user?.name).toBe('Alice')
    expect(bobInA?.user?.name).toBe('Bob')
    expect(aliceInB?.user?.name).toBe('Alice')
    expect(bobInB?.user?.name).toBe('Bob')

    // Distinct colours verified in Open mode
    expect(aliceInA?.user?.color).not.toBe(bobInA?.user?.color)

    await act(async () => {
      rootB?.unmount()
      rootA?.unmount()
      rootB = null
      rootA = null
    })
    containerA.remove()
    containerB.remove()
  })

  it('Open mode 4405 document socket rejection surfaces visible error badge', async () => {
    const roomId = 'open-err-room'
    window.history.replaceState({}, '', `/room/${roomId}`)
    relay.roomModes.set(roomId, 'open')
    relay.roomModules.set(roomId, new Set(['mod_approved_only']))

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(
        <RoomPage
          docId="mod_unregistered_invalid"
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '80000000-0000-4000-8000-000000000008',
          }}
        />,
      )
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!
    await act(async () => {
      input.value = 'Eve'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await act(async () => {
      await new Promise((r) => setTimeout(r, 45))
    })

    // Control join was successful, so editor mounted
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).not.toBeNull()

    // But document socket closed with 4405, and error is visibly surfaced!
    const docErrorBadge = container.querySelector('[data-testid="room-doc-error"]')
    expect(docErrorBadge).not.toBeNull()
    expect(docErrorBadge?.textContent).toContain('4405')
  })

  it('ignores late server response after timeout without mounting editor or corrupting state', async () => {
    let capturedClient: RelaySocket | null = null
    relay.ignoreJoins = true

    const RelayClass = createRelaySocketClass(relay)
    const WebSocketClass = function (url: string) {
      const client = new RelayClass(url) as unknown as RelaySocket
      if (!url.includes('/docs/')) {
        capturedClient = client
      }
      return client as unknown as WebSocket
    } as unknown as typeof WebSocket

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(
        <RoomPage
          wsOptions={{
            WebSocketClass,
            sessionId: '90000000-0000-4000-8000-000000000009',
            joinTimeoutMs: 35,
          }}
        />,
      )
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!
    await act(async () => {
      input.value = 'Frank'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    // Wait for timeout to fire
    await act(async () => {
      await new Promise((r) => setTimeout(r, 60))
    })

    // Timeout alert is shown, editor is hidden
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('Join request timed out')
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()

    // Late joined message arrives on the stale socket
    await act(async () => {
      capturedClient?.receiveText(
        JSON.stringify({
          type: 'joined',
          member_id: '90000000-0000-4000-8000-000000000009',
          role: 'Guest',
          mode: 'pair',
          permissions: { can_edit: true, can_run: true },
        }),
      )
    })

    // Editor MUST remain hidden and prompt still accessible
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('Join request timed out')
    expect(container.querySelector<HTMLButtonElement>('button')?.disabled).toBe(false)
  })

  it('CONNECTING timeout in RoomPage: unblocks form after timeout and allows retry with fresh socket', async () => {
    relay.autoOpen = false // Socket remains CONNECTING indefinitely

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(
        <RoomPage
          wsOptions={{
            WebSocketClass: createRelaySocketClass(relay),
            sessionId: '91111111-1111-4111-8111-111111111111',
            joinTimeoutMs: 35,
          }}
        />,
      )
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!
    await act(async () => {
      input.value = 'Grace'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    // Submitting: loading state shown, editor hidden
    expect(container.querySelector('button')?.disabled).toBe(true)
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()

    // Wait for timeout to fire while CONNECTING
    await act(async () => {
      await new Promise((r) => setTimeout(r, 60))
    })

    // Form unblocked with timeout alert
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('Join request timed out')
    expect(container.querySelector('button')?.disabled).toBe(false)
    expect(container.querySelector<HTMLInputElement>('#display-name')?.value).toBe('Grace')

    // Now enable autoOpen for retry
    relay.autoOpen = true

    // User retries
    await act(async () => {
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await act(async () => {
      await new Promise((r) => setTimeout(r, 40))
    })

    // Fresh socket opened and join succeeded: editor mounts!
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).not.toBeNull()
    expect(container.textContent).toContain('You: Grace')
  })

  it('merges simultaneous edits between two RoomPage participants over the relay without losing either contribution (US-02 / FR2)', async () => {
    const relay = new T20Relay()
    const docRefA: RefObject<Y.Doc | null> = { current: null }
    const docRefB: RefObject<Y.Doc | null> = { current: null }

    const containerA = document.createElement('div')
    const containerB = document.createElement('div')
    document.body.appendChild(containerA)
    document.body.appendChild(containerB)

    const rootA = createRoot(containerA)
    const rootB = createRoot(containerB)

    try {
      await act(async () => {
        rootA.render(
          <RoomPage
            docRef={docRefA}
            wsOptions={{
              WebSocketClass: createRelaySocketClass(relay),
              sessionId: '11111111-1111-4111-8111-111111111111',
            }}
          />,
        )
        rootB.render(
          <RoomPage
            docRef={docRefB}
            wsOptions={{
              WebSocketClass: createRelaySocketClass(relay),
              sessionId: '22222222-2222-4222-8222-222222222222',
            }}
          />,
        )
      })

      // Client A joins as Alice
      const inputA = containerA.querySelector<HTMLInputElement>('#display-name')!
      const formA = containerA.querySelector<HTMLFormElement>('form')!
      await act(async () => {
        inputA.value = 'Alice'
        formA.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
      })

      // Client B joins as Bob
      const inputB = containerB.querySelector<HTMLInputElement>('#display-name')!
      const formB = containerB.querySelector<HTMLFormElement>('form')!
      await act(async () => {
        inputB.value = 'Bob'
        formB.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
      })

      await act(async () => {
        await new Promise((r) => setTimeout(r, 40))
      })

      expect(docRefA.current).not.toBeNull()
      expect(docRefB.current).not.toBeNull()

      // Shared initial text
      await act(async () => {
        docRefA.current?.getText('monaco').insert(0, 'BASE_CODE')
        await new Promise((r) => setTimeout(r, 30))
      })

      expect(docRefB.current?.getText('monaco').toString()).toBe('BASE_CODE')

      // Simultaneous edits by both users
      await act(async () => {
        docRefA.current?.getText('monaco').insert(9, '_ALICE')
        docRefB.current?.getText('monaco').insert(9, '_BOB')
        await new Promise((r) => setTimeout(r, 50))
      })

      const textA = docRefA.current?.getText('monaco').toString()
      const textB = docRefB.current?.getText('monaco').toString()

      // Both converge to identical state
      expect(textA).toBe(textB)

      // Both contributions preserved
      expect(textA).toContain('BASE_CODE')
      expect(textA).toContain('_ALICE')
      expect(textA).toContain('_BOB')
    } finally {
      await act(async () => {
        rootA.unmount()
        rootB.unmount()
      })
      containerA.remove()
      containerB.remove()
    }
  })
})
