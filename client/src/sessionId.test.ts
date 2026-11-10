// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getSessionId } from './sessionId'

describe('getSessionId', () => {
  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('generates and stores a valid UUID v4', () => {
    const sessionId = getSessionId()

    expect(sessionId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
    expect(localStorage.getItem('collab_session_id')).toBe(sessionId)
  })

  it('reuses the existing session ID', () => {
    localStorage.setItem(
      'collab_session_id',
      '123e4567-e89b-42d3-a456-426614174000',
    )

    const sessionId = getSessionId()

    expect(sessionId).toBe('123e4567-e89b-42d3-a456-426614174000')
  })
})
