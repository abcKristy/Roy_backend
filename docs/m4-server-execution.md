# M4 server execution

## Scope and topology

Backend Git root: `project/backend`; target: `main`, remote: `origin/main`.
The task cards and status registers under `spec/plans/` are outside backend Git.
Updating those registers does not version their physical contents in backend `main`.
Client tasks, BG-1, M5 nearby/reminder delivery and overall M4 acceptance are separate scopes.

Preparation found registered worktrees `backend-chat` and `pool-1` through `pool-4`
under `project/worktrees`, but read-only status checks confirmed those paths are absent
in this checkout. Their registrations were left untouched (no prune or deletion).
The main checkout is the only currently accessible registered working tree; no claim
is made that every registered tree is clean. This does not block the sequential first pool.

## Group 1 — T087 / M4.T1.B01 — proposal schema

Base: verified `main=origin/main` at `2f54ac0734004308aaf5e5d2f0b3a8253772c168`.
Working branch: `feat/proposal-creation`.

Existing V010 already implements proposal chat/proposer foreign keys, the positive interval,
nonnegative revision and lifecycle/reason checks, plus the interval immutability trigger.
No applied migration was rewritten and no redundant migration was added.

Added `ProposalSchemaIntegrationTest` with its own guarded `roy_m4_schema` alias.
It exercises UTC offset round-trip, default OPEN/revision zero, invalid foreign keys,
equal/reversed intervals, invalid revisions and lifecycle/reason combinations,
independent start/end mutation rejection, permitted closure/revision updates,
proposer deletion preserving history and chat deletion cascading the proposal.
All synthetic fixture rows are enclosed in a transaction rolled back in `finally`;
schema preparation uses the existing LOCAL migration bootstrap.

Executed 04.11.2026 on newly created owned container `codex-m4-disposable`
(`8b30a5dd02db7ef3a1cdd8bae238b5d7d9af7b7ca163bc10ee2794c0fa5d566b`,
label `codex.task=m4-t087-t114`, loopback port 55484, own anonymous volume).
PostgreSQL 18.6 / PostGIS 3.6.4; Flyway V001–V015 all successful.
Command: `.\gradlew.bat integrationTest --tests com.example.meetings.ProposalSchemaIntegrationTest --offline --no-daemon --max-workers=1 --rerun-tasks`.
Environment: JDK17, TEST_DATABASE_URL uses loopback/roy_m4_schema,
TEST_DATABASE_DISPOSABLE=true, dedicated synthetic DATABASE_USER/PASSWORD (not recorded).
Result: BUILD SUCCESSFUL; JUnit tests=1, skipped=0, failures=0, errors=0.
After run SQL counts users/points/meeting_proposals = 0/0/0: fixture rollback confirmed.
The first sandbox attempt failed at the Gradle cache lock before execution;
the authorized retry above executed the actual test.
External accounting updated: T1/B01-proposal-schema.md, T1/README.md,
execution-checklist.md, M4-T1.md and M4-meetings-booking.md. Those files remain
outside backend Git. Their receipt references this task's unique commit subject
`Verify proposal schema invariants`; the resulting SHA is recorded after commit.
Client tasks, remaining server cards and the parent milestone are not marked complete.

Task review: independent read-only APPROVE received before commit.
Task commit: `f841281f54ac4cbe483cfebc6a49a245cd1ee732` (`Verify proposal schema invariants`).

## Group 1 — T088 / M4.T1.B02 — proposal time

Added an optional injected `Clock` for creation, sampled after the existing actor,
point and chat locks. The production default retains PostgreSQL wall time.
Vote, booking and expiry clocks are unchanged by this task.

Added `ProposalTimeIntegrationTest` with its own guarded `roy_m4_time` alias.
The deterministic HTTP scenario exercises a start one microsecond before server time,
the exact boundary, equal/reversed endpoints, invalid timestamp, and a successful
start one microsecond in the future. It checks HTTP422/stable `INVALID_FIELD`,
absence of proposal/message effects for invalid requests, offset input normalized
to UTC in HTTP and PostgreSQL, and snapshot replay after the clock passes the end.
A new late request is rejected; replay keeps one proposal, one SYSTEM message and
one idempotency record. Its synthetic point/user namespace is removed in `finally`.

The initial representable-microsecond test run executed one test with zero skips,
failures and errors. Independent review then requested changes for a P2 precision
defect: raw nanosecond validation could accept a start that becomes equal to server
time in PostgreSQL, or let a positive interval collapse during storage.
The added real HTTP regression reproduced the first defect using
`.\gradlew.bat integrationTest --tests com.example.meetings.ProposalTimeIntegrationTest --offline --no-daemon --max-workers=1`:
JUnit tests=1, skipped=0, failures=1, errors=0; expected422, actual201.
This RED result is a defect reproduction, not acceptance.

The correction canonicalizes input start/end to microseconds before persisted interval
validation, comparison with the exact sampled server time, insertion and SYSTEM payload
construction. It keeps the clock value authoritative. The existing full-precision
Instant hash is retained to preserve historical idempotency records; raw interval
sanity remains first, and the new persisted-interval check runs after replay/key-reuse
lookup. Thus a successful historical fractional request can still replay its snapshot.
The regression additionally checks a collapsing nanosecond interval, accepted
fractional offset input, canonical UTC SYSTEM payload and an equivalent UTC spelling
of the same full-precision instants on replay. Different nanoseconds still conflict.
A seeded historical success whose 100ns–900ns interval previously rounded to a valid
one-microsecond stored interval verifies preserved snapshot replay, no callback,
no new writes and correct rejection of a changed full-precision request.
Corrected real run: `.\gradlew.bat integrationTest --tests com.example.meetings.ProposalTimeIntegrationTest --offline --no-daemon --max-workers=1 --rerun-tasks`.
04.11.2026: BUILD SUCCESSFUL in 2m17s; JUnit tests=1/skipped=0/failures=0/errors=0.
Same owned container and versions as T087, separate `roy_m4_time` database,
LOCAL bootstrap V001–V015. Post-run users/points/proposals/messages/outcomes=0/0/0/0/0.
External accounting updated: T1/B02-proposal-time.md, T1/README.md,
execution-checklist.md, M4-T1.md and M4-meetings-booking.md, all outside backend Git.
Task commit subject: `Validate proposal time at storage precision`.

Independent read-only repeat task review: APPROVE before commit.
Task commit: `bd0e71d275b6beefcbf09b560c80938e340941e2`.

## Group 1 — T089 / M4.T1.B03 — zero-vote creation and transaction

Existing production creation/member checks and idempotency were retained without
rewriting implemented behavior. Added `ProposalCreateIntegrationTest`, guarded to
the own `roy_m4_create` disposable database. Its private fixture creates two chats
on separate topics of one synthetic point, a member and an outsider, and removes
only that namespace in `finally`.

The first scenario checks outsider refusal with no writes; member creation with
goingCount=0, viewerGoing=false, voting status, null booking and revision zero;
exact status201/production `proposalJson` snapshot; one `meeting_proposed` SYSTEM
message and one outcome; replay without callback or duplication; and changed
chat/start/end hash conflicts. A newer persisted revision is preserved by replay.

The second scenario deliberately throws from the success-body callback after
observing the uncommitted proposal, SYSTEM message and chat sequence increment.
It verifies rollback of scoped proposal/vote/message/outcome counts and chat
sequence, followed by successful reuse of the same previously unconsumed key
and effect-free exact replay. There are no nearby recipients in this fixture;
M5 delivery acceptance is not inferred from these transaction checks.

