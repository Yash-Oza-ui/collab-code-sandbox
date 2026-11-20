import { config } from './config'

// Host-only REST calls for the participant list (US-28 / V2.18, US-54 / V2.19).
// The server is the enforcement (NFR13): every call sends the caller's session id and the
// owner token, and a non-Host gets 403 NOT_OWNER. Hiding a button is never the gate.
// Nothing here logs or returns the owner token.

export interface HostCallContext {
  roomId: string
  /** The browser's own session id (X-Session-Id). */
  sessionId: string
  /** The Host's owner token (X-Owner-Token). */
  ownerToken: string
  fetchFn?: typeof fetch
  /** Base URL of sync-service REST; defaults to the platform API URL from config. */
  baseUrl?: string
}

export type HostApiResult<T> =
  | { ok: true; data: T }
  | { ok: false; status: number; code?: string; error: string }

export interface BlockedMember {
  member_id: string
  display_name: string
  /** RFC 3339 UTC time of the removal. */
  removed_at: string
}

const ROOM_ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/

/** Plain-words text for an error code of V2.12; the server message is the fallback. */
function errorText(code: string | undefined, serverMessage: string | undefined, status: number): string {
  switch (code) {
    case 'NOT_OWNER':
      return 'Only the Host can do this.'
    case 'MEMBER_NOT_FOUND':
      return 'That person is no longer in the room.'
    case 'ROOM_NOT_FOUND':
      return 'This room no longer exists.'
    case 'SESSION_NOT_FOUND':
      return 'You are not in this room. Please rejoin.'
    default:
      return serverMessage || code || `The request failed (HTTP ${status}).`
  }
}

async function hostRequest<T>(
  ctx: HostCallContext,
  method: 'GET' | 'POST',
  path: string,
  parse: (raw: unknown) => T | null,
): Promise<HostApiResult<T>> {
  const fetchFn = ctx.fetchFn ?? fetch
  const base = ctx.baseUrl ?? config.platformApiUrl
  let response: Response
  try {
    response = await fetchFn(`${base}/v1/rooms/${encodeURIComponent(ctx.roomId)}${path}`, {
      method,
      headers: { 'X-Session-Id': ctx.sessionId, 'X-Owner-Token': ctx.ownerToken },
    })
  } catch {
    return { ok: false, status: 0, code: 'NETWORK', error: 'Could not connect to the room server. Please try again.' }
  }

  let body: unknown
  try {
    body = await response.json()
  } catch {
    body = null
  }

  if (!response.ok) {
    const envelope = (body as { error?: { code?: unknown; message?: unknown } } | null)?.error
    const code = typeof envelope?.code === 'string' ? envelope.code : undefined
    const message = typeof envelope?.message === 'string' ? envelope.message : undefined
    return { ok: false, status: response.status, code, error: errorText(code, message, response.status) }
  }

  const data = parse(body)
  if (data === null) {
    return { ok: false, status: response.status, code: 'BAD_RESPONSE', error: 'The room server sent an unexpected answer.' }
  }
  return { ok: true, data }
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** Remove a participant: they are blocked and their sockets close with 4403 (V2.18). */
export function removeMember(ctx: HostCallContext, memberId: string) {
  return hostRequest(ctx, 'POST', `/members/${encodeURIComponent(memberId)}/remove`, (raw) =>
    isObject(raw) && raw.status === 'removed' ? { member_id: String(raw.member_id ?? memberId) } : null,
  )
}

/** Re-admit a removed member by the member_id the block list shows (V2.18). */
export function readmitMember(ctx: HostCallContext, memberId: string) {
  return hostRequest(ctx, 'POST', `/members/${encodeURIComponent(memberId)}/readmit`, (raw) =>
    isObject(raw) && raw.status === 'readmitted' ? { member_id: String(raw.member_id ?? memberId) } : null,
  )
}

/** Removed members, newest data from the server; never contains a session id (V2.18). */
export function listBlockedMembers(ctx: HostCallContext) {
  return hostRequest<BlockedMember[]>(ctx, 'GET', '/members/blocked', (raw) => {
    if (!isObject(raw) || !Array.isArray(raw.blocked)) return null
    const out: BlockedMember[] = []
    for (const item of raw.blocked) {
      if (
        !isObject(item) ||
        typeof item.member_id !== 'string' ||
        typeof item.display_name !== 'string' ||
        typeof item.removed_at !== 'string'
      ) {
        return null
      }
      out.push({ member_id: item.member_id, display_name: item.display_name, removed_at: item.removed_at })
    }
    return out
  })
}

/** Burn the current link: the room moves to a new id (V2.18). */
export function regenerateLink(ctx: HostCallContext) {
  return hostRequest(ctx, 'POST', '/regenerate-link', (raw) =>
    isObject(raw) && raw.status === 'regenerated' && typeof raw.room_id === 'string' && ROOM_ID_PATTERN.test(raw.room_id)
      ? { room_id: raw.room_id }
      : null,
  )
}

/** Hand the Host role to a participant; the new token comes only as `host-granted` (V2.19). */
export function makeHost(ctx: HostCallContext, memberId: string) {
  return hostRequest(ctx, 'POST', `/members/${encodeURIComponent(memberId)}/make-host`, (raw) =>
    isObject(raw) && raw.status === 'transferred' && typeof raw.host_member_id === 'string'
      ? { host_member_id: raw.host_member_id }
      : null,
  )
}
