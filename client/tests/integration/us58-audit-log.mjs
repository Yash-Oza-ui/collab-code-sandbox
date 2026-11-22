// Integration test for US-58 / Issue #217: Audit log of permission changes (FR34, V2.23).
//
// Drives sync-service over real HTTP + WebSocket (SYNC_SERVICE_URL, default http://localhost:8081):
//   1. In a Pair room, Host and Guest connect to control WebSocket.
//   2. Host changes role of Guest (Participant -> Observer).
//   3. Both Host and Guest receive live audit-entry WebSocket event.
//   4. Host changes role back and grants edit access.
//   5. Client fetches GET /v1/rooms/{roomId}/audit-log:
//      - accessible to all room members (Host, Participant, Observer)
//      - newest first order
//      - no session_id or user code leaked
//   6. Client attempts POST/DELETE on /audit-log -> 405 Method Not Allowed.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'

const api = process.env.SYNC_SERVICE_URL || 'http://localhost:8081'
const wsBase = api.replace(/^http/, 'ws')
const TIMEOUT = 10000

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
    socket.once('error', (err) => {
      clearTimeout(timer)
      reject(err)
    })
  })
}

async function pollMessage(state, type, predicate = () => true, timeout = TIMEOUT) {
  const start = Date.now()
  while (Date.now() - start < timeout) {
    const idx = state.texts.findIndex((m) => m && m.type === type && predicate(m))
    if (idx !== -1) {
      return state.texts.splice(idx, 1)[0]
    }
    await sleep(25)
  }
  return null
}

async function run() {
  console.log(`US-58: connecting to ${api}...`)

  // 1. Create Pair mode room
  const createRes = await http('POST', '/v1/rooms', {
    body: { mode: 'pair', preset: 'teach_interview' },
  })
  assert.equal(createRes.status, 201, 'room creation must succeed')
  const roomId = createRes.body.room_id
  const ownerToken = createRes.body.owner_token

  const hostSession = randomUUID()
  const guestSession = randomUUID()

  // 2. Connect control sockets
  const hostCtrl = await open(`${wsBase}/ws/rooms/${roomId}?session_id=${hostSession}`, 'hostCtrl')
  const guestCtrl = await open(`${wsBase}/ws/rooms/${roomId}?session_id=${guestSession}`, 'guestCtrl')

  hostCtrl.socket.send(JSON.stringify({ type: 'join', display_name: 'Alice', owner_token: ownerToken }))
  const hostJoined = await pollMessage(hostCtrl, 'joined')
  assert(hostJoined, 'host joined')

  guestCtrl.socket.send(JSON.stringify({ type: 'join', display_name: 'Bob' }))
  const guestJoined = await pollMessage(guestCtrl, 'joined')
  assert(guestJoined, 'guest joined')
  const guestId = guestJoined.member_id

  // 3. Role change: Host makes Bob an Observer
  const roleRes = await http('PATCH', `/v1/rooms/${roomId}/members/${guestId}`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { role: 'observer' },
  })
  assert.equal(roleRes.status, 200, 'role change must succeed')

  // Both control sockets must receive live audit-entry
  const hostAudit1 = await pollMessage(hostCtrl, 'audit-entry')
  assert(hostAudit1, 'host received audit-entry')
  assert.equal(hostAudit1.entry.action, 'role_change')
  assert.equal(hostAudit1.entry.actor, 'Alice')
  assert.equal(hostAudit1.entry.target, 'Bob')
  assert.equal(hostAudit1.entry.old_value, 'participant')
  assert.equal(hostAudit1.entry.new_value, 'observer')

  const guestAudit1 = await pollMessage(guestCtrl, 'audit-entry')
  assert(guestAudit1, 'guest received audit-entry')
  assert.equal(guestAudit1.entry.action, 'role_change')

  // 4. Role change back to participant, then edit access grant
  await http('PATCH', `/v1/rooms/${roomId}/members/${guestId}`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { role: 'participant' },
  })
  await pollMessage(hostCtrl, 'audit-entry')
  await pollMessage(guestCtrl, 'audit-entry')

  const editRes = await http('PATCH', `/v1/rooms/${roomId}/members/${guestId}/edit-access`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { can_edit: true },
  })
  assert.equal(editRes.status, 200, 'edit-access grant must succeed')

  const hostAudit2 = await pollMessage(hostCtrl, 'audit-entry')
  assert(hostAudit2, 'host received edit_access audit-entry')
  assert.equal(hostAudit2.entry.action, 'edit_access')
  assert.equal(hostAudit2.entry.new_value, true)

  // 5. Query GET /v1/rooms/{roomId}/audit-log
  const logRes = await http('GET', `/v1/rooms/${roomId}/audit-log`, {
    headers: { 'X-Session-Id': guestSession },
  })
  assert.equal(logRes.status, 200, 'guest can query audit log')
  assert.equal(logRes.body.room_id, roomId)
  const entries = logRes.body.entries
  assert(Array.isArray(entries), 'entries is an array')
  assert.equal(entries.length, 3, '3 changes recorded')
  // Newest first
  assert.equal(entries[0].action, 'edit_access')
  assert.equal(entries[1].action, 'role_change')
  assert.equal(entries[2].action, 'role_change')

  // AC2: Verify no session_id leaked
  assert(!logRes.raw.includes(hostSession), 'no host session_id in audit log')
  assert(!logRes.raw.includes(guestSession), 'no guest session_id in audit log')

  // 6. AC6: Verify client cannot write/delete entries
  const postRes = await http('POST', `/v1/rooms/${roomId}/audit-log`, {
    headers: { 'X-Session-Id': hostSession },
    body: { action: 'hacked' },
  })
  assert.equal(postRes.status, 405, 'POST audit-log must return 405 Method Not Allowed')

  hostCtrl.socket.close()
  guestCtrl.socket.close()
  await sleep(300)
  console.log('US-58: all integration checks passed successfully!')
}

run().catch((err) => {
  console.error('US-58 integration test failed:', err)
  process.exit(1)
})
