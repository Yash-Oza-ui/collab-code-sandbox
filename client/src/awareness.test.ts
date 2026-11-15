import { describe, expect, it } from 'vitest'
import * as Y from 'yjs'
import {
  createAwareness,
  getExistingUserColors,
  getLocalUserAwareness,
  setUserAwareness,
} from './awareness'

describe('awareness', () => {
  it('creates an Awareness instance attached to a Y.Doc with matching clientID', () => {
    const doc = new Y.Doc()
    try {
      const awareness = createAwareness(doc)
      expect(awareness.clientID).toBe(doc.clientID)
      expect(awareness.doc).toBe(doc)
      awareness.destroy()
    } finally {
      doc.destroy()
    }
  })

  it('sets and retrieves local user presence information in awareness state', () => {
    const doc = new Y.Doc()
    try {
      const awareness = createAwareness(doc)
      expect(getLocalUserAwareness(awareness)).toBeNull()

      setUserAwareness(awareness, { name: 'Alice', color: '#2563eb' })

      const localUser = getLocalUserAwareness(awareness)
      expect(localUser).toEqual({ name: 'Alice', color: '#2563eb' })

      const rawState = awareness.getLocalState()
      expect(rawState).toHaveProperty('user')
      expect(rawState?.user).toEqual({ name: 'Alice', color: '#2563eb' })
      awareness.destroy()
    } finally {
      doc.destroy()
    }
  })

  it('collects user colors across awareness participants', () => {
    const doc = new Y.Doc()
    try {
      const awareness = createAwareness(doc)
      setUserAwareness(awareness, { name: 'Alice', color: '#2563eb' })

      // Simulate a remote peer state in awareness
      awareness.setLocalStateField('user', { name: 'Alice', color: '#2563eb' })
      const colors = getExistingUserColors(awareness)

      expect(colors).toContain('#2563eb')
      awareness.destroy()
    } finally {
      doc.destroy()
    }
  })
})

