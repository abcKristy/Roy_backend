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
