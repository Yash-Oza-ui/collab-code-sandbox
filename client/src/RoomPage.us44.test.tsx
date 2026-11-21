// @vitest-environment happy-dom
/* eslint-disable @typescript-eslint/no-explicit-any */
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Y from 'yjs'
import RoomPage from './RoomPage'

const activeBindings: Array<{ ytext: Y.Text; model: any }> = []

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
    destroy: () => void
    constructor(ytext: Y.Text, model: any) {
      activeBindings.push({ ytext, model })
      this.destroy = () => {
        const idx = activeBindings.findIndex((b) => b.ytext === ytext)
        if (idx >= 0) activeBindings.splice(idx, 1)
      }
    }
  },
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

class MockWs {
  static instances: MockWs[] = []
  static joinRole: 'participant' | 'observer' = 'participant'
  static initialModules: Array<Record<string, unknown>> = []
  readyState = 0
  binaryType = 'arraybuffer'
  url: string
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onclose: ((e: CloseEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null

  constructor(url: string) {
    this.url = url
    MockWs.instances.push(this)
    queueMicrotask(() => {
      this.readyState = 1
      this.onopen?.(new Event('open'))
    })
  }

  send(data: string | ArrayBuffer | Uint8Array) {
    if (typeof data !== 'string') return
    const parsed = JSON.parse(data)
    if (parsed.type === 'join') {
      setTimeout(() => {
        this.emitJson({
          type: 'joined',
          member_id: 'm_reader',
          role: MockWs.joinRole,
          mode: 'open',
          preset: null,
          language: 'python',
          permissions: { can_run: MockWs.joinRole !== 'observer' },
        })
        this.emitJson({
          type: 'roster',
          members: [
            {
              member_id: 'm_owner',
              display_name: 'Owner',
              role: 'host',
              permissions: { can_run: true },
            },
            {
              member_id: 'm_reader',
              display_name: parsed.display_name,
              role: MockWs.joinRole,
              permissions: { can_run: MockWs.joinRole !== 'observer' },
            },
          ],
        })
      }, 10)
    }
  }

  emitJson(payload: unknown) {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(payload) }))
  }

  close() {
    this.readyState = 3
    this.onclose?.(new CloseEvent('close', { code: 1000 }))
  }
}

describe('RoomPage US-44: Read every module live', () => {
  let container: HTMLDivElement
  let root: Root | null = null

  beforeEach(() => {
    MockWs.instances = []
    MockWs.joinRole = 'participant'
    MockWs.initialModules = []
    activeBindings.length = 0
    container = document.createElement('div')
    document.body.appendChild(container)
    window.history.pushState({}, '', '/room/us44-test-room')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async (url: string) => {
        if (String(url).includes('/modules')) {
          return { ok: true, status: 200, json: async () => MockWs.initialModules }
        }
        return { ok: true, status: 200, json: async () => ({}) }
      }),
    )
  })

  afterEach(async () => {
    if (root) {
      await act(async () => {
        root?.unmount()
      })
      root = null
    }
    container.remove()
    localStorage.clear()
    sessionStorage.clear()
    vi.unstubAllGlobals()
  })

  function submitDisplayName(name: string) {
    const input = container.querySelector('input') as HTMLInputElement
    const button = container.querySelector('button') as HTMLButtonElement
    act(() => {
      input.value = name
      input.dispatchEvent(new Event('input', { bubbles: true }))
      button.click()
    })
  }

  it('shows live edits in read-only mode for a non-writer participant in Open mode', async () => {
    MockWs.joinRole = 'participant'
    MockWs.initialModules = [
      {
        module_id: 'mod_a',
        name: 'main.py',
        owner_member_id: 'm_owner',
        writers: [],
        is_entry: true,
        pending: false,
      },
    ]

    const moduleDocs = new Map<string, Y.Doc>()
    const docA = new Y.Doc()
    docA.getText('monaco').insert(0, 'initial-a')
    moduleDocs.set('mod_a', docA)

    root = createRoot(container)
    act(() => {
      root?.render(
        <RoomPage
          moduleDocs={moduleDocs}
          wsOptions={{ WebSocketClass: MockWs as unknown as typeof WebSocket }}
        />,
      )
    })

    submitDisplayName('Reader')

    await vi.waitFor(() => {
      expect(container.querySelector('[data-testid="room-edit-status-badge"]')?.textContent).toBe('Read only')
      expect(activeBindings.at(-1)?.ytext.toString()).toBe('initial-a')
    })

    expect(container.querySelector('[data-testid="mock-monaco"]')?.getAttribute('data-readonly')).toBe('true')

    act(() => {
      docA.getText('monaco').insert(docA.getText('monaco').length, '\nprint("live")')
    })

    await vi.waitFor(() => {
      expect(activeBindings.at(-1)?.ytext.toString()).toBe('initial-a\nprint("live")')
    })
  })

  it('lets an Observer switch across all modules, see new modules from module-updated, and resync on reconnect', async () => {
    MockWs.joinRole = 'observer'
    MockWs.initialModules = [
      {
        module_id: 'mod_a',
        name: 'main.py',
        owner_member_id: 'm_owner',
        writers: [],
        is_entry: true,
        pending: false,
      },
      {
        module_id: 'mod_b',
        name: 'utils.py',
        owner_member_id: 'm_owner',
        writers: [],
        is_entry: false,
        pending: false,
      },
    ]

    const moduleDocs = new Map<string, Y.Doc>()
    const docA = new Y.Doc()
    const docB = new Y.Doc()
    docA.getText('monaco').insert(0, 'alpha')
    docB.getText('monaco').insert(0, 'beta')
    moduleDocs.set('mod_a', docA)
    moduleDocs.set('mod_b', docB)

    root = createRoot(container)
    act(() => {
      root?.render(
        <RoomPage
          moduleDocs={moduleDocs}
          wsOptions={{ WebSocketClass: MockWs as unknown as typeof WebSocket }}
        />,
      )
    })

    submitDisplayName('Obs')

    await vi.waitFor(() => {
      expect(activeBindings.at(-1)?.ytext.toString()).toBe('alpha')
    })
    expect(container.querySelector('[data-testid="mock-monaco"]')?.getAttribute('data-readonly')).toBe('true')

    const utilsBtn = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('utils.py'),
    )
    expect(utilsBtn).toBeTruthy()
    act(() => {
      utilsBtn?.click()
    })

    await vi.waitFor(() => {
      expect(activeBindings.at(-1)?.ytext.toString()).toBe('beta')
    })
    expect(container.querySelector('[data-testid="mock-monaco"]')?.getAttribute('data-readonly')).toBe('true')

    const controlWs = MockWs.instances.find((ws) => !ws.url.includes('/docs/'))
    expect(controlWs).toBeTruthy()
    act(() => {
      controlWs?.emitJson({
        type: 'module-updated',
        module_id: 'mod_c',
        name: 'helper.py',
        owner_member_id: 'm_owner',
        writers: [],
        is_entry: false,
        pending: false,
        deleted: false,
      })
    })

    await vi.waitFor(() => {
      expect(container.textContent).toContain('helper.py')
    })

    act(() => {
      docB.getText('monaco').insert(docB.getText('monaco').length, '-resynced')
    })

    await vi.waitFor(() => {
      expect(activeBindings.at(-1)?.ytext.toString()).toBe('beta-resynced')
    })
  })
})