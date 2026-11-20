import { config } from './config'

export interface WorkspaceModule {
  module_id: string
  name: string
  owner_member_id: string
  writers?: string[]
  runnable?: boolean
  is_entry?: boolean
  version?: number
  pending?: boolean
}

export interface WorkspaceApiContext {
  roomId: string
  sessionId: string
  ownerToken?: string
  fetchFn?: typeof fetch
  baseUrl?: string
}

export type WorkspaceApiResult<T> =
  | { ok: true; data: T }
  | { ok: false; status: number; code?: string; error: string }

export const COURSEWORK_EXTENSIONS: Record<string, string[]> = {
  python: ['.py'],
  javascript: ['.js'],
  java: ['.java'],
  cpp: ['.cpp', '.cc', '.cxx'],
  c: ['.c'],
}

/**
 * Validates a module file name against DR4 rules for the given language.
 */
export function validateModuleName(
  name: string,
  language?: string,
): { valid: boolean; error?: string } {
  if (!name || !name.trim()) {
    return { valid: false, error: "Field 'name' is missing or invalid." }
  }

  const trimmed = name.trim()

  if (trimmed.includes('/') || trimmed.includes('\\') || trimmed.includes('..')) {
    return {
      valid: false,
      error: `File name '${trimmed}' is invalid. Plain file name with language extension required; directory traversal is forbidden.`,
    }
  }

  if (trimmed.startsWith('.')) {
    return {
      valid: false,
      error: `File name '${trimmed}' is invalid. Hidden files and bare extensions are not allowed.`,
    }
  }

  if (language) {
    const langKey = language.toLowerCase()
    const validExts = COURSEWORK_EXTENSIONS[langKey]
    if (validExts) {
      const lower = trimmed.toLowerCase()
      const hasValidExt = validExts.some((ext) => lower.endsWith(ext) && lower.length > ext.length)
      if (!hasValidExt) {
        return {
          valid: false,
          error: `File name '${trimmed}' does not match language '${language}'.`,
        }
      }
    }
  }

  return { valid: true }
}

/**
 * Returns default extension for a coursework language (e.g. 'py' for python).
 */
export function defaultLanguageExtension(language?: string): string {
  if (!language) return 'txt'
  const langKey = language.toLowerCase()
  return COURSEWORK_EXTENSIONS[langKey]?.[0]?.replace(/^\./, '') ?? 'txt'
}

/**
 * Lists modules in an Open-mode room (V2.6).
 */
export async function listModules(
  ctx: WorkspaceApiContext,
): Promise<WorkspaceApiResult<WorkspaceModule[]>> {
  const fetchFn = ctx.fetchFn ?? fetch
  const base = (
    ctx.baseUrl ??
    config.syncWsUrl.replace(/^ws/, 'http').replace(/\/ws\/rooms.*$/, '')
  ).replace(/\/$/, '')

  try {
    const res = await fetchFn(`${base}/v1/rooms/${encodeURIComponent(ctx.roomId)}/modules`, {
      method: 'GET',
      headers: {
        'X-Session-Id': ctx.sessionId,
      },
    })

    if (!res.ok) {
      let code: string | undefined
      let message: string | undefined
      try {
        const body = (await res.json()) as { error?: { code?: string; message?: string } }
        code = body?.error?.code
        message = body?.error?.message
      } catch {
        // non-json
      }
      return {
        ok: false,
        status: res.status,
        code,
        error: message || code || `Failed to list modules (HTTP ${res.status}).`,
      }
    }

    const data = (await res.json()) as WorkspaceModule[]
    return { ok: true, data: Array.isArray(data) ? data : [] }
  } catch (err) {
    return {
      ok: false,
      status: 0,
      error: err instanceof Error ? err.message : 'Network error while listing modules.',
    }
  }
}

/**
 * Creates or requests a module in an Open-mode room (V2.6, US-39).
 */
export async function createModule(
  ctx: WorkspaceApiContext,
  name: string,
): Promise<WorkspaceApiResult<WorkspaceModule>> {
  const fetchFn = ctx.fetchFn ?? fetch
  const base = (
    ctx.baseUrl ??
    config.syncWsUrl.replace(/^ws/, 'http').replace(/\/ws\/rooms.*$/, '')
  ).replace(/\/$/, '')

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Session-Id': ctx.sessionId,
  }
  if (ctx.ownerToken) {
    headers['X-Owner-Token'] = ctx.ownerToken
  }

  try {
    const res = await fetchFn(`${base}/v1/rooms/${encodeURIComponent(ctx.roomId)}/modules`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ name }),
    })

    if (res.status !== 201 && res.status !== 202) {
      let code: string | undefined
      let message: string | undefined
      try {
        const body = (await res.json()) as { error?: { code?: string; message?: string } }
        code = body?.error?.code
        message = body?.error?.message
      } catch {
        // non-json
      }
      return {
        ok: false,
        status: res.status,
        code,
        error: message || code || `Failed to create module (HTTP ${res.status}).`,
      }
    }

    const data = (await res.json()) as WorkspaceModule
    return { ok: true, data }
  } catch (err) {
    return {
      ok: false,
      status: 0,
      error: err instanceof Error ? err.message : 'Network error while creating module.',
    }
  }
}

