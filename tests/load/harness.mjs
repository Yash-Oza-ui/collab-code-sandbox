// T-22 load-test harness — test set-up T1 (T1-P, T1-A, T1-O).
//
// Simulates up to 10 users in one room over the REAL control + document sockets,
// typing REAL Yjs text at the T1 rate, and measures how long each edit takes to
// reach the other users (NFR1: < 200 ms at the 95th percentile).
//
// Test set-up T1 (Requirements v2, below the NFR table):
//   10 users, a document of ~1,000 lines, each typist ~5 characters/second.
//   T1-P  Pair/teach_interview — 1 types, 9 read.
//   T1-A  Pair/debug_together  — all 10 type in one file; every typist measured.
//   T1-O  Open — 5 modules of ~200 lines; 5 owners each type in THEIR module
//         (owner via the real module-request + Host-approve flow), 5 read ALL 5.
//
// All users run in ONE process, so one monotonic clock times send -> receive.
// Every edit is a Yjs transaction that appends a character AND stamps a probe
// (editor, seq, sendMs) the receivers read back, so EVERY typist is measured.
// No real user data or real code — generated lines only.
//
//   node harness.mjs --variant T1-A --users 10 --edits 50 --out results/T1-A.json
import * as Y from 'yjs'
import * as syncProtocol from 'y-protocols/sync'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'
import WebSocket from 'ws'
import { randomUUID } from 'node:crypto'
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import os from 'node:os'

const MESSAGE_SYNC = 0
const nowMs = () => Number(process.hrtime.bigint() / 1000n) / 1000
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function parseArgs(argv) {
  const a = { variant: 'T1-P', users: 10, edits: 50, intervalMs: 200, modules: 5,
              seedLines: 1000, url: 'http://localhost:8081', wsUrl: null, out: null, label: null, editCheck: null }
  for (let i = 2; i < argv.length; i++) {
    const [k, inl] = argv[i].split('='); const v = inl !== undefined ? inl : argv[i + 1]
    const take = () => { if (inl === undefined) i++ }
    switch (k) {
      case '--variant': a.variant = v; take(); break
      case '--users': a.users = Number(v); take(); break
      case '--edits': a.edits = Number(v); take(); break
      case '--interval': a.intervalMs = Number(v); take(); break // 200ms => ~5 chars/sec
      case '--modules': a.modules = Number(v); take(); break
      case '--seed-lines': a.seedLines = Number(v); take(); break
      case '--url': a.url = v; take(); break
      case '--ws': a.wsUrl = v; take(); break
      case '--out': a.out = v; take(); break
      case '--label': a.label = v; take(); break
      case '--edit-check': a.editCheck = v; take(); break // 'on' | 'off' (validated against the server)
      default: break
    }
  }
  if (!['T1-P', 'T1-A', 'T1-O'].includes(a.variant)) throw new Error(`bad --variant '${a.variant}'`)
  if (!a.wsUrl) a.wsUrl = a.url.replace(/^http/, 'ws')
  return a
}

// A line of generated, made-up "code" content (never real user data).
const lineFor = (n) => `line ${n}: const v${n} = step(${n}) + accumulate(${(n * 37) % 1000});\n`
function generateDoc(lines) { let s = ''; for (let i = 1; i <= lines; i++) s += lineFor(i); return s }

async function createRoom(httpBase, variant) {
  const body = variant === 'T1-O'
    ? { mode: 'open', language: 'python' }
    : { mode: 'pair', preset: variant === 'T1-A' ? 'debug_together' : 'teach_interview' }
  const res = await fetch(`${httpBase}/v1/rooms`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  if (res.status !== 201) throw new Error(`POST /v1/rooms -> ${res.status}: ${await res.text()}`)
  return res.json()
}

// The real module flow: the REQUESTER owns the module (FR21.1-21.3); the Host approves it.
async function requestModule(httpBase, roomId, requesterSessionId, name) {
  const r = await fetch(`${httpBase}/v1/rooms/${roomId}/modules`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Session-Id': requesterSessionId },
    body: JSON.stringify({ name }) })
  if (r.status !== 202) throw new Error(`request module -> ${r.status}: ${await r.text()}`)
  return (await r.json()).module_id
}
async function approveModule(httpBase, roomId, moduleId, hostSessionId, ownerToken) {
  const r = await fetch(`${httpBase}/v1/rooms/${roomId}/modules/${moduleId}/approve`, {
    method: 'POST', headers: { 'X-Session-Id': hostSessionId, 'X-Owner-Token': ownerToken } })
  if (!r.ok) throw new Error(`approve module -> ${r.status}: ${await r.text()}`)
}

