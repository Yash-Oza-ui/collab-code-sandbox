// CR13 comparison (T-22): the cost of the per-edit write-permission check.
//
// Takes two harness result files for the same variant — one run with the check
// ON (the default, NFR13) and one with it OFF (sync-service started with
// LOADTEST_DISABLE_EDIT_CHECK=true, test-only) — and prints the latency delta.
//
//   node cr13-compare.mjs results/T1-A-checks-on.json results/T1-A-checks-off.json
//
// NFR13 is verified only when NFR1 (p95 < 200 ms) holds with the check ON (CR13).
import { readFileSync, writeFileSync } from 'node:fs'

const [onPath, offPath, outPath] = process.argv.slice(2)
if (!onPath || !offPath) {
  console.error('usage: node cr13-compare.mjs <checks-on.json> <checks-off.json> [out.json]')
  process.exit(1)
}
const on = JSON.parse(readFileSync(onPath, 'utf8'))
const off = JSON.parse(readFileSync(offPath, 'utf8'))
const d = (a, b) => (a == null || b == null ? null : Math.round((a - b) * 100) / 100)

const report = {
  cr13: 'cost of the per-edit write-permission check (NFR13 vs NFR1)',
  variant: on.variant,
  checks_on: on.latency_ms,
  checks_off: off.latency_ms,
  check_cost_ms: { p50: d(on.latency_ms.p50_ms, off.latency_ms.p50_ms), p95: d(on.latency_ms.p95_ms, off.latency_ms.p95_ms) },
  nfr1_met_with_checks_on: on.nfr1_met,
  nfr13_note: 'NFR13 is verified only when NFR1 (p95 < 200 ms) holds with the check ON.',
  machine: on.machine, ran_at: new Date().toISOString(),
}
console.log(`CR13 — ${on.variant}`)
console.log(`  checks ON : p50=${on.latency_ms.p50_ms}ms p95=${on.latency_ms.p95_ms}ms max=${on.latency_ms.max_ms}ms`)
console.log(`  checks OFF: p50=${off.latency_ms.p50_ms}ms p95=${off.latency_ms.p95_ms}ms max=${off.latency_ms.max_ms}ms`)
console.log(`  check cost (ON-OFF): p50=${report.check_cost_ms.p50}ms p95=${report.check_cost_ms.p95}ms`)
console.log(`  NFR1 met with checks ON: ${on.nfr1_met}`)
if (outPath) { writeFileSync(outPath, JSON.stringify(report, null, 2)); console.log(`  -> ${outPath}`) }
