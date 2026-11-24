// Integration test for US-35 / Issue #128: only people with edit access can edit in Pair mode.
//
// Drives a live sync-service over real HTTP + WebSocket (SYNC_SERVICE_URL, default
// http://localhost:8081) and behaves like a MODIFIED CLIENT: a participant without edit access
// sends real Yjs update frames straight to the document socket, in the normal encoding and in
// encodings the relay must not be fooled by. Verifies:
//   1. an update from a member without edit access is not forwarded and the sender gets
//      edit-rejected (doc_id, reason no_edit_access);
//   2. the same update written with longer (non-canonical) number encodings, which Yjs still
//      applies, is dropped too (the first-two-bytes rule cannot be bypassed);
//   3. awareness and query-awareness frames from that member are still relayed, and produce no
//      edit-rejected;
//   4. a member with edit access still edits, and the frame reaches others byte for byte;
//   5. access changed by the Host mid-session applies to the next frame (observer, then back).
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'
import * as Y from 'yjs'
import * as syncProtocol from 'y-protocols/sync'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'

const api = process.env.SYNC_SERVICE_URL || 'http://localhost:8081'
const wsBase = api.replace(/^http/, 'ws')
const TIMEOUT = 10000
const QUIET_MS = 700

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

// --- Yjs frames, built like a client would -------------------------------------------------

const attackerDoc = new Y.Doc()
attackerDoc.getText('t').insert(0, 'EVIL')
const update = Y.encodeStateAsUpdate(attackerDoc)

const canonicalEncoder = encoding.createEncoder()
encoding.writeVarUint(canonicalEncoder, 0) // message type: sync
syncProtocol.writeUpdate(canonicalEncoder, update) // sub-type 2 (update) + payload
const canonicalUpdate = encoding.toUint8Array(canonicalEncoder)
const payload = canonicalUpdate.slice(2) // everything after the two header bytes

// Same update, but the type 0 and sub-type 2 are written with a redundant second byte.
const disguisedBoth = Uint8Array.from([0x80, 0x00, 0x82, 0x00, ...payload])
const disguisedSubtype = Uint8Array.from([0x00, 0x82, 0x00, ...payload])

// What a receiving client does with a frame (reads the numbers as variable-length integers).
function applyAsReceiver(frame) {
  const doc = new Y.Doc()
  const decoder = decoding.createDecoder(frame)
  if (decoding.readVarUint(decoder) !== 0) return null
  syncProtocol.readSyncMessage(decoder, encoding.createEncoder(), doc, 'remote')
  return doc.getText('t').toString()
}

async function expectNothing(state, description) {
  const before = state.binaries.length
  await sleep(QUIET_MS)
  assert.equal(state.binaries.length, before, description)
}

