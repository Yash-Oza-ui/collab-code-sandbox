// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CreateRoomScreen from './CreateRoomScreen'
import type { CreatedRoom } from './createRoom'

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const ORIGIN = 'http://localhost:3000'
const ROOM_ID = 'k3Yc1t9Qx7mBvA2nR0sLpQ'
const LINK = `${ORIGIN}/room/${ROOM_ID}`

describe('CreateRoomScreen', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount())
      root = null
    }
    container?.remove()
    container = null
    Reflect.deleteProperty(window.navigator, 'clipboard')
  })

  async function renderScreen(createRoomFn: () => Promise<CreatedRoom>) {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
      root?.render(<CreateRoomScreen createRoomFn={createRoomFn} origin={ORIGIN} />)
    })
  }

  function button(label: string): HTMLButtonElement {
    const found = Array.from(container?.querySelectorAll('button') ?? []).find(
      (candidate) => candidate.textContent === label,
    )
    if (!found) throw new Error(`No button labelled "${label}"`)
    return found
  }

  async function createOk(ownerTokenStored = true) {
    const createRoomFn = vi
      .fn()
      .mockResolvedValue({ roomId: ROOM_ID, ownerTokenStored })
    await renderScreen(createRoomFn)
    await act(async () => {
      button('Create room').click()
    })
    return createRoomFn
  }

  it('shows the create button and no link at first', async () => {
    await renderScreen(vi.fn())
    expect(button('Create room').disabled).toBe(false)
    expect(container?.querySelector('input')).toBeNull()
  })

  it('creates a room and shows the share link and an open link', async () => {
    const createRoomFn = await createOk()
    expect(createRoomFn).toHaveBeenCalledTimes(1)
    const input = container?.querySelector('input') as HTMLInputElement
    expect(input.value).toBe(LINK)
    const open = container?.querySelector('a') as HTMLAnchorElement
    expect(open.getAttribute('href')).toBe(LINK)
  })

  it('disables the button while the room is being created', async () => {
    let resolveCreate!: (room: CreatedRoom) => void
    const pending = new Promise<CreatedRoom>((resolve) => {
      resolveCreate = resolve
    })
    await renderScreen(vi.fn().mockReturnValue(pending))
    await act(async () => {
      button('Create room').click()
    })
    expect(button('Creating room...').disabled).toBe(true)
    await act(async () => {
      resolveCreate({ roomId: ROOM_ID, ownerTokenStored: true })
      await pending
    })
    expect(container?.querySelector('input')).not.toBeNull()
  })

  it('shows the error and lets the user try again when creation fails', async () => {
    await renderScreen(
      vi.fn().mockRejectedValue(new Error('Unable to reach the room server.')),
    )
    await act(async () => {
      button('Create room').click()
    })
    expect(container?.querySelector('[role="alert"]')?.textContent).toBe(
      'Unable to reach the room server.',
    )
    expect(button('Create room').disabled).toBe(false)
    expect(container?.querySelector('input')).toBeNull()
  })

  it('warns when the owner token could not be stored', async () => {
    await createOk(false)
    expect(container?.textContent).toContain('could not save the owner token')
  })

  it('copies the link to the clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })
    await createOk()
    await act(async () => {
      button('Copy link').click()
    })
    expect(writeText).toHaveBeenCalledWith(LINK)
    expect(container?.textContent).toContain('Link copied.')
  })

  it('tells the user to copy manually when the clipboard is blocked', async () => {
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
      configurable: true,
    })
    await createOk()
    await act(async () => {
      button('Copy link').click()
    })
    expect(container?.textContent).toContain('copy it manually')
  })
})
