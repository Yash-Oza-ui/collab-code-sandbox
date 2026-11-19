import { Awareness } from 'y-protocols/awareness'
import type * as Y from 'yjs'

export interface UserPresence {
  name: string
  color: string
  /** Public server member ID; never the private session ID. */
  member_id?: string
}

export interface AwarenessUserState {
  user?: UserPresence
  [key: string]: unknown
}

/**
 * Creates an Awareness instance attached to a Y.Doc.
 */
export function createAwareness(doc: Y.Doc): Awareness {
  return new Awareness(doc)
}

/**
 * Sets the local user presence (name and assigned color) in Yjs awareness.
 * Structured so US-03 live cursors can consume `state.user` directly.
 */
export function setUserAwareness(awareness: Awareness, user: UserPresence): void {
  awareness.setLocalStateField('user', user)
}

/**
 * Gets the local user presence from the awareness instance.
 */

/**
 * Replace remote cursor label text with the server's V2.4 roster names.
 *
 * Awareness is client-writable, so its `user.name` must never be displayed as
 * an authoritative identity. The public member_id is only a lookup hint;
 * no role or permission is inferred from awareness. An unknown member is
 * deliberately left unverified, rather than showing their claimed name.
 *
 * `y-monaco` listens to the Awareness 'change' event for cursor decorations.
 * Fire one synthetic change when a roster update corrects a remote label.
 */
export function applyRosterNamesToAwareness(
  awareness: Awareness,
  members: ReadonlyArray<{ member_id: string; display_name: string }>,
): void {
  const nameById = new Map(members.map((member) => [member.member_id, member.display_name]))
  const updated: number[] = []
  const states = awareness.getStates()

  states.forEach((state, clientId) => {
    if (clientId === awareness.clientID) return
    const peer = state as AwarenessUserState
    if (!peer?.user) return
    const name = (peer.user.member_id && nameById.get(peer.user.member_id)) || 'Unknown participant'
    if (peer.user.name === name) return
    states.set(clientId, { ...peer, user: { ...peer.user, name } })
    updated.push(clientId)
  })

  if (updated.length > 0) {
    awareness.emit('change', [{ added: [], updated, removed: [] }, 'roster'])
  }
}

export function getLocalUserAwareness(awareness: Awareness): UserPresence | null {
  const state = awareness.getLocalState() as AwarenessUserState | null
  return state?.user ?? null
}

/**
 * Collects all user colors currently active across all participants in the room awareness states.
 * Enables selecting a non-colliding color for newly joining participants.
 */
export function getExistingUserColors(awareness: Awareness): string[] {
  const colors: string[] = []
  const states = awareness.getStates()

  states.forEach((state) => {
    const userState = state as AwarenessUserState
    if (userState?.user?.color) {
      colors.push(userState.user.color)
    }
  })

  return colors
}

