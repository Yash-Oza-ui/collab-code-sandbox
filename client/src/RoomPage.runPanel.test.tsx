// @vitest-environment happy-dom
import { act } from 'react'
import type { RefObject } from 'react'
import { createRoot } from 'react-dom/client'
import type * as Y from 'yjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'

vi.mock('@monaco-editor/react', () => ({
  default: ({ language }: { language?: string }) => (
    <div data-testid="mock-monaco-editor" data-language={language} />
  ),
}))

vi.mock('y-monaco', () => ({
  MonacoBinding: class {
    destroy() {}
  },
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

class MockControlSocket extends EventTarget {
  readyState: number = WebSocket.OPEN
  url: string
  sentMessages: string[] = []

  constructor(url: string) {
    super()
    this.url = url
    setTimeout(() => {
      this.dispatchEvent(new Event('open'))
    }, 0)
  }

  send(data: string) {
    this.sentMessages.push(data)
    try {
      const parsed = JSON.parse(data)
      if (parsed.type === 'join') {
        setTimeout(() => {
          const joinedEvt = new MessageEvent('message', {
            data: JSON.stringify({
              type: 'joined',
              member_id: 'm_1',
              role: 'editor',
              mode: 'pair',
              permissions: { can_edit: true, can_run: true },
            }),
          })
          this.dispatchEvent(joinedEvt)
        }, 0)
      }
    } catch {
      // not JSON
    }
  }

  close() {
    this.readyState = WebSocket.CLOSED
  }
}

describe('RoomPage RunPanel integration (T-11)', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null

  beforeEach(() => {
    sessionStorage.clear() // Each test starts without a previously joined browser tab.
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    window.history.pushState({}, '', '/room/demo-run-room')
  })

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount())
      root = null
    }
    container?.remove()
    container = null
    vi.restoreAllMocks()
  })

  async function joinRoomAs(name: string, customFetch?: typeof fetch, docRef?: RefObject<Y.Doc | null>) {
    await act(async () => {
      root?.render(
        <RoomPage
          docRef={docRef}
          wsOptions={{
            WebSocketClass: MockControlSocket as unknown as typeof WebSocket,
            fetchFn: customFetch,
          }}
        />,
      )
    })

    const input = container!.querySelector<HTMLInputElement>('#display-name')!
    const form = container!.querySelector<HTMLFormElement>('form')!
    await act(async () => {
      input.value = name
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    // Allow mock control socket to respond with joined
    await act(async () => {
      await new Promise((res) => setTimeout(res, 20))
    })
  }

  it('renders RunPanel with Run button and language picker after joining', async () => {
    await joinRoomAs('Alice')

    expect(container!.querySelector('[data-testid="room-user-badge"]')?.textContent).toContain('You: Alice')
    const runPanel = container!.querySelector('[data-testid="run-panel"]')
    expect(runPanel).not.toBeNull()

    const select = container!.querySelector<HTMLSelectElement>('[data-testid="language-select"]')
    expect(select).not.toBeNull()
    expect(select!.value).toBe('python')

    const runBtn = container!.querySelector<HTMLButtonElement>('[data-testid="run-button"]')
    expect(runBtn).not.toBeNull()
    expect(runBtn!.textContent).toContain('Run')

    const editor = container!.querySelector('[data-testid="mock-monaco-editor"]')
    expect(editor?.getAttribute('data-language')).toBe('python')
  })

  it('updates Monaco editor language when user selects a different language in RunPanel', async () => {
    await joinRoomAs('Alice')

    const select = container!.querySelector<HTMLSelectElement>('[data-testid="language-select"]')!
    await act(async () => {
      select.value = 'javascript'
      select.dispatchEvent(new Event('change', { bubbles: true }))
    })

    const editor = container!.querySelector('[data-testid="mock-monaco-editor"]')
    expect(editor?.getAttribute('data-language')).toBe('javascript')
  })

  it('sends editor code and stdin to execution service when clicking Run and displays result', async () => {
    const docRef: RefObject<Y.Doc | null> = { current: null }
    let requestPayload: unknown = null

    const mockFetch = vi.fn().mockImplementation(async (_url, init) => {
      requestPayload = JSON.parse(init.body as string)
      const payload = requestPayload as { request_id?: string; room_id?: string; language?: string }
      return {
        ok: true,
        status: 200,
        json: async () => ({
          request_id: payload.request_id ?? 'test-req-id',
          room_id: payload.room_id ?? 'demo-run-room',
          language: payload.language ?? 'python',
          stdout: '42\n',
          stderr: '',
          exit_code: 0,
          execution_time_ms: 14.2,
          peak_memory_mb: 3.5,
          timed_out: false,
          oom_killed: false,
          failure_kind: null,
          output_truncated: false,
          message: null,
          compile_time_ms: null,
        }),
      }
    })

    await joinRoomAs('Alice', mockFetch as unknown as typeof fetch, docRef)

    // Set code in shared document
    act(() => {
      docRef.current?.getText('monaco').insert(0, 'print(42)')
    })

    // Toggle and enter stdin
    const toggleStdinBtn = container!.querySelector<HTMLButtonElement>('[data-testid="stdin-toggle-button"]')!
    await act(async () => {
      toggleStdinBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })

    const stdinArea = container!.querySelector<HTMLTextAreaElement>('[data-testid="stdin-textarea"]')!
    await act(async () => {
      const nativeSetter = Object.getOwnPropertyDescriptor(
        window.HTMLTextAreaElement.prototype,
        'value',
      )?.set
      nativeSetter?.call(stdinArea, 'hello stdin')
      stdinArea.dispatchEvent(new Event('input', { bubbles: true }))
      stdinArea.dispatchEvent(new Event('change', { bubbles: true }))
    })

    // Click Run
    const runBtn = container!.querySelector<HTMLButtonElement>('[data-testid="run-button"]')!
    await act(async () => {
      runBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })

    expect(mockFetch).toHaveBeenCalledTimes(1)
    const payload = requestPayload as {
      room_id: string
      language: string
      stdin: string
      source: { kind: string; files: { name: string; content: string }[]; entry: string }
    }
    expect(payload.room_id).toBe('demo-run-room')
    expect(payload.language).toBe('python')
    expect(payload.stdin).toBe('hello stdin')
    expect(payload.source.kind).toBe('inline')
    expect(payload.source.entry).toBe('main.py')
    expect(payload.source.files[0].content).toBe('print(42)')

    // Verify output appears
    const stdout = container!.querySelector('[data-testid="run-stdout"]')
    expect(stdout?.textContent).toBe('42\n')
  })

  it('displays error banner when execution service is offline or unreachable', async () => {
    const mockFetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))

    await joinRoomAs('Alice', mockFetch as unknown as typeof fetch)

    const runBtn = container!.querySelector<HTMLButtonElement>('[data-testid="run-button"]')!
    await act(async () => {
      runBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })

    const banner = container!.querySelector('[data-testid="run-status-banner"]')
    expect(banner).not.toBeNull()
    expect(banner?.textContent).toContain('Execution service is unavailable')
  })
})
