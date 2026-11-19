// Integration test for US-54 / Issue #132: transfer the Host role (FR31.1, V2.19).
//
// Drives a live sync-service over real HTTP + WebSocket (SYNC_SERVICE_URL, default
// http://localhost:8081). Covers: make-host (200, no token in the HTTP response),
// the host-granted control message (new token to the new Host), the roster swap,
// the old owner token being retired (403 NOT_OWNER), and the new token working.
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
  // Create a Pair / teach_interview room and join a host + two participants.
  const created = await http('POST', '/v1/rooms', { body: { mode: 'pair', preset: 'teach_interview' } })
  assert.equal(created.status, 201, 'room created')
  const roomId = created.body.room_id
  const ownerToken = created.body.owner_token
  const hostSession = randomUUID()
  const guestSession = randomUUID()
  const otherSession = randomUUID()

  const host = await connect(roomId, hostSession)
  sendJoin(host, 'Host', ownerToken)
  await waitFor(firstOfType(host, 'joined'), 'host joined')

  const guest = await connect(roomId, guestSession)
  sendJoin(guest, 'Lin', null)
  const guestId = (await waitFor(firstOfType(guest, 'joined'), 'guest joined')).member_id

  const other = await connect(roomId, otherSession)
  sendJoin(other, 'Max', null)
  const otherId = (await waitFor(firstOfType(other, 'joined'), 'other joined')).member_id
  console.log(`joined: host, guest (${guestId}), other (${otherId})`)

  // 1. Transfer the Host role to the guest.
  const res = await http('POST', `/v1/rooms/${roomId}/members/${guestId}/make-host`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
  })
  assert.equal(res.status, 200, 'make-host 200')
  assert.equal(res.body.host_member_id, guestId, 'host_member_id')
  assert.equal(res.body.status, 'transferred', 'status transferred')
  assert.ok(!res.raw.includes('owner_token'), 'owner_token is NEVER in the HTTP response')

  // 2. The new Host receives host-granted with a new owner token.
  const granted = await waitFor(firstOfType(guest, 'host-granted'), 'host-granted')
  assert.equal(granted.member_id, guestId, 'host-granted member_id')
  const newToken = granted.owner_token
  assert.ok(newToken && newToken !== ownerToken, 'host-granted carries a new token')
  console.log('transfer: guest is Host; host-granted delivered with a new token')

  // 3. The roster reflects the swap (guest is host).
  const roster = await waitFor(
    () =>
      [...guest.messages]
        .reverse()
        .find(
          (m) => m.type === 'roster' && m.members.find((x) => x.member_id === guestId)?.role === 'host',
        ),
    'roster shows the guest as host',
  )
  assert.ok(roster, 'a roster broadcast marked the guest as host')
  console.log('roster: guest shown as host')

  // 4. The OLD owner token no longer transfers (T-10).
  const withOld = await http('POST', `/v1/rooms/${roomId}/members/${otherId}/make-host`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
  })
  assert.equal(withOld.status, 403, 'old token -> 403')
  assert.ok(withOld.raw.includes('NOT_OWNER'), 'old token -> NOT_OWNER')
  console.log('old owner token retired (403 NOT_OWNER)')

  // 5. The NEW token (held by the new Host) works.
  const withNew = await http('POST', `/v1/rooms/${roomId}/members/${otherId}/make-host`, {
    headers: { 'X-Session-Id': guestSession, 'X-Owner-Token': newToken },
  })
  assert.equal(withNew.status, 200, 'new token -> 200')
  assert.equal(withNew.body.host_member_id, otherId, 'new token transfers to other')
  console.log('new owner token works (transfer to other)')

  for (const s of [host, guest, other]) {
    try {
      s.socket.close()
    } catch {
      /* ignore */
    }
  }
  // Let the WebSocket closes complete before the process exits, so process.exit()
  // never races an in-flight close handle (a libuv abort seen on Windows).
  await sleep(200)
  console.log('US-54 integration: PASS')
}

main()
  .then(() => process.exit(0))
  .catch((e) => {
    console.error('US-54 integration FAILED:', e.message)
    process.exit(1)
  })
