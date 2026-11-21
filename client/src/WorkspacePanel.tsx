import { useState } from 'react'
import type { FormEvent } from 'react'
import type { WorkspaceModule } from './workspaceApi'
import { defaultLanguageExtension } from './workspaceApi'

export interface WorkspacePanelProps {
  roomId: string
  isHost: boolean
  isObserver?: boolean
  language?: string
  modules: WorkspaceModule[]
  activeModuleId: string | null
  onSelectModule: (moduleId: string) => void
  onCreateModule?: (name: string) => Promise<void> | void
  onRequestModule?: (name: string) => Promise<void> | void
  onApproveModule?: (moduleId: string, ownerMemberId?: string) => Promise<void> | void
  onDeclineModule?: (moduleId: string) => Promise<void> | void
  onToggleRunnable?: (moduleId: string, runnable: boolean) => Promise<void> | void
  onSetEntry?: (moduleId: string) => Promise<void> | void
  onGrantWriteAccess?: (moduleId: string, memberId: string) => Promise<void> | void
  onRevokeWriteAccess?: (moduleId: string, memberId: string) => Promise<void> | void
  creationError?: string | null
  actionError?: string | null
  isCreating?: boolean
  roster?: Array<{ member_id: string; display_name: string; role: string }>
  myMemberId?: string | null
}

