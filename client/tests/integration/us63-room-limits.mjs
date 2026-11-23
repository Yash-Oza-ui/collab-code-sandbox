// US-63: real HTTP + control-WebSocket integration for capacity and module limits.
// Run against sync-service with the default limits (10 members, 10 approved modules).
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'

const api = (process.env.SYNC_SERVICE_URL || 'http://localhost:8081').replace(/\/$/, '')
const wsBase = api.replace(/^http/, 'ws')
const maxMembers = Number(process.env.US63_TEST_MAX_MEMBERS || 10)
const maxModules = Number(process.env.US63_TEST_MAX_MODULES || 10)
const timeout = 10000
const sockets = []

function waitFor(predicate, label) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeout
    const poll = () => {
      try {
        const result = predicate()
        if (result) return resolve(result)
        if (Date.now() >= deadline) return reject(new Error(`Timed out: ${label}`))
        setTimeout(poll, 20)
      } catch (error) { reject(error) }
    }
    poll()
  })
}

async function request(method, path, { session, token, data } = {}) {
  const response = await fetch(`${api}${path}`, {
    method,
    headers: {
      ...(data !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(session ? { 'X-Session-Id': session } : {}),
      ...(token ? { 'X-Owner-Token': token } : {}),
    },
    ...(data !== undefined ? { body: JSON.stringify(data) } : {}),
  })
  const bodyText = await response.text()
  return { status: response.status, body: bodyText ? JSON.parse(bodyText) : null }
}

function expectStatus(result, code, label) {
  assert.equal(result.status, code, `${label}: ${JSON.stringify(result.body)}`)
}

async function openControl(roomId, sessionId) {
  const socket = new WebSocket(`${wsBase}/ws/rooms/${encodeURIComponent(roomId)}?session_id=${encodeURIComponent(sessionId)}`, {
    handshakeTimeout: timeout,
  })
  const state = { socket, events: [], closeCode: null }
  sockets.push(socket)
  socket.on('message', (payload, binary) => {
    if (!binary) state.events.push(JSON.parse(payload.toString()))
  })
  socket.on('close', code => { state.closeCode = code })
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('WebSocket open timed out')), timeout)
    socket.once('open', () => { clearTimeout(timer); resolve() })
    socket.once('error', error => { clearTimeout(timer); reject(error) })
  })
  return state
}

async function join(roomId, sessionId, name, token) {
  const state = await openControl(roomId, sessionId)
  state.socket.send(JSON.stringify({
    type: 'join', display_name: name, ...(token ? { owner_token: token } : {}),
  }))
  const result = await waitFor(() => state.events.find(event =>
    event.type === 'joined' || event.type === 'join-refused'), `join result for ${name}`)
  return { ...state, result }
}

async function createRoom(mode = 'open') {
  const result = await request('POST', '/v1/rooms', {
    data: mode === 'open' ? { mode, language: 'python' } : { mode: 'pair', preset: 'debug_together' },
  })
  expectStatus(result, 201, 'Create room')
  return result.body
}

async function moduleList(roomId, session, token, pending = false) {
  const result = await request('GET', `/v1/rooms/${roomId}/modules${pending ? '?pending=true' : ''}`, { session, token })
  expectStatus(result, 200, `List ${pending ? 'pending' : 'approved'} modules`)
  assert.ok(Array.isArray(result.body), 'Module listing is an array')
  return result.body
}

