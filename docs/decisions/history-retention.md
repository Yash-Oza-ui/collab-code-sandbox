# Decision Record: Execution History Retention (US-30)

**Issue:** US-30 · #181  
**Service:** `sync-service`  
**Status:** Provisional — the retention period must be confirmed before FR15 is marked Final in Sprint 6.

## Context

`sync-service` owns the canonical in-memory `RunRecordStore` (US-52). FR15 requires old execution history to be removed after a configured period. US-68 calculates the slowdown baseline using a separate `ai-ml-service` room-scoped measurement history, not the canonical `sync-service` records. The baseline needs at least three earlier matching measurements and is not governed by this retention sweep.

## Decision

- Use a configurable retention period with a proposed default of **86,400 seconds (24 hours)**.
- Configure it in `sync-service/src/main/resources/application.properties` as `app.run-history.retention-seconds`, overridable with `APP_RUN_HISTORY_RETENTION_SECONDS`; Docker Compose explicitly forwards the host `.env` value (default `86400`).
- Run a scheduled cleanup every **60,000 ms** by default, overridable with `APP_RUN_HISTORY_SWEEP_INTERVAL_MS` forwarded by Docker Compose (default `60000`).
- The scheduled sweep and room lifecycle cleanup share the same per-room deletion primitive in `RunRecordStore`.
- The sweep logs the number of removed records only. It never logs code, inputs, hashes, tokens, or other user-written content.
- The sweep only removes run records. It does not modify room state or live code/documents.
- `ai-ml-service` measurements are cleared with the room by the existing US-56 `DELETE /v1/rooms/{room_id}/state` flow. US-30 does not add a second timer there.

## CR11 check

US-68 requires **at least three earlier measurements with the same `input_hash`** before a slowdown baseline is established (`docs/ai-ml-contract.md` §16.2). `ai-ml-service` maintains these measurements separately in `room_histories[room_id][input_hash]` for the room/session lifetime. The US-30 sweep only ages out canonical `sync-service` run records; **it does not reduce the US-68 sample count or reset the slowdown baseline**. Room teardown clears both stores through their respective lifecycle paths. The 24-hour proposal is therefore a canonical-history retention decision, not a slowdown-baseline window.

## Revisit

Confirm the retention duration before FR15 is marked Final in Sprint 6. Recheck host storage requirements and normal-session length before changing the default; the independent US-68 baseline does not use this retention window.
