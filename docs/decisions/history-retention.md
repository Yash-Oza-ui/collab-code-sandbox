# Decision Record: Execution History Retention (US-30)

**Issue:** US-30 · #181  
**Service:** `sync-service`  
**Status:** Provisional — the retention period must be confirmed before FR15 is marked Final in Sprint 6.

## Context

`sync-service` owns the canonical in-memory `RunRecordStore` (US-52). FR15 requires old execution history to be removed after a configured period. US-68 uses earlier runs with the same `input_hash` to calculate a slowdown baseline, so retention must preserve enough history for a normal coding session. The baseline requires at least three earlier matching runs.

## Decision

- Use a configurable retention period with a proposed default of **86,400 seconds (24 hours)**.
- Configure it once in `sync-service/src/main/resources/application.properties` as `app.run-history.retention-seconds`, overridable with `APP_RUN_HISTORY_RETENTION_SECONDS`.
- Run a scheduled cleanup every **60,000 ms** by default, overridable with `APP_RUN_HISTORY_SWEEP_INTERVAL_MS`.
- The scheduled sweep and room lifecycle cleanup share the same per-room deletion primitive in `RunRecordStore`.
- The sweep logs the number of removed records only. It never logs code, inputs, hashes, tokens, or other user-written content.
- The sweep only removes run records. It does not modify room state or live code/documents.
- `ai-ml-service` measurements are cleared with the room by the existing US-56 `DELETE /v1/rooms/{room_id}/state` flow. US-30 does not add a second timer there.

## CR11 check

US-68 requires **at least three earlier runs with the same `input_hash`** before a slowdown baseline is established (`docs/ai-ml-contract.md`, §16.2). A normal session that produces three or more comparable runs within the proposed 24-hour window retains those samples. Sessions with fewer comparable runs remain in baseline-establishing mode; sessions extending beyond the window may lose older samples. This is an adequacy assumption, not a final retention decision.

## Revisit

Confirm the retention duration before FR15 is marked Final in Sprint 6. Recheck normal-session length and the US-68 baseline requirements before changing the default.
