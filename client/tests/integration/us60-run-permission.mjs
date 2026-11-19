// Integration test for US-60 / Issue #130: control who can run (FR41.1 to FR41.4, V2.20).
//
// Drives a live sync-service over real HTTP + WebSocket (SYNC_SERVICE_URL, default
// http://localhost:8081). Covers: the Host taking Run away from a participant and giving
// it back, Host-only running on and off, the roster that follows (can_run,
// run_denied_reason, host_only_run), a non-Host getting NOT_OWNER, and the session lookup
// execution-service reads (needs INTERNAL_API_TOKEN).
//
// With US60_CHECK_RUN=1 it also posts real run requests to execution-service
// (EXECUTION_API_URL, default http://localhost:8082) and checks 403 RUN_NOT_ALLOWED with
// the right reason. That needs the real permission lookup: start the stack with
//   EXECUTION_SPRING_PROFILES= bash tests/integration/up.sh
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

const internalToken = process.env.INTERNAL_API_TOKEN || ''
const execApi = process.env.EXECUTION_API_URL || 'http://localhost:8082'
const checkRun = process.env.US60_CHECK_RUN === '1'

async function lookup(roomId, sessionId) {
  const res = await fetch(`${api}/internal/rooms/${roomId}/sessions/${sessionId}`, {
    headers: { 'X-Internal-Token': internalToken },
  })
  assert.equal(res.status, 200, 'session lookup 200')
  return res.json()
}