async function main() {
  // Precondition: the disguised frames really are updates for a receiving client, otherwise
  // the rest of this test would prove nothing.
  assert.equal(applyAsReceiver(canonicalUpdate), 'EVIL', 'canonical frame applies')
  assert.equal(applyAsReceiver(disguisedBoth), 'EVIL', 'disguised frame (type and sub-type) applies')
  assert.equal(applyAsReceiver(disguisedSubtype), 'EVIL', 'disguised frame (sub-type) applies')
  console.log(`frames: canonical starts ${canonicalUpdate[0]},${canonicalUpdate[1]}; disguised start ${disguisedBoth[0]},${disguisedBoth[1]} and ${disguisedSubtype[0]},${disguisedSubtype[1]}; all apply on a receiver`)

  // ---- Room 1: teach_interview. Only the Host can edit; the participant cannot. ----
  const created = await http('POST', '/v1/rooms', { body: { mode: 'pair', preset: 'teach_interview' } })
  assert.equal(created.status, 201, 'room created')
  const roomId = created.body.room_id
  const ownerToken = created.body.owner_token
  const hostSession = randomUUID()
  const guestSession = randomUUID()

  const hostCtl = await connectControl(roomId, hostSession)
  sendJoin(hostCtl, 'Host', ownerToken)
  await waitFor(firstOfType(hostCtl, 'joined'), 'host joined')
  const guestCtl = await connectControl(roomId, guestSession)
  sendJoin(guestCtl, 'Lin', null)
  await waitFor(firstOfType(guestCtl, 'joined'), 'guest joined')
  const hostDoc = await connectDoc(roomId, hostSession)
  const guestDoc = await connectDoc(roomId, guestSession)
  await sleep(150)

  // 1. A normal update from the participant is rejected and not forwarded.
  guestDoc.socket.send(canonicalUpdate)
  const first = await waitFor(() => rejections(guestCtl)[0], 'edit-rejected for the canonical update')
  assert.equal(first.doc_id, 'main', 'edit-rejected doc_id')
  assert.equal(first.reason, 'no_edit_access', 'edit-rejected reason')
  await expectNothing(hostDoc, 'the host must not receive the participant update')
  console.log('1. update without edit access: dropped, sender got edit-rejected (no_edit_access)')

  // 2. The same update in encodings that only a modified client would use.
  guestDoc.socket.send(disguisedBoth)
  guestDoc.socket.send(disguisedSubtype)
  await waitFor(() => rejections(guestCtl).length >= 3, 'edit-rejected for both disguised updates')
  await expectNothing(hostDoc, 'the host must not receive a disguised update')
  assert.ok(
    rejections(guestCtl).every((m) => m.reason === 'no_edit_access' && m.doc_id === 'main'),
    'every rejection carries doc_id main and reason no_edit_access',
  )
  console.log('2. disguised update frames: dropped, sender got edit-rejected for each')

  // 3. Awareness and query-awareness still work for that participant.
  const awareness = Uint8Array.from([0x01, 0x01, 0x02, 0x03])
  const queryAwareness = Uint8Array.from([0x03])
  const rejectedBefore = rejections(guestCtl).length
  guestDoc.socket.send(awareness)
  await waitFor(() => hostDoc.binaries.find((b) => sameBytes(b, awareness)), 'awareness relayed to the host')
  guestDoc.socket.send(queryAwareness)
  await waitFor(() => hostDoc.binaries.find((b) => sameBytes(b, queryAwareness)), 'query-awareness relayed')
  await sleep(300)
  assert.equal(rejections(guestCtl).length, rejectedBefore, 'awareness frames never produce edit-rejected')
  console.log('3. awareness and query-awareness from a member without edit access: relayed, no edit-rejected')

  // 4. The Host (edit access) edits normally; the frame arrives unchanged.
  hostDoc.socket.send(canonicalUpdate)
  await waitFor(() => guestDoc.binaries.find((b) => sameBytes(b, canonicalUpdate)), 'host update reaches the guest')
  assert.equal(rejections(hostCtl).length, 0, 'the host is never rejected')
  console.log('4. member with edit access: edit reaches everyone unchanged')

  // ---- Room 2: debug_together. Everyone edits until the Host changes access mid-session. ----
  const created2 = await http('POST', '/v1/rooms', { body: { mode: 'pair', preset: 'debug_together' } })
  assert.equal(created2.status, 201, 'second room created')
  const room2 = created2.body.room_id
  const token2 = created2.body.owner_token
  const host2 = randomUUID()
  const peer2 = randomUUID()
  const host2Ctl = await connectControl(room2, host2)
  sendJoin(host2Ctl, 'Host', token2)
  await waitFor(firstOfType(host2Ctl, 'joined'), 'host2 joined')
  const peer2Ctl = await connectControl(room2, peer2)
  sendJoin(peer2Ctl, 'Max', null)
  const peerId = (await waitFor(firstOfType(peer2Ctl, 'joined'), 'peer2 joined')).member_id
  const host2Doc = await connectDoc(room2, host2)
  const peer2Doc = await connectDoc(room2, peer2)
  await sleep(150)

  peer2Doc.socket.send(canonicalUpdate)
  await waitFor(() => host2Doc.binaries.find((b) => sameBytes(b, canonicalUpdate)), 'peer edit reaches host (access on)')

  const setRole = (role) =>
    http('PATCH', `/v1/rooms/${room2}/members/${peerId}`, {
      headers: { 'X-Session-Id': host2, 'X-Owner-Token': token2 },
      body: { role },
    })
  assert.equal((await setRole('observer')).status, 200, 'host makes the peer an observer')
  const hostFramesBefore = host2Doc.binaries.length
  peer2Doc.socket.send(canonicalUpdate)
  const obs = await waitFor(() => rejections(peer2Ctl)[0], 'edit-rejected after access was taken back')
  assert.equal(obs.reason, 'observer', 'an observer is told the reason observer')
  await sleep(QUIET_MS)
  assert.equal(host2Doc.binaries.length, hostFramesBefore, 'nothing is forwarded after access was taken back')

  assert.equal((await setRole('participant')).status, 200, 'host gives edit access back')
  const marker = Uint8Array.from([0x00, 0x02, 0x55, 0x66])
  peer2Doc.socket.send(marker)
  await waitFor(() => host2Doc.binaries.find((b) => sameBytes(b, marker)), 'edit reaches host after access is restored')
  console.log('5. access taken back and restored mid-session: the next frame follows it')

  for (const s of [hostCtl, guestCtl, hostDoc, guestDoc, host2Ctl, peer2Ctl, host2Doc, peer2Doc]) {
    try {
      s.socket.close()
    } catch {
      /* ignore */
    }
  }
  // Let the WebSocket closes complete before the process exits (see the US-54 script).
  await sleep(200)
  console.log('US-35 integration: PASS')
}

main()
  .then(() => process.exit(0))
  .catch((e) => {
    console.error('US-35 integration FAILED:', e.message)
    process.exit(1)
  })
