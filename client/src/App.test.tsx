// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./CreateRoomScreen', () => ({
  default: () => <div data-testid="create-room-screen" />,
}))
vi.mock('./RoomPage', () => ({
  default: () => <div data-testid="room-page" />,
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('App routing', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null
  const originalPathname = window.location.pathname

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount())
      root = null
    }
    window.history.replaceState({}, '', originalPathname)
    container?.remove()
    container = null
  })

  async function renderAt(path: string) {
    window.history.replaceState({}, '', path)
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
      root?.render(<App />)
    })
  }

  it('shows the create-room screen at exactly "/"', async () => {
    await renderAt('/')
    expect(container?.querySelector('[data-testid="create-room-screen"]')).not.toBeNull()
    expect(container?.querySelector('[data-testid="room-page"]')).toBeNull()
  })

  it('shows the room page for a room link', async () => {
    await renderAt('/room/room-demo')
    expect(container?.querySelector('[data-testid="room-page"]')).not.toBeNull()
  })

  it('leaves other paths to the room page, which shows its invalid-link message', async () => {
    await renderAt('/invalid-room')
    expect(container?.querySelector('[data-testid="room-page"]')).not.toBeNull()
    expect(container?.querySelector('[data-testid="create-room-screen"]')).toBeNull()
  })
})
