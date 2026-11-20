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

  it('renders owner labels for each module in the list (US-40, FR21.3)', async () => {
    const root = createRoot(container)
    const roster = [
      { member_id: 'm_1', display_name: 'Alice', role: 'host' },
      { member_id: 'm_2', display_name: 'Bob', role: 'participant' },
      { member_id: 'm_3', display_name: 'Charlie', role: 'participant' },
    ]

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          language="python"
          modules={sampleModules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          roster={roster}
        />,
      )
    })

    const owner1 = container.querySelector('[data-testid="owner-label-mod_1"]')
    expect(owner1?.textContent).toBe('Owner: Alice')

    const owner2 = container.querySelector('[data-testid="owner-label-mod_2"]')
    expect(owner2?.textContent).toBe('Owner: Bob')

    act(() => {
      root.unmount()
    })
  })

  it('renders request module form for participant and calls onRequestModule (US-40, FR21.1)', async () => {
    const root = createRoot(container)
    const onRequest = vi.fn()

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          isObserver={false}
          language="python"
          modules={sampleModules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onRequestModule={onRequest}
        />,
      )
    })

    const form = container.querySelector('[data-testid="request-module-form"]')
    expect(form).not.toBeNull()

    const input = container.querySelector('[data-testid="request-module-input"]') as HTMLInputElement
    expect(input).not.toBeNull()
    expect(input.placeholder).toContain('solver.py')

    act(() => {
      input.value = 'my_task.py'
      input.dispatchEvent(new Event('input', { bubbles: true }))
      form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    expect(onRequest).toHaveBeenCalledWith('my_task.py')

    act(() => {
      root.unmount()
    })
  })

  it('hides request form for Observer and displays observer notice (US-40, FR21.1)', async () => {
    const root = createRoot(container)

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          isObserver={true}
          language="python"
          modules={sampleModules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onRequestModule={vi.fn()}
        />,
      )
    })

    expect(container.querySelector('[data-testid="request-module-form"]')).toBeNull()
    const notice = container.querySelector('[data-testid="observer-no-request"]')
    expect(notice?.textContent).toContain('Observers cannot request modules.')

    act(() => {
      root.unmount()
    })
  })

  it('renders pending requests panel for Host and handles Approve and Decline (US-40, FR21.2)', async () => {
    const root = createRoot(container)
    const onApprove = vi.fn()
    const onDecline = vi.fn()
    const roster = [
      { member_id: 'm_1', display_name: 'Alice Host', role: 'host' },
      { member_id: 'm_3', display_name: 'Charlie Part', role: 'participant' },
    ]

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={true}
          language="python"
          modules={sampleModules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onApproveModule={onApprove}
          onDeclineModule={onDecline}
          roster={roster}
        />,
      )
    })

    const pendingPanel = container.querySelector('[data-testid="pending-requests-panel"]')
    expect(pendingPanel).not.toBeNull()

    const pendingItem = container.querySelector('[data-testid="pending-module-mod_3"]')
    expect(pendingItem).not.toBeNull()

    const requesterText = container.querySelector('[data-testid="pending-requester-mod_3"]')
    expect(requesterText?.textContent).toContain('Requested by: Charlie Part')

    // Approve button
    const approveBtn = container.querySelector('[data-testid="approve-module-mod_3"]') as HTMLButtonElement
    expect(approveBtn).not.toBeNull()
    act(() => {
      approveBtn.click()
    })
    expect(onApprove).toHaveBeenCalledWith('mod_3', 'm_3')

    // Decline button
    const declineBtn = container.querySelector('[data-testid="decline-module-mod_3"]') as HTMLButtonElement
    expect(declineBtn).not.toBeNull()
    act(() => {
      declineBtn.click()
    })
    expect(onDecline).toHaveBeenCalledWith('mod_3')

    act(() => {
      root.unmount()
    })
  })
})
