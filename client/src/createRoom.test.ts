import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PLACEHOLDER_CREATE_ROOM_BODY,
  createRoom,
  ownerTokenKey,
} from './createRoom'

const PLATFORM_URL = 'http://platform.test:8081'
const ROOM_ID = 'k3Yc1t9Qx7mBvA2nR0sLpQ'
const TOKEN = 'PLACEHOLDER_NOT_A_REAL_TOKEN'

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function createdResponse(): Response {
  return jsonResponse(
    {
      room_id: ROOM_ID,
      owner_token: TOKEN,
      mode: 'pair',
      preset: 'debug_together',
      language: null,
    },
    201,
  )
}

describe('createRoom', () => {
  const fetchMock = vi.fn()
  let storage: Storage

  beforeEach(() => {
    fetchMock.mockReset()
    storage = {
      getItem: vi.fn(),
      setItem: vi.fn(),
      removeItem: vi.fn(),
      clear: vi.fn(),
      key: vi.fn(),
      length: 0,
    } as unknown as Storage
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', storage)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts the placeholder body to /v1/rooms', async () => {
    fetchMock.mockResolvedValue(createdResponse())
    await createRoom(PLATFORM_URL)
    expect(fetchMock).toHaveBeenCalledWith(`${PLATFORM_URL}/v1/rooms`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(PLACEHOLDER_CREATE_ROOM_BODY),
    })
  })

  it('ignores a trailing slash on the platform URL', async () => {
    fetchMock.mockResolvedValue(createdResponse())
    await createRoom(`${PLATFORM_URL}/`)
    expect(fetchMock.mock.calls[0][0]).toBe(`${PLATFORM_URL}/v1/rooms`)
  })

  it('returns the room id and stores the owner token for that room', async () => {
    fetchMock.mockResolvedValue(createdResponse())
    const result = await createRoom(PLATFORM_URL)
    expect(result).toEqual({ roomId: ROOM_ID, ownerTokenStored: true })
    expect(storage.setItem).toHaveBeenCalledWith(ownerTokenKey(ROOM_ID), TOKEN)
  })

  it('uses the server error message from the error envelope', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          request_id: null,
          error: {
            code: 'INVALID_REQUEST',
            message: "Field 'mode' is missing or invalid.",
            retry_after_seconds: null,
          },
        },
        422,
      ),
    )
    await expect(createRoom(PLATFORM_URL)).rejects.toThrow(
      "Field 'mode' is missing or invalid.",
    )
    expect(storage.setItem).not.toHaveBeenCalled()
  })

  it('falls back to a generic message when the error body is not JSON', async () => {
    fetchMock.mockResolvedValue(new Response('Bad gateway', { status: 502 }))
    await expect(createRoom(PLATFORM_URL)).rejects.toThrow(
      'Room creation failed (HTTP 502).',
    )
  })

  it('reports a friendly message when the server cannot be reached', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(createRoom(PLATFORM_URL)).rejects.toThrow(
      'Unable to reach the room server.',
    )
    expect(storage.setItem).not.toHaveBeenCalled()
  })

  it('rejects a response with an invalid room id and stores nothing', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ room_id: 'bad id!', owner_token: TOKEN }, 201),
    )
    await expect(createRoom(PLATFORM_URL)).rejects.toThrow(
      'The room server sent an invalid response.',
    )
    expect(storage.setItem).not.toHaveBeenCalled()
  })

  it('rejects a response without an owner token', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ room_id: ROOM_ID }, 201))
    await expect(createRoom(PLATFORM_URL)).rejects.toThrow(
      'The room server sent an invalid response.',
    )
  })

  it('still returns the room when browser storage is unavailable', async () => {
    fetchMock.mockResolvedValue(createdResponse())
    vi.mocked(storage.setItem).mockImplementation(() => {
      throw new Error('storage disabled')
    })
    const result = await createRoom(PLATFORM_URL)
    expect(result).toEqual({ roomId: ROOM_ID, ownerTokenStored: false })
  })
})
