import { describe, expect, it, vi } from 'vitest'
import {
  listBlockedMembers,
  makeHost,
  readmitMember,
  regenerateLink,
  removeMember,
  setEditAccess,
  setAiSwitch,
  approveEditRequest,
  declineEditRequest,
  listEditRequests,
  type HostCallContext,
} from './hostApi'

// US-28 (V2.18), US-54 (V2.19), US-36 (V2.21): the Host-only REST calls behind the participant list.

const SESSION = '50000000-0000-4000-8000-000000000005'
const TOKEN = 'owner-token-for-test'

function ctx(fetchFn: unknown): HostCallContext {
  return {
    roomId: 'Zq8-Lw2Ef6Th0Yx4Rn7JbA',
    sessionId: SESSION,
    ownerToken: TOKEN,
    fetchFn: fetchFn as typeof fetch,
    baseUrl: 'http://sync.test',
  }
}

const okJson = (body: unknown) => vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body })
const failJson = (status: number, code: string, message = 'server text') =>
  vi.fn().mockResolvedValue({ ok: false, status, json: async () => ({ request_id: null, error: { code, message } }) })

describe('hostApi requests', () => {
  it('remove: POST /members/{id}/remove with the Host headers and no body', async () => {
    const fetchFn = okJson({ member_id: 'm_4b19d2', status: 'removed' })

    const result = await removeMember(ctx(fetchFn), 'm_4b19d2')

    expect(result).toEqual({ ok: true, data: { member_id: 'm_4b19d2' } })
    const [url, init] = fetchFn.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://sync.test/v1/rooms/Zq8-Lw2Ef6Th0Yx4Rn7JbA/members/m_4b19d2/remove')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'X-Session-Id': SESSION, 'X-Owner-Token': TOKEN })
    expect(init.body).toBeUndefined()
  })

  it('readmit: POST /members/{id}/readmit', async () => {
    const fetchFn = okJson({ member_id: 'm_4b19d2', status: 'readmitted' })

    const result = await readmitMember(ctx(fetchFn), 'm_4b19d2')

    expect(result.ok).toBe(true)
    const [url, init] = fetchFn.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://sync.test/v1/rooms/Zq8-Lw2Ef6Th0Yx4Rn7JbA/members/m_4b19d2/readmit')
    expect(init.method).toBe('POST')
  })

  it('blocked list: GET /members/blocked, entries without any session id', async () => {
    const fetchFn = okJson({
      blocked: [{ member_id: 'm_4b19d2', display_name: 'Lin', removed_at: '2026-11-18T06:12:40.512Z' }],
    })

    const result = await listBlockedMembers(ctx(fetchFn))

    expect(result).toEqual({
      ok: true,
      data: [{ member_id: 'm_4b19d2', display_name: 'Lin', removed_at: '2026-11-18T06:12:40.512Z' }],
    })
    const [url, init] = fetchFn.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://sync.test/v1/rooms/Zq8-Lw2Ef6Th0Yx4Rn7JbA/members/blocked')
    expect(init.method).toBe('GET')
  })

  it('blocked list: an empty list is fine', async () => {
    expect(await listBlockedMembers(ctx(okJson({ blocked: [] })))).toEqual({ ok: true, data: [] })
  })

  it('regenerate link: POST /regenerate-link and returns the new room id', async () => {
    const fetchFn = okJson({ room_id: 'T4vAWVm_BI_DgmSxSGmr8w', status: 'regenerated' })

    const result = await regenerateLink(ctx(fetchFn))

    expect(result).toEqual({ ok: true, data: { room_id: 'T4vAWVm_BI_DgmSxSGmr8w' } })
    const [url, init] = fetchFn.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://sync.test/v1/rooms/Zq8-Lw2Ef6Th0Yx4Rn7JbA/regenerate-link')
    expect(init.method).toBe('POST')
  })

  it('make host: POST /members/{id}/make-host, confirmation without any token', async () => {
    const fetchFn = okJson({ host_member_id: 'm_7c22a1', status: 'transferred' })

    const result = await makeHost(ctx(fetchFn), 'm_7c22a1')

    expect(result).toEqual({ ok: true, data: { host_member_id: 'm_7c22a1' } })
    const [url, init] = fetchFn.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://sync.test/v1/rooms/Zq8-Lw2Ef6Th0Yx4Rn7JbA/members/m_7c22a1/make-host')
    expect(init.method).toBe('POST')
  })

  it('edit access: PATCH /members/{id}/edit-access with can_edit in body (V2.21)', async () => {
    const fetchFn = okJson({
      member_id: 'm_4b19d2',
      display_name: 'Lin',
      role: 'participant',
      permissions: { can_edit: true, can_run: true, run_denied_reason: null },
    })

    const result = await setEditAccess(ctx(fetchFn), 'm_4b19d2', true)

    expect(result).toEqual({
      ok: true,
      data: {
        member_id: 'm_4b19d2',
        display_name: 'Lin',
        role: 'participant',
        permissions: { can_edit: true, can_run: true, run_denied_reason: null },
      },
    })
    const [url, init] = fetchFn.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://sync.test/v1/rooms/Zq8-Lw2Ef6Th0Yx4Rn7JbA/members/m_4b19d2/edit-access')
    expect(init.method).toBe('PATCH')
    expect(init.headers).toEqual({
      'X-Session-Id': SESSION,
      'X-Owner-Token': TOKEN,
      'Content-Type': 'application/json',
    })
    expect(init.body).toBe(JSON.stringify({ can_edit: true }))
  })

  it('encodes ids in the path', async () => {
    const fetchFn = okJson({ member_id: 'x', status: 'removed' })

    await removeMember(ctx(fetchFn), 'a/b?c')

    expect((fetchFn.mock.calls[0] as [string])[0]).toContain('/members/a%2Fb%3Fc/remove')
  })
})

