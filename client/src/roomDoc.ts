import * as Y from 'yjs'

/**
 * Creates and initializes a Yjs Y.Doc instance for collaborative editing.
 *
 * Traceability: T-01, FR2.
 * Relay connection (via y-websocket to sync-service) and Monaco binding
 * will be hooked up in US-01 and US-02.
 *
 * Per T-01 acceptance criteria and implementation guidance:
 * - yjs is installed as a dependency.
 * - One Y.Doc is created for the page.
 * - Relay connection is NOT connected yet (handled by US-01 / US-02).
 */
export function createRoomDoc(): Y.Doc {
  return new Y.Doc()
}