function joinControl(wsBase, roomId, sessionId, displayName, ownerToken) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${wsBase}/ws/rooms/${roomId}?session_id=${sessionId}`)
    const timer = setTimeout(() => reject(new Error(`join timeout ${displayName}`)), 15000)
    ws.on('open', () => {
      const m = { type: 'join', display_name: displayName }; if (ownerToken) m.owner_token = ownerToken
      ws.send(JSON.stringify(m))
    })
    ws.on('message', (d) => { let m; try { m = JSON.parse(d.toString()) } catch { return }
      if (m.type === 'joined') { clearTimeout(timer); resolve(ws) }
      else if (m.type === 'join-refused') { clearTimeout(timer); reject(new Error(`join-refused ${m.reason}`)) } })
    ws.on('error', (e) => { clearTimeout(timer); reject(e) })
  })
}

// A (user, doc) endpoint: a Y.Doc wired to a document socket. Editors forward
// local updates; everyone applies remote updates and reads the probe map.
function openDoc(wsBase, roomId, docId, sessionId, isEditor, myEditorKey, onProbe) {
  return new Promise((resolve, reject) => {
    const doc = new Y.Doc()
    const text = doc.getText('code')
    const probe = doc.getMap('probe')
    const ws = new WebSocket(`${wsBase}/ws/rooms/${roomId}/docs/${docId}?session_id=${sessionId}`)
    const timer = setTimeout(() => reject(new Error(`doc socket timeout ${docId}`)), 15000)

    if (isEditor) {
      doc.on('update', (update, origin) => {
        if (origin === 'remote' || ws.readyState !== WebSocket.OPEN) return
        const enc = encoding.createEncoder()
        encoding.writeVarUint(enc, MESSAGE_SYNC); syncProtocol.writeUpdate(enc, update)
        ws.send(encoding.toUint8Array(enc))
      })
    }
    ws.on('open', () => { clearTimeout(timer); resolve({ ws, doc, text, probe }) })
    ws.on('message', (data) => {
      const bytes = new Uint8Array(data); if (!bytes.length) return
      const dec = decoding.createDecoder(bytes)
      if (decoding.readVarUint(dec) !== MESSAGE_SYNC) return
      const enc = encoding.createEncoder(); encoding.writeVarUint(enc, MESSAGE_SYNC)
      syncProtocol.readSyncMessage(dec, enc, doc, 'remote')
      if (encoding.length(enc) > 1 && ws.readyState === WebSocket.OPEN) ws.send(encoding.toUint8Array(enc))
    })
    ws.on('error', (e) => { clearTimeout(timer); reject(e) })

    probe.observe((event) => {
      // Measure ONLY the keys changed by this update, not a re-scan of all keys.
      event.keysChanged.forEach((key) => {
        if (key === myEditorKey) return // never measure your own edits
        const val = probe.get(key)
        if (val == null) return
        const [seq, sentMs] = String(val).split(':')
        onProbe(key, Number(seq), Number(sentMs))
      })
    })
  })
}

function percentile(sorted, p) {
  if (!sorted.length) return null
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)]
}
function summarise(lat) {
  const s = [...lat].sort((a, b) => a - b); const r = (x) => x == null ? null : Math.round(x * 100) / 100
  return { samples: s.length, p50_ms: r(percentile(s, 50)), p95_ms: r(percentile(s, 95)), max_ms: r(s[s.length - 1]) }
}

// Opens a raw document socket (no Yjs wiring) for the edit-check probe.
function openRawDoc(wsBase, roomId, docId, sessionId) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${wsBase}/ws/rooms/${roomId}/docs/${docId}?session_id=${sessionId}`)
    const timer = setTimeout(() => reject(new Error('probe doc socket timeout')), 10000)
    ws.on('open', () => { clearTimeout(timer); resolve(ws) })
    ws.on('error', (e) => { clearTimeout(timer); reject(e) })
  })
}

