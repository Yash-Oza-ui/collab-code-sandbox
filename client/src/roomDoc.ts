import { useEffect, useRef } from 'react'
import type { RefObject } from 'react'
import * as Y from 'yjs'

/**
 * Creates and initializes a Yjs Y.Doc instance for collaborative editing.
 *
 * Traceability: T-01, FR2.
 */
export function createRoomDoc(): Y.Doc {
  return new Y.Doc()
}

export interface UseRoomDocOptions {
  /**
   * Optional ref to receive the active Y.Doc instance.
   * Useful for US-01/US-02 integration and component tests.
   */
  docRef?: RefObject<Y.Doc | null>
  /**
   * Factory function to create a Y.Doc instance. Defaults to createRoomDoc.
   * Enables lifecycle tracking and custom instantiation in tests.
   */
  createDoc?: () => Y.Doc
}

export interface UseRoomDocResult {
  docRef: RefObject<Y.Doc | null>
}

/**
 * React lifecycle hook managing the Y.Doc instance for a room.
 *
 * StrictMode Lifecycle Fix (T-01 Review Feedback):
 * In development, React StrictMode simulates a mount -> unmount -> mount cycle
 * (setup -> cleanup -> setup) to detect side-effect bugs.
 *
 * To ensure the active document is never left in a destroyed state after effect replay,
 * each Y.Doc is created inside effect setup and destroyed in its corresponding cleanup.
 *
 * No disposable documents are created during the render phase.
 *
 * The active document remains available via `docRef` for synchronous access
 * by subsequent US-01/US-02 relay provider and Monaco binding integrations.
 */
export function useRoomDoc(
  roomId?: string | null,
  options?: UseRoomDocOptions,
): UseRoomDocResult {
  const internalRef = useRef<Y.Doc | null>(null)
  const targetRef = options?.docRef ?? internalRef
  const createFn = options?.createDoc ?? createRoomDoc

  useEffect(() => {
    // 1. Create Y.Doc inside effect setup (never during render)
    const activeDoc = createFn()
    targetRef.current = activeDoc

    // 2. Destroy that same instance in its cleanup
    return () => {
      activeDoc.destroy()
      if (targetRef.current === activeDoc) {
        targetRef.current = null
      }
    }
  }, [roomId, targetRef, createFn])

  return { docRef: targetRef }
}