Executed 04.11.2026 on own `roy_m4_create` in the same verified disposable
PostgreSQL18.6/PostGIS3.6.4 container; LOCAL bootstrap V001–V015.
Command: `.\gradlew.bat integrationTest --tests com.example.meetings.ProposalCreateIntegrationTest --offline --no-daemon --max-workers=1 --rerun-tasks`.
BUILD SUCCESSFUL in 3m9s; JUnit tests=2/skipped=0/failures=0/errors=0.
SQL post-run users/points/proposals/messages/outcomes=0/0/0/0/0.
External accounting updated: T1/B03-proposal-create.md, T1/README.md,
execution-checklist.md, M4-T1.md and M4-meetings-booking.md, outside backend Git.
Task commit subject: `Verify atomic zero vote proposal creation`.
Remote recheck before group completion: fetch succeeded, main=origin/main still
`2f54ac0734004308aaf5e5d2f0b3a8253772c168`.

Independent read-only task review: APPROVE before T089 commit
`a8302d2d2d1b5be6a050abd72810f47487ae82b3`.
The independent second-level group reviewer approved all three group 1 task commits
and their full diff. Group 1 merged into main as
`82d13a37acc968b10a08cb9c3f4729cebd8d91ea`; push and `ls-remote` verified
`main=origin/main` at that commit, with a clean accessible main working tree.
Pool 1 completed before pool 2 began; no implementation groups ran in parallel.

## Group 2 — T090 / M4.T1.B04 — proposal history and current pin

Working branch `feat/proposal-read-events` starts at the verified published group 1
merge `82d13a37acc968b10a08cb9c3f4729cebd8d91ea`.
Existing `MeetingProjection`, `ProposalService` read paths and `ChatReadService`
nearest-future-open selection were retained without a speculative rewrite.

Added `ProposalReadIntegrationTest`, guarded to own disposable `roy_m4_read`.
The synthetic fixture contains six proposals: future open alternatives including
an equal-start tie, an earlier explicitly closed proposal, a started active booked
proposal and a started unbooked proposal. Creation timestamps give a deterministic
complete three-page history independent of start order.

The scenario checks list/detail and the production personal REST serializer,
different actor/peer viewerGoing with the same count, nonmember denial including
closed and booked records, scoped/signed cursor rejection and repeatable pages.
Chat detail and myChats select the nearest future OPEN pin, breaking equal-start
ties by proposal ID. Closing candidates advances the pin and finally clears it
without removing history. The started booking remains booked/ACTIVE with no
cancellation metadata; the started unbooked record projects closed. Synthetic
point/user rows are removed in `finally`. WS payload acceptance is a separate T091
scope; this service/projection test does not claim wire-level event verification.

Executed 04.11.2026 on own `roy_m4_read`, PostgreSQL18.6/PostGIS3.6.4,
LOCAL bootstrap V001–V015 in the verified disposable container.
Command: `.\gradlew.bat integrationTest --tests com.example.meetings.ProposalReadIntegrationTest --offline --no-daemon --max-workers=1`.
BUILD SUCCESSFUL in 1m27s; JUnit tests=1/skipped=0/failures=0/errors=0;
2 tasks executed, 4 up-to-date (the integration test itself executed).
Post-run SQL users/points/proposals/bookings=0/0/0/0.
External accounting updated: T1/B04-proposal-read.md, T1/README.md,
execution-checklist.md, M4-T1.md and M4-meetings-booking.md, outside backend Git.
Task commit subject: `Verify proposal history and current pin`.

Independent read-only task review: APPROVE before T090 commit
`a24e8793309b5ca52d3a96b75f5f8f717217825e`.

## Group 2 — T091 / M4.T1.B05 — committed creation events and public nearby hook

Retained the existing transactional SYSTEM/nearby producer and post-commit route
publication. Added `ProposalEventsIntegrationTest`, scoped to own disposable
`roy_m4_events`, with real JDBC sessions, a native loopback Netty server and JDK
HTTP/WebSocket clients. The three synthetic accounts are author, peer and nearby
nonmember; all have nearby opt-in/location, so exclusion of author and members is
observable. Four sockets cover author/peer in the target chat, peer in the adjacent
topic, and the nearby account whose target-chat subscription is explicitly rejected.

The test uses an own-database SHARE table lock to stop the final outcome INSERT,
observes its actual waiting RowExclusiveLock through pg_locks within five seconds,
and checks an independent committed SQL snapshot is empty. Ordered USER sentinels
verify no proposal/chat event is visible while the producing transaction remains
blocked. The test runtime has bounded 20-second lock/30-second statement timeouts;
it verifies the request is still pending before release. After releasing the gate,
it expects201, matching target-socket event IDs, persisted SYSTEM/proposal IDs and
revision, chat scope, and common payloads without viewerGoing. Adjacent and rejected
sockets receive no proposal events through an ordered sentinel plus250ms window.

The public notifications HTTP projection is checked for point-only targeting,
null chatId/proposalId and absence of private values in its response. The private
database may retain proposal_id for lifecycle/dedup: exactly one nearby row for
the eligible outsider uses `nearby:<proposalId>`; author and member get none.
An exact replay expects the same201 snapshot, unchanged rows and no new WS events.

A temporary trigger, scoped to the synthetic author and create operation, raises
nonretryable SQLSTATE23514 at the final outcome INSERT. The real HTTP request must
return500 while proposal/vote/SYSTEM/outcome/nearby counts and chat sequence remain
unchanged, public invitations stay unchanged and all sockets stay silent through
ordered markers. After dropping the trigger the same key can create successfully.
Trigger/function cleanup and fixture deletion target only their own names/IDs.

This is a bounded local server test, not unbounded exactly-once delivery, external
push delivery, the M5 reminder/nearby acceptance or BG-1. Actual execution evidence
and its limitations are recorded below; implementing assertions alone is not PASS.

First required command: `.\gradlew.bat integrationTest --tests com.example.meetings.ProposalEventsIntegrationTest --offline --no-daemon --max-workers=1`.
It actually executed and failed in1m41s: JUnit tests=1,
skipped=0, failures=1, errors=0. The test expected a top-level `systemType`,
but the canonical production Message serializer exposes lowercase `type=system`
and the nested `system.kind`/`system.proposalId` fields. The resulting test
NullPointerException at assertEvents was an incorrect test expectation, not a
confirmed backend defect. Independent review requested changes to the test.
The corrected expectations now compare the exact Message/system/event-envelope
keys and the complete proposal.updated payload (including status/count/bookingId),
plus matching event timestamps. No production change was made for this failure.
Corrected real command: `.\gradlew.bat integrationTest --tests com.example.meetings.ProposalEventsIntegrationTest --offline --no-daemon --max-workers=1`.
04.11.2026: BUILD SUCCESSFUL in1m52s, JUnit tests=1/skipped=0/failures=0/errors=0;
the integration task executed (2 tasks executed,4 up-to-date).
Own `roy_m4_events`, verified disposable container, PostgreSQL18.6/PostGIS3.6.4,
LOCAL bootstrap V001–V015. Post-run users/points/proposals/messages/outcomes/
notifications/custom fault triggers/custom fault functions=0/0/0/0/0/0/0/0.
External accounting updated: T1/B05-proposal-producer.md, T1/README.md,
execution-checklist.md, M4-T1.md and M4-meetings-booking.md, outside backend Git.
Task commit subject: `Verify committed proposal events and nearby targets`.
M4.T1 backend B01–B05 accepted locally; client, M5 external delivery and BG-1
remain outside this result. B4 as a whole is not accepted by this group alone.

Independent read-only T091 repeat task review: APPROVE before commit
`569a8a84be98d977d4bf81189ce3036bc4d9a2e8`.
The separate second-level group reviewer approved T090/T091 commits and their full
diff against current main. Group 2 merged and published as
`fa43431df668208128adcba9ccadedadf4fc6ffa`. Push, fetch and ls-remote verified
`main=origin/main` at that commit and the accessible main working tree was clean.
Pool 2 finished before pool 3 began; groups remained sequential.

## Group 3 — T092 / M4.T2.B01 — own desired-state vote writes

Branch `feat/meeting-votes` starts from verified published main
`fa43431df668208128adcba9ccadedadf4fc6ffa`.
Existing production PUT/service desired-Boolean behavior, actor locks and unique
vote key were retained. Added `VoteWriteIntegrationTest` with own guarded
`roy_m4_vote`, real JDBC sessions and native loopback HTTP against Netty.

