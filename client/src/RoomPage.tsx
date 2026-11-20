import { useCallback, useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import type { editor as MonacoEditor } from 'monaco-editor'
import type { Awareness } from 'y-protocols/awareness'
import * as Y from 'yjs'
import Editor from '@monaco-editor/react'
import { MonacoBinding } from 'y-monaco'
import DisplayNamePrompt from './DisplayNamePrompt'
import RunPanel from './RunPanel'
import RunOutputPanel from './RunOutputPanel'
import {
  applyRosterNamesToAwareness,
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
  type RunDeniedReason,
  CLOSE_LINK_REGENERATED,
  CLOSE_REMOVED,
  CLOSE_ROOM_CLOSED,
  CLOSE_UNKNOWN_ROOM,
} from './awarenessWs'
import { ownerTokenKey } from './createRoom'
import {
  listBlockedMembers,
  makeHost,
  readmitMember,
  regenerateLink,
  removeMember,
  type BlockedMember,
  type HostApiResult,
  type HostCallContext,
} from './hostApi'
import { getRoomIdFromPath } from './roomId'
import { useRoomDoc } from './roomDoc'
import { parseRunResultFrame, runNotAllowedMessage } from './runCode'
import type { RunResponseData, SupportedLanguage } from './runCode'
import { pickUserColor, USER_COLORS } from './userColor'
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
  /** Other registered Open-mode Yjs documents to keep synchronized while inactive. */
  moduleDocs?: ReadonlyMap<string, Y.Doc>
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
  /**
   * Opens another page (default: a full page load). Used after the Host regenerates the link
   * (US-28); a test can pass a spy instead.
   */
  navigate?: (path: string) => void
}

export type RoomJoinStatus = 'prompt' | 'joining' | 'joined' | 'refused' | 'error'

function readOwnerToken(roomId: string): string | undefined {
  try {
    return localStorage.getItem(ownerTokenKey(roomId)) || undefined
  } catch {
    // Storage access may be disabled. The join can proceed without a token.
    return undefined
  }
}

function writeOwnerToken(roomId: string, token: string): boolean {
  try {
    localStorage.setItem(ownerTokenKey(roomId), token)
    return true
  } catch {
    return false
  }
}

function dropOwnerToken(roomId: string): void {
  try {
    localStorage.removeItem(ownerTokenKey(roomId))
  } catch {
    // Storage access may be disabled; there is nothing stored to drop.
  }
}

// Messages for the signals that end a session for good (V2.18, "Client reconnect").
const REMOVED_MESSAGE = 'The Host has removed you from this room.'
const LINK_REGENERATED_MESSAGE =
  'The Host changed the room link. Ask the Host for the new link to rejoin.'

