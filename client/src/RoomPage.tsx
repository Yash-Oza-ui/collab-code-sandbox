import { useEffect, useMemo } from 'react'
import Editor from '@monaco-editor/react'
import { getRoomIdFromPath } from './roomId'
import { createRoomDoc } from './roomDoc'

// Reads the room id from the URL (T-06 section 3.2: /room/{room_id}).
// Only reads it: US-01 builds the link and the join flow.
// Creates one Y.Doc for the page (T-01); relay connection is wired in US-01 / US-02.
export default function RoomPage() {
  const roomId = getRoomIdFromPath(window.location.pathname)

  // One Y.Doc is created for the page (T-01).
  // Lifecycle is bound to the room page component and cleaned up on unmount.
  const doc = useMemo(() => createRoomDoc(), [])

  useEffect(() => {
    return () => {
      doc.destroy()
    }
  }, [doc])

  return (
    <div className="room-page">
      <header className="room-header">
        {roomId ? `Room: ${roomId}` : 'No room id in the URL (expected /room/{room_id})'}
      </header>
      <div className="room-editor">
        <Editor height="100%" defaultLanguage="plaintext" defaultValue="" />
      </div>
    </div>
  )
}