The first scenario checks PUT without expectedRevision; initial false and repeated
true/false leave canonical snapshots and scoped DB effects unchanged. A peer's
false request cannot remove the author's vote. A direct duplicate insert verifies
SQLSTATE23505 for (proposal,user). The response uses the production Proposal fields
and equals subsequent GET. Strict unknown userId/expectedRevision/missing/wrong-type
payloads return422 INVALID_FIELD; malformed JSON returns400 INVALID_JSON. Missing
authentication, nonmembership, started/closed proposals and missing resources use
the existing401/403/409/404 stable codes without changing votes, revisions, SYSTEM
messages, notifications or chat sequence. Started/closed true and false are checked.

The second scenario releases two distinct native HTTP voters from a shared
ready/start latch on two worker threads. It checks both successful responses,
revisions1/2 and counts1/2, both unique persisted actors and final count2/revision2,
then verifies a repeated true produces no additional effects. Futures, HTTP calls,
barriers and worker shutdown are bounded. This is a concurrent two-request case,
not exhaustive vote/booking race acceptance. Both fixtures delete only their own
point and account namespaces in finally.
The later confirmed transport-only correction is recorded below; the existing
vote transaction/storage implementation remains unchanged.

The initial implementation required the corrections and real executions recorded below.

The first command `.\gradlew.bat integrationTest --tests com.example.meetings.VoteWriteIntegrationTest --offline --no-daemon --max-workers=1`
failed during test compilation in1m27s, before any integration scenario executed:
the fixture initializer called suspend `resolvedConnectors()` from a non-suspend
context at line171. This is a test-compilation error, not integration acceptance
or a confirmed backend defect. The approved correction wraps connector resolution
in `runBlocking`; HTTP/race semantics and production code are unchanged.
The next real run using the same command executed both tests and failed in1m27s:
JUnit tests=2, skipped=0, failures=1, errors=0; the invalid-type loop expected422
but quoted `going` received200. The separate concurrent-voter test executed.
Independent review confirmed a P2 contract defect: the default Boolean JSON
decoder accepted quoted boolean text despite the required native Boolean DTO.

The approved narrow correction adds a private serializer solely to VoteInput.going.
It accepts only an unquoted JsonPrimitive whose booleanOrNull is nonnull, throwing
SerializationException for other shapes so the existing strict request handling
returns422 INVALID_FIELD. It retains Boolean as the DTO type and serializes with
encodeBoolean; no global parser or other DTO was changed. The rejected-type matrix
now includes quoted true/false, null, numbers, object and array, with synthetic-body
diagnostics on status failures and unchanged scoped DB effects. Native true/false
remain covered by the desired-state and concurrent tests. The corrected required
run: `.\gradlew.bat integrationTest --tests com.example.meetings.VoteWriteIntegrationTest --offline --no-daemon --max-workers=1`.
04.11.2026 BUILD SUCCESSFUL in1m40s, JUnit tests=2/skipped=0/failures=0/errors=0;
4 tasks executed,2 up-to-date. Both real native HTTP scenarios executed.
Own `roy_m4_vote` in verified disposable PostgreSQL18.6/PostGIS3.6.4, V001–V015.
Post-run users/points/proposals/votes/messages/notifications=0/0/0/0/0/0.
The P2 strict-Boolean regression and native distinct-voter concurrency are GREEN.
External accounting updated: T2/B01-vote-write.md, T2/README.md,
execution-checklist.md, M4-T2.md and M4-meetings-booking.md, outside backend Git.
Task commit subject: `Enforce strict own vote writes`.

Independent read-only repeat task review: APPROVE before T092 commit
`330983374d555752e1b85b4241bc29d34964cc31`.

## Group 3 — T093 / M4.T2.B02 — shared count and personal going projection

Existing production projection and event paths were retained. Added
`VoteProjectionIntegrationTest`, guarded to own disposable `roy_m4_vote_projection`.
The fixture has three active members A/B/C, real JDBC sessions, one future proposal
without a booking and recipient settings that make the transactional notification
write observable. The native loopback Netty/HTTP/WS scenario exercises committed
states0→A-only1→A+B2→B-only1; C never votes.

For every state, all three actors' service list/detail and production HTTP list/detail
must agree on shared goingCount/status/revision and on their own viewerGoing flag.
Repeated snapshots leave SQL votes/revision/messages/notifications unchanged.
The >1 state projects ready_to_book; other states project voting with null booking.
All three subscribed sockets receive an identical proposal.updated event, including
the same event ID and exact shared payload, without viewerGoing, and its revision
matches committed PostgreSQL. The count2 SYSTEM meeting_ready frame is drained
using canonical Message.system fields; readiness-edge dedup acceptance remains T095.

Before the first vote commits, an own-database SHARE lock on notification_events
blocks the transaction's actual notification INSERT. Within a five-second bound,
pg_locks must show the waiting RowExclusiveLock and the HTTP future stays pending.
Independent SQL and every actor's list/detail continue to show the old0/false/voting
snapshot. Ordered USER markers plus250ms observations show no premature chat event.
Only after releasing the gate may HTTP return the new personal snapshot and WS
publish its shared invalidation. Explicit bounded lock/statement/HTTP timeouts are
40/50/45 seconds to accommodate those read checks; they are not an unbounded wait.

Sockets/server and the fixture's own point/accounts are cleaned in finally. No
production rewrite, broad T092 error matrix or exhaustive race acceptance is implied.
Executed 04.11.2026 with `.\gradlew.bat integrationTest --tests com.example.meetings.VoteProjectionIntegrationTest --offline --no-daemon --max-workers=1`.
BUILD SUCCESSFUL in1m56s; JUnit tests=1/skipped=0/failures=0/errors=0,
integration task executed (2 tasks executed,4 up-to-date).
Own `roy_m4_vote_projection`, verified disposable PostgreSQL18.6/PostGIS3.6.4,
LOCAL bootstrap V001–V015; post-run users/points/proposals/votes/messages/
notifications=0/0/0/0/0/0.
External accounting updated: T2/B02-vote-projection.md, T2/README.md,
execution-checklist.md, M4-T2.md and M4-meetings-booking.md, outside backend Git.
Task commit subject: `Verify personal vote snapshots and shared events`.

Independent read-only T093 task review: APPROVE before commit
`13ba75bdfb2152020d70326b93f73cd892f5abde`.
The separate group 3 reviewer approved both task commits and the complete branch
diff after the strict-Boolean correction and actual test evidence. Group 3 merged
and published as `6cea7c437456ce793afe58dafc08c3c6998922ca`; push, fetch and
ls-remote verified `main=origin/main` at that commit with a clean accessible main
checkout. Pool 3 completed before sequential pool 4 began.

## Group 4 — T094 / M4.T2.B03 — bounded public voter pages

Branch `feat/voter-list-transition` starts at published verified main
`6cea7c437456ce793afe58dafc08c3c6998922ca`.
Existing service keyset/cursor and transport UserPublic projection were retained.
Added `VoterPageIntegrationTest`, guarded to own disposable `roy_m4_voters`.
Its real JDBC session/native loopback HTTP fixture has two chats at one point,
two proposals in the first chat and one in the second, two authorized viewers,
five voters and an outsider. Equal vote timestamps deliberately cross a page
boundary; positive UUID high bits3/2/1 give explicit SQL descending tie order,
without relying on Java's signed UUID comparator.

The scenario checks three bounded pages for exact order, complete coverage,
no duplicates and repeatability; default,1 and100 limits are exercised. Every
Vote has exactly proposalId/user/createdAt and UserPublic has exactly
id/displayName/avatarUrl. Avatars expose only the public user-avatar URL or null;
phone, age, password hash and raw object-key data remain absent. URL shape is
checked, not media fetching or external storage delivery.

