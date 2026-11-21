// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'

// US-37 (FR19.2, EPIC 5): Pair mode room presets (Teach/Interview and Debug together).
// - Preset label is rendered ("Teach / Interview" or "Debug together").
// - Clear "You can edit" / "Read only" viewer indicator is rendered from server permissions.
// - Teach/Interview participant starts as "Read only".
// - Debug together participant starts as "You can edit".
// - Host starts as "You can edit" in either preset.
// - Observer starts as "Read only" in either preset.
// - Roster update that grants or revokes access flips the indicator dynamically.
// - Open mode displays no preset indicator or Pair edit status indicator.

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

vi.mock('y-monaco', () => ({ MonacoBinding: class { destroy() {} } }))
;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const ROOM_ID = 'room-pair-preset-test'
const SESSION_ID = '70000000-0000-4000-8000-000000000007'

class FakeSocket {
  static instances: FakeSocket[] = []
  static role = 'participant'
  static memberId = 'm_me'
  static mode = 'pair'
  static preset: string | null = 'teach_interview'
  static initialCanEdit = false
  static aiEnabled = false

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
    const msg = JSON.parse(data) as { type?: string }
    if (msg.type !== 'join') return
    setTimeout(() => {
      this.receive({
        type: 'joined',
        member_id: FakeSocket.memberId,
        role: FakeSocket.role,
        mode: FakeSocket.mode,
        preset: FakeSocket.preset,
        ai_enabled: FakeSocket.aiEnabled,
        permissions: {
          can_edit: FakeSocket.role === 'host' ? true : FakeSocket.initialCanEdit,
          can_run: true,
          run_denied_reason: null,
        },
      })
    }, 0)
  }

  receive(data: Record<string, unknown>) {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(data) }))
  }

  close() {
    this.readyState = 3
  }
}

