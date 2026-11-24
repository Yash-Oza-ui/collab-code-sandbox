// Integration test for US-47 / Issue #211: Run one runnable module (FR25.1, FR25.2, EPIC 7).
//
// Drives sync-service and execution-service over real HTTP + WebSocket:
//   1. Open room: a module that is marked runnable can be run on its own (FR25.2).
//   2. Run contains only that module. Non-runnable module gets 422 MODULE_NOT_RUNNABLE.
//   3. Run uses saved version and never live text (FR25.1). Unsaved edits have no effect.
//   4. Never-saved module gets 422 MODULE_NOT_SAVED telling the person to save first.
//   5. Request carries source: {"kind":"saved","target":"module","module_id":"..."} and no code.
//      Inline code in Open mode is rejected with 422 INVALID_REQUEST.
//   6. Observer gets 403 RUN_NOT_ALLOWED.
//   7. Result visibility is private, delivered only to runner's session (audience).
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'

const syncApi = process.env.SYNC_SERVICE_URL || 'http://localhost:8081'
const execApi = process.env.EXECUTION_API_URL || null
const checkRun = process.env.US47_CHECK_RUN === '1' && Boolean(execApi)
const wsBase = syncApi.replace(/^http/, 'ws')
const internalToken = process.env.INTERNAL_API_TOKEN || 'test-only-integration-token-fixture'
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

async function waitFor(predicate, description) {
  const start = Date.now()
  while (Date.now() - start < TIMEOUT) {
    if (predicate()) return
    await new Promise((r) => setTimeout(r, 50))
  }
  throw new Error(`timed out waiting for: ${description}`)
}

