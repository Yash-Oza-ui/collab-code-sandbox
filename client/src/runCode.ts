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
}

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
  } = options

  if (!isLanguageSupported(language)) {
    return {
      ok: false,
      code: 'UNSUPPORTED_LANGUAGE',
      error: `Language "${language}" is not available yet. Supported languages: Python, JavaScript, C, C++, Java.`,
    }
  }

  const requestId =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : '00000000-0000-4000-8000-000000000000'

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

  try {
    const url = `${executionApiUrl.replace(/\/+$/, '')}/v1/runs`
    const response = await fetchFn(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
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

    const data = (await response.json()) as RunResponseData
    return {
      ok: true,
      data,
    }
  } catch (err) {
    const message =
      err instanceof Error && err.message
        ? err.message
        : 'Execution service is unavailable. Please check that the execution service is running.'

    return {
      ok: false,
      code: 'SERVICE_UNAVAILABLE',
      error: message.includes('Failed to fetch') || message.includes('NetworkError')
        ? 'Execution service is unavailable. Please check that the execution service is running.'
        : message,
    }
  }
}
