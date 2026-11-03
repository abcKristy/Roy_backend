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
## T058 — M3.T1.B02

Preceding accepted task commit: `8a78bc1` (T057). Task changes are identified by the commit containing this section. `ChatReadService.detail` now applies the same `isActiveMember` guard as history/search before reading the summary; the membership SQL join remains in place. No migration changed.

Evidence: `com.example.api.HistoryAccessIntegrationTest`, self-contained UUID fixture on `roy_m3_chat`, same isolated PostgreSQL 18.6/PostGIS 3.6.4 and V001–V014 schema recorded above. Actual HTTP detail/history/search return private message text only to the member; outsider and left-member repeated requests return generic 403 NOT_MEMBER without author/text/count/snippet. Missing and revoked access return 401. The member read cursor remains zero. JWT verification uses production SessionAccessService, with a session-registry test double; membership, message storage and SQL ACL operate against real PostgreSQL. This scenario does not independently prove JDBC session persistence.

On 04.11.2026 Lead executed `./gradlew.bat integrationTest --tests com.example.api.HistoryAccessIntegrationTest --offline --no-daemon --max-workers=1`: BUILD SUCCESSFUL in 1m52s (JDK17/Gradle9.5.1). Inspected `build/test-results/integrationTest/TEST-com.example.api.HistoryAccessIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0; suite timestamp `2026-11-03T21:34:03.139Z`, testcase 2.365s. Local T058 server PASS for those assertions. Independent read-only review: APPROVE, no findings, 04.11.2026. External accounting statuses remain pending group acceptance; BG-1 and mobile acceptance remain open.
## T059 — M3.T1.B03

Preceding task commit: `6c38b07` (T058). Existing production keyset pagination retained without changes. Evidence: `com.example.api.HistoryPaginationIntegrationTest`, self-contained UUID fixture in guarded `roy_m3_chat`, same disposable PostgreSQL18.6/PostGIS3.6.4 and V001–V014 schema recorded above.

Actual REST assertions: latest page seq 5/4; insert committed system seq6 between reads; older pages 3/2 then1 remain disjoint; delta after3 returns4/5 then6 with the stable message ID; exact-limit terminal pages have null continuation; repeated request preserves sequence. Malformed, negative, zero-before, conflicting and overflowing seq parameters return 400 INVALID_FIELD. The member read cursor remains 0.

Lead executed on 04.11.2026: `./gradlew.bat integrationTest --tests com.example.api.HistoryPaginationIntegrationTest --offline --no-daemon --max-workers=1` (JDK17/Gradle9.5.1), BUILD SUCCESSFUL in 2m35s. Inspected `build/test-results/integrationTest/TEST-com.example.api.HistoryPaginationIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0; timestamp `2026-11-03T21:47:04.730Z`; testcase 2.263s. Local T059 server PASS for these assertions. Independent read-only task review: APPROVE, no findings, 04.11.2026. External status accounting remains pending group acceptance. These tests do not complete BG-1 or mobile acceptance.
## Group 1 accepted integration and accounting ledger — 04.11.2026

Working branch: `feat/chat-membership-history`; task commits T057 `8a78bc1`, T058 `6c38b07`, T059 `2f3ea97`. Independent branch reviewer `group_review`: APPROVE, no findings. Lead confirmed fast-forward integration into `feat/back-part-3` and non-force push; both local target and `origin/feat/back-part-3` resolved to `2f3ea9769e9bc432d63f9dd880051f009db64b79`, with clean working tree at the group gate.

Main reference before and immediately after this group's merge remained the accepted prior-fix base `e12ec3302ae9cb74fedc59a6b7ab555742fa8e77`. A later main change to `2c1de31` was made by the separate preceding-review chat, not by M3 integration; the main reference is shared between worktrees. No M3 group was merged or pushed to main.

The following external accounting files were actually updated for the accepted three tasks:

- `spec/plans/tasks/M3/T1/B01-membership-constraints.md`: local server ГОТОВО, checked acceptance and concrete T057 evidence/commit.
- `spec/plans/tasks/M3/T1/B02-history-access.md`: local server ГОТОВО, checked acceptance and concrete T058 evidence/commit.
- `spec/plans/tasks/M3/T1/B03-history-page.md`: local server ГОТОВО, checked acceptance and concrete T059 evidence/commit.
- `spec/plans/tasks/M3/T1/README.md`: B01–B03 checked with dates and commits; remaining B/C unchanged.
- `spec/plans/roadmap/execution-checklist.md`: T057–T059 checked with commits/date/local PASS, M3.T1 partial overview, freshly counted 57/455 checked tasks. Unrelated M1/M2 accounting was preserved.
- `spec/plans/tasks/M3/M3-T1.md`: partial 3/5 server progress (B01–B03 complete; B04–B05 and C open), not whole-parent completion.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: matching partial M3.T1 server summary with evidence link.

