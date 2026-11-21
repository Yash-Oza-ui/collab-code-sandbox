import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

console.log('========================================================================');
console.log('US-46 Two-Browser End-to-End Verification');
console.log('Acceptance Criteria:');
console.log('  - Owner marks module non-runnable, both browsers show updated badge live');
console.log('  - Host designates entry file, both browsers show entry badge live');
console.log('  - Replacing entry file leaves exactly one entry file in both browsers');
console.log('========================================================================');

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const clientDist = path.join(rootDir, 'client', 'dist');
const screenshotsDir = path.join(rootDir, 'docs', 'screenshots');

if (!fs.existsSync(screenshotsDir)) {
  fs.mkdirSync(screenshotsDir, { recursive: true });
}

// 1. WebSocket Server using client/node_modules/ws
const wsPath = path.join(rootDir, 'client', 'node_modules', 'ws', 'index.js');
const wsModule = await import(pathToFileURL(wsPath).href);
const WebSocketServer = wsModule.WebSocketServer || wsModule.default?.WebSocketServer || wsModule.default;
const WebSocket = wsModule.WebSocket || wsModule.default;

const testRoomId = 'us46-e2e-open-room';
const ownerToken = 'token-owner-secret';

const roomClients = new Map(); // roomId -> Set of client objects
const roomModules = new Map(); // roomId -> Array of modules

roomModules.set(testRoomId, [
  { module_id: 'mod_main', name: 'main.py', owner_member_id: 'm_alice', runnable: true, is_entry: false, pending: false },
  { module_id: 'mod_helper', name: 'helper.py', owner_member_id: 'm_bob', runnable: true, is_entry: false, pending: false },
]);

function broadcastToRoom(roomId, message) {
  const clients = roomClients.get(roomId);
  if (!clients) return;
  const payload = JSON.stringify(message);
  for (const c of clients) {
    if (c.type === 'control' && c.ws.readyState === WebSocket.OPEN) {
      c.ws.send(payload);
    }
  }
}

// Sync Service HTTP & WS (Port 8081)
const syncHttpServer = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Session-Id, X-Owner-Token');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.url === '/actuator/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'UP' }));
    return;
  }

  // Owner check
  if (req.url.startsWith(`/v1/rooms/${testRoomId}/owner`)) {
    const token = req.headers['x-owner-token'];
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ room_id: testRoomId, is_owner: token === ownerToken }));
    return;
  }

  // List Modules
  if (req.url.startsWith(`/v1/rooms/${testRoomId}/modules`) && req.method === 'GET') {
    const mods = roomModules.get(testRoomId) || [];
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(mods));
    return;
  }

  // PATCH Module (US-46)
  const patchMatch = req.url.match(/\/v1\/rooms\/([^/]+)\/modules\/([^/]+)$/);
  if (patchMatch && req.method === 'PATCH') {
    const roomId = patchMatch[1];
    const moduleId = patchMatch[2];

    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      const parsed = JSON.parse(body || '{}');
      const mods = roomModules.get(roomId) || [];
      const mod = mods.find(m => m.module_id === moduleId);

      if (!mod) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: { code: 'MODULE_NOT_FOUND', message: 'Module not found.' } }));
        return;
      }

      if ('runnable' in parsed) {
        mod.runnable = Boolean(parsed.runnable);
        broadcastToRoom(roomId, {
          type: 'module-updated',
          ...mod,
          deleted: false,
        });

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(mod));
        return;
      }

      if ('is_entry' in parsed) {
        const isEntry = Boolean(parsed.is_entry);
        if (isEntry) {
          for (const other of mods) {
            if (other.is_entry && other.module_id !== moduleId) {
              other.is_entry = false;
              broadcastToRoom(roomId, {
                type: 'module-updated',
                ...other,
                deleted: false,
              });
            }
          }
        }

        mod.is_entry = isEntry;
        broadcastToRoom(roomId, {
          type: 'module-updated',
          ...mod,
          deleted: false,
        });

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(mod));
        return;
      }

      res.writeHead(422, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { code: 'INVALID_REQUEST', message: 'Invalid PATCH.' } }));
    });
    return;
  }

  res.writeHead(404);
  res.end();
});

const wss = new WebSocketServer({ noServer: true });

