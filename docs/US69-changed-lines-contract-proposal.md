# US-69 / FR37.3 — Proposed Changed Lines Contract Specification

**Status: Proposed, awaiting review and approval from Digvijay Parmar (contract lead), Femina Rathod (room lifecycle), Bhavya Vekariya (run-record), and Het Kapadiya & Niranjan Panchal (client). Part of #220.**

## Summary & Privacy Invariant Rationale

Under US-68 (FR37, Sprint 5), the slowdown detection endpoint was implemented with `changed_lines: []` deferred to US-69. The original draft schema in `docs/schemas/slowdown-response.json` contained:
```json
"changed_lines": [ { "line": 2, "content": "    time.sleep(1.4)" } ]
```
**This original shape is not privacy-compliant:** returning or logging raw source-code line content violates project privacy invariants (NFR8.2, AC2). Raw code, line contents, diff text, tokens, or markers must never be persisted in room-scoped measurement memory, logged, or returned in telemetry/responses.

This proposal specifies the minimal contract changes required to safely support:
1. **Current line numbers to highlight** (1-based, pointing to the current editor version).
2. **Open mode module separation** (optional `module_id`).
3. **Removed-line count** (`removed_lines_count`).
4. **Availability status** (`comparison_available`).
5. **Human-readable comparison notes** (`comparison_note`, e.g., when no earlier normal run exists).

---

## Architectural Decision: Option B (Per-Line Hashes) vs Option A (Saved Versions)

### Option A Evaluation (Saved Versions)
- Investigation of `sync-service` (`RunRecord.java`, `SavedVersionSummary.java`) and `execution-service` (`SavedVersionReader.java`, `StubSavedVersionReader.java`) confirms:
  - `sync-service` stores only metadata summaries in `saved_versions` (`module_id`, `name`, `version`, `saved_by`, `saved_at`) and file names in `files`. It **explicitly contains no source code text**.
  - `sync-service` does not expose an API to fetch the full historical source code of past saved versions.
  - `execution-service`'s `SavedVersionReader` is a local stub awaiting future sync-service module versioning.
  - Furthermore, `POST /v1/analyses/slowdown` receives only `current_run.code` from `execution-service`, without past version identifiers.
- **Conclusion:** Option A is currently non-viable without major upstream changes to `sync-service` and `execution-service`.

### Option B Evaluation (Per-Line Cryptographic Hashes) — Selected
- For each qualifying **normal (non-slowdown)** run sharing the same `(room_id, input_hash)`:
  - `ai-ml-service` splits the code into lines and computes a SHA-256 cryptographic hash for each line.
  - Only the ordered list of SHA-256 hashes is retained in room-scoped in-memory memory (`SlowdownStore`).
  - Raw source code is **never** retained in `SlowdownStore`.
- On a flagged run (`is_slowdown == true`):
  - If a previous normal run's line hashes exist for `(room_id, input_hash)`, the service runs a deterministic line-diff algorithm (LCS) comparing previous hashes to current hashes.
  - Identifies 1-based line numbers in the **current code** that are added or modified.
  - Counts deleted lines and reports them via `removed_lines_count`.
- **Baseline preservation:** Flagged slowdown runs are **never** recorded into the baseline or the normal line-hash store, completely avoiding baseline poisoning.
- **Room lifecycle:** All stored hashes are keyed by `room_id` and are completely purged on `DELETE /v1/rooms/{room_id}/state` (FR32, US-56).

---

## Schema Changes (`docs/schemas/slowdown-response.json`)

### `changed_lines`
Replaces `{ "line": int, "content": str }` with:
```json
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "line": { "type": "integer", "minimum": 1 },
      "module_id": { "type": ["string", "null"] }
    },
    "required": ["line"],
    "additionalProperties": false
  }
}
```

### New Response Fields
| Field | Type | Required | Description |
|---|---|---|---|
| `removed_lines_count` | integer (>= 0) | Yes | Count of lines deleted since the previous normal run. Cannot be highlighted in current editor. |
| `comparison_available` | boolean | Yes | `true` if an earlier normal run existed and diff was computed; `false` otherwise. |
| `comparison_note` | string or null | Yes | Explanatory note, e.g. `"No earlier normal run to compare."` or `"1 line removed."`. |

---

## Response Examples

### 1. Slowdown Flagged with Changed Line (200 OK)
```json
{
  "request_id": "1c2d3e4f-5a6b-7c8d-9e0f-1a2b3c4d5e6f",
  "analysis_id": "2d3e4f5a-6b7c-8d9e-0f1a-2b3c4d5e6f7a",
  "task": "slowdown",
  "input_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "is_slowdown": true,
  "slowdown_factor": 14.2,
  "baseline": {
    "median_ms": 100.0,
    "mad_ms": 6.2,
    "sample_count": 7
  },
  "changed_lines": [
    {
      "line": 2
    }
  ],
  "removed_lines_count": 0,
  "comparison_available": true,
  "comparison_note": null,
  "message": "Execution runtime was 14.2x slower than normal baseline.",
  "ai_step": {
    "status": "ok",
    "error_code": null,
    "explanation": "Runtime regression caused by newly introduced blocking sleep call on line 2."
  }
}
```

### 2. Slowdown Flagged Without Earlier Normal Run (200 OK)
```json
{
  "request_id": "1c2d3e4f-5a6b-7c8d-9e0f-1a2b3c4d5e6f",
  "analysis_id": "2d3e4f5a-6b7c-8d9e-0f1a-2b3c4d5e6f7a",
  "task": "slowdown",
  "input_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "is_slowdown": true,
  "slowdown_factor": 10.0,
  "baseline": {
    "median_ms": 100.0,
    "mad_ms": 5.0,
    "sample_count": 3
  },
  "changed_lines": [],
  "removed_lines_count": 0,
  "comparison_available": false,
  "comparison_note": "No earlier normal run to compare.",
  "message": "Execution runtime was 10.0x slower than normal baseline. No earlier normal run to compare.",
  "ai_step": {
    "status": "skipped",
    "error_code": "CONSENT_MISSING",
    "explanation": null
  }
}
```

### 3. Normal Performance (Not a Slowdown, 200 OK)
```json
{
  "request_id": "1c2d3e4f-5a6b-7c8d-9e0f-1a2b3c4d5e6f",
  "analysis_id": "2d3e4f5a-6b7c-8d9e-0f1a-2b3c4d5e6f7a",
  "task": "slowdown",
  "input_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "is_slowdown": false,
  "slowdown_factor": 1.04,
  "baseline": {
    "median_ms": 100.0,
    "mad_ms": 6.2,
    "sample_count": 8
  },
  "changed_lines": [],
  "removed_lines_count": 0,
  "comparison_available": false,
  "comparison_note": null,
  "message": "Execution runtime is within normal parameters.",
  "ai_step": {
    "status": "ok",
    "error_code": null,
    "explanation": null
  }
}
```