Git-topology limitation: all seven files are outside backend Git. Their updates are accounting state in the shared workspace and are not physically contained in `feat/back-part-3` or its remote reference. This ledger versions the precise updated-file list and limitation; user plans were not moved or duplicated into backend Git. BG-1, mobile tasks, overall M3.T1 and overall M3 remain open.
## T060 — M3.T1.B04

Group2 working branch `feat/chat-search-lifecycle`, accepted target/remote base `f1ef52a300d0390a408364313efca4df5e273737`. Existing production search, parameterized SQL and signed scoped cursor retained. No schema changes; same disposable `roy_m3_chat` PostgreSQL18.6/PostGIS3.6.4 environment, V001–V014.

Evidence `com.example.api.HistorySearchIntegrationTest`: actual HTTP trim/Unicode query length3–256, escaped LIKE percent/underscore/backslash, deterministic seq3→2→1 search pages; a backdated seq7 inserted after page1 is excluded from saved snapshot continuation and included in a fresh search. Cursor replay is stable; changed actor/chat/query/limit and tampered cursor return 400 INVALID_CURSOR without snippets. Outsider/left requests return 403 NOT_MEMBER. Search does not advance read cursor. Production JWT verification uses a session-registry test double; this scenario does not establish JDBC session persistence or mobile acceptance.

Lead executed 04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.HistorySearchIntegrationTest --offline --no-daemon --max-workers=1`; exit 0, BUILD SUCCESSFUL in 41s.

Inspected JUnit XML: `build/test-results/integrationTest/TEST-com.example.api.HistorySearchIntegrationTest.xml`; tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-03T22:06:14.294Z, suite seconds=2.318. Local T060 server PASS for the stated assertions. Independent read-only task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance; BG-1 remains open.
## T061 — M3.T1.B05

Preceding accepted task commit: `2b7e5a6db31a0c68ef0145c81b93620befbd8482` (T060), working branch `feat/chat-search-lifecycle`. Existing PointWriteService lifecycle reused unchanged. Environment remains the owned disposable `roy_m3_chat` PostgreSQL18.6/PostGIS3.6.4 with V001–V014; no migration edited.

Evidence: `com.example.api.LeaveLifecycleIntegrationTest`, two self-contained scenarios. Actual HTTP DELETE for a muted member returns 204, removes the actor vote, increments proposal revision, cancels an insufficient-vote booking and emits the expected system messages; repeat 204 preserves the snapshot. A synthetic booking-update trigger raises an error after vote deletion: HTTP 500 and unchanged membership/votes/revision/booking/sequence/system-message snapshot prove rollback; the trigger/function are removed in finally. Leaving the first chat retains a point with a live participant in another topic; final leave removes all point topics/chats/membership/messages/bookings.

The concurrent scenario starts actual join and last-leave coroutines after both readiness barriers, releases a shared start barrier, awaits both results and checks persisted point/topic/chat/membership/ledger invariants for the resulting legal ordering. This is a real simultaneous-start race run, not proof of every possible interleaving. JWT authentication in the HTTP scenario uses production SessionAccessService with a session-registry test double.

Lead executed on 04.11.2026 (JDK17/Gradle9.5.1): `./gradlew.bat integrationTest --tests com.example.api.LeaveLifecycleIntegrationTest --offline --no-daemon --max-workers=1`: exit 0, BUILD SUCCESSFUL in 41s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.LeaveLifecycleIntegrationTest.xml`: tests=2, skipped=0, failures=0, errors=0, timestamp=2026-11-03T22:11:14.918Z, suite seconds=2.607. Local T061 server PASS for these cases. Independent read-only task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance; no mobile, BG-1 or overall M3 acceptance is claimed.
## Group 2 accepted integration and accounting ledger — 04.11.2026

Working branch `feat/chat-search-lifecycle`, created from accepted clean target/origin `f1ef52a300d0390a408364313efca4df5e273737`. Task commits: T060 `2b7e5a6db31a0c68ef0145c81b93620befbd8482`, T061 `1d13f3ce41c4d4c4ebe9b5a49cd3a8d72045ff72`. Independent task and whole-branch read-only reviews: APPROVE, no findings. Lead confirmed fast-forward merge and non-force push only to `feat/back-part-3`; local and origin target both resolved to `1d13f3ce41c4d4c4ebe9b5a49cd3a8d72045ff72` with clean working tree at the group gate. Main before and after this integration: `2c1de31de2a5481e90e3c84e93a8abdb38f16169`, unchanged by this work.

Actually updated external accounting files:

