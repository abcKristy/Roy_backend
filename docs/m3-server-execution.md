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

## T066 — M3.T2.B05

Preceding accepted task commit `7a8a8c4` (T065), branch `feat/chat-realtime-sessions`. Production publication retained unchanged; the fixture uses real JdbcSessionRepository sessions and the owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015.

Evidence `com.example.realtime.MessagePublicationIntegrationTest` executes actual HTTP POST through ChatWriteRoutes/ChatSendService and three headless sockets. Author and recipient subscribed to the same chat receive the same canonical UUID eventId and v1 envelope, chat scope, occurredAt and complete Message payload equal to HTTP201 (ID/sequence/text/author/time and full DTO). An independent JDBC connection reads the persisted message after event receipt and confirms the returned fields. The adjacent-topic subscriber receives no message event before its correlated user sentinel or during the subsequent 250ms observation.

A same-key POST returns the original201 DTO and unchanged snapshots of messages/chat/point/memberships, both sending actors' rate/outcome records and notification events. On each socket a user sentinel published after the awaited POST is drained while rejecting every chat.message.created, followed by a bounded 250ms no-frame observation. A unique temporary outcome-insert trigger forces HTTP500 for a different active sender; identical full snapshots prove rollback. All sockets again reach their user sentinels without a message event and remain alive through the observation window. Trigger/function cleanup runs in finally.

The correlation and 250ms windows establish the actual observed no-repeat/no-rollback-success cases; they do not promise unbounded exactly-once delivery or durable WS replay. User sentinels are synthetic observation probes; the successful Message event itself is produced by the actual POST route after commit.

Lead executed on04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.realtime.MessagePublicationIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in44s.

Final XML inspected: `build/test-results/integrationTest/TEST-com.example.realtime.MessagePublicationIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp `2026-11-03T23:15:59.432Z`, suite seconds 4.844. Local T066 server PASS for these assertions. Independent read-only task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance; mobile, BG-1 and overall M3 remain open.

## T067 — M3.T2.B06

Preceding accepted task commit `f25cfea` (T066), branch `feat/chat-realtime-sessions`. Existing production heartbeat and session lifecycle retained unchanged. The owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat` runs V001–V015; JdbcSessionRepository validates actual refresh-capable database session rows.

Evidence `com.example.realtime.WebSocketLifecycleIntegrationTest` runs the production realtime transport/routes on ephemeral loopback Netty and native JDK17 WebSocket listeners. No accelerated clock or shortened heartbeat is used. Native onPing records System.nanoTime relative to onOpen; the configured first server ping is accepted only in a documented 28000–40000ms scheduling tolerance around 30s. The client Pong send future completes. A native client Ping also obtains an actual server Pong callback, exercising control frames in both directions rather than only checking hello metadata.

Three persisted sessions belong to the same actor. One JWT is signed to expire five seconds after fixture token creation; its close4401 is observed only after that signed expiry, with native close Instant and monotonic elapsed checks bounded to 4000–40000ms (the existing auth watcher checks every30s). A third socket is abruptly aborted, its database session remains usable and the same JWT reconnects with hello; actual SessionAccessService.logout then revokes only that session and closes its reconnected socket4401. The other session remains active in JDBC and receives a correlated user event after the expired/revoked sockets closed. Those closed sockets receive no post-close frame during 250ms observations. The survivor performs an ordinary close1000. Sockets are aborted and the temporary Netty server stopped in finally; production finally unregisters the client and cancels sender/auth-watcher jobs.

Actual captured safe timing output: `Observed native server heartbeat 30009ms; expiry close 30017ms; client Pong sent and server Pong received; expired/revoked close4401; other session live`.

The evidence distinguishes the executed abrupt transport loss from authenticated logout and JWT expiry. It does not establish every network failure/interleaving, heap/job leak absence, durable replay, or a full idle-timeout/nonresponding-peer acceptance test. The heartbeat/configuration and bounded observed session outcomes are the scope of this local case.

Lead executed on04.11.2026, JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.realtime.WebSocketLifecycleIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in1m18s.

