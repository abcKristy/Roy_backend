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
