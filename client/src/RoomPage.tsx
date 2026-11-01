import { useState } from 'react'
import type { RefObject } from 'react'
import type * as Y from 'yjs'
import Editor from '@monaco-editor/react'
import DisplayNamePrompt from './DisplayNamePrompt'
import { getRoomIdFromPath } from './roomId'
import { useRoomDoc } from './roomDoc'

export interface RoomPageProps {
  /**
   * Optional ref to expose the active Y.Doc instance.
   * Enables US-01/US-02 integration and StrictMode regression testing.
   */
  docRef?: RefObject<Y.Doc | null>
  /** Called once a guest has supplied a valid name; ready for awareness integration. */
  onDisplayNameAccepted?: (displayName: string) => void
}

// Reads the room id from the URL (T-06 section 3.2: /room/{room_id}).
// Manages the Y.Doc lifecycle inside effect setup/cleanup (T-01).
export default function RoomPage(props: RoomPageProps = {}) {
  const { docRef: externalDocRef, onDisplayNameAccepted } = props
  const roomId = getRoomIdFromPath(window.location.pathname)
  const [displayName, setDisplayName] = useState<string | null>(null)

  // One Y.Doc is created inside effect setup and destroyed in its cleanup (T-01).
  const { docRef } = useRoomDoc(roomId, { docRef: externalDocRef })

  function handleDisplayNameAccepted(name: string) {
    setDisplayName(name)
    onDisplayNameAccepted?.(name)
  }

  if (roomId && displayName === null) {
    return <DisplayNamePrompt onSubmit={handleDisplayNameAccepted} />
  }

  return (
    <div className="room-page">
      <header className="room-header">
        {roomId ? `Room: ${roomId}` : 'No room id in the URL (expected /room/{room_id})'}
        {displayName ? ` · You: ${displayName}` : ''}
      </header>
      <div className="room-editor">
        <Editor
          height="100%"
          defaultLanguage="plaintext"
          defaultValue=""
          onMount={() => {
            // US-01 / US-02 will bind Monaco to the active docRef.current.
            // The accepted displayName is available here for the future awareness integration.
            void docRef.current
            void displayName
          }}
        />
      </div>
    </div>
  )
}