Final XML inspected: `build/test-results/integrationTest/TEST-com.example.realtime.WebSocketLifecycleIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp `2026-11-03T23:21:34.559Z`, suite seconds 35.166. Local T067 server PASS for the stated assertions. Independent read-only task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance; mobile, BG-1 and overall M3 remain open.
## Group 4 accepted integration and accounting ledger — 04.11.2026

Working branch `feat/chat-realtime-sessions`, accepted clean target/origin base `f4e24f4d7531e022c431659f16fa5cfcf0f1e7c9`. Task commits: T065 `7a8a8c4`, T066 `f25cfea`, T067 `42f4b45`. Independent task and whole-branch read-only reviews: APPROVE, no findings. Lead confirmed fast-forward integration and non-force target push; local `feat/back-part-3` and `origin/feat/back-part-3` both resolved to `42f4b45a661efb765382340b1cbf0d5c0f457f21`, clean at the group gate. Main before/after stayed `2c1de31de2a5481e90e3c84e93a8abdb38f16169`; M3 was not merged or pushed to main.

Accepted evidence uses persisted JdbcSessionRepository sessions, real production REST/WS routes and isolated PostgreSQL18.6/PostGIS3.6.4, V001–V015. The captured native server heartbeat was 30009ms and JWT expiry close 30017ms. These measured outcomes and bounded observations establish the executed cases described above; they do not prove all queued-delivery races, all network faults, leak absence, durable replay or complete idle-timeout/nonresponding-peer acceptance.

Actually updated external accounting paths:

- `spec/plans/tasks/M3/T2/B04-socket-scope.md`: T065 local ГОТОВО, checked acceptance, persisted-session/ACL evidence and task commit.
- `spec/plans/tasks/M3/T2/B05-message-event.md`: T066 local ГОТОВО, checked acceptance, actual committed message/replay/rollback evidence and task commit.
- `spec/plans/tasks/M3/T2/B06-socket-expiry.md`: T067 local ГОТОВО, checked acceptance, native heartbeat/expiry/session lifecycle evidence and task commit.
- `spec/plans/tasks/M3/T2/README.md`: B04–B06 checked with date and commits.
- `spec/plans/roadmap/execution-checklist.md`: T065–T067 checked; M3.T2 backend 6/6 locally complete, C open; fresh total 65/455 checked tasks. Unrelated accounting preserved through optimistic reread before write.
- `spec/plans/tasks/M3/M3-T2.md`: B01–B06 locally complete, C01–C06 and whole-parent acceptance remain open.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: corresponding M3.T2 backend 6/6, partial parent summary.

All seven files remain outside backend Git and are not physically contained in the target/origin branch. This versioned ledger records the exact updated paths and topology boundary without moving or duplicating user plans. Mobile, overall parent/M3, external integration and BG-1 acceptance remain open.
## T068 — M3.T3.B01

Group5 branch `feat/chat-summary-unread`, accepted clean target/origin base `0a92097bdddee5563af9960f6670dcedc97b7418`. Existing production projection retained unchanged. Fixture: owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, migrations V001–V015, real JdbcSessionRepository and persisted refresh-capable sessions.

Evidence `com.example.api.ChatSummaryIntegrationTest` executes actual GET /api/v1/me/chats. Five synthetic chats distinguish active text/system/empty membership from foreign and left membership; only the first three are listed. Exact page, summary, topic and preview field sets, IDs, address/custom topic labels, participant counts, valid timestamps and null absent proposal are asserted. Text preview follows the highest message sequence despite a future timestamp on an older sequence. System preview is the safe Russian text for member_left, without raw payload. An account without memberships gets an empty page; missing access gets 401. Repeated GET is stable and does not advance the actor read cursors. Phone values, private profile/hash/age/author/avatar fields and raw system payload are absent from the summary.

Lead executed 04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.ChatSummaryIntegrationTest --offline --no-daemon --max-workers=1`, exit 0, BUILD SUCCESSFUL in 47s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.ChatSummaryIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-03T23:35:21.378Z, suite seconds=2.226. Local T068 server PASS for these assertions. Independent read-only task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance; mobile, BG-1 and overall M3 acceptance remain open.

## T069 — M3.T3.B02

