// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'
import { ownerTokenKey } from './createRoom'
import { config } from './config'

// US-36 (FR19.1, V2.21): Give and take back edit access in Pair mode.
// - Host has a toggle switch/button in the participant list for each participant.
// - Observers, Host, and Open mode rooms do not have the switch.
// - Guest's Monaco editor dynamically switches between read-only and editable on roster update.

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

const ROOM_ID = 'room-edit-access-test'
const SESSION_ID = '70000000-0000-4000-8000-000000000007'
const OWNER_TOKEN = 'owner-secret-edit-access'
const BASE = `${config.platformApiUrl}/v1/rooms/${ROOM_ID}`

class FakeSocket {
  static instances: FakeSocket[] = []
  static role = 'participant'
  static memberId = 'm_me'
  static mode = 'pair'
  static preset = 'teach_interview'
  static initialCanEdit = false

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

describe('RoomPage edit access controls (US-36, V2.21)', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let originalPathname = '/'
  let fetchFn: ReturnType<typeof vi.fn>

  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    FakeSocket.instances = []
    FakeSocket.role = 'participant'
    FakeSocket.memberId = 'm_me'
    FakeSocket.mode = 'pair'
    FakeSocket.preset = 'teach_interview'
    FakeSocket.initialCanEdit = false
    monacoUpdateOptionsSpy.mockClear()
    fetchFn = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body })
      if (method === 'PATCH' && url === `${BASE}/members/m_lin/edit-access`) {
        const body = JSON.parse(String(init?.body)) as { can_edit: boolean }
        return ok({
          member_id: 'm_lin',
          display_name: 'Lin',
          role: 'participant',
          permissions: { can_edit: body.can_edit, can_run: true, run_denied_reason: null },
        })
      }
      return { ok: false, status: 404, json: async () => ({ error: { code: 'NOT_FOUND', message: 'x' } }) }
    })
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
            fetchFn,
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

  it('guest editor starts read-only in teach_interview Pair room, switches dynamically on grant/revoke', async () => {
    FakeSocket.role = 'participant'
    FakeSocket.initialCanEdit = false
    await joinAs('Lin')

    const editorEl = container?.querySelector('[data-testid="mock-monaco-editor"]')
    expect(editorEl?.getAttribute('data-readonly')).toBe('true')

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
            display_name: 'Lin',
            role: 'participant',
            permissions: { can_edit: true, can_run: true },
          },
        ],
      })
    })

    expect(editorEl?.getAttribute('data-readonly')).toBe('false')

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
            display_name: 'Lin',
            role: 'participant',
            permissions: { can_edit: false, can_run: true },
          },
        ],
      })
    })

    expect(editorEl?.getAttribute('data-readonly')).toBe('true')
  })

  it('Host in Pair mode sees toggle button for participant and can grant/revoke edit access', async () => {
    localStorage.setItem(ownerTokenKey(ROOM_ID), OWNER_TOKEN)
    FakeSocket.role = 'host'
    FakeSocket.memberId = 'm_host'
    await joinAs('Host')

    // Send roster with participant Lin without edit access
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
            member_id: 'm_lin',
            display_name: 'Lin',
            role: 'participant',
            permissions: { can_edit: false, can_run: true },
          },
          {
            member_id: 'm_obs',
            display_name: 'Observer',
            role: 'observer',
            permissions: { can_edit: false, can_run: false },
          },
        ],
      })
    })

    // Lin has toggle button showing "Grant Edit"
    const toggleLin = container?.querySelector<HTMLButtonElement>('[data-testid="toggle-edit-access-m_lin"]')
    expect(toggleLin).not.toBeNull()
    expect(toggleLin?.textContent).toContain('Grant Edit')

    // Host and Observer do not have toggle-edit-access buttons
    expect(container?.querySelector('[data-testid="toggle-edit-access-m_host"]')).toBeNull()
    expect(container?.querySelector('[data-testid="toggle-edit-access-m_obs"]')).toBeNull()

    // Click "Grant Edit"
    await act(async () => {
      toggleLin?.click()
    })

    expect(fetchFn).toHaveBeenCalledWith(
      `${BASE}/members/m_lin/edit-access`,
      expect.objectContaining({
        method: 'PATCH',
        headers: expect.objectContaining({
          'X-Session-Id': SESSION_ID,
          'X-Owner-Token': OWNER_TOKEN,
          'Content-Type': 'application/json',
        }),
        body: JSON.stringify({ can_edit: true }),
      }),
    )

    // Receive updated roster where Lin now has can_edit: true
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
            member_id: 'm_lin',
            display_name: 'Lin',
            role: 'participant',
            permissions: { can_edit: true, can_run: true },
          },
        ],
      })
    })

    const updatedToggleLin = container?.querySelector<HTMLButtonElement>('[data-testid="toggle-edit-access-m_lin"]')
    expect(updatedToggleLin?.textContent).toContain('Revoke Edit')

    // Click "Revoke Edit"
    await act(async () => {
      updatedToggleLin?.click()
    })

    expect(fetchFn).toHaveBeenCalledWith(
      `${BASE}/members/m_lin/edit-access`,
      expect.objectContaining({
        method: 'PATCH',
        headers: expect.objectContaining({
          'X-Session-Id': SESSION_ID,
          'X-Owner-Token': OWNER_TOKEN,
          'Content-Type': 'application/json',
        }),
        body: JSON.stringify({ can_edit: false }),
      }),
    )
  })

  it('Open mode room does not display edit access toggle', async () => {
    localStorage.setItem(ownerTokenKey(ROOM_ID), OWNER_TOKEN)
    FakeSocket.role = 'host'
    FakeSocket.memberId = 'm_host'
    FakeSocket.mode = 'open'
    await joinAs('Host')

    await act(async () => {
      FakeSocket.instances[0].receive({
        type: 'roster',
        members: [
          {
            member_id: 'm_host',
            display_name: 'Host',
            role: 'host',
            permissions: { can_run: true },
          },
          {
            member_id: 'm_lin',
            display_name: 'Lin',
            role: 'participant',
            permissions: { can_run: true },
          },
        ],
      })
    })

    expect(container?.querySelector('[data-testid="toggle-edit-access-m_lin"]')).toBeNull()
  })
})
