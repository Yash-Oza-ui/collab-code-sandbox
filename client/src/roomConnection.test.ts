

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
  getSessionId,
  joinRoom,
} from './roomConnection'
import { config } from './config'


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
  let storage: Storage

  beforeEach(() => {
    storage = {
      getItem: vi.fn(),
      setItem: vi.fn(),
      removeItem: vi.fn(),
      clear: vi.fn(),
      key: vi.fn(),
      length: 0,
    } as unknown as Storage

    vi.stubGlobal('localStorage', storage)
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValue('test-session-id'),
    })

    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })


  it('sends join and resolves after the server accepts', async () => {
    vi.stubGlobal('WebSocket', MockWebSocket)

    const promise = joinRoom('room_123', 'Chandan', 'session_456')
    const socket = MockWebSocket.instance

    socket.open()

    expect(socket.send).toHaveBeenCalledWith(
      JSON.stringify({
        type: 'join',
        display_name: 'Chandan',
      }),
    )

    socket.receive({
      type: 'joined',
      member_id: 'member_1',
    })

    const result = await promise

    expect(result.joined.type).toBe('joined')
    expect(result.joined.member_id).toBe('member_1')
    expect(result.socket).toBe(socket)
  })

  it('rejects when the server refuses the join', async () => {
    vi.stubGlobal('WebSocket', MockWebSocket)

    const promise = joinRoom('room_123', 'Chandan', 'session_456')
    const socket = MockWebSocket.instance

    socket.open()
    socket.receive({
      type: 'join-refused',
      reason: 'NAME_TAKEN',
    })

    await expect(promise).rejects.toThrow(
      'Room join refused: NAME_TAKEN',
    )
  })

  it('rejects when the room does not exist (4404)', async () => {
    vi.stubGlobal('WebSocket', MockWebSocket)

    const promise = joinRoom('invalid_room', 'Chandan', 'session_456')
    const socket = MockWebSocket.instance

    socket.closeWithCode(4404)

    await expect(promise).rejects.toThrow('Room not found.')
  })


  it('creates and stores a session ID when none exists', () => {
    vi.mocked(storage.getItem).mockReturnValue(null)

    expect(getSessionId()).toBe('test-session-id')
    expect(storage.setItem).toHaveBeenCalledWith(
      'collab_session_id',
      'test-session-id',
    )
  })

  it('reuses an existing session ID', () => {
    vi.mocked(storage.getItem).mockReturnValue('existing-session-id')

    expect(getSessionId()).toBe('existing-session-id')
    expect(storage.setItem).not.toHaveBeenCalled()
  })

  it('builds the control socket URL', () => {
    const url = createControlSocketUrl('room_123', 'session_456')

    expect(url).toBe(
      `${config.syncWsUrl}/room_123?session_id=session_456`,
    )
  })

  it('creates the document provider for the main document', () => {
    const doc = {} as import('yjs').Doc

    createDocumentProvider('room_123', doc, 'session_456')

    expect(WebsocketProviderMock).toHaveBeenCalledWith(
      `${config.syncWsUrl}/room_123/docs`,
      'main',
      doc,
      { params: { session_id: 'session_456' } },
    )
  })
})