export default function WorkspacePanel({
  roomId,
  isHost,
  isObserver = false,
  language,
  modules,
  activeModuleId,
  onSelectModule,
  onCreateModule,
  onRequestModule,
  onApproveModule,
  onDeclineModule,
  onToggleRunnable,
  onSetEntry,
  onGrantWriteAccess,
  onRevokeWriteAccess,
  creationError,
  actionError,
  isCreating = false,
  roster = [],
  myMemberId,
}: WorkspacePanelProps) {
  const [newModuleName, setNewModuleName] = useState('')
  const [requestModuleName, setRequestModuleName] = useState('')
  const [selectedOwners, setSelectedOwners] = useState<Record<string, string>>({})
  const [expandedWriters, setExpandedWriters] = useState<Record<string, boolean>>({})

  const handleCreateSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = e.currentTarget
    const formData = new FormData(form)
    const rawVal = (formData.get('moduleName') as string | null) ?? newModuleName
    const trimmed = (rawVal || newModuleName).trim()
    if (!trimmed || !onCreateModule || isCreating) return
    void Promise.resolve(onCreateModule(trimmed)).then(() => {
      setNewModuleName('')
    })
  }

  const handleRequestSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = e.currentTarget
    const formData = new FormData(form)
    const rawVal = (formData.get('moduleName') as string | null) ?? requestModuleName
    const trimmed = (rawVal || requestModuleName).trim()
    const handler = onRequestModule || onCreateModule
    if (!trimmed || !handler || isCreating) return
    void Promise.resolve(handler(trimmed)).then(() => {
      setRequestModuleName('')
    })
  }

  const defaultExt = defaultLanguageExtension(language)

  const getMemberDisplayName = (memberId: string) => {
    const found = roster.find((p) => p.member_id === memberId)
    return found?.display_name || memberId
  }

  const pendingModules = modules.filter((m) => m.pending)
  const approvedModules = modules.filter((m) => !m.pending)
  const entryModule = approvedModules.find((m) => m.is_entry)

  return (
    <section
      className="room-workspace-panel"
      aria-label="Workspace modules"
      data-testid="workspace-panel"
      data-room-id={roomId}
    >
      <div className="workspace-header">
        <h3 data-testid="workspace-title">Workspace ({modules.length})</h3>
        {language && (
          <span className="workspace-language-badge" data-testid="workspace-language">
            {language}
          </span>
        )}
      </div>

      <div className="workspace-entry-status" data-testid="workspace-entry-status">
        {entryModule ? (
          <span className="entry-file-indicator" data-testid="entry-file-indicator">
            Entry file: <strong>{entryModule.name}</strong>
          </span>
        ) : (
          <span className="no-entry-file-message" data-testid="no-entry-file-message">
            No entry file set
          </span>
        )}
      </div>

      {modules.length === 0 ? (
        <p className="no-modules" data-testid="no-modules">
          No modules in workspace.
        </p>
      ) : (
        <ul className="workspace-module-list" data-testid="module-list">
          {modules.map((m) => {
            const isActive = m.module_id === activeModuleId
            const ownerName = getMemberDisplayName(m.owner_member_id)
            const isOwner = Boolean(myMemberId && m.owner_member_id === myMemberId)
            const isRunnable = m.runnable !== false

            return (
              <li
                key={m.module_id}
                data-testid={`module-${m.module_id}`}
                className={`workspace-module-item ${isActive ? 'active' : ''}`}
              >
                <div className="module-item-header">
                  <button
                    type="button"
                    data-testid={`select-module-${m.module_id}`}
                    className="module-select-button"
                    onClick={() => onSelectModule(m.module_id)}
                    aria-current={isActive ? 'true' : undefined}
                  >
                    <span className="module-name">{m.name}</span>
                    <span
                      className={`module-badge ${isRunnable ? 'runnable-badge' : 'non-runnable-badge'}`}
                      data-testid={`runnable-badge-${m.module_id}`}
                    >
                      {isRunnable ? 'runnable' : 'non-runnable'}
                    </span>
                    {m.is_entry && (
                      <span
                        className="module-badge entry-badge"
                        data-testid={`entry-badge-${m.module_id}`}
                      >
                        entry
                      </span>
                    )}
                    {m.pending && (
                      <span
                        className="module-badge pending-badge"
                        data-testid={`pending-badge-${m.module_id}`}
                      >
                        pending
                      </span>
                    )}
                  </button>
                  <span
                    className="module-owner-label"
                    data-testid={`owner-label-${m.module_id}`}
                  >
                    Owner: {ownerName}
                  </span>
                </div>

                {!m.pending && (
                  <div className="module-controls" data-testid={`module-controls-${m.module_id}`}>
                    {isOwner && onToggleRunnable && (
                      <button
                        type="button"
                        className="module-toggle-runnable-btn"
                        data-testid={`toggle-runnable-${m.module_id}`}
                        onClick={() => void onToggleRunnable(m.module_id, !isRunnable)}
                      >
                        {isRunnable ? 'Mark non-runnable' : 'Mark runnable'}
                      </button>
                    )}
                    {isHost && onSetEntry && !m.is_entry && (
                      <button
                        type="button"
                        className="module-set-entry-btn"
                        data-testid={`set-entry-${m.module_id}`}
                        onClick={() => void onSetEntry(m.module_id)}
                      >
                        Set as entry
                      </button>
                    )}
                  </div>
                )}

                {/* Write Access Control (US-43 / FR22.1) */}
                {!m.pending && (isHost || (myMemberId && m.owner_member_id === myMemberId)) && (
                  <div className="module-writers-control" data-testid={`writers-control-${m.module_id}`}>
                    <button
                      type="button"
                      className="toggle-writers-btn"
                      data-testid={`toggle-writers-${m.module_id}`}
                      aria-expanded={expandedWriters[m.module_id] ? 'true' : 'false'}
                      onClick={() =>
                        setExpandedWriters((prev) => ({
                          ...prev,
                          [m.module_id]: !prev[m.module_id],
                        }))
                      }
                    >
                      Write access ({(m.writers || []).length + 1})
                    </button>
                    {expandedWriters[m.module_id] && (
                      <div className="module-writers-dropdown" data-testid={`writers-dropdown-${m.module_id}`}>
                        <span className="writers-dropdown-title">Manage write access:</span>
                        <ul className="writers-participants-list" data-testid={`writers-list-${m.module_id}`}>
                          {roster.map((p) => {
                            const isOwnerMember = p.member_id === m.owner_member_id
                            const isWriter = isOwnerMember || (m.writers || []).includes(p.member_id)
                            return (
                              <li
                                key={p.member_id}
                                className="writer-participant-item"
                                data-testid={`writer-item-${m.module_id}-${p.member_id}`}
                              >
                                <label className="writer-switch-label">
                                  <input
                                    type="checkbox"
                                    role="switch"
                                    className="writer-toggle-switch"
                                    data-testid={`writer-toggle-${m.module_id}-${p.member_id}`}
                                    aria-label={`Give write access to ${p.display_name} for ${m.name}`}
                                    checked={isWriter}
                                    disabled={isOwnerMember}
                                    onChange={(e) => {
                                      if (isOwnerMember) return
                                      if (e.target.checked) {
                                        void onGrantWriteAccess?.(m.module_id, p.member_id)
                                      } else {
                                        void onRevokeWriteAccess?.(m.module_id, p.member_id)
                                      }
                                    }}
                                  />
                                  <span className="writer-display-name">{p.display_name}</span>
                                  {isOwnerMember && <span className="writer-owner-tag">(Owner)</span>}
                                </label>
                              </li>
                            )
                          })}
                        </ul>
                      </div>
                    )}
                  </div>
                )}
                {!m.pending && !(isHost || (myMemberId && m.owner_member_id === myMemberId)) && (
                  <span
                    className="module-access-badge"
                    data-testid={`access-badge-${m.module_id}`}
                  >
                    {m.owner_member_id === myMemberId
                      ? 'Owner'
                      : (m.writers || []).includes(myMemberId || '')
                        ? 'Writer'
                        : 'Read-only'}
                  </span>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {/* Host Pending Requests Panel (US-40) */}
      {isHost && (
        <section
          className="workspace-pending-panel"
          data-testid="pending-requests-panel"
          aria-label="Pending module requests"
        >
          <h4 data-testid="pending-requests-title">
            Pending Requests ({pendingModules.length})
          </h4>
          {pendingModules.length === 0 ? (
            <p className="no-pending-requests" data-testid="no-pending-requests">
              No pending requests.
            </p>
          ) : (
            <ul className="pending-module-list" data-testid="pending-module-list">
              {pendingModules.map((m) => {
                const requesterName = getMemberDisplayName(m.owner_member_id)
                const selectedOwner = selectedOwners[m.module_id] || m.owner_member_id
                const eligibleOwners = roster.filter(
                  (p) => p.role.toLowerCase() !== 'observer',
                )

                return (
                  <li
                    key={m.module_id}
                    className="pending-module-item"
                    data-testid={`pending-module-${m.module_id}`}
                  >
                    <div className="pending-module-info">
                      <span className="pending-module-name">{m.name}</span>
                      <span
                        className="pending-module-requester"
                        data-testid={`pending-requester-${m.module_id}`}
                      >
                        Requested by: {requesterName}
                      </span>
                    </div>

                    {eligibleOwners.length > 0 && (
                      <select
                        className="pending-module-owner-select"
                        data-testid={`approve-owner-select-${m.module_id}`}
                        aria-label="Assign owner"
                        value={selectedOwner}
                        onChange={(e) =>
                          setSelectedOwners((prev) => ({
                            ...prev,
                            [m.module_id]: e.target.value,
                          }))
                        }
                      >
                        {eligibleOwners.map((owner) => (
                          <option key={owner.member_id} value={owner.member_id}>
                            {owner.display_name}
                          </option>
                        ))}
                      </select>
                    )}

                    <div className="pending-module-actions">
                      <button
                        type="button"
                        className="approve-module-button"
                        data-testid={`approve-module-${m.module_id}`}
                        onClick={() => onApproveModule?.(m.module_id, selectedOwner)}
                      >
                        Approve
                      </button>
                      <button
                        type="button"
                        className="decline-module-button"
                        data-testid={`decline-module-${m.module_id}`}
                        onClick={() => onDeclineModule?.(m.module_id)}
                      >
                        Decline
                      </button>
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </section>
      )}

      {/* Host Direct Creation Form */}
      {isHost && onCreateModule && (
        <form
          className="workspace-create-form"
          data-testid="create-module-form"
          onSubmit={handleCreateSubmit}
        >
          <input
            type="text"
            name="moduleName"
            data-testid="new-module-name-input"
            className="workspace-module-input"
            placeholder={`e.g. main.${defaultExt}`}
            value={newModuleName}
            onChange={(e) => setNewModuleName(e.target.value)}
            disabled={isCreating}
          />
          <button
            type="submit"
            data-testid="create-module-button"
            className="workspace-create-button"
            disabled={isCreating}
          >
            {isCreating ? 'Creating...' : 'New Module'}
          </button>
        </form>
      )}

      {/* Participant Request Form (US-40) */}
      {!isHost && !isObserver && (onRequestModule || onCreateModule) && (
        <form
          className="workspace-request-form"
          data-testid="request-module-form"
          onSubmit={handleRequestSubmit}
        >
          <input
            type="text"
            name="moduleName"
            data-testid="request-module-input"
            className="workspace-module-input"
            placeholder={`e.g. solver.${defaultExt}`}
            value={requestModuleName}
            onChange={(e) => setRequestModuleName(e.target.value)}
            disabled={isCreating}
          />
          <button
            type="submit"
            data-testid="request-module-button"
            className="workspace-request-button"
            disabled={isCreating}
          >
            {isCreating ? 'Requesting...' : 'Request Module'}
          </button>
        </form>
      )}

      {/* Observer notice */}
      {isObserver && (
        <p className="observer-no-request" data-testid="observer-no-request">
          Observers cannot request modules.
        </p>
      )}

      {(actionError || creationError) && (
        <p role="alert" data-testid="workspace-error" className="workspace-error-message">
          {actionError || creationError}
        </p>
      )}
    </section>
  )
}
