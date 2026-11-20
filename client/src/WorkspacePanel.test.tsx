// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkspacePanel from './WorkspacePanel'
import type { WorkspaceModule } from './workspaceApi'

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('WorkspacePanel', () => {
  let container: HTMLDivElement

  beforeEach(() => {
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    document.body.removeChild(container)
  })

  const sampleModules: WorkspaceModule[] = [
    { module_id: 'mod_1', name: 'main.py', owner_member_id: 'm_1', is_entry: true, pending: false },
    { module_id: 'mod_2', name: 'solver.py', owner_member_id: 'm_2', is_entry: false, pending: false },
    { module_id: 'mod_3', name: 'draft.py', owner_member_id: 'm_3', is_entry: false, pending: true },
  ]

  it('renders workspace title, language badge, and module list', async () => {
    const onSelect = vi.fn()
    const root = createRoot(container)

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          language="python"
          modules={sampleModules}
          activeModuleId="mod_1"
          onSelectModule={onSelect}
        />,
      )
    })

    const title = container.querySelector('[data-testid="workspace-title"]')
    expect(title?.textContent).toContain('Workspace (3)')

    const langBadge = container.querySelector('[data-testid="workspace-language"]')
    expect(langBadge?.textContent).toBe('python')

    const moduleItems = container.querySelectorAll('[data-testid^="module-mod_"]')
    expect(moduleItems.length).toBe(3)

    // Checks entry and pending badges
    expect(container.querySelector('[data-testid="entry-badge-mod_1"]')).not.toBeNull()
    expect(container.querySelector('[data-testid="pending-badge-mod_3"]')).not.toBeNull()

    // Active item has active class
    const activeItem = container.querySelector('[data-testid="module-mod_1"]')
    expect(activeItem?.className).toContain('active')

    act(() => {
      root.unmount()
    })
  })

  it('calls onSelectModule when a module is clicked', async () => {
    const onSelect = vi.fn()
    const root = createRoot(container)

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          language="python"
          modules={sampleModules}
          activeModuleId="mod_1"
          onSelectModule={onSelect}
        />,
      )
    })

    const button = container.querySelector('[data-testid="select-module-mod_2"]') as HTMLButtonElement
    expect(button).not.toBeNull()

    act(() => {
      button.click()
    })

    expect(onSelect).toHaveBeenCalledWith('mod_2')

    act(() => {
      root.unmount()
    })
  })

  it('renders module creation form for Host, hides it for non-Host', async () => {
    const root = createRoot(container)

    // Non-host: no creation form
    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          language="python"
          modules={sampleModules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
        />,
      )
    })

    expect(container.querySelector('[data-testid="create-module-form"]')).toBeNull()

    // Host: shows creation form
    const onCreate = vi.fn()
    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={true}
          language="python"
          modules={sampleModules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onCreateModule={onCreate}
        />,
      )
    })

    const form = container.querySelector('[data-testid="create-module-form"]')
    expect(form).not.toBeNull()

    const input = container.querySelector('[data-testid="new-module-name-input"]') as HTMLInputElement
    expect(input).not.toBeNull()
    expect(input.placeholder).toContain('main.py')

    act(() => {
      input.value = 'helper.py'
      input.dispatchEvent(new Event('input', { bubbles: true }))
      form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    expect(onCreate).toHaveBeenCalledWith('helper.py')

    act(() => {
      root.unmount()
    })
  })

  it('displays creation error message when provided', async () => {
    const root = createRoot(container)

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={true}
          language="python"
          modules={sampleModules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onCreateModule={vi.fn()}
          creationError="Module name 'main.py' is already taken."
        />,
      )
    })

    const errorEl = container.querySelector('[data-testid="workspace-error"]')
    expect(errorEl?.textContent).toContain("Module name 'main.py' is already taken.")

    act(() => {
      root.unmount()
    })
  })
})
