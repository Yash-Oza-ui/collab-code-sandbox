
// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'

const { joinRoomMock, createDocumentProviderMock } = vi.hoisted(() => ({
  joinRoomMock: vi.fn(),
  createDocumentProviderMock: vi.fn(),
}))

vi.mock('./roomConnection', () => ({
  getSessionId: () => 'test-session',
  joinRoom: joinRoomMock,
  createDocumentProvider: createDocumentProviderMock,
}))

vi.mock('@monaco-editor/react', () => ({
  default: () => <div data-testid="mock-monaco-editor" />,
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('RoomPage display-name and join gate', () => {
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

  it('shows the editor only after the server accepts the room join', async () => {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/room-demo')

    let resolveJoin!: (value: { socket: WebSocket; joined: { type: 'joined' } }) => void

    const joinPromise = new Promise<{ socket: WebSocket; joined: { type: 'joined' } }>(
      (resolve) => {
        resolveJoin = resolve
      },
    )

    const socket = { close: vi.fn() } as unknown as WebSocket
    const provider = {
      awareness: {},
      on: vi.fn(),
      destroy: vi.fn(),
    }

    joinRoomMock.mockReturnValue(joinPromise)
    createDocumentProviderMock.mockReturnValue(provider)

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RoomPage />)
    })

    expect(container.textContent).toContain('Choose your display name')
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    await act(async () => {
      input.value = 'Chandan'
      form.dispatchEvent(new Event('submit', {
        bubbles: true,
        cancelable: true,
      }))
    })

    expect(joinRoomMock).toHaveBeenCalledWith(
      'room-demo',
      'Chandan',
      'test-session',
    )

    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()
    expect(container.textContent).toContain('Joining room...')

    await act(async () => {
      resolveJoin({
        socket,
        joined: { type: 'joined' },
      })
      await joinPromise
    })

    expect(createDocumentProviderMock).toHaveBeenCalled()
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).not.toBeNull()
    expect(container.textContent).toContain('You: Chandan')
  })

  it('rejects an invalid room URL without showing the editor', async () => {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/invalid-room')

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RoomPage />)
    })

    expect(container.textContent).toContain('Invalid room link')
    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).toBeNull()
    expect(joinRoomMock).not.toHaveBeenCalled()
  })
})