// Ground-truth of the server's edit-permission check (NFR13/CR13). In a Pair
// teach_interview room only the Host may edit, so a NON-Host participant's
// document change is forwarded to the Host ONLY when the check is disabled.
// Returns 'on' (check enforced) or 'off' (check bypassed on the server).
async function probeEditCheck(httpBase, wsBase) {
  const room = await createRoom(httpBase, 'T1-P') // pair / teach_interview
  const hostSess = randomUUID(), partSess = randomUUID()
  const hostCtrl = await joinControl(wsBase, room.room_id, hostSess, 'probe-host', room.owner_token)
  const partCtrl = await joinControl(wsBase, room.room_id, partSess, 'probe-part', null) // non-Host: cannot edit
  const hostDoc = await openRawDoc(wsBase, room.room_id, 'main', hostSess)
  const partDoc = await openRawDoc(wsBase, room.room_id, 'main', partSess)
  let hostReceived = false
  hostDoc.on('message', (data) => {
    const b = new Uint8Array(data)
    if (b.length >= 2 && b[0] === MESSAGE_SYNC && b[1] === 2) hostReceived = true // sync-update forwarded
  })
  await sleep(300)
  const ydoc = new Y.Doc(); ydoc.getText('code').insert(0, 'probe')
  const enc = encoding.createEncoder()
  encoding.writeVarUint(enc, MESSAGE_SYNC)
  syncProtocol.writeUpdate(enc, Y.encodeStateAsUpdate(ydoc))
  partDoc.send(encoding.toUint8Array(enc))
  await sleep(800)
  hostDoc.close(); partDoc.close(); hostCtrl.close(); partCtrl.close()
  return hostReceived ? 'off' : 'on'
}

