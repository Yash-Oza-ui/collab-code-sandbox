
import { useEffect, useState } from 'react'
import type { RefObject } from 'react'
import type * as Y from 'yjs'
import type { editor as MonacoEditor } from 'monaco-editor'
import Editor from '@monaco-editor/react'
import { MonacoBinding } from 'y-monaco'
import { WebsocketProvider } from 'y-websocket'
import DisplayNamePrompt from './DisplayNamePrompt'
import { getRoomIdFromPath } from './roomId'
import { useRoomDoc } from './roomDoc'
import { config } from './config'
import { getSessionId } from './sessionId'

export interface RoomPageProps {
  docRef?: RefObject<Y.Doc | null>
  onDisplayNameAccepted?: (displayName: string) => void
}

export default function RoomPage(props: RoomPageProps = {}) {
  const { docRef: externalDocRef, onDisplayNameAccepted } = props
  const roomId = getRoomIdFromPath(window.location.pathname)
  const [displayName, setDisplayName] = useState<string | null>(null)
  const [editor, setEditor] =
    useState<MonacoEditor.IStandaloneCodeEditor | null>(null)
  const [connectionStatus, setConnectionStatus] = useState<
    'connecting' | 'connected' | 'not-found'
  >('connecting')
  const { docRef } = useRoomDoc(roomId, { docRef: externalDocRef })

  function handleDisplayNameAccepted(name: string) {
    setDisplayName(name)
    onDisplayNameAccepted?.(name)
  }

  useEffect(() => {
    const doc = docRef.current

    if (!roomId || !displayName || !doc || !editor) {
      return
    }

    const model = editor.getModel()

    if (!model) {
      return
    }

    const provider = new WebsocketProvider(
      config.syncWsUrl,
      roomId,
      doc,
      {
        params: {
          session_id: getSessionId(),
        },
      },
    )

    provider.on('status', ({ status }) => {
      if (status === 'connected') {
        setConnectionStatus('connected')
      } else {
        setConnectionStatus((current) =>
          current === 'not-found' ? 'not-found' : 'connecting'
        )
      }
    })

    provider.on('connection-close', (event) => {
      if (event?.code === 4404) {
        provider.disconnect()
        setConnectionStatus('not-found')
      }
    })

    provider.awareness.setLocalStateField('user', {
      name: displayName,
    })

    const binding = new MonacoBinding(
      doc.getText('monaco'),
      model,
      new Set([editor]),
      provider.awareness,
    )

    return () => {
      binding.destroy()
      provider.destroy()
    }
  }, [roomId, displayName, docRef, editor])

  if (roomId && displayName === null) {
    return <DisplayNamePrompt onSubmit={handleDisplayNameAccepted} />
  }

  return (
    <div className="room-page">
      <header className="room-header">
        {roomId
          ? `Room: ${roomId}`
          : 'No room id in the URL (expected /room/{room_id})'}
        {displayName ? ` · You: ${displayName}` : ''}
      </header>

      <div className="room-editor" style={{ position: 'relative' }}>
        {connectionStatus !== 'connected' && (
          <div
            role={connectionStatus === 'not-found' ? 'alert' : 'status'}
            style={{
              position: 'absolute',
              inset: 0,
              zIndex: 10,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: '#ffffff',
              padding: '24px',
              textAlign: 'center',
            }}
          >
            {connectionStatus === 'not-found' ? (
              <div>
                <h2>Room not found</h2>
                <p>
                  This room does not exist. Please check your invitation
                  link or create a new room.
                </p>
              </div>
            ) : (
              <p>Connecting to room...</p>
            )}
          </div>
        )}

        <div
          style={{
            height: '100%',
            visibility:
              connectionStatus === 'connected' ? 'visible' : 'hidden',
          }}
        >
          <Editor
            height="100%"
            defaultLanguage="plaintext"
            defaultValue=""
            onMount={(mountedEditor) => {
              setEditor(mountedEditor)
            }}
          />
        </div>
      </div>
    </div>
  )
}
