import { useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import type { Awareness } from 'y-protocols/awareness'
import type * as Y from 'yjs'
import Editor from '@monaco-editor/react'
import DisplayNamePrompt from './DisplayNamePrompt'
import {
  createAwareness,
  getExistingUserColors,
  setUserAwareness,
} from './awareness'
import { getRoomIdFromPath } from './roomId'
import { useRoomDoc } from './roomDoc'
import { pickUserColor } from './userColor'

export interface RoomPageProps {
  /**
   * Optional ref to expose the active Y.Doc instance.
   * Enables US-01/US-02 integration and StrictMode regression testing.
   */
  docRef?: RefObject<Y.Doc | null>
  /**
   * Optional ref to expose the active Awareness instance.
   * Enables tests and downstream US-03 live cursor / awareness integration.
   */
  awarenessRef?: RefObject<Awareness | null>
  /** Called once a guest has supplied a valid name; ready for awareness integration. */
  onDisplayNameAccepted?: (displayName: string, userColor?: string) => void
}

// Reads the room id from the URL (T-06 section 3.2: /room/{room_id}).
// Manages the Y.Doc lifecycle inside effect setup/cleanup (T-01).
// Manages the Yjs Awareness lifecycle and user presence colour assignment (US-05 / FR14).
export default function RoomPage(props: RoomPageProps = {}) {
  const {
    docRef: externalDocRef,
    awarenessRef: externalAwarenessRef,
    onDisplayNameAccepted,
  } = props
  const roomId = getRoomIdFromPath(window.location.pathname)
  const [displayName, setDisplayName] = useState<string | null>(null)
  const [userColor, setUserColor] = useState<string | null>(null)

  // One Y.Doc is created inside effect setup and destroyed in its cleanup (T-01).
  const { docRef } = useRoomDoc(roomId, { docRef: externalDocRef })

  const internalAwarenessRef = useRef<Awareness | null>(null)
  const awarenessRef = externalAwarenessRef ?? internalAwarenessRef

  // Manage Awareness instance lifecycle tied to the active Y.Doc
  useEffect(() => {
    const doc = docRef.current
    if (!doc) return

    const awareness = createAwareness(doc)
    awarenessRef.current = awareness

    // Expose awareness on window for browser console inspection (US-05 DoD #5)
    if (typeof window !== 'undefined') {
      ;(window as unknown as { awareness?: Awareness }).awareness = awareness
    }

    return () => {
      awareness.destroy()
      if (awarenessRef.current === awareness) {
        awarenessRef.current = null
      }
      if (
        typeof window !== 'undefined' &&
        (window as unknown as { awareness?: Awareness }).awareness === awareness
      ) {
        delete (window as unknown as { awareness?: Awareness }).awareness
      }
    }
  }, [roomId, docRef, awarenessRef])

  // Keep presence updated in awareness whenever displayName and userColor are set or change
  useEffect(() => {
    const awareness = awarenessRef.current
    if (awareness && displayName && userColor) {
      setUserAwareness(awareness, { name: displayName, color: userColor })
    }
  }, [displayName, userColor, awarenessRef])

  function handleDisplayNameAccepted(name: string) {
    const awareness = awarenessRef.current
    const existingColors = awareness ? getExistingUserColors(awareness) : []
    const fallbackIndex = docRef.current?.clientID ?? 0
    const color = pickUserColor(existingColors, fallbackIndex)

    if (awareness) {
      setUserAwareness(awareness, { name, color })
    }

    setDisplayName(name)
    setUserColor(color)
    onDisplayNameAccepted?.(name, color)
  }

  if (roomId && displayName === null) {
    return <DisplayNamePrompt onSubmit={handleDisplayNameAccepted} />
  }

  return (
    <div className="room-page">
      <header className="room-header">
        <span>{roomId ? `Room: ${roomId}` : 'No room id in the URL (expected /room/{room_id})'}</span>
        {displayName && (
          <>
            <span className="room-header-separator" aria-hidden="true"> · </span>
            <span className="room-user-badge" data-testid="room-user-badge">
              {userColor && (
                <span
                  className="room-user-color-dot"
                  data-testid="room-user-color-dot"
                  style={{ backgroundColor: userColor }}
                  aria-hidden="true"
                />
              )}
              <span>You: {displayName}</span>
            </span>
          </>
        )}
      </header>
      <div className="room-editor">
        <Editor
          height="100%"
          defaultLanguage="plaintext"
          defaultValue=""
          onMount={() => {
            // US-01 / US-02 will bind Monaco to the active docRef.current.
            // The accepted display name is held by RoomPage and exposed through the callback.
            void docRef.current
          }}
        />
      </div>
    </div>
  )
}
