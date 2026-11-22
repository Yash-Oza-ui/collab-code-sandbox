// @vitest-environment happy-dom
/* eslint-disable @typescript-eslint/no-explicit-any */
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'
import { formatAuditEntry, type AuditLogEntry } from './auditLogApi'

vi.mock('@monaco-editor/react', async () => {
  const React = await import('react')
  return {
    default: ({ options, onMount }: any) => {
      React.useEffect(() => {
        onMount?.({
          getModel: () => ({}),
          updateOptions: vi.fn(),
          deltaDecorations: vi.fn().mockReturnValue([]),
        })
      }, [])
      return <div data-testid="mock-monaco" data-readonly={String(Boolean(options?.readOnly))} />
    },
  }
})

vi.mock('y-monaco', () => ({
  MonacoBinding: class {
    destroy() {}
  },
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

class FakeSocket {
  static instances: FakeSocket[] = []
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

  send() {}

  emitJson(payload: unknown) {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(payload) }))
  }

  close() {
    this.readyState = 3
    this.onclose?.(new CloseEvent('close', { code: 1000 }))
  }
}

const ROOM_ID = 'us58-test-room'
const SESSION_ID = '22222222-2222-4222-8222-222222222222'

describe('RoomPage US-58: Audit log of permission changes', () => {
  let container: HTMLDivElement
  let root: Root | null = null

  beforeEach(() => {
    FakeSocket.instances = []
    container = document.createElement('div')
    document.body.appendChild(container)
    window.history.pushState({}, '', `/room/${ROOM_ID}`)
    localStorage.clear()
    sessionStorage.clear()
  })

  afterEach(async () => {
    if (root) {
      await act(async () => {
        root?.unmount()
      })
      root = null
    }
    container.remove()
    vi.unstubAllGlobals()
  })

  function controlSocket(): FakeSocket | undefined {
    return FakeSocket.instances.find((ws) => !ws.url.includes('/docs/'))
  }

  function submitDisplayName(name: string) {
    const input = container.querySelector('input') as HTMLInputElement
    const button = container.querySelector('button') as HTMLButtonElement
    act(() => {
      input.value = name
      input.dispatchEvent(new Event('input', { bubbles: true }))
      button.click()
    })
  }

  async function renderAndJoin(role: 'host' | 'participant' | 'observer', fetchFn: typeof fetch) {
    root = createRoot(container)
    act(() => {
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

    submitDisplayName(role === 'observer' ? 'Bob' : 'Alice')
    await vi.waitFor(() => expect(controlSocket()).toBeTruthy())

    await act(async () => {
      controlSocket()?.emitJson({
        type: 'joined',
        member_id: 'm_me',
        role,
        mode: 'pair',
        preset: 'debug_together',
        permissions: { can_edit: role !== 'observer', can_run: role !== 'observer' },
      })
      controlSocket()?.emitJson({
        type: 'roster',
        members: [
          {
            member_id: 'm_me',
            display_name: role === 'observer' ? 'Bob' : 'Alice',
            role,
            permissions: { can_edit: role !== 'observer', can_run: role !== 'observer' },
          },
        ],
      })
    })
  }

  it('formats all permission and role change actions in human-friendly phrases', () => {
    const base: Omit<AuditLogEntry, 'id' | 'action' | 'property' | 'old_value' | 'new_value' | 'details'> = {
      timestamp: '2026-11-22T11:30:00Z',
      actor: 'Alice',
      actor_display_name: 'Alice',
      actor_member_id: 'm_alice',
      target: 'Bob',
      target_display_name: 'Bob',
      target_member_id: 'm_bob',
    }

    expect(
      formatAuditEntry({ ...base, id: '1', action: 'role_change', property: 'role', old_value: 'participant', new_value: 'observer', details: null }),
    ).toBe('Alice made Bob an Observer')
    expect(
      formatAuditEntry({ ...base, id: '2', action: 'edit_access', property: 'can_edit', old_value: false, new_value: true, details: null }),
    ).toBe('Alice gave Bob edit access')
    expect(
      formatAuditEntry({ ...base, id: '3', action: 'host_only_run', property: 'host_only_run', old_value: false, new_value: true, details: null }),
    ).toBe('Alice enabled Host-only run')
    expect(
      formatAuditEntry({ ...base, id: '4', action: 'host_transfer', target: 'Charlie', target_display_name: 'Charlie', property: 'role', old_value: 'participant', new_value: 'host', details: null }),
    ).toBe('Alice transferred Host to Charlie')
    expect(
      formatAuditEntry({ ...base, id: '5', action: 'module_write_access', property: 'writers', old_value: false, new_value: true, details: 'main.py' }),
    ).toBe('Alice granted Bob write access to module main.py')
  })

  it('fetches GET /audit-log with X-Session-Id and displays entries newest first for late joiners & observers', async () => {
    const fetchFn = vi.fn().mockImplementation(async (url: string) => {
      if (String(url).endsWith('/audit-log')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            room_id: ROOM_ID,
            entries: [
              {
                id: 'aud_1',
                timestamp: '2026-11-22T11:30:00Z',
                action: 'role_change',
                actor: 'Alice',
                actor_display_name: 'Alice',
                actor_member_id: 'm_alice',
                target: 'Bob',
                target_display_name: 'Bob',
                target_member_id: 'm_bob',
                property: 'role',
                old_value: 'participant',
                new_value: 'observer',
                details: null,
              },
              {
                id: 'aud_2',
                timestamp: '2026-11-22T11:35:00Z',
                action: 'edit_access',
                actor: 'Alice',
                actor_display_name: 'Alice',
                actor_member_id: 'm_alice',
                target: 'Charlie',
                target_display_name: 'Charlie',
                target_member_id: 'm_charlie',
                property: 'can_edit',
                old_value: false,
                new_value: true,
                details: null,
              },
            ],
          }),
        }
      }
      return { ok: true, status: 200, json: async () => ({}) }
    }) as unknown as typeof fetch

    await renderAndJoin('observer', fetchFn)

    const toggle = container.querySelector('[data-testid="audit-log-toggle"]') as HTMLButtonElement
    expect(toggle).not.toBeNull()

    await act(async () => {
      toggle.click()
    })

    expect(fetchFn).toHaveBeenCalledWith(
      expect.stringMatching(/\/v1\/rooms\/us58-test-room\/audit-log$/),
      expect.objectContaining({
        method: 'GET',
        headers: { 'X-Session-Id': SESSION_ID },
      }),
    )

    const items = Array.from(container.querySelectorAll('[data-testid="audit-log-list"] li'))
    expect(items).toHaveLength(2)
    expect(items[0].textContent).toContain('Alice gave Charlie edit access')
    expect(items[1].textContent).toContain('Alice made Bob an Observer')
    expect(container.textContent).not.toContain(SESSION_ID)
  })

  it('prepends live incoming audit-entry WebSocket frames without refresh', async () => {
    const fetchFn = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ room_id: ROOM_ID, entries: [] }),
    }) as unknown as typeof fetch

    await renderAndJoin('participant', fetchFn)

    await act(async () => {
      ;(container.querySelector('[data-testid="audit-log-toggle"]') as HTMLButtonElement).click()
    })
    expect(container.querySelector('[data-testid="audit-log-empty"]')).not.toBeNull()

    await act(async () => {
      controlSocket()?.emitJson({
        type: 'audit-entry',
        entry: {
          id: 'aud_live_1',
          timestamp: '2026-11-22T12:00:00Z',
          action: 'host_only_run',
          actor: 'Alice',
          actor_display_name: 'Alice',
          actor_member_id: 'm_alice',
          target: null,
          target_display_name: null,
          target_member_id: null,
          property: 'host_only_run',
          old_value: false,
          new_value: true,
          details: null,
        },
      })
    })

    const item = container.querySelector('[data-testid="audit-entry-aud_live_1"]')
    expect(item?.textContent).toContain('Alice enabled Host-only run')
  })
})
