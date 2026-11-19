import { describe, expect, it, vi } from 'vitest'
import {
  applyAwarenessUpdate,
  encodeAwarenessUpdate,
} from 'y-protocols/awareness'
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
  it('removes a remote collaborator after 30 seconds without awareness updates', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })

    const aliceDoc = new Y.Doc()
    const bobDoc = new Y.Doc()
    const aliceAwareness = createAwareness(aliceDoc)
    const bobAwareness = createAwareness(bobDoc)

    try {
      bobAwareness.setLocalState({
        user: { name: 'Bob', color: '#2563eb' },
      })

      const bobClientID = bobAwareness.clientID
      const removedClients: number[] = []

      aliceAwareness.on('change', ({ removed }: { removed: number[] }) => {
        removedClients.push(...removed)
      })

      applyAwarenessUpdate(
        aliceAwareness,
        encodeAwarenessUpdate(bobAwareness, [bobClientID]),
        'test',
      )

      expect(aliceAwareness.getStates().has(bobClientID)).toBe(true)

      // Simulate Bob's last awareness update being 31 seconds old.
      const bobMeta = aliceAwareness.meta.get(bobClientID)
      expect(bobMeta).toBeDefined()

      bobMeta!.lastUpdated = Date.now() - 31_000

      await vi.advanceTimersByTimeAsync(3_000)

      expect(aliceAwareness.getStates().has(bobClientID)).toBe(false)
      expect(removedClients).toContain(bobClientID)
    } finally {
      aliceAwareness.destroy()
      bobAwareness.destroy()
      aliceDoc.destroy()
      bobDoc.destroy()
      vi.useRealTimers()
    }
  })
})

