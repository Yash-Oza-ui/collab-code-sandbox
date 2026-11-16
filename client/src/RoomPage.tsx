import { useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import type { editor as MonacoEditor } from 'monaco-editor'
import type { Awareness } from 'y-protocols/awareness'
import type * as Y from 'yjs'
import Editor from '@monaco-editor/react'
import { MonacoBinding } from 'y-monaco'
import DisplayNamePrompt from './DisplayNamePrompt'
import RunPanel from './RunPanel'
import {
  createAwareness,
  getExistingUserColors,
  setUserAwareness,
  type AwarenessUserState,
} from './awareness'
import {
  connectAwarenessWs,
  getCollabSessionId,
  type AwarenessWsConnection,
  type JoinedMessage,
  type RosterMember,
} from './awarenessWs'
import { ownerTokenKey } from './createRoom'
import { getRoomIdFromPath } from './roomId'
import { useRoomDoc } from './roomDoc'
import type { SupportedLanguage } from './runCode'
import { pickUserColor } from './userColor'
import { config } from './config'

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
   * Optional document ID to bind. In Pair mode, defaults to 'main'.
   * In Open mode, must be a valid registered module_id (US-05 / T-17 V2.5).
   */
  docId?: string
  /**
   * Optional resolver function to determine document ID from joined message in Open mode.
   */
  resolveDocId?: (joined: JoinedMessage) => Promise<string | null> | string | null
  /**
   * Optional WebSocket relay configuration or custom WebSocket implementation for testing.
   */
  wsOptions?: {
    syncWsUrl?: string
    sessionId?: string
    WebSocketClass?: typeof WebSocket
    joinTimeoutMs?: number
    executionApiUrl?: string
    fetchFn?: typeof fetch
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
    docId: propDocId,
    resolveDocId,
    wsOptions,
  } = props
  const roomId = getRoomIdFromPath(window.location.pathname)
  const isInvalidRoomId = !roomId || !/^[A-Za-z0-9_-]{1,64}$/.test(roomId)

  const [displayName, setDisplayName] = useState<string | null>(null)
  const [userColor, setUserColor] = useState<string | null>(null)
  const [joinStatus, setJoinStatus] = useState<RoomJoinStatus>('prompt')
  const [serverError, setServerError] = useState<string | null>(null)
  const [docError, setDocError] = useState<string | null>(null)
  const [pendingName, setPendingName] = useState<string | null>(null)
  const [currentLanguage, setCurrentLanguage] = useState<SupportedLanguage>('python')
  // Role from the server's `joined` or `roster` message (T-10 Host badge / US-27 Observer mode)
  const [role, setRole] = useState<string | null>(null)
  const [myMemberId, setMyMemberId] = useState<string | null>(null)
  const [roster, setRoster] = useState<RosterMember[]>([])
  const [roleChangeError, setRoleChangeError] = useState<string | null>(null)
  const [changingRoleFor, setChangingRoleFor] = useState<string | null>(null)
  const isObserver = role?.toLowerCase() === 'observer'

  // One Y.Doc is created inside effect setup and destroyed in its cleanup (T-01).
  const { docRef } = useRoomDoc(roomId, { docRef: externalDocRef })

  const awarenessInstanceRef = useRef<Awareness | null>(null)
  const connRef = useRef<AwarenessWsConnection | null>(null)
  const pendingNameRef = useRef<string | null>(null)
  const joinStatusRef = useRef<RoomJoinStatus>('prompt')
  const onDisplayNameAcceptedRef = useRef(onDisplayNameAccepted)
  const bindingRef = useRef<MonacoBinding | null>(null)
  const editorRef = useRef<MonacoEditor.IStandaloneCodeEditor | null>(null)
  const sessionIdRef = useRef<string | null>(null)
  const myMemberIdRef = useRef<string | null>(null)

  useEffect(() => {
    onDisplayNameAcceptedRef.current = onDisplayNameAccepted
  }, [onDisplayNameAccepted])

  // Dynamically update Monaco Editor readOnly state if role changes while editor is mounted (US-27)
  useEffect(() => {
    if (editorRef.current) {
      editorRef.current.updateOptions({ readOnly: isObserver })
    }
  }, [isObserver])

  // Manage Awareness instance lifecycle tied to active Y.Doc and WebSocket relay
  useEffect(() => {
    const doc = docRef.current
    if (!doc || isInvalidRoomId) return

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

    // Connect awareness and Yjs document sync to room WebSocket relay if in a room (US-02 / US-05 / T-20)
    let wsCleanup: (() => void) | undefined
    if (roomId) {
      const sessionId = wsOptions?.sessionId ?? getCollabSessionId()
      sessionIdRef.current = sessionId
      const conn = connectAwarenessWs({
        roomId,
        doc,
        docId: propDocId,
        resolveDocId,
        awareness,
        syncWsUrl: wsOptions?.syncWsUrl,
        sessionId,
        WebSocketClass: wsOptions?.WebSocketClass,
        joinTimeoutMs: wsOptions?.joinTimeoutMs,
        onJoined: (joined) => {
          myMemberIdRef.current = joined.member_id ?? null
          setMyMemberId(joined.member_id ?? null)
          setRole(joined.role)
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
        onRoster: (roster) => {
          setRoster(roster.members ?? [])

          if (myMemberIdRef.current) {
            const me = roster.members?.find((m) => m.member_id === myMemberIdRef.current)
            if (me?.role) {
              setRole(me.role)
            }
          }
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
        onJoinTimeout: () => {
          if (joinStatusRef.current === 'joining') {
            setServerError('Join request timed out. The server did not respond. Please try again.')
            setJoinStatus('error')
            joinStatusRef.current = 'error'
          }
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
        onDocError: () => {
          setDocError('Presence connection error. Remote presence synchronization may be degraded.')
        },
        onDocClose: (event) => {
          if (event.code === 4405) {
            setDocError('Document sync error (4405): unknown document or unregistered module.')
          } else if (event.code === 4406) {
            setDocError('Document sync error (4406): document socket opened before room join.')
          } else if (event.code !== 1000) {
            setDocError(`Presence connection closed (${event.code}). Remote presence synchronization stopped.`)
          }
        },
        onEditRejected: (msg) => {
          let reasonText = 'You do not have permission to edit this document.'
          if (msg.reason === 'observer') {
            reasonText = 'Observers cannot edit in this room.'
          } else if (msg.reason === 'not_module_writer') {
            reasonText = 'You are not a registered writer for this module.'
          } else if (msg.reason === 'no_edit_access') {
            reasonText = 'You do not have edit access in this Pair room.'
          }
          setDocError(reasonText)
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
      bindingRef.current?.destroy()
      bindingRef.current = null
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
  }, [roomId, isInvalidRoomId, docRef, externalAwarenessRef, propDocId, resolveDocId, wsOptions])

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
    setDocError(null)
    setJoinStatus('joining')
    joinStatusRef.current = 'joining'

    let ownerToken: string | undefined
    if (roomId && typeof localStorage !== 'undefined') {
      try {
        const stored = localStorage.getItem(ownerTokenKey(roomId))
        if (stored) ownerToken = stored
      } catch {
        // ignore localStorage access issues
      }
    }

    connRef.current?.join(name, ownerToken)
  }

  async function handleMakeObserver(member: RosterMember) {
    if (!roomId || role?.toLowerCase() !== 'host') return
    if (member.member_id === myMemberIdRef.current) return
    if (member.role.toLowerCase() === 'observer') return

    setRoleChangeError(null)
    setChangingRoleFor(member.member_id)

    try {
      const ownerToken = localStorage.getItem(ownerTokenKey(roomId))
      const sessionId = sessionIdRef.current

      if (!ownerToken || !sessionId) {
        setRoleChangeError('Missing Host credentials. Please rejoin the room.')
        return
      }

      const fetchFn = wsOptions?.fetchFn ?? fetch
      const response = await fetchFn(
        `${config.platformApiUrl}/v1/rooms/${encodeURIComponent(roomId)}/members/${encodeURIComponent(member.member_id)}`,
        {
          method: 'PATCH',
          headers: {
            'Content-Type': 'application/json',
            'X-Session-Id': sessionId,
            'X-Owner-Token': ownerToken,
          },
          body: JSON.stringify({ role: 'observer' }),
        },
      )

      if (!response.ok) {
        let errorMessage = `Could not change role (HTTP ${response.status}).`

        try {
          const errorBody = await response.json() as { code?: string; message?: string }
          errorMessage = errorBody.message || errorBody.code || errorMessage
        } catch {
          // Keep the HTTP status message when the response has no JSON body.
        }

        setRoleChangeError(errorMessage)
      }
    } catch {
      setRoleChangeError('Could not connect to the room server. Please try again.')
    } finally {
      setChangingRoleFor(null)
    }
  }

  if (isInvalidRoomId) {
    return (
      <div className="room-page">
        <header className="room-header">
          {roomId ? `Room: ${roomId}` : 'No room id in the URL (expected /room/{room_id})'}
        </header>
        <div className="room-editor" style={{ position: 'relative' }}>
          <div
            role="alert"
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
            <div>
              <h2>Invalid room link</h2>
              <p>Please check the room URL and try again.</p>
            </div>
          </div>
        </div>
      </div>
    )
  }

  if (joinStatus !== 'joined') {
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
        <span>Room: {roomId}</span>
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
            {role?.toLowerCase() === 'host' && (
              <span className="room-host-badge" data-testid="room-host-badge">
                Host
              </span>
            )}
            {isObserver && (
              <span className="room-observer-badge" data-testid="room-observer-badge">
                Observer
              </span>
            )}
          </>
        )}
        {docError && (
          <span
            className="room-doc-error-badge"
            data-testid="room-doc-error"
            role="alert"
            style={{ marginLeft: '1rem', color: '#ef4444' }}
          >
            ⚠️ {docError}
          </span>
        )}
      </header>
      <section className="room-participants" aria-label="Room participants">
        <h3>Participants ({roster.length})</h3>

        {roster.length === 0 ? (
          <p>No participant information available.</p>
        ) : (
          <ul>
            {roster.map((member) => {
              const isSelf = member.member_id === myMemberId
              const isMemberObserver = member.role.toLowerCase() === 'observer'

              return (
                <li key={member.member_id} data-testid={`participant-${member.member_id}`}>
                  <span>
                    {member.display_name}
                    {isSelf ? ' (You)' : ''}
                  </span>

                  <span>{member.role}</span>

                  {role?.toLowerCase() === 'host' && !isSelf && !isMemberObserver && (
                    <button
                      type="button"
                      onClick={() => void handleMakeObserver(member)}
                      disabled={changingRoleFor === member.member_id}
                    >
                      {changingRoleFor === member.member_id
                        ? 'Changing...'
                        : 'Make Observer'}
                    </button>
                  )}

                  {isMemberObserver && <span>Read-only</span>}
                </li>
              )
            })}
          </ul>
        )}

        {roleChangeError && (
          <p role="alert" data-testid="role-change-error">
            {roleChangeError}
          </p>
        )}
      </section>
      <RunPanel
        roomId={roomId || ''}
        getCode={() => docRef.current?.getText('monaco').toString() ?? ''}
        initialLanguage={currentLanguage}
        onLanguageChange={(lang) => setCurrentLanguage(lang)}
        executionApiUrl={wsOptions?.executionApiUrl}
        fetchFn={wsOptions?.fetchFn}
        disabled={isObserver}
        disabledReason={isObserver ? 'Observers cannot run code' : undefined}
      />
      <div className="room-editor">
        <Editor
          height="100%"
          language={currentLanguage}
          defaultLanguage="python"
          defaultValue=""
          options={{ readOnly: isObserver }}
          onMount={(editor: MonacoEditor.IStandaloneCodeEditor) => {
            editorRef.current = editor
            editor.updateOptions({ readOnly: isObserver })
            const doc = docRef.current
            const awareness = awarenessInstanceRef.current
            if (doc && awareness) {
              const model = editor.getModel()
              if (model) {
                bindingRef.current = new MonacoBinding(
                  doc.getText('monaco'),
                  model,
                  new Set([editor]),
                  awareness,
                )
              }
            }
          }}
        />
      </div>
    </div>
  )
}
