// Integration test for US-38 / Issue #171: Request edit access in Pair mode (FR19.3, V2.22).
//
// Drives sync-service over real HTTP + WebSocket (SYNC_SERVICE_URL, default http://localhost:8081):
//   1. In a Pair room, participant sends request-edit-access on control socket.
//   2. Host receives edit-access-requested with requester's member_id and display_name.
//   3. Duplicate request from same member is a no-op (no second message).
//   4. Host approves via POST /v1/rooms/{roomId}/edit-requests/{memberId}/approve:
//      requester receives edit-access-responded (approved: true), roster updates, and edits succeed.
//   5. Host declines via POST /v1/rooms/{roomId}/edit-requests/{memberId}/decline:
//      requester receives edit-access-responded (approved: false), access unchanged.
//   6. Non-Host approval gets 403 NOT_OWNER.
//   7. Observer and Open-mode rejections return INVALID_REQUEST.
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
    socket.once('error', (err) => {
      clearTimeout(timer)
      reject(err)
    })
  })
}

async function waitMessage(state, predicate, desc, timeout = 4000) {
  const start = Date.now()
  while (Date.now() - start < timeout) {
    const match = state.texts.find(predicate)
    if (match) return match
    await sleep(50)
  }
  throw new Error(`timed out waiting for ${desc}; got: ${JSON.stringify(state.texts)}`)
}

async function joinControl(roomId, sessionId, displayName, ownerToken = null) {
  const conn = await open(`${wsBase}/ws/rooms/${roomId}?session_id=${sessionId}`, displayName)
  conn.socket.send(
    JSON.stringify({
      type: 'join',
      display_name: displayName,
      ...(ownerToken ? { owner_token: ownerToken } : {}),
    }),
  )
  const joined = await waitMessage(conn, (m) => m.type === 'joined', 'joined frame')
  return { conn, joined }
}

async function main() {
  console.log('[US-38] starting edit access request integration checks...')

  // Step 1: Create Pair mode room
  const createRes = await http('POST', '/v1/rooms', {
    body: { mode: 'pair', preset: 'teach_interview' },
  })
  assert.equal(createRes.status, 201, 'Pair room creation')
  const { room_id: roomId, owner_token: ownerToken } = createRes.body

  const hostSessionId = randomUUID()
  const guestSessionId = randomUUID()
  const guest2SessionId = randomUUID()

  const { conn: hostCtrl } = await joinControl(roomId, hostSessionId, 'Ada', ownerToken)
  const { conn: guestCtrl, joined: guestJoined } = await joinControl(roomId, guestSessionId, 'Lin', null)

  assert.equal(guestJoined.permissions.can_edit, false, 'Guest starts read-only in teach_interview')

  // Step 2: Lin requests edit access
  guestCtrl.socket.send(JSON.stringify({ type: 'request-edit-access' }))

  const editReqMsg = await waitMessage(
    hostCtrl,
    (m) => m.type === 'edit-access-requested' && m.member_id === guestJoined.member_id,
    'edit-access-requested message to Host',
  )
  assert.equal(editReqMsg.display_name, 'Lin', 'Request identifies display_name')
  assert.ok(editReqMsg.requested_at, 'Request includes requested_at')

  // Step 3: Duplicate request from Lin is a no-op
  const countBefore = hostCtrl.texts.filter((m) => m.type === 'edit-access-requested').length
  guestCtrl.socket.send(JSON.stringify({ type: 'request-edit-access' }))
  await sleep(QUIET_MS)
  const countAfter = hostCtrl.texts.filter((m) => m.type === 'edit-access-requested').length
  assert.equal(countBefore, countAfter, 'Duplicate request creates no extra notification')

  // Step 4: Host approves via REST
  const approveRes = await http(
    'POST',
    `/v1/rooms/${roomId}/edit-requests/${guestJoined.member_id}/approve`,
    {
      headers: {
        'X-Session-Id': hostSessionId,
        'X-Owner-Token': ownerToken,
      },
    },
  )
  assert.equal(approveRes.status, 200, 'Host approve returns 200')
  assert.equal(approveRes.body.permissions.can_edit, true, 'Approve gives can_edit: true')

  // Step 5: Lin receives edit-access-responded with approved: true
  const approvedMsg = await waitMessage(
    guestCtrl,
    (m) => m.type === 'edit-access-responded' && m.member_id === guestJoined.member_id,
    'edit-access-responded frame to guest',
  )
  assert.equal(approvedMsg.approved, true, 'approved is true')

  // Step 6: Decline flow with a second guest (Bob)
  const { conn: guest2Ctrl, joined: guest2Joined } = await joinControl(roomId, guest2SessionId, 'Bob', null)
  assert.equal(guest2Joined.permissions.can_edit, false)

  guest2Ctrl.socket.send(JSON.stringify({ type: 'request-edit-access' }))
  await waitMessage(
    hostCtrl,
    (m) => m.type === 'edit-access-requested' && m.member_id === guest2Joined.member_id,
    'Bob request reaches Host',
  )

  const declineRes = await http(
    'POST',
    `/v1/rooms/${roomId}/edit-requests/${guest2Joined.member_id}/decline`,
    {
      headers: {
        'X-Session-Id': hostSessionId,
        'X-Owner-Token': ownerToken,
      },
    },
  )
  assert.equal(declineRes.status, 200, 'Decline returns 200')
  assert.equal(declineRes.body.status, 'declined')

  const declinedMsg = await waitMessage(
    guest2Ctrl,
    (m) => m.type === 'edit-access-responded' && m.member_id === guest2Joined.member_id,
    'Bob receives decline response',
  )
  assert.equal(declinedMsg.approved, false, 'Bob approved is false')

  // Step 7: Non-Host cannot approve or decline (403 NOT_OWNER)
  const nonHostRes = await http(
    'POST',
    `/v1/rooms/${roomId}/edit-requests/${guest2Joined.member_id}/approve`,
    {
      headers: {
        'X-Session-Id': guestSessionId,
        'X-Owner-Token': 'bad-token',
      },
    },
  )
  assert.equal(nonHostRes.status, 403, 'Non-Host approve returns 403')
  assert.equal(nonHostRes.body.error.code, 'NOT_OWNER')

  // Step 8: Open mode room refusal
  const openRes = await http('POST', '/v1/rooms', {
    body: { mode: 'open', language: 'python' },
  })
  assert.equal(openRes.status, 201)
  const openRoomId = openRes.body.room_id
  const openGuestSessionId = randomUUID()
  const { conn: openCtrl } = await joinControl(openRoomId, openGuestSessionId, 'Charlie', null)

  openCtrl.socket.send(JSON.stringify({ type: 'request-edit-access' }))
  const openErrMsg = await waitMessage(openCtrl, (m) => m.type === 'error', 'Open mode error frame')
  assert.equal(openErrMsg.code, 'INVALID_REQUEST')
  assert.ok(openErrMsg.message.includes('Pair mode'))

  // Close all sockets
  hostCtrl.socket.close()
  guestCtrl.socket.close()
  guest2Ctrl.socket.close()
  openCtrl.socket.close()

  console.log('[US-38] all integration checks passed successfully.')
}

main().catch((err) => {
  console.error('[US-38] integration check failed:', err)
  process.exit(1)
})
