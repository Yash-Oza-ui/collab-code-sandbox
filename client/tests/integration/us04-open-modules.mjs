import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'
import * as Y from 'yjs'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'
import * as sync from 'y-protocols/sync'

const api = (process.env.SYNC_SERVICE_URL || 'http://localhost:8081').replace(/\/$/, '')
const wsBase = api.replace(/^http/, 'ws')
const timeoutMs = 10000
const sockets = []
const documents = []

function waitFor(predicate, description) {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const timer = setInterval(() => {
      try {
        if (predicate()) {
          clearInterval(timer)
          resolve()
        } else if (Date.now() - start > timeoutMs) {
          clearInterval(timer)
          reject(new Error(`Timed out: ${description}`))
        }
      } catch (error) {
        clearInterval(timer)
        reject(error)
      }
    }, 25)
  })
}

async function request(path, method = 'GET', body, headers = {}) {
  const response = await fetch(`${api}${path}`, {
    method,
    headers: {
      ...headers,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })

  const text = await response.text()
  let data
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }

  if (!response.ok) {
    throw new Error(`${method} ${path}: HTTP ${response.status}: ${JSON.stringify(data)}`)
  }

  return { status: response.status, data }
}

function openSocket(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url, { handshakeTimeout: timeoutMs })
    const timer = setTimeout(() => {
      socket.terminate()
      reject(new Error(`WebSocket connection timeout`))
    }, timeoutMs)

    socket.once('open', () => {
      clearTimeout(timer)
      sockets.push(socket)
      resolve(socket)
    })

    socket.once('error', (error) => {
      clearTimeout(timer)
      reject(error)
    })
  })
}

