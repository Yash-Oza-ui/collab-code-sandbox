import { describe, expect, it } from 'vitest'
import { getRoomIdFromPath } from './roomId'

describe('getRoomIdFromPath', () => {
  it('returns the room id from /room/{room_id}', () => {
    expect(getRoomIdFromPath('/room/room-demo-01')).toBe('room-demo-01')
  })

  it('accepts a trailing slash', () => {
    expect(getRoomIdFromPath('/room/abc_123/')).toBe('abc_123')
  })

  it('accepts a 64-character id and rejects a 65-character one', () => {
    expect(getRoomIdFromPath('/room/' + 'a'.repeat(64))).toBe('a'.repeat(64))
    expect(getRoomIdFromPath('/room/' + 'a'.repeat(65))).toBeNull()
  })

  it('returns null when there is no room id', () => {
    expect(getRoomIdFromPath('/')).toBeNull()
    expect(getRoomIdFromPath('/room/')).toBeNull()
  })

  it('returns null for characters outside A-Z a-z 0-9 _ -', () => {
    expect(getRoomIdFromPath('/room/bad id')).toBeNull()
    expect(getRoomIdFromPath('/room/bad.id')).toBeNull()
  })

  it('returns null for other paths', () => {
    expect(getRoomIdFromPath('/rooms/abc')).toBeNull()
    expect(getRoomIdFromPath('/room/abc/extra')).toBeNull()
  })
})