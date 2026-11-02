// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DisplayNamePrompt from './DisplayNamePrompt'

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('DisplayNamePrompt', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount())
      root = null
    }
    container?.remove()
    container = null
  })

  it('renders a name prompt and submits the trimmed name', async () => {
    const onSubmit = vi.fn()
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<DisplayNamePrompt onSubmit={onSubmit} />)
    })

    expect(container.textContent).toContain('Choose your display name')
    const input = container.querySelector<HTMLInputElement>('#display-name')
    const form = container.querySelector<HTMLFormElement>('form')
    expect(input).not.toBeNull()
    expect(form).not.toBeNull()

    await act(async () => {
      input!.value = '  Alice  '
      form!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    expect(onSubmit).toHaveBeenCalledWith('Alice')
  })

  it('does not submit an empty name and displays a validation message', async () => {
    const onSubmit = vi.fn()
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<DisplayNamePrompt onSubmit={onSubmit} />)
    })

    const form = container.querySelector<HTMLFormElement>('form')!
    await act(async () => {
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    expect(onSubmit).not.toHaveBeenCalled()
    expect(container.textContent).toContain('Display name cannot be empty.')
  })
})
