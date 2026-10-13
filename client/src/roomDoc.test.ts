import { describe, expect, it } from 'vitest'
import * as Y from 'yjs'
import { createRoomDoc } from './roomDoc'

describe('createRoomDoc', () => {
  it('creates an initialized Y.Doc instance with valid clientID', () => {
    const doc = createRoomDoc()
    try {
      expect(doc).toBeInstanceOf(Y.Doc)
      expect(typeof doc.clientID).toBe('number')
      expect(doc.clientID).toBeGreaterThan(0)
    } finally {
      doc.destroy()
    }
  })

  it('allows creating and mutating shared types in the Y.Doc', () => {
    const doc = createRoomDoc()
    try {
      const text = doc.getText('monaco')
      text.insert(0, 'console.log("hello collab");')
      expect(text.toString()).toBe('console.log("hello collab");')
    } finally {
      doc.destroy()
    }
  })

  it('destroys cleanly without throwing', () => {
    const doc = createRoomDoc()
    expect(() => doc.destroy()).not.toThrow()
  })
})
