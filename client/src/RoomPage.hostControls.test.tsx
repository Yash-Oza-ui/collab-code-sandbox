// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'
import { ownerTokenKey } from './createRoom'
import { config } from './config'

// US-28 (V2.18) and US-54 (V2.19): participant-list Host controls and the messages behind them.
// - Host: Remove, Make Host, the removed list with Re-admit, Regenerate link.
// - host-granted stores the new owner token; a role that is no longer Host drops it.
// - USER_BLOCKED, close 4403, 4404 and 4411 end the session with a message and no retry.
// The server is the enforcement (NFR13); these tests only check that the client drives it.

vi.mock('@monaco-editor/react', () => ({ default: () => <div data-testid="mock-monaco-editor" /> }))
vi.mock('y-monaco', () => ({ MonacoBinding: class { destroy() {} } }))
;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const ROOM_ID = 'room-host-controls-test'
const NEW_ROOM_ID = 'T4vAWVm_BI_DgmSxSGmr8w'
const SESSION_ID = '60000000-0000-4000-8000-000000000006'
const OLD_TOKEN = 'old-owner-token'
const NEW_TOKEN = 'new-owner-token'
const BASE = `${config.platformApiUrl}/v1/rooms/${ROOM_ID}`

class FakeSocket {
  static instances: FakeSocket[] = []
  static role = 'participant'
  static refuse: string | null = null

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
    if ((JSON.parse(data) as { type?: string }).type !== 'join') return
    setTimeout(() => {
      if (FakeSocket.refuse) {
        this.receive({ type: 'join-refused', reason: FakeSocket.refuse, message: 'server wording' })
        return
      }
      this.receive({
        type: 'joined',
        member_id: 'm_me',
        role: FakeSocket.role,
        mode: 'pair',
        preset: 'debug_together',
        permissions: { can_edit: true, can_run: true },
      })
    }, 0)
  }

  receive(data: Record<string, unknown>) {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(data) }))
  }

  /** The server closes this socket with a code (4403, 4404, 4411 ...). */
  closeWith(code: number) {
    this.readyState = 3
    this.onclose?.(Object.assign(new Event('close'), { code, reason: '' }) as unknown as CloseEvent)
  }

  close() {
    this.readyState = 3
  }
}

const member = (id: string, name: string, role: string) => ({
  member_id: id,
  display_name: name,
  role,
  permissions: { can_edit: true, can_run: true },
})

