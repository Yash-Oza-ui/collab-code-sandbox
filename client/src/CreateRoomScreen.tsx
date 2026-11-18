import { useState } from 'react'
import { createRoom } from './createRoom'
import type { CreatedRoom, CreateRoomChoice, RoomMode, PairPreset, WorkspaceLanguage } from './createRoom'
import { buildRoomLink } from './roomLink'

export interface CreateRoomScreenProps {
  /** Overridable for tests. Defaults to the real POST /v1/rooms call. */
  createRoomFn?: (choice: CreateRoomChoice) => Promise<CreatedRoom>
  /** Origin the link is built on. Defaults to the browser's current origin. */
  origin?: string
}

type CopyStatus = 'idle' | 'copied' | 'failed'

export default function CreateRoomScreen({
  createRoomFn = createRoom,
  origin = window.location.origin,
}: CreateRoomScreenProps) {
  const [mode, setMode] = useState<RoomMode>('pair')
  const [preset, setPreset] = useState<PairPreset>('debug_together')
  const [language, setLanguage] = useState<WorkspaceLanguage>('python')
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<CreatedRoom | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copyStatus, setCopyStatus] = useState<CopyStatus>('idle')

  async function handleCreate() {
    setCreating(true)
    setError(null)
    setCopyStatus('idle')
    try {
      setCreated(await createRoomFn(mode === 'pair' ? { mode, preset } : { mode, language }))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Room creation failed.')
    } finally {
      setCreating(false)
    }
  }

  async function handleCopy(link: string) {
    try {
      await navigator.clipboard.writeText(link)
      setCopyStatus('copied')
    } catch {
      setCopyStatus('failed')
    }
  }

  const link = created ? buildRoomLink(origin, created.roomId) : null

  return (
    <main className="display-name-screen">
      <section className="display-name-card" aria-labelledby="create-room-title">
        <h1 id="create-room-title">Create a room</h1>
        <p>Start a shared coding session and send the link to your teammates.</p>
        {created && link ? (
          <div className="create-room-body">
            <label htmlFor="create-room-link">Share this link</label>
            <input
              id="create-room-link"
              type="text"
              readOnly
              value={link}
              onFocus={(event) => event.currentTarget.select()}
            />
            <div className="create-room-actions">
              <button type="button" onClick={() => handleCopy(link)}>
                Copy link
              </button>
              <a href={link}>Open room</a>
            </div>
            {copyStatus === 'copied' && (
              <p className="display-name-help" role="status">
                Link copied.
              </p>
            )}
            {copyStatus === 'failed' && (
              <p className="display-name-error" role="alert">
                Could not copy automatically. Select the link above and copy it manually.
              </p>
            )}
            {!created.ownerTokenStored && (
              <p className="display-name-error" role="alert">
                This browser could not save the owner token for this room.
              </p>
            )}
          </div>
        ) : (
          <div className="create-room-body">
            <label htmlFor="room-mode">Room mode</label>
            <select id="room-mode" value={mode} disabled={creating} onChange={(e) => setMode(e.target.value as RoomMode)}>
              <option value="pair">Pair</option>
              <option value="open">Open</option>
            </select>
            <p>{mode === 'pair' ? 'Pair coding with a guided preset.' : 'Open workspace with a chosen programming language.'}</p>
            {mode === 'pair' ? (
              <>
                <label htmlFor="pair-preset">Pair preset</label>
                <select id="pair-preset" value={preset} disabled={creating} onChange={(e) => setPreset(e.target.value as PairPreset)}>
                  <option value="teach_interview">Teach / Interview</option>
                  <option value="debug_together">Debug together</option>
                </select>
              </>
            ) : (
              <>
                <label htmlFor="workspace-language">Workspace language</label>
                <select id="workspace-language" value={language} disabled={creating} onChange={(e) => setLanguage(e.target.value as WorkspaceLanguage)}>
                  <option value="cpp">C++</option>
                  <option value="python">Python</option>
                  <option value="c">C</option>
                  <option value="java">Java</option>
                  <option value="javascript">JavaScript</option>
                </select>
              </>
            )}
            <button type="button" onClick={handleCreate} disabled={creating}>
              {creating ? 'Creating room...' : 'Create room'}
            </button>
            {error && (
              <p className="display-name-error" role="alert">
                {error}
              </p>
            )}
          </div>
        )}
      </section>
    </main>
  )
}