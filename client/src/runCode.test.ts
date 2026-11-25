import { describe, expect, it, vi } from 'vitest'
import {
  DR1_LANGUAGES,
  executeRun,
  generateSecureUUID,
  getEntryFileName,
  getLanguageExtension,
  getLanguageOption,
  isLanguageSupported,
  parseRunResultFrame,
  shareRun,
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

describe('validateRunResponse (CHECK 1 — Strict API Response Validation)', () => {
  const validPayload = {
    request_id: '0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10',
    room_id: 'test-room-1',
    language: 'python',
    stdout: 'hello\n',
    stderr: '',
    exit_code: 0,
    execution_time_ms: 12.5,
    peak_memory_mb: 4.2,
    timed_out: false,
    oom_killed: false,
  }

  const validContext = {
    requestId: '0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10',
    roomId: 'test-room-1',
    language: 'python',
  }

  it('validates a complete, successful response payload matching request context', () => {
    const result = validateRunResponse(validPayload, validContext)
    expect(result.valid).toBe(true)
    if (result.valid) {
      expect(result.data.request_id).toBe(validContext.requestId)
      expect(result.data.room_id).toBe(validContext.roomId)
      expect(result.data.language).toBe('python')
      expect(result.data.stdout).toBe('hello\n')
      expect(result.data.exit_code).toBe(0)
      expect(result.data.execution_time_ms).toBe(12.5)
      expect(result.data.peak_memory_mb).toBe(4.2)
    }
  })

  it('preserves stdout and stderr for legitimate execution failures with nonzero exit codes', () => {
    const failPayload = {
      ...validPayload,
      stdout: 'partial stdout\n',
      stderr: 'Traceback (most recent call last):\nNameError: name "x" is not defined\n',
      exit_code: 1,
      execution_time_ms: 8.0,
      peak_memory_mb: 3.1,
    }
    const result = validateRunResponse(failPayload, validContext)
    expect(result.valid).toBe(true)
    if (result.valid) {
      expect(result.data.exit_code).toBe(1)
      expect(result.data.stdout).toBe('partial stdout\n')
      expect(result.data.stderr).toContain('NameError')
    }
  })

  it('rejects responses missing request_id or with empty request_id', () => {
    expect(validateRunResponse({ ...validPayload, request_id: undefined }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, request_id: '' }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, request_id: '   ' }).valid).toBe(false)
  })

  it('rejects responses missing room_id or with empty room_id', () => {
    expect(validateRunResponse({ ...validPayload, room_id: undefined }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, room_id: '' }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, room_id: '   ' }).valid).toBe(false)
  })

  it('rejects responses missing language or with unsupported language', () => {
    expect(validateRunResponse({ ...validPayload, language: undefined }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, language: 'unsupported-lang' }).valid).toBe(false)
  })

  it('rejects responses when returned request_id does not match submitted request ID', () => {
    const result = validateRunResponse(validPayload, {
      ...validContext,
      requestId: 'different-request-id',
    })
    expect(result.valid).toBe(false)
    if (!result.valid) {
      expect(result.error).toContain('does not match requested')
    }
  })

  it('rejects responses when returned room_id does not match requested room', () => {
    const result = validateRunResponse(validPayload, {
      ...validContext,
      roomId: 'different-room',
    })
    expect(result.valid).toBe(false)
    if (!result.valid) {
      expect(result.error).toContain('does not match requested')
    }
  })

  it('rejects responses when returned language does not match requested language', () => {
    const result = validateRunResponse(validPayload, {
      ...validContext,
      language: 'javascript',
    })
    expect(result.valid).toBe(false)
    if (!result.valid) {
      expect(result.error).toContain('does not match requested')
    }
  })

  it('rejects responses with invalid numeric fields (exit_code, execution_time_ms, peak_memory_mb)', () => {
    // exit_code must be an integer
    expect(validateRunResponse({ ...validPayload, exit_code: 1.5 }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, exit_code: '0' }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, exit_code: NaN }).valid).toBe(false)

    // execution_time_ms must be a non-negative number
    expect(validateRunResponse({ ...validPayload, execution_time_ms: -1 }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, execution_time_ms: NaN }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, execution_time_ms: '10' }).valid).toBe(false)

    // peak_memory_mb must be a non-negative number
    expect(validateRunResponse({ ...validPayload, peak_memory_mb: -0.1 }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, peak_memory_mb: NaN }).valid).toBe(false)
  })

  it('rejects malformed responses missing required string fields', () => {
    expect(validateRunResponse(null).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, stdout: 123 }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, stderr: null }).valid).toBe(false)
  })

  it('rejects malformed runner attribution objects (US-50 / FR26)', () => {
    // Non-object runner
    expect(validateRunResponse({ ...validPayload, runner: null }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, runner: 'Ada' }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, runner: 123 }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, runner: [] }).valid).toBe(false)

    // Missing or invalid display_name
    expect(validateRunResponse({ ...validPayload, runner: {} }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, runner: { display_name: '' } }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, runner: { display_name: '   ' } }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, runner: { display_name: 123 } }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, runner: { member_id: 'm1' } }).valid).toBe(false)

    // Invalid member_id if present
    expect(validateRunResponse({ ...validPayload, runner: { display_name: 'Ada', member_id: '' } }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, runner: { display_name: 'Ada', member_id: 123 } }).valid).toBe(false)

    // Valid runner succeeds
    expect(validateRunResponse({ ...validPayload, runner: { display_name: 'Ada' } }).valid).toBe(true)
    expect(validateRunResponse({ ...validPayload, runner: { display_name: 'Ada', member_id: 'm1' } }).valid).toBe(true)
  })

  it('rejects malformed or missing timestamps when attribution is expected (US-50 / FR26)', () => {
    // Invalid ran_at formats
    expect(validateRunResponse({ ...validPayload, ran_at: '' }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, ran_at: 'not-a-timestamp' }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, ran_at: '2026-11-01 10:20:05' }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, ran_at: 123456789 }).valid).toBe(false)

    // requireAttribution option enforces runner and ran_at
    expect(validateRunResponse(validPayload, { requireAttribution: true }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, runner: { display_name: 'Ada' } }, { requireAttribution: true }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, ran_at: '2026-11-01T10:20:05.087Z' }, { requireAttribution: true }).valid).toBe(false)
    expect(
      validateRunResponse(
        {
          ...validPayload,
          runner: { display_name: 'Ada' },
          ran_at: '2026-11-01T10:20:05.087Z',
        },
        { requireAttribution: true }
      ).valid
    ).toBe(true)
  })

  it('rejects malformed saved_versions entries in Open mode (US-50 / FR26)', () => {
    // Non-array
    expect(validateRunResponse({ ...validPayload, saved_versions: 'not-array' }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, saved_versions: {} }).valid).toBe(false)

    // Non-object entries
    expect(validateRunResponse({ ...validPayload, saved_versions: [null] }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, saved_versions: ['module1'] }).valid).toBe(false)

    // Missing required fields (name, version, module_id)
    expect(validateRunResponse({ ...validPayload, saved_versions: [{ name: 'main.py' }] }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, saved_versions: [{ name: 'main.py', version: 1 }] }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, saved_versions: [{ module_id: 'm1', version: 1 }] }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, saved_versions: [{ module_id: 'm1', name: '' }] }).valid).toBe(false)

    // Invalid version (must be non-negative integer)
    expect(validateRunResponse({ ...validPayload, saved_versions: [{ module_id: 'm1', name: 'main.py', version: '1' }] }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, saved_versions: [{ module_id: 'm1', name: 'main.py', version: 1.5 }] }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, saved_versions: [{ module_id: 'm1', name: 'main.py', version: -1 }] }).valid).toBe(false)

    // Invalid saved_by or saved_at if provided
    expect(validateRunResponse({ ...validPayload, saved_versions: [{ module_id: 'm1', name: 'main.py', version: 1, saved_by: 'Ada' }] }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, saved_versions: [{ module_id: 'm1', name: 'main.py', version: 1, saved_by: { display_name: '' } }] }).valid).toBe(false)
    expect(validateRunResponse({ ...validPayload, saved_versions: [{ module_id: 'm1', name: 'main.py', version: 1, saved_at: 'invalid-time' }] }).valid).toBe(false)

    // Valid saved_versions array
    expect(
      validateRunResponse({
        ...validPayload,
        saved_versions: [
          {
            module_id: 'm1',
            name: 'main.py',
            version: 1,
            saved_by: { display_name: 'Ada' },
            saved_at: '2026-11-01T10:20:05.087Z',
          },
        ],
      }).valid
    ).toBe(true)
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
    const fetchMock = vi.fn().mockImplementation(async (_url, init) => {
      const parsedBody = JSON.parse(init.body as string)
      return {
        ok: true,
        status: 200,
        json: async () => ({
          request_id: parsedBody.request_id,
          room_id: parsedBody.room_id,
          language: parsedBody.language,
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
        }),
      }
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
      json: async () => ({ unexpected_structure: true }),
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

  it('handles invalid JSON returned from server without throwing', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => {
        throw new SyntaxError('Unexpected token < in JSON at position 0')
      },
    })

    const result = await executeRun({
      ...defaultOptions,
      fetchFn: fetchMock as unknown as typeof fetch,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('MALFORMED_RESPONSE')
      expect(result.error).toContain('invalid JSON')
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

  it('shows the plain-words reason for each RUN_NOT_ALLOWED refusal (US-60, FR41.4)', async () => {
    const cases: Array<[string, string]> = [
      ['observer', 'Observers cannot run code.'],
      ['run_revoked', 'The Host has turned off running for you.'],
      ['host_only', 'Only the Host can run code in this room right now.'],
      ['not_in_room', 'You are not in this room.'],
    ]
    for (const [reason, text] of cases) {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 403,
        json: async () => ({
          request_id: 'r',
          error: { code: 'RUN_NOT_ALLOWED', message: 'server text', reason, retry_after_seconds: null },
        }),
      })

      const result = await executeRun({ ...defaultOptions, fetchFn: fetchMock as unknown as typeof fetch })

      expect(result.ok).toBe(false)
      if (!result.ok) {
        expect(result.code).toBe('RUN_NOT_ALLOWED')
        expect(result.reason).toBe(reason)
        expect(result.status).toBe(403)
        expect(result.error).toBe(text)
      }
    }
  })

  it('falls back to the server message for an unknown RUN_NOT_ALLOWED reason', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ error: { code: 'RUN_NOT_ALLOWED', message: 'Not today.', reason: 'something_new' } }),
    })

    const result = await executeRun({ ...defaultOptions, fetchFn: fetchMock as unknown as typeof fetch })

    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error).toBe('Not today.')
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

