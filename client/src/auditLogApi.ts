import { config } from './config'

export interface AuditLogEntry {
  id: string
  timestamp: string
  action: string
  actor: string | null
  actor_display_name: string | null
  actor_member_id: string | null
  target: string | null
  target_display_name: string | null
  target_member_id: string | null
  property: string
  old_value: unknown
  new_value: unknown
  details: string | Record<string, unknown> | null
}

export type AuditLogFetchResult =
  | { ok: true; entries: AuditLogEntry[] }
  | { ok: false; error: string }

export async function fetchAuditLog(params: {
  roomId: string
  sessionId: string
  baseUrl?: string
  fetchFn?: typeof fetch
}): Promise<AuditLogFetchResult> {
  const fetchFn = params.fetchFn ?? fetch
  const base = params.baseUrl ?? config.platformApiUrl
  let response: Response
  try {
    response = await fetchFn(
      `${base}/v1/rooms/${encodeURIComponent(params.roomId)}/audit-log`,
      {
        method: 'GET',
        headers: { 'X-Session-Id': params.sessionId },
      },
    )
  } catch {
    return { ok: false, error: 'Could not load activity log. Please try again.' }
  }

  let body: unknown
  try {
    body = await response.json()
  } catch {
    body = null
  }

  if (!response.ok) {
    const msg = (body as { error?: { message?: unknown } } | null)?.error?.message
    return {
      ok: false,
      error: typeof msg === 'string' ? msg : `Could not load activity log (HTTP ${response.status}).`,
    }
  }

  const rawEntries = Array.isArray(body)
    ? body
    : typeof body === 'object' && body !== null && Array.isArray((body as { entries?: unknown }).entries)
      ? (body as { entries: unknown[] }).entries
      : null

  if (!rawEntries) {
    return { ok: false, error: 'The room server sent an unexpected activity log.' }
  }

  return { ok: true, entries: rawEntries as AuditLogEntry[] }
}

function actorName(entry: AuditLogEntry): string {
  return entry.actor_display_name || entry.actor || 'Host'
}

function targetName(entry: AuditLogEntry): string {
  return entry.target_display_name || entry.target || 'a member'
}

export function formatAuditEntry(entry: AuditLogEntry): string {
  const actor = actorName(entry)
  const target = targetName(entry)

  switch (entry.action) {
    case 'role_change': {
      const nextRole = String(entry.new_value ?? '').toLowerCase()
      if (nextRole === 'observer') return `${actor} made ${target} an Observer`
      if (nextRole === 'participant') return `${actor} made ${target} a Participant`
      return `${actor} changed ${target}'s role from ${String(entry.old_value)} to ${String(entry.new_value)}`
    }
    case 'host_transfer':
      return `${actor} transferred Host to ${target}`
    case 'edit_access':
      return entry.new_value === true
        ? `${actor} gave ${target} edit access`
        : `${actor} took edit access from ${target}`
    case 'run_permission':
      return entry.new_value === true
        ? `${actor} turned on Run for ${target}`
        : `${actor} turned off Run for ${target}`
    case 'host_only_run':
      return entry.new_value === true
        ? `${actor} enabled Host-only run`
        : `${actor} disabled Host-only run`
    case 'module_write_access': {
        const mod =
            typeof entry.details === 'string'
            ? entry.details.trim() || 'module'
            : (typeof entry.details?.module_name === 'string' && entry.details.module_name) ||
                (typeof entry.details?.name === 'string' && entry.details.name) ||
                (typeof entry.details?.module_id === 'string' && entry.details.module_id) ||
                'module'
        return entry.new_value === true
            ? `${actor} granted ${target} write access to module ${mod}`
            : `${actor} revoked ${target}'s write access to module ${mod}`
    }
    default:
      return `${actor} updated ${entry.property}${entry.target_display_name || entry.target ? ` for ${target}` : ''} (${String(entry.old_value)} → ${String(entry.new_value)})`
  }
}

export function formatAuditTimestamp(timestamp: string): string {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}
