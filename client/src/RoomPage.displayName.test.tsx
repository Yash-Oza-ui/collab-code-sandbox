
// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'


vi.mock('./roomConnection', () => ({
  joinRoom: vi.fn(),
  createDocumentProvider: vi.fn(),
}))

vi.mock('@monaco-editor/react', () => ({
  default: () => <div data-testid="mock-monaco-editor" />,
}))

vi.mock('y-monaco', () => ({
  MonacoBinding: class {
    destroy() {}
  },
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('RoomPage display-name gate and invalid room links', () => {
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

  it('requires a display name before rendering the room editor', async () => {
    await renderRoomAt('/room/room-demo')

    expect(container!.textContent).toContain('Choose your display name')
    expect(
      container!.querySelector('[data-testid="mock-monaco-editor"]'),
    ).toBeNull()

    const input = container!.querySelector<HTMLInputElement>('#display-name')!
    const form = container!.querySelector<HTMLFormElement>('form')!

    await act(async () => {
      input.value = 'Chandan'
      form.dispatchEvent(
        new Event('submit', { bubbles: true, cancelable: true }),
      )
    })

    expect(
      container!.querySelector('[data-testid="mock-monaco-editor"]'),
    ).not.toBeNull()
    expect(container!.textContent).toContain('You: Chandan')
  })

  it('shows an invalid-link error when the room ID is missing', async () => {
    await renderRoomAt('/room/')

    expect(container!.textContent).toContain('Invalid room link')
    expect(container!.textContent).not.toContain('Connecting to room...')
    expect(container!.querySelector('[role="alert"]')).not.toBeNull()
  })

  it('shows an invalid-link error when the room ID contains invalid characters', async () => {
    await renderRoomAt('/room/invalid.room')

    expect(container!.textContent).toContain('Invalid room link')
    expect(container!.textContent).not.toContain('Connecting to room...')
  })

  it('shows an invalid-link error when the room ID exceeds 64 characters', async () => {
    const longRoomId = 'a'.repeat(65)

    await renderRoomAt(`/room/${longRoomId}`)

    expect(container!.textContent).toContain('Invalid room link')
    expect(container!.textContent).not.toContain('Connecting to room...')
  })
})