Preceding accepted task commit `8c135548966ff21f2abe4a90791c994334888719` (T068), working branch `feat/chat-summary-unread`. Production unread SQL retained unchanged. Same owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015; real JdbcSessionRepository validates persisted session rows.

Evidence `com.example.api.UnreadCountIntegrationTest` exercises actual HTTP and database state. Four visible TEXT/SYSTEM rows at seq1/4/9/10 with actor cursor1 yield unread3, recipient cursor0 yields4, demonstrating row counts rather than sequence subtraction. Repeated background GET list/detail/history/search preserves the actor count. Another104 visible text rows yield exact numeric107/108 in JSON, with no UI 99+ formatting. Actual own-send POST assigns seq115 and advances its author's cursor to115, yielding author unread0 and recipient109. Recipient read9 yields106; late read4 remains cursor9/count106; late author read1 remains115. Same-key send replay returns the original DTO without changing counts. Final independent JDBC assertions confirm actor115/recipient9 cursors and109 persisted messages. The test does not infer invisible/deleted storage rows beyond the actual missing-sequence fixture.

Lead executed 04.11.2026, JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.UnreadCountIntegrationTest --offline --no-daemon --max-workers=1`, exit 0, BUILD SUCCESSFUL in 43s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.UnreadCountIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-03T23:41:31.316Z, suite seconds=2.609. Local T069 server PASS for these assertions. Independent read-only task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance; no mobile, BG-1 or overall M3 acceptance claimed.

## Group 5 accepted integration and accounting ledger — 04.11.2026

Working branch `feat/chat-summary-unread`, accepted clean target/origin base `0a92097bdddee5563af9960f6670dcedc97b7418`. Task commits: T068 `8c135548966ff21f2abe4a90791c994334888719`, T069 `cdf64416900bc3270502c37647aa31fbe100a095`. Independent task and whole-branch read-only reviews: APPROVE, no findings. Lead confirmed fast-forward integration and non-force target push; local `feat/back-part-3` and `origin/feat/back-part-3` both resolved to `cdf64416900bc3270502c37647aa31fbe100a095`, clean at the group gate. Main before/after remained `2c1de31de2a5481e90e3c84e93a8abdb38f16169`. No M3 code was merged or pushed to main.

Actually updated external accounting paths:

- `spec/plans/tasks/M3/T3/B01-chat-summary.md`: T068 local server ГОТОВО, checked criteria, real projection/session evidence and task commit.
- `spec/plans/tasks/M3/T3/B02-unread-count.md`: T069 local server ГОТОВО, checked criteria, actual row count/own-send/background/read/replay evidence and task commit.
- `spec/plans/tasks/M3/T3/README.md`: B01–B02 checked with date and task commits.
- `spec/plans/roadmap/execution-checklist.md`: T068–T069 checked, M3.T3 partial backend 2/4 and client open; fresh checked total 67/455. Unrelated statuses preserved with optimistic reread before write.
- `spec/plans/tasks/M3/M3-T3.md`: B01–B02 locally complete, B03–B04/C and overall parent acceptance remain open.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: corresponding partial M3.T3 server summary.

The same owned disposable PostgreSQL18.6/PostGIS3.6.4 database runs V001–V015; both cases validate real persisted sessions through JdbcSessionRepository. All six accounting files remain outside backend Git and their physical contents do not belong to target/origin history. This ledger records the exact updates/topology limitation; user plans were not moved or duplicated. Mobile, BG-1, overall parent/M3 and external integration acceptance remain open.

## T070 — M3.T3.B03

Group6 branch `feat/chat-read-pagination`, accepted clean target/origin base `4ff36e21ed40f83356a6c58fec75cdcf76936cf3`. Production cursor implementation retained unchanged. Same owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015; persisted sessions validated by actual JdbcSessionRepository.

Evidence `com.example.api.ReadCursorIntegrationTest`: actual PUT read-cursor rejects negative, above-last, Long.MAX_VALUE, null, boolean, fractional, missing and unknown-field input with422 INVALID_FIELD; malformed JSON returns400 INVALID_JSON. Canonical semantic JDBC snapshots of memberships/chat/messages/point remain unchanged for these errors. Missing, outsider, persisted-left and revoked-session requests refuse access and preserve the scoped snapshots. Zero and late valid values return canonical max cursor; repeat and late responses preserve Membership fields and joinedAt, with exact safe DTO keys asserted.

Two actual HTTP advances (6/9) start after both coroutine readiness barriers and a shared release, bounded by15s. The6 response is between6 and9 according to legal serialization; the9 response and final canonical cursor are9. Subsequent late4 cannot lower it; maximum10 is accepted, and late0 returns10. This run establishes the observed concurrent advance ordering, not every possible interleaving. The test does not execute a concurrent own-send/read race; own-send cursor evidence belongs to T069.

Lead executed 04.11.2026, JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.ReadCursorIntegrationTest --offline --no-daemon --max-workers=1`, exit 0, BUILD SUCCESSFUL in 47s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.ReadCursorIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-03T23:53:19.747Z, suite seconds=2.585. Local T070 server PASS for these assertions; final independent review and external accounting pending. Mobile, BG-1 and overall M3 remain open.

