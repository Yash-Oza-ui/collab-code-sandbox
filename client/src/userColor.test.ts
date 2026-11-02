import { describe, expect, it } from 'vitest'
import { getUserColor, pickUserColor, USER_COLORS } from './userColor'

describe('userColor', () => {
  describe('getUserColor', () => {
    it('gives mutually distinct colors for the first N users', () => {
      const n = USER_COLORS.length
      const assignedColors = Array.from({ length: n }, (_, i) => getUserColor(i))
      const uniqueColors = new Set(assignedColors)

      expect(assignedColors.length).toBe(n)
      expect(uniqueColors.size).toBe(n)
    })

    it('wraps around deterministically after N users', () => {
      const n = USER_COLORS.length
      expect(getUserColor(n)).toBe(getUserColor(0))
      expect(getUserColor(n + 1)).toBe(getUserColor(1))
      expect(getUserColor(2 * n + 3)).toBe(getUserColor(3))
    })

    it('handles negative indices gracefully', () => {
      expect(getUserColor(-1)).toBe(getUserColor(1))
      expect(getUserColor(-5)).toBe(getUserColor(5))
    })
  })

  describe('pickUserColor', () => {
    it('returns the first available color when no colors are in use', () => {
      expect(pickUserColor([])).toBe(USER_COLORS[0])
    })

    it('picks an unused color among current room participants', () => {
      const existing = [USER_COLORS[0], USER_COLORS[1]]
      const picked = pickUserColor(existing)

      expect(picked).toBe(USER_COLORS[2])
      expect(existing).not.toContain(picked)
    })

    it('performs case-insensitive color matching', () => {
      const existing = [USER_COLORS[0].toLowerCase(), USER_COLORS[1].toUpperCase()]
      const picked = pickUserColor(existing)

      expect(picked).toBe(USER_COLORS[2])
    })

    it('falls back deterministically when all palette colors are in use', () => {
      const picked = pickUserColor(USER_COLORS, 3)
      expect(picked).toBe(USER_COLORS[3])
    })
  })
})

