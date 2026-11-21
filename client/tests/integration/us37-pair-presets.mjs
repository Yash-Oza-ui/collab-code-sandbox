// Integration test for US-37 / Issue #175: Pair mode presets: Teach/Interview and Debug together (FR19.2).
//
// Drives sync-service over real HTTP + WebSocket (SYNC_SERVICE_URL, default http://localhost:8081):
//   1. Teach/Interview preset (teach_interview):
//      - Host joins with can_edit=true by default; edits pass through.
//      - Participant joins with can_edit=false by default; edits dropped with edit-rejected (no_edit_access).
//      - Later participant joins with can_edit=false; edits dropped with edit-rejected (no_edit_access).
//      - Observer cannot edit (edits dropped with edit-rejected reason observer).
//   2. Debug together preset (debug_together):
//      - Host joins with can_edit=true by default; edits pass through.
//      - Participant joins with can_edit=true by default; edits reach host.
//      - Later participant joins with can_edit=true; edits reach host.
//      - Observer cannot edit (edits dropped with edit-rejected reason observer).
//   3. Server enforces defaults / modified client cannot elevate its own permissions (NFR13):
//      - Join message claiming permissions.can_edit=true is ignored; server assigns can_edit=false in teach_interview.
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

function sendJoin(state, name, ownerToken, extra = {}) {
  state.socket.send(
    JSON.stringify({
      type: 'join',
      display_name: name,
      ...(ownerToken ? { owner_token: ownerToken } : {}),
      ...extra,
    }),
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
  console.log('US-37 integration: start')

  const openSockets = []
  const track = (s) => {
    openSockets.push(s)
    return s
  }

  try {
    // =========================================================================
    // 1. Teach/Interview room tests
    // =========================================================================
    console.log('1. Testing Teach/Interview room preset...')
    const teachRoomRes = await http('POST', '/v1/rooms', { body: { mode: 'pair', preset: 'teach_interview' } })
    assert.equal(teachRoomRes.status, 201, 'Teach/Interview room created')
    const teachRoomId = teachRoomRes.body.room_id
    const teachOwnerToken = teachRoomRes.body.owner_token

    const hostSession = randomUUID()
    const guest1Session = randomUUID()
    const guest2Session = randomUUID()

    const teachHostCtl = track(await connectControl(teachRoomId, hostSession))
    sendJoin(teachHostCtl, 'AliceHost', teachOwnerToken)
    const hostJoined = await waitFor(firstOfType(teachHostCtl, 'joined'), 'teach host joined')
    assert.equal(hostJoined.mode, 'pair')
    assert.equal(hostJoined.preset, 'teach_interview')
    assert.equal(hostJoined.permissions?.can_edit, true, 'Teach/Interview Host has can_edit=true')

    const teachGuest1Ctl = track(await connectControl(teachRoomId, guest1Session))
    sendJoin(teachGuest1Ctl, 'BobParticipant1', null)
    const guest1Joined = await waitFor(firstOfType(teachGuest1Ctl, 'joined'), 'guest 1 joined')
    assert.equal(guest1Joined.mode, 'pair')
    assert.equal(guest1Joined.preset, 'teach_interview')
    assert.equal(guest1Joined.permissions?.can_edit, false, 'Teach/Interview Guest 1 starts with can_edit=false')

    const teachGuest2Session = guest2Session
    const teachGuest2Ctl = track(await connectControl(teachRoomId, teachGuest2Session))
    sendJoin(teachGuest2Ctl, 'CharlieParticipant2', null)
    const guest2Joined = await waitFor(firstOfType(teachGuest2Ctl, 'joined'), 'guest 2 joined')
    assert.equal(guest2Joined.permissions?.can_edit, false, 'Teach/Interview later joiner starts with can_edit=false')

    // Document checks in Teach/Interview
    const teachHostDoc = track(await connectDoc(teachRoomId, hostSession))
    const teachGuest1Doc = track(await connectDoc(teachRoomId, guest1Session))
    const teachGuest2Doc = track(await connectDoc(teachRoomId, teachGuest2Session))

    // Host sends edit -> passes without rejection
    const frameHost = Uint8Array.from([0x00, 0x02, 0x01, 0x02])
    teachHostDoc.socket.send(frameHost)
    await sleep(QUIET_MS)
    assert.equal(rejections(teachHostCtl).length, 0, 'Host edit produces no rejection')

    // Guest 1 sends edit -> dropped with edit-rejected (no_edit_access)
    const frameGuest1 = Uint8Array.from([0x00, 0x02, 0x03, 0x04])
    teachGuest1Doc.socket.send(frameGuest1)
    const rejGuest1 = await waitFor(() => rejections(teachGuest1Ctl)[0], 'guest 1 edit-rejected')
    assert.equal(rejGuest1.reason, 'no_edit_access')
    assert.ok(!teachHostDoc.binaries.some((b) => sameBytes(b, frameGuest1)), 'guest 1 edit dropped')

    // Guest 2 (later joiner) sends edit -> dropped with edit-rejected (no_edit_access)
    const frameGuest2 = Uint8Array.from([0x00, 0x02, 0x05, 0x06])
    teachGuest2Doc.socket.send(frameGuest2)
    const rejGuest2 = await waitFor(() => rejections(teachGuest2Ctl)[0], 'guest 2 edit-rejected')
    assert.equal(rejGuest2.reason, 'no_edit_access')
    assert.ok(!teachHostDoc.binaries.some((b) => sameBytes(b, frameGuest2)), 'guest 2 edit dropped')

    // Demote Guest 2 to Observer -> edit dropped with reason "observer"
    const patchObsRes = await http('PATCH', `/v1/rooms/${teachRoomId}/members/${guest2Joined.member_id}`, {
      headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': teachOwnerToken },
      body: { role: 'observer' },
    })
    assert.equal(patchObsRes.status, 200, 'Guest 2 changed to observer')
    const frameGuest2Obs = Uint8Array.from([0x00, 0x02, 0x07, 0x08])
    const countBeforeObs = rejections(teachGuest2Ctl).length
    teachGuest2Doc.socket.send(frameGuest2Obs)
    const rejObs = await waitFor(() => rejections(teachGuest2Ctl)[countBeforeObs], 'observer edit-rejected')
    assert.equal(rejObs.reason, 'observer', 'Observer edit rejected with reason observer')

    console.log('✓ Teach/Interview: Host edits allowed, initial and later participants denied, observer denied.')

    // =========================================================================
    // 2. Debug together room tests
    // =========================================================================
    console.log('2. Testing Debug together room preset...')
    const debugRoomRes = await http('POST', '/v1/rooms', { body: { mode: 'pair', preset: 'debug_together' } })
    assert.equal(debugRoomRes.status, 201, 'Debug together room created')
    const debugRoomId = debugRoomRes.body.room_id
    const debugOwnerToken = debugRoomRes.body.owner_token

    const dHostSession = randomUUID()
    const dGuest1Session = randomUUID()
    const dGuest2Session = randomUUID()

    const dHostCtl = track(await connectControl(debugRoomId, dHostSession))
    sendJoin(dHostCtl, 'DanHost', debugOwnerToken)
    const dHostJoined = await waitFor(firstOfType(dHostCtl, 'joined'), 'debug host joined')
    assert.equal(dHostJoined.mode, 'pair')
    assert.equal(dHostJoined.preset, 'debug_together')
    assert.equal(dHostJoined.permissions?.can_edit, true, 'Debug together Host has can_edit=true')

    const dGuest1Ctl = track(await connectControl(debugRoomId, dGuest1Session))
    sendJoin(dGuest1Ctl, 'ErinGuest1', null)
    const dGuest1Joined = await waitFor(firstOfType(dGuest1Ctl, 'joined'), 'debug guest 1 joined')
    assert.equal(dGuest1Joined.mode, 'pair')
    assert.equal(dGuest1Joined.preset, 'debug_together')
    assert.equal(dGuest1Joined.permissions?.can_edit, true, 'Debug together Guest 1 starts with can_edit=true')

    const dGuest2Ctl = track(await connectControl(debugRoomId, dGuest2Session))
    sendJoin(dGuest2Ctl, 'FrankGuest2', null)
    const dGuest2Joined = await waitFor(firstOfType(dGuest2Ctl, 'joined'), 'debug guest 2 joined')
    assert.equal(dGuest2Joined.permissions?.can_edit, true, 'Debug together later joiner starts with can_edit=true')

    const dHostDoc = track(await connectDoc(debugRoomId, dHostSession))
    const dGuest1Doc = track(await connectDoc(debugRoomId, dGuest1Session))
    const dGuest2Doc = track(await connectDoc(debugRoomId, dGuest2Session))

    // Guest 1 sends edit -> forwarded to Host
    const dFrame1 = Uint8Array.from([0x00, 0x02, 0x21, 0x22])
    dGuest1Doc.socket.send(dFrame1)
    await waitFor(() => dHostDoc.binaries.find((b) => sameBytes(b, dFrame1)), 'guest 1 frame reached host')
    assert.equal(rejections(dGuest1Ctl).length, 0, 'Guest 1 produces no rejection')

    // Guest 2 (later joiner) sends edit -> forwarded to Host
    const dFrame2 = Uint8Array.from([0x00, 0x02, 0x23, 0x24])
    dGuest2Doc.socket.send(dFrame2)
    await waitFor(() => dHostDoc.binaries.find((b) => sameBytes(b, dFrame2)), 'guest 2 frame reached host')
    assert.equal(rejections(dGuest2Ctl).length, 0, 'Guest 2 produces no rejection')

    // Observer check in Debug together room
    const dPatchObsRes = await http('PATCH', `/v1/rooms/${debugRoomId}/members/${dGuest2Joined.member_id}`, {
      headers: { 'X-Session-Id': dHostSession, 'X-Owner-Token': debugOwnerToken },
      body: { role: 'observer' },
    })
    assert.equal(dPatchObsRes.status, 200, 'Guest 2 demoted to observer in debug room')
    const dFrameObs = Uint8Array.from([0x00, 0x02, 0x25, 0x26])
    const countBeforeDObs = rejections(dGuest2Ctl).length
    dGuest2Doc.socket.send(dFrameObs)
    const dRejObs = await waitFor(() => rejections(dGuest2Ctl)[countBeforeDObs], 'debug observer edit-rejected')
    assert.equal(dRejObs.reason, 'observer', 'Observer edit rejected in debug_together preset')

    console.log('✓ Debug together: Host and all participants edit by default; observer denied.')

    // =========================================================================
    // 3. Modified client cannot elevate edit permissions (NFR13)
    // =========================================================================
    console.log('3. Testing modified client attempting to claim edit access...')
    const rogueSession = randomUUID()
    const rogueCtl = track(await connectControl(teachRoomId, rogueSession))
    // Client attempts to claim can_edit in join payload
    sendJoin(rogueCtl, 'RogueUser', null, { permissions: { can_edit: true } })
    const rogueJoined = await waitFor(firstOfType(rogueCtl, 'joined'), 'rogue joined')
    assert.equal(rogueJoined.permissions?.can_edit, false, 'Server overrides client claim: can_edit=false')

    const rogueDoc = track(await connectDoc(teachRoomId, rogueSession))
    const rogueFrame = Uint8Array.from([0x00, 0x02, 0x31, 0x32])
    rogueDoc.socket.send(rogueFrame)
    const rogueRej = await waitFor(() => rejections(rogueCtl)[0], 'rogue edit rejected')
    assert.equal(rogueRej.reason, 'no_edit_access')
    console.log('✓ Modified client claiming edit access is rejected by server.')

    console.log('US-37 integration: ALL CHECKS PASSED')
  } finally {
    for (const s of openSockets) {
      try {
        s.socket.close()
      } catch {
        /* ignore */
      }
    }
    await sleep(200)
  }
}

main()
  .then(() => process.exit(0))
  .catch((e) => {
    console.error('US-37 integration FAILED:', e.message)
    process.exit(1)
  })
