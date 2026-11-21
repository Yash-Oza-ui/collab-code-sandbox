# US-72 / FR38.2 — Proposed saved test case contract

**Status: Proposed, awaiting approval from Digvijay (contract), Femina (room deletion), and Het/Niranjan (client UI).** This proposal is not an agreed team contract.

## Ownership and persistence

Store test cases in sync-service's **room-scoped in-memory state**, with member-scoped access. They are deleted when the room is ended/deleted by US-56. No program source code, source hash, or private session ID is stored as part of a saved-test record. Saved tests are intentionally not persisted after room deletion.

## REST endpoints

- `POST /v1/rooms/{roomId}/saved-tests`
- `GET /v1/rooms/{roomId}/saved-tests`

Both calls require `X-Session-Id` for an active room member. A member sees only their own saved tests, and the Save action is rendered only for an `analysis-result` received on the member's session. In Open mode, a private run's minimised input is not broadcast to other members, preserving US-49 visibility until shared.

Request:

```json
{
  "input": "1 2 3",
  "failure_type": "wrong_output",
  "language": "python",
  "expected_output": "6\n"
}
```

Response (`201 Created`):

```json
{
  "id": "example-uuid",
  "input": "1 2 3",
  "failureType": "wrong_output",
  "language": "python",
  "expectedOutput": "6\n"
}
```

The server validates strict fields and sizes, rejects invalid room sessions, and returns only the caller's records to GET. `expected_output` may be null unless `failure_type` is `wrong_output`. The client sends only the `test_case` object, never program code. `GET` returns an array of saved entries.

## Dependency note

The initial US-70 search and this US-72 minimiser share one request-level budget. US-71's batch runner owns per-batch limits and Docker isolation. Test execution is **never** performed by the client or ai-ml-service host.