syncHttpServer.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost:8081');
  const pathname = url.pathname;
  const match = pathname.match(/\/ws\/rooms\/([^/]+)/);
  if (!match) {
    socket.destroy();
    return;
  }
  const roomId = match[1];
  const sessionId = url.searchParams.get('session_id') || 'sess-' + Math.random().toString(36).slice(2);
  const isDoc = pathname.includes('/docs/');

  wss.handleUpgrade(req, socket, head, (ws) => {
    let clients = roomClients.get(roomId);
    if (!clients) {
      clients = new Set();
      roomClients.set(roomId, clients);
    }

    const clientInfo = {
      ws,
      type: isDoc ? 'doc' : 'control',
      sessionId,
      member_id: null,
      display_name: null,
      role: 'participant',
    };
    clients.add(clientInfo);

    ws.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw.toString());
        if (msg.type === 'join') {
          const isAlice = msg.display_name?.includes('Alice');
          clientInfo.display_name = msg.display_name;
          clientInfo.member_id = isAlice ? 'm_alice' : 'm_bob';
          clientInfo.role = isAlice ? 'host' : 'participant';

          const joinedMsg = {
            type: 'joined',
            member_id: clientInfo.member_id,
            display_name: clientInfo.display_name,
            role: clientInfo.role,
            mode: 'open',
            language: 'python',
          };
          ws.send(JSON.stringify(joinedMsg));

          // Send roster
          const rosterList = Array.from(clients)
            .filter((c) => c.member_id)
            .map((c) => ({
              member_id: c.member_id,
              display_name: c.display_name,
              role: c.role,
              is_observer: false,
            }));
          broadcastToRoom(roomId, { type: 'roster', members: rosterList });
        }
      } catch {}
    });

    ws.on('close', () => {
      clients.delete(clientInfo);
    });
  });
});

await new Promise(resolve => syncHttpServer.listen(8081, resolve));
console.log('Mock sync-service listening on port 8081');

// 2. Static Server for client/dist (Port 5173)
const mimeTypes = {
  '.html': 'text/html',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

const staticServer = http.createServer((req, res) => {
  const urlPath = req.url.split('?')[0];
  let filePath = path.join(clientDist, urlPath);

  if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
    filePath = path.join(clientDist, 'index.html');
  }

  const ext = path.extname(filePath);
  const contentType = mimeTypes[ext] || 'application/octet-stream';

  try {
    const data = fs.readFileSync(filePath);
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(data);
  } catch {
    res.writeHead(404);
    res.end('Not found');
  }
});

await new Promise(resolve => staticServer.listen(5173, resolve));
console.log('Client static server listening on port 5173');

// 3. Launch Chrome Instances
const cdpPort1 = 9222;
const cdpPort2 = 9223;
const tempUserData1 = path.join(os.tmpdir(), `chrome-alice-${Date.now()}`);
const tempUserData2 = path.join(os.tmpdir(), `chrome-bob-${Date.now()}`);

const chrome1 = spawn('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', [
  '--headless=new',
  `--remote-debugging-port=${cdpPort1}`,
  `--user-data-dir=${tempUserData1}`,
  '--window-size=1280,900',
  'about:blank',
]);

const chrome2 = spawn('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', [
  '--headless=new',
  `--remote-debugging-port=${cdpPort2}`,
  `--user-data-dir=${tempUserData2}`,
  '--window-size=1280,900',
  'about:blank',
]);

await new Promise(resolve => setTimeout(resolve, 2000));

class CDPClient {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl);
    this.nextId = 1;
    this.pending = new Map();
    this.ws.on('message', raw => {
      const msg = JSON.parse(raw.toString());
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      }
    });
  }

  async connect() {
    if (this.ws.readyState === WebSocket.OPEN) return;
    await new Promise((res, rej) => {
      this.ws.once('open', res);
      this.ws.once('error', rej);
    });
  }

  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = this.nextId++;
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  async eval(expression) {
    const res = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    return res.result?.value;
  }
}

async function attachToChrome(port, roomUrl) {
  const tabInfo = await (await fetch(`http://127.0.0.1:${port}/json/new?${roomUrl}`, { method: 'PUT' })).json();
  const client = new CDPClient(tabInfo.webSocketDebuggerUrl);
  await client.connect();
  await client.send('Page.enable');
  await client.send('Runtime.enable');
  return client;
}

