import type { RefObject } from 'react'
import type * as Y from 'yjs'
import Editor from '@monaco-editor/react'
import { getRoomIdFromPath } from './roomId'
import { useRoomDoc } from './roomDoc'

export interface RoomPageProps {
  /**
   * Optional ref to expose the active Y.Doc instance.
   * Enables US-01/US-02 integration and StrictMode regression testing.
   */
  docRef?: RefObject<Y.Doc | null>
}

// Reads the room id from the URL (T-06 section 3.2: /room/{room_id}).
// Only reads it: US-01 builds the link and the join flow.
// Manages the Y.Doc lifecycle inside effect setup/cleanup (T-01); relay connection is wired in US-01 / US-02.
export default function RoomPage(props: RoomPageProps = {}) {
  const { docRef: externalDocRef } = props
  const roomId = getRoomIdFromPath(window.location.pathname)

  // One Y.Doc is created inside effect setup and destroyed in its cleanup (T-01).
  // The active document is preserved in docRef for US-01/US-02 relay and editor binding.
  const { docRef } = useRoomDoc(roomId, { docRef: externalDocRef })

  return (
    <div className="room-page">
      <header className="room-header">
        {roomId ? `Room: ${roomId}` : 'No room id in the URL (expected /room/{room_id})'}
      </header>
      <div className="room-editor">
        <Editor
          height="100%"
          defaultLanguage="plaintext"
          defaultValue=""
          onMount={() => {
            // US-01 / US-02 will bind Monaco to the active docRef.current
            void docRef.current
          }}
        />
      </div>
    </div>
  )
}