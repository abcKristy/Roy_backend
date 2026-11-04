# M4 group 6 — booking response and events

## Scope, base and ownership

Group 6 branch: `feat/booking-response-event`, own worktree
`C:/Users/lolut/.codex/worktrees/booking-response-event/backend`.
Lead verified clean starting main commit `ae43668958e3034ada605809d29f1d09a95969d4`.
This protocol and the group's own tests belong to group 6; master execution protocol,
global checklist and roadmap are integrated by Lead. No worker Git mutation was used.
T099 precedes T100; this recording does not claim T100 acceptance.

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