async function main() {
  assert.ok(Number.isInteger(maxMembers) && maxMembers >= 2)
  assert.ok(Number.isInteger(maxModules) && maxModules >= 1)

  // Room A: fill member capacity; a second tab with the same session is not another person.
  const roomA = await createRoom()
  const roomId = roomA.room_id
  const hostSession = randomUUID()
  const host = await join(roomId, hostSession, 'US63Host', roomA.owner_token)
  assert.equal(host.result.type, 'joined', 'Host joins')
  const memberSessions = []
  for (let i = 1; i < maxMembers; i++) {
    const session = randomUUID()
    memberSessions.push(session)
    const person = await join(roomId, session, `US63Person${i}`)
    assert.equal(person.result.type, 'joined', `Person ${i} joins`)
  }
  const extra = await join(roomId, randomUUID(), 'US63Extra')
  assert.equal(extra.result.type, 'join-refused')
  assert.equal(extra.result.reason, 'ROOM_FULL')
  const secondTab = await join(roomId, memberSessions[0], 'US63Person1')
  assert.equal(secondTab.result.type, 'joined', 'Existing session can reconnect when full')
  const hostSecondTab = await join(roomId, hostSession, 'US63Host')
  assert.equal(hostSecondTab.result.type, 'joined', 'Existing Host can reconnect when full')
  assert.equal(hostSecondTab.result.member_id, host.result.member_id)
  const roster = await waitFor(() => hostSecondTab.events.filter(x => x.type === 'roster').at(-1), 'roster')
  assert.equal(roster.members.length, maxMembers, 'Multiple tabs do not increase member count')
  console.log('PASS: ROOM_FULL, same-session reconnect, unique members')

  // Separate room B: capacity is per-room, not global.
  const roomB = await createRoom()
  const otherHostSession = randomUUID()
  const otherHost = await join(roomB.room_id, otherHostSession, 'US63OtherHost', roomB.owner_token)
  assert.equal(otherHost.result.type, 'joined', 'Other room remains accessible')

  // Room A: direct creation fills approved-module capacity.
  const pathA = `/v1/rooms/${roomId}/modules`
  for (let i = 0; i < maxModules; i++) {
    const created = await request('POST', pathA, {
      session: hostSession, token: roomA.owner_token, data: { name: `module${i}.py` },
    })
    expectStatus(created, 201, `Create approved module ${i}`)
    assert.equal(created.body.pending, false)
  }
  const exceeded = await request('POST', pathA, {
    session: hostSession, token: roomA.owner_token, data: { name: 'overlimit.py' },
  })
  expectStatus(exceeded, 409, 'Over-limit Host creation')
  assert.equal(exceeded.body.error.code, 'LIMIT_REACHED')
  assert.equal((await moduleList(roomId, hostSession)).length, maxModules)

  // Participant can request a module; approval at capacity fails without dropping it.
  const requested = await request('POST', pathA, {
    session: memberSessions[0], data: { name: 'pending63.py' },
  })
  expectStatus(requested, 202, 'Pending module request')
  assert.equal(requested.body.pending, true)
  const pendingId = requested.body.module_id
  const rejected = await request('POST', `${pathA}/${pendingId}/approve`, {
    session: hostSession, token: roomA.owner_token,
  })
  expectStatus(rejected, 409, 'Approval at capacity')
  assert.equal(rejected.body.error.code, 'LIMIT_REACHED')
  assert.equal((await moduleList(roomId, hostSession)).length, maxModules)
  const pendingModules = await moduleList(roomId, hostSession, roomA.owner_token, true)
  assert.ok(pendingModules.some(module => module.module_id === pendingId && module.pending === true),
    'Rejected approval leaves original request pending')
  console.log('PASS: LIMIT_REACHED for creation and approval; pending request preserved')

  // Room B has an independent module allowance.
  const independent = await request('POST', `/v1/rooms/${roomB.room_id}/modules`, {
    session: otherHostSession, token: roomB.owner_token, data: { name: 'independent.py' },
  })
  expectStatus(independent, 201, 'Different room can still create modules')
  assert.equal((await moduleList(roomB.room_id, otherHostSession)).length, 1)

  // Pair rooms do not support module operations.
  const pair = await createRoom('pair')
  const pairHostSession = randomUUID()
  const pairHost = await join(pair.room_id, pairHostSession, 'US63PairHost', pair.owner_token)
  assert.equal(pairHost.result.type, 'joined')
  const pairCreate = await request('POST', `/v1/rooms/${pair.room_id}/modules`, {
    session: pairHostSession, token: pair.owner_token, data: { name: 'no.py' },
  })
  expectStatus(pairCreate, 422, 'Pair mode cannot create modules')
  assert.equal(pairCreate.body.error.code, 'INVALID_REQUEST')
  console.log('PASS: room isolation and Pair-mode module refusal')
  console.log('US-63 integration: PASS (saved-module size tests deferred until US-45)')
}

try {
  await main()
} catch (error) {
  console.error('US-63 integration: FAIL', error)
  process.exitCode = 1
} finally {
  for (const socket of sockets) {
    try { socket.close() } catch { /* clean up */ }
  }
}
