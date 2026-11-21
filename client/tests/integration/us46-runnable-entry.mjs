// Integration test for US-46 / Issue #174: Mark a module runnable and set the entry file (FR20.2, FR20.3, V2.6).
//
// Drives sync-service over real HTTP + WebSocket:
//   1. In an Open room, module owner marks module runnable/non-runnable (HTTP 200, module-updated broadcast).
//   2. Non-owner attempting to mark runnable gets 403 NOT_MODULE_OWNER.
//   3. Host designates module as workspace entry file (HTTP 200, module-updated broadcast).
//   4. Replacing entry file unsets previous entry file (leaving exactly one entry file).
//   5. Non-Host attempting to designate entry file gets 403 NOT_OWNER.
//   6. Setting entry file for non-existent module gets 404 MODULE_NOT_FOUND.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'

const api = process.env.SYNC_SERVICE_URL || 'http://localhost:8081'
const wsBase = api.replace(/^http/, 'ws')
const TIMEOUT = 10000

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

function sendJoin(state, name, ownerToken) {
  state.socket.send(
    JSON.stringify({ type: 'join', display_name: name, ...(ownerToken ? { owner_token: ownerToken } : {}) }),
  )
}

async function waitFor(predicate, description) {
  const start = Date.now()
  while (Date.now() - start < TIMEOUT) {
    if (predicate()) return
    await new Promise((r) => setTimeout(r, 50))
  }
  throw new Error(`timed out waiting for: ${description}`)
}

