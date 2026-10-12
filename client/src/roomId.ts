// Room id format fixed by T-06 section 3.2: 1 to 64 characters from A-Z a-z 0-9 _ -
// Page route fixed by T-06 section 3.2: /room/{room_id}
const ROOM_PATH = /^\/room\/([A-Za-z0-9_-]{1,64})\/?$/

/**
 * Returns the room id from a path such as "/room/room-demo-01",
 * or null if the path is not a valid room route.
 * What to show when this returns null is decided in US-01.
 */
export function getRoomIdFromPath(pathname: string): string | null {
  const match = ROOM_PATH.exec(pathname)
  return match ? match[1] : null
}