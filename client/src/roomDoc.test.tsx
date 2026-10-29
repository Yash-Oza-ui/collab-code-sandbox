// @vitest-environment happy-dom
import { StrictMode, act } from 'react'
import type { RefObject } from 'react'
import { createRoot } from 'react-dom/client'
import { describe, expect, it, vi } from 'vitest'
import * as Y from 'yjs'
import { createRoomDoc, useRoomDoc } from './roomDoc'
import RoomPage from './RoomPage'

// Configure React act environment for Vitest/happy-dom
;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

vi.mock('@monaco-editor/react', () => ({
  default: () => <div data-testid="mock-monaco-editor" />,
}))

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
    expect(doc.isDestroyed).toBe(true)
  })
})

describe('useRoomDoc StrictMode regression tests', () => {
  it('under StrictMode: replaced document is destroyed, document after effect replay is live, and document is destroyed on unmount', async () => {
    const createdDocs: Y.Doc[] = []
    const externalRef: RefObject<Y.Doc | null> = { current: null }

    const trackCreateDoc = () => {
      const d = createRoomDoc()
      createdDocs.push(d)
      return d
    }

    function TestComponent() {
      useRoomDoc('test-room', {
        docRef: externalRef,
        createDoc: trackCreateDoc,
      })
      return <div>room test component</div>
    }

    const container = document.createElement('div')
    document.body.appendChild(container)
    const root = createRoot(container)

    // Mount under StrictMode
    await act(async () => {
      root.render(
        <StrictMode>
          <TestComponent />
        </StrictMode>,
      )
    })

    // StrictMode in development runs setup -> cleanup -> setup.
    // Two documents must have been created across the two setup cycles.
    expect(createdDocs.length).toBe(2)
    const [firstDoc, secondDoc] = createdDocs

    // 1. Initial document must be destroyed during cleanup
    expect(firstDoc.isDestroyed).toBe(true)

    // 2. Active document after effect replay must be live and usable
    expect(secondDoc.isDestroyed).toBe(false)
    expect(externalRef.current).toBe(secondDoc)

    // Live document can be mutated without error
    const text = secondDoc.getText('monaco')
    text.insert(0, 'live content')
    expect(text.toString()).toBe('live content')

    // 3. Final unmount must destroy the active document
    await act(async () => {
      root.unmount()
    })

    expect(secondDoc.isDestroyed).toBe(true)
    expect(externalRef.current).toBeNull()

    container.remove()
  })

  it('destroys old document and replaces with live document when roomId changes', async () => {
    const externalRef: RefObject<Y.Doc | null> = { current: null }

    function TestComponent({ roomId }: { roomId: string }) {
      useRoomDoc(roomId, { docRef: externalRef })
      return <div>room-{roomId}</div>
    }

    const container = document.createElement('div')
    document.body.appendChild(container)
    const root = createRoot(container)

    await act(async () => {
      root.render(
        <StrictMode>
          <TestComponent roomId="room-alpha" />
        </StrictMode>,
      )
    })

    const alphaDoc = externalRef.current
    expect(alphaDoc).not.toBeNull()
    expect(alphaDoc!.isDestroyed).toBe(false)

    // Change roomId to room-beta
    await act(async () => {
      root.render(
        <StrictMode>
          <TestComponent roomId="room-beta" />
        </StrictMode>,
      )
    })

    const betaDoc = externalRef.current
    expect(betaDoc).not.toBeNull()
    expect(betaDoc).not.toBe(alphaDoc)
    expect(alphaDoc!.isDestroyed).toBe(true)
    expect(betaDoc!.isDestroyed).toBe(false)

    await act(async () => {
      root.unmount()
    })

    expect(betaDoc!.isDestroyed).toBe(true)
    expect(externalRef.current).toBeNull()

    container.remove()
  })
})

describe('RoomPage component StrictMode regression tests', () => {
  it('exercises RoomPage under StrictMode and ensures active Y.Doc is live and destroyed on unmount', async () => {
    const externalRef: RefObject<Y.Doc | null> = { current: null }
    const container = document.createElement('div')
    document.body.appendChild(container)
    const root = createRoot(container)

    await act(async () => {
      root.render(
        <StrictMode>
          <RoomPage docRef={externalRef} />
        </StrictMode>,
      )
    })

    // Active document after StrictMode effect replay must be live
    const activeDoc = externalRef.current
    expect(activeDoc).not.toBeNull()
    expect(activeDoc!.isDestroyed).toBe(false)

    // Document is functional
    activeDoc!.getText('monaco').insert(0, 'room document test')
    expect(activeDoc!.getText('monaco').toString()).toBe('room document test')

    // Unmount RoomPage
    await act(async () => {
      root.unmount()
    })

    // Final document must be destroyed on unmount
    expect(activeDoc!.isDestroyed).toBe(true)
    expect(externalRef.current).toBeNull()

    container.remove()
  })
})
