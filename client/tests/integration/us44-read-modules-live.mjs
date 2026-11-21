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
const docs = []

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
      } catch (err) {
        clearInterval(timer)
        reject(err)
      }
    }, 25)
  })
}

async function request(path, method = 'GET', body, headers = {}) {
  const res = await fetch(`${api}${path}`, {
    method,
    headers: {
      ...headers,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) {
    throw new Error(`${method} ${path}: HTTP ${res.status}: ${JSON.stringify(data)}`)
  }
  return { status: res.status, data }
}

function openSocket(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { handshakeTimeout: timeoutMs })
    const timer = setTimeout(() => {
      ws.terminate()
      reject(new Error(`WebSocket timeout: ${url}`))
    }, timeoutMs)
    ws.once('open', () => {
      clearTimeout(timer)
      sockets.push(ws)
      resolve(ws)
    })
    ws.once('error', (err) => {
      clearTimeout(timer)
      reject(err)
    })
  })
}

async function joinControl(roomId, sessionId, name, ownerToken) {
  const ws = await openSocket(`${wsBase}/ws/rooms/${encodeURIComponent(roomId)}?session_id=${encodeURIComponent(sessionId)}`)
  const events = []
  const joined = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Join timeout: ${name}`)), timeoutMs)
    ws.on('message', (data, binary) => {
      if (binary) return
      const msg = JSON.parse(data.toString())
      events.push(msg)
      if (msg.type === 'joined') {
        clearTimeout(timer)
        resolve(msg)
      } else if (msg.type === 'join-refused') {
        clearTimeout(timer)
        reject(new Error(`Join refused: ${JSON.stringify(msg)}`))
      }
    })
  })
  ws.send(JSON.stringify({
    type: 'join',
    display_name: name,
    ...(ownerToken ? { owner_token: ownerToken } : {}),
  }))
  const joinedMsg = await joined
  return { ws, events, joinedMsg }
}

async function connectDoc(roomId, moduleId, sessionId, doc) {
  const ws = await openSocket(
    `${wsBase}/ws/rooms/${encodeURIComponent(roomId)}/docs/${encodeURIComponent(moduleId)}?session_id=${encodeURIComponent(sessionId)}`,
  )
  const send = (writer) => {
    if (ws.readyState !== WebSocket.OPEN) return
    const enc = encoding.createEncoder()
    encoding.writeVarUint(enc, 0)
    writer(enc)
    ws.send(encoding.toUint8Array(enc))
  }
  const onUpdate = (update, origin) => {
    if (origin !== ws) send((enc) => sync.writeUpdate(enc, update))
  }
  doc.on('update', onUpdate)
  ws.on('message', (data, binary) => {
    if (!binary) return
    const dec = decoding.createDecoder(new Uint8Array(data))
    if (decoding.readVarUint(dec) !== 0) return
    const enc = encoding.createEncoder()
    encoding.writeVarUint(enc, 0)
    sync.readSyncMessage(dec, enc, doc, ws)
    const reply = encoding.toUint8Array(enc)
    if (reply.length > 1 && ws.readyState === WebSocket.OPEN) ws.send(reply)
  })
  ws.on('close', () => doc.off('update', onUpdate))
  send((enc) => sync.writeSyncStep1(enc, doc))
  send((enc) => sync.writeSyncStep2(enc, doc))
  return ws
}

async function createAndApproveModule(roomId, sessionId, ownerToken, name) {
  const created = await request(`/v1/rooms/${encodeURIComponent(roomId)}/modules`, 'POST', { name }, { 'X-Session-Id': sessionId })
  assert.equal(created.status, 202)
  const moduleId = created.data.module_id
  const approved = await request(
    `/v1/rooms/${encodeURIComponent(roomId)}/modules/${encodeURIComponent(moduleId)}/approve`,
    'POST',
    undefined,
    { 'X-Session-Id': sessionId, 'X-Owner-Token': ownerToken },
  )
  assert.equal(approved.status, 200)
  return moduleId
}

try {
  const created = await request('/v1/rooms', 'POST', { mode: 'open', language: 'python' })
  assert.equal(created.status, 201)
  const { room_id: roomId, owner_token: ownerToken } = created.data

  const hostSession = randomUUID()
  const obsSession = randomUUID()
  await joinControl(roomId, hostSession, 'US44Host', ownerToken)

  const modA = await createAndApproveModule(roomId, hostSession, ownerToken, 'main.py')
  const modB = await createAndApproveModule(roomId, hostSession, ownerToken, 'utils.py')

  const obs = await joinControl(roomId, obsSession, 'US44Observer')
  await request(
    `/v1/rooms/${encodeURIComponent(roomId)}/members/${encodeURIComponent(obs.joinedMsg.member_id)}`,
    'PATCH',
    { role: 'observer' },
    { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
  )

  const hostDocA = new Y.Doc()
  const hostDocB = new Y.Doc()
  const obsDocA = new Y.Doc()
  const obsDocB = new Y.Doc()
  docs.push(hostDocA, hostDocB, obsDocA, obsDocB)

  await connectDoc(roomId, modA, hostSession, hostDocA)
  await connectDoc(roomId, modB, hostSession, hostDocB)
  await connectDoc(roomId, modA, obsSession, obsDocA)
  const obsSockB = await connectDoc(roomId, modB, obsSession, obsDocB)

  hostDocA.getText('content').insert(0, 'print("alpha")')
  hostDocB.getText('content').insert(0, 'print("beta")')

  await waitFor(
    () =>
      obsDocA.getText('content').toString() === 'print("alpha")' &&
      obsDocB.getText('content').toString() === 'print("beta")',
    'Observer sees live edits across both modules',
  )

  obsDocA.getText('content').insert(0, 'forbidden ')
  await waitFor(
    () => obs.events.some((e) => e.type === 'edit-rejected' && e.doc_id === modA),
    'Observer write rejected',
  )

  const modC = await createAndApproveModule(roomId, hostSession, ownerToken, 'helper.py')
  await waitFor(
    () => obs.events.some((e) => e.type === 'module-updated' && e.module_id === modC),
    'Observer receives module-updated for new module',
  )

  obsSockB.close()
  await new Promise((r) => setTimeout(r, 100))
  hostDocB.getText('content').insert(hostDocB.getText('content').length, ' +resync')

  const obsDocBReconnected = new Y.Doc()
  docs.push(obsDocBReconnected)
  await connectDoc(roomId, modB, obsSession, obsDocBReconnected)
  await waitFor(
    () => obsDocBReconnected.getText('content').toString() === 'print("beta") +resync',
    'Observer resyncs modB after reconnect',
  )

  console.log('PASS: US-44 live read-only modules, module-updated push, and reconnect resync')
} finally {
  for (const ws of sockets.reverse()) {
    try { ws.close() } catch {}
  }
  for (const d of docs) d.destroy()
}