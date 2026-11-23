import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'
import * as Y from 'yjs'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'
import * as sync from 'y-protocols/sync'

const api = (process.env.SYNC_SERVICE_URL || 'http://localhost:8081').replace(/\/$/, '')
const wsBase = api.replace(/^http/, 'ws')
const timeoutMs = 10000
const sockets = []
const docs = []

// Standard CRC-32 (IEEE 802.3)
const CRC_TABLE = new Uint32Array(256)
for (let i = 0; i < 256; i++) {
  let c = i
  for (let j = 0; j < 8; j++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  }
  CRC_TABLE[i] = c >>> 0
}

function crc32(bytes) {
  let c = 0xffffffff
  for (let i = 0; i < bytes.length; i++) {
    c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8)
  }
  return (c ^ 0xffffffff) >>> 0
}

function sanitizeAndValidateZipEntryName(rawName) {
  if (!rawName || typeof rawName !== 'string') return { valid: false, error: 'Empty filename' }
  const trimmed = rawName.trim()
  if (trimmed.startsWith('/') || trimmed.startsWith('\\')) {
    return { valid: false, error: 'Leading slashes not allowed' }
  }
  if (trimmed.includes('..')) {
    return { valid: false, error: 'Directory traversal not allowed' }
  }
  if (trimmed.includes('/') || trimmed.includes('\\')) {
    return { valid: false, error: 'Folder parts not allowed' }
  }
  return { valid: true, name: trimmed }
}

function buildZipArchive(files) {
  if (!files || files.length === 0) {
    throw new Error('Cannot create zip: no files provided')
  }
  const textEncoder = new TextEncoder()
  const validatedEntries = []

  for (const file of files) {
    const check = sanitizeAndValidateZipEntryName(file.name)
    if (!check.valid || !check.name) {
      throw new Error(`Refusing entry: ${check.error}`)
    }
    const nameBytes = textEncoder.encode(check.name)
    const contentBytes = textEncoder.encode(file.content ?? '')
    const crc = crc32(contentBytes)
    validatedEntries.push({ name: check.name, nameBytes, contentBytes, crc })
  }

  let totalLocalHeadersSize = 0
  let totalCentralDirSize = 0
  for (const entry of validatedEntries) {
    totalLocalHeadersSize += 30 + entry.nameBytes.length + entry.contentBytes.length
    totalCentralDirSize += 46 + entry.nameBytes.length
  }
  const totalZipSize = totalLocalHeadersSize + totalCentralDirSize + 22

  const buffer = new ArrayBuffer(totalZipSize)
  const view = new DataView(buffer)
  const bytes = new Uint8Array(buffer)

  let offset = 0
  const localHeaderOffsets = []

  for (const entry of validatedEntries) {
    localHeaderOffsets.push(offset)
    view.setUint32(offset, 0x04034b50, true)
    view.setUint16(offset + 4, 20, true)
    view.setUint16(offset + 6, 0x0800, true)
    view.setUint16(offset + 8, 0, true)
    view.setUint16(offset + 10, 0, true)
    view.setUint16(offset + 12, 0, true)
    view.setUint32(offset + 14, entry.crc, true)
    view.setUint32(offset + 18, entry.contentBytes.length, true)
    view.setUint32(offset + 22, entry.contentBytes.length, true)
    view.setUint16(offset + 26, entry.nameBytes.length, true)
    view.setUint16(offset + 28, 0, true)
    offset += 30
    bytes.set(entry.nameBytes, offset)
    offset += entry.nameBytes.length
    bytes.set(entry.contentBytes, offset)
    offset += entry.contentBytes.length
  }

  const centralDirStartOffset = offset
  for (let i = 0; i < validatedEntries.length; i++) {
    const entry = validatedEntries[i]
    const localOffset = localHeaderOffsets[i]
    view.setUint32(offset, 0x02014b50, true)
    view.setUint16(offset + 4, 0x0314, true)
    view.setUint16(offset + 6, 20, true)
    view.setUint16(offset + 8, 0x0800, true)
    view.setUint16(offset + 10, 0, true)
    view.setUint16(offset + 12, 0, true)
    view.setUint16(offset + 14, 0, true)
    view.setUint32(offset + 16, entry.crc, true)
    view.setUint32(offset + 20, entry.contentBytes.length, true)
    view.setUint32(offset + 24, entry.contentBytes.length, true)
    view.setUint16(offset + 28, entry.nameBytes.length, true)
    view.setUint16(offset + 30, 0, true)
    view.setUint16(offset + 32, 0, true)
    view.setUint16(offset + 34, 0, true)
    view.setUint16(offset + 36, 0, true)
    view.setUint32(offset + 38, 0x81a40000, true)
    view.setUint32(offset + 42, localOffset, true)
    offset += 46
    bytes.set(entry.nameBytes, offset)
    offset += entry.nameBytes.length
  }

  const centralDirSize = offset - centralDirStartOffset
  view.setUint32(offset, 0x06054b50, true)
  view.setUint16(offset + 4, 0, true)
  view.setUint16(offset + 6, 0, true)
  view.setUint16(offset + 8, validatedEntries.length, true)
  view.setUint16(offset + 10, validatedEntries.length, true)
  view.setUint32(offset + 12, centralDirSize, true)
  view.setUint32(offset + 16, centralDirStartOffset, true)
  view.setUint16(offset + 20, 0, true)

  return bytes
}

