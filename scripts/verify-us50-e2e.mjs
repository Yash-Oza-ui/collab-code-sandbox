import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

console.log('========================================================================');
console.log('US-50 Two-Browser Dual-Runner End-to-End Verification');
console.log('Environment: Isolated Dual Headless Chrome instances via DevTools Protocol');
console.log('Backend Mode: Mock sync-service (port 8081) & mock execution-service (port 8082)');
console.log('Client Application: Real production bundle built from client/dist');
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

const roomClients = new Map(); // roomId -> Set of { ws, type, member_id, display_name, role, sessionId }

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
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

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

  res.writeHead(404);
  res.end();
});

const wss = new WebSocketServer({ server: syncHttpServer });

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost:8081');
  const pathname = url.pathname;
  const parts = pathname.split('/').filter(Boolean);
  const roomId = parts[2] || 'us50-demo';
  const isDoc = parts.length > 3 && parts[3] === 'docs';
  const sessionId = url.searchParams.get('session_id') || `sess_${Date.now()}_${Math.random()}`;

  if (!roomClients.has(roomId)) {
    roomClients.set(roomId, new Set());
  }
  const clientInfo = {
    ws,
    type: isDoc ? 'doc' : 'control',
    member_id: null,
    display_name: null,
    role: 'participant',
    sessionId,
  };
  roomClients.get(roomId).add(clientInfo);

  ws.on('message', (raw) => {
    if (isDoc) {
      for (const other of roomClients.get(roomId)) {
        if (other !== clientInfo && other.type === 'doc' && other.ws.readyState === WebSocket.OPEN) {
          other.ws.send(raw);
        }
      }
      return;
    }

    try {
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'join') {
        const isFirst = Array.from(roomClients.get(roomId)).filter(c => c.type === 'control' && c.member_id).length === 0;
        const memberId = isFirst ? 'm_alice' : 'm_bob';
        const role = isFirst ? 'host' : 'participant';
        clientInfo.member_id = memberId;
        clientInfo.display_name = msg.display_name || (isFirst ? 'Alice' : 'Bob');
        clientInfo.role = role;

        ws.send(JSON.stringify({
          type: 'joined',
          member_id: memberId,
          role,
          mode: 'pair',
          preset: 'teach_interview',
          permissions: { can_edit: true, can_run: true },
        }));

        const members = Array.from(roomClients.get(roomId))
          .filter(c => c.type === 'control' && c.member_id)
          .map(c => ({
            member_id: c.member_id,
            display_name: c.display_name,
            role: c.role,
          }));

        broadcastToRoom(roomId, {
          type: 'roster',
          members,
        });
      }
    } catch {
      // ignore
    }
  });

  ws.on('close', () => {
    roomClients.get(roomId)?.delete(clientInfo);
  });
});

await new Promise(resolve => syncHttpServer.listen(8081, resolve));
console.log('Mock sync-service listening on port 8081');

// 2. Execution Service HTTP Server (Port 8082)
let runCounter = 1;
const executionHttpServer = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.method === 'POST' && req.url === '/v1/runs') {
    let body = '';
    req.on('data', chunk => (body += chunk));
    req.on('end', () => {
      let parsed = {};
      try { parsed = JSON.parse(body); } catch {}
      const targetRoomId = parsed.room_id || 'us50-demo';

      // Dynamically resolve runner from session_id
      let runnerDisplayName = 'Unknown';
      let runnerMemberId = 'm_unknown';
      const clients = roomClients.get(targetRoomId);
      if (clients && parsed.session_id) {
        for (const c of clients) {
          if (c.sessionId === parsed.session_id && c.display_name) {
            runnerDisplayName = c.display_name;
            runnerMemberId = c.member_id;
            break;
          }
        }
      }
      if (runnerDisplayName === 'Unknown') {
        console.error(`Execution failed: could not resolve runner for session ${parsed.session_id}`);
        res.writeHead(422, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'UNRESOLVED_RUNNER', message: 'No active room participant matches session_id' }));
        return;
      }

      const runId = `req-us50-${runCounter++}`;
      const runResult = {
        type: 'run-result',
        request_id: parsed.request_id || runId,
        room_id: targetRoomId,
        language: parsed.language || 'python',
        stdout: `Execution output #${runCounter - 1} run by ${runnerDisplayName}\n`,
        stderr: '',
        exit_code: 0,
        execution_time_ms: 42,
        peak_memory_mb: 8.0,
        timed_out: false,
        oom_killed: false,
        failure_kind: null,
        output_truncated: false,
        message: null,
        compile_time_ms: null,
        runner: {
          member_id: runnerMemberId,
          display_name: runnerDisplayName,
        },
        ran_at: new Date().toISOString(),
        visibility: 'room',
        saved_versions: [],
        failure: null,
      };

      console.log(`Execution service resolved runner: "${runnerDisplayName}" for session ${parsed.session_id}`);

      // Broadcast to room
      broadcastToRoom(runResult.room_id, runResult);

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(runResult));
    });
    return;
  }

  res.writeHead(404);
  res.end();
});

