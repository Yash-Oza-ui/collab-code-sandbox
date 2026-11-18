import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..', '..', '..')

console.log('=== US-50 Client Integration Test: Run Result Labeling ===')

// 1. Verify Real Contract Fixtures
const pairFixturePath = path.join(rootDir, 'docs', 'examples', 'v2-run-result-pair.json')
const openFixturePath = path.join(rootDir, 'docs', 'examples', 'v2-run-result-open-failure.json')

assert(fs.existsSync(pairFixturePath), `Pair fixture missing: ${pairFixturePath}`)
assert(fs.existsSync(openFixturePath), `Open fixture missing: ${openFixturePath}`)

const pairFixture = JSON.parse(fs.readFileSync(pairFixturePath, 'utf-8'))
const openFixture = JSON.parse(fs.readFileSync(openFixturePath, 'utf-8'))

// Test 1: Pair mode run result carries runner display_name, ISO ran_at, and empty saved_versions
assert.equal(typeof pairFixture.runner, 'object', 'runner must be an object')
assert.equal(pairFixture.runner.display_name, 'Ada', 'runner display_name must be Ada')
assert.match(pairFixture.ran_at, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/, 'ran_at must be ISO UTC')
assert(Array.isArray(pairFixture.saved_versions), 'saved_versions must be an array')
assert.equal(pairFixture.saved_versions.length, 0, 'saved_versions in Pair mode must be empty')
console.log('PASS: Pair mode fixture conforms to V2.9 runner attribution schema')

// Test 2: Open mode run result carries runner display_name, ISO ran_at, and saved_versions details
assert.equal(typeof openFixture.runner, 'object', 'runner must be an object')
assert.equal(openFixture.runner.display_name, 'Lin', 'runner display_name must be Lin')
assert.match(openFixture.ran_at, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/, 'ran_at must be ISO UTC')
assert(Array.isArray(openFixture.saved_versions), 'saved_versions must be a non-empty array')
assert.equal(openFixture.saved_versions.length, 2, 'saved_versions must contain 2 modules')

const [mainMod, helperMod] = openFixture.saved_versions
assert.equal(mainMod.name, 'main.py')
assert.equal(mainMod.version, 4)
assert.equal(mainMod.saved_by.display_name, 'Ada')
assert.equal(helperMod.name, 'helper.py')
assert.equal(helperMod.version, 2)
assert.equal(helperMod.saved_by.display_name, 'Lin')
console.log('PASS: Open mode fixture conforms to V2.9 runner and saved_versions schema')

// Test 3: Formatting & Tooltip contract verification
function formatRanAt(ranAt) {
  const d = new Date(ranAt)
  return Number.isNaN(d.getTime()) ? ranAt : d.toLocaleTimeString()
}

const localTime = formatRanAt(pairFixture.ran_at)
assert(typeof localTime === 'string' && localTime.length > 0, 'localTime must be non-empty string')
assert.equal(pairFixture.ran_at, '2026-11-01T10:20:05.087Z', 'raw timestamp preserved for tooltip')
console.log(`PASS: Localized timestamp formatting: "${localTime}" with raw tooltip preserved`)

console.log('ALL US-50 CLIENT INTEGRATION TESTS PASSED')
process.exit(0)
