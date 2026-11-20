// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'
import { ownerTokenKey } from './createRoom'

// US-60 (FR41.1 to FR41.4): who can run, in RoomPage.
// - The Run button follows `can_run` and the reason is shown in plain words.
// - The Host can turn Run off / on for a participant and switch Host-only running.
// - Those controls send the Host's session id and owner token; nobody else sees them.

vi.mock('@monaco-editor/react', () => ({ default: () => <div data-testid="mock-monaco-editor" /> }))
vi.mock('y-monaco', () => ({ MonacoBinding: class { destroy() {} } }))
;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const ROOM_ID = 'room-run-permission-test'
const SESSION_ID = '40000000-0000-4000-8000-000000000004'
const OWNER_TOKEN = 'owner-secret-for-test'

class FakeSocket {
  static instances: FakeSocket[] = []
  static role = 'participant'
  static memberId = 'm_me'

  readyState = 0
  binaryType = 'arraybuffer'
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onclose: ((e: CloseEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null

  url: string

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
    if ((JSON.parse(data) as { type?: string }).type !== 'join') return
    setTimeout(() => {
      this.receive({
        type: 'joined',
        member_id: FakeSocket.memberId,
        role: FakeSocket.role,
        mode: 'pair',
        preset: 'debug_together',
        permissions: { can_edit: true, can_run: true, run_denied_reason: null },
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

function member(id: string, name: string, role: string, canRun: boolean, reason: string | null) {
  return {
    member_id: id,
    display_name: name,
    role,
    permissions: { can_edit: true, can_run: canRun, run_denied_reason: reason },
  }
}

describe('RoomPage run permission (US-60)', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let originalPathname = '/'

  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    FakeSocket.instances = []
    FakeSocket.role = 'participant'
    FakeSocket.memberId = 'm_me'
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

  async function joinAs(name: string, fetchFn?: typeof fetch) {
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

  async function roster(members: unknown[], hostOnlyRun?: boolean) {
    await act(async () => {
      FakeSocket.instances[0].receive({
        type: 'roster',
        members,
        ...(hostOnlyRun === undefined ? {} : { host_only_run: hostOnlyRun }),
      })
    })
  }

  const runButton = () => container?.querySelector<HTMLButtonElement>('[data-testid="run-button"]')
  const reasonText = () => container?.querySelector('[data-testid="run-disabled-reason"]')?.textContent

  it('keeps Run enabled when the server allows it (FR41.1)', async () => {
    await joinAs('Bob')

    expect(runButton()?.disabled).toBe(false)
    expect(reasonText()).toBeUndefined()
  })

  it('disables Run and says why when the Host turned it off for me', async () => {
    await joinAs('Bob')
    await roster([member('m_me', 'Bob', 'participant', false, 'run_revoked')])

    expect(runButton()?.disabled).toBe(true)
    expect(reasonText()).toBe('The Host has turned off running for you.')
    expect(runButton()?.title).toBe('The Host has turned off running for you.')
  })

  it('enables Run again when the next roster gives it back', async () => {
    await joinAs('Bob')
    await roster([member('m_me', 'Bob', 'participant', false, 'run_revoked')])
    await roster([member('m_me', 'Bob', 'participant', true, null)])

    expect(runButton()?.disabled).toBe(false)
    expect(reasonText()).toBeUndefined()
  })

  it('disables Run for a participant while Host-only running is on, with its reason', async () => {
    await joinAs('Bob')
    await roster([member('m_me', 'Bob', 'participant', false, 'host_only')], true)

    expect(runButton()?.disabled).toBe(true)
    expect(reasonText()).toBe('Only the Host can run code in this room right now.')
    expect(container?.querySelector('[data-testid="host-only-run-notice"]')).not.toBeNull()
    expect(container?.querySelector('[data-testid="host-only-run-toggle"]')).toBeNull()
  })

  it('does not show the Host controls to a participant', async () => {
    await joinAs('Bob')
    await roster([
      member('m_host', 'Ada', 'host', true, null),
      member('m_me', 'Bob', 'participant', true, null),
    ])

    expect(container?.querySelector('[data-testid^="run-off-"]')).toBeNull()
    expect(container?.querySelector('[data-testid="host-only-run-toggle"]')).toBeNull()
  })

  describe('as the Host', () => {
    // The Host also reads the removed-members list on joining (US-28, GET), which these tests
    // ignore: they look only at the PATCH calls.
    function hostFetch() {
      return vi.fn().mockImplementation(async (_url: string, init?: RequestInit) => ({
        ok: true,
        status: 200,
        json: async () => (init?.method === 'GET' ? { blocked: [] } : {}),
      }))
    }
    const patchCalls = (fetchFn: ReturnType<typeof hostFetch>) =>
      fetchFn.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'PATCH')

    beforeEach(() => {
      FakeSocket.role = 'host'
      localStorage.setItem(ownerTokenKey(ROOM_ID), OWNER_TOKEN)
    })

    it('Host always keeps Run, even while Host-only running is on', async () => {
      await joinAs('Ada')
      await roster([member('m_me', 'Ada', 'host', true, null)], true)

      expect(runButton()?.disabled).toBe(false)
    })

    it('turns Run off for a participant with the Host credentials', async () => {
      const fetchFn = hostFetch()
      await joinAs('Ada', fetchFn as unknown as typeof fetch)
      await roster([
        member('m_me', 'Ada', 'host', true, null),
        member('m_bob', 'Bob', 'participant', true, null),
      ])

      expect(container?.querySelector('[data-testid="run-off-m_me"]')).toBeNull() // not for myself
      await act(async () => {
        container?.querySelector<HTMLButtonElement>('[data-testid="run-off-m_bob"]')?.click()
      })

      expect(patchCalls(fetchFn)).toHaveLength(1)
      const [url, init] = patchCalls(fetchFn)[0] as [string, RequestInit]
      expect(url).toContain(`/v1/rooms/${ROOM_ID}/members/m_bob/run-permission`)
      expect(init.method).toBe('PATCH')
      expect(JSON.parse(init.body as string)).toEqual({ can_run: false })
      const headers = init.headers as Record<string, string>
      expect(headers['X-Session-Id']).toBe(SESSION_ID)
      expect(headers['X-Owner-Token']).toBe(OWNER_TOKEN)
    })

    it('offers Turn on Run for a member whose Run is off, and sends can_run true', async () => {
      const fetchFn = hostFetch()
      await joinAs('Ada', fetchFn as unknown as typeof fetch)
      await roster([
        member('m_me', 'Ada', 'host', true, null),
        member('m_bob', 'Bob', 'participant', false, 'run_revoked'),
      ])

      expect(container?.querySelector('[data-testid="run-off-m_bob"]')).toBeNull()
      await act(async () => {
        container?.querySelector<HTMLButtonElement>('[data-testid="run-on-m_bob"]')?.click()
      })

      const [, init] = patchCalls(fetchFn)[0] as [string, RequestInit]
      expect(JSON.parse(init.body as string)).toEqual({ can_run: true })
    })

    it('offers no run control for an Observer', async () => {
      await joinAs('Ada')
      await roster([
        member('m_me', 'Ada', 'host', true, null),
        member('m_obs', 'Cy', 'observer', false, 'observer'),
      ])

      expect(container?.querySelector('[data-testid="run-off-m_obs"]')).toBeNull()
      expect(container?.querySelector('[data-testid="run-on-m_obs"]')).toBeNull()
    })

    it('switches Host-only running on and follows the roster flag', async () => {
      const fetchFn = hostFetch()
      await joinAs('Ada', fetchFn as unknown as typeof fetch)
      await roster([member('m_me', 'Ada', 'host', true, null)], false)

      const toggle = () => container?.querySelector<HTMLInputElement>('[data-testid="host-only-run-toggle"]')
      expect(toggle()?.checked).toBe(false)
      await act(async () => {
        toggle()?.click()
      })

      const [url, init] = patchCalls(fetchFn)[0] as [string, RequestInit]
      expect(url).toContain(`/v1/rooms/${ROOM_ID}/run-policy`)
      expect(init.method).toBe('PATCH')
      expect(JSON.parse(init.body as string)).toEqual({ host_only_run: true })

      await roster([member('m_me', 'Ada', 'host', true, null)], true)
      expect(toggle()?.checked).toBe(true)
    })

    it('shows the server error when a change is refused', async () => {
      const fetchFn = vi.fn().mockResolvedValue({
        ok: false,
        status: 403,
        json: async () => ({ error: { code: 'NOT_OWNER', message: 'Missing or invalid owner token.' } }),
      })
      await joinAs('Ada', fetchFn as unknown as typeof fetch)
      await roster([member('m_me', 'Ada', 'host', true, null)], false)

      await act(async () => {
        container?.querySelector<HTMLInputElement>('[data-testid="host-only-run-toggle"]')?.click()
      })

      expect(container?.querySelector('[data-testid="run-control-error"]')?.textContent).toBe(
        'Missing or invalid owner token.',
      )
    })
  })
})
