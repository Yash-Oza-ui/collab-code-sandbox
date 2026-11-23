// @vitest-environment happy-dom
/* eslint-disable @typescript-eslint/no-explicit-any */
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Y from 'yjs'
import RoomPage from './RoomPage'
import { readZipEntries, EXPORT_ZIP_FILENAME } from './exportZip'

const activeBindings: Array<{ ytext: Y.Text; model: any }> = []

vi.mock('@monaco-editor/react', async () => {
  const React = await import('react')
  return {
    default: function MockEditor({ options, onMount }: any) {
      React.useEffect(() => {
        onMount?.({
          getModel: () => ({
            getPositionAt: (offset: number) => ({ lineNumber: 1, column: offset + 1 }),
          }),
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
  static joinRole: 'host' | 'participant' | 'observer' = 'participant'
  static joinMode: 'pair' | 'open' = 'pair'
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
          member_id: 'm_test_user',
          role: MockWs.joinRole,
          mode: MockWs.joinMode,
          preset: null,
          language: 'python',
          permissions: { can_run: MockWs.joinRole !== 'observer' },
        })
        this.emitJson({
          type: 'roster',
          members: [
            {
              member_id: 'm_test_user',
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

describe('RoomPage US-57: Export room files as ZIP', () => {
  let container: HTMLDivElement
  let root: Root | null = null
  let downloadedBlob: Blob | null = null
  let downloadedFilename: string | null = null

  beforeEach(() => {
    downloadedBlob = null
    downloadedFilename = null
    MockWs.instances = []
    MockWs.joinRole = 'participant'
    MockWs.joinMode = 'pair'
    MockWs.initialModules = []
    activeBindings.length = 0

    // Spy on URL.createObjectURL and revokeObjectURL
    URL.createObjectURL = vi.fn((blob: Blob) => {
      downloadedBlob = blob
      return 'blob:mock-zip-url'
    })
    URL.revokeObjectURL = vi.fn()

    // Intercept download triggers on DOM anchor elements
    const origCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tagName: string, options?: any) => {
      const el = origCreateElement(tagName, options)
      if (tagName.toLowerCase() === 'a') {
        el.click = () => {
          downloadedFilename = (el as HTMLAnchorElement).download
        }
      }
      return el
    })

    container = document.createElement('div')
    document.body.appendChild(container)
    window.history.pushState({}, '', '/room/us57-export-room')

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
    vi.restoreAllMocks()
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

  it('does not show Export button before joining the room', () => {
    root = createRoot(container)
    act(() => {
      root?.render(
        <RoomPage
          wsOptions={{
            WebSocketClass: MockWs as unknown as typeof WebSocket,
            syncWsUrl: 'ws://localhost:8080',
          }}
        />,
      )
    })

    const exportBtn = container.querySelector('[data-testid="room-export-button"]')
    expect(exportBtn).toBeNull()
  })

  it('exports single file as main.py with exact text in Pair mode', async () => {
    MockWs.joinMode = 'pair'
    MockWs.joinRole = 'participant'

    const docRef = { current: null as Y.Doc | null }

    root = createRoot(container)
    act(() => {
      root?.render(
        <RoomPage
          docRef={docRef}
          wsOptions={{
            WebSocketClass: MockWs as unknown as typeof WebSocket,
            syncWsUrl: 'ws://localhost:8080',
          }}
        />,
      )
    })

    submitDisplayName('Coder')
    await act(async () => {
      await new Promise((r) => setTimeout(r, 60))
    })

    act(() => {
      docRef.current?.getText('monaco').insert(0, 'def solution():\n    return 42\n')
    })

    const exportBtn = container.querySelector('[data-testid="room-export-button"]') as HTMLButtonElement
    expect(exportBtn).not.toBeNull()
    expect(exportBtn.disabled).toBe(false)

    await act(async () => {
      exportBtn.click()
    })

    expect(downloadedFilename).toBe(EXPORT_ZIP_FILENAME)
    expect(downloadedBlob).not.toBeNull()

    const arrayBuffer = await downloadedBlob!.arrayBuffer()
    const entries = readZipEntries(new Uint8Array(arrayBuffer))
    expect(entries).toHaveLength(1)
    expect(entries[0].name).toBe('main.py')
    expect(entries[0].content).toBe('def solution():\n    return 42\n')
  })

  it('exports all approved modules under module file names in Open mode, excluding pending modules', async () => {
    MockWs.joinMode = 'open'
    MockWs.joinRole = 'participant'
    MockWs.initialModules = [
      {
        module_id: 'mod_entry',
        name: 'app.py',
        owner_member_id: 'm_owner',
        writers: [],
        is_entry: true,
        pending: false,
      },
      {
        module_id: 'mod_math',
        name: 'math_utils.py',
        owner_member_id: 'm_owner',
        writers: [],
        is_entry: false,
        pending: false,
      },
      {
        module_id: 'mod_pending',
        name: 'unapproved.py',
        owner_member_id: 'm_untrusted',
        writers: [],
        is_entry: false,
        pending: true,
      },
    ]

    const moduleDocs = new Map<string, Y.Doc>()
    const docEntry = new Y.Doc()
    docEntry.getText('monaco').insert(0, 'from math_utils import add\nprint(add(2, 3))\n')
    moduleDocs.set('mod_entry', docEntry)

    const docMath = new Y.Doc()
    docMath.getText('monaco').insert(0, 'def add(a, b):\n    return a + b\n')
    moduleDocs.set('mod_math', docMath)

    const docPending = new Y.Doc()
    docPending.getText('monaco').insert(0, 'malicious_code()')
    moduleDocs.set('mod_pending', docPending)

    root = createRoot(container)
    act(() => {
      root?.render(
        <RoomPage
          moduleDocs={moduleDocs}
          wsOptions={{
            WebSocketClass: MockWs as unknown as typeof WebSocket,
            syncWsUrl: 'ws://localhost:8080',
          }}
        />,
      )
    })

    submitDisplayName('Dev')
    await act(async () => {
      await new Promise((r) => setTimeout(r, 60))
    })

    const note = container.querySelector('[data-testid="room-export-note"]')
    expect(note).not.toBeNull()
    expect(note?.textContent).toContain('Exports live text; saved version may be older')

    const exportBtn = container.querySelector('[data-testid="room-export-button"]') as HTMLButtonElement
    expect(exportBtn).not.toBeNull()
    expect(exportBtn.disabled).toBe(false)

    await act(async () => {
      exportBtn.click()
    })

    expect(downloadedFilename).toBe('room-export.zip')
    expect(downloadedBlob).not.toBeNull()

    const arrayBuffer = await downloadedBlob!.arrayBuffer()
    const entries = readZipEntries(new Uint8Array(arrayBuffer))
    expect(entries).toHaveLength(2)

    const entryNames = entries.map((e) => e.name)
    expect(entryNames).toContain('app.py')
    expect(entryNames).toContain('math_utils.py')
    expect(entryNames).not.toContain('unapproved.py')

    const appEntry = entries.find((e) => e.name === 'app.py')
    expect(appEntry?.content).toBe('from math_utils import add\nprint(add(2, 3))\n')

    const mathEntry = entries.find((e) => e.name === 'math_utils.py')
    expect(mathEntry?.content).toBe('def add(a, b):\n    return a + b\n')
  })

  it('disables export action in Open mode when workspace has 0 modules and shows message', async () => {
    MockWs.joinMode = 'open'
    MockWs.joinRole = 'participant'
    MockWs.initialModules = [] // Empty workspace

    root = createRoot(container)
    act(() => {
      root?.render(
        <RoomPage
          wsOptions={{
            WebSocketClass: MockWs as unknown as typeof WebSocket,
            syncWsUrl: 'ws://localhost:8080',
          }}
        />,
      )
    })

    submitDisplayName('Dev')
    await act(async () => {
      await new Promise((r) => setTimeout(r, 60))
    })

    const exportBtn = container.querySelector('[data-testid="room-export-button"]') as HTMLButtonElement
    expect(exportBtn).not.toBeNull()
    expect(exportBtn.disabled).toBe(true)
    expect(exportBtn.title).toBe('No modules to export')

    const note = container.querySelector('[data-testid="room-export-note"]')
    expect(note).not.toBeNull()
    expect(note?.textContent).toBe('No modules to export')

    // Clicking disabled button produces no download
    await act(async () => {
      exportBtn.click()
    })
    expect(downloadedBlob).toBeNull()
  })

  it('allows an Observer in the room to export the work', async () => {
    MockWs.joinMode = 'pair'
    MockWs.joinRole = 'observer'

    const docRef = { current: null as Y.Doc | null }

    root = createRoot(container)
    act(() => {
      root?.render(
        <RoomPage
          docRef={docRef}
          wsOptions={{
            WebSocketClass: MockWs as unknown as typeof WebSocket,
            syncWsUrl: 'ws://localhost:8080',
          }}
        />,
      )
    })

    submitDisplayName('Auditor')
    await act(async () => {
      await new Promise((r) => setTimeout(r, 60))
    })

    act(() => {
      docRef.current?.getText('monaco').insert(0, '# observer snapshot')
    })

    const observerBadge = container.querySelector('[data-testid="room-observer-badge"]')
    expect(observerBadge).not.toBeNull()

    const exportBtn = container.querySelector('[data-testid="room-export-button"]') as HTMLButtonElement
    expect(exportBtn).not.toBeNull()
    expect(exportBtn.disabled).toBe(false)

    await act(async () => {
      exportBtn.click()
    })

    expect(downloadedFilename).toBe('room-export.zip')
    expect(downloadedBlob).not.toBeNull()
    const arrayBuffer = await downloadedBlob!.arrayBuffer()
    const entries = readZipEntries(new Uint8Array(arrayBuffer))
    expect(entries[0].content).toBe('# observer snapshot')
  })

  it('ensures in-flight concurrent edits do not alter the exported zip snapshot', async () => {
    MockWs.joinMode = 'pair'
    MockWs.joinRole = 'participant'

    const docRef = { current: null as Y.Doc | null }

    root = createRoot(container)
    act(() => {
      root?.render(
        <RoomPage
          docRef={docRef}
          wsOptions={{
            WebSocketClass: MockWs as unknown as typeof WebSocket,
            syncWsUrl: 'ws://localhost:8080',
          }}
        />,
      )
    })

    submitDisplayName('Dev')
    await act(async () => {
      await new Promise((r) => setTimeout(r, 60))
    })

    act(() => {
      docRef.current?.getText('monaco').insert(0, 'original version')
    })

    const exportBtn = container.querySelector('[data-testid="room-export-button"]') as HTMLButtonElement

    await act(async () => {
      exportBtn.click()
    })

    // Now someone types more text immediately afterwards
    act(() => {
      docRef.current?.getText('monaco').insert(16, ' -- and concurrent edits')
    })

    const arrayBuffer = await downloadedBlob!.arrayBuffer()
    const entries = readZipEntries(new Uint8Array(arrayBuffer))
    expect(entries[0].content).toBe('original version')
  })
})
