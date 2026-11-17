# Load-test harness (T-22: T1-P, T1-A, T1-O)

Measures **edit-sync latency** — how long an edit takes to reach the other users
in a room — against a running `sync-service`, over the real control and document
sockets with real Yjs updates. It is the base for US-06 (edit sync latency),
US-31 (room size and load test) and US-63 (limits).

- **NFR1:** edits reach other users within **200 ms at the 95th percentile**,
  measured under test set-up **T1**.
- **NFR2:** a room supports 2–5 users typically, up to **10** maximum, under the
  T1 variants below.

No browser and no real user data: every "edit" is a generated counter value.

## The three T1 variants

| Variant | Room | Who edits | Measures |
|---------|------|-----------|----------|
| **T1-P** | Pair, `teach_interview` | one editor (Host), others read | one-writer fan-out |
| **T1-A** | Pair, `debug_together` | every user edits | all-writers load |
| **T1-O** | Open, 5 modules | Host writes each module, others read | per-module isolation |

## Prerequisites

- **Node 20+** (developed on Node 24).
- A running `sync-service` (default `http://localhost:8081`). Start the whole
  stack with `bash tests/integration/up.sh`, or run just sync-service locally.

## Install and run

```bash
cd tests/load
npm install

# T1-P with 5 users, 50 probe edits, write the result to a file:
node harness.mjs --variant T1-P --users 5 --edits 50 --out results/T1-P.json

# T1-A (everyone edits) and T1-O (Open mode, five modules):
node harness.mjs --variant T1-A --users 5 --edits 50 --out results/T1-A.json
node harness.mjs --variant T1-O --users 5 --edits 50 --modules 5 --out results/T1-O.json
```

### Options

| Flag | Default | Meaning |
|------|---------|---------|
| `--variant` | `T1-P` | `T1-P`, `T1-A` or `T1-O` |
| `--users` | `5` | simulated users (NFR2 max is 10; US-31 uses 10) |
| `--edits` | `50` | probe edits per document |
| `--interval` | `50` | milliseconds between probe edits |
| `--modules` | `5` | modules for T1-O |
| `--url` | `http://localhost:8081` | sync-service base URL |
| `--ws` | derived from `--url` | WebSocket base (ws/wss) |
| `--out` | *(stdout)* | write the result JSON to this path |

## Reading the results

The result JSON reports, per run:

- `latency_ms`: `p50_ms`, `p95_ms`, `max_ms` and the sample count. **Compare
  `p95_ms` to `nfr1_target_p95_ms` (200).** `nfr1_met` is `p95_ms < 200`.
- `machine`: hostname, OS, CPU count and Node version — US-31 must record which
  machine produced the numbers.
- `write_permission_check`: the per-edit permission check (NFR13) is **active**
  in every run, which is what CR13 asks to measure. A without-check baseline
  needs a server-side toggle that does not exist in `main` yet; when it does,
  run the same command against that build and compare `p95_ms`.
- `limits_in_force` and `room_full_check`.

Development-machine numbers are **not** the final figures (CR03). The final-host
run is **US-31 (Sprint 6)** — re-run the same command with `--url` pointing at the
deployed sync-service; nothing else changes.

## Known gaps (marked, not faked)

- **Room-size / `ROOM_FULL` (NFR15, US-63):** `sync-service` does not enforce a
  people limit yet, so the "one extra join beyond the limit" step records that
  the extra join was **accepted** and flags it as pending **US-63**. Once US-63
  adds the limit and `ROOM_FULL`, the same step will record the refusal.
- **Module / module-size limits (NFR15, US-63):** likewise not enforced yet; the
  document frame cap (1 MiB) is enforced by `DocumentHandler`.
