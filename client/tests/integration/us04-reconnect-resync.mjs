import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'
import * as Y from 'yjs'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'
import * as sync from 'y-protocols/sync'

const api = process.env.SYNC_SERVICE_URL || 'http://localhost:8081'
const wsBase = api.replace(/^http/, 'ws')
const timeoutMs = 10000

function waitFor(predicate, description) {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const timer = setInterval(() => {
      if (predicate()) {
        clearInterval(timer)
        resolve()
      } else if (Date.now() - start > timeoutMs) {
        clearInterval(timer)
        reject(new Error(`Timed out: ${description}`))
      }
    }, 25)
  })
}

function openSocket(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url)
    socket.once('open', () => resolve(socket))
    socket.once('error', reject)
  })
}

async function join(roomId, sessionId, name, ownerToken) {
  const url =
    `${wsBase}/ws/rooms/${encodeURIComponent(roomId)}` +
    `?session_id=${encodeURIComponent(sessionId)}`

  const socket = await openSocket(url)

  const joined = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`Join timeout: ${name}`))
    }, timeoutMs)

    socket.on('message', (data, binary) => {
      if (binary) return

      const message = JSON.parse(data.toString())

      if (message.type === 'joined') {
        clearTimeout(timer)
        resolve(message)
      } else if (message.type === 'join-refused') {
        clearTimeout(timer)
        reject(new Error(`Join refused: ${message.reason}`))
      }
    })

    socket.once('close', (code) => {
      clearTimeout(timer)
      reject(new Error(`Control socket closed: ${code}`))
    })
  })

  socket.send(JSON.stringify({
    type: 'join',
    display_name: name,
    ...(ownerToken ? { owner_token: ownerToken } : {}),
  }))

  const joinedMessage = await joined
  console.log('DEBUG: Joined permissions:', name, JSON.stringify({
    role: joinedMessage.role,
    permissions: joinedMessage.permissions,
  }))
  return socket
}

async function connectDocument(roomId, sessionId, doc) {
  const url =
    `${wsBase}/ws/rooms/${encodeURIComponent(roomId)}` +
    `/docs/main?session_id=${encodeURIComponent(sessionId)}`

  const socket = await openSocket(url)

  function sendSync(writer) {
    if (socket.readyState !== WebSocket.OPEN) return

    const encoder = encoding.createEncoder()
    encoding.writeVarUint(encoder, 0)
    writer(encoder)
    socket.send(encoding.toUint8Array(encoder))
  }

  const onUpdate = (update, origin) => {
    if (origin === socket) return
    sendSync((encoder) => sync.writeUpdate(encoder, update))
  }

  doc.on('update', onUpdate)

  let reverseSyncSent = false

  socket.on('message', (data, binary) => {
    if (!binary) return

    const bytes = new Uint8Array(data)
    const decoder = decoding.createDecoder(bytes)
    const type = decoding.readVarUint(decoder)
    if (type !== 0) return

    const encoder = encoding.createEncoder()
    encoding.writeVarUint(encoder, 0)

    const syncType = sync.readSyncMessage(
      decoder,
      encoder,
      doc,
      socket,
    )

    const reply = encoding.toUint8Array(encoder)

    if (reply.length > 1 && socket.readyState === WebSocket.OPEN) {
      socket.send(reply)
    }

    // Ask the server for changes missing from this local document.
    // A reverse SyncStep1 is necessary when the server initiates
    // synchronization with a client that has offline edits.
    if (
      !reverseSyncSent &&
      syncType === sync.messageYjsSyncStep1 &&
      socket.readyState === WebSocket.OPEN
    ) {
      reverseSyncSent = true

      sendSync((writer) => {
        sync.writeSyncStep1(writer, doc)
      })
    }
  })

  socket.on('close', () => {
    doc.off('update', onUpdate)
  })

  sendSync((encoder) => sync.writeSyncStep1(encoder, doc))
  sendSync((encoder) => sync.writeSyncStep2(encoder, doc))

  return socket
}

async function closeSocket(socket) {
  if (!socket || socket.readyState === WebSocket.CLOSED) return

  await new Promise((resolve) => {
    socket.once('close', resolve)
    socket.close()
  })
}

const sockets = []
const documents = []

try {
  const response = await fetch(`${api}/v1/rooms`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      mode: 'pair',
      preset: 'teach_interview',
    }),
  })

  assert.equal(response.status, 201, 'Room creation must succeed')

  const room = await response.json()
  const roomId = room.room_id
  const ownerToken = room.owner_token

  assert.ok(roomId, 'Room ID is required')
  assert.ok(ownerToken, 'Owner token is required')

  const sessionA = randomUUID()
  const sessionB = randomUUID()

  let controlA = await join(roomId, sessionA, 'US04Alice', ownerToken)
  sockets.push(controlA)

  const controlB = await join(roomId, sessionB, 'US04Bob')
  sockets.push(controlB)

  const docA = new Y.Doc()
  const docB = new Y.Doc()
  documents.push(docA, docB)

  let socketA = await connectDocument(roomId, sessionA, docA)
  sockets.push(socketA)

  const socketB = await connectDocument(roomId, sessionB, docB)
  sockets.push(socketB)

  docA.getText('content').insert(0, 'before-disconnect')

  await waitFor(
    () => docB.getText('content').toString() === 'before-disconnect',
    'initial Yjs synchronization',
  )

  console.log('PASS: Initial document synchronization')

  await closeSocket(socketA)
  await closeSocket(controlA)

  docA.getText('content').insert(
    docA.getText('content').length,
    '-offline-A',
  )

  // Bob is read-only in Pair mode, so he must not generate edits.
  // Alice's offline edit should synchronize after reconnecting.

  controlA = await join(roomId, sessionA, 'US04Alice', ownerToken)
  sockets.push(controlA)

  socketA = await connectDocument(roomId, sessionA, docA)
  sockets.push(socketA)

  await waitFor(() => {
    const a = docA.getText('content').toString()
    const b = docB.getText('content').toString()

    return a === b &&
      a.includes('before-disconnect') &&
      a.includes('offline-A')
  }, 'host offline edit to reach participant')

  console.log('PASS: Rejoined with original session ID and name')
  console.log('PASS: Offline host edit synchronized to participant')
  console.log('PASS: US-04 Pair-mode real-service integration')

} catch (error) {
  console.error('FAIL: US-04 integration:', error.message)
  process.exitCode = 1
} finally {
  for (const socket of sockets.reverse()) {
    try {
      await closeSocket(socket)
    } catch {
      // Best-effort cleanup
    }
  }

  for (const doc of documents) doc.destroy()
}
