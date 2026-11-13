
const SESSION_STORAGE_KEY = 'collab_session_id'

/**
 * Generates a UUID v4, including on HTTP LAN connections
 * where crypto.randomUUID() may not be available.
 */
function generateSessionId(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }

  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)

  // UUID version 4
  bytes[6] = (bytes[6] & 0x0f) | 0x40

  // UUID variant 10xx
  bytes[8] = (bytes[8] & 0x3f) | 0x80

  const hex = Array.from(bytes, (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('')

  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join('-')
}

/**
 * Returns the browser's persistent collaboration session ID.
 */
export function getSessionId(): string {
  const existingId = localStorage.getItem(SESSION_STORAGE_KEY)

  if (existingId) {
    return existingId
  }

  const sessionId = generateSessionId()
  localStorage.setItem(SESSION_STORAGE_KEY, sessionId)

  return sessionId
}
