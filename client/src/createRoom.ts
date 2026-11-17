import { config } from './config'
import { getRoomIdFromPath } from './roomId'

// localStorage key prefix for the owner token, one entry per room.
// The key name is a proposal: docs/api-contracts.md only says "keyed by room id".
export const OWNER_TOKEN_KEY_PREFIX = 'collab_owner_token:'

export function ownerTokenKey(roomId: string): string {
  return `${OWNER_TOKEN_KEY_PREFIX}${roomId}`
}

export type RoomMode = 'pair' | 'open'
export type PairPreset = 'teach_interview' | 'debug_together'
export type WorkspaceLanguage = 'cpp' | 'python' | 'c' | 'java' | 'javascript'
export type CreateRoomChoice =
  | { mode: 'pair'; preset: PairPreset }
  | { mode: 'open'; language: WorkspaceLanguage }

export function buildCreateRoomBody(choice: CreateRoomChoice): CreateRoomChoice {
  return choice.mode === 'pair'
    ? { mode: 'pair', preset: choice.preset }
    : { mode: 'open', language: choice.language }
}

export interface CreatedRoom {
  roomId: string
  ownerTokenStored: boolean
}

function errorMessageFrom(body: unknown, status: number): string {
  if (typeof body === 'object' && body !== null) {
    const error = (body as { error?: unknown }).error
    if (typeof error === 'object' && error !== null) {
      const message = (error as { message?: unknown }).message
      if (typeof message === 'string' && message.length > 0) return message
    }
  }
  return `Room creation failed (HTTP ${status}).`
}

// POST /v1/rooms on sync-service (docs/api-contracts.md, V2.1).
// The server generates the room id; the owner token is returned once, so it is
// stored here. It is never logged, shown or put in a URL, and is not yet sent in
// "join" (follow-up PR after T-10).
export async function createRoom(
  choice: CreateRoomChoice,
  platformApiUrl: string = config.platformApiUrl,
): Promise<CreatedRoom> {
  const url = `${platformApiUrl.replace(/\/+$/, '')}/v1/rooms`

  let response: Response
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(buildCreateRoomBody(choice)),
    })
  } catch {
    throw new Error(
      'Unable to reach the room server. Check that sync-service is running.',
    )
  }

  const body: unknown = await response.json().catch(() => null)

  if (!response.ok) {
    throw new Error(errorMessageFrom(body, response.status))
  }

  const fields = (body ?? {}) as { room_id?: unknown; owner_token?: unknown }
  const roomId = fields.room_id
  const ownerToken = fields.owner_token
  if (
    typeof roomId !== 'string' ||
    getRoomIdFromPath(`/room/${roomId}`) !== roomId ||
    typeof ownerToken !== 'string' ||
    ownerToken.length === 0
  ) {
    throw new Error('The room server sent an invalid response.')
  }

  let ownerTokenStored = true
  try {
    localStorage.setItem(ownerTokenKey(roomId), ownerToken)
  } catch {
    ownerTokenStored = false
  }
  return { roomId, ownerTokenStored }
}