await new Promise(resolve => executionHttpServer.listen(8082, resolve));
console.log('Mock execution-service listening on port 8082');

// 3. Static Server for client/dist (Port 5173)
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

// 4. Chrome CDP Automation - Launch Two Distinct Chrome Browser Instances
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

  async ready() {
    if (this.ws.readyState === WebSocket.OPEN) return;
    return new Promise(resolve => this.ws.once('open', resolve));
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

  close() {
    this.ws.close();
  }
}

function cleanup() {
  chrome1.kill();
  chrome2.kill();
  syncHttpServer.close();
  executionHttpServer.close();
  staticServer.close();
  try { fs.rmSync(tempUserData1, { recursive: true, force: true }); } catch {}
  try { fs.rmSync(tempUserData2, { recursive: true, force: true }); } catch {}
}

let client1;
let client2;

try {
  // Browser 1 (Alice): create tab
  const tab1Info = await (await fetch(`http://127.0.0.1:${cdpPort1}/json/new?http://localhost:5173/room/us50-demo`, { method: 'PUT' })).json();
  client1 = new CDPClient(tab1Info.webSocketDebuggerUrl);
  await client1.ready();
  await client1.send('Page.enable');
  await client1.send('Runtime.enable');
  console.log('Browser 1 (Alice) connected');

  // Browser 2 (Bob): create tab in separate instance
  const tab2Info = await (await fetch(`http://127.0.0.1:${cdpPort2}/json/new?http://localhost:5173/room/us50-demo`, { method: 'PUT' })).json();
  client2 = new CDPClient(tab2Info.webSocketDebuggerUrl);
  await client2.ready();
  await client2.send('Page.enable');
  await client2.send('Runtime.enable');
  console.log('Browser 2 (Bob) connected');

  // Wait for initial render
  await new Promise(resolve => setTimeout(resolve, 3000));

  // Join as Alice in Browser 1
  await client1.eval(`
    (function() {
      const input = document.querySelector('#display-name');
      if (input) {
        input.value = 'Alice';
        input.dispatchEvent(new Event('input', { bubbles: true }));
        const form = document.querySelector('form');
        if (form) form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
    })()
  `);
  console.log('Alice joined in Browser 1');

  // Join as Bob in Browser 2
  await client2.eval(`
    (function() {
      const input = document.querySelector('#display-name');
      if (input) {
        input.value = 'Bob';
        input.dispatchEvent(new Event('input', { bubbles: true }));
        const form = document.querySelector('form');
        if (form) form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
    })()
  `);
  console.log('Bob joined in Browser 2');

  await new Promise(resolve => setTimeout(resolve, 3000));

  // ==========================================
  // PHASE 1: Alice triggers Run
  // ==========================================
  console.log('--- PHASE 1: Alice triggers Run in Browser 1 ---');
  await client1.eval(`
    (function() {
      const runBtn = document.querySelector('[data-testid="run-button"]');
      if (runBtn) runBtn.click();
    })()
  `);

  let aliceRunSuccess = false;
  let t1Runner = '', t1Time = '', t1Title = '';
  let t2Runner = '', t2Time = '', t2Title = '';

  for (let i = 0; i < 30; i++) {
    await new Promise(resolve => setTimeout(resolve, 500));
    t1Runner = await client1.eval(`document.querySelector('[data-testid="run-output-runner"]')?.textContent || ''`);
    t1Time = await client1.eval(`document.querySelector('[data-testid="run-output-time"]')?.textContent || ''`);
    t1Title = await client1.eval(`document.querySelector('[data-testid="run-output-time"]')?.getAttribute('title') || ''`);

    t2Runner = await client2.eval(`document.querySelector('[data-testid="run-output-runner"]')?.textContent || ''`);
    t2Time = await client2.eval(`document.querySelector('[data-testid="run-output-time"]')?.textContent || ''`);
    t2Title = await client2.eval(`document.querySelector('[data-testid="run-output-time"]')?.getAttribute('title') || ''`);

    if (
      t1Runner === 'Run by Alice' &&
      t1Time.startsWith(' at ') &&
      t1Title.endsWith('Z') &&
      t2Runner === 'Run by Alice' &&
      t2Time.startsWith(' at ') &&
      t2Title.endsWith('Z')
    ) {
      aliceRunSuccess = true;
      break;
    }
  }

  // EXPLICIT ASSERTION 1: Both browsers MUST display "Run by Alice" and valid timestamp
  if (!aliceRunSuccess) {
    console.error('ASSERTION FAILED: Browser 1 or Browser 2 did not display correct attribution for Alice!');
    console.error({ t1Runner, t1Time, t1Title, t2Runner, t2Time, t2Title });
    cleanup();
    process.exit(1);
  }
  console.log('ASSERTION 1 PASSED: Browser 1 and Browser 2 both display "Run by Alice" with ISO UTC timestamp in tooltip.');

  // Capture Phase 1 screenshots
  const shot1 = await client1.send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(screenshotsDir, 'us50-browser-alice-runner.png'), Buffer.from(shot1.data, 'base64'));
  console.log('Saved docs/screenshots/us50-browser-alice-runner.png');

  const shot2 = await client2.send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(screenshotsDir, 'us50-browser-bob-participant.png'), Buffer.from(shot2.data, 'base64'));
  console.log('Saved docs/screenshots/us50-browser-bob-participant.png');

  // ==========================================
  // PHASE 2: Bob triggers Run (Dynamic attribution test)
  // ==========================================
  console.log('--- PHASE 2: Bob triggers Run in Browser 2 ---');
  await client2.eval(`
    (function() {
      const runBtn = document.querySelector('[data-testid="run-button"]');
      if (runBtn) runBtn.click();
    })()
  `);

  let bobRunSuccess = false;
  for (let i = 0; i < 30; i++) {
    await new Promise(resolve => setTimeout(resolve, 500));
    t1Runner = await client1.eval(`document.querySelector('[data-testid="run-output-runner"]')?.textContent || ''`);
    t1Time = await client1.eval(`document.querySelector('[data-testid="run-output-time"]')?.textContent || ''`);
    t1Title = await client1.eval(`document.querySelector('[data-testid="run-output-time"]')?.getAttribute('title') || ''`);

    t2Runner = await client2.eval(`document.querySelector('[data-testid="run-output-runner"]')?.textContent || ''`);
    t2Time = await client2.eval(`document.querySelector('[data-testid="run-output-time"]')?.textContent || ''`);
    t2Title = await client2.eval(`document.querySelector('[data-testid="run-output-time"]')?.getAttribute('title') || ''`);

    if (
      t1Runner === 'Run by Bob' &&
      t1Time.startsWith(' at ') &&
      t1Title.endsWith('Z') &&
      t2Runner === 'Run by Bob' &&
      t2Time.startsWith(' at ') &&
      t2Title.endsWith('Z')
    ) {
      bobRunSuccess = true;
      break;
    }
  }

  // EXPLICIT ASSERTION 2: Both browsers MUST dynamically update to "Run by Bob"
  if (!bobRunSuccess) {
    console.error('ASSERTION FAILED: Browser 1 or Browser 2 did not update to "Run by Bob"!');
    console.error({ t1Runner, t1Time, t1Title, t2Runner, t2Time, t2Title });
    cleanup();
    process.exit(1);
  }
  console.log('ASSERTION 2 PASSED: Browser 1 and Browser 2 both dynamically updated to "Run by Bob" with ISO UTC timestamp in tooltip.');

  // Capture Phase 2 screenshots
  const shotBobRunner = await client2.send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(screenshotsDir, 'us50-browser-bob-runner.png'), Buffer.from(shotBobRunner.data, 'base64'));
  console.log('Saved docs/screenshots/us50-browser-bob-runner.png');

  const shotAliceParticipant = await client1.send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(screenshotsDir, 'us50-browser-alice-participant.png'), Buffer.from(shotAliceParticipant.data, 'base64'));
  console.log('Saved docs/screenshots/us50-browser-alice-participant.png');

  client1.close();
  client2.close();
  cleanup();

  console.log('ALL TWO-BROWSER DUAL ATTRIBUTION ASSERTIONS PASSED WITH ZERO ERRORS.');
  process.exit(0);
} catch (err) {
  console.error('Unexpected error in two-browser verification:', err);
  cleanup();
  process.exit(1);
}
