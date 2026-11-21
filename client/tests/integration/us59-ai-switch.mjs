// US-59: real sync-service HTTP + two distinct joined control-WebSocket clients.
// Every input is synthetic. No real student code or external AI is used.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'

const api = process.env.SYNC_SERVICE_URL || 'http://localhost:8081'
const wsBase = api.replace(/^http/, 'ws')
const TIMEOUT = 10000
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const token = process.env.INTERNAL_API_TOKEN || ''
const sockets = []

async function request(method, path, { headers = {}, body } = {}) {
  const resp = await fetch(api + path, {
    method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  })
  return { status: resp.status, body: await resp.json() }
}

async function connect(roomId) {
  const sessionId = randomUUID()
  const socket = new WebSocket(`${wsBase}/ws/rooms/${roomId}?session_id=${sessionId}`)
  const state = { sessionId, socket, messages: [] }
  sockets.push(socket)
  socket.on('message', (data, isBinary) => {
    if (isBinary) return
    try { state.messages.push(JSON.parse(data.toString())) } catch { /* ignore */ }
  })
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('WebSocket open timed out')), TIMEOUT)
    socket.once('open', () => { clearTimeout(timer); resolve() })
    socket.once('error', (error) => { clearTimeout(timer); reject(error) })
  })
  return state
}

async function until(predicate, what) {
  for (let i = 0; i < TIMEOUT / 25; i++) {
    const result = predicate()
    if (result) return result
    await wait(25)
  }
  throw new Error('Timed out: ' + what)
}

async function join(state, name, ownerToken) {
  state.socket.send(JSON.stringify({
    type: 'join', display_name: name,
    ...(ownerToken ? { owner_token: ownerToken } : {}),
  }))
  return until(() => state.messages.find((m) => m.type === 'joined'), name + ' joined')
}

async function lookup(roomId, sessionId) {
  if (!token) throw new Error('INTERNAL_API_TOKEN required to verify authoritative state')
  const result = await request('GET', `/internal/rooms/${roomId}/sessions/${sessionId}`, {
    headers: { 'X-Internal-Token': token },
  })
  assert.equal(result.status, 200)
  return result.body
}

async function exercisePreset(config, expected) {
  const created = await request('POST', '/v1/rooms', { body: config })
  assert.equal(created.status, 201, 'create ' + JSON.stringify(config))
  const { room_id: roomId, owner_token: ownerToken } = created.body
  const host = await connect(roomId)
  const joinedHost = await join(host, 'US59 Host', ownerToken)
  const guest = await connect(roomId)
  const joinedGuest = await join(guest, 'US59 Guest')
  assert.equal(joinedHost.ai_enabled, expected, 'Host preset default')
  assert.equal(joinedGuest.ai_enabled, expected, 'Guest preset default')
  assert.equal((await lookup(roomId, guest.sessionId)).ai_enabled, expected, 'sync-service lookup default')
  const headers = { 'X-Session-Id': host.sessionId, 'X-Owner-Token': ownerToken }
  const path = `/v1/rooms/${roomId}/ai-switch`
  const guestDenied = await request('PATCH', path, {
    headers: { 'X-Session-Id': guest.sessionId }, body: { ai_enabled: !expected },
  })
  assert.equal(guestDenied.status, 403, 'Guest cannot toggle AI')
  assert.equal(guestDenied.body.error.code, 'NOT_OWNER')
  assert.equal((await lookup(roomId, guest.sessionId)).ai_enabled, expected, 'refused change does not mutate AI')

  for (const enabled of [!expected, expected]) {
    const changed = await request('PATCH', path, { headers, body: { ai_enabled: enabled } })
    assert.equal(changed.status, 200, 'Host toggles AI')
    assert.equal(changed.body.ai_enabled, enabled)
    for (const state of [host, guest]) {
      await until(() => state.messages.some(
        (m) => m.type === 'room-state' && m.ai_enabled === enabled),
      'room-state to Host and Guest without reload')
    }
    assert.equal((await lookup(roomId, guest.sessionId)).ai_enabled, enabled)
  }
  console.log('PASS US-59', config.mode, config.preset || 'open', 'default', expected)
}

try {
  await exercisePreset({ mode: 'pair', preset: 'teach_interview' }, false)
  await exercisePreset({ mode: 'pair', preset: 'debug_together' }, true)
  await exercisePreset({ mode: 'open', language: 'python' }, true)
  console.log('PASS US-59: Host, guest, live room switch, and all preset defaults')
} catch (error) {
  console.error('FAIL US-59:', error.message)
  process.exitCode = 1
} finally {
  for (const socket of sockets) {
    try { socket.close() } catch { /* ignore */ }
  }
}