Missing authentication and nonmembership return the established401/403 codes.
Invalid limits return400 INVALID_FIELD; malformed/tampered cursors and changes
to viewer, page limit, proposal, associated chat or cursor scope return400
INVALID_CURSOR without voter data. Both resource-context comparison endpoints
are independently accessible to the viewer, so rejection is attributed to cursor
context rather than a membership failure. Repeated reads and rejected requests
leave scoped vote/revision/message/notification/sequence SQL state unchanged.
The fixture removes only its own point and accounts in finally.

Executed 04.11.2026 with `.\gradlew.bat integrationTest --tests com.example.meetings.VoterPageIntegrationTest --offline --no-daemon --max-workers=1`.
BUILD SUCCESSFUL in1m39s; JUnit tests=1/skipped=0/failures=0/errors=0.
Own `roy_m4_voters`, verified disposable PostgreSQL18.6/PostGIS3.6.4,
LOCAL bootstrap V001–V015; post-run users/points/proposals/votes/messages/
notifications=0/0/0/0/0/0.
External accounting updated: T2/B03-voter-page.md, T2/README.md,
execution-checklist.md (92/455), M4-T2.md (backend3/4), and M4-meetings-booking.md
(T2 backend3/4; total M4 backend8/28). These five status files remain outside
backend Git; their physical contents do not enter main through this protocol.
Task commit subject: `Verify bounded public voter pages`; SHA is not invented
before creation. Independent read-only task review: APPROVE before commit.
Client completion, BG-1 and overall M4 acceptance remain open.

T094 task commit: `fa48db960ded93ad184cb85f447fd7f10ee5f06e`; task APPROVE
preceded that commit. All five external T094 status references now carry its
actual short SHA. The obsolete pending-task-review sentence in the card was
corrected to that confirmed approval; group 4 branch review remains separate.

## Group 4 — T095 / M4.T2.B04 — atomic readiness edges

Existing production vote/threshold transaction and publication behavior were retained.
Added `VoteReadinessIntegrationTest`, guarded to own disposable `roy_m4_ready`,
with three real JDBC sessions, native loopback HTTP and three subscribed WS clients.
The first proposal exercises0→1→2→3→2→1→2: readiness is expected only at revisions2/6,
with no extra SYSTEM/notification/revision effect from repeated reads or desired
states. Each logical ready edge has one stored meeting_ready and revision-scoped
`proposal_ready:<proposalId>:<revision>` notification keys for two eligible peers.

The second proposal starts at one vote. Two actual worker threads synchronize
through ready/start latches, then send distinct B/C HTTP votes concurrently.
Both rows must persist, final count/revision must be3/3, and there must be exactly
one ready edge at revision2. WS update revisions2/3 are compared as event-ID/content
maps between subscribers, allowing post-commit reordering rather than requiring
a fixed frame sequence. Canonical Message.system and exact shared proposal payloads
are checked without viewerGoing and against committed SYSTEM rows.

For the third proposal, a proposal-scoped temporary notification INSERT trigger
raises nonretryable SQLSTATE23514 after the ready SYSTEM append. A real HTTP500
must leave vote/revision/SYSTEM/notification/sequence state unchanged and no WS
effect through ordered USER markers plus250ms observations. Dropping the trigger
allows retrying the desired vote to create the edge exactly once; its repeat is
effect-free. All workers, sockets, server, fault DDL and own fixture IDs are bounded
or cleaned in finally. This does not establish exhaustive schedules, external
delivery, client behavior, BG-1 or overall M4 acceptance.

Executed 04.11.2026 after dot-sourcing the ignored local test-environment recipe
with alias `roy_m4_ready` (its contents/credentials are not reproduced here).
Exact test command: `.\gradlew.bat integrationTest --tests com.example.meetings.VoteReadinessIntegrationTest --offline --no-daemon --max-workers=1`.
BUILD SUCCESSFUL in1m28s, exit0;2 tasks executed,4 up-to-date. Actual JUnit XML:
tests=1/skipped=0/failures=0/errors=0; timestamp2026-11-04T13:53:56.166Z,
suite20.334s/test20.331s. This is actual execution, not an assumption skip.

Before execution, read-only `docker inspect codex-m4-disposable` selected only
container ID, ownership label, running state and published ports. It verified
`8b30a5dd02db7ef3a1cdd8bae238b5d7d9af7b7ca163bc10ee2794c0fa5d566b`,
`codex.task=m4-t087-t114`, running, exclusively127.0.0.1:55484 for PostgreSQL.
The sandbox Docker inspection was denied; the authorized read-only retry and the
Gradle run used escalation for the Docker pipe/Gradle cache, with no environment dump.
The exact post-run read-only SQL command was:

```powershell
docker exec codex-m4-disposable psql -U m4_test -d roy_m4_ready -At -c "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version IS NOT NULL; SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM points),(SELECT count(*) FROM meeting_proposals),(SELECT count(*) FROM votes),(SELECT count(*) FROM messages),(SELECT count(*) FROM notification_events),(SELECT count(*) FROM operation_idempotency),(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'm4_ready_fault_%'),(SELECT count(*) FROM pg_proc WHERE proname LIKE 'm4_ready_fault_%');"
```

It verified database alias, PostgreSQL18.6/PostGIS3.6.4 and all successful V001–V015.
SQL counts users/points/proposals/votes/messages/notifications/outcomes/custom
ready fault triggers/custom ready fault functions=0/0/0/0/0/0/0/0/0.
No production code was changed; no failed T095 test or confirmed production
finding was observed in this run.

External accounting updated: T2/B04-vote-transition.md, T2/README.md,
execution-checklist.md (93/455), M4-T2.md (backend4/4) and M4-meetings-booking.md
(T2 backend4/4; M4 backend9/28). These five external status files are physically
outside backend Git. Task commit subject: `Verify atomic readiness transitions`;
the future SHA is not fabricated. Independent read-only task review: APPROVE before commit.
Group review remains separate; client, BG-1 and overall M4 completion are open.

T095 task commit: `08d903b4eee17186ec1a43e24879f051f0aeea5b`, after independent
task APPROVE. The separate group 4 reviewer approved T094/T095 and the whole
branch diff, including external-evidence wording corrections. Group 4 merged and
published as `1398e7b0b8097d0250c227960c5f0b184edf8c7e`; push/fetch/ls-remote
verified main=origin/main at that commit and the accessible working tree was clean.
Sequential pool 4 finished before pool 5 began.

## Group 5 — T096 / M4.T3.B01 — one active booking schema

Branch `feat/booking-core` starts at the verified published merge
`1398e7b0b8097d0250c227960c5f0b184edf8c7e`.
V010 already contains the proposal-scoped partial ACTIVE unique index, owner and
proposal foreign keys, state CHECK and database-generated booking_order. It was
retained; no applied migration or production service/route was changed.
Added `BookingSchemaIntegrationTest`, guarded to own `roy_m4_booking_schema`.

The SQL fixture/savepoint scenario verifies actual SQLSTATE23505 for a second
ACTIVE booking of one proposal, while another proposal can have an ACTIVE row.
It exercises invalid proposal/owner foreign keys, null proposal, generated-order
override rejection and invalid state/cancellation combinations. Cancellation is
retained as history; rebooking uses a new ID and larger order without modifying
the cancelled snapshot. Identity sequence gaps from rejected statements are valid,
so the test does not require old order+1. Owner deletion preserves a nullable
historical owner; proposal deletion cascades only its bookings. All fixture rows
are rolled back in finally; expected SQL errors are isolated by savepoints.

This is local booking-core schema acceptance. Public booking routes already
existed in the baseline; exposure was not expanded and nothing was deployed.
Combined T3–T5/T6 server acceptance, client behavior and BG-1 remain open.
Executed 04.11.2026 after dot-sourcing the ignored test-environment recipe with
alias `roy_m4_booking_schema`; its credentials are not reproduced here.
Exact command: `.\gradlew.bat integrationTest --tests com.example.meetings.BookingSchemaIntegrationTest --offline --no-daemon --max-workers=1`.
BUILD SUCCESSFUL in35s, exit0;2 tasks executed,4 up-to-date. Actual XML:
tests=1/skipped=0/failures=0/errors=0, timestamp2026-11-04T14:04:52.547Z,
suite2.324s/test2.321s. No T096 failed test or confirmed production finding occurred.

