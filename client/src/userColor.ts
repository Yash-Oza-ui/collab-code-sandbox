/**
 * Curated palette of 10 distinct, accessible, high-contrast colors.
 * Sized to cover the NFR2 maximum room capacity (up to 10 users).
 */
export const USER_COLORS: readonly string[] = [
  '#e11d48', // rose
  '#2563eb', // blue
  '#16a34a', // green
  '#d97706', // amber
  '#7c3aed', // violet
  '#0891b2', // cyan
  '#ea580c', // orange
  '#4f46e5', // indigo
  '#059669', // emerald
  '#c026d3', // fuchsia
]

/**
 * Pure function returning a color deterministically from the palette.
 * The first N users (where N <= USER_COLORS.length) each receive a distinct color.
 */
export function getUserColor(index: number): string {
  const normalized = Math.abs(Math.floor(index)) % USER_COLORS.length
  return USER_COLORS[normalized]
}

/**
 * Pure function that selects an unused color among current room participants.
 * If every palette color is currently taken, it falls back deterministically to fallbackIndex.
 */
export function pickUserColor(
  existingColors: readonly string[] = [],
  fallbackIndex = 0,
): string {
  const used = new Set(existingColors.map((c) => c.toLowerCase()))
  const available = USER_COLORS.find((c) => !used.has(c.toLowerCase()))

  if (available) {
    return available
  }

  return getUserColor(fallbackIndex)
}

