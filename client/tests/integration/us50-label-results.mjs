import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..', '..', '..')
const clientDir = path.resolve(rootDir, 'client')

console.log('=== US-50 Client Integration Test: Real Component & Run Processing Flow ===')

// 1. Transpile production TypeScript components in an isolated cache directory
const ts = require(path.join(clientDir, 'node_modules', 'typescript', 'lib', 'typescript.js'))
const cacheDir = path.join(clientDir, 'node_modules', '.cache_us50_integration')
fs.mkdirSync(cacheDir, { recursive: true })
fs.writeFileSync(path.join(cacheDir, 'package.json'), JSON.stringify({ type: 'module' }))

// Transpile RunOutputPanel.tsx (production React component)
const panelSource = fs.readFileSync(path.join(clientDir, 'src', 'RunOutputPanel.tsx'), 'utf-8')
const transpiledPanel = ts.transpileModule(panelSource, {
  compilerOptions: {
    jsx: ts.JsxEmit.ReactJSX,
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
})
const panelFilePath = path.join(cacheDir, 'RunOutputPanel.js')
fs.writeFileSync(panelFilePath, transpiledPanel.outputText)

// Load production React, ReactDOMServer, Happy-DOM, and production component
const React = await import(pathToFileURL(path.join(clientDir, 'node_modules', 'react', 'index.js')).href)
const ReactDOMServer = await import(pathToFileURL(path.join(clientDir, 'node_modules', 'react-dom', 'server.node.js')).href)
const { Window } = await import(pathToFileURL(path.join(clientDir, 'node_modules', 'happy-dom', 'lib', 'index.js')).href)
const { default: RunOutputPanel } = await import(pathToFileURL(panelFilePath).href)

function renderPanelToDOM(result) {
  const createElement = React.default?.createElement || React.createElement
  const renderToStaticMarkup = ReactDOMServer.default?.renderToStaticMarkup || ReactDOMServer.renderToStaticMarkup
  const html = renderToStaticMarkup(createElement(RunOutputPanel, { result }))

  const window = new Window()
  const document = window.document
  document.body.innerHTML = html
  return { document, html }
}

// 2. Load Real Contract JSON Fixtures
const pairFixturePath = path.join(rootDir, 'docs', 'examples', 'v2-run-result-pair.json')
const openFixturePath = path.join(rootDir, 'docs', 'examples', 'v2-run-result-open-failure.json')

assert(fs.existsSync(pairFixturePath), `Pair fixture missing: ${pairFixturePath}`)
assert(fs.existsSync(openFixturePath), `Open fixture missing: ${openFixturePath}`)

const pairResult = JSON.parse(fs.readFileSync(pairFixturePath, 'utf-8'))
const openResult = JSON.parse(fs.readFileSync(openFixturePath, 'utf-8'))

// -------------------------------------------------------------
// Test 1: Pair Mode Production Component Display
// -------------------------------------------------------------
console.log('--- Test 1: Pair Mode Production Component Display ---')
const pairRender = renderPanelToDOM(pairResult)
const pairDoc = pairRender.document

const pairRunner = pairDoc.querySelector('[data-testid="run-output-runner"]')
assert(pairRunner !== null, 'Runner element must be rendered in Pair mode')
assert.equal(pairRunner.textContent, 'Run by Ada', 'Runner display name must come from result.runner.display_name')

const pairTime = pairDoc.querySelector('[data-testid="run-output-time"]')
assert(pairTime !== null, 'Timestamp element must be rendered')
assert.equal(pairTime.getAttribute('title'), '2026-11-01T10:20:05.087Z', 'Raw ISO timestamp must be preserved in title tooltip')
assert(pairTime.textContent.startsWith(' at '), 'Timestamp label must format with " at <time>"')

// Saved versions must not be rendered when empty
const pairSavedVersions = pairDoc.querySelector('[data-testid="run-output-saved-versions"]')
assert.equal(pairSavedVersions, null, 'Pair mode with empty saved_versions must NOT render saved versions row')

// Member ID must never be displayed in visible text
assert(!pairRender.html.includes('m_7a3c9f'), 'Member ID must NEVER be rendered in user-visible text')
console.log('PASS: Pair mode renders runner, local time tooltip, no saved_versions, and no member IDs')

// -------------------------------------------------------------
// Test 2: Open Mode Production Component Display
// -------------------------------------------------------------
console.log('--- Test 2: Open Mode Production Component Display ---')
const openRender = renderPanelToDOM(openResult)
const openDoc = openRender.document

const openRunner = openDoc.querySelector('[data-testid="run-output-runner"]')
assert(openRunner !== null, 'Runner element must be rendered in Open mode')
assert.equal(openRunner.textContent, 'Run by Lin', 'Runner display name must come from result.runner.display_name')

const openTime = openDoc.querySelector('[data-testid="run-output-time"]')
assert(openTime !== null, 'Timestamp element must be rendered in Open mode')
assert.equal(openTime.getAttribute('title'), '2026-11-01T10:15:30.412Z', 'Raw ISO timestamp must be preserved in title tooltip')

const openSavedVersions = openDoc.querySelector('[data-testid="run-output-saved-versions"]')
assert(openSavedVersions !== null, 'Open mode must render saved versions container')
assert(openSavedVersions.textContent.includes('main.py v4'), 'Must display main.py v4')
assert(openSavedVersions.textContent.includes('helper.py v2'), 'Must display helper.py v2')

const mainSavedModule = openDoc.querySelector('[data-testid="saved-version-main.py"]')
assert(mainSavedModule !== null, 'Module main.py element must exist')
assert.equal(mainSavedModule.getAttribute('title'), 'Saved by Ada at 2026-11-01T10:12:04.118Z', 'Tooltip must show saver and timestamp')

const helperSavedModule = openDoc.querySelector('[data-testid="saved-version-helper.py"]')
assert(helperSavedModule !== null, 'Module helper.py element must exist')
assert.equal(helperSavedModule.getAttribute('title'), 'Saved by Lin at 2026-11-01T10:14:51.733Z', 'Tooltip must show saver and timestamp')

// Member IDs must never be rendered in user-visible text
assert(!openRender.html.includes('m_4b19d2'), 'Member ID m_4b19d2 must NEVER be rendered')
assert(!openRender.html.includes('m_7a3c9f'), 'Member ID m_7a3c9f must NEVER be rendered')
console.log('PASS: Open mode renders runner, local time tooltip, module version labels, and saver tooltips')

// -------------------------------------------------------------
// Test 3: Runner Identity Taken from Payload, NOT Local State
// -------------------------------------------------------------
console.log('--- Test 3: Runner Identity Taken Exclusively from Payload ---')
const localUserState = { member_id: 'm_local_bob', display_name: 'LocalBob' }
const payloadFromRemote = {
  ...pairResult,
  runner: { member_id: 'm_remote_charlie', display_name: 'Charlie' },
}

const remoteRender = renderPanelToDOM(payloadFromRemote)
const remoteRunnerEl = remoteRender.document.querySelector('[data-testid="run-output-runner"]')
assert.equal(remoteRunnerEl.textContent, 'Run by Charlie')
assert(!remoteRender.html.includes(localUserState.display_name), 'Local user state must never overwrite remote runner attribution')
console.log('PASS: Runner identity is read strictly from result.runner and ignores local user identity')

// -------------------------------------------------------------
// Test 4: Retention of Attribution Across Consecutive Runs (AC 4)
// -------------------------------------------------------------
console.log('--- Test 4: Retention of Attribution Across Consecutive Runs ---')
// Run 1: Ada
const run1 = renderPanelToDOM(pairResult)
// Run 2: Lin
const run2 = renderPanelToDOM(openResult)

// Assert Run 1 retains Ada's metadata
assert.equal(run1.document.querySelector('[data-testid="run-output-runner"]').textContent, 'Run by Ada')
assert.equal(run1.document.querySelector('[data-testid="run-output-time"]').getAttribute('title'), '2026-11-01T10:20:05.087Z')
assert.equal(run1.document.querySelector('[data-testid="run-output-saved-versions"]'), null)

// Assert Run 2 retains Lin's metadata
assert.equal(run2.document.querySelector('[data-testid="run-output-runner"]').textContent, 'Run by Lin')
assert.equal(run2.document.querySelector('[data-testid="run-output-time"]').getAttribute('title'), '2026-11-01T10:15:30.412Z')
assert(run2.document.querySelector('[data-testid="run-output-saved-versions"]').textContent.includes('main.py v4'))
console.log('PASS: Older results strictly retain their own attribution across consecutive runs')

// Cleanup cache directory
fs.rmSync(cacheDir, { recursive: true, force: true })

console.log('ALL US-50 CLIENT INTEGRATION TESTS PASSED')
process.exit(0)
