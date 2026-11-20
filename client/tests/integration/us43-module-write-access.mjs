// Integration test for US-43 / Issue #177: Control write access to a module (FR22.1, FR22.2, NFR13, CR13).
//
// Drives a live sync-service over real HTTP + WebSocket (SYNC_SERVICE_URL, default
// http://localhost:8081) and verifies:
//   1. In Open mode, an update from a member without write access is dropped and the sender
//      gets edit-rejected (doc_id = moduleId, reason = not_module_writer).
//   2. The module owner or Host grants write access via POST .../writers; room receives
//      module-updated with the member in writers.
//   3. The member's next edit is accepted immediately and relayed to others.
//   4. Revoking write access via DELETE .../writers/{memberId} takes effect immediately:
//      subsequent edit is dropped with edit-rejected (not_module_writer).
//   5. Module owner cannot lose write access: DELETE .../writers/{ownerId} fails with 422.
//   6. An Observer granted write access still cannot edit (edit-rejected reason = observer).
//   7. Non-owner / non-Host cannot change write access (fails with 403 NOT_MODULE_OWNER).
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'
import * as Y from 'yjs'
import * as syncProtocol from 'y-protocols/sync'
import * as encoding from 'lib0/encoding'

const api = process.env.SYNC_SERVICE_URL || 'http://localhost:8081'
const wsBase = api.replace(/^http/, 'ws')
const TIMEOUT = 10000
const QUIET_MS = 600

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function http(method, path, { headers = {}, body } = {}) {
  const res = await fetch(api + path, {
    method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  })
  const raw = await res.text()
  let json = null
  try {
    json = raw ? JSON.parse(raw) : null
  } catch {
    json = null
  }
  return { status: res.status, body: json, raw }
}

function open(url, label) {
  const socket = new WebSocket(url)
  const state = { socket, texts: [], binaries: [], closeCode: null }
  socket.on('message', (data, isBinary) => {
    if (isBinary) {
      state.binaries.push(new Uint8Array(data))
      return
    }
    try {
      state.texts.push(JSON.parse(data.toString()))
    } catch {
      /* ignore non-JSON */
    }
  })
  socket.on('close', (code) => {
    state.closeCode = code
  })
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`connect timeout: ${label}`)), TIMEOUT)
    socket.once('open', () => {
      clearTimeout(timer)
      resolve(state)
    })
    socket.once('error', (e) => {
      clearTimeout(timer)
      reject(e)
    })
  })
}

const connectControl = (roomId, session) =>
  open(`${wsBase}/ws/rooms/${roomId}?session_id=${session}`, `control ${roomId}`)
const connectDoc = (roomId, moduleId, session) =>
  open(`${wsBase}/ws/rooms/${roomId}/docs/${moduleId}?session_id=${session}`, `doc ${roomId}/${moduleId}`)

function sendJoin(state, name, ownerToken) {
  state.socket.send(
    JSON.stringify({ type: 'join', display_name: name, ...(ownerToken ? { owner_token: ownerToken } : {}) }),
  )
}

async function waitFor(predicate, description) {
  const start = Date.now()
  while (Date.now() - start < TIMEOUT) {
    const value = predicate()
    if (value) return value
    await sleep(25)
  }
  throw new Error(`timeout: ${description}`)
}

const firstOfType = (state, type) => () => state.texts.find((m) => m.type === type)
const rejections = (state) => state.texts.filter((m) => m.type === 'edit-rejected')
const moduleUpdates = (state) => state.texts.filter((m) => m.type === 'module-updated')
const sameBytes = (a, b) => a.length === b.length && a.every((v, i) => v === b[i])

async function expectNothing(state, description) {
  const before = state.binaries.length
  await sleep(QUIET_MS)
  assert.equal(state.binaries.length, before, description)
}

// Build a canonical Yjs update frame
const testDoc = new Y.Doc()
testDoc.getText('monaco').insert(0, '# US-43 module write test\n')
const updatePayload = Y.encodeStateAsUpdate(testDoc)
const enc = encoding.createEncoder()
encoding.writeVarUint(enc, 0) // sync
syncProtocol.writeUpdate(enc, updatePayload)
const canonicalUpdate = encoding.toUint8Array(enc)

