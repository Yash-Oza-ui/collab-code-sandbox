
import { WebsocketProvider } from 'y-websocket'
import type * as Y from 'yjs'
import { config } from './config'
import { ownerTokenKey } from './createRoom'

export interface JoinedMessage {
  type: 'joined'
  member_id?: string
  role?: string
  mode?: string
  preset?: string
  permissions?: unknown
}

export function createControlSocketUrl(
  roomId: string,
  sessionId: string,
): string {
  const baseUrl = config.syncWsUrl.replace(/\/$/, '')

  return `${baseUrl}/${encodeURIComponent(roomId)}?session_id=${encodeURIComponent(sessionId)}`
}

export function createDocumentProvider(
  roomId: string,
  doc: Y.Doc,
  sessionId: string,
): WebsocketProvider {
  const baseUrl = config.syncWsUrl.replace(/\/$/, '')

  return new WebsocketProvider(
    `${baseUrl}/${encodeURIComponent(roomId)}/docs`,
    'main',
    doc,
    {
      params: {
        session_id: sessionId,
      },
    },
  )
}


export interface JoinRoomOptions {
  signal?: AbortSignal
  timeoutMs?: number
}

const DEFAULT_JOIN_TIMEOUT_MS = 15_000

export async function joinRoom(
  roomId: string,
  displayName: string,
  sessionId: string,
  options: JoinRoomOptions = {},
): Promise<{ socket: WebSocket; joined: JoinedMessage }> {
  return new Promise((resolve, reject) => {
    const { signal, timeoutMs = DEFAULT_JOIN_TIMEOUT_MS } = options

    if (signal?.aborted) {
      reject(new Error('Room connection cancelled.'))
      return
    }

    const socket = new WebSocket(
      createControlSocketUrl(roomId, sessionId),
    )

    let settled = false
    let timeout: ReturnType<typeof setTimeout> | undefined

    function cleanup() {
      if (timeout !== undefined) {
        clearTimeout(timeout)
        timeout = undefined
      }

      signal?.removeEventListener('abort', handleAbort)

      socket.onopen = null
      socket.onmessage = null
      socket.onerror = null
      socket.onclose = null
    }

    function fail(message: string) {
      if (settled) return

      settled = true
      cleanup()

      try {
        socket.close()
      } catch {
        // The socket may already be closed.
      }

      reject(new Error(message))
    }

    function handleAbort() {
      fail('Room connection cancelled.')
    }

    signal?.addEventListener('abort', handleAbort)

    timeout = setTimeout(() => {
      fail('Room connection timed out. Please try again.')
    }, timeoutMs)

    socket.onopen = () => {
      let ownerToken: string | null = null

      try {
        ownerToken = localStorage.getItem(ownerTokenKey(roomId))
      } catch {
        // Storage may be unavailable; join without Host ownership.
      }

      const joinMessage: Record<string, string> = {
        type: 'join',
        display_name: displayName,
      }

      if (typeof ownerToken === 'string' && ownerToken.length > 0) {
        joinMessage.owner_token = ownerToken
      }

      try {
        socket.send(JSON.stringify(joinMessage))
      } catch {
        fail('Unable to send the room join request.')
      }
    }

    socket.onmessage = (event: MessageEvent) => {
      let message: Record<string, unknown>

      try {
        message = JSON.parse(String(event.data))
      } catch {
        fail('The server sent an invalid response.')
        return
      }

      if (message.type === 'joined') {
        if (settled) return

        settled = true
        cleanup()

        const joined: JoinedMessage = {
          type: 'joined',
          ...(typeof message.member_id === 'string'
            ? { member_id: message.member_id }
            : {}),
          ...(typeof message.role === 'string'
            ? { role: message.role }
            : {}),
          ...(typeof message.mode === 'string'
            ? { mode: message.mode }
            : {}),
          ...(typeof message.preset === 'string'
            ? { preset: message.preset }
            : {}),
          ...(message.permissions !== undefined
            ? { permissions: message.permissions }
            : {}),
        }

        resolve({ socket, joined })
        return
      }

      if (message.type === 'join-refused') {
        const reason =
          typeof message.reason === 'string'
            ? message.reason
            : 'UNKNOWN'

        fail(`Room join refused: ${reason}`)
      }
    }

    socket.onerror = () => {
      fail('Unable to connect to the room server.')
    }

    socket.onclose = (event: CloseEvent) => {
      if (!settled) {
        fail(
          event.code === 4404
            ? 'Room not found.'
            : `Room connection closed (${event.code}).`,
        )
      }
    }
  })
}
