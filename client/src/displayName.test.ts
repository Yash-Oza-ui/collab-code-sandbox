import { describe, expect, it } from 'vitest'
import { MAX_DISPLAY_NAME_LENGTH, validateDisplayName } from './displayName'

describe('validateDisplayName', () => {
  it('trims leading and trailing whitespace', () => {
    expect(validateDisplayName('  Alice  ')).toEqual({ valid: true, displayName: 'Alice' })
  })

  it('rejects an empty value', () => {
    expect(validateDisplayName('   ')).toEqual({
      valid: false,
      message: 'Display name cannot be empty.',
    })
  })

  it('rejects a non-string form value', () => {
    expect(validateDisplayName(null).valid).toBe(false)
  })

  it('accepts a name at the 50-character limit', () => {
    const name = 'a'.repeat(MAX_DISPLAY_NAME_LENGTH)
    expect(validateDisplayName(name)).toEqual({ valid: true, displayName: name })
  })

  it('rejects a name longer than 50 characters', () => {
    expect(validateDisplayName('a'.repeat(MAX_DISPLAY_NAME_LENGTH + 1))).toEqual({
      valid: false,
      message: 'Display name must be 50 characters or fewer.',
    })
  })
})