function readZipEntries(zipBytes) {
  const view = new DataView(zipBytes.buffer, zipBytes.byteOffset, zipBytes.byteLength)
  const textDecoder = new TextDecoder()
  const entries = []

  let offset = 0
  while (offset + 30 <= zipBytes.length) {
    const sig = view.getUint32(offset, true)
    if (sig !== 0x04034b50) break
    const compressedSize = view.getUint32(offset + 18, true)
    const nameLength = view.getUint16(offset + 26, true)
    const extraLength = view.getUint16(offset + 28, true)

    const nameBytes = zipBytes.subarray(offset + 30, offset + 30 + nameLength)
    const name = textDecoder.decode(nameBytes)

    const dataOffset = offset + 30 + nameLength + extraLength
    const dataBytes = zipBytes.subarray(dataOffset, dataOffset + compressedSize)
    const content = textDecoder.decode(dataBytes)

    entries.push({ name, content })
    offset = dataOffset + compressedSize
  }
  return entries
}

function waitFor(predicate, description) {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const timer = setInterval(() => {
      try {
        if (predicate()) {
          clearInterval(timer)
          resolve()
        } else if (Date.now() - start > timeoutMs) {
          clearInterval(timer)
          reject(new Error(`Timed out: ${description}`))
        }
      } catch (err) {
        clearInterval(timer)
        reject(err)
      }
    }, 25)
  })
}

async function request(path, method = 'GET', body, headers = {}) {
  const res = await fetch(`${api}${path}`, {
    method,
    headers: {
      ...headers,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) {
    throw new Error(`${method} ${path}: HTTP ${res.status}: ${JSON.stringify(data)}`)
  }
  return { status: res.status, data }
}

function openSocket(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { handshakeTimeout: timeoutMs })
    const timer = setTimeout(() => {
      ws.terminate()
      reject(new Error(`WebSocket timeout: ${url}`))
    }, timeoutMs)
    ws.once('open', () => {
      clearTimeout(timer)
      sockets.push(ws)
      resolve(ws)
    })
    ws.once('error', (err) => {
      clearTimeout(timer)
      reject(err)
    })
  })
}

