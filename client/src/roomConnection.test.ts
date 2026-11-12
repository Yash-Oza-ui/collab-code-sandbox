// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { WebsocketProviderMock } = vi.hoisted(() => ({
  WebsocketProviderMock: vi.fn(),
}))

vi.mock('y-websocket', () => ({
  WebsocketProvider: WebsocketProviderMock,
}))

import {
  createControlSocketUrl,
  createDocumentProvider,
  joinRoom,
} from './roomConnection'
import { config } from './config'
import { ownerTokenKey } from './createRoom'

class MockWebSocket {
  static instance: MockWebSocket

  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null

  send = vi.fn()
  close = vi.fn()

  url: string

  constructor(url: string) {
    this.url = url
    MockWebSocket.instance = this
  }

  open() {
    this.onopen?.(new Event('open'))
  }

  receive(data: object) {
    this.onmessage?.(
      new MessageEvent('message', {
        data: JSON.stringify(data),
      }),
    )
  }

  closeWithCode(code: number) {
    this.onclose?.({ code } as CloseEvent)
  }
}

describe('roomConnection', () => {
  const roomId = 'room_123'
  const tokenKey = ownerTokenKey(roomId)

  beforeEach(() => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    vi.clearAllMocks()
    localStorage.removeItem(tokenKey)
  })

  afterEach(() => {
    localStorage.removeItem(tokenKey)
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('builds the control socket URL', () => {
    expect(createControlSocketUrl(roomId, 'session_456')).toBe(
      `${config.syncWsUrl}/room_123?session_id=session_456`,
    )
  })

  it('includes the stored owner token in the creator join message', async () => {
    localStorage.setItem(tokenKey, 'secret-owner-token')

    const promise = joinRoom(roomId, 'Chandan', 'session_456')
    const socket = MockWebSocket.instance

    socket.open()

    expect(socket.send).toHaveBeenCalledWith(
      JSON.stringify({
        type: 'join',
        display_name: 'Chandan',
        owner_token: 'secret-owner-token',
      }),
    )

    expect(socket.url).not.toContain('secret-owner-token')

    socket.receive({ type: 'joined', role: 'host' })

    const result = await promise
    expect(result.joined.role).toBe('host')
  })

  it('allows an ordinary participant to join without an owner token', async () => {
    const promise = joinRoom(roomId, 'Chandan', 'session_456')
    const socket = MockWebSocket.instance

    socket.open()

    expect(socket.send).toHaveBeenCalledWith(
      JSON.stringify({
        type: 'join',
        display_name: 'Chandan',
      }),
    )

    socket.receive({ type: 'joined', role: 'participant' })

    const result = await promise
    expect(result.joined.role).toBe('participant')
  })

  it('still allows joining when local storage is unavailable', async () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem')
      .mockImplementation(() => {
        throw new Error('Storage unavailable')
      })

    const promise = joinRoom(roomId, 'Chandan', 'session_456')
    const socket = MockWebSocket.instance

    socket.open()

    expect(socket.send).toHaveBeenCalledWith(
      JSON.stringify({
        type: 'join',
        display_name: 'Chandan',
      }),
    )

    socket.receive({ type: 'joined' })
    await expect(promise).resolves.toBeDefined()

    getItem.mockRestore()
  })

  it('resolves after server acceptance and removes handshake handlers', async () => {
    const promise = joinRoom(roomId, 'Chandan', 'session_456')
    const socket = MockWebSocket.instance

    socket.open()
    socket.receive({
      type: 'joined',
      member_id: 'member_1',
    })

    const result = await promise

    expect(result.joined.type).toBe('joined')
    expect(result.joined.member_id).toBe('member_1')
    expect(result.socket).toBe(socket)
    expect(socket.onopen).toBeNull()
    expect(socket.onmessage).toBeNull()
    expect(socket.onerror).toBeNull()
    expect(socket.onclose).toBeNull()
    expect(socket.close).not.toHaveBeenCalled()
  })

  it('rejects when the server refuses the join', async () => {
    const promise = joinRoom(roomId, 'Chandan', 'session_456')
    const socket = MockWebSocket.instance

    socket.open()
    socket.receive({
      type: 'join-refused',
      reason: 'NAME_TAKEN',
    })

    await expect(promise).rejects.toThrow(
      'Room join refused: NAME_TAKEN',
    )
    expect(socket.close).toHaveBeenCalled()
  })

  it('rejects when the room does not exist (4404)', async () => {
    const promise = joinRoom('invalid_room', 'Chandan', 'session_456')
    const socket = MockWebSocket.instance

    socket.closeWithCode(4404)

    await expect(promise).rejects.toThrow('Room not found.')
  })

  it('rejects when the control socket encounters an error', async () => {
    const promise = joinRoom(roomId, 'Chandan', 'session_456')
    const socket = MockWebSocket.instance

    socket.onerror?.(new Event('error'))

    await expect(promise).rejects.toThrow(
      'Unable to connect to the room server.',
    )
    expect(socket.close).toHaveBeenCalled()
  })

  it('rejects when the handshake times out and closes the socket', async () => {
    vi.useFakeTimers()

    const promise = joinRoom(
      roomId,
      'Chandan',
      'session_456',
      { timeoutMs: 5000 },
    )
    const socket = MockWebSocket.instance

    const rejection = expect(promise).rejects.toThrow(
      'Room connection timed out. Please try again.',
    )

    await vi.advanceTimersByTimeAsync(5000)
    await rejection

    expect(socket.close).toHaveBeenCalled()
    expect(socket.onopen).toBeNull()
    expect(socket.onmessage).toBeNull()
    expect(socket.onerror).toBeNull()
    expect(socket.onclose).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('cancels a pending handshake and closes the socket when aborted', async () => {
    const controller = new AbortController()
    const promise = joinRoom(
      roomId,
      'Chandan',
      'session_456',
      { signal: controller.signal },
    )
    const socket = MockWebSocket.instance

    const rejection = expect(promise).rejects.toThrow(
      'Room connection cancelled.',
    )

    controller.abort()
    await rejection

    expect(socket.close).toHaveBeenCalled()
    expect(socket.onopen).toBeNull()
    expect(socket.onmessage).toBeNull()
    expect(socket.onerror).toBeNull()
    expect(socket.onclose).toBeNull()
  })

  it('creates the provider for the main document using the same session ID', () => {
    const doc = {} as import('yjs').Doc

    createDocumentProvider(roomId, doc, 'session_456')

    expect(WebsocketProviderMock).toHaveBeenCalledWith(
      `${config.syncWsUrl}/room_123/docs`,
      'main',
      doc,
      {
        params: {
          session_id: 'session_456',
        },
      },
    )
  })
})
