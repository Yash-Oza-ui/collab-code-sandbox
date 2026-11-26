// Integration test for US-49: Keep a run result private or share it (FR27, FR4.5, NFR9, Issue #213).
//
// Real HTTP + WebSocket tests against sync-service and execution-service:
//   1. Open room: run result is private to runner by default (FR27).
//      Delivered only to runner's connections (audience: sessions).
//      Participant and observer do NOT receive the private run-result frame.
//   2. Another participant trying to share gets 403 NOT_RUNNER.
//   3. Runner calls POST /v1/runs/{request_id}/share:
//      Transitions visibility to "room", broadcasts to everyone in the room.
//      Both participant and observer receive the shared run-result frame.
//   4. Sharing twice is harmless (idempotent 200, no duplicate broadcast).
//   5. Pair room: runs have visibility "room" by default (FR4.5).
//      Calling /share is a harmless 200 no-op.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'

const syncApi = process.env.SYNC_SERVICE_URL || 'http://localhost:8081'
const execApi = process.env.EXECUTION_API_URL || 'http://localhost:8082'
const wsBase = syncApi.replace(/^http/, 'ws')
const TIMEOUT = 10000

async function http(baseUrl, method, path, { headers = {}, body } = {}) {
  const res = await fetch(baseUrl + path, {
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

function openSocket(url, label) {
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
  openSocket(`${wsBase}/ws/rooms/${roomId}?session_id=${session}`, `control ${roomId}`)

function sendJoin(state, name, ownerToken, role) {
  state.socket.send(
    JSON.stringify({
      type: 'join',
      display_name: name,
      ...(ownerToken ? { owner_token: ownerToken } : {}),
      ...(role ? { role } : {}),
    }),
  )
}

async function waitForText(state, predicate, label, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const found = state.texts.find(predicate)
    if (found) return found
    await new Promise((r) => setTimeout(r, 40))
  }
  throw new Error(`timeout waiting for message: ${label}`)
}

async function main() {
  console.log('--- US-49 integration: Keep a run result private or share it ---')

  // Verify services are reachable
  const syncHealth = await http(syncApi, 'GET', '/actuator/health')
  assert.equal(syncHealth.status, 200, 'sync-service health check failed')

  const execHealth = await http(execApi, 'GET', '/actuator/health')
  assert.equal(execHealth.status, 200, 'execution-service health check failed')

  // 1. Create an Open mode room
  const createRes = await http(syncApi, 'POST', '/v1/rooms', {
    body: { mode: 'open', language: 'python' },
  })
  assert.equal(createRes.status, 201, 'expected 201 from room creation')
  const roomId = createRes.body.room_id
  const ownerToken = createRes.body.owner_token
  assert.ok(roomId, 'room_id must be present')
  assert.ok(ownerToken, 'owner_token must be present')

  // 2. Connect Runner (Host), Participant, and Observer
  const runnerSession = randomUUID()
  const partSession = randomUUID()
  const obsSession = randomUUID()

  const runnerConn = await connectControl(roomId, runnerSession)
  sendJoin(runnerConn, 'Runner', ownerToken)
  await waitForText(runnerConn, (m) => m.type === 'joined', 'runner joined')

  const partConn = await connectControl(roomId, partSession)
  sendJoin(partConn, 'Participant', null, 'participant')
  await waitForText(partConn, (m) => m.type === 'joined', 'participant joined')

  const obsConn = await connectControl(roomId, obsSession)
  sendJoin(obsConn, 'Observer', null, 'observer')
  await waitForText(obsConn, (m) => m.type === 'joined', 'observer joined')

  // 3. Runner creates, saves, and sets module runnable
  const modRes = await http(syncApi, 'POST', `/v1/rooms/${roomId}/modules`, {
    headers: { 'X-Session-Id': runnerSession },
    body: { name: 'calc.py' },
  })
  assert.equal(modRes.status, 201, 'module creation status')
  const moduleId = modRes.body.module.module_id

  const saveRes = await http(syncApi, 'POST', `/v1/rooms/${roomId}/modules/${moduleId}/save`, {
    headers: { 'X-Session-Id': runnerSession },
    body: { content: 'print("hello private run")\n' },
  })
  assert.equal(saveRes.status, 200, 'module save status')

  const runnableRes = await http(syncApi, 'PATCH', `/v1/rooms/${roomId}/modules/${moduleId}`, {
    headers: { 'X-Session-Id': runnerSession },
    body: { is_runnable: true },
  })
  assert.equal(runnableRes.status, 200, 'module set runnable status')

  // 4. Run the module in Open mode
  const runReqId = randomUUID()
  console.log(`Running code with request_id: ${runReqId}`)

  const runRes = await http(execApi, 'POST', '/v1/runs', {
    body: {
      request_id: runReqId,
      room_id: roomId,
      session_id: runnerSession,
      language: 'python',
      source: {
        kind: 'saved',
        target: 'module',
        module_id: moduleId,
      },
    },
  })
  assert.equal(runRes.status, 200, 'run response status')
  assert.equal(runRes.body.visibility, 'private', 'Open mode run result must be private by default')
  assert.ok(runRes.body.stdout.includes('hello private run'), 'run stdout matches')

  // 5. Verify WebSocket deliveries for the private run
  // Runner connection should receive the private run-result
  const runnerRunMsg = await waitForText(
    runnerConn,
    (m) => m.type === 'run-result' && m.request_id === runReqId,
    'runner receives private run-result',
    4000,
  )
  assert.equal(runnerRunMsg.visibility, 'private', 'runner received private visibility')

  // Give 500ms and verify Participant and Observer NEVER received it
  await new Promise((r) => setTimeout(r, 500))
  const partSawPrivate = partConn.texts.some(
    (m) => m.type === 'run-result' && m.request_id === runReqId,
  )
  assert.equal(partSawPrivate, false, 'participant must NOT receive private run-result frame (FR27)')

  const obsSawPrivate = obsConn.texts.some(
    (m) => m.type === 'run-result' && m.request_id === runReqId,
  )
  assert.equal(obsSawPrivate, false, 'observer must NOT receive private run-result frame (FR9.4)')

  // 6. Non-runner attempts to share: 403 NOT_RUNNER
  console.log('Testing non-runner share refusal...')
  const nonRunnerShareRes = await http(execApi, 'POST', `/v1/runs/${runReqId}/share`, {
    body: { session_id: partSession },
  })
  assert.equal(nonRunnerShareRes.status, 403, 'non-runner share must return 403')
  assert.equal(nonRunnerShareRes.body?.error?.code, 'NOT_RUNNER', 'error code NOT_RUNNER')

  // Unknown run share: 404 RUN_NOT_FOUND
  const unknownShareRes = await http(execApi, 'POST', `/v1/runs/${randomUUID()}/share`, {
    body: { session_id: runnerSession },
  })
  assert.equal(unknownShareRes.status, 404, 'unknown run share must return 404')
  assert.equal(unknownShareRes.body?.error?.code, 'RUN_NOT_FOUND', 'error code RUN_NOT_FOUND')

  // 7. Runner shares to room
  console.log('Runner sharing to room...')
  const shareRes = await http(execApi, 'POST', `/v1/runs/${runReqId}/share`, {
    body: { session_id: runnerSession },
  })
  assert.equal(shareRes.status, 200, 'share response status 200')
  assert.equal(shareRes.body.request_id, runReqId, 'request_id matches')
  assert.equal(shareRes.body.visibility, 'room', 'visibility is now room')

  // 8. Verify everyone in the room receives the shared run-result
  const partSharedMsg = await waitForText(
    partConn,
    (m) => m.type === 'run-result' && m.request_id === runReqId,
    'participant receives shared run-result',
    4000,
  )
  assert.equal(partSharedMsg.visibility, 'room', 'participant sees visibility: room')

  const obsSharedMsg = await waitForText(
    obsConn,
    (m) => m.type === 'run-result' && m.request_id === runReqId,
    'observer receives shared run-result',
    4000,
  )
  assert.equal(obsSharedMsg.visibility, 'room', 'observer sees visibility: room')

  // 9. Sharing twice is harmless: second call returns 200 room and does not send again
  console.log('Testing idempotent share twice...')
  const partRunCountBefore = partConn.texts.filter(
    (m) => m.type === 'run-result' && m.request_id === runReqId,
  ).length

  const shareTwiceRes = await http(execApi, 'POST', `/v1/runs/${runReqId}/share`, {
    body: { session_id: runnerSession },
  })
  assert.equal(shareTwiceRes.status, 200, 'second share returns 200')
  assert.equal(shareTwiceRes.body.visibility, 'room', 'visibility is room')

  await new Promise((r) => setTimeout(r, 400))
  const partRunCountAfter = partConn.texts.filter(
    (m) => m.type === 'run-result' && m.request_id === runReqId,
  ).length
  assert.equal(partRunCountAfter, partRunCountBefore, 'second share must not broadcast duplicate frame')

  // 10. Pair room: runs have visibility room by default, and /share is a no-op 200
  console.log('Testing Pair room visibility and share no-op...')
  const pairCreate = await http(syncApi, 'POST', '/v1/rooms', {
    body: { mode: 'pair' },
  })
  const pairRoomId = pairCreate.body.room_id
  const pairOwnerToken = pairCreate.body.owner_token
  const pairRunnerSession = randomUUID()

  const pairRunnerConn = await connectControl(pairRoomId, pairRunnerSession)
  sendJoin(pairRunnerConn, 'PairRunner', pairOwnerToken)
  await waitForText(pairRunnerConn, (m) => m.type === 'joined', 'pair runner joined')

  const pairRunReqId = randomUUID()
  const pairRunRes = await http(execApi, 'POST', '/v1/runs', {
    body: {
      request_id: pairRunReqId,
      room_id: pairRoomId,
      session_id: pairRunnerSession,
      language: 'python',
      source: {
        kind: 'inline',
        files: [{ name: 'main.py', content: 'print("hello pair mode")\n' }],
      },
    },
  })
  assert.equal(pairRunRes.status, 200, 'pair run status 200')
  assert.equal(pairRunRes.body.visibility, 'room', 'pair mode run result must be room visibility (FR4.5)')

  const pairShareRes = await http(execApi, 'POST', `/v1/runs/${pairRunReqId}/share`, {
    body: { session_id: pairRunnerSession },
  })
  assert.equal(pairShareRes.status, 200, 'pair share returns 200')
  assert.equal(pairShareRes.body.visibility, 'room', 'pair share visibility is room')

  // Clean up sockets
  runnerConn.socket.close()
  partConn.socket.close()
  obsConn.socket.close()
  pairRunnerConn.socket.close()

  console.log('✅ All US-49 integration checks passed successfully.')
}

main().catch((err) => {
  console.error('❌ US-49 integration test failed:', err)
  process.exit(1)
})