/**
 * Requests creation of a module by a participant (US-40, FR21.1).
 */
export async function requestModule(
  ctx: WorkspaceApiContext,
  name: string,
): Promise<WorkspaceApiResult<WorkspaceModule>> {
  return createModule({ ...ctx, ownerToken: undefined }, name)
}

/**
 * Host approves a pending module (US-40, FR21.2).
 */
export async function approveModule(
  ctx: WorkspaceApiContext,
  moduleId: string,
  ownerMemberId?: string,
): Promise<WorkspaceApiResult<WorkspaceModule>> {
  const fetchFn = ctx.fetchFn ?? fetch
  const base = (
    ctx.baseUrl ??
    config.syncWsUrl.replace(/^ws/, 'http').replace(/\/ws\/rooms.*$/, '')
  ).replace(/\/$/, '')

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Session-Id': ctx.sessionId,
  }
  if (ctx.ownerToken) {
    headers['X-Owner-Token'] = ctx.ownerToken
  }

  const body = ownerMemberId ? JSON.stringify({ owner_member_id: ownerMemberId }) : '{}'

  try {
    const res = await fetchFn(
      `${base}/v1/rooms/${encodeURIComponent(ctx.roomId)}/modules/${encodeURIComponent(moduleId)}/approve`,
      {
        method: 'POST',
        headers,
        body,
      },
    )

    if (!res.ok) {
      let code: string | undefined
      let message: string | undefined
      try {
        const data = (await res.json()) as { error?: { code?: string; message?: string } }
        code = data?.error?.code
        message = data?.error?.message
      } catch {
        // non-json
      }
      return {
        ok: false,
        status: res.status,
        code,
        error: message || code || `Failed to approve module (HTTP ${res.status}).`,
      }
    }

    const data = (await res.json()) as WorkspaceModule
    return { ok: true, data }
  } catch (err) {
    return {
      ok: false,
      status: 0,
      error: err instanceof Error ? err.message : 'Network error while approving module.',
    }
  }
}

/**
 * Host declines a pending module (US-40, FR21.2).
 */
export async function declineModule(
  ctx: WorkspaceApiContext,
  moduleId: string,
): Promise<WorkspaceApiResult<{ module_id: string; status: string }>> {
  const fetchFn = ctx.fetchFn ?? fetch
  const base = (
    ctx.baseUrl ??
    config.syncWsUrl.replace(/^ws/, 'http').replace(/\/ws\/rooms.*$/, '')
  ).replace(/\/$/, '')

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Session-Id': ctx.sessionId,
  }
  if (ctx.ownerToken) {
    headers['X-Owner-Token'] = ctx.ownerToken
  }

  try {
    const res = await fetchFn(
      `${base}/v1/rooms/${encodeURIComponent(ctx.roomId)}/modules/${encodeURIComponent(moduleId)}/decline`,
      {
        method: 'POST',
        headers,
      },
    )

    if (!res.ok) {
      let code: string | undefined
      let message: string | undefined
      try {
        const data = (await res.json()) as { error?: { code?: string; message?: string } }
        code = data?.error?.code
        message = data?.error?.message
      } catch {
        // non-json
      }
      return {
        ok: false,
        status: res.status,
        code,
        error: message || code || `Failed to decline module (HTTP ${res.status}).`,
      }
    }

    const data = (await res.json()) as { module_id: string; status: string }
    return { ok: true, data }
  } catch (err) {
    return {
      ok: false,
      status: 0,
      error: err instanceof Error ? err.message : 'Network error while declining module.',
    }
  }
}

/**
 * Lists pending module requests for Host (US-40).
 */
export async function listPendingModules(
  ctx: WorkspaceApiContext,
): Promise<WorkspaceApiResult<WorkspaceModule[]>> {
  const fetchFn = ctx.fetchFn ?? fetch
  const base = (
    ctx.baseUrl ??
    config.syncWsUrl.replace(/^ws/, 'http').replace(/\/ws\/rooms.*$/, '')
  ).replace(/\/$/, '')

  const headers: Record<string, string> = {
    'X-Session-Id': ctx.sessionId,
  }
  if (ctx.ownerToken) {
    headers['X-Owner-Token'] = ctx.ownerToken
  }

  try {
    const res = await fetchFn(
      `${base}/v1/rooms/${encodeURIComponent(ctx.roomId)}/modules?pending=true`,
      {
        method: 'GET',
        headers,
      },
    )

    if (!res.ok) {
      let code: string | undefined
      let message: string | undefined
      try {
        const body = (await res.json()) as { error?: { code?: string; message?: string } }
        code = body?.error?.code
        message = body?.error?.message
      } catch {
        // non-json
      }
      return {
        ok: false,
        status: res.status,
        code,
        error: message || code || `Failed to list pending modules (HTTP ${res.status}).`,
      }
    }

    const data = (await res.json()) as WorkspaceModule[]
    return { ok: true, data: Array.isArray(data) ? data : [] }
  } catch (err) {
    return {
      ok: false,
      status: 0,
      error: err instanceof Error ? err.message : 'Network error while listing pending modules.',
    }
  }
}
