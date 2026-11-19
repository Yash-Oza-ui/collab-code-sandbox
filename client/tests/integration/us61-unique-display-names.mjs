// US-61 / FR42: live control-socket uniqueness, rejoin and refusal contracts.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'

const api = process.env.SYNC_SERVICE_URL || 'http://localhost:8081'
const wsBase = api.replace(/^http/, 'ws')
const TIMEOUT_MS = 10000
const sockets = new Set()

function waitFor(condition, description) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + TIMEOUT_MS
    const poll = () => {
      try {
        const value = condition()
        if (value) return resolve(value)
        if (Date.now() >= deadline) return reject(new Error(`Timed out: ${description}`))
        setTimeout(poll, 15)
      } catch (error) {
        reject(error)
      }
    }
    poll()
  })
}

async function createRoom() {
  const response = await fetch(`${api}/v1/rooms`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode: 'pair', preset: 'debug_together' }),
  })
  assert.equal(response.status, 201, 'room creation')
  return response.json()
}

function connect(roomId, sessionId) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${wsBase}/ws/rooms/${roomId}?session_id=${sessionId}`)
    sockets.add(socket)
    const client = { socket, messages: [], closeCode: null }
    const timer = setTimeout(() => reject(new Error('WebSocket connect timeout')), TIMEOUT_MS)
    socket.on('message', (payload, isBinary) => {
      if (!isBinary) client.messages.push(JSON.parse(payload.toString()))
    })
    socket.on('close', (code) => { client.closeCode = code })
    socket.once('open', () => { clearTimeout(timer); resolve(client) })
    socket.once('error', (error) => { clearTimeout(timer); reject(error) })
  })
}

async function join(client, displayName, ownerToken) {
  client.socket.send(JSON.stringify({
    type: 'join',
    display_name: displayName,
    ...(ownerToken ? { owner_token: ownerToken } : {}),
  }))
  return waitFor(() => client.messages.find(m =>
    m.type === 'joined' || m.type === 'join-refused'
  ), `join outcome for ${displayName}`)
}

async function main() {
  const { room_id: roomId, owner_token: ownerToken } = await createRoom()
  const aliceId = randomUUID()
  const bobId = randomUUID()

  const first = await connect(roomId, aliceId)
  const accepted = await join(first, 'Ada', ownerToken)
  assert.equal(accepted.type, 'joined')

  // A separate session cannot claim Ada, even with different casing.
  const duplicate = await connect(roomId, bobId)
  const refused = await join(duplicate, 'ada')
  assert.equal(refused.type, 'join-refused')
  assert.equal(refused.reason, 'NAME_TAKEN')
  assert.match(refused.message, /another name/i)
  await waitFor(() => duplicate.closeCode !== null, 'refused socket closed')

  // A second tab with the SAME session id is a rejoin, not a new member.
  const secondTab = await connect(roomId, aliceId)
  const rejoined = await join(secondTab, 'Ada')
  assert.equal(rejoined.type, 'joined')
  assert.equal(rejoined.member_id, accepted.member_id)
  const roster = await waitFor(() =>
    secondTab.messages.filter(m => m.type === 'roster').at(-1), 'server roster'
  )
  assert.equal(roster.members.filter(m => m.display_name === 'Ada').length, 1)
  assert.equal(roster.members.length, 1)
  assert.ok(roster.members.every(m => !('session_id' in m)), 'session IDs are private')

  // A NAME_TAKEN client can retry on a fresh socket without a page reload.
  const retry = await connect(roomId, bobId)
  const retryOutcome = await join(retry, 'Lin')
  assert.equal(retryOutcome.type, 'joined')

  // Race two genuinely distinct sessions for one name: exactly one wins.
  const raceRoom = await createRoom()
  const raceSessionA = randomUUID()
  const raceSessionB = randomUUID()
  const raceA = await connect(raceRoom.room_id, raceSessionA)
  const raceB = await connect(raceRoom.room_id, raceSessionB)
  const [a, b] = await Promise.all([join(raceA, 'Grace'), join(raceB, 'grace')])
  assert.equal([a, b].filter(m => m.type === 'joined').length, 1)
  assert.equal([a, b].filter(m => m.reason === 'NAME_TAKEN').length, 1)

  // A racing join's roster broadcast can overlap the refused socket closing.
  // Query the stable winner identity on a fresh tab, rather than assuming the
  // first socket receives a broadcast at a particular point in the race.
  const winnerSession = a.type === 'joined' ? raceSessionA : raceSessionB
  const winnerName = a.type === 'joined' ? 'Grace' : 'grace'
  const winnerTab = await connect(raceRoom.room_id, winnerSession)
  const winnerRejoin = await join(winnerTab, winnerName)
  assert.equal(winnerRejoin.type, 'joined')
  assert.equal(winnerRejoin.member_id, (a.type === 'joined' ? a : b).member_id)
  const finalRoster = await waitFor(() =>
    winnerTab.messages.filter(m => m.type === 'roster').at(-1), 'race roster after rejoin'
  )
  assert.equal(finalRoster.members.length, 1)
  console.log('US-61 integration: PASS (duplicate, case, same-session tabs, retry, race, roster)')
}

try {
  await main()
} catch (error) {
  console.error('US-61 integration: FAIL', error.message)
  process.exitCode = 1
} finally {
  for (const socket of sockets) {
    try { socket.close() } catch { /* ignore cleanup failures */ }
  }
}
