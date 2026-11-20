import { useState } from 'react'
import type { FormEvent } from 'react'
import type { WorkspaceModule } from './workspaceApi'
import { defaultLanguageExtension } from './workspaceApi'

export interface WorkspacePanelProps {
  roomId: string
  isHost: boolean
  language?: string
  modules: WorkspaceModule[]
  activeModuleId: string | null
  onSelectModule: (moduleId: string) => void
  onCreateModule?: (name: string) => Promise<void> | void
  creationError?: string | null
  isCreating?: boolean
}

export default function WorkspacePanel({
  roomId: _roomId,
  isHost,
  language,
  modules,
  activeModuleId,
  onSelectModule,
  onCreateModule,
  creationError,
  isCreating = false,
}: WorkspacePanelProps) {
  const [newModuleName, setNewModuleName] = useState('')

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
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

  const defaultExt = defaultLanguageExtension(language)

  return (
    <section
      className="room-workspace-panel"
      aria-label="Workspace modules"
      data-testid="workspace-panel"
    >
      <div className="workspace-header">
        <h3 data-testid="workspace-title">Workspace ({modules.length})</h3>
        {language && (
          <span className="workspace-language-badge" data-testid="workspace-language">
            {language}
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
            return (
              <li
                key={m.module_id}
                data-testid={`module-${m.module_id}`}
                className={`workspace-module-item ${isActive ? 'active' : ''}`}
              >
                <button
                  type="button"
                  data-testid={`select-module-${m.module_id}`}
                  className="module-select-button"
                  onClick={() => onSelectModule(m.module_id)}
                  aria-current={isActive ? 'true' : undefined}
                >
                  <span className="module-name">{m.name}</span>
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
              </li>
            )
          })}
        </ul>
      )}

      {isHost && onCreateModule && (
        <form
          className="workspace-create-form"
          data-testid="create-module-form"
          onSubmit={handleSubmit}
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

      {creationError && (
        <p role="alert" data-testid="workspace-error" className="workspace-error-message">
          {creationError}
        </p>
      )}
    </section>
  )
}
