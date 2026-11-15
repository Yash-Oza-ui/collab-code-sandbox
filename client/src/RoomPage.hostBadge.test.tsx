// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'
import { ownerTokenKey } from './createRoom'

// T-10 (FR9.5): the room header shows "Host" for the owner, and the stored
// owner token is sent in the join message, never in a URL.

vi.mock('@monaco-editor/react', () => ({
  default: () => <div data-testid="mock-monaco-editor" />,
}))

vi.mock('y-monaco', () => ({
  MonacoBinding: class {
    destroy() {}
  },
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const ROOM_ID = 'room-host-test'
const SESSION_ID = '20000000-0000-4000-8000-000000000002'
const OWNER_TOKEN = 'secret-owner-token-for-test'

/** Minimal fake WebSocket: answers a `join` frame with a `joined` frame carrying `FakeSocket.role`. */
class FakeSocket {
  static role = 'host'
  static urls: string[] = []
  static joinFrames: Array<Record<string, unknown>> = []

  readyState = 0
  binaryType = 'arraybuffer'
  url: string
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onclose: ((e: CloseEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeSocket.urls.push(url)
    queueMicrotask(() => {
      this.readyState = 1
      this.onopen?.(new Event('open'))
    })
  }

  send(data: unknown) {
    if (typeof data !== 'string') return // binary Yjs/awareness frames are ignored
    const msg = JSON.parse(data) as Record<string, unknown>
    if (msg.type !== 'join') return
    FakeSocket.joinFrames.push(msg)
    setTimeout(() => {
      this.onmessage?.(
        new MessageEvent('message', {
          data: JSON.stringify({
            type: 'joined',
            member_id: 'member-1',
            role: FakeSocket.role,
            mode: 'pair',
            permissions: { can_edit: true, can_run: true },
          }),
        }),
      )
    }, 0)
  }

  close() {
    this.readyState = 3
  }
}

/** In-memory Storage. Stubbed as a global like the other tests, so this works on every Node version. */
function makeStorage(): Storage {
  const data = new Map<string, string>()
  return {
    get length() {
      return data.size
    },
    clear: () => data.clear(),
    getItem: (key: string) => data.get(key) ?? null,
    key: (index: number) => Array.from(data.keys())[index] ?? null,
    removeItem: (key: string) => {
      data.delete(key)
    },
    setItem: (key: string, value: string) => {
      data.set(key, String(value))
    },
  } as Storage
}

describe('RoomPage Host indicator and owner token (T-10, FR9.5)', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let originalPathname = '/'

  beforeEach(() => {
    FakeSocket.role = 'host'
    FakeSocket.urls = []
    FakeSocket.joinFrames = []
    vi.stubGlobal('localStorage', makeStorage())
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', `/room/${ROOM_ID}`)
  })

  afterEach(async () => {
    if (root) {
      await act(async () => {
        root?.unmount()
      })
      root = null
    }
    vi.unstubAllGlobals()
    window.history.replaceState({}, '', originalPathname)
    container?.remove()
    container = null
  })

  async function joinAs(name: string) {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(
        <RoomPage
          wsOptions={{
            WebSocketClass: FakeSocket as unknown as typeof WebSocket,
            sessionId: SESSION_ID,
          }}
        />,
      )
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!
    await act(async () => {
      input.value = name
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })
    await act(async () => {
      await new Promise((r) => setTimeout(r, 45))
    })
  }

  it('shows the Host badge when the server says role is "host"', async () => {
    FakeSocket.role = 'host'
    await joinAs('Alice')

    expect(container?.querySelector('[data-testid="room-user-badge"]')?.textContent).toContain(
      'You: Alice',
    )
    const badge = container?.querySelector('[data-testid="room-host-badge"]')
    expect(badge).not.toBeNull()
    expect(badge?.textContent).toBe('Host')
  })

  it('shows no Host badge for a participant', async () => {
    FakeSocket.role = 'participant'
    await joinAs('Bob')

    expect(container?.querySelector('[data-testid="room-user-badge"]')).not.toBeNull()
    expect(container?.querySelector('[data-testid="room-host-badge"]')).toBeNull()
  })

  it('shows no Host badge for an observer', async () => {
    FakeSocket.role = 'observer'
    await joinAs('Carol')

    expect(container?.querySelector('[data-testid="room-host-badge"]')).toBeNull()
  })

  it('sends the stored owner token in the join frame and never in a URL', async () => {
    localStorage.setItem(ownerTokenKey(ROOM_ID), OWNER_TOKEN)
    await joinAs('Alice')

    expect(FakeSocket.joinFrames).toHaveLength(1)
    expect(FakeSocket.joinFrames[0].owner_token).toBe(OWNER_TOKEN)
    expect(FakeSocket.urls.length).toBeGreaterThan(0)
    for (const url of FakeSocket.urls) {
      expect(url).not.toContain(OWNER_TOKEN)
    }
  })

  it('sends no owner_token when none is stored (different browser or cleared site data)', async () => {
    FakeSocket.role = 'participant'
    await joinAs('Dave')

    expect(FakeSocket.joinFrames).toHaveLength(1)
    expect('owner_token' in FakeSocket.joinFrames[0]).toBe(false)
  })
})
