
import { WebsocketProvider } from 'y-websocket'
import type * as Y from 'yjs'
import { config } from './config'

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

export async function joinRoom(
  roomId: string,
  displayName: string,
  sessionId: string,
): Promise<{ socket: WebSocket; joined: JoinedMessage }> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(
      createControlSocketUrl(roomId, sessionId),
    )

    let settled = false

    function fail(message: string) {
      if (settled) return

      settled = true
      socket.close()
      reject(new Error(message))
    }

    socket.onopen = () => {
      socket.send(
        JSON.stringify({
          type: 'join',
          display_name: displayName,
        }),
      )
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
        settled = true

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