async function main() {
  console.log(`[US-46] Target sync-service: ${api}`)

  // 1. Create Open mode room (Python)
  const createRoomRes = await http('POST', '/v1/rooms', {
    body: { mode: 'open', language: 'python' },
  })
  assert.equal(createRoomRes.status, 201, `Failed to create room: ${createRoomRes.raw}`)
  const roomId = createRoomRes.body.room_id
  const ownerToken = createRoomRes.body.owner_token
  assert.ok(roomId && ownerToken)

  const hostSession = randomUUID()
  const guestSession = randomUUID()

  // Connect Host
  const hostControl = await connectControl(roomId, hostSession)
  sendJoin(hostControl, 'Host Alice', ownerToken)
  await waitFor(() => hostControl.texts.some((m) => m.type === 'joined'), 'host joined')
  const hostJoined = hostControl.texts.find((m) => m.type === 'joined')
  const hostMemberId = hostJoined.member_id

  // Connect Guest
  const guestControl = await connectControl(roomId, guestSession)
  sendJoin(guestControl, 'Guest Bob')
  await waitFor(() => guestControl.texts.some((m) => m.type === 'joined'), 'guest joined')
  const guestJoined = guestControl.texts.find((m) => m.type === 'joined')
  const guestMemberId = guestJoined.member_id

  // 2. Host creates main.py
  const createMainRes = await http('POST', `/v1/rooms/${roomId}/modules`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { name: 'main.py' },
  })
  assert.equal(createMainRes.status, 201)
  const mainModuleId = createMainRes.body.module_id
  assert.equal(createMainRes.body.owner_member_id, hostMemberId)

  // 3. Guest creates helper.py (direct create or approved)
  // Let Host directly create helper.py with owner=guestMemberId or request & approve
  const requestHelperRes = await http('POST', `/v1/rooms/${roomId}/modules`, {
    headers: { 'X-Session-Id': guestSession },
    body: { name: 'helper.py' },
  })
  assert.equal(requestHelperRes.status, 202)
  const helperModuleId = requestHelperRes.body.module_id

  // Host approves helper.py
  const approveRes = await http('POST', `/v1/rooms/${roomId}/modules/${helperModuleId}/approve`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
  })
  assert.equal(approveRes.status, 200)
  assert.equal(approveRes.body.owner_member_id, guestMemberId)

  // Clear incoming message logs for clean event checking
  hostControl.texts = []
  guestControl.texts = []

  // TEST 1: Guest (owner) marks helper.py non-runnable (FR20.2)
  console.log('[US-46] Test 1: Module owner marks module non-runnable...')
  const markNonRunnable = await http('PATCH', `/v1/rooms/${roomId}/modules/${helperModuleId}`, {
    headers: { 'X-Session-Id': guestSession },
    body: { runnable: false },
  })
  assert.equal(markNonRunnable.status, 200)
  assert.equal(markNonRunnable.body.runnable, false)

  await waitFor(
    () => hostControl.texts.some((m) => m.type === 'module-updated' && m.module_id === helperModuleId && m.runnable === false),
    'host receives module-updated with runnable=false',
  )
  console.log('✓ Owner toggled runnable=false and Host received live update.')

  // Guest marks runnable back to true
  const markRunnable = await http('PATCH', `/v1/rooms/${roomId}/modules/${helperModuleId}`, {
    headers: { 'X-Session-Id': guestSession },
    body: { runnable: true },
  })
  assert.equal(markRunnable.status, 200)
  assert.equal(markRunnable.body.runnable, true)

  // TEST 2: Non-owner marking runnable refused with 403 NOT_MODULE_OWNER (FR20.2)
  console.log('[US-46] Test 2: Non-owner marking runnable refused with NOT_MODULE_OWNER...')
  const nonOwnerToggle = await http('PATCH', `/v1/rooms/${roomId}/modules/${helperModuleId}`, {
    headers: { 'X-Session-Id': hostSession },
    body: { runnable: false },
  })
  assert.equal(nonOwnerToggle.status, 403)
  assert.equal(nonOwnerToggle.body?.error?.code, 'NOT_MODULE_OWNER')
  console.log('✓ Non-owner refused with 403 NOT_MODULE_OWNER.')

  // TEST 3: Host sets entry file (FR20.3)
  console.log('[US-46] Test 3: Host designates main.py as entry file...')
  hostControl.texts = []
  guestControl.texts = []

  const setEntryRes = await http('PATCH', `/v1/rooms/${roomId}/modules/${mainModuleId}`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { is_entry: true },
  })
  assert.equal(setEntryRes.status, 200)
  assert.equal(setEntryRes.body.is_entry, true)

  await waitFor(
    () => guestControl.texts.some((m) => m.type === 'module-updated' && m.module_id === mainModuleId && m.is_entry === true),
    'guest receives module-updated with is_entry=true',
  )
  console.log('✓ Host set entry file and guest received live update.')

  // TEST 4: Replacing entry file leaves exactly one entry file (FR20.3)
  console.log('[US-46] Test 4: Replacing entry file unsets previous entry file...')
  hostControl.texts = []
  guestControl.texts = []

  const replaceEntryRes = await http('PATCH', `/v1/rooms/${roomId}/modules/${helperModuleId}`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { is_entry: true },
  })
  assert.equal(replaceEntryRes.status, 200)
  assert.equal(replaceEntryRes.body.is_entry, true)

  await waitFor(
    () => guestControl.texts.some((m) => m.type === 'module-updated' && m.module_id === mainModuleId && m.is_entry === false),
    'guest receives main.py entry replaced (is_entry=false)',
  )
  await waitFor(
    () => guestControl.texts.some((m) => m.type === 'module-updated' && m.module_id === helperModuleId && m.is_entry === true),
    'guest receives helper.py set as entry (is_entry=true)',
  )

  // Verify listModules confirms exactly one entry file
  const listRes = await http('GET', `/v1/rooms/${roomId}/modules`, {
    headers: { 'X-Session-Id': guestSession },
  })
  assert.equal(listRes.status, 200)
  const entryCount = listRes.body.filter((m) => m.is_entry).length
  assert.equal(entryCount, 1, 'Expected exactly one entry file in room')
  console.log('✓ Replacing entry file leaves exactly one entry file.')

  // TEST 5: Non-Host setting entry file refused with 403 NOT_OWNER (FR20.3)
  console.log('[US-46] Test 5: Non-Host setting entry file refused with NOT_OWNER...')
  const nonHostEntry = await http('PATCH', `/v1/rooms/${roomId}/modules/${mainModuleId}`, {
    headers: { 'X-Session-Id': guestSession },
    body: { is_entry: true },
  })
  assert.equal(nonHostEntry.status, 403)
  assert.equal(nonHostEntry.body?.error?.code, 'NOT_OWNER')
  console.log('✓ Non-Host refused with 403 NOT_OWNER.')

  // TEST 6: Non-existent module returns 404 MODULE_NOT_FOUND
  console.log('[US-46] Test 6: Non-existent module returns MODULE_NOT_FOUND...')
  const notFoundRes = await http('PATCH', `/v1/rooms/${roomId}/modules/mod_nonexistent999`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { is_entry: true },
  })
  assert.equal(notFoundRes.status, 404)
  assert.equal(notFoundRes.body?.error?.code, 'MODULE_NOT_FOUND')
  console.log('✓ Non-existent module refused with 404 MODULE_NOT_FOUND.')

  hostControl.socket.close()
  guestControl.socket.close()
  console.log('✅ ALL US-46 INTEGRATION CHECKS PASSED!')
}

main().catch((err) => {
  console.error('❌ US-46 Integration check failed:', err)
  process.exit(1)
})
