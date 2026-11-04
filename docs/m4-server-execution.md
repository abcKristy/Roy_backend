# M4 server execution

## Scope and topology

Backend Git root: `project/backend`; target: `main`, remote: `origin/main`.
The task cards and status registers under `spec/plans/` are outside backend Git.
Updating those registers does not version their physical contents in backend `main`.
Client tasks, BG-1, M5 nearby/reminder delivery and overall M4 acceptance are separate scopes.

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
