# US-63: Resource Limit Rationale (NFR15)

## Purpose

US-63 introduces configurable limits on room membership, module count, and saved-module size to prevent a single room from exhausting the service.

## T-22 Baseline Measurements

The following tests were conducted on a local Windows development machine using the existing T-22 load harness.

| Scenario | Members | Modules | p95 Latency | NFR1 Target | Result |
|---|---:|---:|---:|---:|---|
| T1-O (Open Room) | 10 | 5 | 8.20 ms | 200 ms | PASS |
| T1-P (Pair Room) | 10 | Not applicable | 4.16 ms | 200 ms | PASS |

Evidence:
- `docs/us63-baseline/T1-O.json`
- `docs/us63-baseline/T1-P.json`

## Provisional Configuration Defaults

| Resource | Default | Reason |
|---|---:|---|
| Maximum people per room | 10 | Matches the required supported maximum for the US-31 final-host test. Both baseline scenarios supported 10 members with p95 below 200 ms. |
| Maximum modules per Open room | 10 | Provisional upper bound. T1-O confirms acceptable performance at five modules but does not establish the safety of ten modules. Further load testing is required. |
| Maximum saved-module size | 1,048,576 bytes | Provisional configuration value only. The US-45 save implementation and module-size testing are required before this limit can be enforced or validated. |

All three values are configurable using the corresponding environment variables listed in `docs/host-config.md`.

## Enforcement Locations

- **People limit:** `RoomService.join()` refuses excess members with `ROOM_FULL`. Existing members can reconnect without being counted twice.
- **Module count:** `ModuleService` enforces the limit when modules are created or pending requests are approved. Excess operations return `LIMIT_REACHED` (HTTP 409).
- **Module size:** Must be enforced where US-45 saves complete module content. This enforcement is pending until that functionality is available.

Pair mode has no modules, so module-count and module-size limits do not apply.

## Live Document Limitation

The WebSocket relay forwards Yjs document updates without parsing document content, as required by CR13. Consequently, a live document can grow between saves. A saved-module size limit cannot prevent that transient growth.

T-21 findings about deleted content retained within Yjs documents should be reviewed when available.

## Final-Host Verification

These are development-machine measurements, not final deployment results.

US-31 is responsible for repeating the load tests on the final deployment host and verifying that the supported room capacity of ten members rejects an additional join with `ROOM_FULL`.

## Outstanding Dependencies

- US-45: Saved-module size enforcement and preservation of the previous saved version after rejection.
- Additional module-count and module-size measurements.
- T-21: Review document growth caused by retained Yjs history.

The US-63 issue remains open until all acceptance criteria are implemented and verified.