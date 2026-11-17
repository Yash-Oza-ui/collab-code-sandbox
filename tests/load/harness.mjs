// T-22 load-test harness (T1-P, T1-A, T1-O).
//
// Simulates N users that join a room over the REAL control socket, open the
// REAL document socket(s), and exchange REAL Yjs updates, then measures how long
// an edit takes to reach the other users (NFR1: < 200 ms at the 95th percentile,
// test set-up T1). All users run in ONE Node process, so a single monotonic
// clock times send -> receive with no cross-machine clock skew.
//
//   node harness.mjs --variant T1-P --users 5 --edits 50 --out results/T1-P.json
//
// Variants (NFR2 / contract V2.1, V2.3, V2.5, V2.6):
//   T1-P  Pair mode, preset teach_interview  -> one editor (Host), others read.
//   T1-A  Pair mode, preset debug_together   -> every user edits.
//   T1-O  Open mode, five modules            -> Host writes each module, others read.
//
// No browser, no real user data: the edits are generated counter values.
import * as Y from 'yjs'
import * as syncProtocol from 'y-protocols/sync'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'
import WebSocket from 'ws'
import { randomUUID } from 'node:crypto'
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import os from 'node:os'

const MESSAGE_SYNC = 0 // outer y-websocket message type (byte 0); byte 1 = 2 is an update.

// ----------------------------------------------------------------- CLI args
function parseArgs(argv) {
  const a = { variant: 'T1-P', users: 5, edits: 50, intervalMs: 50, modules: 5,
              url: 'http://localhost:8081', wsUrl: null, out: null }
  for (let i = 2; i < argv.length; i++) {
    const [k, vInline] = argv[i].split('=')
    const v = vInline !== undefined ? vInline : argv[i + 1]
    const take = () => (vInline !== undefined ? null : i++)
    switch (k) {
      case '--variant': a.variant = v; take(); break
      case '--users': a.users = Number(v); take(); break
      case '--edits': a.edits = Number(v); take(); break
      case '--interval': a.intervalMs = Number(v); take(); break
      case '--modules': a.modules = Number(v); take(); break
      case '--url': a.url = v; take(); break
      case '--ws': a.wsUrl = v; take(); break
      case '--out': a.out = v; take(); break
      default: break
    }
  }
  if (!['T1-P', 'T1-A', 'T1-O'].includes(a.variant)) {
    throw new Error(`--variant must be T1-P, T1-A or T1-O (got '${a.variant}')`)
  }
  if (!a.wsUrl) a.wsUrl = a.url.replace(/^http/, 'ws')
  return a
}

const nowMs = () => Number(process.hrtime.bigint() / 1000n) / 1000 // ms, monotonic
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// --------------------------------------------------------------- REST calls
async function createRoom(httpBase, variant) {
  const body = variant === 'T1-O'
    ? { mode: 'open', language: 'python' }
    : { mode: 'pair', preset: variant === 'T1-A' ? 'debug_together' : 'teach_interview' }
  const res = await fetch(`${httpBase}/v1/rooms`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  if (res.status !== 201) throw new Error(`POST /v1/rooms -> ${res.status}: ${await res.text()}`)
  return res.json()
}

// A module is created by the Host (joined member) and then approved, so a
// document socket may address it (ModuleService.canAddressModule).
async function createApprovedModule(httpBase, roomId, hostSessionId, ownerToken, name) {
  const create = await fetch(`${httpBase}/v1/rooms/${roomId}/modules`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Session-Id': hostSessionId },
    body: JSON.stringify({ name }),
  })
  if (create.status !== 202) throw new Error(`create module -> ${create.status}: ${await create.text()}`)
  const moduleId = (await create.json()).module_id
  const approve = await fetch(`${httpBase}/v1/rooms/${roomId}/modules/${moduleId}/approve`, {
    method: 'POST', headers: { 'X-Session-Id': hostSessionId, 'X-Owner-Token': ownerToken },
  })
  if (!approve.ok) throw new Error(`approve module -> ${approve.status}: ${await approve.text()}`)
  return moduleId
}

