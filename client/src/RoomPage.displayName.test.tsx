// @vitest-environment happy-dom
import { act } from 'react'
import type { RefObject } from 'react'
import { createRoot } from 'react-dom/client'
import type { Awareness } from 'y-protocols/awareness'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RoomPage from './RoomPage'
import { USER_COLORS } from './userColor'

vi.mock('@monaco-editor/react', () => ({
  default: () => <div data-testid="mock-monaco-editor" />,
}))

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('RoomPage display-name and colour presence', () => {
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
    delete (window as unknown as { awareness?: unknown }).awareness
  })

  it('requires a display name before rendering the room editor, then sets colour badge and awareness', async () => {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/room-demo')
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
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    expect(container.querySelector('[data-testid="mock-monaco-editor"]')).not.toBeNull()
    expect(container.textContent).toContain('You: Chandan')

    // US-05: check user colour dot in room header badge
    const badge = container.querySelector('[data-testid="room-user-badge"]')
    const dot = container.querySelector<HTMLElement>('[data-testid="room-user-color-dot"]')
    expect(badge).not.toBeNull()
    expect(dot).not.toBeNull()
    expect(dot?.style.backgroundColor).toBeTruthy()

    // US-05 DoD #5: check awareness exposed on window has name and colour
    const windowAwareness = (window as unknown as { awareness?: Awareness }).awareness
    expect(windowAwareness).toBeDefined()
    const localState = windowAwareness?.getLocalState() as { user?: { name: string; color: string } }
    expect(localState?.user?.name).toBe('Chandan')
    expect(localState?.user?.color).toBeTruthy()
  })

  it('calls onDisplayNameAccepted with both display name and assigned colour', async () => {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/room-demo')
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    let acceptedName = ''
    let acceptedColor = ''

    await act(async () => {
      root?.render(
        <RoomPage
          onDisplayNameAccepted={(name, color) => {
            acceptedName = name
            acceptedColor = color ?? ''
          }}
        />,
      )
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    await act(async () => {
      input.value = 'Alice'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    expect(acceptedName).toBe('Alice')
    expect(USER_COLORS).toContain(acceptedColor)
  })

  it('assigns a distinct colour when awareness already contains other participant colours', async () => {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/room-demo')
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    const awarenessRef: RefObject<Awareness | null> = { current: null }

    await act(async () => {
      root?.render(<RoomPage awarenessRef={awarenessRef} />)
    })

    // Simulate an existing peer already holding USER_COLORS[0]
    const awareness = awarenessRef.current
    expect(awareness).not.toBeNull()
    awareness!.states.set(999, {
      user: { name: 'Alice', color: USER_COLORS[0] },
    })

    const input = container.querySelector<HTMLInputElement>('#display-name')!
    const form = container.querySelector<HTMLFormElement>('form')!

    await act(async () => {
      input.value = 'Bob'
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    const localState = awareness!.getLocalState() as { user?: { name: string; color: string } }
    expect(localState.user?.name).toBe('Bob')
    // Must not collide with Alice's colour
    expect(localState.user?.color).not.toBe(USER_COLORS[0])
    expect(localState.user?.color).toBe(USER_COLORS[1])
  })

  it('cleans up window.awareness and destroys instance when unmounted', async () => {
    originalPathname = window.location.pathname
    window.history.replaceState({}, '', '/room/room-demo')
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    const awarenessRef: RefObject<Awareness | null> = { current: null }

    await act(async () => {
      root?.render(<RoomPage awarenessRef={awarenessRef} />)
    })

    const awareness = awarenessRef.current
    expect(awareness).not.toBeNull()
    expect((window as unknown as { awareness?: Awareness }).awareness).toBe(awareness)

    await act(async () => {
      root?.unmount()
      root = null
    })

    expect((window as unknown as { awareness?: Awareness }).awareness).toBeUndefined()
    expect(awarenessRef.current).toBeNull()
  })
})
