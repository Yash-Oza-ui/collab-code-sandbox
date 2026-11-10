
import { useEffect, useState } from 'react'
import type { RefObject } from 'react'
import type * as Y from 'yjs'
import type { editor as MonacoEditor } from 'monaco-editor'
import Editor from '@monaco-editor/react'
import { MonacoBinding } from 'y-monaco'
import DisplayNamePrompt from './DisplayNamePrompt'
import { getRoomIdFromPath } from './roomId'
import { useRoomDoc } from './roomDoc'
import {
  createDocumentProvider,
  joinRoom,
} from './roomConnection'
import { getSessionId } from './sessionId'

export interface RoomPageProps {
  docRef?: RefObject<Y.Doc | null>
  onDisplayNameAccepted?: (displayName: string) => void
}

export default function RoomPage(props: RoomPageProps = {}) {
  const { docRef: externalDocRef, onDisplayNameAccepted } = props
  const roomId = getRoomIdFromPath(window.location.pathname)
  const isInvalidRoomId = !roomId || !/^[A-Za-z0-9_-]{1,64}$/.test(roomId)
  const [displayName, setDisplayName] = useState<string | null>(null)
  const [editor, setEditor] =
    useState<MonacoEditor.IStandaloneCodeEditor | null>(null)
  const [connectionStatus, setConnectionStatus] = useState<
    'connecting' | 'connected' | 'not-found' | 'connection-error'
  >('connecting')
  const { docRef } = useRoomDoc(roomId, { docRef: externalDocRef })

  function handleDisplayNameAccepted(name: string) {
    setDisplayName(name)
    onDisplayNameAccepted?.(name)
  }

  useEffect(() => {
    const doc = docRef.current

    if (isInvalidRoomId || !displayName || !doc || !editor) {
      return
    }

    const validRoomId = roomId
    const acceptedDisplayName = displayName
    const roomDoc = doc
    const roomEditor = editor

    let cancelled = false
    let controlSocket: WebSocket | null = null
    let provider: ReturnType<typeof createDocumentProvider> | null = null
    let binding: MonacoBinding | null = null

    async function connect() {
      try {
        const sessionId = getSessionId()
        const result = await joinRoom(validRoomId, acceptedDisplayName, sessionId)

        if (cancelled) {
          result.socket.close()
          return
        }

        controlSocket = result.socket

        provider = createDocumentProvider(validRoomId, roomDoc, sessionId)

        const model = roomEditor.getModel()
        if (!model || !provider) return

        binding = new MonacoBinding(
          roomDoc.getText('monaco'),
          model,
          new Set([roomEditor]),
          provider.awareness,
        )

        provider.awareness.setLocalStateField('user', {
          name: acceptedDisplayName,
          color:
            provider.awareness.getLocalState()?.user?.color ?? '#3B82F6',
        })

        provider.on('status', ({ status }) => {
          if (status === 'connected') {
            setConnectionStatus('connected')
          } else {
            setConnectionStatus((current) =>
              current === 'not-found' ? 'not-found' : 'connecting',
            )
          }
        })

        provider.on('connection-close', (event) => {
          if (event?.code === 4404) {
            setConnectionStatus('not-found')
          }
        })
      } catch (error) {
        if (cancelled) return

        setConnectionStatus(
          error instanceof Error &&
            error.message.toLowerCase().includes('not found')
            ? 'not-found'
            : 'connection-error',
        )
      }
    }

    void connect()

    return () => {
      cancelled = true
      binding?.destroy()
      provider?.destroy()
      controlSocket?.close()
    }
  }, [
    roomId,
    isInvalidRoomId,
    displayName,
    docRef,
    editor,
  ])


  if (!isInvalidRoomId && displayName === null) {
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
        {(isInvalidRoomId || connectionStatus !== 'connected') && (
          <div
            role={
              isInvalidRoomId || connectionStatus === 'not-found'
                ? 'alert'
                : 'status'
            }
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
            {isInvalidRoomId ? (
              <div>
                <h2>Invalid room link</h2>
                <p>Please check the room URL and try again.</p>
              </div>
            ) : connectionStatus === 'not-found' ? (
              <div>
                <h2>Room not found</h2>
                <p>
                  This room does not exist. Please check your invitation
                  link or create a new room.
                </p>
              </div>
            ) : connectionStatus === 'connection-error' ? (
              <div>
                <h2>Connection failed</h2>
                <p>Unable to connect to the room. Please check your connection and try again.</p>
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