describe('hostApi errors', () => {
  it('says why in plain words for the codes of V2.12', async () => {
    const cases: Array<[number, string, string]> = [
      [403, 'NOT_OWNER', 'Only the Host can do this.'],
      [404, 'MEMBER_NOT_FOUND', 'That person is no longer in the room.'],
      [404, 'ROOM_NOT_FOUND', 'This room no longer exists.'],
      [404, 'SESSION_NOT_FOUND', 'You are not in this room. Please rejoin.'],
    ]
    for (const [status, code, text] of cases) {
      const result = await removeMember(ctx(failJson(status, code)), 'm_1')
      expect(result).toEqual({ ok: false, status, code, error: text })
    }
  })

  it('uses the server message for any other code, and the status when there is none', async () => {
    const a = await makeHost(ctx(failJson(422, 'INVALID_REQUEST', 'Cannot transfer to an Observer.')), 'm_1')
    expect(a).toMatchObject({ ok: false, code: 'INVALID_REQUEST', error: 'Cannot transfer to an Observer.' })

    const bare = vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => { throw new Error('no body') } })
    const b = await makeHost(ctx(bare), 'm_1')
    expect(b).toMatchObject({ ok: false, status: 500, error: 'The request failed (HTTP 500).' })
  })

  it('reports a network failure', async () => {
    const result = await removeMember(ctx(vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))), 'm_1')

    expect(result).toMatchObject({ ok: false, status: 0, code: 'NETWORK' })
  })

  it('refuses an answer that is not the documented shape', async () => {
    const bad: Array<() => Promise<unknown>> = [
      () => removeMember(ctx(okJson({ status: 'something' })), 'm_1'),
      () => readmitMember(ctx(okJson({})), 'm_1'),
      () => makeHost(ctx(okJson({ status: 'transferred' })), 'm_1'),
      () => regenerateLink(ctx(okJson({ status: 'regenerated', room_id: '../evil' })), ),
      () => regenerateLink(ctx(okJson({ status: 'regenerated' }))),
      () => listBlockedMembers(ctx(okJson({ blocked: [{ member_id: 'm_1' }] }))),
      () => listBlockedMembers(ctx(okJson({ blocked: 'no' }))),
    ]
    for (const call of bad) {
      expect(await call()).toMatchObject({ ok: false, code: 'BAD_RESPONSE' })
    }
  })
})


