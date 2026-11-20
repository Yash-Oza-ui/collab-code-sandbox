// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'
import { ownerTokenKey } from './createRoom'

vi.mock('@monaco-editor/react', () => ({
  default: () => <div data-testid="mock-monaco-editor" />,
}))

vi.mock('y-monaco', () => ({
  MonacoBinding: class {
    destroy() {}
  },
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const ROOM_ID = 'test-room-workspace'
const OWNER_TOKEN = 'secret-owner-token'

class MockWs {
  static role = 'host'
  static mode = 'open'
  static language = 'python'
  static preset: string | null = null
  static instances: MockWs[] = []
  readyState = 0
  binaryType = 'arraybuffer'
  url: string
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onclose: ((e: CloseEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null

  constructor(url: string) {
    this.url = url
    MockWs.instances.push(this)
    queueMicrotask(() => {
      this.readyState = 1
      this.onopen?.(new Event('open'))
    })
  }

  send(data: unknown) {
    if (typeof data !== 'string') return
    const msg = JSON.parse(data) as Record<string, unknown>
    if (msg.type !== 'join') return

    setTimeout(() => {
      this.onmessage?.(
        new MessageEvent('message', {
          data: JSON.stringify({
            type: 'joined',
            member_id: 'm_host',
            role: MockWs.role,
            mode: MockWs.mode,
            preset: MockWs.preset,
            language: MockWs.language,
            permissions: { can_run: true },
          }),
        }),
      )

      this.onmessage?.(
        new MessageEvent('message', {
          data: JSON.stringify({
            type: 'roster',
            members: [{ member_id: 'm_host', display_name: 'Host User', role: MockWs.role }],
          }),
        }),
      )
    }, 10)
  }

  close() {
    this.readyState = 3
    this.onclose?.(new CloseEvent('close', { code: 1000 }))
  }
}

describe('RoomPage Workspace integration (US-39)', () => {
  let container: HTMLDivElement

  beforeEach(() => {
    container = document.createElement('div')
    document.body.appendChild(container)
    MockWs.instances = []
    window.history.pushState({}, '', `/room/${ROOM_ID}`)
    localStorage.setItem(ownerTokenKey(ROOM_ID), OWNER_TOKEN)
  })

  afterEach(() => {
    document.body.removeChild(container)
    localStorage.clear()
    sessionStorage.clear()
  })

  it('does NOT render workspace panel in Pair mode', async () => {
    MockWs.mode = 'pair'
    MockWs.preset = 'debug_together'

    const root = createRoot(container)
    act(() => {
      root.render(
        <RoomPage
          wsOptions={{
            WebSocketClass: MockWs as unknown as typeof WebSocket,
          }}
        />,
      )
    })

    // Submit display name
    const input = container.querySelector('input') as HTMLInputElement
    const button = container.querySelector('button') as HTMLButtonElement
    act(() => {
      input.value = 'Alice'
      input.dispatchEvent(new Event('input', { bubbles: true }))
      button.click()
    })

    await vi.waitFor(() => {
      expect(container.querySelector('[data-testid="room-mode-badge"]')).not.toBeNull()
    })

    expect(container.querySelector('[data-testid="workspace-panel"]')).toBeNull()

    act(() => {
      root.unmount()
    })
  })

  it('renders workspace panel in Open mode and populates module list', async () => {
    MockWs.mode = 'open'
    MockWs.language = 'python'

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/modules')) {
        return {
          ok: true,
          status: 200,
          json: async () => [
            { module_id: 'mod_alpha', name: 'alpha.py', owner_member_id: 'm_host', is_entry: true, pending: false },
          ],
        }
      }
      return { ok: true, status: 200, json: async () => ({}) }
    })

    const root = createRoot(container)
    act(() => {
      root.render(
        <RoomPage
          wsOptions={{
            WebSocketClass: MockWs as unknown as typeof WebSocket,
            fetchFn: fetchMock as unknown as typeof fetch,
          }}
        />,
      )
    })

    const input = container.querySelector('input') as HTMLInputElement
    const button = container.querySelector('button') as HTMLButtonElement
    act(() => {
      input.value = 'Host User'
      input.dispatchEvent(new Event('input', { bubbles: true }))
      button.click()
    })

    await vi.waitFor(() => {
      expect(container.querySelector('[data-testid="workspace-panel"]')).not.toBeNull()
      expect(container.querySelector('[data-testid="module-mod_alpha"]')).not.toBeNull()
    })

    const langBadge = container.querySelector('[data-testid="workspace-language"]')
    expect(langBadge?.textContent).toBe('python')

    act(() => {
      root.unmount()
    })
  })

  it('updates module list on module-updated push and lets Host create module', async () => {
    MockWs.mode = 'open'
    MockWs.language = 'python'

    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/modules')) {
        if (init?.method === 'POST') {
          return {
            ok: true,
            status: 201,
            json: async () => ({
              module_id: 'mod_beta',
              name: 'beta.py',
              owner_member_id: 'm_host',
              is_entry: false,
              pending: false,
            }),
          }
        }
        return {
          ok: true,
          status: 200,
          json: async () => [
            { module_id: 'mod_alpha', name: 'alpha.py', owner_member_id: 'm_host', is_entry: true, pending: false },
          ],
        }
      }
      return { ok: true, status: 200, json: async () => ({}) }
    })

    const root = createRoot(container)
    act(() => {
      root.render(
        <RoomPage
          wsOptions={{
            WebSocketClass: MockWs as unknown as typeof WebSocket,
            fetchFn: fetchMock as unknown as typeof fetch,
          }}
        />,
      )
    })

    const nameInput = container.querySelector('input') as HTMLInputElement
    const joinBtn = container.querySelector('button') as HTMLButtonElement
    act(() => {
      nameInput.value = 'Host User'
      nameInput.dispatchEvent(new Event('input', { bubbles: true }))
      joinBtn.click()
    })

    await vi.waitFor(() => {
      expect(container.querySelector('[data-testid="module-mod_alpha"]')).not.toBeNull()
    })

    // Create module via form
    const newModInput = container.querySelector('[data-testid="new-module-name-input"]') as HTMLInputElement
    const form = container.querySelector('[data-testid="create-module-form"]') as HTMLFormElement
    act(() => {
      newModInput.value = 'beta.py'
      newModInput.dispatchEvent(new Event('input', { bubbles: true }))
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await vi.waitFor(() => {
      expect(container.querySelector('[data-testid="module-mod_beta"]')).not.toBeNull()
    })

    // Now simulate incoming module-updated push on control socket
    const controlWs = MockWs.instances[0]
    act(() => {
      controlWs.onmessage?.(
        new MessageEvent('message', {
          data: JSON.stringify({
            type: 'module-updated',
            module_id: 'mod_gamma',
            name: 'gamma.py',
            owner_member_id: 'm_2',
            runnable: true,
            is_entry: false,
            version: 1,
            pending: false,
            deleted: false,
          }),
        }),
      )
    })

    await vi.waitFor(() => {
      expect(container.querySelector('[data-testid="module-mod_gamma"]')).not.toBeNull()
    })

    // Simulate module deletion
    act(() => {
      controlWs.onmessage?.(
        new MessageEvent('message', {
          data: JSON.stringify({
            type: 'module-updated',
            module_id: 'mod_gamma',
            name: 'gamma.py',
            owner_member_id: 'm_2',
            deleted: true,
          }),
        }),
      )
    })

    await vi.waitFor(() => {
      expect(container.querySelector('[data-testid="module-mod_gamma"]')).toBeNull()
    })

    act(() => {
      root.unmount()
    })
  })
})
