# US-56 room data cleanup — implementation review note

## Lifecycle and privacy

- `RoomCleanupCoordinator.endRoom(roomId, reason)` is the shared **Spring-managed** cleanup path. Both `RoomService.closeRoom/deleteRoom` and US-55 `HostDepartureManager.closeRoom` delegate to it. Existing direct-constructor unit tests retain their legacy fallback path.
- `Room.close()` marks the room closed immediately. Control and document WebSocket sessions are closed with **4410**, then registry entries, Open-mode modules (including their saved versions and pending requests), canonical run records, members, block list, and the owner-token hash are removed.
- Keep an empty closed tombstone to return **4410** on future sockets for a previously closed room. An unknown room still returns **4404**. No public Host close/delete route is invented.
- Pair-mode document state lives in clients' Yjs/CRDT documents, not in a durable backend store. Disconnecting sockets ends access to the old document. There is no server-side persisted Yjs document to delete in the current repository.
- `execution-service` has no durable per-room run-history map: run records live in sync-service. It forwards authenticated `POST /internal/rooms/{room_id}/ended` to the preexisting AI endpoint `DELETE /v1/rooms/{room_id}/state`. The existing AI endpoint clears slowdown measurements, cache and usage counters according to its own API. No second AI deletion path is added.

## Failure and retry

- Local cleanup is complete before enqueueing remote work; remote failure cannot retain local code. `RoomEndedNotifier` sends only `room_id` and `reason`, authenticates with `X-Internal-Token`, and schedules at most three HTTP attempts with bounded timeouts and short backoff. It logs room id, attempt number and status, never request body or user code.
- Repeated notifications to execution-service are idempotent. A returned 200 means the AI DELETE returned 200; a failed downstream call returns 503 and is retried by sync-service.
- Retry state is in memory: a process restart can lose an in-flight remote cleanup. Durable retry/outbox requires a separate deployment decision.

## Collaboration and coverage

- The merged `docs/api-contracts.md` proposal still requests Bhavya Vekariya's and Prince Gadara's confirmation. The existing AI DELETE currently allows unauthenticated direct access; it receives `X-Internal-Token` from the new execution-service client but **does not itself enforce the token**. This security concern needs AI owner approval before a production deployment. Changing it here would break existing non-authenticated AI endpoint callers and tests.
- The regression tests cover sync-service local deletion and isolation, authenticated execution-to-AI forwarding and repeated calls, and retry after 503; `tests/integration/test-us56-room-cleanup.sh` exercises a live Host timeout and cross-service request in the integration stack.
- Run `sync-service` and `execution-service` Maven test suites, AI room-state tests, and full Docker integration tests before requesting merge. This package was not Maven-tested in the assistant environment because the Maven bootstrap URL was unavailable.
