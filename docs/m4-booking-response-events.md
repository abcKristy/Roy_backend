# M4 group 6 — booking response and events

## Scope, base and ownership

Group 6 branch: `feat/booking-response-event`, own worktree
`C:/Users/lolut/.codex/worktrees/booking-response-event/backend`.
Lead verified clean starting main commit `ae43668958e3034ada605809d29f1d09a95969d4`.
This protocol and the group's own tests belong to group 6; master execution protocol,
global checklist and roadmap are integrated by Lead. No worker Git mutation was used.
T099 precedes T100; ordered per-task execution evidence is recorded below.

## T099 / M4.T3.B04 — canonical booking response

Existing MeetingProjection/MeetingModels behavior was retained; no production,
route, service or migration change was made. BookingResponseIntegrationTest uses
native loopback Netty/JDK HTTP and real JDBC sessions on own roy_m4_booking_response.
Its three members have distinct proposer, actual booking actor and nonvoting viewer.
The fixture has two distinct point IDs and three topic chats with seven proposals.

A successful confirmed native booking checks the complete canonical Proposal and
Booking keys, actual owner different from proposer, server order, active status and
null cancellation metadata. Immediate detail and batched list snapshots agree with
201. The observer changes only personal viewerGoing while the common booking stays
identical. Direct active conflicts are sorted by database booking_order despite
seeded bookedAt/start timestamps deliberately ordered differently. A merely transitive
slot, exact boundary touch, cancelled booking and an equal-coordinate different point
are excluded from direct overlaps. The sorted public conflict fields match stored
IDs/intervals/order and timestamps.

A cancelled-only history projects its cancelled booking and ready_to_book proposal,
with cancellation metadata and no active overlap list. An older cancelled target
booking remains unchanged after rebooking with a new active ID/larger order.
Repeated GET and exact idempotent replay preserve booking/order, proposal revisions,
SYSTEM rows, notifications, outcomes and point timestamps. Fixture cleanup deletes
only the own two points and three accounts. Mobile/device UI, M5 delivery,
client acceptance, cancellation policy, combined T3–T5/T6 and BG-1 are not inferred.

## Execution evidence

Targeted pre-run Docker inspection verified container codex-m4-response,
`b9cd122b11f0dfaa36d930a6ac8b3554eca4dcfef3f6bdf0e5d6cee3bc9ea871`,
labels codex.group=6 and codex.task=m4-t087-t114, running, exclusively127.0.0.1:55485.
The ignored absolute local environment recipe was dot-sourced for the own alias and
port; no environment contents or credentials are recorded. Exact Gradle command,
with the own worktree as explicit working directory:

`.\gradlew.bat integrationTest --tests com.example.meetings.BookingResponseIntegrationTest --offline --no-daemon --max-workers=1`

04.11.2026 first real run: BUILD SUCCESSFUL in2m3s, exit0;6 tasks executed.
Actual XML `build/test-results/integrationTest/TEST-com.example.meetings.BookingResponseIntegrationTest.xml`:
tests=1/skipped=0/failures=0/errors=0; timestamp2026-11-04T14:51:05.050Z,
suite7.506s/test7.503s. No T099 failed run or confirmed production finding occurred.
The test source was unchanged after this PASS. Existing baseline compiler/Gradle
deprecation warnings did not prevent actual integration execution.

Exact post-run read-only command (same explicit worktree):

```powershell
docker exec codex-m4-response psql -U m4_test -d roy_m4_booking_response -At -c "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version IS NOT NULL; SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM points),(SELECT count(*) FROM chats),(SELECT count(*) FROM meeting_proposals),(SELECT count(*) FROM votes),(SELECT count(*) FROM bookings),(SELECT count(*) FROM messages),(SELECT count(*) FROM notification_events),(SELECT count(*) FROM push_delivery_jobs),(SELECT count(*) FROM operation_idempotency),(SELECT count(*) FROM auth_sessions);"
```

It confirmed own alias, PostgreSQL18.6/PostGIS3.6.4, successful V001–V015 and
eleven zero fixture counts in that displayed order. No applied migration changed.
The three worker-owned external status files updated were
`spec/plans/tasks/M4/T3/B04-booking-snapshot.md`, `spec/plans/tasks/M4/T3/README.md`
and `spec/plans/tasks/M4/M4-T3.md` (backend4/5). They are physically outside backend
Git. Global checklist/roadmap and master protocol were left to Lead as assigned;
no claim is made that they have already been synchronized by this worker.
Commit subject: `Verify canonical booking response snapshots`.
Independent task review and commit are separate; no unreceived APPROVE or T100,
whole parent, combined server, M5 delivery, client or BG-1 acceptance is claimed.

Lead synchronized the two shared external status registers before review: checklist97/455, T3 backend4/5, M4 backend13/28 at that checkpoint. Independent read-only T099 task review: APPROVE before commit.

T099 task review: contracts APPROVE before Lead commit
`43f9edccf21cc777bca0620d06b87ac7cbc8ac58`. The three worker-owned external
subject references now carry actual short SHA43f9edc.

