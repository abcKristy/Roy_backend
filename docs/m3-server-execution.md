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

## T063 — M3.T2.B02

Preceding accepted task commit `022156e` (T062), working branch `feat/chat-send-transactions`. Existing production ChatSendService transaction and locks retained. Environment: owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, migrations V001–V015.

Evidence `com.example.chats.SendTransactionIntegrationTest` calls the real application service and PostgreSQL, without claiming HTTP/session validation. Two different actors reach readiness barriers before a shared start barrier; both actual asynchronous sends complete with distinct message IDs and sequence1/2. Returned server timestamps fall between database clock samples. Persisted message text/sequence/time, matching rate timestamp, sender read cursor, outcome result reference and saved response are checked together.

A unique temporary PostgreSQL trigger rejects insertion of the third actor's specific outcome key with SQLSTATE P0001. The thrown failure and identical before/after snapshots verify rollback of message, chat sequence, point timestamp, memberships/read cursors, actor rate/outcome and chat notification events. Trigger/function cleanup runs in finally. Retrying the same command after cleanup succeeds at sequence3; same-key replay preserves the committed response and snapshots, with one actor rate event and one outcome. The readiness/start barriers establish an executed concurrent-start case; they do not establish every possible database interleaving.

Lead executed 04.11.2026, JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.chats.SendTransactionIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in42s. Inspected XML `build/test-results/integrationTest/TEST-com.example.chats.SendTransactionIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp `2026-11-03T22:36:20.181Z`, suite seconds 1.777. Local T063 server PASS for the stated assertions. Independent read-only task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance; mobile, BG-1 and overall M3 acceptance remain open.

## T064 — M3.T2.B03

Preceding task commit `89f5982` (T063), branch `feat/chat-send-transactions`. Existing production replay retained. Environment remains owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015.

Evidence `com.example.api.SendReplayIntegrationTest` executes real HTTP and database assertions. Two same-actor/same-key requests start after readiness/shared barriers: trimmed and untrimmed equivalent text return the same HTTP201 and complete Message DTO, with one persisted message, rate event, outcome and recipient notification. Changed text or route chat with the key returns409 IDEMPOTENCY_KEY_REUSED. The production SQL selects by actor/operation/key, and requestHash includes route chat and canonical text; different actor with the same key commits an independent message.

For the second actor the first committed response body is intentionally discarded, then retry is checked against the saved database outcome and result reference. This exercises application response-discard recovery; it is not a TCP/network fault-injection test. Full semantic JSON DTO equality is asserted, including stable ID/schema/status; wire JSON key ordering or byte identity is not promised by the contract or this evidence.

After changing the original author's persisted profile and mute deadline, advancing the read cursor, and executing actual PointWriteService.leave, repeats return the original201 snapshot without changing snapshots of messages/chat/point/memberships/user/rate/outcome/notifications. An expired JWT is rejected with401 before replay and leaves those snapshots unchanged. Production JWT validation uses a session-registry test double. The route publishes only on Created in code; actual no-repeat WebSocket delivery is not claimed here and remains a T066 runtime scenario.

Lead executed on04.11.2026, JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.SendReplayIntegrationTest --offline --no-daemon --max-workers=1`; initial PASS42s, enhanced final PASS40s, exit0. Final XML `build/test-results/integrationTest/TEST-com.example.api.SendReplayIntegrationTest.xml` inspected: tests=1, skipped=0, failures=0, errors=0, timestamp `2026-11-03T22:42:18.579Z`, suite seconds 2.619. Local T064 server PASS for these assertions. Independent read-only task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance; mobile, BG-1 and overall M3 remain open.
## Group 3 accepted integration, final commit identities and accounting — 04.11.2026

Working branch `feat/chat-send-transactions`, accepted clean target/origin base `934a61e2cb5f05a902c8760565e793da8dffb550`. Definitive published task commits: T062 `44892a5`, T063 `d7408e9`, T064 `78352e7`. Independent task and whole-branch read-only reviews: APPROVE, no remaining confirmed findings. Lead confirmed fast-forward integration and non-force target push; local `feat/back-part-3` and `origin/feat/back-part-3` both resolved to `78352e7dda318b394687e9874e1d3a06f9f44719` with clean working tree at the group gate. Main before/after remained `2c1de31de2a5481e90e3c84e93a8abdb38f16169`.