try {
  console.log('Attaching CDP clients...');
  const aliceUrl = `http://localhost:5173/room/${testRoomId}`;
  const bobUrl = `http://localhost:5173/room/${testRoomId}`;

  const alice = await attachToChrome(cdpPort1, aliceUrl);
  const bob = await attachToChrome(cdpPort2, bobUrl);

  await new Promise(r => setTimeout(r, 2000));

  // Set Alice owner token in localStorage
  await alice.eval(`localStorage.setItem('collab_owner_token:${testRoomId}', '${ownerToken}');`);

  // Alice joins
  await alice.eval(`
    (function() {
      const input = document.querySelector('#display-name');
      if (input) {
        input.value = 'Alice Host';
        input.dispatchEvent(new Event('input', { bubbles: true }));
        const form = document.querySelector('form');
        if (form) form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
    })()
  `);
  console.log('Alice joined as Host.');

  // Bob joins
  await bob.eval(`
    (function() {
      const input = document.querySelector('#display-name');
      if (input) {
        input.value = 'Bob Member';
        input.dispatchEvent(new Event('input', { bubbles: true }));
        const form = document.querySelector('form');
        if (form) form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
    })()
  `);
  console.log('Bob joined as Participant.');

  await new Promise(r => setTimeout(r, 2500));

  // Verify Alice and Bob both see Workspace panel with No entry file set
  const aliceStatus = await alice.eval(`document.querySelector('[data-testid="no-entry-file-message"]')?.textContent`);
  const bobStatus = await bob.eval(`document.querySelector('[data-testid="no-entry-file-message"]')?.textContent`);
  console.log(`Initial Status - Alice: "${aliceStatus}", Bob: "${bobStatus}"`);
  if (!aliceStatus?.includes('No entry file set') || !bobStatus?.includes('No entry file set')) {
    throw new Error('Expected both browsers to show "No entry file set"');
  }

  // Verify Bob (owner of helper.py) sees toggle button, Alice does not
  const bobHasToggle = await bob.eval(`Boolean(document.querySelector('[data-testid="toggle-runnable-mod_helper"]'))`);
  const aliceHasToggle = await alice.eval(`Boolean(document.querySelector('[data-testid="toggle-runnable-mod_helper"]'))`);
  console.log(`Owner Toggle Visibility - Bob (Owner): ${bobHasToggle}, Alice (Non-owner): ${aliceHasToggle}`);
  if (!bobHasToggle || aliceHasToggle) {
    throw new Error('Expected Bob to see toggle for helper.py and Alice to not see it');
  }

  // Bob toggles helper.py to non-runnable
  console.log('Bob clicks "Mark non-runnable" on helper.py...');
  await bob.eval(`document.querySelector('[data-testid="toggle-runnable-mod_helper"]')?.click()`);
  await new Promise(r => setTimeout(r, 1000));

  // Verify BOTH Alice and Bob see helper.py as non-runnable
  const aliceBadgeHelper = await alice.eval(`document.querySelector('[data-testid="runnable-badge-mod_helper"]')?.textContent`);
  const bobBadgeHelper = await bob.eval(`document.querySelector('[data-testid="runnable-badge-mod_helper"]')?.textContent`);
  console.log(`Live Badge Verification - Alice: "${aliceBadgeHelper}", Bob: "${bobBadgeHelper}"`);
  if (aliceBadgeHelper !== 'non-runnable' || bobBadgeHelper !== 'non-runnable') {
    throw new Error('Expected live update of non-runnable badge on both browsers');
  }

  // Alice (Host) sets main.py as entry file
  console.log('Alice clicks "Set as entry" on main.py...');
  await alice.eval(`document.querySelector('[data-testid="set-entry-mod_main"]')?.click()`);
  await new Promise(r => setTimeout(r, 1000));

  // Verify BOTH Alice and Bob see entry indicator: "Entry file: main.py"
  const aliceEntryStatus = await alice.eval(`document.querySelector('[data-testid="entry-file-indicator"]')?.textContent`);
  const bobEntryStatus = await bob.eval(`document.querySelector('[data-testid="entry-file-indicator"]')?.textContent`);
  console.log(`Live Entry Indicator - Alice: "${aliceEntryStatus}", Bob: "${bobEntryStatus}"`);
  if (!aliceEntryStatus?.includes('main.py') || !bobEntryStatus?.includes('main.py')) {
    throw new Error('Expected both browsers to see main.py as entry file');
  }

  // Alice replaces entry file with helper.py
  console.log('Alice replaces entry file: clicks "Set as entry" on helper.py...');
  await alice.eval(`document.querySelector('[data-testid="set-entry-mod_helper"]')?.click()`);
  await new Promise(r => setTimeout(r, 1000));

  // Verify BOTH see exactly one entry file (helper.py)
  const aliceHelperEntry = await alice.eval(`Boolean(document.querySelector('[data-testid="entry-badge-mod_helper"]'))`);
  const aliceMainEntry = await alice.eval(`Boolean(document.querySelector('[data-testid="entry-badge-mod_main"]'))`);
  const bobHelperEntry = await bob.eval(`Boolean(document.querySelector('[data-testid="entry-badge-mod_helper"]'))`);
  const bobMainEntry = await bob.eval(`Boolean(document.querySelector('[data-testid="entry-badge-mod_main"]'))`);

  console.log(`Replacement verification - helper is entry: Alice=${aliceHelperEntry}, Bob=${bobHelperEntry}`);
  console.log(`Replacement verification - main is NOT entry: Alice=${!aliceMainEntry}, Bob=${!bobMainEntry}`);
  if (!aliceHelperEntry || aliceMainEntry || !bobHelperEntry || bobMainEntry) {
    throw new Error('Entry file replacement verification failed');
  }

  // Capture screenshot of Bob's view
  console.log('Capturing two-browser verification screenshot...');
  const screenshotData = await bob.send('Page.captureScreenshot', { format: 'png' });
  const screenshotPath = path.join(screenshotsDir, 'us46-two-browser-runnable-entry.png');
  fs.writeFileSync(screenshotPath, Buffer.from(screenshotData.data, 'base64'));
  console.log(`Screenshot saved to ${screenshotPath}`);

  console.log('========================================================================');
  console.log('✅ ALL US-46 TWO-BROWSER E2E ASSERTIONS PASSED SUCCESSFULLY!');
  console.log('========================================================================');
} finally {
  chrome1.kill();
  chrome2.kill();
  syncHttpServer.close();
  staticServer.close();
  try { fs.rmSync(tempUserData1, { recursive: true, force: true }); } catch {}
  try { fs.rmSync(tempUserData2, { recursive: true, force: true }); } catch {}
}
