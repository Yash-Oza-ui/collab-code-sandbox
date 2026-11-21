import { describe, expect, it, vi } from 'vitest'
import {
  approveModule,
  createModule,
  declineModule,
  friendlyWorkspaceErrorMessage,
  grantWriteAccess,
  listModules,
  listPendingModules,
  requestModule,
  revokeWriteAccess,
  setModuleEntry,
  setModuleRunnable,
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

describe('requestModule', () => {
  it('requests module without X-Owner-Token (US-40, FR21.1)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 202,
      json: async () => ({
        module_id: 'mod_part',
        name: 'task.py',
        owner_member_id: 'm_part',
        pending: true,
      }),
    })

    const res = await requestModule(
      {
        roomId: 'room-123',
        sessionId: 'sess-part',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'task.py',
    )

    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data.pending).toBe(true)
      expect(res.data.owner_member_id).toBe('m_part')
    }
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8081/v1/rooms/room-123/modules',
      expect.objectContaining({
        method: 'POST',
        headers: expect.not.objectContaining({ 'X-Owner-Token': expect.anything() }),
      }),
    )
  })
})

describe('approveModule', () => {
  it('approves module with optional new owner (US-40, FR21.2)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        module_id: 'mod_1',
        name: 'task.py',
        owner_member_id: 'm_new_owner',
        pending: false,
      }),
    })

    const res = await approveModule(
      {
        roomId: 'room-123',
        sessionId: 'sess-host',
        ownerToken: 'token-secret',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_1',
      'm_new_owner',
    )

    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data.owner_member_id).toBe('m_new_owner')
      expect(res.data.pending).toBe(false)
    }
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8081/v1/rooms/room-123/modules/mod_1/approve',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Session-Id': 'sess-host',
          'X-Owner-Token': 'token-secret',
        }),
        body: JSON.stringify({ owner_member_id: 'm_new_owner' }),
      }),
    )
  })
})

describe('declineModule', () => {
  it('declines module sending POST to decline endpoint (US-40, FR21.2)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        module_id: 'mod_1',
        status: 'declined',
      }),
    })

    const res = await declineModule(
      {
        roomId: 'room-123',
        sessionId: 'sess-host',
        ownerToken: 'token-secret',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_1',
    )

    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data.status).toBe('declined')
    }
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8081/v1/rooms/room-123/modules/mod_1/decline',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Session-Id': 'sess-host',
          'X-Owner-Token': 'token-secret',
        }),
      }),
    )
  })
})

describe('listPendingModules', () => {
  it('fetches pending modules with ?pending=true (US-40)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => [
        { module_id: 'mod_p', name: 'draft.py', owner_member_id: 'm_part', pending: true },
      ],
    })

    const res = await listPendingModules({
      roomId: 'room-123',
      sessionId: 'sess-host',
      ownerToken: 'token-secret',
      fetchFn: fetchMock as unknown as typeof fetch,
      baseUrl: 'http://localhost:8081',
    })

    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data).toHaveLength(1)
      expect(res.data[0].pending).toBe(true)
    }
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8081/v1/rooms/room-123/modules?pending=true',
      expect.objectContaining({
        method: 'GET',
        headers: expect.objectContaining({
          'X-Session-Id': 'sess-host',
          'X-Owner-Token': 'token-secret',
        }),
      }),
    )
  })
})

describe('friendlyWorkspaceErrorMessage', () => {
  it('translates V2.12 error codes to plain words', () => {
    expect(friendlyWorkspaceErrorMessage('NOT_MODULE_OWNER')).toBe(
      'Only the module owner can change its runnable status.',
    )
    expect(friendlyWorkspaceErrorMessage('NOT_OWNER')).toBe(
      'Only the room Host can designate the workspace entry file.',
    )
    expect(friendlyWorkspaceErrorMessage('MODULE_NOT_FOUND')).toBe(
      'Module not found in this workspace.',
    )
    expect(friendlyWorkspaceErrorMessage('NAME_TAKEN')).toBe(
      'A module with this name already exists in this workspace.',
    )
    expect(friendlyWorkspaceErrorMessage('UNKNOWN_CODE', 'Custom message')).toBe('Custom message')
  })
})

describe('grantWriteAccess', () => {
  it('sends POST /v1/rooms/{roomId}/modules/{moduleId}/writers with member_id (US-43)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        module_id: 'mod_1',
        name: 'main.py',
        owner_member_id: 'm_owner',
        writers: ['m_part'],
        pending: false,
      }),
    })

    const res = await grantWriteAccess(
      {
        roomId: 'room-123',
        sessionId: 'sess-owner',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_1',
      'm_part',
    )

    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data.writers).toContain('m_part')
    }
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8081/v1/rooms/room-123/modules/mod_1/writers',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          'X-Session-Id': 'sess-owner',
        }),
        body: JSON.stringify({ member_id: 'm_part' }),
      }),
    )
  })

  it('handles error when not owner (US-43)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({
        error: { code: 'NOT_MODULE_OWNER', message: 'Only module owner or Host can grant write access.' },
      }),
    })

    const res = await grantWriteAccess(
      {
        roomId: 'room-123',
        sessionId: 'sess-rando',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_1',
      'm_part',
    )

    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.code).toBe('NOT_MODULE_OWNER')
    }
  })
})

