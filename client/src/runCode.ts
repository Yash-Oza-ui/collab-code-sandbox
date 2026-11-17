/**
 * Run Code Execution Module (T-11, T-19, US-08)
 *
 * Implements the client-side API client for POST /v1/runs matching the
 * T-17 / T-19 execution contract (Decision 8 & Decision 9).
 */

import { config } from './config'
import { getSessionId } from './sessionId'

/** The five DR1 supported languages */
export type SupportedLanguage = 'python' | 'javascript' | 'c' | 'cpp' | 'java'

export interface LanguageOption {
  id: string
  label: string
  monacoLanguage: string
  fileExtension: string
  available: boolean
}

export const DR1_LANGUAGES: LanguageOption[] = [
  { id: 'python', label: 'Python', monacoLanguage: 'python', fileExtension: 'py', available: true },
  { id: 'javascript', label: 'JavaScript', monacoLanguage: 'javascript', fileExtension: 'js', available: true },
  { id: 'c', label: 'C', monacoLanguage: 'c', fileExtension: 'c', available: true },
  { id: 'cpp', label: 'C++', monacoLanguage: 'cpp', fileExtension: 'cpp', available: true },
  { id: 'java', label: 'Java', monacoLanguage: 'java', fileExtension: 'java', available: true },
]

/** Extended list including languages planned for future sprints */
export const ALL_LANGUAGE_OPTIONS: LanguageOption[] = [
  ...DR1_LANGUAGES,
  { id: 'rust', label: 'Rust', monacoLanguage: 'rust', fileExtension: 'rs', available: false },
  { id: 'go', label: 'Go', monacoLanguage: 'go', fileExtension: 'go', available: false },
  { id: 'ruby', label: 'Ruby', monacoLanguage: 'ruby', fileExtension: 'rb', available: false },
]

export function isLanguageSupported(language: string): boolean {
  return DR1_LANGUAGES.some((lang) => lang.id.toLowerCase() === language.toLowerCase() && lang.available)
}

export function getLanguageOption(language: string): LanguageOption | undefined {
  const normalized = language.toLowerCase()
  return (
    ALL_LANGUAGE_OPTIONS.find((lang) => lang.id.toLowerCase() === normalized) ||
    DR1_LANGUAGES.find((lang) => lang.id.toLowerCase() === normalized)
  )
}

export function getLanguageExtension(language: string): string {
  const opt = getLanguageOption(language)
  if (opt) return opt.fileExtension
  switch (language.toLowerCase()) {
    case 'python':
      return 'py'
    case 'javascript':
      return 'js'
    case 'java':
      return 'java'
    case 'cpp':
      return 'cpp'
    case 'c':
      return 'c'
    default:
      return 'py'
  }
}

export function getEntryFileName(language: string): string {
  return `main.${getLanguageExtension(language)}`
}

/**
 * Generates a fresh, cryptographically secure UUID v4.
 * Uses crypto.randomUUID() when available, with a fallback to crypto.getRandomValues().
 * Fails gracefully if secure randomness is not available in the environment.
 */