// ------------------------------------------------------------ control socket
function joinControl(wsBase, roomId, sessionId, displayName, ownerToken) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${wsBase}/ws/rooms/${roomId}?session_id=${sessionId}`)
    const timer = setTimeout(() => reject(new Error(`join timeout for ${displayName}`)), 15000)
    ws.on('open', () => {
      const msg = { type: 'join', display_name: displayName }
      if (ownerToken) msg.owner_token = ownerToken
      ws.send(JSON.stringify(msg))
    })
    ws.on('message', (data) => {
      let m; try { m = JSON.parse(data.toString()) } catch { return }
      if (m.type === 'joined') { clearTimeout(timer); resolve(ws) }
      else if (m.type === 'join-refused') { clearTimeout(timer); reject(new Error(`join-refused: ${m.reason}`)) }
    })
    ws.on('error', (e) => { clearTimeout(timer); reject(e) })
    ws.on('close', (code) => { if (code === 4404) reject(new Error('room not found (4404)')) })
  })
}

// ----------------------------------------------------------- document socket
// Opens a doc socket, wires a Y.Doc to it, and (for the editor) sends local
// updates as sync messages. Returns handles used to edit and to read latency.
function openDoc(wsBase, roomId, docId, sessionId, isEditor) {
  return new Promise((resolve, reject) => {
    const doc = new Y.Doc()
    const probe = doc.getMap('probe')
    const ws = new WebSocket(`${wsBase}/ws/rooms/${roomId}/docs/${docId}?session_id=${sessionId}`)
    const timer = setTimeout(() => reject(new Error(`doc socket timeout (${docId})`)), 15000)

    // Editor: forward every LOCAL update to the relay as [0][2]<update>.
    if (isEditor) {
      doc.on('update', (update, origin) => {
        if (origin === 'remote' || ws.readyState !== WebSocket.OPEN) return
        const enc = encoding.createEncoder()
        encoding.writeVarUint(enc, MESSAGE_SYNC)
        syncProtocol.writeUpdate(enc, update)
        ws.send(encoding.toUint8Array(enc))
      })
    }

    const handle = { ws, doc, probe, latencies: [], onProbe: null }

    ws.on('open', () => { clearTimeout(timer); resolve(handle) })
    ws.on('message', (data) => {
      const bytes = new Uint8Array(data)
      if (bytes.length < 1) return
      const dec = decoding.createDecoder(bytes)
      const type = decoding.readVarUint(dec)
      if (type !== MESSAGE_SYNC) return // ignore awareness here
      const enc = encoding.createEncoder()
      encoding.writeVarUint(enc, MESSAGE_SYNC)
      // Applies incoming updates to doc with origin 'remote' (so the editor
      // guard above never re-sends them). May produce a reply (sync step 2).
      syncProtocol.readSyncMessage(dec, enc, doc, 'remote')
      if (encoding.length(enc) > 1 && ws.readyState === WebSocket.OPEN) {
        ws.send(encoding.toUint8Array(enc))
      }
    })
    ws.on('error', (e) => { clearTimeout(timer); reject(e) })

    // Reader latency: when the probe value changes, record now - sendTime[seq].
    probe.observe(() => {
      if (handle.onProbe) handle.onProbe(probe.get('seq'), probe.get('t'))
    })
  })
}

// --------------------------------------------------------------------- stats
function percentile(sorted, p) {
  if (sorted.length === 0) return null
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)
  return sorted[idx]
}
function summarise(latencies) {
  const s = [...latencies].sort((a, b) => a - b)
  return {
    samples: s.length,
    p50_ms: s.length ? Math.round(percentile(s, 50) * 100) / 100 : null,
    p95_ms: s.length ? Math.round(percentile(s, 95) * 100) / 100 : null,
    max_ms: s.length ? Math.round(s[s.length - 1] * 100) / 100 : null,
  }
}

// ----------------------------------------------------------------- main run
async function main() {
  const args = parseArgs(process.argv)
  const { variant, users, edits, intervalMs, url: httpBase, wsUrl: wsBase } = args
  console.log(`[harness] ${variant}  users=${users}  edits=${edits}  target=${httpBase}`)

  const room = await createRoom(httpBase, variant)
  console.log(`[harness] room ${room.room_id} (${room.mode}${room.preset ? '/' + room.preset : ''})`)

  // Build users. User 0 is the Host (owner token). Each has a session_id (UUID v4).
  const sessions = Array.from({ length: users }, () => randomUUID())
  const controls = []
  for (let i = 0; i < users; i++) {
    controls.push(await joinControl(wsBase, room.room_id, sessions[i], `user-${i}`,
      i === 0 ? room.owner_token : null))
  }
  console.log(`[harness] ${users} users joined`)

  // Pick the document targets per variant.
  let docTargets // [{ docId, editorIdx, readerIdxs }]
  if (variant === 'T1-O') {
    const moduleIds = []
    for (let m = 0; m < args.modules; m++) {
      moduleIds.push(await createApprovedModule(httpBase, room.room_id, sessions[0], room.owner_token, `module-${m}`))
    }
    console.log(`[harness] ${moduleIds.length} modules created and approved`)
    // Host (user 0) writes every module; the other users read, spread across modules.
    docTargets = moduleIds.map((docId, m) => ({
      docId, editorIdx: 0,
      readerIdxs: Array.from({ length: users }, (_, i) => i).filter((i) => i !== 0 && i % moduleIds.length === m),
    }))
  } else {
    const editors = variant === 'T1-A'
      ? Array.from({ length: users }, (_, i) => i) // everyone edits
      : [0]                                        // one editor (Host)
    docTargets = [{ docId: 'main', editorIdx: 0,
      readerIdxs: Array.from({ length: users }, (_, i) => i).filter((i) => i !== 0),
      allEditors: editors }]
  }

  // Open the document sockets. One Y.Doc per (user, doc).
  const probeHandles = [] // the editor->readers handle set we measure per doc
  const background = []   // extra editors that create load in T1-A
  for (const t of docTargets) {
    const editorHandle = await openDoc(wsBase, room.room_id, t.docId, sessions[t.editorIdx], true)
    const readerHandles = []
    for (const r of t.readerIdxs) {
      readerHandles.push(await openDoc(wsBase, room.room_id, t.docId, sessions[r], false))
    }
    probeHandles.push({ editor: editorHandle, readers: readerHandles })
    // T1-A: the non-probe users also edit the same doc (load). They share the
    // doc socket we already opened for reading? No — they need their own editor
    // socket. Open an editor doc socket for each extra editor.
    if (variant === 'T1-A') {
      for (const e of t.allEditors) {
        if (e === t.editorIdx) continue
        background.push(await openDoc(wsBase, room.room_id, t.docId, sessions[e], true))
      }
    }
  }
  await sleep(300) // let sockets settle / initial sync exchange

  // Latency collection: wire reader callbacks.
  const sendTimes = new Map() // seq -> t_send (ms)
  const allLatencies = []
  for (const { readers } of probeHandles) {
    for (const h of readers) {
      h.onProbe = (seq, t) => {
        if (typeof seq !== 'number') return
        const sent = sendTimes.get(seq)
        if (sent !== undefined) allLatencies.push(nowMs() - sent)
      }
    }
  }

  // Background load for T1-A: each extra editor bumps its own 'load' counter.
  let loadTimer
  if (variant === 'T1-A' && background.length) {
    let n = 0
    loadTimer = setInterval(() => {
      for (const h of background) h.doc.getMap('load').set('n', n)
      n++
    }, intervalMs)
  }

  // Probe: the designated editor of each doc sets probe={seq,t}. Readers observe.
  let seq = 0
  for (let e = 0; e < edits; e++) {
    for (const { editor } of probeHandles) {
      const t = nowMs()
      sendTimes.set(seq, t)
      editor.doc.transact(() => { editor.probe.set('seq', seq); editor.probe.set('t', t) })
      seq++
    }
    await sleep(intervalMs)
  }
  await sleep(1000) // drain in-flight receipts
  if (loadTimer) clearInterval(loadTimer)

  // NFR15 / ROOM_FULL (US-63): attempt one extra join beyond the user count and
  // record what happens. A people limit is NOT enforced in main yet (US-63), so
  // this is reported honestly, not faked.
  let roomFull
  try {
    const extra = await joinControl(wsBase, room.room_id, randomUUID(), `user-extra`, null)
    extra.close()
    roomFull = { attempted: true, refused: false, note: 'no people limit enforced yet (US-63); extra join was accepted' }
  } catch (e) {
    roomFull = { attempted: true, refused: true, error: String(e.message || e) }
  }

  // Tear down.
  for (const { editor, readers } of probeHandles) { editor.ws.close(); readers.forEach((h) => h.ws.close()) }
  background.forEach((h) => h.ws.close())
  controls.forEach((ws) => ws.close())

  const stats = summarise(allLatencies)
  const NFR1_TARGET_P95_MS = 200 // NFR1 (Requirements v2): < 200 ms at the 95th percentile.
  const result = {
    variant, users, edits, measured_samples: stats.samples,
    latency_ms: stats,
    nfr1_target_p95_ms: NFR1_TARGET_P95_MS,
    nfr1_met: stats.p95_ms != null ? stats.p95_ms < NFR1_TARGET_P95_MS : null,
    limits_in_force: {
      people_limit: 'not enforced yet (US-63); NFR2 design target is 10 users',
      module_limit: 'not enforced yet (US-63)',
      module_size_limit: 'document frame cap 1 MiB (DocumentHandler), per-module content limit is US-63',
    },
    room_full_check: roomFull,
    write_permission_check: 'active (NFR13/CR13): every document-change frame is permission-checked by the relay. A without-check baseline needs a server-side toggle (not in main); see README.',
    machine: { hostname: os.hostname(), platform: `${os.type()} ${os.release()}`, arch: os.arch(),
               cpus: os.cpus().length, node: process.version },
    ran_at: new Date().toISOString(),
    note: 'Development-machine run. The final-host run (US-31) re-runs this with --url pointing at the deployed sync-service.',
  }

  console.log(`[harness] samples=${stats.samples}  p50=${stats.p50_ms}ms  p95=${stats.p95_ms}ms  (NFR1 target <${NFR1_TARGET_P95_MS}ms)`)
  if (args.out) {
    mkdirSync(dirname(args.out), { recursive: true })
    writeFileSync(args.out, JSON.stringify(result, null, 2))
    console.log(`[harness] results -> ${args.out}`)
  } else {
    console.log(JSON.stringify(result, null, 2))
  }
  // Give sockets a moment to close, then exit.
  await sleep(200)
  process.exit(0)
}

main().catch((e) => { console.error('[harness] FAILED:', e.message || e); process.exit(1) })