describe('RoomPage pair presets and edit indicators (US-37, FR19.2)', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let originalPathname = '/'

  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    FakeSocket.instances = []
    FakeSocket.role = 'participant'
    FakeSocket.memberId = 'm_me'
    FakeSocket.mode = 'pair'
    FakeSocket.preset = 'teach_interview'
    FakeSocket.initialCanEdit = false
    FakeSocket.aiEnabled = false
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
    container?.remove()
    container = null
    window.history.replaceState({}, '', originalPathname)
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

  it('Teach/Interview participant sees "Teach / Interview", "Read only" indicator, and read-only Monaco editor', async () => {
    FakeSocket.role = 'participant'
    FakeSocket.preset = 'teach_interview'
    FakeSocket.initialCanEdit = false
    await joinAs('Bob')

    const presetBadge = container?.querySelector('[data-testid="room-preset-badge"]')
    expect(presetBadge?.textContent).toBe('Teach / Interview')

    const editStatus = container?.querySelector('[data-testid="room-edit-status-badge"]')
    expect(editStatus?.textContent).toBe('Read only')

    const editorEl = container?.querySelector('[data-testid="mock-monaco-editor"]')
    expect(editorEl?.getAttribute('data-readonly')).toBe('true')
  })

  it('Debug together participant sees "Debug together", "You can edit" indicator, and editable Monaco editor', async () => {
    FakeSocket.role = 'participant'
    FakeSocket.preset = 'debug_together'
    FakeSocket.initialCanEdit = true
    await joinAs('Charlie')

    const presetBadge = container?.querySelector('[data-testid="room-preset-badge"]')
    expect(presetBadge?.textContent).toBe('Debug together')

    const editStatus = container?.querySelector('[data-testid="room-edit-status-badge"]')
    expect(editStatus?.textContent).toBe('You can edit')

    const editorEl = container?.querySelector('[data-testid="mock-monaco-editor"]')
    expect(editorEl?.getAttribute('data-readonly')).toBe('false')
  })

  it('Host sees "You can edit" in Teach/Interview preset', async () => {
    FakeSocket.role = 'host'
    FakeSocket.preset = 'teach_interview'
    await joinAs('Alice')

    const presetBadge = container?.querySelector('[data-testid="room-preset-badge"]')
    expect(presetBadge?.textContent).toBe('Teach / Interview')

    const editStatus = container?.querySelector('[data-testid="room-edit-status-badge"]')
    expect(editStatus?.textContent).toBe('You can edit')

    const editorEl = container?.querySelector('[data-testid="mock-monaco-editor"]')
    expect(editorEl?.getAttribute('data-readonly')).toBe('false')
  })

  it('Observer sees "Read only" in either preset', async () => {
    FakeSocket.role = 'observer'
    FakeSocket.preset = 'debug_together'
    FakeSocket.initialCanEdit = false
    await joinAs('Dave')

    const presetBadge = container?.querySelector('[data-testid="room-preset-badge"]')
    expect(presetBadge?.textContent).toBe('Debug together')

    const editStatus = container?.querySelector('[data-testid="room-edit-status-badge"]')
    expect(editStatus?.textContent).toBe('Read only')

    const editorEl = container?.querySelector('[data-testid="mock-monaco-editor"]')
    expect(editorEl?.getAttribute('data-readonly')).toBe('true')
  })

  it('roster update that grants or revokes access flips the indicator without a refresh', async () => {
    FakeSocket.role = 'participant'
    FakeSocket.preset = 'teach_interview'
    FakeSocket.initialCanEdit = false
    await joinAs('Elena')

    // Starts read-only
    let editStatus = container?.querySelector('[data-testid="room-edit-status-badge"]')
    expect(editStatus?.textContent).toBe('Read only')

    // Host grants edit access via roster
    await act(async () => {
      FakeSocket.instances[0].receive({
        type: 'roster',
        members: [
          {
            member_id: 'm_host',
            display_name: 'Host',
            role: 'host',
            permissions: { can_edit: true, can_run: true },
          },
          {
            member_id: 'm_me',
            display_name: 'Elena',
            role: 'participant',
            permissions: { can_edit: true, can_run: true },
          },
        ],
      })
    })

    editStatus = container?.querySelector('[data-testid="room-edit-status-badge"]')
    expect(editStatus?.textContent).toBe('You can edit')

    // Host revokes edit access via roster
    await act(async () => {
      FakeSocket.instances[0].receive({
        type: 'roster',
        members: [
          {
            member_id: 'm_host',
            display_name: 'Host',
            role: 'host',
            permissions: { can_edit: true, can_run: true },
          },
          {
            member_id: 'm_me',
            display_name: 'Elena',
            role: 'participant',
            permissions: { can_edit: false, can_run: true },
          },
        ],
      })
    })

    editStatus = container?.querySelector('[data-testid="room-edit-status-badge"]')
    expect(editStatus?.textContent).toBe('Read only')
  })

  it('Open mode shows no preset indicator or pair edit status badge', async () => {
    FakeSocket.role = 'participant'
    FakeSocket.mode = 'open'
    FakeSocket.preset = null
    await joinAs('Frank')

    expect(container?.querySelector('[data-testid="room-preset-badge"]')).toBeNull()
    expect(container?.querySelector('[data-testid="room-edit-status-badge"]')).toBeNull()
  })
})


describe('US-59 room AI switch indicators', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let priorPath: string
  beforeEach(() => {
    priorPath = window.location.pathname
    window.history.replaceState({}, '', '/room/room-pair-preset-test')
    FakeSocket.instances = []
    FakeSocket.role = 'participant'
    FakeSocket.aiEnabled = false
    FakeSocket.preset = 'teach_interview'
    sessionStorage.clear()
    localStorage.clear()
  })
  afterEach(async () => {
    if (root) await act(async () => root?.unmount())
    container?.remove()
    root = null
    container = null
    window.history.replaceState({}, '', priorPath)
  })
  it('shows a guest the server value and updates on a room-state message without reload', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => root?.render(<RoomPage wsOptions={{ WebSocketClass: FakeSocket as unknown as typeof WebSocket, sessionId: SESSION_ID }} />))
    const input = container.querySelector<HTMLInputElement>('#display-name')!
    await act(async () => {
      input.value = 'Guest'
      container!.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 45)) })
    expect(container.querySelector('[data-testid="room-ai-status"]')?.textContent).toBe('AI off')
    expect(container.querySelector('[data-testid="host-ai-switch-toggle"]')).toBeNull()
    await act(async () => FakeSocket.instances[0].receive({ type: 'room-state', ai_enabled: true }))
    expect(container.querySelector('[data-testid="room-ai-status"]')?.textContent).toBe('AI on')
  })
})
