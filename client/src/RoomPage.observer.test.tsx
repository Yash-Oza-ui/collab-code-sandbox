// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'

// US-27 (FR9.4): Observer view in RoomPage.
// - Observer badge is displayed when server assigns observer role.
// - Monaco Editor is set to read-only (both initially and dynamically on roster update).
// - RunPanel Run button is disabled with tooltip "Observers cannot run code".
// - Receiving an edit-rejected frame with reason "observer" shows the error banner.
// - Remote presence and cursors remain visible.

const monacoUpdateOptionsSpy = vi.fn()

vi.mock('@monaco-editor/react', () => ({
  default: ({ options, onMount }: { options?: { readOnly?: boolean }; onMount?: (editor: unknown) => void }) => {
    return (
      <div
        data-testid="mock-monaco-editor"
        data-readonly={String(options?.readOnly ?? false)}
        ref={(el) => {
          if (el && onMount) {
            onMount({
              updateOptions: (opts: { readOnly?: boolean }) => {
                monacoUpdateOptionsSpy(opts)
                if (typeof opts?.readOnly === 'boolean') {
                  el.setAttribute('data-readonly', String(opts.readOnly))
                }
              },
              getModel: () => null,
            })
          }
        }}
      />
    )
  },
}))

vi.mock('y-monaco', () => ({
  MonacoBinding: class {
    destroy() {}
  },
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const ROOM_ID = 'room-observer-test'
const SESSION_ID = '30000000-0000-4000-8000-000000000003'

class FakeSocket {
  static instances: FakeSocket[] = []
  static role = 'participant'
  static memberId = 'member-observer-1'

  readyState = 0
  binaryType = 'arraybuffer'
  url: string
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onclose: ((e: CloseEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeSocket.instances.push(this)
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
      this.receive({
        type: 'joined',
        member_id: FakeSocket.memberId,
        role: FakeSocket.role,
        mode: 'pair',
        preset: 'debug_together',
        permissions: {
          can_edit: FakeSocket.role !== 'observer',
          can_run: FakeSocket.role !== 'observer',
        },
      })
    }, 0)
  }

  receive(data: Record<string, unknown>) {
    this.onmessage?.(
      new MessageEvent('message', {
        data: JSON.stringify(data),
      }),
    )
  }

  close() {
    this.readyState = 3
  }
}

describe('RoomPage Observer View (US-27, FR9.4)', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let originalPathname = '/'

  beforeEach(() => {
    sessionStorage.clear() // Each test starts without a previously joined browser tab.
    FakeSocket.instances = []
    FakeSocket.role = 'participant'
    FakeSocket.memberId = 'member-observer-1'
    monacoUpdateOptionsSpy.mockClear()
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

  it('renders Observer badge when server responds with role "observer" on join', async () => {
    FakeSocket.role = 'observer'
    await joinAs('Carol')

    expect(container?.querySelector('[data-testid="room-user-badge"]')?.textContent).toContain('You: Carol')

    const observerBadge = container?.querySelector('[data-testid="room-observer-badge"]')
    expect(observerBadge).not.toBeNull()
    expect(observerBadge?.textContent).toBe('Observer')

    const hostBadge = container?.querySelector('[data-testid="room-host-badge"]')
    expect(hostBadge).toBeNull()
  })

  it('locks Monaco Editor in readOnly mode when user is an observer', async () => {
    FakeSocket.role = 'observer'
    await joinAs('Carol')

    const editorEl = container?.querySelector('[data-testid="mock-monaco-editor"]')
    expect(editorEl).not.toBeNull()
    expect(editorEl?.getAttribute('data-readonly')).toBe('true')
  })

  it('disables Run button with "Observers cannot run code" tooltip when user is an observer', async () => {
    FakeSocket.role = 'observer'
    await joinAs('Carol')

    const runBtn = container?.querySelector<HTMLButtonElement>('[data-testid="run-button"]')
    expect(runBtn).not.toBeNull()
    expect(runBtn?.disabled).toBe(true)
    expect(runBtn?.title).toBe('Observers cannot run code')
  })

  it('allows editing and running when user is a regular participant', async () => {
    FakeSocket.role = 'participant'
    await joinAs('Bob')

    const observerBadge = container?.querySelector('[data-testid="room-observer-badge"]')
    expect(observerBadge).toBeNull()

    const editorEl = container?.querySelector('[data-testid="mock-monaco-editor"]')
    expect(editorEl?.getAttribute('data-readonly')).toBe('false')

    const runBtn = container?.querySelector<HTMLButtonElement>('[data-testid="run-button"]')
    expect(runBtn?.disabled).toBe(false)
    expect(runBtn?.title).not.toBe('Observers cannot run code')
  })

  it('dynamically switches to observer view when server broadcasts roster update', async () => {
    FakeSocket.role = 'participant'
    await joinAs('Dave')

    // Initially participant: editor editable, run button enabled
    let runBtn = container!.querySelector<HTMLButtonElement>('[data-testid="run-button"]')!
    expect(runBtn.disabled).toBe(false)
    expect(container?.querySelector('[data-testid="room-observer-badge"]')).toBeNull()

    // Host demotes Dave to Observer via PATCH, server pushes roster frame
    const controlSocket = FakeSocket.instances.find((s) => s.url.includes(`/ws/rooms/${ROOM_ID}`))
    expect(controlSocket).toBeDefined()

    await act(async () => {
      controlSocket?.receive({
        type: 'roster',
        members: [
          {
            member_id: FakeSocket.memberId,
            display_name: 'Dave',
            role: 'observer',
            permissions: { can_edit: false, can_run: false },
          },
        ],
      })
    })

    // Now Dave is dynamically an observer!
    const observerBadge = container?.querySelector('[data-testid="room-observer-badge"]')
    expect(observerBadge).not.toBeNull()
    expect(observerBadge?.textContent).toBe('Observer')

    const editorEl = container?.querySelector('[data-testid="mock-monaco-editor"]')
    expect(editorEl?.getAttribute('data-readonly')).toBe('true')

    runBtn = container!.querySelector<HTMLButtonElement>('[data-testid="run-button"]')!
    expect(runBtn.disabled).toBe(true)
    expect(runBtn.title).toBe('Observers cannot run code')
  })

  it('displays "Observers cannot edit in this room." error banner on edit-rejected frame', async () => {
    FakeSocket.role = 'observer'
    await joinAs('Eve')

    const controlSocket = FakeSocket.instances.find((s) => s.url.includes(`/ws/rooms/${ROOM_ID}`))
    expect(controlSocket).toBeDefined()

    await act(async () => {
      controlSocket?.receive({
        type: 'edit-rejected',
        reason: 'observer',
      })
    })

    const errorAlert = container?.querySelector('[data-testid="room-doc-error"]')
    expect(errorAlert).not.toBeNull()
    expect(errorAlert?.getAttribute('role')).toBe('alert')
    expect(errorAlert?.textContent).toContain('Observers cannot edit in this room.')
  })

  it('preserves user presence dot and header info in observer mode', async () => {
    FakeSocket.role = 'observer'
    await joinAs('Frank')

    expect(container?.querySelector('[data-testid="room-user-badge"]')?.textContent).toContain('You: Frank')
    const colorDot = container?.querySelector('[data-testid="room-user-color-dot"]')
    expect(colorDot).not.toBeNull()
  })
})
