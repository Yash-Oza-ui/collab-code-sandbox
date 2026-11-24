import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

const BASE_URL = (process.env.SYNC_SERVICE_URL || 'http://localhost:8081').replace(/\/+$/, '')
const WS_BASE = BASE_URL.replace(/^http/, 'ws')

async function jsonReq(path, options = {}) {
  const res = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  })
  const text = await res.text()
  let data = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  return { status: res.status, data }
}

function connectControl(roomId, sessionId, displayName, ownerToken) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS_BASE}/ws/rooms/${encodeURIComponent(roomId)}?session_id=${encodeURIComponent(sessionId)}`)
    const messages = []
    const waiters = []

    const timer = setTimeout(() => reject(new Error(`Timeout joining ${displayName}`)), 5000)

    ws.addEventListener('open', () => {
      const joinMsg = { type: 'join', display_name: displayName }
      if (ownerToken) joinMsg.owner_token = ownerToken
      ws.send(JSON.stringify(joinMsg))
    })

    ws.addEventListener('message', (event) => {
      const msg = JSON.parse(String(event.data))
      messages.push(msg)
      for (let i = waiters.length - 1; i >= 0; i--) {
        if (waiters[i].pred(msg)) {
          const w = waiters.splice(i, 1)[0]
          clearTimeout(w.timer)
          w.resolve(msg)
        }
      }
      if (msg.type === 'joined') {
        clearTimeout(timer)
        resolve({
          ws,
          memberId: msg.member_id,
          waitFor(pred, label = 'message') {
            const existing = messages.find(pred)
            if (existing) return Promise.resolve(existing)
            return new Promise((res, rej) => {
              const t = setTimeout(() => rej(new Error(`Timeout waiting for ${label}`)), 5000)
              waiters.push({ pred, resolve: res, timer: t })
            })
          },
        })
      }
    })

    ws.addEventListener('error', () => {
      clearTimeout(timer)
      reject(new Error(`WebSocket error for ${displayName}`))
    })
  })
}

async function run() {
  const createOpen = await jsonReq('/v1/rooms', {
    method: 'POST',
    body: JSON.stringify({ mode: 'open', language: 'python' }),
  })
  assert.equal(createOpen.status, 201)
  const roomId = createOpen.data.room_id
  const ownerToken = createOpen.data.owner_token

  const hostSession = randomUUID()
  const writerSession = randomUUID()
  const readerSession = randomUUID()

  const hostCtrl = await connectControl(roomId, hostSession, 'Host', ownerToken)
  const writerCtrl = await connectControl(roomId, writerSession, 'Writer')
  const readerCtrl = await connectControl(roomId, readerSession, 'Reader')

  // Host creates solver.py (Host owns it)
  // Host creates solver.py (Host owns it) and approves it
  const createSolver = await jsonReq(`/v1/rooms/${roomId}/modules`, {
    method: 'POST',
    headers: { 'X-Session-Id': hostSession },
    body: JSON.stringify({ name: 'solver.py' }),
  })
  assert.equal(createSolver.status, 202)
  const solverId = createSolver.data.module_id

  const approveSolver = await jsonReq(`/v1/rooms/${roomId}/modules/${solverId}/approve`, {
    method: 'POST',
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: JSON.stringify({}),
  })
  assert.equal(approveSolver.status, 200)

  // Writer requests helper.py and Host approves with Writer as owner (so Host does NOT have write access)
  const reqHelper = await jsonReq(`/v1/rooms/${roomId}/modules`, {
    method: 'POST',
    headers: { 'X-Session-Id': writerSession },
    body: JSON.stringify({ name: 'helper.py' }),
  })
  assert.equal(reqHelper.status, 202)
  const helperId = reqHelper.data.module_id

  const approveHelper = await jsonReq(`/v1/rooms/${roomId}/modules/${helperId}/approve`, {
    method: 'POST',
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: JSON.stringify({}),
  })
  assert.equal(approveHelper.status, 200)

  // Grant Writer write access on solver.py
  const grantWriter = await jsonReq(`/v1/rooms/${roomId}/modules/${solverId}/writers`, {
    method: 'POST',
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: JSON.stringify({ member_id: writerCtrl.memberId }),
  })
  assert.equal(grantWriter.status, 200)

  // Host sends a Yjs sync update frame [0, 2, 1] on solver.py -> dirty becomes true in both sockets
  await new Promise((resolve, reject) => {
    const docWs = new WebSocket(
      `${WS_BASE}/ws/rooms/${encodeURIComponent(roomId)}/docs/${encodeURIComponent(solverId)}?session_id=${encodeURIComponent(hostSession)}`,
    )
    docWs.addEventListener('open', () => {
      docWs.send(new Uint8Array([0, 2, 1]))
      setTimeout(() => {
        docWs.close()
        resolve()
      }, 150)
    })
    docWs.addEventListener('error', () => reject(new Error('Doc WS error')))
  })

  await writerCtrl.waitFor((m) => m.type === 'module-updated' && m.module_id === solverId && m.dirty === true, 'dirty=true')

  // Reader (no write access) and Host (no write access on helper.py) are both refused with 403 NOT_MODULE_WRITER
  const readerSave = await jsonReq(`/v1/rooms/${roomId}/modules/${solverId}/save`, {
    method: 'POST',
    headers: { 'X-Session-Id': readerSession },
    body: JSON.stringify({ text: 'bad' }),
  })
  assert.equal(readerSave.status, 403)
  assert.equal(readerSave.data.error.code, 'NOT_MODULE_WRITER')

  const hostSaveHelper = await jsonReq(`/v1/rooms/${roomId}/modules/${helperId}/save`, {
    method: 'POST',
    headers: { 'X-Session-Id': hostSession },
    body: JSON.stringify({ text: 'bad' }),
  })
  assert.equal(hostSaveHelper.status, 403)
  assert.equal(hostSaveHelper.data.error.code, 'NOT_MODULE_WRITER')

  // Writer saves solver.py -> version=1, dirty=false, saved_by, saved_at, and module-updated pushed
  const writerSave = await jsonReq(`/v1/rooms/${roomId}/modules/${solverId}/save`, {
    method: 'POST',
    headers: { 'X-Session-Id': writerSession },
    body: JSON.stringify({ text: 'print("v1")\n' }),
  })
  assert.equal(writerSave.status, 200)
  assert.equal(writerSave.data.version, 1)
  assert.equal(writerSave.data.dirty, false)
  assert.equal(writerSave.data.saved_by.member_id, writerCtrl.memberId)
  assert.ok(writerSave.data.saved_at)

  await readerCtrl.waitFor(
    (m) => m.type === 'module-updated' && m.module_id === solverId && m.version === 1 && m.dirty === false,
    'dirty=false after save',
  )

  // Unknown module -> 404 MODULE_NOT_FOUND
  const missingSave = await jsonReq(`/v1/rooms/${roomId}/modules/mod_unknown_123456/save`, {
    method: 'POST',
    headers: { 'X-Session-Id': hostSession },
    body: JSON.stringify({ text: 'x' }),
  })
  assert.equal(missingSave.status, 404)
  assert.equal(missingSave.data.error.code, 'MODULE_NOT_FOUND')

  // Pair mode -> 422 INVALID_REQUEST
  const createPair = await jsonReq('/v1/rooms', {
    method: 'POST',
    body: JSON.stringify({ mode: 'pair', preset: 'debug_together' }),
  })
  assert.equal(createPair.status, 201)
  const pairHost = randomUUID()
  const pairCtrl = await connectControl(createPair.data.room_id, pairHost, 'Host', createPair.data.owner_token)
  const pairSave = await jsonReq(`/v1/rooms/${createPair.data.room_id}/modules/mod_any/save`, {
    method: 'POST',
    headers: { 'X-Session-Id': pairHost },
    body: JSON.stringify({ text: 'x' }),
  })
  assert.equal(pairSave.status, 422)
  assert.equal(pairSave.data.error.code, 'INVALID_REQUEST')

  hostCtrl.ws.close()
  writerCtrl.ws.close()
  readerCtrl.ws.close()
  pairCtrl.ws.close()
  console.log('US-45 integration test passed!')
  process.exit(0)
}

run().catch((err) => {
  console.error(err)
  process.exit(1)
})