Pre-run targeted Docker inspection verified the same owned container ID
`8b30a5dd02db7ef3a1cdd8bae238b5d7d9af7b7ca163bc10ee2794c0fa5d566b`,
ownership label m4-t087-t114, running and loopback127.0.0.1:55484. The run used
authorized escalation for Docker/Gradle cache only, without dumping environment.
Post-run exact command:

```powershell
docker exec codex-m4-disposable psql -U m4_test -d roy_m4_booking_schema -At -c "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version IS NOT NULL; SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM points),(SELECT count(*) FROM meeting_proposals),(SELECT count(*) FROM bookings);"
```

It confirmed own alias, PostgreSQL18.6/PostGIS3.6.4, successful V001–V015 and
users/points/proposals/bookings=0/0/0/0. V010 SHA256 before/after the run was identical:
`D96E1DEAE94966185F3D1F07B67CD19B43EB1CD418AA7CF82C93EB2C49A00EAC`.
No migration was added or modified; identity sequence gaps from rolled-back
inserts are intentionally outside the zero-row cleanup claim.

External status files updated: T3/B01-booking-unique.md, T3/README.md,
execution-checklist.md (94/455), M4-T3.md (backend1/5), M4-meetings-booking.md
(T3 backend1/5; M4 backend10/28), all outside backend Git.
Task commit subject: `Verify active booking schema invariants`.
Independent read-only task review: APPROVE before commit. Whole-T3 acceptance
is not inferred from this schema run.

T096 task APPROVE preceded commit `67ab9a370eeec5813d68c2c2dbd7e11ac440d9ff`.
The five external T096 references now carry actual SHA `67ab9a3` and the unique
subject, without temporary future-SHA suffixes.

## Group 5 — T097 / M4.T3.B02 — booking eligibility guards

Added `BookingGuardsIntegrationTest`, guarded to own `roy_m4_booking_guards`,
using native loopback HTTP and real JDBC sessions. Nine independently seeded
proposal states and fresh booking keys isolate the intended guard: zero votes,
one own vote, two votes without own participation, eligible nonauthor booker,
stale revision, closed lifecycle, started interval, existing active booking at
current revision, and a fresh eligible proposal dedicated to revision types.
Their intervals do not overlap, preventing an unrelated confirmation guard from
masking eligibility. Actor→point→proposal locked rechecks remain the existing
BookingService implementation; no production change is assumed from source review.

The scenario expects distinct VOTE_REQUIRED/INSUFFICIENT_VOTES/STALE_STATE,
member/auth/gone/closed/started/already-booked errors without changing scoped
booking/vote/proposal rows, revisions/updated timestamps, SYSTEM/notifications,
chat sequence or any synthetic actor's idempotency outcomes. Eligible booking
by a voter different from the proposer must return201 and agree with GET/DB owner.
The separate eligible revision2 target exercises quoted2, null, boolean, object,
array, fractional numeric forms, overflow, negative and missing expectedRevision;
native Long.MAX_VALUE must yield stale without wrapping. confirmOverlap remains
native false; quoted confirmOverlap belongs to its later task.

Actual execution and its observed type-contract defect are recorded below.
Public baseline routes were not expanded or deployed; whole T3–T5/T6 and BG-1
acceptance remain outside this local core task.

First real command: `.\gradlew.bat integrationTest --tests com.example.meetings.BookingGuardsIntegrationTest --offline --no-daemon --max-workers=1`,
after dot-sourcing the ignored own test-environment recipe for `roy_m4_booking_guards`.
04.11.2026 BUILD FAILED in57s, exit1; actual JUnit tests=1/skipped=0/failures=1/
errors=0, timestamp2026-11-04T14:12:47.574Z, suite9.049s. Earlier isolated eligibility
guards and the eligible nonauthor201/GET/DB-owner assertions completed, then native
HTTP `{"confirmOverlap":false,"expectedRevision":"2"}` on a fresh count2/revision2
proposal returned201 instead of422. This is an observed revision type-contract
defect, not an acceptance PASS. Remaining type-matrix cases were not reached.
The finding was reported to Lead and the independent contract reviewer before
any production correction. Post-failure own-fixture users/points/proposals/votes/
bookings/messages/notifications/outcomes=0/0/0/0/0/0/0/0.

Lead and the independent contract reviewer confirmed this P2 and approved a
narrow correction before production editing: a private KSerializer<Long> only on
BookingInput.expectedRevision. It requires an unquoted JsonPrimitive with a valid
longOrNull; other shapes throw SerializationException and preserve the existing
422 INVALID_FIELD mapping. DTO Long, encodeLong and the existing negative-revision
guard are retained. confirmOverlap, CloseProposalInput, global JSON configuration
and BookingService locking/eligibility logic are unchanged. The matrix additionally
includes quoted2.0.

Corrected exact command: `.\gradlew.bat integrationTest --tests com.example.meetings.BookingGuardsIntegrationTest --offline --no-daemon --max-workers=1`.
04.11.2026 BUILD SUCCESSFUL in1m3s, exit0;4 tasks executed,2 up-to-date.
Actual XML tests=1/skipped=0/failures=0/errors=0, timestamp2026-11-04T14:16:10.432Z,
suite5.513s/test5.508s. The full eligibility and revision-type matrix executed,
including safe native Long.MAX_VALUE stale handling, without guard-refusal effects.
The approved expectedRevision type correction is GREEN; independent repeat task
review is separate and its APPROVE is not invented.

Targeted pre-run Docker inspection reverified the same owned container ID
`8b30a5dd02db7ef3a1cdd8bae238b5d7d9af7b7ca163bc10ee2794c0fa5d566b`,
label m4-t087-t114, running, only127.0.0.1:55484. The local ignored environment
recipe selected own alias `roy_m4_booking_guards`; no credentials are recorded.
Post-GREEN exact SQL command:

```powershell
docker exec codex-m4-disposable psql -U m4_test -d roy_m4_booking_guards -At -c "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version IS NOT NULL; SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM points),(SELECT count(*) FROM meeting_proposals),(SELECT count(*) FROM votes),(SELECT count(*) FROM bookings),(SELECT count(*) FROM messages),(SELECT count(*) FROM notification_events),(SELECT count(*) FROM operation_idempotency);"
```

It confirmed PostgreSQL18.6/PostGIS3.6.4, successful V001–V015 and the same eight
zero fixture counts as the RED cleanup. No applied migration was changed.
External statuses updated: T3/B02-booking-guards.md, T3/README.md,
execution-checklist.md (95/455), M4-T3.md (backend2/5), M4-meetings-booking.md
(T3 backend2/5; M4 backend11/28), physically outside backend Git.
Task commit subject: `Enforce booking eligibility guards`. This local core result
does not expand baseline public routes, deploy, close the parent or establish
combined T3–T5/T6, client, external-delivery or BG-1 acceptance.

Independent read-only repeat task review: APPROVE before commit.

T097 final task commit: `069c2cb535423b1330361257e2d3413a157801ac`.
The five external T097 subject references now include actual SHA `069c2cb`.

## Group 5 — T098 / M4.T3.B03 — booking COMMIT and idempotency transaction

Added `BookingTransactionIntegrationTest`, scoped to own disposable
`roy_m4_booking_transaction`, real JDBC sessions, native loopback HTTP and two WS
subscribers. No production or migration change was made. The baseline already
contains public routes and overlap/cancellation hooks; this task neither expands
exposure nor deploys or describes the baseline as dev-gated. Acceptance remains
local core; combined T3–T5/T6, external delivery, client and BG-1 stay open. Known
post-start cancellation policy work belongs to T101/T103, not this transaction test.