describe('US-59 Host AI switch API', () => {
  it('PATCHes the server switch using Host credentials and a boolean', async () => {
    const fn = okJson({ ai_enabled: false })
    expect(await setAiSwitch(ctx(fn), false)).toEqual({ ok: true, data: { ai_enabled: false } })
    const [url, init] = fn.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://sync.test/v1/rooms/Zq8-Lw2Ef6Th0Yx4Rn7JbA/ai-switch')
    expect(init.method).toBe('PATCH')
    expect(init.headers).toMatchObject({ 'X-Session-Id': SESSION, 'X-Owner-Token': TOKEN })
    expect(init.body).toBe(JSON.stringify({ ai_enabled: false }))
  })
  it('rejects a non-boolean server response', async () => {
    expect(await setAiSwitch(ctx(okJson({ ai_enabled: 'false' })), false))
      .toMatchObject({ ok: false, code: 'BAD_RESPONSE' })
  })
  it('surfaces the NOT_OWNER result to a non-Host', async () => {
    expect(await setAiSwitch(ctx(failJson(403, 'NOT_OWNER')), true))
      .toMatchObject({ ok: false, status: 403, code: 'NOT_OWNER' })
  })
  const editReqCtx = { roomId: 'room-1', sessionId: 'sess-1', ownerToken: 'tok-1' }
  it('listEditRequests sends GET with Host headers and parses the array response', async () => {
    const fetchFn = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => [
        { member_id: 'm_bob', display_name: 'Bob', requested_at: '2026-11-22T10:00:00Z' },
      ],
    })
    const result = await listEditRequests({ ...editReqCtx, fetchFn })

    expect(fetchFn).toHaveBeenCalledWith(
      expect.stringMatching(/\/v1\/rooms\/room-1\/edit-requests$/),
      expect.objectContaining({
        method: 'GET',
        headers: { 'X-Session-Id': 'sess-1', 'X-Owner-Token': 'tok-1' },
      }),
    )
    expect(result).toEqual({
      ok: true,
      data: [{ member_id: 'm_bob', display_name: 'Bob', requested_at: '2026-11-22T10:00:00Z' }],
    })
  })

  it('approveEditRequest sends POST /approve with Host headers', async () => {
    const fetchFn = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        member_id: 'm_bob',
        display_name: 'Bob',
        role: 'participant',
        permissions: { can_edit: true, can_run: true },
      }),
    })
    const result = await approveEditRequest({ ...editReqCtx, fetchFn }, 'm_bob')

    expect(fetchFn).toHaveBeenCalledWith(
      expect.stringMatching(/\/v1\/rooms\/room-1\/edit-requests\/m_bob\/approve$/),
      expect.objectContaining({
        method: 'POST',
        headers: { 'X-Session-Id': 'sess-1', 'X-Owner-Token': 'tok-1' },
      }),
    )
    expect(result).toEqual({
      ok: true,
      data: {
        member_id: 'm_bob',
        display_name: 'Bob',
        role: 'participant',
        permissions: { can_edit: true, can_run: true },
      },
    })
  })

  it('declineEditRequest sends POST /decline and maps NOT_OWNER, MEMBER_NOT_FOUND, INVALID_REQUEST', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ member_id: 'm_bob', status: 'declined' }),
      })
      .mockResolvedValueOnce({
        ok: false,
        status: 403,
        json: async () => ({ error: { code: 'NOT_OWNER', message: 'Forbidden' } }),
      })
      .mockResolvedValueOnce({
        ok: false,
        status: 404,
        json: async () => ({ error: { code: 'MEMBER_NOT_FOUND', message: 'Member not found in this room.' } }),
      })
      .mockResolvedValueOnce({
        ok: false,
        status: 422,
        json: async () => ({ error: { code: 'INVALID_REQUEST', message: 'Edit-access requests apply only in Pair mode.' } }),
      })

    const okRes = await declineEditRequest({ ...editReqCtx, fetchFn }, 'm_bob')
    expect(okRes).toEqual({ ok: true, data: { member_id: 'm_bob' } })

    const notOwner = await declineEditRequest({ ...editReqCtx, fetchFn }, 'm_bob')
    expect(notOwner.ok).toBe(false)
    if (!notOwner.ok) expect(notOwner.error).toBe('Only the Host can do this.')

    const notFound = await declineEditRequest({ ...editReqCtx, fetchFn }, 'm_bob')
    expect(notFound.ok).toBe(false)
    if (!notFound.ok) expect(notFound.error).toBe('That person is no longer in the room.')

    const invalid = await declineEditRequest({ ...editReqCtx, fetchFn }, 'm_bob')
    expect(invalid.ok).toBe(false)
    if (!invalid.ok) expect(invalid.error).toBe('Edit-access requests apply only in Pair mode.')
  })
})
