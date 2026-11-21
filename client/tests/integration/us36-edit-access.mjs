// Integration test for US-36 / Issue #179: Give and take back edit access in Pair mode (FR19.1, V2.21).
//
// Drives sync-service over real HTTP + WebSocket (SYNC_SERVICE_URL, default http://localhost:8081):
//   1. In a Pair room, participant initially has no edit access; update is dropped with edit-rejected.
//   2. Host grants edit access via PATCH /v1/rooms/{roomId}/members/{memberId}/edit-access:
//      updated roster is broadcast, and guest's very next update goes through.
//   3. Host withdraws edit access: updated roster is broadcast, and guest's next update is dropped
//      with edit-rejected (reason no_edit_access).
//   4. Non-Host caller gets 403 NOT_OWNER.
//   5. Open mode room refuses edit access call with 422 INVALID_REQUEST.
//   6. Observer target is refused with 422 INVALID_REQUEST.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'

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

const connectControl = (roomId, session) => open(`${wsBase}/ws/rooms/${roomId}?session_id=${session}`, `control ${roomId}`)
const connectDoc = (roomId, session) => open(`${wsBase}/ws/rooms/${roomId}/docs/main?session_id=${session}`, `doc ${roomId}`)

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
const sameBytes = (a, b) => a.length === b.length && a.every((v, i) => v === b[i])