Local prepublication documentation-only autosquash mapping: old `022156e` → final `44892a5` (T062), old `89f5982` → final `d7408e9` (T063), old `d535a43` → final `78352e7` (T064). Older hashes in preceding execution notes identify historical prepublication snapshots only; this ledger defines the final published task identities. One task remains one task commit; no force-push of published history was used.

Task-review finding `T062-INPUT-NUL` (P2) was fixed and independently approved before publication, with HTTP NUL regression and unchanged failure snapshots. Group-review finding `G3-DOC-NAME-BOUND` (P3) was corrected before publication through the documentation-only corrections and re-review. These are task/group review identifiers, distinct from the later final-block audit IDs and its finding counts; no final audit is claimed here.

Actual accounting updates outside backend Git:

- `spec/plans/tasks/M3/T2/B01-send-input.md`: T062 ГОТОВО locally, checked criteria, input/NUL evidence and final commit.
- `spec/plans/tasks/M3/T2/B02-send-transaction.md`: T063 ГОТОВО locally, checked criteria, transaction/concurrency/rollback evidence and final commit.
- `spec/plans/tasks/M3/T2/B03-send-replay.md`: T064 ГОТОВО locally, checked criteria, replay evidence/limits and final commit.
- `spec/plans/tasks/M3/T2/README.md`: B01–B03 checked with dates and final task commits.
- `spec/plans/roadmap/execution-checklist.md`: T062–T064 checked, M3.T2 partial B3/6/C open; freshly recounted 62/455 checked tasks, unrelated accounting preserved with an optimistic read comparison.
- `spec/plans/tasks/M3/M3-T2.md`: backend 3/6 complete locally; B04–B06/C and overall parent acceptance remain open.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: matching M3.T2 partial server summary with protocol link.

Schema at accepted group3: V001–V015, PostgreSQL18.6/PostGIS3.6.4. All seven accounting files remain outside backend Git: their physical contents are not part of target/origin history. The versioned ledger records the exact updated paths, final commits and limitation without moving or duplicating plans. Mobile, external integrations, BG-1 and overall M3 remain open.

## T065 — M3.T2.B04

Group 4 branch `feat/chat-realtime-sessions`, accepted clean target/origin base `f4e24f4d7531e022c431659f16fa5cfcf0f1e7c9`. Production hub/routes retained unchanged. Environment: the same owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015.

Evidence `com.example.realtime.WebSocketSubscriptionIntegrationTest` uses actual JdbcSessionRepository and refresh-capable auth_sessions rows. Missing, expired and persisted-revoked JWT handshakes are verified as HTTP401 with native JDK17 WebSocketHandshakeException against an ephemeral Netty server bound to 127.0.0.1, using the production transport/routes and the same hub/database access. Each temporary server stops in finally. Positive headless sockets use Ktor testhost: duplicate mixed-chat subscription preserves requestId and accepts only the active membership; a neighboring existing chat and a nonexistent chat are rejected through the same response shape.

Synthetic scope probes through production RealtimeHub, followed by correlated user-scope sentinels, verify receipt of the allowed chat and exclusion of foreign, unsubscribed and post-leave chat probes. Actual HTTP DELETE leaves membership; subscriptions are rejected afterward. Two sockets for the same actor use distinct persisted session IDs. Actual SessionAccessService.logout revokes only the first database session, invokes production hub revocation, and closes that socket4401; the other receives a subsequent user probe and its database session remains active. These probes establish subscription/session isolation, not complete Message publication; the latter remains T066. They do not prove every queued-delivery race/interleaving.

Earlier fixture attempts were not product defects or successful acceptance: ordinary non-upgrade GET returned400 (first required run FAIL42s); a shell-written stray quote caused compile failure30s before the test body; manually setting Upgrade headers on the regular client caused UnsafeHeaderException (FAIL38s); Ktor testhost masked rejected upgrades as IllegalStateException before the client status pipeline (FAIL41s). Installed Ktor/JDK APIs were inspected, and the final native loopback handshake helper observes the actual response status without unsafe request headers or new dependencies.

Lead executed on04.11.2026, JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.realtime.WebSocketSubscriptionIntegrationTest --offline --no-daemon --max-workers=1`, final exit0, BUILD SUCCESSFUL in42s.

Final XML inspected: `build/test-results/integrationTest/TEST-com.example.realtime.WebSocketSubscriptionIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp `2026-11-03T23:10:11.823Z`, suite seconds 3.556. Local T065 server PASS for these assertions. Independent read-only task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance; mobile, BG-1 and overall M3 remain open.