async function runAs(roomId, sessionId) {
  const res = await fetch(`${execApi}/v1/runs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      request_id: randomUUID(),
      room_id: roomId,
      session_id: sessionId,
      language: 'python',
      source: { kind: 'inline', files: [{ name: 'main.py', content: 'print(1)\n' }], entry: 'main.py' },
    }),
  })
  const raw = await res.text()
  let json = null
  try {
    json = JSON.parse(raw)
  } catch {
    json = null
  }
  return { status: res.status, body: json, raw }
}

const lastRoster = (state) => [...state.messages].reverse().find((m) => m.type === 'roster')
const entryOf = (roster, id) => roster?.members.find((m) => m.member_id === id)

async function main() {
  const created = await http('POST', '/v1/rooms', { body: { mode: 'pair', preset: 'debug_together' } })
  assert.equal(created.status, 201, 'room created')
  const roomId = created.body.room_id
  const ownerToken = created.body.owner_token
  const hostSession = randomUUID()
  const adaSession = randomUUID()
  const linSession = randomUUID()

  const host = await connect(roomId, hostSession)
  sendJoin(host, 'Host', ownerToken)
  await waitFor(firstOfType(host, 'joined'), 'host joined')
  const ada = await connect(roomId, adaSession)
  sendJoin(ada, 'Ada', null)
  const adaId = (await waitFor(firstOfType(ada, 'joined'), 'ada joined')).member_id
  const lin = await connect(roomId, linSession)
  sendJoin(lin, 'Lin', null)
  const linId = (await waitFor(firstOfType(lin, 'joined'), 'lin joined')).member_id
  console.log(`joined: host, ada (${adaId}), lin (${linId})`)

  const hostHeaders = { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken }
  const runPerm = (memberId, canRun, headers = hostHeaders) =>
    http('PATCH', `/v1/rooms/${roomId}/members/${memberId}/run-permission`, { headers, body: { can_run: canRun } })
  const runPolicy = (hostOnly, headers = hostHeaders) =>
    http('PATCH', `/v1/rooms/${roomId}/run-policy`, { headers, body: { host_only_run: hostOnly } })

  // 0. By default everyone can run (FR41.1).
  if (internalToken) {
    assert.equal((await lookup(roomId, adaSession)).can_run, true, 'ada can run by default')
  }
  if (checkRun) {
    assert.equal((await runAs(roomId, adaSession)).status, 200, 'ada runs by default')
  }

  // 1. Host takes Run away from Ada (FR41.2).
  const off = await runPerm(adaId, false)
  assert.equal(off.status, 200, 'run-permission off 200')
  assert.equal(off.body.permissions.can_run, false, 'response: can_run false')
  assert.equal(off.body.permissions.run_denied_reason, 'run_revoked', 'response: run_revoked')
  const rosterOff = await waitFor(
    () => {
      const r = lastRoster(lin)
      return entryOf(r, adaId)?.permissions.can_run === false ? r : null
    },
    'roster after Run taken away',
  )
  assert.equal(entryOf(rosterOff, adaId).permissions.run_denied_reason, 'run_revoked', 'roster reason')
  assert.equal(entryOf(rosterOff, linId).permissions.can_run, true, 'lin unaffected')
  console.log('Run taken away from Ada; roster updated for the room')

  if (internalToken) {
    const l = await lookup(roomId, adaSession)
    assert.equal(l.can_run, false, 'lookup: can_run false')
    assert.equal(l.run_denied_reason, 'run_revoked', 'lookup: run_revoked')
    assert.equal((await lookup(roomId, linSession)).can_run, true, 'lookup: lin can run')
  }
  if (checkRun) {
    const refused = await runAs(roomId, adaSession)
    assert.equal(refused.status, 403, 'run refused 403')
    assert.equal(refused.body.error.code, 'RUN_NOT_ALLOWED', 'RUN_NOT_ALLOWED')
    assert.equal(refused.body.error.reason, 'run_revoked', 'reason run_revoked')
    assert.equal((await runAs(roomId, linSession)).status, 200, 'lin still runs')
  }

  // 2. A non-Host session cannot change run permission (NOT_OWNER).
  for (const token of [null, 'wrong-token']) {
    const headers = { 'X-Session-Id': linSession, ...(token ? { 'X-Owner-Token': token } : {}) }
    const a = await runPerm(adaId, true, headers)
    assert.equal(a.status, 403, 'non-Host member change -> 403')
    assert.ok(a.raw.includes('NOT_OWNER'), 'non-Host member change -> NOT_OWNER')
    const b = await runPolicy(true, headers)
    assert.equal(b.status, 403, 'non-Host policy change -> 403')
    assert.ok(b.raw.includes('NOT_OWNER'), 'non-Host policy change -> NOT_OWNER')
  }
  console.log('non-Host run-permission changes refused (NOT_OWNER)')

  // 3. Host gives Run back.
  assert.equal((await runPerm(adaId, true)).status, 200, 'run-permission on 200')
  await waitFor(() => entryOf(lastRoster(ada), adaId)?.permissions.can_run === true, 'roster after Run given back')
  if (internalToken) assert.equal((await lookup(roomId, adaSession)).can_run, true, 'lookup: back on')
  if (checkRun) assert.equal((await runAs(roomId, adaSession)).status, 200, 'ada runs again')
  console.log('Run given back to Ada')

  // 4. Host-only running on (FR41.3).
  const on = await runPolicy(true)
  assert.equal(on.status, 200, 'run-policy on 200')
  assert.equal(on.body.host_only_run, true, 'response: host_only_run true')
  const rosterOn = await waitFor(
    () => {
      const r = lastRoster(ada)
      return r?.host_only_run === true ? r : null
    },
    'roster with host_only_run',
  )
  assert.equal(entryOf(rosterOn, adaId).permissions.run_denied_reason, 'host_only', 'roster reason host_only')
  assert.equal(entryOf(rosterOn, adaId).permissions.can_run, false, 'ada cannot run')
  const hostEntry = rosterOn.members.find((m) => m.role === 'host')
  assert.equal(hostEntry.permissions.can_run, true, 'the Host still can run')
  if (internalToken) {
    assert.equal((await lookup(roomId, linSession)).run_denied_reason, 'host_only', 'lookup: host_only')
    assert.equal((await lookup(roomId, hostSession)).can_run, true, 'lookup: Host can run')
  }
  if (checkRun) {
    const refused = await runAs(roomId, linSession)
    assert.equal(refused.status, 403, 'host-only: lin refused')
    assert.equal(refused.body.error.reason, 'host_only', 'reason host_only')
    assert.equal((await runAs(roomId, hostSession)).status, 200, 'host-only: the Host runs')
  }
  console.log('Host-only running on: everyone but the Host is refused')

  // 5. Host-only running off.
  assert.equal((await runPolicy(false)).status, 200, 'run-policy off 200')
  await waitFor(() => lastRoster(lin)?.host_only_run === false, 'roster with host_only_run false')
  if (internalToken) assert.equal((await lookup(roomId, linSession)).can_run, true, 'lookup: lin back')
  if (checkRun) assert.equal((await runAs(roomId, linSession)).status, 200, 'lin runs again')
  console.log('Host-only running off: everyone can run again')

  for (const s of [host, ada, lin]) {
    try {
      s.socket.close()
    } catch {
      /* ignore */
    }
  }
  await sleep(200)
  console.log('US-60 integration: PASS')
}

main()
  .then(() => process.exit(0))
  .catch((e) => {
    console.error('US-60 integration FAILED:', e.message)
    process.exit(1)
  })