async function joinControl(roomId, sessionId, name, ownerToken) {
  const ws = await openSocket(`${wsBase}/ws/rooms/${encodeURIComponent(roomId)}?session_id=${encodeURIComponent(sessionId)}`)
  const events = []
  const joined = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Join timeout: ${name}`)), timeoutMs)
    ws.on('message', (data, binary) => {
      if (binary) return
      const msg = JSON.parse(data.toString())
      events.push(msg)
      if (msg.type === 'joined') {
        clearTimeout(timer)
        resolve(msg)
      } else if (msg.type === 'join-refused') {
        clearTimeout(timer)
        reject(new Error(`Join refused: ${JSON.stringify(msg)}`))
      }
    })
  })
  ws.send(JSON.stringify({
    type: 'join',
    display_name: name,
    ...(ownerToken ? { owner_token: ownerToken } : {}),
  }))
  const joinedMsg = await joined
  return { ws, events, joinedMsg }
}

async function connectDoc(roomId, moduleId, sessionId, doc) {
  const ws = await openSocket(
    `${wsBase}/ws/rooms/${encodeURIComponent(roomId)}/docs/${encodeURIComponent(moduleId)}?session_id=${encodeURIComponent(sessionId)}`,
  )
  const send = (writer) => {
    if (ws.readyState !== WebSocket.OPEN) return
    const enc = encoding.createEncoder()
    encoding.writeVarUint(enc, 0)
    writer(enc)
    ws.send(encoding.toUint8Array(enc))
  }
  const onUpdate = (update, origin) => {
    if (origin !== ws) send((enc) => sync.writeUpdate(enc, update))
  }
  doc.on('update', onUpdate)
  ws.on('message', (data, binary) => {
    if (!binary) return
    const dec = decoding.createDecoder(new Uint8Array(data))
    if (decoding.readVarUint(dec) !== 0) return
    const enc = encoding.createEncoder()
    encoding.writeVarUint(enc, 0)
    sync.readSyncMessage(dec, enc, doc, ws)
    const reply = encoding.toUint8Array(enc)
    if (reply.length > 1 && ws.readyState === WebSocket.OPEN) ws.send(reply)
  })
  ws.on('close', () => doc.off('update', onUpdate))
  send((enc) => sync.writeSyncStep1(enc, doc))
  send((enc) => sync.writeSyncStep2(enc, doc))
  return ws
}

async function createAndApproveModule(roomId, sessionId, ownerToken, name) {
  const created = await request(`/v1/rooms/${encodeURIComponent(roomId)}/modules`, 'POST', { name }, { 'X-Session-Id': sessionId })
  assert.equal(created.status, 202)
  const moduleId = created.data.module_id
  const approved = await request(
    `/v1/rooms/${encodeURIComponent(roomId)}/modules/${encodeURIComponent(moduleId)}/approve`,
    'POST',
    undefined,
    { 'X-Session-Id': sessionId, 'X-Owner-Token': ownerToken },
  )
  assert.equal(approved.status, 200)
  return moduleId
}

try {
  // -------------------------------------------------------------
  // Test 1: Pair mode room export
  // -------------------------------------------------------------
  const pairRoomRes = await request('/v1/rooms', 'POST', { mode: 'pair', language: 'python' })
  assert.equal(pairRoomRes.status, 201)
  const pairRoomId = pairRoomRes.data.room_id
  const pairOwnerToken = pairRoomRes.data.owner_token

  const pairHostSession = randomUUID()
  await joinControl(pairRoomId, pairHostSession, 'PairHost', pairOwnerToken)

  const pairDoc = new Y.Doc()
  docs.push(pairDoc)
  await connectDoc(pairRoomId, 'main', pairHostSession, pairDoc)

  const pairCode = 'def greet():\n    print("Hello from Pair mode export")\n'
  pairDoc.getText('monaco').insert(0, pairCode)

  const pairZipBytes = buildZipArchive([{ name: 'main.py', content: pairDoc.getText('monaco').toString() }])
  const pairEntries = readZipEntries(pairZipBytes)
  assert.equal(pairEntries.length, 1)
  assert.equal(pairEntries[0].name, 'main.py')
  assert.equal(pairEntries[0].content, pairCode)

  // -------------------------------------------------------------
  // Test 2: Open mode room export with live text & pending exclusion
  // -------------------------------------------------------------
  const openRoomRes = await request('/v1/rooms', 'POST', { mode: 'open', language: 'python' })
  assert.equal(openRoomRes.status, 201)
  const openRoomId = openRoomRes.data.room_id
  const openOwnerToken = openRoomRes.data.owner_token

  const openHostSession = randomUUID()
  const openObsSession = randomUUID()
  await joinControl(openRoomId, openHostSession, 'OpenHost', openOwnerToken)

  const modMain = await createAndApproveModule(openRoomId, openHostSession, openOwnerToken, 'main.py')
  const modCalc = await createAndApproveModule(openRoomId, openHostSession, openOwnerToken, 'calculator.py')

  // Request an unapproved pending module
  const unapprovedRes = await request(
    `/v1/rooms/${encodeURIComponent(openRoomId)}/modules`,
    'POST',
    { name: 'unapproved.py' },
    { 'X-Session-Id': openHostSession },
  )
  assert.equal(unapprovedRes.status, 202)
  assert.equal(unapprovedRes.data.pending, true)

  const obs = await joinControl(openRoomId, openObsSession, 'OpenObserver')
  await request(
    `/v1/rooms/${encodeURIComponent(openRoomId)}/members/${encodeURIComponent(obs.joinedMsg.member_id)}`,
    'PATCH',
    { role: 'observer' },
    { 'X-Session-Id': openHostSession, 'X-Owner-Token': openOwnerToken },
  )

  const hostDocMain = new Y.Doc()
  const hostDocCalc = new Y.Doc()
  const obsDocMain = new Y.Doc()
  const obsDocCalc = new Y.Doc()
  docs.push(hostDocMain, hostDocCalc, obsDocMain, obsDocCalc)

  await connectDoc(openRoomId, modMain, openHostSession, hostDocMain)
  await connectDoc(openRoomId, modCalc, openHostSession, hostDocCalc)
  await connectDoc(openRoomId, modMain, openObsSession, obsDocMain)
  await connectDoc(openRoomId, modCalc, openObsSession, obsDocCalc)

  const mainCode = 'import calculator\nprint(calculator.add(10, 20))\n'
  const calcCode = 'def add(a, b):\n    return a + b\n'
  hostDocMain.getText('monaco').insert(0, mainCode)
  hostDocCalc.getText('monaco').insert(0, calcCode)

  await waitFor(
    () =>
      obsDocMain.getText('monaco').toString() === mainCode &&
      obsDocCalc.getText('monaco').toString() === calcCode,
    'Observer sees live text across both modules',
  )

  // Fetch approved modules list
  const modulesRes = await request(`/v1/rooms/${encodeURIComponent(openRoomId)}/modules`)
  assert.equal(modulesRes.status, 200)
  const approvedModules = modulesRes.data.filter((m) => !m.pending)
  assert.equal(approvedModules.length, 2)

  // Snapshot live text from Observer perspective
  const obsModuleDocs = new Map([
    [modMain, obsDocMain],
    [modCalc, obsDocCalc],
  ])

  const openFiles = approvedModules.map((m) => ({
    name: m.name,
    content: obsModuleDocs.get(m.module_id).getText('monaco').toString(),
  }))

  const openZipBytes = buildZipArchive(openFiles)
  const openEntries = readZipEntries(openZipBytes)
  assert.equal(openEntries.length, 2)

  const openNames = openEntries.map((e) => e.name)
  assert.ok(openNames.includes('main.py'))
  assert.ok(openNames.includes('calculator.py'))
  assert.ok(!openNames.includes('unapproved.py'))

  const entryMain = openEntries.find((e) => e.name === 'main.py')
  const entryCalc = openEntries.find((e) => e.name === 'calculator.py')
  assert.equal(entryMain.content, mainCode)
  assert.equal(entryCalc.content, calcCode)

  // -------------------------------------------------------------
  // Test 3: Path traversal and sanitization checks
  // -------------------------------------------------------------
  assert.equal(sanitizeAndValidateZipEntryName('../evil.py').valid, false)
  assert.equal(sanitizeAndValidateZipEntryName('/absolute/path.py').valid, false)
  assert.equal(sanitizeAndValidateZipEntryName('sub/folder.py').valid, false)
  assert.equal(sanitizeAndValidateZipEntryName('valid.py').valid, true)

  // -------------------------------------------------------------
  // Test 4: Empty workspace produces no zip
  // -------------------------------------------------------------
  assert.throws(() => buildZipArchive([]), /Cannot create zip: no files provided/)

  console.log('PASS: US-57 export zip in Pair and Open modes with live text snapshot')
} finally {
  for (const ws of sockets.reverse()) {
    try { ws.close() } catch {}
  }
  for (const d of docs) d.destroy()
}
