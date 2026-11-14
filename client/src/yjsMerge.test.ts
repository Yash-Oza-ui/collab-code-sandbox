
import * as Y from 'yjs'
import { describe, expect, it } from 'vitest'

describe('Yjs concurrent editing', () => {
  it('merges simultaneous edits without losing either contribution', () => {
    const a = new Y.Doc()
    const b = new Y.Doc()

    try {
      // Start with the same shared document state.
      a.getText('t').insert(0, 'hello')
      Y.applyUpdate(b, Y.encodeStateAsUpdate(a))

      // Both users edit independently at the same position.
      a.getText('t').insert(5, ' A')
      b.getText('t').insert(5, ' B')

      // Exchange updates so both documents receive both edits.
      Y.applyUpdate(a, Y.encodeStateAsUpdate(b))
      Y.applyUpdate(b, Y.encodeStateAsUpdate(a))

      const textA = a.getText('t').toString()
      const textB = b.getText('t').toString()

      // Both documents must converge to the same state.
      expect(textA).toBe(textB)

      // Neither user's contribution should be lost.
      expect(textA).toContain('hello')
      expect(textA).toContain(' A')
      expect(textA).toContain(' B')
    } finally {
      a.destroy()
      b.destroy()
    }
  })
})
