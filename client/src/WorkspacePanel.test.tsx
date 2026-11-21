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

  it('renders write access toggle controls for owner or Host, and manages grants/revokes (US-43, FR22.1)', async () => {
    const root = createRoot(container)
    const onGrant = vi.fn()
    const onRevoke = vi.fn()
    const modulesWithWriters: WorkspaceModule[] = [
      {
        module_id: 'mod_1',
        name: 'main.py',
        owner_member_id: 'm_owner',
        writers: ['m_part1'],
        is_entry: true,
        pending: false,
      },
    ]
    const roster = [
      { member_id: 'm_owner', display_name: 'Alice Owner', role: 'participant' },
      { member_id: 'm_part1', display_name: 'Bob Writer', role: 'participant' },
      { member_id: 'm_part2', display_name: 'Charlie NonWriter', role: 'participant' },
    ]

    // As module owner
    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          language="python"
          modules={modulesWithWriters}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onGrantWriteAccess={onGrant}
          onRevokeWriteAccess={onRevoke}
          roster={roster}
          myMemberId="m_owner"
        />,
      )
    })

    const toggleBtn = container.querySelector('[data-testid="toggle-writers-mod_1"]') as HTMLButtonElement
    expect(toggleBtn).not.toBeNull()
    expect(toggleBtn.textContent).toContain('Write access (2)') // 1 writer + 1 owner

    // Expand dropdown
    act(() => {
      toggleBtn.click()
    })

    const dropdown = container.querySelector('[data-testid="writers-dropdown-mod_1"]')
    expect(dropdown).not.toBeNull()

    // Owner switch is checked and disabled
    const ownerSwitch = container.querySelector(
      '[data-testid="writer-toggle-mod_1-m_owner"]',
    ) as HTMLInputElement
    expect(ownerSwitch).not.toBeNull()
    expect(ownerSwitch.checked).toBe(true)
    expect(ownerSwitch.disabled).toBe(true)

    // Bob switch is checked (already writer)
    const bobSwitch = container.querySelector(
      '[data-testid="writer-toggle-mod_1-m_part1"]',
    ) as HTMLInputElement
    expect(bobSwitch).not.toBeNull()
    expect(bobSwitch.checked).toBe(true)
    expect(bobSwitch.disabled).toBe(false)

    // Charlie switch is unchecked
    const charlieSwitch = container.querySelector(
      '[data-testid="writer-toggle-mod_1-m_part2"]',
    ) as HTMLInputElement
    expect(charlieSwitch).not.toBeNull()
    expect(charlieSwitch.checked).toBe(false)
    expect(charlieSwitch.disabled).toBe(false)

    // Toggle Bob to revoke
    act(() => {
      bobSwitch.click()
    })
    expect(onRevoke).toHaveBeenCalledWith('mod_1', 'm_part1')

    // Toggle Charlie to grant
    act(() => {
      charlieSwitch.click()
    })
    expect(onGrant).toHaveBeenCalledWith('mod_1', 'm_part2')

    act(() => {
      root.unmount()
    })
  })

  it('renders read-only badge for non-owner non-host participants without write access (US-43, FR22.2)', async () => {
    const root = createRoot(container)
    const modulesWithWriters: WorkspaceModule[] = [
      {
        module_id: 'mod_1',
        name: 'main.py',
        owner_member_id: 'm_owner',
        writers: [],
        is_entry: true,
        pending: false,
      },
    ]

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          language="python"
          modules={modulesWithWriters}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          myMemberId="m_guest"
        />,
      )
    })

    // Writers control button not visible
    expect(container.querySelector('[data-testid="writers-control-mod_1"]')).toBeNull()

    // Read-only badge is displayed
    const badge = container.querySelector('[data-testid="access-badge-mod_1"]')
    expect(badge).not.toBeNull()
    expect(badge?.textContent).toBe('Read-only')

    act(() => {
      root.unmount()
    })
  })

  it('renders runnable and non-runnable badges for modules (US-46, FR20.2)', async () => {
    const root = createRoot(container)
    const modules: WorkspaceModule[] = [
      { module_id: 'mod_1', name: 'main.py', owner_member_id: 'm_1', runnable: true, pending: false },
      { module_id: 'mod_2', name: 'helper.py', owner_member_id: 'm_2', runnable: false, pending: false },
    ]

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          language="python"
          modules={modules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
        />,
      )
    })

    const badge1 = container.querySelector('[data-testid="runnable-badge-mod_1"]')
    expect(badge1?.textContent).toBe('runnable')
    expect(badge1?.className).toContain('runnable-badge')

    const badge2 = container.querySelector('[data-testid="runnable-badge-mod_2"]')
    expect(badge2?.textContent).toBe('non-runnable')
    expect(badge2?.className).toContain('non-runnable-badge')

    act(() => {
      root.unmount()
    })
  })

  it('displays "No entry file set" status when no entry file exists (US-46)', async () => {
    const root = createRoot(container)
    const modules: WorkspaceModule[] = [
      { module_id: 'mod_1', name: 'a.py', owner_member_id: 'm_1', is_entry: false, pending: false },
      { module_id: 'mod_2', name: 'b.py', owner_member_id: 'm_2', is_entry: false, pending: false },
    ]

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={true}
          language="python"
          modules={modules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onSetEntry={vi.fn()}
        />,
      )
    })

    const noEntryMsg = container.querySelector('[data-testid="no-entry-file-message"]')
    expect(noEntryMsg).not.toBeNull()
    expect(noEntryMsg?.textContent).toContain('No entry file set')

    // Host sees the control to set entry file
    expect(container.querySelector('[data-testid="set-entry-mod_1"]')).not.toBeNull()
    expect(container.querySelector('[data-testid="set-entry-mod_2"]')).not.toBeNull()

    act(() => {
      root.unmount()
    })
  })

  it('displays designated entry file in workspace status (US-46)', async () => {
    const root = createRoot(container)
    const modules: WorkspaceModule[] = [
      { module_id: 'mod_1', name: 'main.py', owner_member_id: 'm_1', is_entry: true, pending: false },
      { module_id: 'mod_2', name: 'utils.py', owner_member_id: 'm_2', is_entry: false, pending: false },
    ]

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={true}
          language="python"
          modules={modules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onSetEntry={vi.fn()}
        />,
      )
    })

    const entryIndicator = container.querySelector('[data-testid="entry-file-indicator"]')
    expect(entryIndicator?.textContent).toContain('Entry file: main.py')

    // Host sees "Set as entry" on utils.py (non-entry), but not on main.py (already entry)
    expect(container.querySelector('[data-testid="set-entry-mod_1"]')).toBeNull()
    expect(container.querySelector('[data-testid="set-entry-mod_2"]')).not.toBeNull()

    act(() => {
      root.unmount()
    })
  })

  it('renders runnable toggle only for the module owner and calls onToggleRunnable (US-46, FR20.2)', async () => {
    const root = createRoot(container)
    const onToggle = vi.fn()
    const modules: WorkspaceModule[] = [
      { module_id: 'mod_1', name: 'main.py', owner_member_id: 'm_host', runnable: true, pending: false },
      { module_id: 'mod_2', name: 'solver.py', owner_member_id: 'm_alice', runnable: false, pending: false },
    ]

    // Render as Alice (m_alice)
    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          language="python"
          modules={modules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onToggleRunnable={onToggle}
          myMemberId="m_alice"
        />,
      )
    })

    // Alice is owner of mod_2, but not mod_1
    expect(container.querySelector('[data-testid="toggle-runnable-mod_1"]')).toBeNull()
    const aliceBtn = container.querySelector('[data-testid="toggle-runnable-mod_2"]') as HTMLButtonElement
    expect(aliceBtn).not.toBeNull()
    expect(aliceBtn.textContent).toBe('Mark runnable')

    act(() => {
      aliceBtn.click()
    })

    expect(onToggle).toHaveBeenCalledWith('mod_2', true)

    act(() => {
      root.unmount()
    })
  })

  it('renders "Set as entry" only for Host and calls onSetEntry (US-46, FR20.3)', async () => {
    const root = createRoot(container)
    const onSetEntry = vi.fn()
    const modules: WorkspaceModule[] = [
      { module_id: 'mod_1', name: 'main.py', owner_member_id: 'm_host', is_entry: true, pending: false },
      { module_id: 'mod_2', name: 'solver.py', owner_member_id: 'm_alice', is_entry: false, pending: false },
    ]

    // Non-Host does not see set-entry buttons
    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          language="python"
          modules={modules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onSetEntry={onSetEntry}
          myMemberId="m_alice"
        />,
      )
    })

    expect(container.querySelector('[data-testid="set-entry-mod_1"]')).toBeNull()
    expect(container.querySelector('[data-testid="set-entry-mod_2"]')).toBeNull()

    // Host sees set-entry on mod_2 (non-entry)
    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={true}
          language="python"
          modules={modules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          onSetEntry={onSetEntry}
          myMemberId="m_host"
        />,
      )
    })

    const setEntryBtn = container.querySelector('[data-testid="set-entry-mod_2"]') as HTMLButtonElement
    expect(setEntryBtn).not.toBeNull()

    act(() => {
      setEntryBtn.click()
    })

    expect(onSetEntry).toHaveBeenCalledWith('mod_2')

    act(() => {
      root.unmount()
    })
  })

  it('renders plain-words error message when actionError is provided (US-46)', async () => {
    const root = createRoot(container)

    act(() => {
      root.render(
        <WorkspacePanel
          roomId="room-1"
          isHost={false}
          language="python"
          modules={sampleModules}
          activeModuleId="mod_1"
          onSelectModule={vi.fn()}
          actionError="Only the module owner can change its runnable status."
        />,
      )
    })

    const error = container.querySelector('[data-testid="workspace-error"]')
    expect(error?.textContent).toContain('Only the module owner can change its runnable status.')

    act(() => {
      root.unmount()
    })
  })
})
