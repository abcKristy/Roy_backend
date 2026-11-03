# M3 server task execution protocol

## Scope and repository topology

Target branch: `feat/back-part-3`. Accepted prior-fix base: `e12ec3302ae9cb74fedc59a6b7ab555742fa8e77`.
Implementation runs in the separate `project/worktrees/backend-chat` Git worktree. Source checkout is not used for edits.

`spec/plans/` is outside backend Git. Task files, parent README, execution checklist, parent specifications and milestone roadmap are accounting locations; their edits do not physically belong to a backend branch. Updated accounting locations will be listed per completed task below. No plans are copied into backend Git.

## T057 — M3.T1.B01

Existing implementation retained: V003 enforces UNIQUE(point_topic_id), PRIMARY KEY(chat_id,user_id), and cascading point → topic → chat → membership/message deletion. PointWriteService joins under actor → point → chat locks; active repeat returns canonical membership and rejoin restores the existing pair. No applied migration was edited.

Evidence case: `com.example.api.MembershipConstraintsIntegrationTest`, guarded database alias `roy_m3_chat`, self-contained random synthetic actor, two separate points/topics/chats. It checks canonical join/rejoin and repeat snapshots, actual SQLSTATE 23505 for duplicate chat and membership inserts, cascade isolation, and repeated HTTP 404 RESOURCE_GONE after deletion with zero residual membership.

Execution date: 04.11.2026 (Europe/Moscow). Backend prior-fix base: `e12ec3302ae9cb74fedc59a6b7ab555742fa8e77`; task changes are identified by the commit containing this section.

Isolated environment verified by Lead: disposable container `roy-m3-chat-20261104`, ownership label `codex.owner=m3-chat-20261104`, PostgreSQL storage on tmpfs `/var/lib/postgresql`, published only on `127.0.0.1:35257`. PostgreSQL 18.6 / PostGIS 3.6.4. Database alias: `roy_m3_chat`; disposable confirmation enabled. Credentials are intentionally omitted.

Actual commands executed by Lead from this worktree with JDK 17 and Gradle 9.5.1:

- `./gradlew.bat runMigrations --offline --no-daemon --max-workers=1`: exit 0 in 2m32s; applied/validated 14 migrations, V001–V014.
- `./gradlew.bat integrationTest --tests com.example.api.MembershipConstraintsIntegrationTest --offline --no-daemon --max-workers=1`: BUILD SUCCESSFUL in 1m34s.

JUnit XML inspected: `build/test-results/integrationTest/TEST-com.example.api.MembershipConstraintsIntegrationTest.xml`, tests=1, skipped=0, failures=0, errors=0; testcase executed in 2.244s, suite timestamp `2026-11-03T21:23:31.324Z`. Result: local T057 server PASS for the enumerated assertions. No Android/iOS, external-provider or BG-1 acceptance is implied. Independent read-only task review: APPROVE, no findings (04.11.2026). External status accounting remains pending until the task commit is recorded.