A synthetic actor/operation-scoped CONSTRAINT TRIGGER on the final idempotency
INSERT is AFTER INSERT, DEFERRABLE INITIALLY DEFERRED, with both pg_trigger flags
asserted true. Its SQLSTATE23514 is deferred until the existing runtime's literal
connection.commit() after the transaction block. HTTP500 must restore full booking,
vote, proposal revision/updatedAt, point updatedAt, SYSTEM/chat sequence,
notification/job and outcome snapshots. GET must remain ready_to_book/bookingnull;
ordered USER sentinels plus250ms check bounded WS silence. One synthetic recipient
device makes notification/job rollback nonvacuous; no delivery worker or provider
is started and opaque synthetic device data is not an encryption/delivery proof.

After dropping fault DDL, the same key and current revision must return201 and
match GET/DB booking ID/order/owner/revision, one meeting_booked, SUCCESS result_ref
and saved response snapshot. Both original replay and changed valid
expectedRevision/confirmOverlap replay must return that same snapshot without WS,
business writes or sequence allocation. pg_get_serial_sequence resolves the real
identity sequence for before/after replay comparison. Failed-COMMIT identity gaps
are valid, not reset and not claimed gapless. Reusing the key on another proposal
must conflict without effects. Fault DDL, devices and fixture namespace are removed
in finally. Full vote/unvote/leave races remain T113.

Executed 04.11.2026 after dot-sourcing the ignored local test environment for own
`roy_m4_booking_transaction`; no environment contents or credentials are recorded.
Exact command: `.\gradlew.bat integrationTest --tests com.example.meetings.BookingTransactionIntegrationTest --offline --no-daemon --max-workers=1`.
BUILD SUCCESSFUL in1m36s, exit0;2 tasks executed,4 up-to-date. Actual XML:
tests=1/skipped=0/failures=0/errors=0, timestamp2026-11-04T14:26:46.734Z,
suite22.507s/test22.483s. The deferred-COMMIT rollback, same-key retry, actual job,
saved snapshot, WS absence and unchanged replay identity-sequence assertions all
executed. No T098 failed run or confirmed production finding was observed.

Before the run, targeted Docker inspection verified the same owned container
`8b30a5dd02db7ef3a1cdd8bae238b5d7d9af7b7ca163bc10ee2794c0fa5d566b`,
label m4-t087-t114, running and only127.0.0.1:55484. Post-run exact SQL command:

```powershell
docker exec codex-m4-disposable psql -U m4_test -d roy_m4_booking_transaction -At -c "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version IS NOT NULL; SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM points),(SELECT count(*) FROM meeting_proposals),(SELECT count(*) FROM votes),(SELECT count(*) FROM bookings),(SELECT count(*) FROM messages),(SELECT count(*) FROM notification_events),(SELECT count(*) FROM push_delivery_jobs),(SELECT count(*) FROM operation_idempotency),(SELECT count(*) FROM device_registrations),(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'm4_booking_commit_fault_%'),(SELECT count(*) FROM pg_proc WHERE proname LIKE 'm4_booking_commit_fault_%');"
```

It confirmed own alias, PostgreSQL18.6/PostGIS3.6.4, successful V001–V015 and all
twelve scoped disposable-db row/fault counts zero. Identity sequence values are
not reset and are outside this zero-row cleanup claim.
External statuses updated: T3/B03-booking-write.md, T3/README.md,
execution-checklist.md (96/455), M4-T3.md (backend3/5), M4-meetings-booking.md
(T3 backend3/5; M4 backend12/28), all physically outside backend Git.
Task commit subject: `Verify atomic booking commit outcomes`. Independent read-only
task review: APPROVE before commit. Combined server acceptance remains open.


## Завершённые B4 результаты и отдельный итоговый аудит — 04.11.2026

