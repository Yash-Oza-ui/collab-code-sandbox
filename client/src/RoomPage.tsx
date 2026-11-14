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
  type AwarenessUserState,
} from './awareness'
import { connectAwarenessWs, type AwarenessWsConnection } from './awarenessWs'
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
  /** Called once a guest has supplied a valid name and joined; ready for awareness integration. */
  onDisplayNameAccepted?: (displayName: string, userColor?: string) => void
  /**
   * Optional WebSocket relay configuration or custom WebSocket implementation for testing.
   */
  wsOptions?: {
    syncWsUrl?: string
    sessionId?: string
    WebSocketClass?: typeof WebSocket
  }
}

export type RoomJoinStatus = 'prompt' | 'joining' | 'joined' | 'refused' | 'error'

// Reads the room id from the URL (T-06 section 3.2: /room/{room_id}).
// Manages the Y.Doc lifecycle inside effect setup/cleanup (T-01).
// Manages the Yjs Awareness lifecycle and user presence colour assignment (US-05 / FR14).
export default function RoomPage(props: RoomPageProps = {}) {
  const {
    docRef: externalDocRef,
    awarenessRef: externalAwarenessRef,
    onDisplayNameAccepted,
    wsOptions,
  } = props
  const roomId = getRoomIdFromPath(window.location.pathname)
  const [displayName, setDisplayName] = useState<string | null>(null)
  const [userColor, setUserColor] = useState<string | null>(null)
  const [joinStatus, setJoinStatus] = useState<RoomJoinStatus>('prompt')
  const [serverError, setServerError] = useState<string | null>(null)
  const [pendingName, setPendingName] = useState<string | null>(null)

  // One Y.Doc is created inside effect setup and destroyed in its cleanup (T-01).
  const { docRef } = useRoomDoc(roomId, { docRef: externalDocRef })

  const awarenessInstanceRef = useRef<Awareness | null>(null)
  const connRef = useRef<AwarenessWsConnection | null>(null)
  const pendingNameRef = useRef<string | null>(null)
  const joinStatusRef = useRef<RoomJoinStatus>('prompt')
  const onDisplayNameAcceptedRef = useRef(onDisplayNameAccepted)

  useEffect(() => {
    onDisplayNameAcceptedRef.current = onDisplayNameAccepted
  }, [onDisplayNameAccepted])

  // Manage Awareness instance lifecycle tied to active Y.Doc and WebSocket relay
  useEffect(() => {
    const doc = docRef.current
    if (!doc) return

    const awareness = createAwareness(doc)
    awarenessInstanceRef.current = awareness
    if (externalAwarenessRef) {
      externalAwarenessRef.current = awareness
    }

    // Expose awareness on window for browser console inspection (US-05 DoD #5)
    if (typeof window !== 'undefined') {
      ;(window as unknown as { awareness?: Awareness }).awareness = awareness
    }

    // Listen to remote awareness changes to automatically avoid color collisions once document socket connects
    const handleAwarenessChange = () => {
      const localState = awareness.getLocalState() as AwarenessUserState | null
      const myColor = localState?.user?.color
      const myName = localState?.user?.name
      if (!myColor || !myName) return

      const otherColors: string[] = []
      let collisionWithLowerClientId = false

      awareness.getStates().forEach((peerState, peerClientId) => {
        if (peerClientId === awareness.clientID) return
        const peer = (peerState as AwarenessUserState)?.user
        if (peer?.color) {
          otherColors.push(peer.color)
          if (peer.color.toLowerCase() === myColor.toLowerCase()) {
            if (peerClientId < awareness.clientID) {
              collisionWithLowerClientId = true
            }
          }
        }
      })

      if (collisionWithLowerClientId) {
        const newColor = pickUserColor(otherColors, awareness.clientID)
        if (newColor !== myColor) {
          setUserAwareness(awareness, { name: myName, color: newColor })
          setUserColor(newColor)
        }
      }
    }

    awareness.on('change', handleAwarenessChange)

    // Connect awareness to room WebSocket relay if in a room (US-05 / T-20)
    let wsCleanup: (() => void) | undefined
    if (roomId) {
      const conn = connectAwarenessWs({
        roomId,
        docId: 'main',
        awareness,
        syncWsUrl: wsOptions?.syncWsUrl,
        sessionId: wsOptions?.sessionId,
        WebSocketClass: wsOptions?.WebSocketClass,
        onJoined: () => {
          const name = pendingNameRef.current
          if (!name) return

          const existingColors = getExistingUserColors(awareness)
          const fallbackIndex = doc.clientID ?? 0
          const color = pickUserColor(existingColors, fallbackIndex)

          setUserAwareness(awareness, { name, color })
          setDisplayName(name)
          setUserColor(color)
          setServerError(null)
          setJoinStatus('joined')
          joinStatusRef.current = 'joined'
          onDisplayNameAcceptedRef.current?.(name, color)
        },
        onJoinRefused: (msg) => {
          let message = msg.message
          if (!message) {
            if (msg.reason === 'NAME_TAKEN') {
              message = `The display name "${pendingNameRef.current ?? ''}" is already taken in this room. Please choose another name.`
            } else if (msg.reason === 'ROOM_FULL') {
              message = 'This room has reached maximum capacity.'
            } else {
              message = `Join refused: ${msg.reason}`
            }
          }
          setServerError(message)
          setJoinStatus('refused')
          joinStatusRef.current = 'refused'
        },
        onError: () => {
          if (joinStatusRef.current !== 'joined' && joinStatusRef.current !== 'refused') {
            setServerError('Failed to connect to the room server. Please check your network connection.')
            setJoinStatus('error')
            joinStatusRef.current = 'error'
          }
        },
        onClose: (event) => {
          if (joinStatusRef.current === 'refused') {
            // Keep the refusal message intact
            return
          }
          if (joinStatusRef.current === 'joining') {
            let msg = 'Connection closed before joining the room.'
            if (event.code === 4404) {
              msg = 'Room not found (4404). Please verify the room link.'
            } else if (event.code === 4410) {
              msg = 'This room has been closed.'
            }
            setServerError(msg)
            setJoinStatus('error')
            joinStatusRef.current = 'error'
          } else if (joinStatusRef.current === 'prompt') {
            setServerError('Unable to connect to the room server. Please check your connection.')
            setJoinStatus('error')
            joinStatusRef.current = 'error'
          }
        },
      })
      connRef.current = conn
      wsCleanup = () => {
        conn.destroy()
        if (connRef.current === conn) {
          connRef.current = null
        }
      }
    }

    return () => {
      awareness.off('change', handleAwarenessChange)
      wsCleanup?.()
      awareness.destroy()
      if (awarenessInstanceRef.current === awareness) {
        awarenessInstanceRef.current = null
      }
      if (externalAwarenessRef && externalAwarenessRef.current === awareness) {
        externalAwarenessRef.current = null
      }
      if (
        typeof window !== 'undefined' &&
        (window as unknown as { awareness?: Awareness }).awareness === awareness
      ) {
        delete (window as unknown as { awareness?: Awareness }).awareness
      }
    }
  }, [roomId, docRef, externalAwarenessRef, wsOptions])

  // Keep presence updated in awareness whenever displayName and userColor are set or change
  useEffect(() => {
    const awareness = awarenessInstanceRef.current
    if (awareness && displayName && userColor && joinStatus === 'joined') {
      setUserAwareness(awareness, { name: displayName, color: userColor })
    }
  }, [displayName, userColor, joinStatus])

  function handleDisplayNameAccepted(name: string) {
    if (joinStatus === 'joining') return
    setPendingName(name)
    pendingNameRef.current = name
    setServerError(null)
    setJoinStatus('joining')
    joinStatusRef.current = 'joining'
    connRef.current?.join(name)
  }

  if (roomId && joinStatus !== 'joined') {
    return (
      <DisplayNamePrompt
        onSubmit={handleDisplayNameAccepted}
        serverError={serverError}
        submitting={joinStatus === 'joining'}
        initialValue={pendingName ?? ''}
      />
    )
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