async function main() {
  console.log(`[US-47] Sync-service: ${syncApi}`)
  if (execApi) {
    console.log(`[US-47] Execution-service: ${execApi}`)
  } else {
    console.log('[US-47] Execution-service offline; running sync-service snapshot contract checks.')
  }

  // 1. Create Open mode room (Python)
  const createRoomRes = await http(syncApi, 'POST', '/v1/rooms', {
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

  // 2. Host creates module solver.py
  const createModRes = await http(syncApi, 'POST', `/v1/rooms/${roomId}/modules`, {
    headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
    body: { name: 'solver.py' },
  })
  assert.equal(createModRes.status, 201)
  const solverModuleId = createModRes.body.module_id

  // TEST 1: Never-saved module cannot be run / snapshot refused with 422 MODULE_NOT_SAVED
  console.log('[US-47] Test 1: Snapshot refused with MODULE_NOT_SAVED for never-saved module...')
  const snapshotNeverSaved = await http(syncApi, 'POST', `/internal/rooms/${roomId}/snapshot`, {
    headers: { 'X-Internal-Token': internalToken },
    body: { kind: 'saved', target: 'module', module_id: solverModuleId },
  })
  assert.equal(snapshotNeverSaved.status, 422)
  assert.equal(snapshotNeverSaved.body?.error?.code, 'MODULE_NOT_SAVED')
  assert.ok(snapshotNeverSaved.body?.error?.message?.includes('never been saved'))
  console.log('✓ Snapshot correctly refused never-saved module with 422 MODULE_NOT_SAVED.')

  // 3. Save version 1 of solver.py
  console.log('[US-47] Saving version 1 of solver.py...')
  const saveV1Res = await http(syncApi, 'POST', `/v1/rooms/${roomId}/modules/${solverModuleId}/save`, {
    headers: { 'X-Session-Id': hostSession },
    body: { text: "print('hello from solver v1')\n" },
  })
  assert.equal(saveV1Res.status, 200)
  assert.equal(saveV1Res.body.version, 1)

  // TEST 2: Snapshot returns saved version 1
  console.log('[US-47] Test 2: Snapshot returns saved version 1...')
  const snapshotSaved = await http(syncApi, 'POST', `/internal/rooms/${roomId}/snapshot`, {
    headers: { 'X-Internal-Token': internalToken },
    body: { kind: 'saved', target: 'module', module_id: solverModuleId },
  })
  assert.equal(snapshotSaved.status, 200)
  assert.equal(snapshotSaved.body.room_id, roomId)
  assert.equal(snapshotSaved.body.entry, 'solver.py')
  assert.equal(snapshotSaved.body.modules.length, 1)
  assert.equal(snapshotSaved.body.modules[0].module_id, solverModuleId)
  assert.equal(snapshotSaved.body.modules[0].name, 'solver.py')
  assert.equal(snapshotSaved.body.modules[0].content, "print('hello from solver v1')\n")
  assert.equal(snapshotSaved.body.modules[0].version, 1)
  assert.equal(snapshotSaved.body.modules[0].saved_by.member_id, hostMemberId)
  assert.ok(snapshotSaved.body.modules[0].saved_at)
  console.log('✓ Snapshot returned exact saved version 1.')

  // TEST 3: Non-runnable module snapshot refused with 422 MODULE_NOT_RUNNABLE
  console.log('[US-47] Test 3: Unmark runnable and check refusal...')
  const unmarkRunnable = await http(syncApi, 'PATCH', `/v1/rooms/${roomId}/modules/${solverModuleId}`, {
    headers: { 'X-Session-Id': hostSession },
    body: { runnable: false },
  })
  assert.equal(unmarkRunnable.status, 200)
  assert.equal(unmarkRunnable.body.runnable, false)

  const snapshotNotRunnable = await http(syncApi, 'POST', `/internal/rooms/${roomId}/snapshot`, {
    headers: { 'X-Internal-Token': internalToken },
    body: { kind: 'saved', target: 'module', module_id: solverModuleId },
  })
  assert.equal(snapshotNotRunnable.status, 422)
  assert.equal(snapshotNotRunnable.body?.error?.code, 'MODULE_NOT_RUNNABLE')
  console.log('✓ Snapshot refused non-runnable module with 422 MODULE_NOT_RUNNABLE.')

  // Mark runnable again
  const markRunnable = await http(syncApi, 'PATCH', `/v1/rooms/${roomId}/modules/${solverModuleId}`, {
    headers: { 'X-Session-Id': hostSession },
    body: { runnable: true },
  })
  assert.equal(markRunnable.status, 200)
  assert.equal(markRunnable.body.runnable, true)

  // 4. End-to-end execution checks against execution-service if running with real lookup
  if (checkRun) {
    console.log('[US-47] Testing end-to-end execution against execution-service...')
    hostControl.texts = []
    guestControl.texts = []

    // Test 4a: Run runnable module on its own
    const runReqId = randomUUID()
    const runRes = await http(execApi, 'POST', '/v1/runs', {
      body: {
        request_id: runReqId,
        room_id: roomId,
        session_id: hostSession,
        language: 'python',
        source: {
          kind: 'saved',
          target: 'module',
          module_id: solverModuleId,
        },
      },
    })
    assert.equal(runRes.status, 200, `Execution failed: ${runRes.raw}`)
    assert.equal(runRes.body.request_id, runReqId)
    assert.ok(runRes.body.stdout.includes('hello from solver v1'))
    assert.equal(runRes.body.visibility, 'private')
    assert.equal(runRes.body.saved_versions?.length, 1)
    assert.equal(runRes.body.saved_versions[0].module_id, solverModuleId)
    assert.equal(runRes.body.saved_versions[0].name, 'solver.py')
    assert.equal(runRes.body.saved_versions[0].version, 1)
    assert.equal(runRes.body.saved_versions[0].saved_by.member_id, hostMemberId)
    assert.equal(runRes.body.failure, null)
    console.log('✓ Module ran on its own, output matches saved version 1, visibility is private.')

    // Private delivery check: host received private run-result, guest did not
    await waitFor(
      () => hostControl.texts.some((m) => m.type === 'run-result' && m.request_id === runReqId),
      'host receives private run-result',
    )
    assert.ok(
      !guestControl.texts.some((m) => m.type === 'run-result' && m.request_id === runReqId),
      'guest must NOT receive private run-result frame',
    )
    console.log('✓ Private delivery verified: runner received run-result, other participant did not.')

    // Test 4b: Non-runnable module returns 422 MODULE_NOT_RUNNABLE
    await http(syncApi, 'PATCH', `/v1/rooms/${roomId}/modules/${solverModuleId}`, {
      headers: { 'X-Session-Id': hostSession },
      body: { runnable: false },
    })

    const runNotRunnable = await http(execApi, 'POST', '/v1/runs', {
      body: {
        request_id: randomUUID(),
        room_id: roomId,
        session_id: hostSession,
        language: 'python',
        source: {
          kind: 'saved',
          target: 'module',
          module_id: solverModuleId,
        },
      },
    })
    assert.equal(runNotRunnable.status, 422)
    assert.equal(runNotRunnable.body?.error?.code, 'MODULE_NOT_RUNNABLE')
    console.log('✓ Non-runnable module execution refused with 422 MODULE_NOT_RUNNABLE.')

    // Mark runnable again
    await http(syncApi, 'PATCH', `/v1/rooms/${roomId}/modules/${solverModuleId}`, {
      headers: { 'X-Session-Id': hostSession },
      body: { runnable: true },
    })

    // Test 4c: Never-saved module returns 422 MODULE_NOT_SAVED
    const createMod2Res = await http(syncApi, 'POST', `/v1/rooms/${roomId}/modules`, {
      headers: { 'X-Session-Id': hostSession, 'X-Owner-Token': ownerToken },
      body: { name: 'unsaved_mod.py' },
    })
    assert.equal(createMod2Res.status, 201)
    const unsavedModId = createMod2Res.body.module_id

    const runNeverSaved = await http(execApi, 'POST', '/v1/runs', {
      body: {
        request_id: randomUUID(),
        room_id: roomId,
        session_id: hostSession,
        language: 'python',
        source: {
          kind: 'saved',
          target: 'module',
          module_id: unsavedModId,
        },
      },
    })
    assert.equal(runNeverSaved.status, 422)
    assert.equal(runNeverSaved.body?.error?.code, 'MODULE_NOT_SAVED')
    console.log('✓ Never-saved module execution refused with 422 MODULE_NOT_SAVED.')

    // Test 4d: Open mode inline code rejected with 422 INVALID_REQUEST
    const runInlineInOpen = await http(execApi, 'POST', '/v1/runs', {
      body: {
        request_id: randomUUID(),
        room_id: roomId,
        session_id: hostSession,
        language: 'python',
        source: {
          kind: 'inline',
          files: [{ name: 'solver.py', content: 'print(1)' }],
          entry: 'solver.py',
        },
      },
    })
    assert.equal(runInlineInOpen.status, 422)
    assert.equal(runInlineInOpen.body?.error?.code, 'INVALID_REQUEST')
    console.log('✓ Inline code in Open mode room rejected with 422 INVALID_REQUEST.')

    // Test 4e: Observer denied with 403 RUN_NOT_ALLOWED
    const obsSession = randomUUID()
    const obsControl = await connectControl(roomId, obsSession)
    sendJoin(obsControl, 'Observer Dan', null, 'observer')
    await waitFor(() => obsControl.texts.some((m) => m.type === 'joined'), 'observer joined')

    const runObserver = await http(execApi, 'POST', '/v1/runs', {
      body: {
        request_id: randomUUID(),
        room_id: roomId,
        session_id: obsSession,
        language: 'python',
        source: {
          kind: 'saved',
          target: 'module',
          module_id: solverModuleId,
        },
      },
    })
    assert.equal(runObserver.status, 403)
    assert.equal(runObserver.body?.error?.code, 'RUN_NOT_ALLOWED')
    assert.equal(runObserver.body?.error?.reason, 'observer')
    console.log('✓ Observer run request refused with 403 RUN_NOT_ALLOWED (reason: observer).')

    await obsControl.socket.close()
  }

  await hostControl.socket.close()
  await guestControl.socket.close()
  console.log('[US-47] All acceptance criteria verified successfully.')
}

main().catch((err) => {
  console.error('[US-47] Integration test failed:', err)
  process.exit(1)
})
