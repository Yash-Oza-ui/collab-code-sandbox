// @vitest-environment happy-dom
/* eslint-disable @typescript-eslint/no-explicit-any */
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'
import { ownerTokenKey } from './createRoom'

vi.mock('@monaco-editor/react', async () => {
  const React = await import('react')
  return {
    default: function MockEditor({ options, onMount }: any) {
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
  sentJson: Array<Record<string, unknown>> = []
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

  send(data: string | ArrayBuffer | Uint8Array) {
    if (typeof data !== 'string') return
    this.sentJson.push(JSON.parse(data))
  }

  emitJson(payload: unknown) {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(payload) }))
  }

  close() {
    this.readyState = 3
    this.onclose?.(new CloseEvent('close', { code: 1000 }))
  }
}

const ROOM_ID = 'us38-test-room'
const SESSION_ID = '11111111-1111-4111-8111-111111111111'
const OWNER_TOKEN = 'host-owner-token'

describe('RoomPage US-38: Request edit access in Pair mode', () => {
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

  async function renderAndJoin(options: {
    mode: 'pair' | 'open'
    role: 'host' | 'participant' | 'observer'
    canEdit: boolean
    fetchFn?: typeof fetch
  }) {
    const fetchFn =
      options.fetchFn ??
      (vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => [],
      }) as unknown as typeof fetch)

    if (options.role === 'host') {
      localStorage.setItem(ownerTokenKey(ROOM_ID), OWNER_TOKEN)
    }

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

    submitDisplayName(options.role === 'host' ? 'Ada' : 'Bob')

    await vi.waitFor(() => {
      expect(controlSocket()).toBeTruthy()
    })

    await act(async () => {
      controlSocket()?.emitJson({
        type: 'joined',
        member_id: 'm_me',
        role: options.role,
        mode: options.mode,
        preset: options.mode === 'pair' ? 'teach_interview' : null,
        permissions: { can_edit: options.canEdit, can_run: true },
      })
      controlSocket()?.emitJson({
        type: 'roster',
        members: [
          {
            member_id: 'm_host',
            display_name: 'Ada',
            role: 'host',
            permissions: { can_edit: true, can_run: true },
          },
          {
            member_id: 'm_me',
            display_name: options.role === 'host' ? 'Ada' : 'Bob',
            role: options.role,
            permissions: { can_edit: options.canEdit, can_run: true },
          },
        ],
      })
    })
  }

  it('shows Request edit access only for a Pair-mode participant without edit access', async () => {
    await renderAndJoin({ mode: 'pair', role: 'participant', canEdit: false })
    expect(container.querySelector('[data-testid="request-edit-access-button"]')).not.toBeNull()
  })

  it('hides Request edit access for Host, participant with edit access, Observer, and Open mode', async () => {
    await renderAndJoin({ mode: 'pair', role: 'host', canEdit: true })
    expect(container.querySelector('[data-testid="request-edit-access-button"]')).toBeNull()
    await act(async () => root?.unmount())
    root = null

    await renderAndJoin({ mode: 'pair', role: 'participant', canEdit: true })
    expect(container.querySelector('[data-testid="request-edit-access-button"]')).toBeNull()
    await act(async () => root?.unmount())
    root = null

    await renderAndJoin({ mode: 'pair', role: 'observer', canEdit: false })
    expect(container.querySelector('[data-testid="request-edit-access-button"]')).toBeNull()
    await act(async () => root?.unmount())
    root = null

    await renderAndJoin({ mode: 'open', role: 'participant', canEdit: false })
    expect(container.querySelector('[data-testid="request-edit-access-button"]')).toBeNull()
    expect(container.querySelector('[data-testid="edit-access-requests-panel"]')).toBeNull()
  })

  it('switches to pending state and prevents duplicate request-edit-access frames', async () => {
    await renderAndJoin({ mode: 'pair', role: 'participant', canEdit: false })
    const btn = container.querySelector('[data-testid="request-edit-access-button"]') as HTMLButtonElement

    act(() => {
      btn.click()
      btn.click()
    })

    const sentRequests = controlSocket()?.sentJson.filter((m) => m.type === 'request-edit-access') ?? []
    expect(sentRequests).toHaveLength(1)
    expect(btn.disabled).toBe(true)
    expect(btn.textContent).toBe('Request sent, waiting for the Host')
  })

  it('updates requester UI on approve and decline responses', async () => {
    await renderAndJoin({ mode: 'pair', role: 'participant', canEdit: false })
    const btn = container.querySelector('[data-testid="request-edit-access-button"]') as HTMLButtonElement

    act(() => {
      btn.click()
    })

    await act(async () => {
      controlSocket()?.emitJson({
        type: 'edit-access-responded',
        member_id: 'm_me',
        approved: false,
        message: 'The Host declined your edit access request.',
      })
    })

    expect(container.querySelector('[data-testid="edit-request-status"]')?.textContent).toBe(
      'The Host declined your edit access request.',
    )
    expect(btn.disabled).toBe(false)
    expect(btn.textContent).toBe('Request edit access')

    act(() => {
      btn.click()
    })

    await act(async () => {
      controlSocket()?.emitJson({
        type: 'edit-access-responded',
        member_id: 'm_me',
        approved: true,
        message: 'The Host approved your request',
      })
      controlSocket()?.emitJson({
        type: 'roster',
        members: [
          {
            member_id: 'm_me',
            display_name: 'Bob',
            role: 'participant',
            permissions: { can_edit: true, can_run: true },
          },
        ],
      })
    })

    expect(container.querySelector('[data-testid="edit-request-status"]')?.textContent).toBe(
      'The Host approved your request',
    )
    expect(container.querySelector('[data-testid="mock-monaco"]')?.getAttribute('data-readonly')).toBe('false')
    expect(container.querySelector('[data-testid="request-edit-access-button"]')).toBeNull()
  })

  it('Host panel fetches pending requests, approves/declines with Host headers, and clears on edit-access-request-cleared', async () => {
    const fetchFn = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith('/edit-requests') && (!init?.method || init.method === 'GET')) {
        return {
          ok: true,
          status: 200,
          json: async () => [{ member_id: 'm_bob', display_name: 'Bob', requested_at: '2026-11-22T10:00:00Z' }],
        }
      }
      if (String(url).endsWith('/edit-requests/m_bob/approve')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            member_id: 'm_bob',
            display_name: 'Bob',
            role: 'participant',
            permissions: { can_edit: true, can_run: true },
          }),
        }
      }
      if (String(url).endsWith('/edit-requests/m_carol/decline')) {
        return {
          ok: false,
          status: 404,
          json: async () => ({ error: { code: 'MEMBER_NOT_FOUND', message: 'Member not found in this room.' } }),
        }
      }
      return { ok: true, status: 200, json: async () => ({}) }
    }) as unknown as typeof fetch

    await renderAndJoin({ mode: 'pair', role: 'host', canEdit: true, fetchFn })

    await vi.waitFor(() => {
      expect(container.querySelector('[data-testid="edit-request-m_bob"]')?.textContent).toContain('Bob')
    })

    await act(async () => {
      ;(container.querySelector('[data-testid="approve-edit-request-m_bob"]') as HTMLButtonElement).click()
    })

    expect(fetchFn).toHaveBeenCalledWith(
      expect.stringMatching(/\/v1\/rooms\/us38-test-room\/edit-requests\/m_bob\/approve$/),
      expect.objectContaining({
        method: 'POST',
        headers: { 'X-Session-Id': SESSION_ID, 'X-Owner-Token': OWNER_TOKEN },
      }),
    )
    expect(container.querySelector('[data-testid="edit-request-m_bob"]')).toBeNull()

    await act(async () => {
      controlSocket()?.emitJson({
        type: 'edit-access-requested',
        member_id: 'm_carol',
        display_name: 'Carol',
        requested_at: '2026-11-22T10:05:00Z',
      })
    })
    expect(container.querySelector('[data-testid="edit-request-m_carol"]')).not.toBeNull()

    await act(async () => {
      ;(container.querySelector('[data-testid="decline-edit-request-m_carol"]') as HTMLButtonElement).click()
    })
    expect(container.querySelector('[data-testid="edit-request-error"]')?.textContent).toBe(
      'That person is no longer in the room.',
    )

    await act(async () => {
      controlSocket()?.emitJson({
        type: 'edit-access-request-cleared',
        member_id: 'm_carol',
        reason: 'left',
      })
    })
    expect(container.querySelector('[data-testid="edit-request-m_carol"]')).toBeNull()
  })

  it('displays control-socket error messages in plain words', async () => {
    await renderAndJoin({ mode: 'pair', role: 'participant', canEdit: false })

    await act(async () => {
      controlSocket()?.emitJson({
        type: 'error',
        code: 'INVALID_REQUEST',
        message: 'Observers cannot request edit access.',
      })
    })

    expect(container.querySelector('[data-testid="edit-request-error"]')?.textContent).toBe(
      'Observers cannot request edit access.',
    )
    const btn = container.querySelector('[data-testid="request-edit-access-button"]') as HTMLButtonElement
    act(() => {
      btn.click()
    })
    expect(controlSocket()?.sentJson.filter((m) => m.type === 'request-edit-access')).toHaveLength(1)
  })
})