## T071 — M3.T3.B04

Preceding task commit `78b5609942bf53b3fcf714441e21112c64fb01c8` (T070), branch `feat/chat-read-pagination`. Existing production signed keyset cursor retained unchanged. Same owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015; actual JdbcSessionRepository validates persisted sessions.

Evidence `com.example.api.ChatListPaginationIntegrationTest`: seven synthetic chats distinguish five active memberships from foreign and left membership. Real GET /me/chats asserts created_at DESC with distinct timestamps and id DESC for ties; pages2/2/1 yield the exact expected IDs without duplicates, repeated continuation yields the same IDs. Inserting new activity in a lower-created chat does not reorder the created_at/id list. Exact-limit end pages (limit5 and final limit1) and an empty account have null nextCursor. A signed search cursor used in list scope, tampered/truncated cursors, changed actor and changed limit return400 INVALID_CURSOR without items. Limits0/101/negative/non-numeric/overflow return400 INVALID_FIELD. Read cursors remain unchanged.

This is stable keyset continuation under the exercised membership set. The implementation/evidence does not promise a frozen membership snapshot while memberships change; later joins/leaves can change what subsequent pages contain.

Lead executed 04.11.2026, JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.ChatListPaginationIntegrationTest --offline --no-daemon --max-workers=1`, exit 0, BUILD SUCCESSFUL in 48s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.ChatListPaginationIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-03T23:58:31.726Z, suite seconds=2.577. Local T071 server PASS for these assertions. Mobile, BG-1 and overall M3 remain open.

Task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance.

## Group 6 accepted integration and accounting ledger — 04.11.2026

Working branch `feat/chat-read-pagination`, accepted clean target/origin base `4ff36e21ed40f83356a6c58fec75cdcf76936cf3`. Task commits: T070 `78b5609942bf53b3fcf714441e21112c64fb01c8`, T071 `8199271ba5049e42ef26ad60f57281042f0d93ee`. Independent task and whole-branch read-only reviews: APPROVE, no findings. This explicitly closes the historical pending-review/accounting state in the earlier T070 execution paragraph: its final task review is APPROVE, no findings, 04.11.2026, and accounting completed as listed here. Lead confirmed fast-forward integration and non-force push only to target; local `feat/back-part-3` and `origin/feat/back-part-3` both resolved to `8199271ba5049e42ef26ad60f57281042f0d93ee`, clean at the group gate. Main before/after remained `2c1de31de2a5481e90e3c84e93a8abdb38f16169`.

Actually updated external accounting paths:

- `spec/plans/tasks/M3/T3/B03-read-cursor.md`: T070 local server ГОТОВО, checked criteria, runtime/concurrency/error evidence and task commit.
- `spec/plans/tasks/M3/T3/B04-chat-list-cursor.md`: T071 local server ГОТОВО, checked criteria, ordering/scoped cursor/runtime evidence and task commit.
- `spec/plans/tasks/M3/T3/README.md`: B03–B04 checked with date and task commits.
- `spec/plans/roadmap/execution-checklist.md`: T070–T071 checked; M3.T3 backend 4/4 locally complete, C open; fresh total 69/455 checked tasks, unrelated accounting preserved with optimistic reread.
- `spec/plans/tasks/M3/M3-T3.md`: backend B01–B04 complete locally, C and whole-parent acceptance open.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: corresponding M3.T3 backend 4/4, partial whole-parent summary.

Runtime remains the owned disposable PostgreSQL18.6/PostGIS3.6.4 with V001–V015 and real JdbcSessionRepository sessions. The observed concurrent cursor advance establishes the exercised ordering, not all interleavings; chat pagination promises no frozen membership snapshot. All six accounting files remain outside backend Git and their physical contents are not versioned by target/origin. This ledger records the exact paths and topology limitation; no user plans were moved or duplicated. Mobile, BG-1, overall parent/M3 and external integrations remain open.

## T072 — M3.T4.B01

Group 7 branch `feat/chat-report-transactions`, accepted clean target/origin base `8decc3f4107f2fdc66319d6da08cdc578ca833cd`. Existing context/target guards retained. ReportRoutes rejects NUL reason as 422 INVALID_FIELD, and ModerationService rejects NUL before its transaction. This input gap was confirmed from code and PostgreSQL text representability; no pre-fix HTTP500 reproduction is claimed. No migration changed; environment remains the owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015.

Evidence `com.example.api.ReportContextIntegrationTest` uses actual HTTP, JdbcSessionRepository and persisted refresh-capable sessions. Reporter must be an active context member; outsider and left reporters are denied. Active target and a left target author of a visible text message are accepted. A target related only to another chat, left target without history, deleted target, nonexistent target, self-report, inaccessible/nonexistent/deleted context are refused without report/outcome or fixture-user state changes. Repeated refusals preserve snapshots scoped to every fixture actor, including target mute/profile state. Error bodies do not reveal the fixture message.

Strict JSON failures, invalid/missing key, missing authentication, blank/overlong/NUL reason are covered. A direct service NUL command fails before database writes. Trimmed 1000 supplementary-codepoint reason and 1000 U+0001 controls are accepted; persisted reasons, original replay DTO and bounded report outcome snapshots are checked. Report response exposes only id/reportedAt.

The first required run failed in 1m35s before HTTP execution: the synthetic deleted target set deleted_at without clearing profile fields, violating users_active_profile_check. The fixture was corrected to the existing deleted-profile invariant; this was a test-fixture failure, not a product defect.

Lead executed 04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.ReportContextIntegrationTest --offline --no-daemon --max-workers=1`; corrected run exit 0, BUILD SUCCESSFUL in 45s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.ReportContextIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-04T00:19:29.599Z, suite seconds=3.302. Local T072 server PASS for the stated assertions. Mobile, BG-1 and overall M3 remain open.

