// @vitest-environment happy-dom
/* eslint-disable @typescript-eslint/no-explicit-any */
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Y from 'yjs'
import RoomPage from './RoomPage'

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

const ROOM_ID = 'us45-test-room'
const SESSION_ID = '33333333-3333-4333-8333-333333333333'

describe('RoomPage US-45: Save a module manually', () => {
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

  async function renderOpenRoom(params: {
    myMemberId: string
    role: 'host' | 'participant' | 'observer'
    modules: Array<Record<string, unknown>>
    moduleDocs: Map<string, Y.Doc>
    fetchFn: typeof fetch
  }) {
    root = createRoot(container)
    act(() => {
      root?.render(
        <RoomPage
          moduleDocs={params.moduleDocs}
          wsOptions={{
            WebSocketClass: FakeSocket as unknown as typeof WebSocket,
            sessionId: SESSION_ID,
            fetchFn: params.fetchFn,
          }}
        />,
      )
    })

    submitDisplayName('Tester')
    await vi.waitFor(() => expect(controlSocket()).toBeTruthy())

    await act(async () => {
      controlSocket()?.emitJson({
        type: 'joined',
        member_id: params.myMemberId,
        role: params.role,
        mode: 'open',
        preset: null,
        language: 'python',
        permissions: { can_run: params.role !== 'observer' },
      })
      controlSocket()?.emitJson({
        type: 'roster',
        members: [
          {
            member_id: params.myMemberId,
            display_name: 'Tester',
            role: params.role,
            permissions: { can_run: params.role !== 'observer' },
          },
        ],
      })
    })

    await vi.waitFor(() => {
      expect(container.querySelector('.room-workspace-panel')).not.toBeNull()
    })
  }

  it('shows Save only for owner/writer, sends text on Save, and toggles dirty dot via module-updated', async () => {
    const modules = [
      {
        module_id: 'mod_own',
        name: 'main.py',
        owner_member_id: 'm_me',
        writers: [],
        runnable: true,
        is_entry: true,
        version: 0,
        pending: false,
        dirty: true,
      },
      {
        module_id: 'mod_read',
        name: 'lib.py',
        owner_member_id: 'm_other',
        writers: [],
        runnable: true,
        is_entry: false,
        version: 1,
        pending: false,
        dirty: false,
      },
    ]

    const fetchFn = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith('/modules') && (!init?.method || init.method === 'GET')) {
        return { ok: true, status: 200, json: async () => modules }
      }
      if (String(url).endsWith('/modules/mod_own/save') && init?.method === 'POST') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            ...modules[0],
            version: 1,
            dirty: false,
            saved_by: { member_id: 'm_me', display_name: 'Tester' },
            saved_at: '2026-11-24T01:20:00Z',
          }),
        }
      }
      return { ok: true, status: 200, json: async () => ({}) }
    }) as unknown as typeof fetch

    const moduleDocs = new Map<string, Y.Doc>()
    const docOwn = new Y.Doc()
    docOwn.getText('monaco').insert(0, 'print("saved")\n')
    moduleDocs.set('mod_own', docOwn)
    moduleDocs.set('mod_read', new Y.Doc())

    await renderOpenRoom({
      myMemberId: 'm_me',
      role: 'participant',
      modules,
      moduleDocs,
      fetchFn,
    })

    await vi.waitFor(() => {
      expect(container.querySelector('[data-testid="save-module-mod_own"]')).not.toBeNull()
    })
    expect(container.querySelector('[data-testid="save-module-mod_read"]')).toBeNull()
    expect(container.querySelector('[data-testid="dirty-dot-mod_own"]')).not.toBeNull()

    await act(async () => {
      ;(container.querySelector('[data-testid="save-module-mod_own"]') as HTMLButtonElement).click()
    })

    expect(fetchFn).toHaveBeenCalledWith(
      expect.stringMatching(/\/v1\/rooms\/us45-test-room\/modules\/mod_own\/save$/),
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Session-Id': SESSION_ID },
        body: JSON.stringify({ text: 'print("saved")\n' }),
      }),
    )
    expect(container.querySelector('[data-testid="dirty-dot-mod_own"]')).toBeNull()

    await act(async () => {
      controlSocket()?.emitJson({
        type: 'module-updated',
        ...modules[0],
        version: 1,
        dirty: true,
        deleted: false,
      })
    })
    expect(container.querySelector('[data-testid="dirty-dot-mod_own"]')).not.toBeNull()
  })

  it('shows Save for a writer and displays plain-words error when server refuses', async () => {
    const modules = [
      {
        module_id: 'mod_w',
        name: 'helper.py',
        owner_member_id: 'm_other',
        writers: ['m_me'],
        runnable: true,
        is_entry: false,
        version: 0,
        pending: false,
        dirty: false,
      },
    ]

    const fetchFn = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith('/modules') && (!init?.method || init.method === 'GET')) {
        return { ok: true, status: 200, json: async () => modules }
      }
      if (String(url).endsWith('/modules/mod_w/save')) {
        return {
          ok: false,
          status: 403,
          json: async () => ({ error: { code: 'NOT_MODULE_WRITER', message: 'Denied' } }),
        }
      }
      return { ok: true, status: 200, json: async () => ({}) }
    }) as unknown as typeof fetch

    const moduleDocs = new Map<string, Y.Doc>()
    moduleDocs.set('mod_w', new Y.Doc())

    await renderOpenRoom({
      myMemberId: 'm_me',
      role: 'participant',
      modules,
      moduleDocs,
      fetchFn,
    })

    await vi.waitFor(() => {
      expect(container.querySelector('[data-testid="save-module-mod_w"]')).not.toBeNull()
    })

    await act(async () => {
      ;(container.querySelector('[data-testid="save-module-mod_w"]') as HTMLButtonElement).click()
    })

    expect(container.querySelector('[data-testid="workspace-error"]')?.textContent).toBe(
      'Only the module owner or a writer can save this module.',
    )
  })
})