async function join(roomId, sessionId, name, ownerToken) {
  const url = `${wsBase}/ws/rooms/${encodeURIComponent(roomId)}?session_id=${encodeURIComponent(sessionId)}`
  const socket = await openSocket(url)

  const joined = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Join timeout: ${name}`)), timeoutMs)

    socket.on('message', (data, binary) => {
      if (binary) return
      let message
      try {
        message = JSON.parse(data.toString())
      } catch {
        return
      }

      if (message.type === 'joined') {
        clearTimeout(timer)
        resolve(message)
      } else if (message.type === 'join-refused') {
        clearTimeout(timer)
        reject(new Error(`Join refused: ${JSON.stringify(message)}`))
      }
    })

    socket.once('close', (code) => {
      clearTimeout(timer)
      reject(new Error(`Control socket closed before join: ${code}`))
    })
  })

  socket.send(JSON.stringify({
    type: 'join',
    display_name: name,
    ...(ownerToken ? { owner_token: ownerToken } : {}),
  }))

  const message = await joined
  console.log(`JOINED: ${name}, role=${message.role}, can_edit=${message.permissions?.can_edit}`)
  return socket
}

async function connectDocument(roomId, moduleId, sessionId, doc) {
  const url =
    `${wsBase}/ws/rooms/${encodeURIComponent(roomId)}` +
    `/docs/${encodeURIComponent(moduleId)}` +
    `?session_id=${encodeURIComponent(sessionId)}`

  const socket = await openSocket(url)

  function send(writer) {
    if (socket.readyState !== WebSocket.OPEN) return

    const encoder = encoding.createEncoder()
    encoding.writeVarUint(encoder, 0)
    writer(encoder)
    socket.send(encoding.toUint8Array(encoder))
  }

  const updateHandler = (update, origin) => {
    if (origin !== socket) {
      send((encoder) => sync.writeUpdate(encoder, update))
    }
  }

  doc.on('update', updateHandler)

  socket.on('message', (data, binary) => {
    if (!binary) return

    try {
      const decoder = decoding.createDecoder(new Uint8Array(data))
      if (decoding.readVarUint(decoder) !== 0) return

      const encoder = encoding.createEncoder()
      encoding.writeVarUint(encoder, 0)
      sync.readSyncMessage(decoder, encoder, doc, socket)

      const reply = encoding.toUint8Array(encoder)
      if (reply.length > 1 && socket.readyState === WebSocket.OPEN) {
        socket.send(reply)
      }
    } catch (error) {
      console.error(`Yjs frame error for ${moduleId}:`, error)
    }
  })

  socket.on('close', (code, reason) => {
    doc.off('update', updateHandler)
    if (code !== 1000 && code !== 1001) {
      console.error(`DOC CLOSED ${moduleId}: ${code} ${reason.toString()}`)
    }
  })

  send((encoder) => sync.writeSyncStep1(encoder, doc))
  send((encoder) => sync.writeSyncStep2(encoder, doc))

  return socket
}

async function closeSocket(socket) {
  if (!socket || socket.readyState === WebSocket.CLOSED) return
  if (socket.readyState === WebSocket.CONNECTING) {
    socket.terminate()
    return
  }

  await new Promise((resolve) => {
    const timer = setTimeout(() => {
      socket.terminate()
      resolve()
    }, 2000)

    socket.once('close', () => {
      clearTimeout(timer)
      resolve()
    })

    socket.close()
  })
}

async function createApprovedModule(roomId, sessionId, ownerToken, name) {
  const base = `/v1/rooms/${encodeURIComponent(roomId)}/modules`

  const created = await request(
    base,
    'POST',
    { name },
    { 'X-Session-Id': sessionId },
  )

  assert.equal(created.status, 202)
  const moduleId = created.data.module_id
  assert.ok(moduleId, `Missing module_id for ${name}`)

  const approved = await request(
    `${base}/${encodeURIComponent(moduleId)}/approve`,
    'POST',
    undefined,
    {
      'X-Session-Id': sessionId,
      'X-Owner-Token': ownerToken,
    },
  )

  assert.equal(approved.status, 200)
  console.log(`APPROVED: ${name} (${moduleId})`)
  return moduleId
}

try {
  const created = await request('/v1/rooms', 'POST', {
    mode: 'open',
    language: 'python',
  })

  assert.equal(created.status, 201)

  const roomId = created.data.room_id
  const ownerToken = created.data.owner_token
  assert.ok(roomId)
  assert.ok(ownerToken)

  const hostSession = randomUUID()
  const observerSession = randomUUID()

  let hostControl = await join(roomId, hostSession, 'US04OpenHost', ownerToken)

  const moduleA = await createApprovedModule(
    roomId, hostSession, ownerToken, 'us04_alpha.py',
  )
  const moduleB = await createApprovedModule(
    roomId, hostSession, ownerToken, 'us04_beta.py',
  )

  const listed = await request(
    `/v1/rooms/${encodeURIComponent(roomId)}/modules`,
    'GET',
    undefined,
    { 'X-Session-Id': hostSession },
  )

  assert.ok(Array.isArray(listed.data))
  assert.ok(listed.data.some((m) => m.module_id === moduleA))
  assert.ok(listed.data.some((m) => m.module_id === moduleB))
  console.log('PASS: Both approved modules are listed')

  const observerControl = await join(
    roomId, observerSession, 'US04OpenObserver',
  )

  const hostA = new Y.Doc()
  const hostB = new Y.Doc()
  const observerA = new Y.Doc()
  const observerB = new Y.Doc()
  documents.push(hostA, hostB, observerA, observerB)

  let hostSocketA = await connectDocument(
    roomId, moduleA, hostSession, hostA,
  )
  let hostSocketB = await connectDocument(
    roomId, moduleB, hostSession, hostB,
  )

  await connectDocument(roomId, moduleA, observerSession, observerA)
  await connectDocument(roomId, moduleB, observerSession, observerB)

  hostA.getText('content').insert(0, 'alpha-initial')
  hostB.getText('content').insert(0, 'beta-initial')

  await waitFor(
    () =>
      observerA.getText('content').toString() === 'alpha-initial' &&
      observerB.getText('content').toString() === 'beta-initial',
    'initial synchronization of both modules',
  )

  console.log('PASS: Initial synchronization of both Open-mode modules')

  await closeSocket(hostSocketA)
  await closeSocket(hostSocketB)
  await closeSocket(hostControl)

  hostA.getText('content').insert(
    hostA.getText('content').length, '-offline-A',
  )
  hostB.getText('content').insert(
    hostB.getText('content').length, '-offline-B',
  )

  hostControl = await join(
    roomId, hostSession, 'US04OpenHost', ownerToken,
  )

  hostSocketA = await connectDocument(
    roomId, moduleA, hostSession, hostA,
  )
  hostSocketB = await connectDocument(
    roomId, moduleB, hostSession, hostB,
  )

  try {
    await waitFor(() => {
      return (
        observerA.getText('content').toString() ===
          hostA.getText('content').toString() &&
        observerB.getText('content').toString() ===
          hostB.getText('content').toString() &&
        observerA.getText('content').toString().includes('-offline-A') &&
        observerB.getText('content').toString().includes('-offline-B')
      )
    }, 'both Open-mode modules to recover')
  } catch (error) {
    console.error('DEBUG: Host alpha:', hostA.getText('content').toString())
    console.error('DEBUG: Observer alpha:', observerA.getText('content').toString())
    console.error('DEBUG: Host beta:', hostB.getText('content').toString())
    console.error('DEBUG: Observer beta:', observerB.getText('content').toString())
    throw error
  }

  console.log('PASS: Host rejoined using original session ID')
  console.log('PASS: Alpha module recovered offline changes')
  console.log('PASS: Beta module recovered offline changes')
  console.log('PASS: US-04 Open-mode two-module real-service integration')

} catch (error) {
  console.error('FAIL: US-04 Open-mode integration:', error)
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
