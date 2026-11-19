import { describe, expect, it, vi } from 'vitest'
import {
  applyAwarenessUpdate,
  encodeAwarenessUpdate,
} from 'y-protocols/awareness'
import * as Y from 'yjs'
import {
  applyRosterNamesToAwareness,
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

  it('uses roster names for remote cursors and never shows forged awareness text', () => {
    const doc = new Y.Doc()
    const awareness = createAwareness(doc)
    try {
      setUserAwareness(awareness, { name: 'Local', color: '#2563eb', member_id: 'm_local' })
      const remoteClientId = awareness.clientID + 1
      const unknownClientId = awareness.clientID + 2
      awareness.getStates().set(remoteClientId, {
        user: { member_id: 'm_ada', name: 'Forged name', color: '#ef4444' },
      })
      awareness.getStates().set(unknownClientId, {
        user: { member_id: 'm_not_in_roster', name: 'Fake owner', color: '#22c55e' },
      })
      const updates: number[][] = []
      awareness.on('change', (changes: { updated: number[] }) => updates.push(changes.updated))

      applyRosterNamesToAwareness(awareness, [
        { member_id: 'm_local', display_name: 'Local' },
        { member_id: 'm_ada', display_name: 'Ada' },
      ])
      const remote = awareness.getStates().get(remoteClientId) as { user: { name: string; color: string } }
      const unknown = awareness.getStates().get(unknownClientId) as { user: { name: string } }
      expect(remote.user.name).toBe('Ada')
      expect(remote.user.color).toBe('#ef4444')
      expect(unknown.user.name).toBe('Unknown participant')
      expect(getLocalUserAwareness(awareness)?.name).toBe('Local')
      expect(updates.flat()).toEqual(expect.arrayContaining([remoteClientId, unknownClientId]))

      // Repeated roster updates must not emit an endless change loop.
      const count = updates.length
      applyRosterNamesToAwareness(awareness, [
        { member_id: 'm_local', display_name: 'Local' },
        { member_id: 'm_ada', display_name: 'Ada' },
      ])
      expect(updates.length).toBe(count)

      // A later awareness update must not be allowed to restore a forged name.
      awareness.getStates().set(remoteClientId, {
        user: { member_id: 'm_ada', name: 'Forged again', color: '#ef4444' },
      })
      applyRosterNamesToAwareness(awareness, [
        { member_id: 'm_local', display_name: 'Local' },
        { member_id: 'm_ada', display_name: 'Ada' },
      ])
      expect((awareness.getStates().get(remoteClientId) as { user: { name: string } }).user.name).toBe('Ada')
    } finally {
      awareness.destroy()
      doc.destroy()
    }
  })})

