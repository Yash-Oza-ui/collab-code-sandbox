import Editor from '@monaco-editor/react'
import { getRoomIdFromPath } from './roomId'

// Reads the room id from the URL (T-06 section 3.2: /room/{room_id}).
// Only reads it: US-01 builds the link and the join flow.
export default function RoomPage() {
  const roomId = getRoomIdFromPath(window.location.pathname)

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