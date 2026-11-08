
import { useEffect, useState } from 'react'
import type { RefObject } from 'react'
import type { editor as MonacoEditor } from 'monaco-editor'
import type * as Y from 'yjs'
import Editor from '@monaco-editor/react'
import { MonacoBinding } from 'y-monaco'

import DisplayNamePrompt from './DisplayNamePrompt'
import { getRoomIdFromPath } from './roomId'
import { useRoomDoc } from './roomDoc'
import {
  createDocumentProvider,
  getSessionId,
  joinRoom,
} from './roomConnection'

export interface RoomPageProps {
  docRef?: RefObject<Y.Doc | null>
  onDisplayNameAccepted?: (displayName: string) => void
}

export default function RoomPage(props: RoomPageProps = {}) {
  const { docRef: externalDocRef, onDisplayNameAccepted } = props

  const roomId = getRoomIdFromPath(window.location.pathname)

  const [displayName, setDisplayName] = useState<string | null>(null)
  const [connectionStatus, setConnectionStatus] = useState('disconnected')
  const [connectionError, setConnectionError] = useState<string | null>(null)

  const [documentProvider, setDocumentProvider] =
    useState<ReturnType<typeof createDocumentProvider> | null>(null)

  const [editorInstance, setEditorInstance] =
    useState<MonacoEditor.IStandaloneCodeEditor | null>(null)

  const { docRef } = useRoomDoc(roomId, {
    docRef: externalDocRef,
  })

  function handleDisplayNameAccepted(name: string) {
    setConnectionError(null)
    setDisplayName(name)
    onDisplayNameAccepted?.(name)
  }

  useEffect(() => {
    if (!roomId || !displayName) return
    const activeRoomId = roomId
    const activeDisplayName = displayName
    let cancelled = false
    let controlSocket: WebSocket | null = null
    let provider: ReturnType<typeof createDocumentProvider> | null = null

    async function connect() {
      try {
        const doc = docRef.current

        if (!doc) {
          throw new Error('Room document is not ready.')
        }

        setConnectionStatus('connecting')
        setConnectionError(null)

        const sessionId = getSessionId()

        const result = await joinRoom(
          activeRoomId,
          activeDisplayName,
          sessionId,
        )
        controlSocket = result.socket

        if (cancelled) {
          controlSocket.close()
          return
        }

        provider = createDocumentProvider(
          activeRoomId,
          doc,
          sessionId,
        )

        provider.on('status', ({ status }) => {
          if (!cancelled) {
            setConnectionStatus(status)
          }
        })

        setDocumentProvider(provider)
      } catch (error) {
        provider?.destroy()
        provider = null

        controlSocket?.close()
        controlSocket = null

        if (!cancelled) {
          setDocumentProvider(null)
          setConnectionStatus('disconnected')
          setConnectionError(
            error instanceof Error
              ? error.message
              : 'Room connection failed.',
          )
        }
      }
    }

    void connect()

    return () => {
      cancelled = true
      provider?.destroy()
      controlSocket?.close()
    }
  }, [roomId, displayName, docRef])

  useEffect(() => {
    if (!editorInstance || !documentProvider) return

    const doc = docRef.current
    const model = editorInstance.getModel()

    if (!doc || !model) return

    const binding = new MonacoBinding(
      doc.getText('monaco'),
      model,
      new Set([editorInstance]),
      documentProvider.awareness,
    )

    return () => {
      binding.destroy()
    }
  }, [editorInstance, documentProvider, docRef])

  if (!roomId) {
    return (
      <div className="room-page">
        <p role="alert">
          Invalid room link. Expected URL format: /room/{'{room_id}'}.
        </p>
      </div>
    )
  }

  if (displayName === null) {
    return (
      <DisplayNamePrompt
        onSubmit={handleDisplayNameAccepted}
      />
    )
  }

  return (
    <div className="room-page">
      <header className="room-header">
        Room: {roomId}
        {' · You: '}
        {displayName}
        {' · Connection: '}
        {connectionStatus}
      </header>

      {connectionError && (
        <div>
          <p role="alert">{connectionError}</p>
          <button
            type="button"
            onClick={() => {
              setDocumentProvider(null)
              setConnectionError(null)
              setDisplayName(null)
              setConnectionStatus('disconnected')
            }}
          >
            Try again
          </button>
        </div>
      )}

      {!connectionError && !documentProvider && (
        <p role="status">Joining room...</p>
      )}

      {documentProvider && (
        <div className="room-editor">
          <Editor
            height="100%"
            defaultLanguage="plaintext"
            defaultValue=""
            onMount={(editor) => {
              setEditorInstance(editor)
            }}
          />
        </div>
      )}
    </div>
  )
}
