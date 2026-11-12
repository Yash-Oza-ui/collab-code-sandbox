import { useState } from 'react'
import { createRoom } from './createRoom'
import type { CreatedRoom } from './createRoom'
import { buildRoomLink } from './roomLink'

export interface CreateRoomScreenProps {
  /** Overridable for tests. Defaults to the real POST /v1/rooms call. */
  createRoomFn?: () => Promise<CreatedRoom>
  /** Origin the link is built on. Defaults to the browser's current origin. */
  origin?: string
}

type CopyStatus = 'idle' | 'copied' | 'failed'

export default function CreateRoomScreen({
  createRoomFn = createRoom,
  origin = window.location.origin,
}: CreateRoomScreenProps) {
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<CreatedRoom | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copyStatus, setCopyStatus] = useState<CopyStatus>('idle')

  async function handleCreate() {
    setCreating(true)
    setError(null)
    setCopyStatus('idle')
    try {
      setCreated(await createRoomFn())
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