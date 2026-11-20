# US-06: Edit Sync Latency (Development-Machine Baseline)

- **Date:** 2026-11-20
- **Requirement:** NFR1 (95th percentile <= 200 ms under test set-up T1) & NFR2 (2, 5, and 10 users)
- **CR13 / NFR13 Write-Permission Check:** **ON** (server-verified by `harness.mjs` for every run)
- **Machine:** AMD Ryzen 5 5600H (6 cores / 12 logical processors), 15.4 GB RAM, Windows, OpenJDK 21.0.13 (Temurin), Node.js v24.19.0
- **Verification Status:** **NFR1 remains UNVERIFIED** until the final-host measurement is completed in **US-31 (Sprint 6)**.

## 1. T-22 Harness Results (`tests/load/results/*.json`)

| Variant | Users | Modules | Samples | p50 (ms) | p95 (ms) | Max (ms) | NFR1 Target (p95) | Status | Raw File |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| T1-P (Pair, 1 editor) | 2 | 1 | 50 | 2.40 | 3.29 | 5.14 | <= 200 ms | Met | `tests/load/results/T1-P-2.json` |
| T1-P (Pair, 1 editor) | 5 | 1 | 200 | 3.03 | 5.22 | 14.99 | <= 200 ms | Met | `tests/load/results/T1-P-5.json` |
| T1-P (Pair, 1 editor) | 10 | 1 | 450 | 3.57 | 5.31 | 7.52 | <= 200 ms | Met | `tests/load/results/T1-P-10.json` |
| T1-A (Pair, all edit) | 2 | 1 | 100 | 2.23 | 3.52 | 5.69 | <= 200 ms | Met | `tests/load/results/T1-A-2.json` |
| T1-A (Pair, all edit) | 5 | 1 | 1000 | 5.33 | 8.96 | 15.02 | <= 200 ms | Met | `tests/load/results/T1-A-5.json` |
| T1-A (Pair, all edit) | 10 | 1 | 4500 | 11.39 | 25.18 | 49.52 | <= 200 ms | Met | `tests/load/results/T1-A-10.json` |
| T1-O (Open) | 2 | 1* | 50 | 1.97 | 2.75 | 4.71 | <= 200 ms | Met | `tests/load/results/T1-O-2.json` |
| T1-O (Open) | 5 | 3* | 300 | 3.14 | 5.83 | 7.73 | <= 200 ms | Met | `tests/load/results/T1-O-5.json` |
| T1-O (Open) | 10 | 5 | 1250 | 5.49 | 9.73 | 13.42 | <= 200 ms | Met | `tests/load/results/T1-O-10.json` |

*\*Note: At 2 and 5 users, `T1-O` used 1 and 3 modules respectively (instead of 5) so at least 1–2 non-owner readers were present to measure cross-client receipt.*

## 2. Two-Browser Real Sample (One Machine)

- **Method:** Two real browser windows opened against the local Vite client (`npm run dev`) connected to `sync-service` (`localhost:8081`, permission check ON). Browser A inserted 35 timestamped probes (`p:<Date.now()>`) via `window.monaco.editor.getModels()[0].applyEdits(...)` at 250 ms intervals (~4 chars/sec). Browser B measured `Date.now() - sentTimestamp` when each probe rendered in `.monaco-editor .view-lines` via `MutationObserver`.

| Mode | Samples | p50 (ms) | p95 (ms) | Max (ms) | NFR1 Target (p95) | Status |
|---|---:|---:|---:|---:|---:|---|
| Pair Mode | 32 | 17 | 69 | 102 | <= 200 ms | Met |
| Open Mode (1 module) | 25 | 21 | 57 | 121 | <= 200 ms | Met |

## 3. What US-31 Must Repeat on the Final Host

1. Re-run `T1-P`, `T1-A`, and `T1-O` (at 2, 5, and 10 users with `--edit-check on`) against the deployed `sync-service` URL (`--url <deployed-url>`).
2. Capture a two-browser sample over the real network/campus connection to confirm end-to-end Monaco + Yjs + network latency stays <= 200 ms at p95.
3. Only after the final-host run meets <= 200 ms p95 can NFR1 (and CR13 / NFR13) be marked verified.
