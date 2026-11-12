
// @vitest-environment happy-dom

import { act, useEffect, useRef } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'

const { joinRoomMock, createDocumentProviderMock } = vi.hoisted(() => ({
  joinRoomMock: vi.fn(),
  createDocumentProviderMock: vi.fn(),
}))

vi.mock('./roomConnection', () => ({
  joinRoom: joinRoomMock,
  createDocumentProvider: createDocumentProviderMock,
}))

vi.mock('./sessionId', () => ({
  getSessionId: () => 'test-session',
}))

vi.mock('@monaco-editor/react', () => ({
  default: function MockEditor({
    onMount,
  }: {
    onMount?: (editor: { getModel: () => object }) => void
  }) {
    const mounted = useRef(false)

    useEffect(() => {
      if (mounted.current) return
      mounted.current = true
      onMount?.({ getModel: () => ({}) })
    }, [onMount])

    return <div data-testid="mock-monaco-editor" />
  },
}))

vi.mock('y-monaco', () => ({
  MonacoBinding: class {
    destroy() {}
  },
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('RoomPage display-name gate and room connection', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  let originalPathname = '/'

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount())
      root = null
    }

    window.history.replaceState({}, '', originalPathname)
    container?.remove()
    container = null

    vi.clearAllMocks()
  })

  async function renderRoomAt(pathname: string) {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', pathname)

    container = document.createElement('div')
    document.body.appendChild(container)

    root = createRoot(container)

    await act(async () => {
      root?.render(<RoomPage />)
    })
  }

  function submitDisplayName(name: string) {
    const input = container!.querySelector<HTMLInputElement>('#display-name')!
    const form = container!.querySelector<HTMLFormElement>('form')!

    input.value = name
    form.dispatchEvent(
      new Event('submit', {
        bubbles: true,
        cancelable: true,
      }),
    )
  }

  function createConnectedProvider() {
    const provider = {
      awareness: {
        setLocalStateField: vi.fn(),
        getLocalState: vi.fn(() => ({
          user: { color: '#3B82F6' },
        })),
      },
      on: vi.fn((event, callback) => {
        if (event === 'status') {
          callback({ status: 'connected' })
        }
      }),
      destroy: vi.fn(),
    }

    return provider
  }

  it('requires a display name before showing the room editor', async () => {
    await renderRoomAt('/room/room-demo')

    expect(container!.textContent).toContain('Choose your display name')
    expect(
      container!.querySelector('[data-testid="mock-monaco-editor"]'),
    ).toBeNull()

    expect(joinRoomMock).not.toHaveBeenCalled()
  })

  it('keeps the editor hidden until the server accepts the room join', async () => {
    let resolveJoin!: (value: {
      socket: WebSocket
      joined: { type: 'joined' }
    }) => void

    const joinPromise = new Promise<{
      socket: WebSocket
      joined: { type: 'joined' }
    }>((resolve) => {
      resolveJoin = resolve
    })

    const socket = {
      close: vi.fn(),
    } as unknown as WebSocket

    const provider = createConnectedProvider()

    joinRoomMock.mockReturnValue(joinPromise)
    createDocumentProviderMock.mockReturnValue(provider)

    await renderRoomAt('/room/room-demo')

    await act(async () => {
      submitDisplayName('Chandan')
    })

    expect(joinRoomMock).toHaveBeenCalledWith(
      'room-demo',
      'Chandan',
      'test-session',
      expect.objectContaining({
        signal: expect.any(AbortSignal),
      }),
    )

    const editor = container!.querySelector(
      '[data-testid="mock-monaco-editor"]',
    )

    expect(editor).not.toBeNull()
    expect(editor!.parentElement?.getAttribute('style')).toContain(
      'visibility: hidden',
    )

    expect(container!.textContent).toContain('Connecting to room...')

    await act(async () => {
      resolveJoin({
        socket,
        joined: { type: 'joined' },
      })

      await joinPromise
    })

    expect(createDocumentProviderMock).toHaveBeenCalled()
    expect(provider.awareness.setLocalStateField).toHaveBeenCalledWith(
      'user',
      expect.objectContaining({
        name: 'Chandan',
      }),
    )

    expect(
      container!.querySelector('[data-testid="mock-monaco-editor"]'),
    ).not.toBeNull()

    expect(
      container!.querySelector('[data-testid="mock-monaco-editor"]')
        ?.parentElement?.getAttribute('style'),
    ).toContain('visibility: visible')

    expect(container!.textContent).toContain('You: Chandan')
  })

  it('shows an invalid-link error when the room ID is missing', async () => {
    await renderRoomAt('/room/')

    expect(container!.textContent).toContain('Invalid room link')
    expect(container!.querySelector('[role="alert"]')).not.toBeNull()
    expect(joinRoomMock).not.toHaveBeenCalled()
  })

  it('rejects room IDs containing invalid characters', async () => {
    await renderRoomAt('/room/invalid.room')

    expect(container!.textContent).toContain('Invalid room link')
    expect(joinRoomMock).not.toHaveBeenCalled()
  })

  it('rejects room IDs longer than 64 characters', async () => {
    await renderRoomAt(`/room/${'a'.repeat(65)}`)

    expect(container!.textContent).toContain('Invalid room link')
    expect(joinRoomMock).not.toHaveBeenCalled()
  })

  it('cancels a pending room join when the component unmounts', async () => {
    let capturedSignal: AbortSignal | undefined

    joinRoomMock.mockImplementation(
      (_roomId, _displayName, _sessionId, options) => {
        capturedSignal = options?.signal
        return new Promise(() => {})
      },
    )

    await renderRoomAt('/room/room-demo')

    await act(async () => {
      submitDisplayName('Chandan')
    })

    expect(joinRoomMock).toHaveBeenCalled()
    expect(capturedSignal).toBeDefined()
    expect(capturedSignal?.aborted).toBe(false)

    await act(async () => {
      root?.unmount()
    })

    root = null

    expect(capturedSignal?.aborted).toBe(true)
  })
})
