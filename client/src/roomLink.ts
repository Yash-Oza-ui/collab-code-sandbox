// Builds the shareable join link. Page route fixed by T-06 section 3.2: /room/{room_id}
// The origin is passed in (the caller uses the browser's current origin) so this stays testable.
export function buildRoomLink(origin: string, roomId: string): string {
  return `${origin.replace(/\/+$/, '')}/room/${encodeURIComponent(roomId)}`
}