// Reads the room id from the URL (T-06 section 3.2: /room/{room_id}).
// Manages the Y.Doc lifecycle inside effect setup/cleanup (T-01).
// Manages the Yjs Awareness lifecycle and user presence colour assignment (US-05 / FR14).
export default function RoomPage(props: RoomPageProps = {}) {
  const {
    docRef: externalDocRef,
    awarenessRef: externalAwarenessRef,
    onDisplayNameAccepted,
    docId: propDocId,
    moduleDocs,
    resolveDocId,
    wsOptions,
    navigate = (path: string) => window.location.assign(path),
  } = props
  const roomId = getRoomIdFromPath(window.location.pathname)
  const isInvalidRoomId = !roomId || !/^[A-Za-z0-9_-]{1,64}$/.test(roomId)

  const [displayName, setDisplayName] = useState<string | null>(null)
  const [userColor, setUserColor] = useState<string | null>(null)
  const [joinStatus, setJoinStatus] = useState<RoomJoinStatus>('prompt')
  const [serverError, setServerError] = useState<string | null>(null)
  const [docError, setDocError] = useState<string | null>(null)
  const [reconnecting, setReconnecting] = useState(false)
  const [pendingName, setPendingName] = useState<string | null>(null)
  const [currentLanguage, setCurrentLanguage] = useState<SupportedLanguage>('python')
  const [runOutput, setRunOutput] = useState<RunResponseData | null>(null)
  // Role from the server's `joined` or `roster` message (T-10 Host badge / US-27 Observer mode)
  const [roomMode, setRoomMode] = useState<{ mode: string; detail?: string } | null>(null)
  const [role, setRole] = useState<string | null>(null)
  const [myMemberId, setMyMemberId] = useState<string | null>(null)
  const [roster, setRoster] = useState<RosterMember[]>([])
  const rosterRef = useRef<RosterMember[]>([])
  const [activePresence, setActivePresence] = useState<
    Array<{ member: RosterMember; color: string }>
  >([])
  const [roleChangeError, setRoleChangeError] = useState<string | null>(null)
  const [changingRoleFor, setChangingRoleFor] = useState<string | null>(null)
  const isObserver = role?.toLowerCase() === 'observer'
  // Run permission (US-60, V2.20): the server's answer for this member, from joined / roster.
  // Unknown (older server) is treated as allowed; the server still checks every run (FR41.4).
  const [myCanRun, setMyCanRun] = useState<boolean | null>(null)
  const [myRunDeniedReason, setMyRunDeniedReason] = useState<RunDeniedReason | null>(null)
  const [hostOnlyRun, setHostOnlyRun] = useState(false)
  const [runControlError, setRunControlError] = useState<string | null>(null)
  const [changingRunFor, setChangingRunFor] = useState<string | null>(null)
  const [changingHostOnly, setChangingHostOnly] = useState(false)
  const isHost = role?.toLowerCase() === 'host'
  const runDisabled = isObserver || myCanRun === false
  const runDisabledReason = runDisabled
    ? isObserver
      ? 'Observers cannot run code' // unchanged US-27 wording
      : runNotAllowedMessage(myRunDeniedReason ?? undefined)
    : undefined
  // Host controls (US-28 remove / re-admit / regenerate link, US-54 make Host).
  const [blocked, setBlocked] = useState<BlockedMember[]>([])
  const [hostActionError, setHostActionError] = useState<string | null>(null)
  const [busyHostAction, setBusyHostAction] = useState<string | null>(null)
  // Bumped to reload the removed-members list (after a change, or once new credentials arrive).
  const [hostDataVersion, setHostDataVersion] = useState(0)
  const roleRef = useRef<string | null>(null)
  // Set while this tab is the one moving the room to a new link, so its own 4411 close is not an error.
  const regeneratingRef = useRef(false)

  // One Y.Doc is created inside effect setup and destroyed in its cleanup (T-01).
  const { docRef } = useRoomDoc(roomId, { docRef: externalDocRef })

  const awarenessInstanceRef = useRef<Awareness | null>(null)
  const connRef = useRef<AwarenessWsConnection | null>(null)
  const pendingNameRef = useRef<string | null>(null)
  // A room-specific name is safe to retain; the session ID remains stable separately.
  const resumeNameKey = roomId ? `collab_room_name:${roomId}:${wsOptions?.sessionId ?? getCollabSessionId()}` : null
  const joinStatusRef = useRef<RoomJoinStatus>('prompt')
  const onDisplayNameAcceptedRef = useRef(onDisplayNameAccepted)
  const bindingRef = useRef<MonacoBinding | null>(null)
  const editorRef = useRef<MonacoEditor.IStandaloneCodeEditor | null>(null)
  const remoteCursorDecorationsRef = useRef<string[]>([])
  const sessionIdRef = useRef<string | null>(null)
  // request_ids already shown: a result can arrive twice (own HTTP response and the room frame), first one wins
  const shownRunIdsRef = useRef<string[]>([])
  const myMemberIdRef = useRef<string | null>(null)

  const renderRemoteCursorLabels = useCallback(() => {
    const editor = editorRef.current
    const doc = docRef.current
    const awareness = awarenessInstanceRef.current
    const model = editor?.getModel()

    if (!editor || !doc || !awareness || !model) return

    const ytext = doc.getText('monaco')
    const decorations: MonacoEditor.IModelDeltaDecoration[] = []

    awareness.getStates().forEach((state, clientID) => {
      if (clientID === awareness.clientID) return

      const user = (state as AwarenessUserState)?.user
      const selection = (
        state as AwarenessUserState & {
          selection?: { head?: Y.RelativePosition }
        }
      ).selection

      if (!user?.name || !user.color || !selection?.head) return

      const member = rosterRef.current.find(
        (entry) => entry.member_id === user.member_id,
      )

      if (!member) return

      const colorIndex = USER_COLORS.findIndex(
        (color) => color.toLowerCase() === user.color.toLowerCase(),
      )

      if (colorIndex === -1) return

      const absolutePosition = Y.createAbsolutePositionFromRelativePosition(
        selection.head,
        doc,
      )

      if (!absolutePosition || absolutePosition.type !== ytext) return

      const position = model.getPositionAt(absolutePosition.index)

      decorations.push({
        range: {
          startLineNumber: position.lineNumber,
          startColumn: position.column,
          endLineNumber: position.lineNumber,
          endColumn: position.column,
        },
        options: {
          after: {
            content: ` ${member.display_name} · ${member.role.toLowerCase()}`,
            inlineClassName: `remote-cursor-label remote-cursor-label-${colorIndex}`,
          },
        },
      })
    })

    remoteCursorDecorationsRef.current = editor.deltaDecorations(
      remoteCursorDecorationsRef.current,
      decorations,
    )
  }, [docRef])

  const updateActivePresence = useCallback(() => {
    const awareness = awarenessInstanceRef.current

    if (!awareness) {
      setActivePresence([])
      return
    }

    const seenByMemberId = new Map<string, { member: RosterMember; color: string }>()

    awareness.getStates().forEach((state) => {
      const user = (state as AwarenessUserState)?.user

      if (!user?.member_id || !user.color) return

      const member = rosterRef.current.find(
        (entry) => entry.member_id === user.member_id,
      )

      if (!member || seenByMemberId.has(member.member_id)) return

      seenByMemberId.set(member.member_id, {
        member,
        color: user.color,
      })
    })

    setActivePresence(Array.from(seenByMemberId.values()))
  }, [])

  // One place for role changes (joined, roster). A role that is not Host means any stored owner
  // token is retired, so the previous Host drops it after a transfer (V2.19) and the Host
  // controls follow the role.
  const applyRole = useCallback(
    (nextRole: string) => {
      roleRef.current = nextRole
      setRole(nextRole)
      if (roomId && nextRole.toLowerCase() !== 'host') dropOwnerToken(roomId)
    },
    [roomId],
  )

  const showRunResult = useCallback((data: RunResponseData) => {
    const shown = shownRunIdsRef.current
    if (shown.includes(data.request_id)) return
    shown.push(data.request_id)
    if (shown.length > 50) shown.shift()
    setRunOutput(data)
  }, [])

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
      // Projection is applied again after each incoming remote update.
      // Do not trust peer-supplied awareness.name for cursor labels.
      applyRosterNamesToAwareness(awareness, rosterRef.current)
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
          setUserAwareness(awareness, {
            name: myName,
            color: newColor,
            member_id: localState?.user?.member_id,
          })
          setUserColor(newColor)
        }
      }
    }

    awareness.on('change', handleAwarenessChange)

    const handleRemoteCursorChange = () => {
      renderRemoteCursorLabels()
      updateActivePresence()
    }

    awareness.on('change', handleRemoteCursorChange)

    // Connect awareness and Yjs document sync to room WebSocket relay if in a room (US-02 / US-05 / T-20)
    let wsCleanup: (() => void) | undefined
    if (roomId) {
      const sessionId = wsOptions?.sessionId ?? getCollabSessionId()
      sessionIdRef.current = sessionId
      const conn = connectAwarenessWs({
        roomId,
        doc,
        docId: propDocId,
        moduleDocs,
        resolveDocId,
        awareness,
        syncWsUrl: wsOptions?.syncWsUrl,
        sessionId,
        WebSocketClass: wsOptions?.WebSocketClass,
        joinTimeoutMs: wsOptions?.joinTimeoutMs,
        onReconnecting: () => setReconnecting(true),
        onReconnected: () => {
          setReconnecting(false)
          setDocError(null)
        },
        onJoined: (joined) => {
          myMemberIdRef.current = joined.member_id ?? null
          setMyMemberId(joined.member_id ?? null)
          applyRole(joined.role)
          setMyCanRun(typeof joined.permissions?.can_run === 'boolean' ? joined.permissions.can_run : null)
          setMyRunDeniedReason(joined.permissions?.run_denied_reason ?? null)
          // V2.3 joined carries mode and preset, but no Open workspace language.
          // Do not infer it from creation state or the shared URL.
          setRoomMode({
            mode: joined.mode,
            detail: joined.mode === 'pair' ? joined.preset : undefined,
          })
          const name = pendingNameRef.current
          if (!name) return

          const existingColors = getExistingUserColors(awareness)
          const fallbackIndex = doc.clientID ?? 0
          const color = pickUserColor(existingColors, fallbackIndex)

          setUserAwareness(awareness, { name, color, member_id: joined.member_id })
          setDisplayName(name)
          setUserColor(color)
          setServerError(null)
          setJoinStatus('joined')
          joinStatusRef.current = 'joined'
          if (resumeNameKey) {
            try { sessionStorage.setItem(resumeNameKey, name) } catch { /* storage disabled */ }
          }
          onDisplayNameAcceptedRef.current?.(name, color)
        },
        onRoster: (roster) => {
          rosterRef.current = roster.members ?? []
          setRoster(rosterRef.current)
          applyRosterNamesToAwareness(awareness, rosterRef.current)
          renderRemoteCursorLabels()
          updateActivePresence()
          if (myMemberIdRef.current) {
            const me = roster.members?.find((m) => m.member_id === myMemberIdRef.current)
            if (me?.role) {
              applyRole(me.role)
            }
            // The roster (V2.4), not the submitted name or awareness, is
            // authoritative for what teammates see. This also handles a
            // second tab rejoining a session with a differently cased name.
            if (me?.display_name && joinStatusRef.current === 'joined') {
              setDisplayName(me.display_name)
              const current = awareness.getLocalState() as AwarenessUserState | null
              if (current?.user?.color) {
                setUserAwareness(awareness, {
                  ...current.user,
                  name: me.display_name,
                  member_id: me.member_id,
                })
              }
              if (resumeNameKey) {
                try { sessionStorage.setItem(resumeNameKey, me.display_name) } catch { /* storage disabled */ }
              }
            }
            if (me?.permissions && typeof me.permissions.can_run === 'boolean') {
              setMyCanRun(me.permissions.can_run)
              setMyRunDeniedReason(me.permissions.run_denied_reason ?? null)
            }
          }
          setHostOnlyRun(roster.host_only_run === true)
        },
        onHostGranted: (granted) => {
          // V2.19: only the new Host gets this. Store the new token; it replaces the old one
          // for every owner action. The token is never logged or shown.
          if (!roomId || granted.member_id !== myMemberIdRef.current) return
          if (typeof granted.owner_token !== 'string' || granted.owner_token === '') return
          if (!writeOwnerToken(roomId, granted.owner_token)) {
            setHostActionError('Could not save the Host credentials in this browser.')
            return
          }
          roleRef.current = 'host'
          setRole('host')
          setHostActionError(null)
          setHostDataVersion((v) => v + 1)
        },
        onJoinRefused: (msg) => {
          let message = msg.message
          if (msg.reason === 'USER_BLOCKED') {
            // V2.18: the exact wording, and no retry (the socket layer already stopped).
            message = REMOVED_MESSAGE
          } else if (!message) {
            if (msg.reason === 'NAME_TAKEN') {
              message = `The display name "${pendingNameRef.current ?? ''}" is already taken in this room. Please choose another name.`
            } else if (msg.reason === 'ROOM_FULL') {
              message = 'This room has reached maximum capacity.'
            } else {
              message = `Join refused: ${msg.reason}`
            }
          }
          setServerError(message)
          setReconnecting(false)
          setJoinStatus('refused')
          joinStatusRef.current = 'refused'
          if (resumeNameKey) {
            try { sessionStorage.removeItem(resumeNameKey) } catch { /* storage disabled */ }
          }
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
          if ([CLOSE_REMOVED, CLOSE_UNKNOWN_ROOM, CLOSE_ROOM_CLOSED, CLOSE_LINK_REGENERATED].includes(event.code)) {
            // None of these can ever succeed on a retry (V2.18); the socket layer has stopped.
            if (event.code === CLOSE_LINK_REGENERATED && regeneratingRef.current) return
            setReconnecting(false)
            setServerError(
              event.code === CLOSE_REMOVED
                ? REMOVED_MESSAGE
                : event.code === CLOSE_UNKNOWN_ROOM
                  ? 'Room not found (4404). Please verify the room link.'
                  : event.code === CLOSE_LINK_REGENERATED
                    ? LINK_REGENERATED_MESSAGE
                    : 'This room has been closed.',
            )
            setJoinStatus('error')
            joinStatusRef.current = 'error'
            if (event.code !== CLOSE_UNKNOWN_ROOM && event.code !== CLOSE_ROOM_CLOSED && resumeNameKey) {
              // No automatic rejoin of the old id on the next load.
              try { sessionStorage.removeItem(resumeNameKey) } catch { /* storage disabled */ }
            }
            return
          }
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
        onRunResult: (msg) => {
          const parsed = parseRunResultFrame(msg, roomId)
          if (parsed.valid) showRunResult(parsed.data)
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
      // A refresh creates a new document, but must rejoin with the same identity
      // before opening document sockets. Never restore a name for another room.
      if (resumeNameKey && !pendingNameRef.current) {
        let savedName: string | null = null
        try {
          savedName = sessionStorage.getItem(resumeNameKey)
        } catch {
          // Storage may be disabled; the manual join flow remains available.
        }

        if (savedName) {
          const ownerToken = readOwnerToken(roomId)
          pendingNameRef.current = savedName
          setPendingName(savedName)
          setJoinStatus('joining')
          joinStatusRef.current = 'joining'
          conn.join(savedName, ownerToken)
        }
      }
      wsCleanup = () => {
        conn.destroy()
        if (connRef.current === conn) {
          connRef.current = null
        }
      }
    }

    return () => {
      awareness.off('change', handleAwarenessChange)
      awareness.off('change', handleRemoteCursorChange)

      const editor = editorRef.current
      if (editor && typeof editor.deltaDecorations === 'function') {
        editor.deltaDecorations(remoteCursorDecorationsRef.current, [])
      }

      remoteCursorDecorationsRef.current = []
      setActivePresence([])

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
  }, [roomId, isInvalidRoomId, docRef, externalAwarenessRef, propDocId, moduleDocs, resolveDocId, wsOptions, showRunResult, applyRole, resumeNameKey, renderRemoteCursorLabels, updateActivePresence])

  // Keep presence updated in awareness whenever displayName and userColor are set or change
  useEffect(() => {
    const awareness = awarenessInstanceRef.current
    if (awareness && displayName && userColor && joinStatus === 'joined') {
      setUserAwareness(awareness, {
        name: displayName,
        color: userColor,
        member_id: myMemberIdRef.current ?? undefined,
      })
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

    connRef.current?.join(name, roomId ? readOwnerToken(roomId) : undefined)
  }

  // Host credentials for a REST call, or null if this browser has no session or owner token.
  const getHostContext = useCallback((): HostCallContext | null => {
    if (!roomId) return null
    const sessionId = sessionIdRef.current
    const ownerToken = readOwnerToken(roomId)
    if (!sessionId || !ownerToken) return null
    return { roomId, sessionId, ownerToken, fetchFn: wsOptions?.fetchFn }
  }, [roomId, wsOptions?.fetchFn])

  // Keep the removed-members list (the re-admit UI) current for the Host, also after a refresh.
  useEffect(() => {
    if (!isHost) return // the list is only rendered for the Host
    const ctx = getHostContext()
    if (!ctx) return // credentials not stored yet (host-granted reloads this)
    let cancelled = false
    void listBlockedMembers(ctx).then((result) => {
      if (cancelled) return
      if (result.ok) setBlocked(result.data)
      else setHostActionError(result.error)
    })
    return () => {
      cancelled = true
    }
  }, [isHost, getHostContext, hostDataVersion])

  async function runHostAction<T>(
    key: string,
    call: (ctx: HostCallContext) => Promise<HostApiResult<T>>,
    onSuccess?: (data: T) => void,
  ) {
    if (!isHost) return
    setHostActionError(null)
    setBusyHostAction(key)
    try {
      const ctx = getHostContext()
      if (!ctx) {
        setHostActionError('Missing Host credentials. Please rejoin the room.')
        return
      }
      const result = await call(ctx)
      if (!result.ok) {
        setHostActionError(result.error)
        return
      }
      onSuccess?.(result.data)
    } finally {
      setBusyHostAction(null)
    }
  }

  function handleRemove(member: RosterMember) {
    if (member.member_id === myMemberIdRef.current) return
    // The roster follows from the server; the blocked list is reloaded for the re-admit UI.
    void runHostAction(`remove:${member.member_id}`, (ctx) => removeMember(ctx, member.member_id), () =>
      setHostDataVersion((v) => v + 1),
    )
  }

  function handleReadmit(entry: BlockedMember) {
    void runHostAction(`readmit:${entry.member_id}`, (ctx) => readmitMember(ctx, entry.member_id), () =>
      setHostDataVersion((v) => v + 1),
    )
  }

  function handleMakeHost(member: RosterMember) {
    if (member.member_id === myMemberIdRef.current) return
    if (member.role.toLowerCase() !== 'participant') return
    if (!window.confirm(`Make ${member.display_name} the Host? You will lose the Host controls.`)) return
    // No role change here: the new roster says who is Host, and this browser drops its token then.
    void runHostAction(`make-host:${member.member_id}`, (ctx) => makeHost(ctx, member.member_id))
  }

  function handleRegenerateLink() {
    if (!roomId) return
    if (
      !window.confirm(
        'Regenerate the room link? The old link stops working for everyone, and you will have to share the new link yourself.',
      )
    ) {
      return
    }
    void runHostAction('regenerate', (ctx) => regenerateLink(ctx), ({ room_id: newRoomId }) => {
      // The Host keeps the same owner token; only the room id changes (V2.18). Carry the token and
      // the name over, then open the new link, so this tab returns with the same member_id.
      const token = readOwnerToken(roomId)
      if (token) writeOwnerToken(newRoomId, token)
      dropOwnerToken(roomId)
      if (displayName && sessionIdRef.current) {
        try {
          sessionStorage.setItem(`collab_room_name:${newRoomId}:${sessionIdRef.current}`, displayName)
        } catch {
          // Storage disabled: the Host is asked for the name again on the new link.
        }
      }
      regeneratingRef.current = true
      navigate(`/room/${encodeURIComponent(newRoomId)}`)
    })
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

  /**
   * A Host-only run-permission request (US-60, V2.20). Returns an error text, or null on
   * success; the change itself comes back to every client as a new `roster`.
   */
  async function sendRunPermissionRequest(path: string, body: Record<string, boolean>): Promise<string | null> {
    if (!roomId || !isHost) return 'Only the Host can change who can run.'
    try {
      const ownerToken = localStorage.getItem(ownerTokenKey(roomId))
      const sessionId = sessionIdRef.current
      if (!ownerToken || !sessionId) {
        return 'Missing Host credentials. Please rejoin the room.'
      }
      const fetchFn = wsOptions?.fetchFn ?? fetch
      const response = await fetchFn(`${config.platformApiUrl}/v1/rooms/${encodeURIComponent(roomId)}${path}`, {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json',
          'X-Session-Id': sessionId,
          'X-Owner-Token': ownerToken,
        },
        body: JSON.stringify(body),
      })
      if (response.ok) return null
      let message = `Could not change run permission (HTTP ${response.status}).`
      try {
        const errorBody = (await response.json()) as { error?: { message?: string; code?: string } }
        message = errorBody.error?.message || errorBody.error?.code || message
      } catch {
        // Keep the HTTP status message when the response has no JSON body.
      }
      return message
    } catch {
      return 'Could not connect to the room server. Please try again.'
    }
  }

  async function handleToggleMemberRun(member: RosterMember, allow: boolean) {
    setRunControlError(null)
    setChangingRunFor(member.member_id)
    try {
      const error = await sendRunPermissionRequest(
        `/members/${encodeURIComponent(member.member_id)}/run-permission`,
        { can_run: allow },
      )
      if (error) setRunControlError(error)
    } finally {
      setChangingRunFor(null)
    }
  }

  async function handleToggleHostOnly(next: boolean) {
    setRunControlError(null)
    setChangingHostOnly(true)
    try {
      const error = await sendRunPermissionRequest('/run-policy', { host_only_run: next })
      if (error) setRunControlError(error)
    } finally {
      setChangingHostOnly(false)
    }
  }

  // A run-result frame can carry a stale or untrusted display_name. Only the
  // server roster's member_id -> display_name mapping may label the runner.
  const runnerMember = runOutput?.runner?.member_id
    ? roster.find((member) => member.member_id === runOutput.runner?.member_id)
    : undefined
  const displayRunOutput = runOutput
    ? {
        ...runOutput,
        runner: runOutput.runner
          ? { ...runOutput.runner, display_name: runnerMember?.display_name }
          : undefined,
      }
    : null

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
      {reconnecting && <div role="status" aria-live="polite">Reconnecting to room...</div>}
      <header className="room-header">
        <span>Room: {roomId}</span>
        {roomMode && (
          <span className="room-mode-badge" data-testid="room-mode-badge">
            {roomMode.mode === 'pair' ? 'Pair' : 'Open'}{roomMode.detail ? ` · ${roomMode.detail}` : ''}
          </span>
        )}
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

                  {isHost && !isSelf && !isMemberObserver && member.role.toLowerCase() !== 'host' && (
                    member.permissions?.run_denied_reason === 'run_revoked' ? (
                      <button
                        type="button"
                        data-testid={`run-on-${member.member_id}`}
                        onClick={() => void handleToggleMemberRun(member, true)}
                        disabled={changingRunFor === member.member_id}
                      >
                        {changingRunFor === member.member_id ? 'Changing...' : 'Turn on Run'}
                      </button>
                    ) : (
                      <button
                        type="button"
                        data-testid={`run-off-${member.member_id}`}
                        onClick={() => void handleToggleMemberRun(member, false)}
                        disabled={changingRunFor === member.member_id}
                      >
                        {changingRunFor === member.member_id ? 'Changing...' : 'Turn off Run'}
                      </button>
                    )
                  )}

                  {isHost && !isSelf && member.role.toLowerCase() === 'participant' && (
                    <button
                      type="button"
                      data-testid={`make-host-${member.member_id}`}
                      onClick={() => handleMakeHost(member)}
                      disabled={busyHostAction !== null}
                    >
                      {busyHostAction === `make-host:${member.member_id}` ? 'Changing...' : 'Make Host'}
                    </button>
                  )}

                  {isHost && !isSelf && member.role.toLowerCase() !== 'host' && (
                    <button
                      type="button"
                      data-testid={`remove-${member.member_id}`}
                      onClick={() => handleRemove(member)}
                      disabled={busyHostAction !== null}
                    >
                      {busyHostAction === `remove:${member.member_id}` ? 'Removing...' : 'Remove'}
                    </button>
                  )}
                </li>
              )
            })}
          </ul>
        )}

        <div className="active-presence" data-testid="active-presence">
          <h4>Active now ({activePresence.length})</h4>

          {activePresence.length === 0 ? (
            <p>No active collaborators.</p>
          ) : (
            <ul>
              {activePresence.map(({ member, color }) => (
                <li
                  key={member.member_id}
                  data-testid={`active-presence-${member.member_id}`}
                >
                  <span
                    aria-hidden="true"
                    className="active-presence-color"
                    style={{ backgroundColor: color }}
                  />
                  <span>{member.display_name}</span>
                  <span>{member.role.toLowerCase()}</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        {roleChangeError && (
          <p role="alert" data-testid="role-change-error">
            {roleChangeError}
          </p>
        )}

        {isHost && (
          <label data-testid="host-only-run-label">
            <input
              type="checkbox"
              data-testid="host-only-run-toggle"
              checked={hostOnlyRun}
              disabled={changingHostOnly}
              onChange={(e) => void handleToggleHostOnly(e.target.checked)}
            />{' '}
            Only the Host can run code
          </label>
        )}
        {!isHost && hostOnlyRun && (
          <p data-testid="host-only-run-notice">Only the Host can run code in this room right now.</p>
        )}

        {runControlError && (
          <p role="alert" data-testid="run-control-error">
            {runControlError}
          </p>
        )}

        {isHost && blocked.length > 0 && (
          <div data-testid="blocked-members" aria-label="Removed members">
            <h4>Removed ({blocked.length})</h4>
            <ul>
              {blocked.map((entry) => (
                <li key={entry.member_id} data-testid={`blocked-${entry.member_id}`}>
                  <span>{entry.display_name}</span>
                  <button
                    type="button"
                    data-testid={`readmit-${entry.member_id}`}
                    onClick={() => handleReadmit(entry)}
                    disabled={busyHostAction !== null}
                  >
                    {busyHostAction === `readmit:${entry.member_id}` ? 'Re-admitting...' : 'Re-admit'}
                  </button>
                </li>
              ))}
            </ul>
            <p>A re-admitted person can open the room link again to rejoin.</p>
          </div>
        )}

        {isHost && (
          <button
            type="button"
            data-testid="regenerate-link-button"
            onClick={handleRegenerateLink}
            disabled={busyHostAction !== null}
          >
            {busyHostAction === 'regenerate' ? 'Regenerating...' : 'Regenerate link'}
          </button>
        )}

        {hostActionError && (
          <p role="alert" data-testid="host-action-error">
            {hostActionError}
          </p>
        )}
      </section>
      {runDisabledReason && (
        <p data-testid="run-disabled-reason" role="status">
          {runDisabledReason}
        </p>
      )}
      <RunPanel
        roomId={roomId || ''}
        onRunComplete={(result) => {
          if (result.ok) showRunResult(result.data)
        }}
        getCode={() => docRef.current?.getText('monaco').toString() ?? ''}
        initialLanguage={currentLanguage}
        onLanguageChange={(lang) => setCurrentLanguage(lang)}
        executionApiUrl={wsOptions?.executionApiUrl}
        fetchFn={wsOptions?.fetchFn}
        disabled={runDisabled}
        disabledReason={runDisabledReason}
      >
        {displayRunOutput ? <RunOutputPanel result={displayRunOutput} /> : null}
      </RunPanel>
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
            renderRemoteCursorLabels()
          }}
        />
      </div>
    </div>
  )
}