describe('parseRunResultFrame (US-08, T-17 section 3.6)', () => {
  const frame = {
    type: 'run-result',
    request_id: '0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10',
    room_id: 'room-demo-01',
    language: 'python',
    stdout: 'hello\n',
    stderr: '',
    exit_code: 0,
    execution_time_ms: 38,
    peak_memory_mb: 7.9,
    timed_out: false,
    oom_killed: false,
    failure_kind: null,
    output_truncated: false,
    message: null,
    runner: { member_id: 'm_7a3c9f', display_name: 'Ada' },
    ran_at: '2026-11-01T10:20:05.087Z',
    visibility: 'room',
  }

  it('accepts a valid run-result frame for the current room', () => {
    const parsed = parseRunResultFrame(frame, 'room-demo-01')
    expect(parsed.valid).toBe(true)
    if (parsed.valid) {
      expect(parsed.data.stdout).toBe('hello\n')
      expect(parsed.data.runner?.display_name).toBe('Ada')
      expect(parsed.data.runner?.member_id).toBe('m_7a3c9f')
      expect(parsed.data.ran_at).toBe('2026-11-01T10:20:05.087Z')
    }
  })

  it('preserves saved_versions in V2.9 run-result frames (US-50 / FR26)', () => {
    const openFrame = {
      ...frame,
      saved_versions: [
        {
          module_id: 'mod_1',
          name: 'main.py',
          version: 4,
          saved_by: { member_id: 'm_ada', display_name: 'Ada' },
          saved_at: '2026-11-01T10:12:04.118Z',
        },
        {
          module_id: 'mod_2',
          name: 'helper.py',
          version: 2,
          saved_by: { member_id: 'm_lin', display_name: 'Lin' },
          saved_at: '2026-11-01T10:14:51.733Z',
        },
      ],
    }

    const parsed = parseRunResultFrame(openFrame, 'room-demo-01')
    expect(parsed.valid).toBe(true)
    if (parsed.valid) {
      expect(parsed.data.saved_versions).toHaveLength(2)
      expect(parsed.data.saved_versions?.[0].name).toBe('main.py')
      expect(parsed.data.saved_versions?.[0].version).toBe(4)
      expect(parsed.data.saved_versions?.[1].name).toBe('helper.py')
      expect(parsed.data.saved_versions?.[1].version).toBe(2)
    }
  })

  it('rejects a frame of another type, for another room, or with a missing field', () => {
    expect(parseRunResultFrame({ ...frame, type: 'roster' }, 'room-demo-01').valid).toBe(false)
    expect(parseRunResultFrame(frame, 'some-other-room').valid).toBe(false)
    expect(parseRunResultFrame({ ...frame, stdout: undefined }, 'room-demo-01').valid).toBe(false)
    expect(parseRunResultFrame(null, 'room-demo-01').valid).toBe(false)
  })
})

