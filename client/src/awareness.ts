import { Awareness } from 'y-protocols/awareness'
import type * as Y from 'yjs'

export interface UserPresence {
  name: string
  color: string
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