describe('revokeWriteAccess', () => {
  it('sends DELETE /v1/rooms/{roomId}/modules/{moduleId}/writers/{memberId} (US-43)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        module_id: 'mod_1',
        name: 'main.py',
        owner_member_id: 'm_owner',
        writers: [],
        pending: false,
      }),
    })

    const res = await revokeWriteAccess(
      {
        roomId: 'room-123',
        sessionId: 'sess-owner',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_1',
      'm_part',
    )

    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data.writers).toEqual([])
    }
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8081/v1/rooms/room-123/modules/mod_1/writers/m_part',
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({
          'X-Session-Id': 'sess-owner',
        }),
      }),
    )
  })

  it('handles error when trying to revoke owner (US-43)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 422,
      json: async () => ({
        error: { code: 'INVALID_REQUEST', message: 'Cannot revoke write access from module owner.' },
      }),
    })

    const res = await revokeWriteAccess(
      {
        roomId: 'room-123',
        sessionId: 'sess-owner',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_1',
      'm_owner',
    )

    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.code).toBe('INVALID_REQUEST')
    }
  })
})

describe('setModuleRunnable', () => {
  it('sends PATCH with runnable boolean and X-Session-Id (US-46)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        module_id: 'mod_1',
        name: 'script.py',
        owner_member_id: 'm_owner',
        runnable: false,
      }),
    })

    const res = await setModuleRunnable(
      {
        roomId: 'room-123',
        sessionId: 'sess-owner',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_1',
      false,
    )

    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data.runnable).toBe(false)
    }
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8081/v1/rooms/room-123/modules/mod_1',
      expect.objectContaining({
        method: 'PATCH',
        headers: expect.objectContaining({
          'X-Session-Id': 'sess-owner',
          'Content-Type': 'application/json',
        }),
        body: JSON.stringify({ runnable: false }),
      }),
    )
  })

  it('handles NOT_MODULE_OWNER refusal in plain words (US-46)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({
        error: { code: 'NOT_MODULE_OWNER', message: 'Not module owner' },
      }),
    })

    const res = await setModuleRunnable(
      {
        roomId: 'room-123',
        sessionId: 'sess-nonowner',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_1',
      true,
    )

    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.status).toBe(403)
      expect(res.code).toBe('NOT_MODULE_OWNER')
      expect(res.error).toBe('Only the module owner can change its runnable status.')
    }
  })
})

describe('setModuleEntry', () => {
  it('sends PATCH with is_entry, X-Session-Id, and X-Owner-Token (US-46)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        module_id: 'mod_1',
        name: 'main.py',
        owner_member_id: 'm_host',
        is_entry: true,
      }),
    })

    const res = await setModuleEntry(
      {
        roomId: 'room-123',
        sessionId: 'sess-host',
        ownerToken: 'token-secret',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_1',
      true,
    )

    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data.is_entry).toBe(true)
    }
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8081/v1/rooms/room-123/modules/mod_1',
      expect.objectContaining({
        method: 'PATCH',
        headers: expect.objectContaining({
          'X-Session-Id': 'sess-host',
          'X-Owner-Token': 'token-secret',
          'Content-Type': 'application/json',
        }),
        body: JSON.stringify({ is_entry: true }),
      }),
    )
  })

  it('handles NOT_OWNER refusal in plain words (US-46)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({
        error: { code: 'NOT_OWNER', message: 'Not owner' },
      }),
    })

    const res = await setModuleEntry(
      {
        roomId: 'room-123',
        sessionId: 'sess-nonhost',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_1',
      true,
    )

    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.status).toBe(403)
      expect(res.code).toBe('NOT_OWNER')
      expect(res.error).toBe('Only the room Host can designate the workspace entry file.')
    }
  })

  it('handles MODULE_NOT_FOUND refusal in plain words (US-46)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({
        error: { code: 'MODULE_NOT_FOUND', message: 'Module not found' },
      }),
    })

    const res = await setModuleEntry(
      {
        roomId: 'room-123',
        sessionId: 'sess-host',
        ownerToken: 'token-secret',
        fetchFn: fetchMock as unknown as typeof fetch,
        baseUrl: 'http://localhost:8081',
      },
      'mod_unknown',
      true,
    )

    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.status).toBe(404)
      expect(res.code).toBe('MODULE_NOT_FOUND')
      expect(res.error).toBe('Module not found in this workspace.')
    }
  })
})
