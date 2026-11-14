import { describe, expect, it } from 'vitest'
import { buildRoomLink } from './roomLink'
import { getRoomIdFromPath } from './roomId'

describe('buildRoomLink', () => {
  it('builds /room/{room_id} on the given origin', () => {
    expect(buildRoomLink('http://localhost:3000', 'k3Yc1t9Qx7mBvA2nR0sLpQ')).toBe(
      'http://localhost:3000/room/k3Yc1t9Qx7mBvA2nR0sLpQ',
    )
  })

  it('ignores a trailing slash on the origin', () => {
    expect(buildRoomLink('http://localhost:3000/', 'abc')).toBe(
      'http://localhost:3000/room/abc',
    )
  })

  it('keeps - and _ in the id unchanged', () => {
    expect(buildRoomLink('http://localhost:3000', 'Zq8-Lw2_Ef')).toBe(
      'http://localhost:3000/room/Zq8-Lw2_Ef',
    )
  })

  it('produces a link whose path getRoomIdFromPath reads back', () => {
    const link = buildRoomLink('http://localhost:3000', 'Zq8-Lw2Ef6Th0Yx4Rn7JbA')
    expect(getRoomIdFromPath(new URL(link).pathname)).toBe('Zq8-Lw2Ef6Th0Yx4Rn7JbA')
  })
})