export function generateSecureUUID(): string {
  if (typeof crypto !== 'undefined') {
    if (typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID()
    }
    if (typeof crypto.getRandomValues === 'function') {
      const bytes = new Uint8Array(16)
      crypto.getRandomValues(bytes)
      // RFC 4122 Section 4.4: Set version to 4 (0100)
      bytes[6] = (bytes[6] & 0x0f) | 0x40
      // Set variant to RFC 4122 (10xx)
      bytes[8] = (bytes[8] & 0x3f) | 0x80
      const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`
    }
  }
  throw new Error('Cryptographically secure randomness is not available in this environment.')
}

export interface RunFile {
  name: string
  content: string
}

export interface InlineSource {
  kind: 'inline'
  files: RunFile[]
  entry: string
}

export interface RunRequestPayload {
  request_id: string
  room_id: string
  session_id: string
  language: string
  stdin?: string
  source: InlineSource
}

export interface RunnerMemberInfo {
  member_id?: string
  display_name?: string
}

export interface RunResponseData {
  request_id: string
  room_id: string
  language: string
  stdout: string
  stderr: string
  exit_code: number
  execution_time_ms: number
  peak_memory_mb: number
  timed_out: boolean
  oom_killed: boolean
  failure_kind: string | null
  output_truncated: boolean
  message: string | null
  compile_time_ms: number | null
  runner?: RunnerMemberInfo
  ran_at?: string
  visibility?: string
  saved_versions?: unknown[]
  failure?: unknown
}

export interface RunErrorEnvelope {
  error?: {
    code?: string
    message?: string
    details?: unknown
    retry_after_seconds?: number
  }
}

export type RunResult =
  | { ok: true; data: RunResponseData }
  | {
      ok: false
      error: string
      code?: string
      retryAfterSeconds?: number
      status?: number
    }

export interface ExecuteRunOptions {
  roomId: string
  code: string
  language: string
  stdin?: string
  sessionId?: string
  executionApiUrl?: string
  fetchFn?: typeof fetch
  /** Maximum time in milliseconds to wait for execution to complete before timing out */
  timeoutMs?: number
  /** Optional external abort signal to cancel execution */
  signal?: AbortSignal
}

export interface ExpectedRunContext {
  requestId?: string
  roomId?: string
  language?: string
}

/**
 * Validates the raw JSON object returned by the execution service.
 * Enforces T-17 / T-19 response schema and verifies request context matching:
 * 1. request_id must be present and match requested ID
 * 2. room_id must be present and match requested room
 * 3. language must be present, supported, and match requested language
 * 4. stdout and stderr must be strings (preserved on failure)
 * 5. exit_code must be an integer (valid non-zero exit codes are accepted)
 * 6. execution_time_ms and peak_memory_mb must be non-negative numbers
 */
export function validateRunResponse(
  raw: unknown,
  expected?: ExpectedRunContext,
): { valid: true; data: RunResponseData } | { valid: false; error: string } {
  if (!raw || typeof raw !== 'object') {
    return { valid: false, error: 'Malformed response: server returned a non-object payload.' }
  }

  const obj = raw as Record<string, unknown>

  if (typeof obj.request_id !== 'string' || obj.request_id.trim() === '') {
    return { valid: false, error: 'Malformed response: missing or invalid "request_id" field.' }
  }
  if (expected?.requestId && obj.request_id !== expected.requestId) {
    return {
      valid: false,
      error: `Malformed response: returned request_id "${obj.request_id}" does not match requested "${expected.requestId}".`,
    }
  }

  if (typeof obj.room_id !== 'string' || obj.room_id.trim() === '') {
    return { valid: false, error: 'Malformed response: missing or invalid "room_id" field.' }
  }
  if (expected?.roomId && obj.room_id !== expected.roomId) {
    return {
      valid: false,
      error: `Malformed response: returned room_id "${obj.room_id}" does not match requested "${expected.roomId}".`,
    }
  }

  if (typeof obj.language !== 'string' || !isLanguageSupported(obj.language)) {
    return { valid: false, error: 'Malformed response: missing or unsupported "language" field.' }
  }
  if (expected?.language && obj.language.toLowerCase() !== expected.language.toLowerCase()) {
    return {
      valid: false,
      error: `Malformed response: returned language "${obj.language}" does not match requested "${expected.language}".`,
    }
  }

  if (typeof obj.stdout !== 'string') {
    return { valid: false, error: 'Malformed response: missing or invalid "stdout" field.' }
  }
  if (typeof obj.stderr !== 'string') {
    return { valid: false, error: 'Malformed response: missing or invalid "stderr" field.' }
  }

  if (typeof obj.exit_code !== 'number' || !Number.isInteger(obj.exit_code)) {
    return { valid: false, error: 'Malformed response: missing or invalid "exit_code" field (must be an integer).' }
  }

  if (
    typeof obj.execution_time_ms !== 'number' ||
    Number.isNaN(obj.execution_time_ms) ||
    obj.execution_time_ms < 0
  ) {
    return {
      valid: false,
      error: 'Malformed response: missing or invalid "execution_time_ms" field (must be a non-negative number).',
    }
  }

  if (
    typeof obj.peak_memory_mb !== 'number' ||
    Number.isNaN(obj.peak_memory_mb) ||
    obj.peak_memory_mb < 0
  ) {
    return {
      valid: false,
      error: 'Malformed response: missing or invalid "peak_memory_mb" field (must be a non-negative number).',
    }
  }

  const data: RunResponseData = {
    request_id: obj.request_id,
    room_id: obj.room_id,
    language: obj.language,
    stdout: obj.stdout,
    stderr: obj.stderr,
    exit_code: obj.exit_code,
    execution_time_ms: obj.execution_time_ms,
    peak_memory_mb: obj.peak_memory_mb,
    timed_out: Boolean(obj.timed_out),
    oom_killed: Boolean(obj.oom_killed),
    failure_kind: typeof obj.failure_kind === 'string' ? obj.failure_kind : null,
    output_truncated: Boolean(obj.output_truncated),
    message: typeof obj.message === 'string' ? obj.message : null,
    compile_time_ms: typeof obj.compile_time_ms === 'number' ? obj.compile_time_ms : null,
    runner: (obj.runner && typeof obj.runner === 'object') ? (obj.runner as RunnerMemberInfo) : undefined,
    ran_at: typeof obj.ran_at === 'string' ? obj.ran_at : undefined,
    visibility: typeof obj.visibility === 'string' ? obj.visibility : undefined,
    saved_versions: Array.isArray(obj.saved_versions) ? obj.saved_versions : undefined,
    failure: obj.failure,
  }

  return { valid: true, data }
}

/**
 * Validates a `run-result` text frame received on the control socket (T-17 section 3.6).
 * The frame is the run result of section 4.2 plus `type: "run-result"`. A frame for another
 * room, or one that fails the same checks as the HTTP response, is rejected.
 */
export function parseRunResultFrame(
  raw: unknown,
  expectedRoomId?: string,
): { valid: true; data: RunResponseData } | { valid: false; error: string } {
  if (!raw || typeof raw !== 'object' || (raw as Record<string, unknown>).type !== 'run-result') {
    return { valid: false, error: 'Not a run-result frame.' }
  }
  return validateRunResponse(raw, expectedRoomId ? { roomId: expectedRoomId } : undefined)
}

/** Default execution timeout: 15 seconds (accommodates 5s execution limit + container scheduling) */
export const DEFAULT_RUN_TIMEOUT_MS = 15000

/**
 * Executes code via the execution-service POST /v1/runs endpoint.
 * Builds an inline source request object in compliance with T-17 / T-19.
 */
export async function executeRun(options: ExecuteRunOptions): Promise<RunResult> {
  const {
    roomId,
    code,
    language,
    stdin = '',
    sessionId = getSessionId(),
    executionApiUrl = config.executionApiUrl,
    fetchFn = (typeof window !== 'undefined' ? window.fetch.bind(window) : fetch),
    timeoutMs = DEFAULT_RUN_TIMEOUT_MS,
    signal,
  } = options

  if (!isLanguageSupported(language)) {
    return {
      ok: false,
      code: 'UNSUPPORTED_LANGUAGE',
      error: `Language "${language}" is not available yet. Supported languages: Python, JavaScript, C, C++, Java.`,
    }
  }

  let requestId: string
  try {
    requestId = generateSecureUUID()
  } catch {
    return {
      ok: false,
      code: 'RANDOMNESS_UNAVAILABLE',
      error: 'Cannot generate a secure request ID. Cryptographically secure randomness is unavailable.',
    }
  }

  const fileName = getEntryFileName(language)
  const payload: RunRequestPayload = {
    request_id: requestId,
    room_id: roomId,
    session_id: sessionId,
    language: language.toLowerCase(),
    stdin: stdin || '',
    source: {
      kind: 'inline',
      files: [
        {
          name: fileName,
          content: code,
        },
      ],
      entry: fileName,
    },
  }

  const controller = new AbortController()
  let isTimedOut = false

  const timer = setTimeout(() => {
    isTimedOut = true
    controller.abort()
  }, timeoutMs)

  const handleExternalAbort = () => {
    controller.abort()
  }

  if (signal) {
    if (signal.aborted) {
      clearTimeout(timer)
      return {
        ok: false,
        code: 'REQUEST_ABORTED',
        error: 'Execution request was cancelled.',
      }
    }
    signal.addEventListener('abort', handleExternalAbort, { once: true })
  }

  try {
    const url = `${executionApiUrl.replace(/\/+$/, '')}/v1/runs`
    const response = await fetchFn(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    })

    if (!response.ok) {
      let errorData: RunErrorEnvelope | null = null
      try {
        errorData = (await response.json()) as RunErrorEnvelope
      } catch {
        // Response body was not JSON
      }

      const code = errorData?.error?.code
      const msg = errorData?.error?.message
      const retryAfter = errorData?.error?.retry_after_seconds

      let friendlyMessage = msg || `Execution failed with HTTP status ${response.status}.`
      if (code === 'QUEUE_FULL') {
        friendlyMessage = `System is busy. Please try again${retryAfter ? ` in ${retryAfter} seconds` : ' shortly'}.`
      } else if (code === 'UNSUPPORTED_LANGUAGE') {
        friendlyMessage = `Language "${language}" is not supported by the runner.`
      } else if (code === 'RUN_NOT_ALLOWED') {
        friendlyMessage = msg || 'You do not have permission to run code in this room.'
      }

      return {
        ok: false,
        error: friendlyMessage,
        code,
        retryAfterSeconds: retryAfter,
        status: response.status,
      }
    }

    let rawJson: unknown
    try {
      rawJson = await response.json()
    } catch {
      return {
        ok: false,
        code: 'MALFORMED_RESPONSE',
        error: 'Malformed server response: received invalid JSON from execution service.',
        status: response.status,
      }
    }

    const validation = validateRunResponse(rawJson, {
      requestId,
      roomId,
      language,
    })
    if (!validation.valid) {
      return {
        ok: false,
        code: 'MALFORMED_RESPONSE',
        error: validation.error,
        status: response.status,
      }
    }

    return {
      ok: true,
      data: validation.data,
    }
  } catch (err) {
    if (isTimedOut) {
      const timeoutSec = Math.round(timeoutMs / 1000)
      return {
        ok: false,
        code: 'REQUEST_TIMEOUT',
        error: `Execution request timed out after ${timeoutSec} seconds. Please try again.`,
      }
    }

    if (err instanceof DOMException && err.name === 'AbortError') {
      return {
        ok: false,
        code: 'REQUEST_ABORTED',
        error: 'Execution request was cancelled.',
      }
    }

    return {
      ok: false,
      code: 'SERVICE_UNAVAILABLE',
      error: 'Execution service is unavailable. Please check that the execution service is running.',
    }
  } finally {
    clearTimeout(timer)
    if (signal) {
      signal.removeEventListener('abort', handleExternalAbort)
    }
  }
}
