// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'

// US-08 / T-11: the result of a run reaches every participant in the room.
// - A `run-result` frame on the control socket shows the output for a participant who did not press Run.
// - Observers receive it too.
// - The same request_id is shown once, whether the HTTP response or the frame arrives first.
// - A frame for another room or a malformed frame is ignored.

vi.mock('@monaco-editor/react', () => ({
  default: () => <div data-testid="mock-monaco-editor" />,
}))

vi.mock('y-monaco', () => ({
  MonacoBinding: class {
    destroy() {}
  },
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const ROOM_ID = 'room-run-result-test'
const SESSION_ID = '40000000-0000-4000-8000-000000000004'
const REQUEST_ID = '0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10'

class FakeSocket {
  static instances: FakeSocket[] = []
  static role = 'participant'

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
        member_id: 'm_self',
        role: FakeSocket.role,
        mode: 'pair',
        permissions: { can_edit: FakeSocket.role !== 'observer', can_run: FakeSocket.role !== 'observer' },
      })
      this.receive({
        type: 'roster',
        members: [
          { member_id: 'm_self', display_name: String(msg.display_name), role: FakeSocket.role },
          { member_id: 'm_ada', display_name: 'Ada', role: 'participant' },
        ],
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

function runResultFrame(overrides: Record<string, unknown> = {}) {
  return {
    type: 'run-result',
    request_id: REQUEST_ID,
    room_id: ROOM_ID,
    language: 'python',
    stdout: 'hello from Ada\n',
    stderr: '',
    exit_code: 0,
    execution_time_ms: 38,
    peak_memory_mb: 7.9,
    timed_out: false,
    oom_killed: false,
    failure_kind: null,
    output_truncated: false,
    message: null,
    compile_time_ms: null,
    runner: { member_id: 'm_ada', display_name: 'Ada' },
    ran_at: '2026-11-01T10:20:05.087Z',
    visibility: 'room',
    saved_versions: [],
    failure: null,
    ...overrides,
  }
}

describe('RoomPage run-result delivery (US-08)', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let originalPathname = '/'

  beforeEach(() => {
    // Prevent a previous test's saved display name from auto-joining this room.
    sessionStorage.clear()

    FakeSocket.instances = []
    FakeSocket.role = 'participant'
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', `/room/${ROOM_ID}`)
  })

  afterEach(async () => {
    if (root) await act(async () => root?.unmount())
    root = null
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
            executionApiUrl: 'http://exec.test',
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

  const controlSocket = () => FakeSocket.instances.find((s) => s.url.includes(`/ws/rooms/${ROOM_ID}`))
  const q = (id: string) => container?.querySelector(`[data-testid="${id}"]`)

  it("shows another participant's run output when the run-result frame arrives", async () => {
    await joinAs('Bob')
    expect(q('run-stdout')).toBeNull()

    await act(async () => controlSocket()?.receive(runResultFrame()))

    expect(q('run-stdout')?.textContent).toBe('hello from Ada\n')
    expect(q('run-output-runner')?.textContent).toContain('Run by Ada')
  })

  it('uses the roster name rather than a forged name in a run-result frame', async () => {
    await joinAs('Bob')

    await act(async () => controlSocket()?.receive(runResultFrame({
      runner: { member_id: 'm_ada', display_name: 'Impersonator' },
    })))
    expect(q('run-output-runner')?.textContent).toContain('Run by Ada')
    expect(q('run-output-runner')?.textContent).not.toContain('Impersonator')

    await act(async () => controlSocket()?.receive(runResultFrame({
      request_id: '22222222-2222-4222-8222-222222222222',
      runner: { member_id: 'm_unknown', display_name: 'Unverified' },
    })))
    expect(q('run-output-runner')).toBeNull()
  })

  it('shows the run output to an observer too', async () => {
    FakeSocket.role = 'observer'
    await joinAs('Carol')

    await act(async () => controlSocket()?.receive(runResultFrame()))

    expect(q('run-stdout')?.textContent).toBe('hello from Ada\n')
  })

  it('replaces the output with the next run, and shows a repeated request_id only once', async () => {
    await joinAs('Bob')
    await act(async () => controlSocket()?.receive(runResultFrame()))
    await act(async () => controlSocket()?.receive(runResultFrame({ stdout: 'duplicate\n' })))
    expect(q('run-stdout')?.textContent).toBe('hello from Ada\n')

    await act(async () =>
      controlSocket()?.receive(runResultFrame({ request_id: '11111111-1111-4111-8111-111111111111', stdout: 'second\n' })),
    )
    expect(q('run-stdout')?.textContent).toBe('second\n')
  })

  it('does not show the result twice when the own HTTP response and the room frame both arrive', async () => {
    let requestId = ''
    const fetchFn = vi.fn(async (_url: unknown, init?: RequestInit) => {
      requestId = JSON.parse(String(init?.body)).request_id
      return new Response(JSON.stringify(runResultFrame({ request_id: requestId, stdout: 'from http\n' })), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }) as unknown as typeof fetch

    await joinAs('Ada', fetchFn)
    await act(async () => {
      container!.querySelector<HTMLButtonElement>('[data-testid="run-button"]')!.click()
    })
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20))
    })
    expect(q('run-stdout')?.textContent).toBe('from http\n')

    // The room frame for the same run arrives afterwards: nothing changes.
    await act(async () => controlSocket()?.receive(runResultFrame({ request_id: requestId, stdout: 'from frame\n' })))
    expect(q('run-stdout')?.textContent).toBe('from http\n')
    expect(container!.querySelectorAll('[data-testid="run-stdout"]').length).toBe(1)
  })

  it('updates visibility from private to room when shared frame arrives for the same request_id (US-49)', async () => {
    await joinAs('Bob')
    expect(q('run-output-preview')).toBeNull()

    // Private run result arrives
    await act(async () =>
      controlSocket()?.receive(runResultFrame({ request_id: '99999999-9999-4999-8999-999999999999', visibility: 'private' })),
    )
    expect(q('run-output-visibility')?.textContent).toBe('Private')

    // Same run is shared to room: visibility updates to 'Shared to room' without adding duplicate
    await act(async () =>
      controlSocket()?.receive(runResultFrame({ request_id: '99999999-9999-4999-8999-999999999999', visibility: 'room' })),
    )
    expect(q('run-output-visibility')?.textContent).toBe('Shared to room')
    expect(container!.querySelectorAll('[data-testid="run-stdout"]').length).toBe(1)
  })

  it('ignores a run-result for another room and a malformed run-result', async () => {
    await joinAs('Bob')

    await act(async () => controlSocket()?.receive(runResultFrame({ room_id: 'some-other-room' })))
    await act(async () => controlSocket()?.receive({ type: 'run-result', request_id: REQUEST_ID }))

    expect(q('run-output-preview')).toBeNull()
  })
})
