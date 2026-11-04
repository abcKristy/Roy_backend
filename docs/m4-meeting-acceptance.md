# M4 server acceptance fixtures — T111

## Scope and baseline

04.11.2026. Local backend API v1, source baseline `59a189132c8136ccef2d8f08d0339a3b27849467`; Lead supplied the clean `tests/meeting-acceptance` branch after publishing pool 7. Task subject: `Add isolated meeting acceptance fixtures`.

Added reusable `src/test/kotlin/meetings/MeetingAcceptanceFixture.kt` and focused `MeetingAcceptanceFixtureIntegrationTest.kt`. Existing private fixtures, production services, routes and applied migrations were not changed. The card's historical MeetingModels link did not require changing a domain model.

The fixture owns one UUID point, two distinct preset topics/chats, three distinct members in both chats, and a fourth user with a real valid JWT/session and no membership. Explicit UTC anchor is `2099-02-10T15:00:00Z`: A=[15:00,15:10), B=[15:05,15:15), C=[15:10,15:20), D=[15:30,15:40). A/B and B/C intersect; A/C only touch; D is disjoint. Native create responses and JDBC timestamps prove all four stored intervals and the correct chat context. These deliberately future fixture dates are synthetic, not production schedules.

Normal fixture services retain PostgreSQL wall time. An explicit `controlledTime=true` opts into the existing creation Clock seam; `controlledCloseTime=true` prepares the existing close Clock seam. The test advances the controlled UTC creation clock, observes exact-boundary rejection, accepted future creation with server-createdAt, and late identical-key replay without writes. It does not claim close-boundary, vote/book Clock or T114 acceptance.

## Isolation and execution

Reused own container `codex-m4-response`, ID `b9cd122b11f0dfaa36d930a6ac8b3554eca4dcfef3f6bdf0e5d6cee3bc9ea871`, historical labels `codex.group=6`, `codex.task=m4-t087-t114`, now reused by group 11. Targeted inspect confirmed running state and only `127.0.0.1:55485` mapped to PostgreSQL5432. Created only `roy_m4_acceptance_fixture` with `docker exec codex-m4-response createdb -U m4_test roy_m4_acceptance_fixture` after confirming alias absence. TestDatabase disposable guard plus an exact loopback/port/alias guard protect the fixture. LOCAL bootstrap applied the real migrations; no production.env or external provider was used.

Commands ran with explicit workdir `C:\Users\lolut\.codex\worktrees\booking-response-event\backend`:

```powershell
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_acceptance_fixture -DatabasePort 55485
.\gradlew.bat integrationTest --tests com.example.meetings.MeetingAcceptanceFixtureIntegrationTest --offline --no-daemon --max-workers=1
```

Actual first focused run: **BUILD SUCCESSFUL in29s**, XML `tests=2, skipped=0, failures=0, errors=0`, suite6.32s, timestamp `2026-11-04T16:37:26.827Z`. Raw XML preserved in ignored `.local/t111-evidence/fixture-green.xml`; original is `build/test-results/integrationTest/TEST-com.example.meetings.MeetingAcceptanceFixtureIntegrationTest.xml`.

## Observed evidence and cleanup

The native Netty/JDK HTTP scenario proves same-point/two-topic context, all four intervals, member reads, valid authenticated outsider403 NOT_MEMBER and unauthenticated401 AUTH_REQUIRED against actual existing chats before creating proposals. Rejected commands leave the complete fixture SQL snapshot unchanged. Creation starts at0 votes/revision0/false personal flag. Identical-key201 replays and member GETs match original canonical JSON, with unchanged persisted proposal/vote/booking/message/notification/outcome/chat-sequence state. Controlled creation rejects at startsAt with422 INVALID_FIELD, accepts a future interval, and replays the old response after the controlled clock advances beyond its interval without consuming a new operation.

A second real-DB test deliberately throws after committed fixture seeding. Constructor failure cleanup physically removes its own point and all four users, preserves the original exception, and verifies namespace absence through an independent JDBC connection. Normal use cleanup similarly deletes only fixture point/users in one transaction and closes the runtime in finally; cascades remove their dependent records. No global truncate/reset or identity-gap assumption is used.

Post-run targeted SQL: PostgreSQL18.6/PostGIS3.6.4; Flyway15 successful records V001–V015. Counts for users/points/point_topics/chats/chat_memberships/auth_sessions/notification_settings/meeting_proposals/votes/bookings/messages/notification_events/push_delivery_jobs/operation_idempotency were respectively `0/0/0/0/0/0/0/0/0/0/0/0/0/0`. Noninternal `m4_%` triggers count0. No production defect was found in this task.

T111 establishes a reusable server fixture only. Full T6 chain with REST/WS IDs, overlap/cancellation/close, reconnect/event loss, actual concurrent races and all startsAt boundaries remain separate T112–T114 acceptance. No M5 delivery, Android/iOS UI, BG-1 or overall M4/T6 completion is claimed. Independent task review and Git actions are owned by Lead.
T111 independent read-only task review by contracts: APPROVE before commit; no confirmed findings. Lead synchronized all five external status places: T6 backend1/4, checklist109/455, M4 backend25/28. Setup-failure execution proves cleanup of its own namespace; preserving another live fixture is supported by scoped DELETE source, not claimed as a separate two-fixture runtime test.
