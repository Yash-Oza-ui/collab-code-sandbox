import { describe, expect, it, vi } from 'vitest'
import {
  createModule,
  listModules,
  validateModuleName,
} from './workspaceApi'

describe('validateModuleName', () => {
  it('rejects empty or blank names', () => {
    expect(validateModuleName('').valid).toBe(false)
    expect(validateModuleName('   ').valid).toBe(false)
  })

  it('rejects directory traversal characters', () => {
    expect(validateModuleName('foo/bar.py').valid).toBe(false)
    expect(validateModuleName('foo\\bar.py').valid).toBe(false)
    expect(validateModuleName('../bar.py').valid).toBe(false)
  })

  it('rejects hidden files and bare extensions', () => {
    expect(validateModuleName('.py').valid).toBe(false)
    expect(validateModuleName('.hidden.py').valid).toBe(false)
  })

  it('validates python files', () => {
    expect(validateModuleName('main.py', 'python').valid).toBe(true)
    expect(validateModuleName('utils.py', 'python').valid).toBe(true)
    expect(validateModuleName('main.js', 'python').valid).toBe(false)
    expect(validateModuleName('main', 'python').valid).toBe(false)
  })

  it('validates cpp files', () => {
    expect(validateModuleName('main.cpp', 'cpp').valid).toBe(true)
    expect(validateModuleName('main.cc', 'cpp').valid).toBe(true)
    expect(validateModuleName('main.cxx', 'cpp').valid).toBe(true)
    expect(validateModuleName('main.c', 'cpp').valid).toBe(false)
  })

  it('validates c files', () => {
    expect(validateModuleName('main.c', 'c').valid).toBe(true)
    expect(validateModuleName('main.cpp', 'c').valid).toBe(false)
  })

  it('validates java files', () => {
    expect(validateModuleName('Main.java', 'java').valid).toBe(true)
    expect(validateModuleName('Main.class', 'java').valid).toBe(false)
  })

  it('validates javascript files', () => {
    expect(validateModuleName('index.js', 'javascript').valid).toBe(true)
    expect(validateModuleName('index.ts', 'javascript').valid).toBe(false)
  })
})

describe('listModules', () => {
  it('fetches modules with X-Session-Id header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => [
        { module_id: 'mod_1', name: 'main.py', owner_member_id: 'm_1', is_entry: true },
      ],
    })

    const res = await listModules({
      roomId: 'room-123',
      sessionId: 'sess-abc',
      fetchFn: fetchMock as unknown as typeof fetch,
      baseUrl: 'http://localhost:8081',
    })

    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data).toHaveLength(1)
      expect(res.data[0].module_id).toBe('mod_1')
    }
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8081/v1/rooms/room-123/modules',
      expect.objectContaining({
        method: 'GET',
        headers: expect.objectContaining({ 'X-Session-Id': 'sess-abc' }),
      }),
    )
  })

  it('handles server errors gracefully', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({ error: { code: 'ROOM_NOT_FOUND', message: 'Room not found.' } }),
    })

    const res = await listModules({
      roomId: 'room-unknown',
      sessionId: 'sess-abc',
      fetchFn: fetchMock as unknown as typeof fetch,
      baseUrl: 'http://localhost:8081',
    })

    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.code).toBe('ROOM_NOT_FOUND')
      expect(res.error).toBe('Room not found.')
    }
  })
})

describe('createModule', () => {
  it('sends POST /v1/rooms/{roomId}/modules with X-Owner-Token when host', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({
        module_id: 'mod_new',
        name: 'helper.py',
        owner_member_id: 'm_host',
        pending: false,
      }),
    })

    const res = await createModule(
      {
        roomId: 'room-123',
        sessionId: 'sess-host',
        ownerToken: 'token-secret',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'helper.py',
    )

    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data.module_id).toBe('mod_new')
    }
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8081/v1/rooms/room-123/modules',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          'X-Session-Id': 'sess-host',
          'X-Owner-Token': 'token-secret',
        }),
        body: JSON.stringify({ name: 'helper.py' }),
      }),
    )
  })

  it('surfaces 422 INVALID_REQUEST on error', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 422,
      json: async () => ({
        error: { code: 'INVALID_REQUEST', message: "File name 'foo.txt' does not match language 'python'." },
      }),
    })

    const res = await createModule(
      {
        roomId: 'room-123',
        sessionId: 'sess-host',
        ownerToken: 'token-secret',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'foo.txt',
    )

    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.status).toBe(422)
      expect(res.error).toContain("File name 'foo.txt' does not match language 'python'.")
    }
  })
})
