# Load-test harness (T-22: T1-P, T1-A, T1-O)

Measures **edit-sync latency** — how long an edit takes to reach the other users
in a room — against a running `sync-service`, over the real control and document
sockets with real Yjs text edits. Base for US-06, US-31 and US-63.

- **NFR1:** edits reach other users within **200 ms at the 95th percentile**,
  under test set-up **T1**.
- **NFR2:** 2–5 users typically, up to **10** maximum, under the T1 variants.

## Test set-up T1 (Requirements v2)

> 10 users in one room, a document of **~1,000 lines**, each typist **~5 characters/second**.

| Variant | Room | Who types | Who reads | Measured |
|---------|------|-----------|-----------|----------|
| **T1-P** | Pair, `teach_interview` | 1 user | the other 9 | the 1 typist, at 9 readers |
| **T1-A** | Pair, `debug_together` | **all 10** | — | **every** typist, at the other 9 |
| **T1-O** | Open, 5 modules (~200 lines each) | **5 owners**, one per module | the other 5 read **all 5** | every owner, at the 5 readers |

How it works: every user joins the real control socket and opens the real
document socket(s); each **typist** types characters at ~5/sec, and **every edit
is one Yjs transaction** that appends a character *and* stamps a probe
(`editor:seq:sendMs`). Receivers read the probe back, so **every typist is
measured at every receiver** (p50/p95/max aggregated across all of them). All
users run in one process → one clock, no skew. Content is generated lines — no
real user data or code. T1-O uses the **real module workflow**: each of the five
owners requests their own module and the Host approves it (FR21.1–21.3), so the
requester owns and edits it.

## Install and run (benchmark)

```bash
cd tests/load
npm install

# Full T1 benchmark (10 users, ~5 chars/sec, ~1000-line doc):
node harness.mjs --variant T1-P --users 10 --edits 50 --out results/T1-P.json
node harness.mjs --variant T1-A --users 10 --edits 50 --out results/T1-A.json
node harness.mjs --variant T1-O --users 10 --edits 50 --modules 5 --out results/T1-O.json
```

| Flag | Default | Meaning |
|------|---------|---------|
| `--variant` | `T1-P` | `T1-P`, `T1-A`, `T1-O` |
| `--users` | `10` | simulated users (NFR2 max 10) |
| `--edits` | `50` | measured edits per typist |
| `--interval` | `200` | ms between a typist's edits (200 ≈ 5 chars/sec) |
| `--modules` | `5` | modules for T1-O |
| `--seed-lines` | `1000` | lines the document is pre-seeded to (T1-O splits across modules) |
| `--url` | `http://localhost:8081` | sync-service base URL |
| `--out` | *(stdout)* | write the result JSON here |

Compare `latency_ms.p95_ms` to `nfr1_target_p95_ms` (200). Development-machine
numbers are **not** final (CR03); the final-host run is **US-31** — re-run the
same command with `--url` at the deployed service.

## CR13 — cost of the write-permission check

NFR13 checks write permission on every edit; CR13 asks for its latency cost. The
check lives in `sync-service` (`DocumentHandler`) and is gated by a **test-only**
switch (`LOADTEST_DISABLE_EDIT_CHECK`, default **off** — the check is always on in
production). Run the same workload twice and compare:

```bash
# 1) check ON (default): start sync-service normally, then
node harness.mjs --variant T1-A --users 10 --edits 50 --out results/T1-A-checks-on.json --label checks-on

# 2) check OFF (test only): restart sync-service with the flag, then
#    LOADTEST_DISABLE_EDIT_CHECK=true  (env)  or  -Dloadtest.editCheckDisabled=true  (JVM)
node harness.mjs --variant T1-A --users 10 --edits 50 --out results/T1-A-checks-off.json --label checks-off

# 3) compare
node cr13-compare.mjs results/T1-A-checks-on.json results/T1-A-checks-off.json
```

Repeat for T1-P and T1-O. **NFR13 is verified only when NFR1 (p95 < 200 ms) holds
with the check ON** (CR13). The switch never bypasses permissions in production.

## Known gaps (marked, not faked)

- **Room-size / `ROOM_FULL` (NFR15, US-63):** `sync-service` does not enforce a
  people limit yet. The harness attempts one extra join beyond the user count and
  records `room_full_check.status: "PENDING_US-63"` (the extra join is accepted).
  It already detects a `ROOM_FULL` refusal, so once US-63 lands the same step
  records `ENFORCED` with no harness change.

## Results: benchmark vs CI smoke

- **Benchmark** results (`kind: "benchmark"`) come from a full 10-user run and are
  what US-06/US-31 cite. Examples are in `sample-results/`.
- **CI smoke** (`kind: "smoke"`, `tests/integration/test-load-smoke.sh`) runs a
  *tiny* T1-P, T1-A and T1-O (2–3 users, a few edits) only to prove each variant's
  topology, connections, propagation and latency collection work. Its numbers are
  **not** benchmark figures.

`node_modules/` and `results/` are git-ignored.