async function main() {
  const args = parseArgs(process.argv)
  const { variant, users, edits, intervalMs, seedLines, url: httpBase, wsUrl: wsBase } = args

  // Verify the SERVER's actual edit-check mode (NFR13/CR13) behaviourally, not from
  // this process's env: a non-Host edit in a teach_interview probe room reaches the
  // Host only when the check is OFF. The report states this VERIFIED server mode.
  const serverEditCheck = await probeEditCheck(httpBase, wsBase) // 'on' | 'off'
  if (args.editCheck && args.editCheck !== serverEditCheck) {
    throw new Error(`--edit-check=${args.editCheck} but the sync-service actually has the edit check ${serverEditCheck.toUpperCase()}. `
      + `For a CR13 OFF baseline start the server with -Dspring.profiles.active=loadtest -Dapp.loadtest.edit-check-disabled=true; otherwise run it normally (ON). Refusing to write a mislabeled report.`)
  }
  console.log(`[harness] ${variant} users=${users} edits=${edits} interval=${intervalMs}ms editCheck=${serverEditCheck.toUpperCase()}(verified) target=${httpBase}`)

  const room = await createRoom(httpBase, variant)
  const sessions = Array.from({ length: users }, () => randomUUID())
  const controls = []
  for (let i = 0; i < users; i++) {
    controls.push(await joinControl(wsBase, room.room_id, sessions[i], `user-${i}`, i === 0 ? room.owner_token : null))
  }
  console.log(`[harness] room ${room.room_id} (${room.mode}${room.preset ? '/' + room.preset : ''}); ${users} users joined`)

  // Build the documents and the editor/reader topology per variant.
  // docs: [{ docId, text, seed, editors:[{idx,key,text,probe}], readers:[{idx,...}] }]
  const docs = []
  const latencies = []
  const onProbe = () => {} // replaced per endpoint below (closure records latency)

  function wireProbe(key) { // returns an onProbe that records latency against now
    return (_k, _seq, sentMs) => { if (Number.isFinite(sentMs)) latencies.push(nowMs() - sentMs) }
  }

  if (variant === 'T1-O') {
    // 5 owners (users 0..4) each request one module; Host (user 0) approves all.
    const ext = { python: '.py', cpp: '.cpp', c: '.c', java: '.java', javascript: '.js' }[room.language] || '.py'
    const owners = Array.from({ length: Math.min(args.modules, users) }, (_, i) => i)
    const moduleIds = []
    for (const o of owners) moduleIds.push(await requestModule(httpBase, room.room_id, sessions[o], `module-${o}${ext}`))
    for (const id of moduleIds) await approveModule(httpBase, room.room_id, id, sessions[0], room.owner_token)
    console.log(`[harness] ${moduleIds.length} modules requested by 5 owners and approved by Host`)
    const readers = Array.from({ length: users }, (_, i) => i).filter((i) => !owners.includes(i))
    for (let m = 0; m < moduleIds.length; m++) {
      const docId = moduleIds[m], ownerIdx = owners[m], key = `e${ownerIdx}`
      const ownerEp = await openDoc(wsBase, room.room_id, docId, sessions[ownerIdx], true, key, wireProbe(key))
      const readerEps = []
      for (const r of readers) readerEps.push(await openDoc(wsBase, room.room_id, docId, sessions[r], false, `e${r}`, wireProbe(`e${r}`)))
      docs.push({ docId, seedLines: Math.round(seedLines / moduleIds.length) || 200, editors: [{ idx: ownerIdx, key, ep: ownerEp }], readerEps })
    }
  } else {
    // Pair mode: one shared document "main". T1-P: user 0 types. T1-A: all type.
    const editors = variant === 'T1-A' ? Array.from({ length: users }, (_, i) => i) : [0]
    const readers = Array.from({ length: users }, (_, i) => i).filter((i) => !editors.includes(i))
    const eps = {} // idx -> endpoint
    for (const e of editors) eps[e] = await openDoc(wsBase, room.room_id, 'main', sessions[e], true, `e${e}`, wireProbe(`e${e}`))
    for (const r of readers) eps[r] = await openDoc(wsBase, room.room_id, 'main', sessions[r], false, `e${r}`, wireProbe(`e${r}`))
    docs.push({ docId: 'main', seedLines, editors: editors.map((idx) => ({ idx, key: `e${idx}`, ep: eps[idx] })),
                readerEps: readers.map((r) => eps[r]) })
  }
  await sleep(300)

  // Seed each document to ~the target size (one owner seeds per doc), then let it sync.
  for (const d of docs) {
    const seeder = d.editors[0].ep
    seeder.doc.transact(() => seeder.text.insert(0, generateDoc(d.seedLines)))
  }
  await sleep(1500) // allow the seed to propagate to all readers before measuring
  console.log(`[harness] documents seeded (~${seedLines} lines total); measuring...`)

  // Measured typing: every editor types `edits` characters at ~5/sec, each a Yjs
  // transaction that appends a char AND stamps probe[eKey] = "seq:sendMs".
  const typistCount = docs.reduce((n, d) => n + d.editors.length, 0)
  const tasks = []
  for (const d of docs) {
    for (const ed of d.editors) {
      tasks.push((async () => {
        for (let seq = 0; seq < edits; seq++) {
          const sendMs = nowMs()
          ed.ep.doc.transact(() => {
            ed.ep.text.insert(ed.ep.text.length, '.')
            ed.ep.probe.set(ed.key, `${seq}:${sendMs}`)
          })
          await sleep(intervalMs)
        }
      })())
    }
  }
  await Promise.all(tasks)
  await sleep(1000) // drain in-flight receipts

  // NFR15 / ROOM_FULL (US-63) — attempt one extra join beyond the user count.
  let roomFull
  try {
    const extra = await joinControl(wsBase, room.room_id, randomUUID(), 'user-extra', null)
    extra.close()
    roomFull = { status: 'PENDING_US-63', refused: false, note: 'People limit not enforced in main yet (US-63). Extra join was accepted; once US-63 lands this records a ROOM_FULL refusal.' }
  } catch (e) {
    roomFull = { status: /ROOM_FULL/.test(String(e.message)) ? 'ENFORCED' : 'REFUSED_OTHER', refused: true, reason: String(e.message) }
  }

  for (const d of docs) { d.editors.forEach((e) => e.ep.ws.close()); d.readerEps.forEach((r) => r.ws.close()) }
  controls.forEach((ws) => ws.close())

  const stats = summarise(latencies)
  const NFR1 = 200
  const result = {
    kind: args.label || 'benchmark',
    variant, users, typists: typistCount, edits_per_typist: edits, typing_interval_ms: intervalMs,
    seed_lines: seedLines,
    edit_check: serverEditCheck === 'off' ? 'disabled (CR13 baseline, test-only) — verified against the server' : 'enabled (NFR13) — verified against the server',
    edit_check_mode: serverEditCheck,
    latency_ms: stats, nfr1_target_p95_ms: NFR1, nfr1_met: stats.p95_ms == null ? null : stats.p95_ms < NFR1,
    room_full_check: roomFull,
    limits_in_force: { people_limit: 'US-63 (not enforced in main)', module_limit: 'US-63', module_size_limit: '1 MiB document frame cap (DocumentHandler); per-module content limit is US-63' },
    workload_note: 'Test set-up T1: ~5 chars/sec per typist, ~1000-line document (T1-O: 5 modules x ~200 lines). Every typist is measured at the receiving users.',
    machine: { hostname: os.hostname(), platform: `${os.type()} ${os.release()}`, arch: os.arch(), cpus: os.cpus().length, node: process.version },
    ran_at: new Date().toISOString(),
    disclaimer: 'Development-machine run (CR03). Final-host numbers are US-31: re-run with --url pointing at the deployed sync-service.',
  }
  console.log(`[harness] ${variant} typists=${typistCount} samples=${stats.samples} p50=${stats.p50_ms}ms p95=${stats.p95_ms}ms max=${stats.max_ms}ms (NFR1<${NFR1}ms)`)
  if (args.out) { mkdirSync(dirname(args.out), { recursive: true }); writeFileSync(args.out, JSON.stringify(result, null, 2)); console.log(`[harness] -> ${args.out}`) }
  else console.log(JSON.stringify(result, null, 2))
  await sleep(200); process.exit(0)
}

main().catch((e) => { console.error('[harness] FAILED:', e.message || e); process.exit(1) })
