// Integration test for US-28 / Issue #133: a removed user stays out (FR9.6, CR04, V2.18).
//
// Drives a live sync-service over real HTTP + WebSocket (SYNC_SERVICE_URL, default
// http://localhost:8081). Covers: remove (socket closed 4403), the blocked list
// (never the session_id), a refused rejoin (USER_BLOCKED), re-admit (fresh
// member_id), and regenerate-link (the old id returns 404).
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

function connect(roomId, sessionId) {
  const url = `${wsBase}/ws/rooms/${roomId}?session_id=${sessionId}`
  const socket = new WebSocket(url)
  const state = { socket, messages: [], closeCode: null }
  socket.on('message', (data, isBinary) => {
    if (isBinary) return
    try {
      state.messages.push(JSON.parse(data.toString()))
    } catch {
      /* ignore non-JSON */
    }
  })
  socket.on('close', (code) => {
    state.closeCode = code
  })
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`connect timeout: ${roomId}`)), TIMEOUT)
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

const firstOfType = (state, type) => () => state.messages.find((m) => m.type === type)

async function main() {
  // 1. Create a Pair / teach_interview room.
  const created = await http('POST', '/v1/rooms', { body: { mode: 'pair', preset: 'teach_interview' } })
  assert.equal(created.status, 201, 'room created')
  const roomId = created.body.room_id
  const ownerToken = created.body.owner_token
  const hostSession = randomUUID()
  const guestSession = randomUUID()

  // 2. Host joins (owner token), 3. guest joins.
  const host = await connect(roomId, hostSession)
  sendJoin(host, 'Host', ownerToken)
  await waitFor(firstOfType(host, 'joined'), 'host joined')

  const guest = await connect(roomId, guestSession)
  sendJoin(guest, 'Lin', null)
  const guestJoined = await waitFor(firstOfType(guest, 'joined'), 'guest joined')
  const guestMemberId = guestJoined.member_id
  console.log(`joined: host + guest (${guestMemberId})`)

  // 4. Host removes the guest.
  const removed = await http('POST', `/v1/rooms/${roomId}/members/${guestMemberId}/remove`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
  })
  assert.equal(removed.status, 200, 'remove returns 200')
  assert.equal(removed.body.status, 'removed', 'remove status')

  // 5. The connected guest's socket is closed with 4403.
  await waitFor(() => guest.closeCode !== null, 'guest socket closed')
  assert.equal(guest.closeCode, 4403, 'guest socket closed with 4403')
  console.log('remove: guest socket closed 4403')

  // 6. The blocked list shows the guest, never the private session_id.
  const blocked = await http('GET', `/v1/rooms/${roomId}/members/blocked`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
  })
  assert.equal(blocked.status, 200, 'blocked list 200')
  assert.equal(blocked.body.blocked.length, 1, 'one blocked member')
  assert.equal(blocked.body.blocked[0].member_id, guestMemberId, 'blocked member_id')
  assert.equal(blocked.body.blocked[0].display_name, 'Lin', 'blocked display_name')
  assert.ok(blocked.body.blocked[0].removed_at, 'blocked removed_at present')
  assert.ok(!blocked.raw.includes(guestSession), 'blocked list never leaks session_id')
  console.log('blocked list: guest present, no session_id')

  // 7. A new connection from the same session is refused at join with USER_BLOCKED.
  const again = await connect(roomId, guestSession)
  sendJoin(again, 'Lin', null)
  const refused = await waitFor(firstOfType(again, 'join-refused'), 'rejoin refused')
  assert.equal(refused.reason, 'USER_BLOCKED', 'rejoin refused USER_BLOCKED')
  console.log('rejoin: refused USER_BLOCKED')

  // 8. Re-admit lifts the block.
  const readmit = await http('POST', `/v1/rooms/${roomId}/members/${guestMemberId}/readmit`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
  })
  assert.equal(readmit.status, 200, 're-admit 200')
  assert.equal(readmit.body.status, 'readmitted', 're-admit status')

  // 9. The session can join again, with a fresh member_id.
  const back = await connect(roomId, guestSession)
  sendJoin(back, 'Lin', null)
  const backJoined = await waitFor(firstOfType(back, 'joined'), 'guest rejoined after re-admit')
  assert.notEqual(backJoined.member_id, guestMemberId, 'fresh member_id after re-admit')
  console.log('re-admit: guest joins again with a fresh member_id')

  // 10. Regenerate the link -> a new room id.
  const regen = await http('POST', `/v1/rooms/${roomId}/regenerate-link`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
  })
  assert.equal(regen.status, 200, 'regenerate 200')
  const newRoomId = regen.body.room_id
  assert.ok(newRoomId && newRoomId !== roomId, 'regenerate returns a new room id')

  // 11. The old id no longer resolves on any interface.
  const oldOwner = await http('GET', `/v1/rooms/${roomId}/owner`, {
    headers: { 'X-Owner-Token': ownerToken },
  })
  assert.equal(oldOwner.status, 404, 'old id -> 404')
  assert.ok(oldOwner.raw.includes('ROOM_NOT_FOUND'), 'old id -> ROOM_NOT_FOUND')
  console.log('regenerate: old id retired (404 ROOM_NOT_FOUND)')

  for (const s of [host, again, back]) {
    try {
      s.socket.close()
    } catch {
      /* ignore */
    }
  }
  console.log('US-28 integration: PASS')
}

main()
  .then(() => process.exit(0))
  .catch((e) => {
    console.error('US-28 integration FAILED:', e.message)
    process.exit(1)
  })