Все T087–T114 реализованы/сверены, фактически проверены и сохранены отдельными тематическими task commits. Все 12 групп девяти пулов прошли task review и другой независимый whole-branch review, затем последовательно слиты и опубликованы только в main. Итоговый проверенный код: [75cf3d6](https://github.com/abcKristy/Roy_backend/commit/75cf3d612cad20d81385bd10f701946379dbbb31). Предсуществовавший Swagger commit2f54ac0 сохранён; фактическая база первого пула —2f54ac0734004308aaf5e5d2f0b3a8253772c168.

| Пул | Группа | Рабочая ветка | Проверенная база | Task commits | Опубликованный merge main |
|---|---|---|---|---|---|
| 1 | 1 | feat/proposal-creation | [2f54ac0](https://github.com/abcKristy/Roy_backend/commit/2f54ac0734004308aaf5e5d2f0b3a8253772c168) | T087 [f841281](https://github.com/abcKristy/Roy_backend/commit/f841281f54ac4cbe483cfebc6a49a245cd1ee732); T088 [bd0e71d](https://github.com/abcKristy/Roy_backend/commit/bd0e71d275b6beefcbf09b560c80938e340941e2); T089 [a8302d2](https://github.com/abcKristy/Roy_backend/commit/a8302d2d2d1b5be6a050abd72810f47487ae82b3) | [82d13a3](https://github.com/abcKristy/Roy_backend/commit/82d13a37acc968b10a08cb9c3f4729cebd8d91ea) |
| 2 | 2 | feat/proposal-read-events | [82d13a3](https://github.com/abcKristy/Roy_backend/commit/82d13a37acc968b10a08cb9c3f4729cebd8d91ea) | T090 [a24e879](https://github.com/abcKristy/Roy_backend/commit/a24e8793309b5ca52d3a96b75f5f8f717217825e); T091 [569a8a8](https://github.com/abcKristy/Roy_backend/commit/569a8a84be98d977d4bf81189ce3036bc4d9a2e8) | [fa43431](https://github.com/abcKristy/Roy_backend/commit/fa43431df668208128adcba9ccadedadf4fc6ffa) |
| 3 | 3 | feat/meeting-votes | [fa43431](https://github.com/abcKristy/Roy_backend/commit/fa43431df668208128adcba9ccadedadf4fc6ffa) | T092 [3309833](https://github.com/abcKristy/Roy_backend/commit/330983374d555752e1b85b4241bc29d34964cc31); T093 [13ba75b](https://github.com/abcKristy/Roy_backend/commit/13ba75bdfb2152020d70326b93f73cd892f5abde) | [6cea7c4](https://github.com/abcKristy/Roy_backend/commit/6cea7c437456ce793afe58dafc08c3c6998922ca) |
| 4 | 4 | feat/voter-list-transition | [6cea7c4](https://github.com/abcKristy/Roy_backend/commit/6cea7c437456ce793afe58dafc08c3c6998922ca) | T094 [fa48db9](https://github.com/abcKristy/Roy_backend/commit/fa48db960ded93ad184cb85f447fd7f10ee5f06e); T095 [08d903b](https://github.com/abcKristy/Roy_backend/commit/08d903b4eee17186ec1a43e24879f051f0aeea5b) | [1398e7b](https://github.com/abcKristy/Roy_backend/commit/1398e7b0b8097d0250c227960c5f0b184edf8c7e) |
| 5 | 5 | feat/booking-core | [1398e7b](https://github.com/abcKristy/Roy_backend/commit/1398e7b0b8097d0250c227960c5f0b184edf8c7e) | T096 [67ab9a3](https://github.com/abcKristy/Roy_backend/commit/67ab9a370eeec5813d68c2c2dbd7e11ac440d9ff); T097 [069c2cb](https://github.com/abcKristy/Roy_backend/commit/069c2cb535423b1330361257e2d3413a157801ac); T098 [c374438](https://github.com/abcKristy/Roy_backend/commit/c3744384068dc379bccfccfe9984c6b9beb7137b) | [ae43668](https://github.com/abcKristy/Roy_backend/commit/ae43668958e3034ada605809d29f1d09a95969d4) |
| 6 | 6 | feat/booking-response-event | [ae43668](https://github.com/abcKristy/Roy_backend/commit/ae43668958e3034ada605809d29f1d09a95969d4) | T099 [43f9edc](https://github.com/abcKristy/Roy_backend/commit/43f9edccf21cc777bca0620d06b87ac7cbc8ac58); T100 [9cf4a67](https://github.com/abcKristy/Roy_backend/commit/9cf4a6746f54d8ed3fa3165d105efef1a769b8be) | [d5cfce8](https://github.com/abcKristy/Roy_backend/commit/d5cfce8cb3f99bf2de9b7ee38995ed3eaa6deeca) |
| 6 | 7 | feat/booking-cancellation | [ae43668](https://github.com/abcKristy/Roy_backend/commit/ae43668958e3034ada605809d29f1d09a95969d4) | T101 [d71a65c](https://github.com/abcKristy/Roy_backend/commit/d71a65caaa2a9c7efdeb417c341188e6439d8db4); T102 [645d842](https://github.com/abcKristy/Roy_backend/commit/645d8422b3766aef900308d2fd91023935b7f4e7); T103 [54e2f2f](https://github.com/abcKristy/Roy_backend/commit/54e2f2f3813a86085ed5a570235db1046f1a2add) | [b29e7af](https://github.com/abcKristy/Roy_backend/commit/b29e7afc5551b7afdf7ff6765f4a9b7f80815135) |
| 7 | 8 | feat/cancellation-events-jobs | [b29e7af](https://github.com/abcKristy/Roy_backend/commit/b29e7afc5551b7afdf7ff6765f4a9b7f80815135) | T104 [ef4e0aa](https://github.com/abcKristy/Roy_backend/commit/ef4e0aa357a6ddc87e9dcbc04ec1b10e1c6723a9); T105 [9aa08b3](https://github.com/abcKristy/Roy_backend/commit/9aa08b379b888263181450f3166647d768db66be) | [59a1891](https://github.com/abcKristy/Roy_backend/commit/59a189132c8136ccef2d8f08d0339a3b27849467) |
| 6 | 9 | feat/booking-overlap | [ae43668](https://github.com/abcKristy/Roy_backend/commit/ae43668958e3034ada605809d29f1d09a95969d4) | T106 [480b4ec](https://github.com/abcKristy/Roy_backend/commit/480b4ec2427b285f14258e999386987b458c3c4b); T107 [36ece2a](https://github.com/abcKristy/Roy_backend/commit/36ece2a57dd9a0e49fb1088c8fb0b9b4d0b335a7); T108 [a395e6b](https://github.com/abcKristy/Roy_backend/commit/a395e6b446aa182557fd20bedf3d46446da46710) | [ed68e26](https://github.com/abcKristy/Roy_backend/commit/ed68e26b61fcf0e9f9d84f1306db8534e7254693) |
| 7 | 10 | feat/booking-priority-close | [b29e7af](https://github.com/abcKristy/Roy_backend/commit/b29e7afc5551b7afdf7ff6765f4a9b7f80815135) | T109 [2cb164b](https://github.com/abcKristy/Roy_backend/commit/2cb164bcdf9a24bea2ebdda14b3ac0ecee7ac62f); T110 [610057a](https://github.com/abcKristy/Roy_backend/commit/610057ab12e5bf2948dd1cf030951d32a95c117c) | [80dcb6f](https://github.com/abcKristy/Roy_backend/commit/80dcb6f80fa4710b6a1d42bebd93c2060df3e9c7) |
| 8 | 11 | tests/meeting-acceptance | [59a1891](https://github.com/abcKristy/Roy_backend/commit/59a189132c8136ccef2d8f08d0339a3b27849467) | T111 [9971abe](https://github.com/abcKristy/Roy_backend/commit/9971abe3fc19e5a6511bfa0d1e9be26c83292e4b); T112 [d07dee4](https://github.com/abcKristy/Roy_backend/commit/d07dee4f7df569ec4bda41713fb0c280c4e1149b) | [1241e99](https://github.com/abcKristy/Roy_backend/commit/1241e9938824d85bd2d491645bcd06e318fe9a63) |
| 9 | 12 | tests/meeting-races | [1241e99](https://github.com/abcKristy/Roy_backend/commit/1241e9938824d85bd2d491645bcd06e318fe9a63) | T113 [67ff044](https://github.com/abcKristy/Roy_backend/commit/67ff044477d4ca9ad83488bd0aa3233714677552); T114 [17270be](https://github.com/abcKristy/Roy_backend/commit/17270be99f32b8c0dfda021780d3ae4681f5d9aa) | [75cf3d6](https://github.com/abcKristy/Roy_backend/commit/75cf3d612cad20d81385bd10f701946379dbbb31) |

Пулы 1–5, 8, 9 выполнялись по одной группе. В пуле 6 фактически параллельно работали группы 6/7/9 в трёх отдельных managed worktrees и собственных БД на 55485/55486/55487 от одного ae43668; порядок слияния 6→9→7. После первого merge группа9 интегрировала main d5cfce8 (integration merge4f74fe9), повторила8 тестов и получила независимый review; группа7 интегрировала ed68e26 (bf1bd6b), повторила6 тестов и получила независимый review. В пуле 7 группы 8/10 параллельны от b29e7af в двух разных worktrees/БД; порядок 10→8. Группа8 интегрировала 80dcb6f (af37b5e), повторила11 тестов и получила независимый review. Опубликованная история не переписывалась; force-push не использовался.

Task reviewer групп 1–6 и 9–12 — contracts; групп 7–8 — group_review. Независимый branch reviewer групп 7–8 — contracts, остальных — group_review. Реализация: environment, cancellation, overlap; Lead интегрировал Git/статусы и итог. Каждая группа прошла fetch/ls-remote сверку main/origin/main и проверку clean tree после push. Старые отсутствующие worktrees не выдаются за проверенные; managed checkout paths переиспользованы только после сохранённого чистого состояния.

### Свежая проверка опубликованного конечного кода

31/31 классов PASS; 67 JUnit tests: 64 integration в 30 классах + 3 unit в 1 классе; skipped/failures/errors — 0, Gradle exit 0 в каждой строке. UTC 2026-11-04T17:38:58.8042039+00:00 — 2026-11-04T18:18:53.0830181+00:00, длительность — 2394.279 с. Код и manifest оставались неизменными, main/source clean до/после. Все 31 actual XML скопированы до следующего Gradle запуска; свежесть/counts/отсутствие skip/failure/error проверены Lead и обоими независимыми аудиторами.

Фактический воспроизводимый формат команд: `.\gradlew.bat integrationTest --tests <FQCN> --offline --no-daemon --max-workers=1`; для SafeErrorEnvelopeTest — `test` вместо integrationTest. Перед integration запуском использовалась ignored local recipe с указанными alias/port. Для двух legacy guards менялся только TEST_DATABASE_URL на собственный loopback55485 legacy alias; production.env не читался. Credentials/env values не выводились.

| Проверка | FQCN | Alias / port | Tests / skipped / failures / errors | Actual XML UTC |
|---|---|---|---|---|
| T087 | com.example.meetings.ProposalSchemaIntegrationTest | roy_m4_schema / 55484 | 1/0/0/0 | 2026-11-04T17:40:32.3290000+00:00 |
| T088 | com.example.meetings.ProposalTimeIntegrationTest | roy_m4_time / 55484 | 1/0/0/0 | 2026-11-04T17:41:39.5130000+00:00 |
| T089 | com.example.meetings.ProposalCreateIntegrationTest | roy_m4_create / 55484 | 2/0/0/0 | 2026-11-04T17:42:52.7250000+00:00 |
| T090 | com.example.meetings.ProposalReadIntegrationTest | roy_m4_read / 55484 | 1/0/0/0 | 2026-11-04T17:44:09.7130000+00:00 |
| T091 | com.example.meetings.ProposalEventsIntegrationTest | roy_m4_events / 55484 | 1/0/0/0 | 2026-11-04T17:45:08.7510000+00:00 |
| T092 | com.example.meetings.VoteWriteIntegrationTest | roy_m4_vote / 55484 | 2/0/0/0 | 2026-11-04T17:46:31.7270000+00:00 |
| T093 | com.example.meetings.VoteProjectionIntegrationTest | roy_m4_vote_projection / 55484 | 1/0/0/0 | 2026-11-04T17:47:20.4390000+00:00 |
| T094 | com.example.meetings.VoterPageIntegrationTest | roy_m4_voters / 55484 | 1/0/0/0 | 2026-11-04T17:48:22.3390000+00:00 |
| T095 | com.example.meetings.VoteReadinessIntegrationTest | roy_m4_ready / 55484 | 1/0/0/0 | 2026-11-04T17:49:23.5710000+00:00 |
| T096 | com.example.meetings.BookingSchemaIntegrationTest | roy_m4_booking_schema / 55484 | 1/0/0/0 | 2026-11-04T17:50:38.4870000+00:00 |
| T097 | com.example.meetings.BookingGuardsIntegrationTest | roy_m4_booking_guards / 55484 | 1/0/0/0 | 2026-11-04T17:51:59.7560000+00:00 |
| T098 | com.example.meetings.BookingTransactionIntegrationTest | roy_m4_booking_transaction / 55484 | 1/0/0/0 | 2026-11-04T17:53:12.5230000+00:00 |
| T099 | com.example.meetings.BookingResponseIntegrationTest | roy_m4_booking_response / 55485 | 1/0/0/0 | 2026-11-04T17:55:06.2330000+00:00 |
| T100 | com.example.meetings.BookingEventsIntegrationTest | roy_m4_booking_events / 55485 | 1/0/0/0 | 2026-11-04T17:56:22.1030000+00:00 |
| T101 | com.example.meetings.BookingCancellationPolicyIntegrationTest | roy_m4_cancel_policy / 55486 | 3/0/0/0 | 2026-11-04T17:57:39.5320000+00:00 |
| T102 | com.example.meetings.BookingCancellationUnvoteIntegrationTest | roy_m4_cancel_unvote / 55486 | 1/0/0/0 | 2026-11-04T17:58:41.3040000+00:00 |
| T103 | com.example.meetings.BookingCancellationDepartureIntegrationTest | roy_m4_cancel_leave / 55486 | 2/0/0/0 | 2026-11-04T17:59:31.5120000+00:00 |
| T104 | com.example.meetings.BookingCancellationEventsIntegrationTest | roy_m4_cancel_events / 55485 | 3/0/0/0 | 2026-11-04T18:01:18.8850000+00:00 |
| T105 | com.example.jobs.BookingReminderCancellationIntegrationTest | roy_m4_cancel_jobs / 55485 | 4/0/0/0 | 2026-11-04T18:02:32.9190000+00:00 |
| T106 | com.example.meetings.BookingOverlapQueryIntegrationTest | roy_m4_overlap_query / 55487 | 2/0/0/0 | 2026-11-04T18:03:34.8580000+00:00 |
| T107 | com.example.meetings.BookingOverlapWarningIntegrationTest | roy_m4_overlap_warning / 55487 | 2/0/0/0 | 2026-11-04T18:04:39.1120000+00:00 |
| T108 | com.example.meetings.BookingOverlapConfirmIntegrationTest | roy_m4_overlap_confirm / 55487 | 4/0/0/0 | 2026-11-04T18:05:53.5610000+00:00 |
| T109 | com.example.meetings.BookingPairwisePriorityIntegrationTest | roy_m4_booking_priority / 55487 | 1/0/0/0 | 2026-11-04T18:07:05.3960000+00:00 |
| T110 | com.example.meetings.BookingAuthorCloseIntegrationTest | roy_m4_booking_close / 55487 | 4/0/0/0 | 2026-11-04T18:08:18.8910000+00:00 |
| T111 | com.example.meetings.MeetingAcceptanceFixtureIntegrationTest | roy_m4_acceptance_fixture / 55485 | 2/0/0/0 | 2026-11-04T18:09:19.5950000+00:00 |
| T112 | com.example.meetings.MeetingRestChainIntegrationTest | roy_m4_acceptance_fixture / 55485 | 1/0/0/0 | 2026-11-04T18:10:21.9970000+00:00 |
| T113 | com.example.meetings.MeetingConcurrencyIntegrationTest | roy_m4_acceptance_fixture / 55485 | 9/0/0/0 | 2026-11-04T18:11:55.7320000+00:00 |
| T114 | com.example.meetings.MeetingTimeBoundsIntegrationTest | roy_m4_acceptance_fixture / 55485 | 6/0/0/0 | 2026-11-04T18:13:51.5620000+00:00 |
| REG_SAFE_ERRORS | com.example.api.SafeErrorEnvelopeTest | нет DB | 3/0/0/0 | 2026-11-04T18:16:21.1280000+00:00 |
| REG_JOBS | com.example.jobs.JobsIntegrationTest | roy_m1t2_step7jobs / 55485 | 3/0/0/0 | 2026-11-04T18:17:11.6660000+00:00 |
| REG_DEVICE_REBIND | com.example.devices.DeviceRebindDeliveryConcurrencyIntegrationTest | roy_m1t2_step8delete / 55485 | 1/0/0/0 | 2026-11-04T18:18:09.4890000+00:00 |

Все 30 integration rows / 27 aliases подтвердили собственные неизменные container IDs, codex.task=m4-t087-t114, единственный 127.0.0.1 port binding, PostgreSQL 18.6 / PostGIS 3.6.4 и 15 успешных V001–V015. До/после проверялась 21 fixture-таблица и временные fault objects; итоговые counts — 0, triggers/functions — 0. Только REG_JOBS/REG_DEVICE_REBIND оставляют synthetic rows: before/after явно сохранены и cleanup выполнен только в их проверенных эксклюзивных own aliases. Sequences записаны, но не сброшены и не объявлены gapless.

Сырые results/31 XML / 31 logs и свежие ledgers T112 (1), T113 (9), T114 (6) находятся в ignored `.local/m4-final-verification/run-20261104T173858804Z-0eab488f/`. Manifest SHA-256: 997897308EB9B5BE217F49768C348EDFDDA7270D8676C71B06057B94D674E367; results.json SHA-256: a77da579e78114911a2921c46e4f4c1ae746b7ab8a20487a6ed567fb0b7e92fb. Типы evidence различены: например T088 — testApplication/realPG/stub SessionRepository, T089/T090 — service/realPG, designated native cases — настоящий Netty/HTTP/WS/JDBC. Ни один missing/stale/skipped/pre-body failure не принят за PASS.

### Статусы, Git-топология и аудит

Все 28 карточек, README/родители, execution-checklist и M4 roadmap синхронизированы:112/455, B4 backend 28/28 ГОТОВО локально; общий M4 остаётся ЧАСТИЧНО, client/BG-1 открыты. Эти учётные файлы физически находятся в родительском `spec/plans/` вне backend Git; их нельзя выдавать за файлы main. Версионируемые completed protocols и [единственный аудит](m4-t087-t114-review.md) находятся внутри backend Git.

Отдельный итоговый аудит выполнен ПОСЛЕ публикации всех 12 групп: contracts проверил T087–T100, group_review — T101–T114, оба прочитали все 31 fresh XML и итоговый manifest/results. Оба FORMAL APPROVE. Новых замечаний — 0, открытых — 0; исправлений после этого аудита — 0; пустая fix/meeting-audit-findings не создавалась. Аудит публикуется отдельным коммитом с уникальным заголовком `Audit completed meeting server tasks`; SHA собственного коммита не выдумывается внутри его содержимого.

Это локальная серверная приёмка B4. Real M5 provider delivery/nearby/reminder acceptance целиком, Android/iOS device UI, production deployment, stress/load и BG-1 не объявлены пройденными. Point-in-time push recheck не отзывает уже начатый send. Дополнительные блоки после полной проверки B4 не начинались по указанию пользователя.
