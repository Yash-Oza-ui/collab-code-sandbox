// @vitest-environment happy-dom
import { act } from 'react'
import type { RefObject } from 'react'
import { createRoot } from 'react-dom/client'
import type { Awareness } from 'y-protocols/awareness'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'
import { USER_COLORS } from './userColor'

vi.mock('@monaco-editor/react', () => ({
  default: () => <div data-testid="mock-monaco-editor" />,
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('RoomPage display-name and colour presence', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let originalPathname = '/'

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount())
      root = null
    }
    window.history.replaceState({}, '', originalPathname)
    container?.remove()
    container = null
    delete (window as unknown as { awareness?: unknown }).awareness
  })

  it('requires a display name before rendering the room editor, then sets colour badge and awareness', async () => {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/room-demo')
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RoomPage />)
    })

    expect(container.textContent).toContain('Choose your display name')
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    await act(async () => {
      input.value = 'Chandan'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).not.toBeNull()
    expect(container.textContent).toContain('You: Chandan')

    // US-05: check user colour dot in room header badge
    const badge = container.querySelector('[data-testid="room-user-badge"]')
    const dot = container.querySelector<HTMLElement>('[data-testid="room-user-color-dot"]')
    expect(badge).not.toBeNull()
    expect(dot).not.toBeNull()
    expect(dot?.style.backgroundColor).toBeTruthy()

    // US-05 DoD #5: check awareness exposed on window has name and colour
    const windowAwareness = (window as unknown as { awareness?: Awareness }).awareness
    expect(windowAwareness).toBeDefined()
    const localState = windowAwareness?.getLocalState() as { user?: { name: string; color: string } }
    expect(localState?.user?.name).toBe('Chandan')
    expect(localState?.user?.color).toBeTruthy()
  })

  it('calls onDisplayNameAccepted with both display name and assigned colour', async () => {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/room-demo')
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    let acceptedName = ''
    let acceptedColor = ''

    await act(async () => {
      root?.render(
        <RoomPage
          onDisplayNameAccepted={(name, color) => {
            acceptedName = name
            acceptedColor = color ?? ''
          }}
        />,
      )
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    await act(async () => {
      input.value = 'Alice'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    expect(acceptedName).toBe('Alice')
    expect(USER_COLORS).toContain(acceptedColor)
  })

  it('assigns a distinct colour when awareness already contains other participant colours', async () => {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/room-demo')
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    const awarenessRef: RefObject<Awareness | null> = { current: null }

    await act(async () => {
      root?.render(<RoomPage awarenessRef={awarenessRef} />)
    })

    // Simulate an existing peer already holding USER_COLORS[0]
    const awareness = awarenessRef.current
    expect(awareness).not.toBeNull()
    awareness!.states.set(999, {
      user: { name: 'Alice', color: USER_COLORS[0] },
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    await act(async () => {
      input.value = 'Bob'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    const localState = awareness!.getLocalState() as { user?: { name: string; color: string } }
    expect(localState.user?.name).toBe('Bob')
    // Must not collide with Alice's colour
    expect(localState.user?.color).not.toBe(USER_COLORS[0])
    expect(localState.user?.color).toBe(USER_COLORS[1])
  })

  it('cleans up window.awareness and destroys instance when unmounted', async () => {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/room-demo')
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    const awarenessRef: RefObject<Awareness | null> = { current: null }

    await act(async () => {
      root?.render(<RoomPage awarenessRef={awarenessRef} />)
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

  it('synchronizes Alice and Bob presence between two connected RoomPage instances over relay', async () => {
    interface RelayMember {
      sessionId: string
      displayName: string
    }

    class T20Relay {
      rooms = new Map<string, Map<string, RelayMember>>()
      controlSockets = new Map<RelaySocket, { roomId: string; sessionId: string }>()
      docSockets = new Map<RelaySocket, { roomId: string; docId: string; sessionId: string }>()

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
        if (!parsed) return
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
            const room = this.rooms.get(meta.roomId) ?? new Map()
            this.rooms.set(meta.roomId, room)
            room.set(meta.sessionId, {
              sessionId: meta.sessionId,
              displayName: msg.display_name,
            })
            // Reply joined
            ws.receiveText(
              JSON.stringify({
                type: 'joined',
                member_id: meta.sessionId,
                role: 'Guest',
                mode: 'COLLAB',
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

    const relay = new T20Relay()

    class RelaySocket {
      readyState = 1
      binaryType = 'arraybuffer'
      url: string
      onopen: ((e: Event) => void) | null = null
      onmessage: ((e: MessageEvent) => void) | null = null
      onclose: ((e: CloseEvent) => void) | null = null
      private listeners: Record<string, Set<(e: Event) => void>> = {}

      constructor(url: string) {
        this.url = url
        queueMicrotask(() => {
          relay.handleOpen(this)
          if (this.readyState === 1) {
            const ev = new Event('open')
            this.onopen?.(ev)
            this.dispatchEvent('open', ev)
          }
        })
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

      private dispatchEvent(type: string, ev: Event) {
        this.listeners[type]?.forEach((fn) => fn(ev))
      }

      send(data: unknown) {
        if (typeof data === 'string') {
          relay.handleText(this, data)
        } else if (data instanceof Uint8Array || data instanceof ArrayBuffer) {
          const uint8 = data instanceof ArrayBuffer ? new Uint8Array(data) : data
          relay.handleBinary(this, uint8)
        }
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
        this.readyState = 3
        relay.handleClose(this)
        const ev = new CloseEvent('close', { code, reason })
        this.onclose?.(ev)
        this.dispatchEvent('close', ev)
      }
    }

    const containerA = document.createElement('div')
    const containerB = document.createElement('div')
    document.body.appendChild(containerA)
    document.body.appendChild(containerB)

    const rootA = createRoot(containerA)
    const rootB = createRoot(containerB)

    const awarenessRefA: RefObject<Awareness | null> = { current: null }
    const awarenessRefB: RefObject<Awareness | null> = { current: null }

    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/shared-room-101')

    await act(async () => {
      rootA.render(
        <RoomPage
          awarenessRef={awarenessRefA}
          wsOptions={{
            WebSocketClass: RelaySocket as unknown as typeof WebSocket,
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

    // Render Bob
    await act(async () => {
      rootB.render(
        <RoomPage
          awarenessRef={awarenessRefB}
          wsOptions={{
            WebSocketClass: RelaySocket as unknown as typeof WebSocket,
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

    // Allow awareness updates to propagate through the relay
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50))
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

    // Unmount
    await act(async () => {
      rootA.unmount()
      rootB.unmount()
    })
    containerA.remove()
    containerB.remove()
  })
})
