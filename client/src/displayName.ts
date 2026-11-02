export const MAX_DISPLAY_NAME_LENGTH = 50

export type DisplayNameValidation =
  | { valid: true; displayName: string }
  | { valid: false; message: string }

/**
 * Trims and validates a guest display name before the room editor is shown.
 */
export function validateDisplayName(value: FormDataEntryValue | null): DisplayNameValidation {
  if (typeof value !== 'string') {
    return { valid: false, message: 'Please enter a display name.' }
  }

  const displayName = value.trim()

  if (!displayName) {
    return { valid: false, message: 'Display name cannot be empty.' }
  }

  if (displayName.length > MAX_DISPLAY_NAME_LENGTH) {
    return {
      valid: false,
      message: `Display name must be ${MAX_DISPLAY_NAME_LENGTH} characters or fewer.`,
    }
  }

  return { valid: true, displayName }
}
