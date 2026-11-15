import { describe, expect, it, vi } from 'vitest'
import {
  DR1_LANGUAGES,
  executeRun,
  generateSecureUUID,
  getEntryFileName,
  getLanguageExtension,
  getLanguageOption,
  isLanguageSupported,
  validateRunResponse,
} from './runCode'

const UUID_V4_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

describe('runCode helper functions', () => {
  it('lists all five DR1 languages as supported', () => {
    const ids = DR1_LANGUAGES.map((l) => l.id)
    expect(ids).toEqual(['python', 'javascript', 'c', 'cpp', 'java'])
    for (const lang of ids) {
      expect(isLanguageSupported(lang)).toBe(true)
    }
    expect(isLanguageSupported('ruby')).toBe(false)
    expect(isLanguageSupported('rust')).toBe(false)
  })

  it('maps languages to correct file extensions and entry file names', () => {
    expect(getLanguageExtension('python')).toBe('py')
    expect(getLanguageExtension('javascript')).toBe('js')
    expect(getLanguageExtension('c')).toBe('c')
    expect(getLanguageExtension('cpp')).toBe('cpp')
    expect(getLanguageExtension('java')).toBe('java')

    expect(getEntryFileName('python')).toBe('main.py')
    expect(getEntryFileName('javascript')).toBe('main.js')
    expect(getEntryFileName('c')).toBe('main.c')
    expect(getEntryFileName('cpp')).toBe('main.cpp')
    expect(getEntryFileName('java')).toBe('main.java')
  })

  it('returns language options for supported and future languages', () => {
    expect(getLanguageOption('python')?.available).toBe(true)
    expect(getLanguageOption('rust')?.available).toBe(false)
    expect(getLanguageOption('rust')?.fileExtension).toBe('rs')
  })
})

describe('generateSecureUUID (Fix 1)', () => {
  it('generates a valid, cryptographically secure UUID v4', () => {
    const uuid = generateSecureUUID()
    expect(uuid).toMatch(UUID_V4_REGEX)
  })

  it('generates unique UUIDs on every call with zero collisions', () => {
    const set = new Set<string>()
    for (let i = 0; i < 50; i++) {
      const id = generateSecureUUID()
      expect(id).toMatch(UUID_V4_REGEX)
      expect(set.has(id)).toBe(false)
      set.add(id)
    }
    expect(set.size).toBe(50)
  })

  it('falls back to crypto.getRandomValues when crypto.randomUUID is not available', () => {
    const originalRandomUUID = crypto.randomUUID
    try {
      // Temporarily remove randomUUID
      ;(crypto as { randomUUID?: () => string }).randomUUID = undefined
      const fallbackId = generateSecureUUID()
      expect(fallbackId).toMatch(UUID_V4_REGEX)
    } finally {
      ;(crypto as { randomUUID?: () => string }).randomUUID = originalRandomUUID
    }
  })

  it('fails gracefully when cryptographically secure randomness is completely unavailable', () => {
    const originalCrypto = globalThis.crypto
    try {
      // Temporarily mock crypto as lacking random methods
      Object.defineProperty(globalThis, 'crypto', {
        value: {},
        configurable: true,
        writable: true,
      })

      expect(() => generateSecureUUID()).toThrow(
        'Cryptographically secure randomness is not available in this environment.',
      )
    } finally {
      Object.defineProperty(globalThis, 'crypto', {
        value: originalCrypto,
        configurable: true,
        writable: true,
      })
    }
  })
})

describe('validateRunResponse (Fix 2)', () => {
  it('validates a complete, successful response payload', () => {
    const raw = {
      request_id: 'req-1',
      room_id: 'room-1',
      language: 'python',
      stdout: 'hello\n',
      stderr: '',
      exit_code: 0,
      execution_time_ms: 12.5,
      peak_memory_mb: 4.2,
      timed_out: false,
      oom_killed: false,
    }
    const result = validateRunResponse(raw)
    expect(result.valid).toBe(true)
    if (result.valid) {
      expect(result.data.stdout).toBe('hello\n')
      expect(result.data.exit_code).toBe(0)
      expect(result.data.execution_time_ms).toBe(12.5)
    }
  })

  it('preserves stdout and stderr for legitimate execution failures with nonzero exit codes', () => {
    const raw = {
      request_id: 'req-2',
      room_id: 'room-1',
      language: 'python',
      stdout: 'partial stdout\n',
      stderr: 'Traceback (most recent call last):\nNameError: name "x" is not defined\n',
      exit_code: 1,
      execution_time_ms: 8.0,
      peak_memory_mb: 3.1,
    }
    const result = validateRunResponse(raw)
    expect(result.valid).toBe(true)
    if (result.valid) {
      expect(result.data.exit_code).toBe(1)
      expect(result.data.stdout).toBe('partial stdout\n')
      expect(result.data.stderr).toContain('NameError')
    }
  })

  it('safely defaults missing or null execution_time_ms without throwing', () => {
    const raw = {
      stdout: 'output\n',
      stderr: '',
      exit_code: 0,
      execution_time_ms: null,
    }
    const result = validateRunResponse(raw)
    expect(result.valid).toBe(true)
    if (result.valid) {
      expect(result.data.execution_time_ms).toBe(0)
    }
  })

  it('rejects malformed responses missing required string fields', () => {
    expect(validateRunResponse(null).valid).toBe(false)
    expect(validateRunResponse({ stdout: 123, stderr: '', exit_code: 0 }).valid).toBe(false)
    expect(validateRunResponse({ stdout: 'ok', stderr: null, exit_code: 0 }).valid).toBe(false)
    expect(validateRunResponse({ stdout: 'ok', stderr: '', exit_code: '0' }).valid).toBe(false)
  })
})