async function main() {
  console.log('US-36 integration: start')

  // Create Pair room (teach_interview preset gives participants can_edit=false by default)
  const pairRoomRes = await http('POST', '/v1/rooms', { body: { mode: 'pair', preset: 'teach_interview' } })
  assert.equal(pairRoomRes.status, 201, 'Pair room created')
  const roomId = pairRoomRes.body.room_id
  const ownerToken = pairRoomRes.body.owner_token

  const hostSession = randomUUID()
  const guestSession = randomUUID()

  const hostCtl = await connectControl(roomId, hostSession)
  sendJoin(hostCtl, 'Alice', ownerToken)
  await waitFor(firstOfType(hostCtl, 'joined'), 'host joined')

  const guestCtl = await connectControl(roomId, guestSession)
  sendJoin(guestCtl, 'Bob', null)
  const guestJoined = await waitFor(firstOfType(guestCtl, 'joined'), 'guest joined')
  const guestId = guestJoined.member_id

  const hostDoc = await connectDoc(roomId, hostSession)
  const guestDoc = await connectDoc(roomId, guestSession)

  // 1. Before grant: update from guest is dropped with edit-rejected (no_edit_access)
  const frame1 = Uint8Array.from([0x00, 0x02, 0x11, 0x22])
  guestDoc.socket.send(frame1)
  const rej1 = await waitFor(() => rejections(guestCtl)[0], 'edit-rejected before grant')
  assert.equal(rej1.reason, 'no_edit_access')
  assert.equal(hostDoc.binaries.length, 0, 'frame was dropped')
  console.log('1. before grant: frame dropped, edit-rejected (no_edit_access)')

  // 2. Host grants edit access via REST
  const grantRes = await http('PATCH', `/v1/rooms/${roomId}/members/${guestId}/edit-access`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { can_edit: true },
  })
  assert.equal(grantRes.status, 200, 'Host grants edit access')
  assert.equal(grantRes.body.permissions.can_edit, true)

  // Wait for updated roster on guest control socket
  await waitFor(
    () => guestCtl.texts.find((m) => m.type === 'roster' && m.members?.some((x) => x.member_id === guestId && x.permissions?.can_edit === true)),
    'roster with can_edit=true',
  )

  // Guest's next change goes through to host
  const frame2 = Uint8Array.from([0x00, 0x02, 0x33, 0x44])
  guestDoc.socket.send(frame2)
  await waitFor(() => hostDoc.binaries.find((b) => sameBytes(b, frame2)), 'frame reached host')
  console.log('2. after grant: frame forwarded successfully')

  // 3. Host withdraws edit access
  const withdrawRes = await http('PATCH', `/v1/rooms/${roomId}/members/${guestId}/edit-access`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { can_edit: false },
  })
  assert.equal(withdrawRes.status, 200, 'Host withdraws edit access')
  assert.equal(withdrawRes.body.permissions.can_edit, false)

  await waitFor(
    () => guestCtl.texts.find((m) => m.type === 'roster' && m.members?.some((x) => x.member_id === guestId && x.permissions?.can_edit === false)),
    'roster with can_edit=false',
  )

  // Guest's next change is dropped with edit-rejected
  const countBefore = hostDoc.binaries.length
  const rejectionsBefore = rejections(guestCtl).length
  const frame3 = Uint8Array.from([0x00, 0x02, 0x55, 0x66])
  guestDoc.socket.send(frame3)
  const rej2 = await waitFor(() => rejections(guestCtl)[rejectionsBefore], 'edit-rejected after withdraw')
  assert.equal(rej2.reason, 'no_edit_access')
  await sleep(QUIET_MS)
  assert.equal(hostDoc.binaries.length, countBefore, 'frame was dropped after withdrawal')
  console.log('3. after withdrawal: frame dropped, edit-rejected (no_edit_access)')

  // 4. Non-Host gets 403 NOT_OWNER
  const nonHostRes = await http('PATCH', `/v1/rooms/${roomId}/members/${guestId}/edit-access`, {
    headers: { 'X-Session-Id': guestSession },
    body: { can_edit: true },
  })
  assert.equal(nonHostRes.status, 403, 'non-Host gets 403')
  assert.equal(nonHostRes.body.error.code, 'NOT_OWNER')
  console.log('4. non-Host gets 403 NOT_OWNER')

  // 5. Open mode room refuses edit access call with 422 INVALID_REQUEST
  const openRoomRes = await http('POST', '/v1/rooms', { body: { mode: 'open', language: 'python' } })
  const openRoomId = openRoomRes.body.room_id
  const openToken = openRoomRes.body.owner_token
  const openHostSession = randomUUID()
  const openHostCtl = await connectControl(openRoomId, openHostSession)
  sendJoin(openHostCtl, 'Alice', openToken)
  await waitFor(firstOfType(openHostCtl, 'joined'), 'open host joined')

  const openGuestSession = randomUUID()
  const openGuestCtl = await connectControl(openRoomId, openGuestSession)
  sendJoin(openGuestCtl, 'Bob', null)
  const openGuestId = (await waitFor(firstOfType(openGuestCtl, 'joined'), 'open guest joined')).member_id

  const openCallRes = await http('PATCH', `/v1/rooms/${openRoomId}/members/${openGuestId}/edit-access`, {
    headers: { 'X-Session-Id': openHostSession, 'X-Owner-Token': openToken },
    body: { can_edit: true },
  })
  assert.equal(openCallRes.status, 422, 'Open mode room refuses call with 422')
  assert.equal(openCallRes.body.error.code, 'INVALID_REQUEST')
  assert.match(openCallRes.body.error.message, /Edit access controls apply only in Pair mode/)
  console.log('5. Open mode room refuses with 422 INVALID_REQUEST')

  // 6. Observer target is refused with 422 INVALID_REQUEST
  await http('PATCH', `/v1/rooms/${roomId}/members/${guestId}`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { role: 'observer' },
  })
  const obsCallRes = await http('PATCH', `/v1/rooms/${roomId}/members/${guestId}/edit-access`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { can_edit: true },
  })
  assert.equal(obsCallRes.status, 422, 'Observer target refused with 422')
  assert.equal(obsCallRes.body.error.code, 'INVALID_REQUEST')
  assert.match(obsCallRes.body.error.message, /Observers cannot have edit access/)
  console.log('6. Observer target refused with 422 INVALID_REQUEST')

  // Close sockets
  for (const s of [hostCtl, guestCtl, hostDoc, guestDoc, openHostCtl, openGuestCtl]) {
    try {
      s.socket.close()
    } catch {
      /* ignore */
    }
  }
  await sleep(200)
  console.log('US-36 integration: PASS')
}

main()
  .then(() => process.exit(0))
  .catch((e) => {
    console.error('US-36 integration FAILED:', e.message)
    process.exit(1)
  })
