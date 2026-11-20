import { describe, expect, it, vi } from 'vitest'
import {
  listBlockedMembers,
  makeHost,
  readmitMember,
  regenerateLink,
  removeMember,
  setEditAccess,
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