describe('shareRun (US-49 / T-17 V2.10)', () => {
  const requestId = 'd7a2f5c8-1e39-4b60-a4c2-9f8e1d7b3a06'
  const sessionId = '2f8d6b14-9c03-4e7a-b5d1-6a0e3c9f7d28'

  it('successfully shares a private run result (200 OK)', async () => {
    const fetchFn = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ request_id: requestId, visibility: 'room' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const result = await shareRun({
      executionApiUrl: 'http://localhost:8082',
      requestId,
      sessionId,
      fetchFn,
    })

    expect(result.ok).toBe(true)
    expect(result.status).toBe(200)
    expect(result.data?.visibility).toBe('room')
    expect(fetchFn).toHaveBeenCalledWith(
      'http://localhost:8082/v1/runs/d7a2f5c8-1e39-4b60-a4c2-9f8e1d7b3a06/share',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ session_id: sessionId }),
      }),
    )
  })

  it('handles NOT_RUNNER (403) from execution-service', async () => {
    const fetchFn = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          request_id: requestId,
          error: { code: 'NOT_RUNNER', message: 'Only the runner may share this run result.' },
        }),
        { status: 403, headers: { 'Content-Type': 'application/json' } },
      ),
    )

    const result = await shareRun({
      executionApiUrl: 'http://localhost:8082',
      requestId,
      sessionId,
      fetchFn,
    })

    expect(result.ok).toBe(false)
    expect(result.status).toBe(403)
    expect(result.code).toBe('NOT_RUNNER')
    expect(result.error).toBe('Only the runner may share this run result.')
  })

  it('handles RUN_NOT_FOUND (404) from execution-service', async () => {
    const fetchFn = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          request_id: requestId,
          error: { code: 'RUN_NOT_FOUND', message: 'Run was not found or has expired.' },
        }),
        { status: 404, headers: { 'Content-Type': 'application/json' } },
      ),
    )

    const result = await shareRun({
      executionApiUrl: 'http://localhost:8082',
      requestId,
      sessionId,
      fetchFn,
    })

    expect(result.ok).toBe(false)
    expect(result.status).toBe(404)
    expect(result.code).toBe('RUN_NOT_FOUND')
  })

  it('handles network failure gracefully', async () => {
    const fetchFn = vi.fn().mockRejectedValue(new Error('Network error'))

    const result = await shareRun({
      executionApiUrl: 'http://localhost:8082',
      requestId,
      sessionId,
      fetchFn,
    })

    expect(result.ok).toBe(false)
    expect(result.code).toBe('NETWORK_ERROR')
  })
})