Task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance.

## T073 — M3.T4.B02

Preceding accepted task commit `644b8c0ae9a63417462647821e369ab7589dc7d7` (T072), branch `feat/chat-report-transactions`. Existing UNIQUE reporter/target and context ON DELETE SET NULL constraints from V009 retained unchanged. Environment remains owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015, with actual JdbcSessionRepository sessions.

Evidence `com.example.api.ReportLifetimeIntegrationTest` starts two actual HTTP POSTs for one reporter/target pair with different keys after readiness and shared-start barriers, bounded to 15s. The resulting statuses are one201 and one409 DUPLICATE_REPORT. Independent SQL verifies exactly one report and one successful report outcome. A direct duplicate-pair INSERT produces SQLSTATE23505. New keys with a different reason or another accessible context still return DUPLICATE_REPORT and preserve scoped report/outcome/user snapshots; the original key returns its original201 DTO without additional effects.

Deleting the original chat through SQL leaves the same report ID, null context_chat_id and lifetime target count1. Original-key replay still returns the saved201. A new key through another accessible context returns DUPLICATE_REPORT without resetting count; a new request through the deleted context returns403. After the reporter leaves the remaining context, a fresh request returns403 FORBIDDEN, preserving the snapshot instead of revealing duplicate status to an inaccessible context. The database deletion specifically exercises FK retention; point lifecycle deletion evidence belongs to T061. The concurrent start covers the executed ordering, not every interleaving.

Lead executed 04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.ReportLifetimeIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in43s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.ReportLifetimeIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-04T00:24:52.820Z, suite seconds=2.54. Local T073 server PASS for the stated assertions. Mobile, BG-1 and overall M3 remain open.