- `spec/plans/tasks/M3/T1/B04-search-query.md`: T060 local server ГОТОВО with checked acceptance, actual 1/0/0/0 execution and commit evidence.
- `spec/plans/tasks/M3/T1/B05-leave-command.md`: T061 local server ГОТОВО with checked acceptance, actual 2/0/0/0 execution and commit evidence.
- `spec/plans/tasks/M3/T1/README.md`: B04–B05 checked with date and task commits.
- `spec/plans/roadmap/execution-checklist.md`: T060–T061 checked with local PASS/date/task commits; M3.T1 backend 5/5, client open; freshly recounted 59/455 checked tasks. Unrelated statuses preserved using optimistic comparison immediately before writing.
- `spec/plans/tasks/M3/M3-T1.md`: backend B01–B05 complete locally, C01–C06 and whole-parent acceptance remain open.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: matching M3.T1 backend 5/5 and partial whole-parent summary.

All six accounting files remain outside backend Git and are not physically versioned by the target branch. This ledger records the actual edits and exact topology limitation, without moving or duplicating plans. Versionable tests and protocol are on the target branch; these local server results do not close mobile tasks, BG-1 or overall M3.

## T062 — M3.T2.B01

Group 3 working branch `feat/chat-send-transactions`, created from accepted clean target/origin `934a61e2cb5f05a902c8760565e793da8dffb550`. The existing strict JSON/JWT/UUID route continues to call the single ChatSendService. The newer author-approved 4096-character message limit is recorded in `docs/database-migration-execution.md`; historical M0's 2000-character text is not used to silently narrow it.

Evidence: `com.example.api.SendInputIntegrationTest`, self-contained synthetic UUID fixture in the owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`. Malformed JSON, unknown/missing/null/numeric fields, array body, empty/blank/4097-codepoint text, missing/invalid key/path, missing/invalid authorization and non-membership are rejected. Database snapshots verify no message, rate, outcome, notification or sequence/read-cursor side effects. Accepted trimmed 4096-emoji input identifies the authenticated author and route chat, persists the returned message ID once, and same-key replay preserves the snapshot. JWT validation uses the production SessionAccessService with a session-registry test double.

The initial required run failed in 44s: a valid 4096-emoji request returned HTTP500 because its saved response exceeded the prior 16KiB `operation_idempotency_snapshot_check`. This was a real server boundary defect, not a skipped test. Append-only `V015__message_idempotency_snapshot_size.sql` raises only `send_message` response snapshots to 65536 bytes; other operations retain 16384 bytes and the object/null shape constraint. Applied V001–V014 were not edited. Migration run completed successfully in 35s; schema is now V015. The retry passed in 37s.

The enhanced regression additionally sends 4096 non-whitespace U+0001 controls from a second active member, confirms HTTP201 and actual stored snapshot size above 24576 and at most 65536 bytes. Real SQLSTATE23514 checks retain the other-operation 16KiB cap, send 64KiB cap and object shape. Worst-case JSON escaping is six bytes per codepoint: 24576 bytes for text plus at most 480 bytes for the canonical author name trimmed by current registration/profile services (at most 80 characters). V006 bounds char_length(btrim(display_name)), so this is not a raw-length guarantee for arbitrary legacy or direct-SQL rows; UUIDs, fixed avatar URL, timestamp and JSON fields fit within a conservative 4KiB envelope budget, totaling under 30KiB. The 64KiB limit covers this representation without removing a bound.

Actual commands executed by Lead on 04.11.2026 with JDK17/Gradle9.5.1:

- `./gradlew.bat runMigrations --offline --no-daemon --max-workers=1`: PASS, 35s, V015 applied/validated.
- `./gradlew.bat integrationTest --tests com.example.api.SendInputIntegrationTest --offline --no-daemon --max-workers=1`: initial FAIL44s, retry PASS37s, enhanced PASS36s, NUL-fix final PASS38s (exit0).

Task-review finding `T062-INPUT-NUL` (P2): REQUEST CHANGES. Reviewer identified that `ChatSendService.send` accepted U+0000 at input validation, while PostgreSQL text rejects it during insertion, producing HTTP500. The minimal fix rejects NUL as `InvalidText` before entering the transaction. The HTTP `a\u0000b` regression now expects 422 INVALID_FIELD and unchanged full database snapshot; U+0001 remains accepted. This is a task-review finding, not a final-block audit ID. Code and regression are fixed; independent targeted re-review APPROVE, 04.11.2026.

Final XML inspected: `build/test-results/integrationTest/TEST-com.example.api.SendInputIntegrationTest.xml`, tests=1, skipped=0, failures=0, errors=0, timestamp `2026-11-03T22:32:04.913Z`, suite seconds 2.673. Local T062 server PASS for the enumerated assertions. Independent targeted task re-review: APPROVE, no remaining findings, 04.11.2026. External accounting remains pending group acceptance. No mobile, BG-1 or overall M3 acceptance is claimed.