## T100 / M4.T3.B05 — committed booking WS events

Added BookingEventsIntegrationTest in the same group 6 worktree, guarded to own
roy_m4_booking_events on codex-m4-response/127.0.0.1:55485. No production, migration,
route exposure or deployment was changed. Native Netty/JDK HTTP/WS uses real JDBC
sessions and four sockets: booker/peer subscribed to the target chat, a separate
peer socket subscribed only to the adjacent topic, and a nonmember whose target
subscription is explicitly rejected. HTTP member checks also deny the outsider.

An own-database SHARE lock on operation_idempotency blocks the actual final
booking outcome INSERT. Bounded pg_locks observation must find its waiting
RowExclusiveLock; SQL and GET stay at the pre-commit ready_to_book/bookingnull
snapshot, the HTTP future remains pending, and ordered USER sentinels show no
premature booking/SYSTEM event. The gate is released before bounded25-second
lock/30-second statement/35-second HTTP timeouts expire.

After201, target members receive identical event IDs/content for exactly
chat.message.created (canonical system/meeting_booked), proposal.updated and
booking.changed with active status and null cancelReason. Full envelope/Message/
SYSTEM/shared payload keys, IDs, scope, timestamps and revision are compared with
committed PostgreSQL. viewerGoing is absent from common WS payloads. Adjacent and
rejected sockets have no private target events through ordered USER markers plus
250ms observation; the outsider's REST denial does not contain the private Booking ID.
No booking.cancelled envelope is introduced or expected.

Exact replay produces no new business effects or WS frames. A key/actor/operation
scoped deferred constraint trigger raises SQLSTATE23514 at COMMIT for a second
proposal: real HTTP500 must leave the committed SQL baseline and ready GET unchanged
and emit nothing. Dropping the fault allows same-key retry, committed events once,
then an effect-free replay. Cleanup removes fault trigger/function, server/sockets
and only the own fixture identities. This is bounded local server wire evidence,
not unbounded exactly-once, full cancellation/race, M5 delivery, client or BG-1 acceptance.

### T100 actual execution

Exact command, with working directory
`C:/Users/lolut/.codex/worktrees/booking-response-event/backend`:

`.\gradlew.bat integrationTest --tests com.example.meetings.BookingEventsIntegrationTest --offline --no-daemon --max-workers=1`

The ignored absolute local recipe was dot-sourced for roy_m4_booking_events and
DatabasePort55485; no contents/credentials were displayed or recorded for this task.
Pre-run targeted inspection confirmed the same group6/task-labeled container ID
b9cd122b11f0dfaa36d930a6ac8b3554eca4dcfef3f6bdf0e5d6cee3bc9ea871, running,
exclusively127.0.0.1:55485. Authorized escalation was limited to own worktree writes,
Gradle cache and the Docker pipe; no security configuration was changed.

04.11.2026 BUILD SUCCESSFUL in55s, exit0;2 tasks executed,4 up-to-date. Actual XML
`build/test-results/integrationTest/TEST-com.example.meetings.BookingEventsIntegrationTest.xml`:
tests=1/skipped=0/failures=0/errors=0, timestamp2026-11-04T15:08:05.414Z,
suite16.283s/test16.279s. No failed T100 run or confirmed production finding occurred;
the executable test source was unchanged after PASS.

Exact post-run SQL command:

```powershell
docker exec codex-m4-response psql -U m4_test -d roy_m4_booking_events -At -c "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version IS NOT NULL; SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM points),(SELECT count(*) FROM chats),(SELECT count(*) FROM meeting_proposals),(SELECT count(*) FROM votes),(SELECT count(*) FROM bookings),(SELECT count(*) FROM messages),(SELECT count(*) FROM notification_events),(SELECT count(*) FROM push_delivery_jobs),(SELECT count(*) FROM operation_idempotency),(SELECT count(*) FROM auth_sessions),(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'm4_booking_ws_fault_%'),(SELECT count(*) FROM pg_proc WHERE proname LIKE 'm4_booking_ws_fault_%');"
```

It confirmed own alias, PostgreSQL18.6/PostGIS3.6.4, successful V001–V015 and
thirteen zero fixture/fault counts in that order. No migration was added or changed.
The worker updated only T3/B05-booking-event.md, T3/README.md and M4-T3.md
(backend5/5 locally). These three external status files remain outside backend Git;
shared checklist/roadmap/master updates belong to Lead. All client components and
combined T3–T5/T6/M5/BG-1 acceptance remain open.
Commit subject: `Verify committed booking websocket events`.
Independent T100 task review and group review are separate; unreceived APPROVE
is not claimed by this execution record.
T100 independent read-only task review by contracts: APPROVE before commit. Lead synchronized shared external T100 status registers; T3 backend5/5. After parallel T102/T107 checkpoints, checklist102/455 and M4 backend18/28; full parent/client/BG-1 remain open.