Task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance.

## T074 — M3.T4.B03

Preceding accepted task commit `575f1a0c82ab944168936758da3d8f96563e06ba` (T073), branch `feat/chat-report-transactions`. Existing production report transaction retained unchanged. Environment remains owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015, actual HTTP/JdbcSessionRepository sessions.

Evidence `com.example.api.ReportTransactionIntegrationTest` seeds fifteen unique prior reporters against one target, then installs a key-specific PostgreSQL constraint trigger on outcome insertion, DEFERRABLE INITIALLY DEFERRED. The trigger raises at COMMIT after the service's report, target-mute and outcome work, causing HTTP500. Exact snapshots of every fixture actor's reports/outcomes/user rows remain unchanged; independent SQL confirms fifteen reports and null target mute/trigger fields. Trigger/function cleanup runs in finally. This is a deferred commit-failure test, not merely a pre-insert statement refusal.

The same key succeeds after cleanup with one canonical201 id/reportedAt; the time falls between separate database clock samples. Independent JDBC checks correlate persisted reporter/target/context/trimmed reason/server timestamp, target trigger timestamp, seven-day deadline, outcome reference and saved201 body, with exactly sixteen target reports. Repeats return the original201 and preserve committed snapshots. Changed reason, target or context with the key returns409 IDEMPOTENCY_KEY_REUSED without changes. The sixteenth-report setup establishes atomic target updates in this transaction case; full threshold/concurrent-boundary acceptance remains T075.

Lead executed 04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.ReportTransactionIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in50s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.ReportTransactionIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-04T00:29:49.716Z, suite seconds=2.589. Local T074 server PASS for the stated assertions. Mobile, BG-1 and overall M3 remain open.

Task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance.

## Group 7 accepted integration and accounting ledger — 04.11.2026

Working branch `feat/chat-report-transactions`, accepted clean target/origin base `8decc3f4107f2fdc66319d6da08cdc578ca833cd`. Task commits: T072 `644b8c0ae9a63417462647821e369ab7589dc7d7`, T073 `575f1a0c82ab944168936758da3d8f96563e06ba`, T074 `f8b1f6ed5483c8df0d1f0b300ecab7f2e5cd91a5`. Independent task and whole-branch read-only reviews: APPROVE, no remaining findings. Lead confirmed fast-forward integration and non-force target push; local `feat/back-part-3` and `origin/feat/back-part-3` both resolved to `f8b1f6ed5483c8df0d1f0b300ecab7f2e5cd91a5`, clean at the group gate. Main before/after remained `2c1de31de2a5481e90e3c84e93a8abdb38f16169`.

Actually updated external accounting paths:

- `spec/plans/tasks/M3/T4/B01-report-context.md`: T072 local server ГОТОВО, checked criteria, HTTP context/target/input evidence and commit.
- `spec/plans/tasks/M3/T4/B02-report-unique.md`: T073 local server ГОТОВО, checked criteria, concurrent pair/lifetime retention/replay evidence and commit.
- `spec/plans/tasks/M3/T4/B03-report-transaction.md`: T074 local server ГОТОВО, checked criteria, deferred COMMIT-failure rollback/canonical outcome evidence and commit.
- `spec/plans/tasks/M3/T4/README.md`: B01–B03 checked with date and task commits.
- `spec/plans/roadmap/execution-checklist.md`: T072–T074 checked; M3.T4 partial backend 3/5 and client open; freshly recounted 72/455 checked tasks, unrelated accounting preserved with optimistic reread.
- `spec/plans/tasks/M3/M3-T4.md`: B01–B03 complete locally; B04–B05/C and whole-parent acceptance open.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: corresponding partial M3.T4 server summary.

The owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat` remains at V001–V015, with actual JdbcSessionRepository sessions. All seven accounting files remain outside backend Git and are not physically versioned by target/origin; the versioned ledger records the exact updated paths and topology limitation without moving or duplicating plans. The executed two-request race does not prove every interleaving, and T074's sixteenth-report atomic update does not replace T075 full threshold acceptance. Mobile, BG-1, overall parent/M3 and external integration acceptance remain open.