describe('RoomPage Host controls (US-28, US-54)', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let originalPathname = '/'
  let blocked: Array<{ member_id: string; display_name: string; removed_at: string }> = []
  let navigate: ReturnType<typeof vi.fn<(path: string) => void>>
  let fetchFn: ReturnType<typeof vi.fn>

  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    FakeSocket.instances = []
    FakeSocket.role = 'participant'
    FakeSocket.refuse = null
    blocked = []
    navigate = vi.fn<(path: string) => void>()
    // A small fake of the sync-service REST calls the participant list makes.
    fetchFn = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body })
      if (method === 'GET' && url === `${BASE}/members/blocked`) return ok({ blocked })
      if (method === 'POST' && url === `${BASE}/members/m_lin/remove`) {
        blocked = [{ member_id: 'm_lin', display_name: 'Lin', removed_at: '2026-11-18T06:12:40.512Z' }]
        return ok({ member_id: 'm_lin', status: 'removed' })
      }
      if (method === 'POST' && url === `${BASE}/members/m_lin/readmit`) {
        blocked = []
        return ok({ member_id: 'm_lin', status: 'readmitted' })
      }
      if (method === 'POST' && url === `${BASE}/members/m_bob/make-host`) {
        return ok({ host_member_id: 'm_bob', status: 'transferred' })
      }
      if (method === 'POST' && url === `${BASE}/regenerate-link`) {
        return ok({ room_id: NEW_ROOM_ID, status: 'regenerated' })
      }
      return { ok: false, status: 404, json: async () => ({ error: { code: 'ROOM_NOT_FOUND', message: 'x' } }) }
    })
    // happy-dom has no window.confirm; the page asks before Make Host and Regenerate link.
    Object.defineProperty(window, 'confirm', { value: vi.fn(() => true), configurable: true, writable: true })
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
    vi.restoreAllMocks()
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
          navigate={navigate}
          wsOptions={{
            WebSocketClass: FakeSocket as unknown as typeof WebSocket,
            sessionId: SESSION_ID,
            fetchFn: fetchFn as unknown as typeof fetch,
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

  async function joinAsHost() {
    FakeSocket.role = 'host'
    localStorage.setItem(ownerTokenKey(ROOM_ID), OLD_TOKEN)
    await joinAs('Ada')
  }

  async function roster(members: unknown[]) {
    await act(async () => {
      FakeSocket.instances[0].receive({ type: 'roster', members })
    })
  }

  async function click(testId: string) {
    await act(async () => {
      container?.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)?.click()
    })
    await act(async () => {
      await new Promise((r) => setTimeout(r, 10))
    })
  }

  const el = (testId: string) => container?.querySelector(`[data-testid="${testId}"]`)
  const calls = (method: string) =>
    fetchFn.mock.calls.filter(([, init]) => ((init as RequestInit | undefined)?.method ?? 'GET') === method)
  const hostRoster = [member('m_me', 'Ada', 'host'), member('m_lin', 'Lin', 'participant'), member('m_bob', 'Bob', 'participant')]

  // ----- who sees the controls -----

  it('shows Remove and Make Host for other participants, never for myself', async () => {
    await joinAsHost()
    await roster([...hostRoster, member('m_obs', 'Cy', 'observer')])

    expect(el('remove-m_lin')).not.toBeNull()
    expect(el('make-host-m_lin')).not.toBeNull()
    expect(el('remove-m_me')).toBeNull()
    expect(el('make-host-m_me')).toBeNull()
    // An Observer can be removed but cannot be made Host (V2.19 rule 2).
    expect(el('remove-m_obs')).not.toBeNull()
    expect(el('make-host-m_obs')).toBeNull()
    expect(el('regenerate-link-button')).not.toBeNull()
  })

  it('shows no Host controls to a participant', async () => {
    await joinAs('Lin')
    await roster([member('m_host', 'Ada', 'host'), member('m_me', 'Lin', 'participant')])

    expect(el('remove-m_host')).toBeNull()
    expect(el('make-host-m_host')).toBeNull()
    expect(el('regenerate-link-button')).toBeNull()
    expect(fetchFn).not.toHaveBeenCalled()
  })

  // ----- US-28: remove, blocked list, re-admit, regenerate -----

  it('lists removed members on joining, so Re-admit survives a refresh', async () => {
    blocked = [{ member_id: 'm_lin', display_name: 'Lin', removed_at: '2026-11-18T06:12:40.512Z' }]
    await joinAsHost()

    expect(el('blocked-m_lin')?.textContent).toContain('Lin')
    expect(el('readmit-m_lin')).not.toBeNull()
    const [url, init] = calls('GET')[0] as [string, RequestInit]
    expect(url).toBe(`${BASE}/members/blocked`)
    expect(init.headers).toEqual({ 'X-Session-Id': SESSION_ID, 'X-Owner-Token': OLD_TOKEN })
  })

  it('Remove sends the Host headers, then shows the person in the removed list', async () => {
    await joinAsHost()
    await roster(hostRoster)

    await click('remove-m_lin')

    const [url, init] = calls('POST')[0] as [string, RequestInit]
    expect(url).toBe(`${BASE}/members/m_lin/remove`)
    expect(init.headers).toEqual({ 'X-Session-Id': SESSION_ID, 'X-Owner-Token': OLD_TOKEN })
    expect(el('readmit-m_lin')).not.toBeNull()
  })

  it('Re-admit sends the request and clears the removed entry', async () => {
    blocked = [{ member_id: 'm_lin', display_name: 'Lin', removed_at: '2026-11-18T06:12:40.512Z' }]
    await joinAsHost()

    await click('readmit-m_lin')

    expect((calls('POST')[0] as [string])[0]).toBe(`${BASE}/members/m_lin/readmit`)
    expect(el('blocked-m_lin')).toBeNull()
  })

  it('shows the server refusal when a Host action fails (NOT_OWNER)', async () => {
    await joinAsHost()
    await roster(hostRoster)
    fetchFn.mockImplementationOnce(async () => ({
      ok: false,
      status: 403,
      json: async () => ({ error: { code: 'NOT_OWNER', message: 'Missing or invalid owner token.' } }),
    }))

    await click('remove-m_lin')

    expect(el('host-action-error')?.textContent).toBe('Only the Host can do this.')
  })

  it('says so when this browser has no owner token', async () => {
    FakeSocket.role = 'host' // joined as Host, but no token stored in this browser
    await joinAs('Ada')
    await roster(hostRoster)

    await click('remove-m_lin')

    expect(el('host-action-error')?.textContent).toBe('Missing Host credentials. Please rejoin the room.')
    expect(calls('POST')).toHaveLength(0)
  })

  it('Regenerate link: asks first, then moves the token and opens the new link', async () => {
    await joinAsHost()
    vi.mocked(window.confirm).mockReturnValueOnce(false)
    await click('regenerate-link-button')
    expect(calls('POST')).toHaveLength(0)
    expect(navigate).not.toHaveBeenCalled()

    await click('regenerate-link-button')

    expect((calls('POST')[0] as [string])[0]).toBe(`${BASE}/regenerate-link`)
    expect(navigate).toHaveBeenCalledWith(`/room/${NEW_ROOM_ID}`)
    // Same owner token (V2.18 rule 1), now stored for the new id only; the name comes along.
    expect(localStorage.getItem(ownerTokenKey(NEW_ROOM_ID))).toBe(OLD_TOKEN)
    expect(localStorage.getItem(ownerTokenKey(ROOM_ID))).toBeNull()
    expect(sessionStorage.getItem(`collab_room_name:${NEW_ROOM_ID}:${SESSION_ID}`)).toBe('Ada')
  })

  it("Regenerate link: the Host's own 4411 close is not shown as an error", async () => {
    await joinAsHost()
    await click('regenerate-link-button')

    await act(async () => {
      FakeSocket.instances[0].closeWith(4411)
    })

    expect(container?.textContent).not.toContain('changed the room link')
  })

  // ----- US-54: make Host, host-granted -----

  it('Make Host: asks first, sends the request, and no role change is assumed', async () => {
    await joinAsHost()
    await roster(hostRoster)
    vi.mocked(window.confirm).mockReturnValueOnce(false)
    await click('make-host-m_bob')
    expect(calls('POST')).toHaveLength(0)

    await click('make-host-m_bob')

    expect((calls('POST')[0] as [string])[0]).toBe(`${BASE}/members/m_bob/make-host`)
    expect(el('make-host-m_bob')).not.toBeNull() // still Host here until the roster says otherwise
    expect(localStorage.getItem(ownerTokenKey(ROOM_ID))).toBe(OLD_TOKEN)
  })

  it('a previous Host drops the owner token and the controls when the roster demotes them', async () => {
    await joinAsHost()
    await roster(hostRoster)

    await roster([member('m_me', 'Ada', 'participant'), member('m_bob', 'Bob', 'host'), member('m_lin', 'Lin', 'participant')])

    expect(localStorage.getItem(ownerTokenKey(ROOM_ID))).toBeNull()
    expect(el('remove-m_lin')).toBeNull()
    expect(el('make-host-m_bob')).toBeNull()
    expect(el('regenerate-link-button')).toBeNull()
    expect(container?.querySelector('[data-testid="room-host-badge"]')).toBeNull()
  })

  it('host-granted stores the new token and the Host controls work with it', async () => {
    await joinAs('Bob')
    FakeSocket.instances[0].receive({ type: 'joined', member_id: 'm_me', role: 'participant', mode: 'pair' })
    await roster([member('m_host', 'Ada', 'host'), member('m_me', 'Bob', 'participant'), member('m_lin', 'Lin', 'participant')])
    expect(localStorage.getItem(ownerTokenKey(ROOM_ID))).toBeNull()

    await act(async () => {
      FakeSocket.instances[0].receive({ type: 'host-granted', member_id: 'm_me', owner_token: NEW_TOKEN })
    })
    await roster([member('m_host', 'Ada', 'participant'), member('m_me', 'Bob', 'host'), member('m_lin', 'Lin', 'participant')])

    expect(localStorage.getItem(ownerTokenKey(ROOM_ID))).toBe(NEW_TOKEN)
    expect(el('room-host-badge')).not.toBeNull()
    expect(el('remove-m_lin')).not.toBeNull()
    // The new token, not the old one, is what the blocked list read sends.
    const [, init] = calls('GET')[calls('GET').length - 1] as [string, RequestInit]
    expect((init.headers as Record<string, string>)['X-Owner-Token']).toBe(NEW_TOKEN)
  })

  it('host-granted for another member, or without a token, changes nothing', async () => {
    await joinAs('Lin')
    await act(async () => {
      FakeSocket.instances[0].receive({ type: 'host-granted', member_id: 'someone_else', owner_token: NEW_TOKEN })
      FakeSocket.instances[0].receive({ type: 'host-granted', member_id: 'm_me', owner_token: '' })
      FakeSocket.instances[0].receive({ type: 'host-granted', member_id: 'm_me' })
    })

    expect(localStorage.getItem(ownerTokenKey(ROOM_ID))).toBeNull()
    expect(el('regenerate-link-button')).toBeNull()
  })

  it('a stale owner token is dropped when the server says this session is not the Host', async () => {
    localStorage.setItem(ownerTokenKey(ROOM_ID), OLD_TOKEN) // retired by a transfer, then a refresh
    FakeSocket.role = 'participant'

    await joinAs('Ada')

    expect(localStorage.getItem(ownerTokenKey(ROOM_ID))).toBeNull()
    expect(el('regenerate-link-button')).toBeNull()
  })

  // ----- V2.18 "Client reconnect": messages and no retry -----

  it('join-refused USER_BLOCKED shows the contract wording and stops', async () => {
    FakeSocket.refuse = 'USER_BLOCKED'

    await joinAs('Lin')

    expect(container?.textContent).toContain('The Host has removed you from this room.')
    expect(container?.textContent).not.toContain('server wording')
    expect(FakeSocket.instances).toHaveLength(1)
  })

  it.each([
    [4403, 'The Host has removed you from this room.'],
    [4404, 'Room not found (4404). Please verify the room link.'],
    [4411, 'The Host changed the room link. Ask the Host for the new link to rejoin.'],
  ])('close %i ends the session with a message and no new socket', async (code, text) => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      await joinAs('Lin')
      expect(el('room-user-badge')).not.toBeNull()
      const socketsBefore = FakeSocket.instances.length // control + document socket

      await act(async () => {
        FakeSocket.instances[0].closeWith(code)
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(120000)
      })

      expect(container?.textContent).toContain(text)
      expect(FakeSocket.instances).toHaveLength(socketsBefore) // nothing reconnected
      // No automatic rejoin of the old id on the next load (4403, 4411).
      if (code !== 4404) {
        expect(sessionStorage.getItem(`collab_room_name:${ROOM_ID}:${SESSION_ID}`)).toBeNull()
      }
    } finally {
      vi.useRealTimers()
    }
  })
})
