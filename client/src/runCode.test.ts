import { describe, expect, it, vi } from 'vitest'
import {
  DR1_LANGUAGES,
  executeRun,
  getEntryFileName,
  getLanguageExtension,
  getLanguageOption,
  isLanguageSupported,
} from './runCode'

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

describe('executeRun API client', () => {
  const defaultOptions = {
    roomId: 'test-room-123',
    code: 'print("hello world")',
    language: 'python',
    stdin: 'test-input',
    sessionId: '7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90',
    executionApiUrl: 'http://localhost:8082',
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

  it('builds a valid T-19 inline source request payload and parses 200 OK response', async () => {
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
    expect(typeof parsedBody.request_id).toBe('string')

    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.data.stdout).toBe('hello world\n')
      expect(result.data.exit_code).toBe(0)
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

  it('handles 403 RUN_NOT_ALLOWED error response (e.g. for Observers in US-27)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({
        error: {
          code: 'RUN_NOT_ALLOWED',
          message: 'Observers cannot execute code.',
        },
      }),
    })

    const result = await executeRun({
      ...defaultOptions,
      fetchFn: fetchMock as unknown as typeof fetch,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.code).toBe('RUN_NOT_ALLOWED')
      expect(result.status).toBe(403)
      expect(result.error).toBe('Observers cannot execute code.')
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