describe('executeRun API client (Fix 1, 2, 3)', () => {
  const defaultOptions = {
    roomId: 'test-room-123',
    code: 'print("hello world")',
    language: 'python',
    stdin: 'test-input',
    sessionId: '7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90',
    executionApiUrl: 'http://localhost:8082',
    timeoutMs: 1000,
  }

  it('rejects unsupported languages before making an HTTP request', async () => {
    const fetchMock = vi.fn()
    const result = await executeRun({
      ...defaultOptions,
      language: 'ruby',
      fetchFn: fetchMock as unknown as typeof fetch,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('UNSUPPORTED_LANGUAGE')
      expect(result.error).toContain('not available yet')
    }
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('builds a valid T-19 inline source request payload with a fresh secure UUID v4', async () => {
    const mockResponseData = {
      request_id: 'dummy-id',
      room_id: 'test-room-123',
      language: 'python',
      stdout: 'hello world\n',
      stderr: '',
      exit_code: 0,
      execution_time_ms: 12.5,
      peak_memory_mb: 4.0,
      timed_out: false,
      oom_killed: false,
      failure_kind: null,
      output_truncated: false,
      message: null,
      compile_time_ms: null,
    }

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => mockResponseData,
    })

    const result = await executeRun({
      ...defaultOptions,
      fetchFn: fetchMock as unknown as typeof fetch,
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('http://localhost:8082/v1/runs')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' })

    const parsedBody = JSON.parse(init.body as string)
    expect(parsedBody.room_id).toBe('test-room-123')
    expect(parsedBody.session_id).toBe('7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90')
    expect(parsedBody.language).toBe('python')
    expect(parsedBody.stdin).toBe('test-input')
    expect(parsedBody.source).toEqual({
      kind: 'inline',
      files: [{ name: 'main.py', content: 'print("hello world")' }],
      entry: 'main.py',
    })
    expect(parsedBody.request_id).toMatch(UUID_V4_REGEX)

    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.data.stdout).toBe('hello world\n')
      expect(result.data.exit_code).toBe(0)
    }
  })

  it('detects malformed server response and reports MALFORMED_RESPONSE without throwing', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ invalid_payload: true }),
    })

    const result = await executeRun({
      ...defaultOptions,
      fetchFn: fetchMock as unknown as typeof fetch,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('MALFORMED_RESPONSE')
      expect(result.error).toContain('Malformed response')
    }
  })

  it('handles request timeout and returns REQUEST_TIMEOUT (Fix 3)', async () => {
    // Mock fetch that hangs past the timeout
    const fetchMock = vi.fn().mockImplementation((_url, init: { signal?: AbortSignal }) => {
      return new Promise((_resolve, reject) => {
        if (init?.signal) {
          init.signal.addEventListener('abort', () => {
            const err = new DOMException('The operation was aborted', 'AbortError')
            reject(err)
          })
        }
      })
    })

    const result = await executeRun({
      ...defaultOptions,
      timeoutMs: 50, // Short timeout for test
      fetchFn: fetchMock as unknown as typeof fetch,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('REQUEST_TIMEOUT')
      expect(result.error).toContain('timed out')
    }
  })

  it('handles external cancellation signal and returns REQUEST_ABORTED', async () => {
    const controller = new AbortController()
    const fetchMock = vi.fn().mockImplementation((_url, init: { signal?: AbortSignal }) => {
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('Aborted', 'AbortError'))
        })
      })
    })

    const runPromise = executeRun({
      ...defaultOptions,
      timeoutMs: 5000,
      signal: controller.signal,
      fetchFn: fetchMock as unknown as typeof fetch,
    })

    // Abort externally
    controller.abort()
    const result = await runPromise

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('REQUEST_ABORTED')
    }
  })

  it('handles 503 QUEUE_FULL error response with friendly retry message', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      json: async () => ({
        error: {
          code: 'QUEUE_FULL',
          message: 'The execution queue is currently full.',
          retry_after_seconds: 5,
        },
      }),
    })

    const result = await executeRun({
      ...defaultOptions,
      fetchFn: fetchMock as unknown as typeof fetch,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('QUEUE_FULL')
      expect(result.status).toBe(503)
      expect(result.retryAfterSeconds).toBe(5)
      expect(result.error).toContain('System is busy')
      expect(result.error).toContain('5 seconds')
    }
  })

  it('handles service unavailable / network connection failure gracefully', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))

    const result = await executeRun({
      ...defaultOptions,
      fetchFn: fetchMock as unknown as typeof fetch,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('SERVICE_UNAVAILABLE')
      expect(result.error).toContain('Execution service is unavailable')
    }
  })
})