async function main() {
  console.log('Running US-43 module write access integration tests...')

  // 1. Create an Open mode room (language python)
  const createRes = await http('POST', '/v1/rooms', {
    body: { mode: 'open', language: 'python' },
  })
  assert.equal(createRes.status, 201, 'open room created')
  const roomId = createRes.body.room_id
  const ownerToken = createRes.body.owner_token

  const hostSession = randomUUID()
  const guestSession = randomUUID()
  const strangerSession = randomUUID()

  // Host joins
  const hostCtl = await connectControl(roomId, hostSession)
  sendJoin(hostCtl, 'Alice Host', ownerToken)
  const hostJoined = await waitFor(firstOfType(hostCtl, 'joined'), 'host joined')
  const hostMemberId = hostJoined.member_id

  // Guest joins
  const guestCtl = await connectControl(roomId, guestSession)
  sendJoin(guestCtl, 'Bob Guest', null)
  const guestJoined = await waitFor(firstOfType(guestCtl, 'joined'), 'guest joined')
  const guestMemberId = guestJoined.member_id

  // Stranger joins
  const strangerCtl = await connectControl(roomId, strangerSession)
  sendJoin(strangerCtl, 'Charlie Stranger', null)
  const strangerJoined = await waitFor(firstOfType(strangerCtl, 'joined'), 'stranger joined')
  const strangerMemberId = strangerJoined.member_id

  // Create and approve a module (US-39 / US-40)
  const createModRes = await http('POST', `/v1/rooms/${roomId}/modules`, {
    headers: { 'X-Session-Id': hostSession },
    body: { name: 'solver.py' },
  })
  assert.equal(createModRes.status, 202, 'module requested')
  const moduleId = createModRes.body.module_id
  assert.ok(moduleId, 'moduleId exists')

  const approveRes = await http('POST', `/v1/rooms/${roomId}/modules/${moduleId}/approve`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: {},
  })
  assert.equal(approveRes.status, 200, 'module approved')
  const entryModule = approveRes.body

  // Connect doc sockets for host and guest
  const hostDoc = await connectDoc(roomId, moduleId, hostSession)
  const guestDoc = await connectDoc(roomId, moduleId, guestSession)

  // Test 1: Guest without write access sends Yjs update -> dropped, receives edit-rejected (not_module_writer)
  guestDoc.socket.send(canonicalUpdate)
  const rej1 = await waitFor(() => rejections(guestCtl)[0], 'guest edit rejected')
  assert.equal(rej1.doc_id, moduleId, 'rejection doc_id must match moduleId')
  assert.equal(rej1.reason, 'not_module_writer', 'rejection reason must be not_module_writer')
  await expectNothing(hostDoc, 'host should not receive rejected update')
  console.log('✓ 1. Edit without write access dropped with reason: not_module_writer')

  // Test 2: Stranger (neither owner nor Host) tries to grant write access -> 403 NOT_MODULE_OWNER
  const unauthGrant = await http('POST', `/v1/rooms/${roomId}/modules/${moduleId}/writers`, {
    headers: { 'X-Session-Id': strangerSession },
    body: { member_id: guestMemberId },
  })
  assert.equal(unauthGrant.status, 403, 'unauthorized grant refused with 403')
  assert.equal(unauthGrant.body?.error?.code, 'NOT_MODULE_OWNER', 'error code NOT_MODULE_OWNER')
  console.log('✓ 2. Non-owner / non-Host grant refused with 403 NOT_MODULE_OWNER')

  // Test 3: Host grants write access to Bob Guest
  const grantRes = await http('POST', `/v1/rooms/${roomId}/modules/${moduleId}/writers`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { member_id: guestMemberId },
  })
  assert.equal(grantRes.status, 200, 'host grants write access')
  assert.ok(grantRes.body.writers.includes(guestMemberId), 'writers list includes guest')

  // Wait for module-updated broadcast on guest control socket
  const updateMsg = await waitFor(
    () => moduleUpdates(guestCtl).find((m) => m.module_id === moduleId && m.writers.includes(guestMemberId)),
    'module-updated broadcast with new writer',
  )
  assert.ok(updateMsg, 'module-updated message received')
  console.log('✓ 3. Host granted write access; module-updated broadcast received')

  // Test 4: Bob Guest sends edit -> accepted and relayed to hostDoc!
  const guestDocUpdate = Uint8Array.from([0x00, 0x02, 0x42, 0x4f, 0x42])
  guestDoc.socket.send(guestDocUpdate)
  await waitFor(
    () => hostDoc.binaries.find((b) => sameBytes(b, guestDocUpdate)),
    'guest update relayed to host',
  )
  console.log('✓ 4. Granted writer edit successfully accepted and relayed')

  // Test 5: Revoke write access from Bob Guest
  const revokeRes = await http('DELETE', `/v1/rooms/${roomId}/modules/${moduleId}/writers/${guestMemberId}`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
  })
  assert.equal(revokeRes.status, 200, 'host revokes write access')
  assert.ok(!revokeRes.body.writers.includes(guestMemberId), 'writers list no longer includes guest')

  // Wait for module-updated broadcast
  await waitFor(
    () => moduleUpdates(guestCtl).find((m) => m.module_id === moduleId && !m.writers.includes(guestMemberId)),
    'module-updated broadcast after revoke',
  )

  // Subsequent edit by Bob is rejected
  const guestRejCount = rejections(guestCtl).length
  guestDoc.socket.send(canonicalUpdate)
  await waitFor(() => rejections(guestCtl).length > guestRejCount, 'guest edit rejected after revoke')
  const lastRej = rejections(guestCtl)[rejections(guestCtl).length - 1]
  assert.equal(lastRej.reason, 'not_module_writer', 'reason is not_module_writer')
  console.log('✓ 5. Revoked writer edit dropped immediately without reload')

  // Test 6: Owner cannot lose write access -> 422 INVALID_REQUEST
  const revokeOwnerRes = await http(
    'DELETE',
    `/v1/rooms/${roomId}/modules/${moduleId}/writers/${entryModule.owner_member_id}`,
    {
      headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    },
  )
  assert.equal(revokeOwnerRes.status, 422, 'revoking owner returns 422')
  assert.equal(revokeOwnerRes.body?.error?.code, 'INVALID_REQUEST')
  console.log('✓ 6. Revoking owner write access refused with 422 INVALID_REQUEST')

  // Test 7: Observer role cannot edit even if in writers
  // Make Charlie Stranger an Observer
  const makeObs = await http('PATCH', `/v1/rooms/${roomId}/members/${strangerMemberId}`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { role: 'observer' },
  })
  assert.equal(makeObs.status, 200, 'charlie made observer')

  // Grant Charlie write access
  const grantObs = await http('POST', `/v1/rooms/${roomId}/modules/${moduleId}/writers`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { member_id: strangerMemberId },
  })
  assert.equal(grantObs.status, 200, 'charlie added to writers')

  // Charlie tries to edit
  const strangerDoc = await connectDoc(roomId, moduleId, strangerSession)
  strangerDoc.socket.send(canonicalUpdate)
  const obsRej = await waitFor(() => rejections(strangerCtl)[0], 'observer edit rejected')
  assert.equal(obsRej.reason, 'observer', 'observer rejection reason must be observer')
  console.log('✓ 7. Observer in writers still cannot edit (reason: observer)')

  // Clean up sockets
  for (const s of [hostCtl, guestCtl, strangerCtl, hostDoc, guestDoc, strangerDoc]) {
    try {
      s.socket.close()
    } catch {
      /* ignore */
    }
  }
  await sleep(200)
  console.log('All US-43 integration tests PASSED!')
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error('US-43 integration test FAILED:', err)
    process.exit(1)
  })
