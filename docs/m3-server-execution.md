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

## T075 — M3.T4.B04 lifetime threshold and mute deadline — 04.11.2026

Base `d33297fa1833f6a36949dcfffea5ecb0257093bf`, working branch `feat/chat-mute-enforcement`; disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, migrations V001–V015 unchanged. Production ModerationService already implements the accepted contract; this task adds a separate real HTTP/JDBC acceptance fixture with JdbcSessionRepository-backed sessions.

`com.example.api.MuteThresholdIntegrationTest` submits fifteen distinct successful reports and independently observes no change to target mute, trigger or updated timestamp. Deleting their original chat leaves all fifteen lifetime reports with null context links. Two distinct reporters then start together behind a coroutine readiness/start barrier in another accessible context: both return201, the count becomes17, and one persisted trigger/updatedAt equals the earlier server reportedAt. Both report times lie between independent database clock samples; message_muted_until is exactly trigger+604800 seconds (7×24 hours), establishing server UTC arithmetic rather than device time.

Replaying either successful key preserves original201 bodies and complete fixture report/outcome/user snapshots. A fresh duplicate pair returns409 DUPLICATE_REPORT without changes. An eighteenth unique report does not alter mute/trigger/updatedAt. The fixture then sets the deadline into the past while retaining the original trigger; a nineteenth unique report leaves that persisted state unchanged. Lifetime counts remain19, including fifteen reports whose context was deleted. This proves the one-time trigger policy for later reports after expiry; it does not simulate waiting seven days. A manually populated longer deadline with a null lifetime trigger has no specified policy in the accepted contract and is not claimed as verified or changed; no maximum-deadline rule was invented.

Lead executed 04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.MuteThresholdIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in1m2s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.MuteThresholdIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-04T00:45:23.487Z, suite seconds=3.315. Local T075 server PASS for the stated threshold/deadline assertions. The barrier case observes one concurrent execution, not all schedules. Send enforcement, read/leave under mute belong to T076; mobile, BG-1 and overall M3 remain open.

Task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance.

## T076 — M3.T4.B05 authoritative send mute guard — 04.11.2026

Preceding accepted task `682bfb0abeee2a1425043ceabb1a67a2202b9568`, working branch `feat/chat-mute-enforcement`; owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, migrations V001–V015 unchanged. Production ChatSendService already checks authoritative persisted mute before rate evaluation; only a separate acceptance fixture was added.

`com.example.api.MuteSendGuardIntegrationTest` uses two different persisted auth_sessions and JWT session IDs for the same actor through actual JdbcSessionRepository authentication. After a successful send and recipient reply, the fixture directly persists a future mute deadline and trigger. This isolates send enforcement; report threshold creation is independently demonstrated by T075. Both devices receive403 MESSAGE_MUTED for new keys, with only mutedUntil in error details matching the database UTC deadline. Exact snapshots of messages, chat sequence, point, membership/read cursor, actor, rate events, idempotency outcomes, notification events and related push jobs remain unchanged. Replays of the earlier successful key from either device return the original201 body with no new effects.

While muted, HTTP chat list, detail, history and search still return200 without mutation; HTTP read-cursor advances successfully and independent SQL confirms the persisted value. To isolate expiry from flood limits, the fixture sets message_muted_until to database clock_timestamp() and ages its existing rate event ten seconds. A subsequent request from the second device returns201/messageSeq3; SQL confirms three total messages and two actor rate events/success outcomes. This verifies an already reached server deadline, not equality at a frozen instant. After restoring a future mute, HTTP leave returns204 and SQL confirms left_at while the other participant preserves the chat. The fixture does not claim a report/send race or exhaustive scheduling coverage.

Lead executed 04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.MuteSendGuardIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in54s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.MuteSendGuardIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-04T00:52:57.940Z, suite seconds=2.92. Local T076 server PASS for the stated assertions; mobile, BG-1, external integration and overall M3 acceptance remain open.

Task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance.

## Group 8 accepted integration and accounting ledger — 04.11.2026

Working branch `feat/chat-mute-enforcement`, accepted clean target/origin base `d33297fa1833f6a36949dcfffea5ecb0257093bf`. Task commits: T075 `682bfb0abeee2a1425043ceabb1a67a2202b9568`, T076 `936086d7511381ac226cc6b94ff1bb2bdac1bdb0`. Independent task and whole-branch read-only reviews: APPROVE, no findings. Lead confirmed fast-forward integration and non-force target push; local `feat/back-part-3` and `origin/feat/back-part-3` both resolved to `936086d7511381ac226cc6b94ff1bb2bdac1bdb0`, clean at the group gate. Main before/after remained `2c1de31de2a5481e90e3c84e93a8abdb38f16169`.

Actually updated external accounting paths:

- `spec/plans/tasks/M3/T4/B04-mute-threshold.md`: T075 local server ГОТОВО, checked criteria, lifetime threshold/one-time deadline evidence and task commit.
- `spec/plans/tasks/M3/T4/B05-mute-guard.md`: T076 local server ГОТОВО, checked criteria, two-session send guard/expiry/read/leave evidence and task commit.
- `spec/plans/tasks/M3/T4/README.md`: B04–B05 checked with date and task commits.
- `spec/plans/roadmap/execution-checklist.md`: T075–T076 checked; M3.T4 backend 5/5 locally complete, C open; freshly counted 74/455 checked tasks, unrelated statuses preserved with optimistic reread.
- `spec/plans/tasks/M3/M3-T4.md`: backend B01–B05 locally complete, C and whole-parent acceptance open.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: matching M3.T4 backend 5/5, partial parent summary.

Environment remains the owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015, with real JdbcSessionRepository sessions. The cases exercise one concurrent threshold ordering and an already reached database deadline, not every schedule or a real seven-day wait. No policy for a manually prepopulated longer deadline with a null trigger was invented. All six accounting files remain outside backend Git and their physical contents are not in target/origin history. This ledger records exact updated paths and topology limitation without moving or duplicating plans. Mobile, BG-1, overall parent/M3 and external integrations remain open.

## T077 — M3.T5.B01 approved content policy version — 04.11.2026

Group 9 branch `feat/chat-content-policy`, accepted clean target/origin base `b41e5ee3e2155d79a9bc9849e91066b325ccf403`. Existing MessageContentPolicy.VERSION `chat-content-v1.0` and separate Phone/Link/Profanity detectors retained unchanged. The read-only approved source is `spec/for dev/artifacts/chat-message-content-policy.md`, revision 1.0 dated 01.11.2026; its inspected raw-file SHA256 is `85e1bd739f5dda5cea14048d527b6f756a93be4c2d84d59c14198218997179c0`. The existing approve-message-content-filter backlog records author approval completed on 02.11.2026; that decision was not reopened or marked blocked.

Self-contained backend test resource `src/test/resources/chats/chat-content-v1.0.json` pins the source identity, approval date, policy/normalization/exception-set versions, minimum approved section-8 corpus and selected explicit normalization/false-positive boundaries. It is a test fixture and attribution manifest, not a copy of user plans or the complete external specification. The hash records the inspected source bytes; portable tests do not read or re-hash the external workspace source.

Evidence `com.example.chats.ContentPolicyVersionTest` has three unit tests: the implementation and fixture share the explicitly pinned approved version; repeated evaluation matches approved blocked/allowed, NFC/yo/format/lookalike/masking and deliberate exceptions; injecting a different profanity detector leaves phone and URL gates independently active. No dictionary, normalization rule or exception was changed. Phone/URL persistence and HTTP rejection evidence remain separate T078/T079 tasks.

Lead executed on 04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat test --tests com.example.chats.ContentPolicyVersionTest --offline --no-daemon --max-workers=1`, exit 0, BUILD SUCCESSFUL in 1m1s.

Inspected `build/test-results/test/TEST-com.example.chats.ContentPolicyVersionTest.xml`: tests=3, skipped=0, failures=0, errors=0, timestamp=2026-11-04T01:08:35.725Z, suite seconds=0.122. Local T077 policy unit PASS for these assertions. No database was required or claimed by this pure policy test; the separate disposable environment remains V001–V015. Mobile, BG-1 and overall M3 remain open.

Task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance.

## T078 — M3.T5.B02 approved phone filter — 04.11.2026

Preceding accepted task commit `9b06fa2` (T077), branch `feat/chat-content-policy`. Existing production phone detector retained unchanged under approved `chat-content-v1.0`; the generic external category remains `phone_or_url_or_profanity` exactly as specified by the approved v1.0 policy. The owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat` remains V001–V015; actual JdbcSessionRepository validates persisted sessions.

Evidence `com.example.api.PhoneFilterIntegrationTest` executes fourteen blocked synthetic phone forms twice each through HTTP: approved 10–15 digit international plus forms, domestic 7/8/9 prefixes, parentheses/dots/hyphens/spaces and NBSP, and 7–9 digit candidates with approved immediate contact markers. Every refusal is422 MESSAGE_REJECTED_PATTERN with only the safe generic category in details. Message/rate/outcome/notification counts, chat sequence and sender read cursor remain unchanged. Responses contain no rejected full text or match/pattern fields. A temporary root Logback ListAppender captures formatted messages and throwable text during these refusals and verifies absence of the complete rejected synthetic strings, then detaches/stops in finally. This is evidence for the captured application logging scope, not a claim about every external log sink or every possible fragment.

Fourteen allowed synthetic date/time/house/amount/coordinate/order-number and deliberately unsupported disguise/boundary forms return201 with their exact text persisted in sequence order. The first accepted edit reuses a previously rejected key, proving the refusal did not save a successful outcome. Every accepted key replay returns the same DTO without additional counts/sequence/read changes. Existing successful rate events are explicitly aged ten seconds between allowed requests to isolate content rules from the separate flood gate; this fixture manipulation is not flood-boundary acceptance.

Lead executed 04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.PhoneFilterIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in54s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.PhoneFilterIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-04T01:15:16.365Z, suite seconds=3.934. Local T078 server PASS for these assertions. No dictionary/source policy was changed. Mobile, BG-1 and overall M3 remain open.

Task review: APPROVE, no findings, 04.11.2026. External accounting pending group acceptance.

## T079 — M3.T5.B03 approved URL and link filter — 04.11.2026

Preceding accepted task commit `61cac87` (T078), branch `feat/chat-content-policy`. The existing link detector is corrected to match approved `chat-content-v1.0` full-IPv6 and explicit scheme/www host rules; no suffix, dictionary, policy or policy version was added or changed. The same owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat` remains V001–V015, using actual JdbcSessionRepository authentication.

Evidence `com.example.api.UrlFilterIntegrationTest` initially exercised 46 blocked synthetic forms twice each through real HTTP: each of the 28 approved suffixes, HTTP/HTTPS/FTP/www forms with valid hosts, Unicode/IDN and NFC decomposed host, email, bare domains/paths, IPv4/IPv6 (including loopback IPv6), and approved limited dot/scheme spacing. Every refusal is422 MESSAGE_REJECTED_PATTERN with only generic phone_or_url_or_profanity details. No complete rejected text, match or pattern fields appear in responses. A temporary root Logback ListAppender checks formatted messages and throwable text for the complete rejected synthetic strings, with detach/stop in finally; this is the captured application scope, not proof about all infrastructure sinks or every possible fragment.

Message/rate/outcome/notification counts, chat sequence and sender cursor remain unchanged for refusals. The baseline twenty allowed synthetic version/dotted-word, incomplete scheme/host, unknown-suffix, explicitly split-label, nickname, written-domain and invalid/incomplete IP cases return201 and persist exact text in order. A previously rejected key accepts edited allowed text; successful replays preserve DTO and database counts/sequence/cursor. Prior rate events are explicitly aged301 seconds between accepted cases to isolate both rolling flood windows; this manipulation is not flood-boundary evidence.

The production detector implementation was inspected as local regex/normalization/IP-shape code without external-service calls. The test does not fetch the synthetic links or claim a runtime monitor of every internet connection; local-only content analysis is established by code inspection and the exercised local gate, not by an invented network-observer result.

Lead executed 04.11.2026 with JDK17/Gradle9.5.1: `./gradlew.bat integrationTest --tests com.example.api.UrlFilterIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in58s.

Inspected `build/test-results/integrationTest/TEST-com.example.api.UrlFilterIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-04T01:21:06.481Z, suite seconds=6.177. Local T079 server PASS for these assertions. Mobile, BG-1 and overall M3 remain open.


Task-review finding `T079-IPV6-EDGE` (P2), REQUEST CHANGES: validIpv6 accepted malformed `1::2:` and `:1::2` by filtering empty groups, producing content422 for ordinary malformed text. The pre-fix HTTP regression run failed in1m11s with expected201/actual422 at the allowed-case loop. XML recorded the loop assertion, not a payload; the first added malformed case is identified by deterministic loop order after the unchanged twenty-case baseline. The minimal implementation correction rejects a leading single colon unless the string starts `::`, and a trailing single colon unless it ends `::`. This implements the already approved full-valid-IPv6 boundary without changing policy version. Additional positive `::`, `1::` and IPv4-mapped `::ffff:192.0.2.1` remain blocked; malformed `:::` remains allowed. Final corpus:49 blocked/23 allowed. This is a task-review finding, distinct from future final-block audit IDs and counts.

Lead reran `./gradlew.bat integrationTest --tests com.example.api.UrlFilterIntegrationTest --offline --no-daemon --max-workers=1`: fixed final PASS in1m1s, exit0. Affected policy regressions: `./gradlew.bat test --tests com.example.chats.MessageContentPolicyTest --tests com.example.chats.ContentPolicyVersionTest --offline --no-daemon --max-workers=1`, PASS37s, exit0. The original baseline PASS58s was not evidence for the later-added malformed-IPv6 boundaries.

Final URL XML: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-04T01:27:56.378Z, suite seconds=6.645. Inspected unit XML confirms MessageContentPolicyTest4/0/0/0 and ContentPolicyVersionTest3/0/0/0. The IPv6 correction subsequently received targeted task re-review APPROVE on 04.11.2026.

Group-9 independent branch-review finding `T079-SCHEME-HOST` (P2), REQUEST CHANGES: the previous suffix/IP-only path allowed `https://fixture.unknownsuffix`, `ftp://fixture.unknownsuffix` and `www.fixture.unknownsuffix`. Approved source section6 requires an explicit HTTP/HTTPS/FTP or www prefix followed by a valid host to be blocked; its fixed suffix allowlist applies to bare domains. The lead confirmed this exact implementation defect without changing the approved policy. After adding the three HTTP regressions only, the pre-fix required run failed in53s, expected422/actual201. Inspected failure XML recorded tests=1/skipped=0/failures=1/errors=0, timestamp=2026-11-04T01:38:43.559Z, suite seconds=4.019, at the blocked-case loop (UrlFilterIntegrationTest.kt:104). It did not print a payload or index; attribution to the first newly added HTTPS case follows deterministic loop order after the unchanged preceding cases, rather than a payload diagnostic.

The minimal correction adds a separate local explicit-prefix/valid-host path for only HTTP, HTTPS, FTP and www, allowing the approved bounded scheme/dot spaces. It validates at least two nonempty Unicode alphanumeric/internal-hyphen domain labels, preserves within-label spaces rather than deleting them, and retains valid full-IP detection and IPv4 octet checks. Bare-domain suffixes, email semantics, approved source, dictionary and policy version remain unchanged. Additional cases cover scheme masks, spaced www, a Unicode unknown-suffix host and internal double hyphens; allowed negatives cover invalid edge hyphens, empty/underscore labels, incomplete hosts, an unsupported scheme, a split label and invalid IPv4. The final corpus is57 blocked forms (each refused twice) and33 allowed forms, retaining the existing safe errors/captured-log assertions, refusal database invariants, edited-key acceptance and successful replay checks. This finding originated in group review, separate from the previously fixed task-review IPv6 finding and from future final-block audit IDs/counts.

Lead executed the same required command `./gradlew.bat integrationTest --tests com.example.api.UrlFilterIntegrationTest --offline --no-daemon --max-workers=1`: fixed PASS in1m4s, exit0. Inspected URL XML: tests=1, skipped=0, failures=0, errors=0, timestamp=2026-11-04T01:43:29.792Z, suite seconds=7.685. Affected unit command `./gradlew.bat test --tests com.example.chats.MessageContentPolicyTest --tests com.example.chats.ContentPolicyVersionTest --offline --no-daemon --max-workers=1` passed in35s, exit0. Inspected MessageContentPolicyTest XML: tests=4/skipped=0/failures=0/errors=0, timestamp=2026-11-04T01:45:16.631Z, suite seconds=0.011; ContentPolicyVersionTest XML: tests=3/skipped=0/failures=0/errors=0, timestamp=2026-11-04T01:45:16.526Z, suite seconds=0.103. These runs establish the exercised local assertions; targeted code re-review and renewed independent group review remain pending.

Group-9 independent review also confirmed `T079-HOST-LABEL` (P2): the bare-host label pattern accepted single internal hyphens but missed valid double internal hyphens. Approved section6 permits internal hyphens without a count restriction. Tests-only reproduction for `fixture--name.com` and its known-suffix email form failed in57s, expected422/actual201, with XML identifying blocked fixture index41 (the first new bare-domain case), tests=1/skipped=0/failures=1/errors=0, timestamp=2026-11-04T01:48:43.024Z, suite seconds=4.486. The minimal correction reuses the already validated hostLabel pattern for bare domains, retaining edge-hyphen boundaries and the fixed suffix set. Added single/double-hyphen positives and a bare unknown-suffix negative produced60 blocked/34 allowed HTTP cases, PASS53s; the affected seven existing policy units passed in40s. This applies the existing known-suffix rule to email text and does not extend email semantics.

The lead then added focused LinkBoundaryTest coverage for legal inputs at or below4096 code points, with an injected false profanity detector to isolate the link gate. This reproduced `T079-LINK-STACK` (P2, independently confirmed group-review finding): a long many-label input caused StackOverflowError in recursive Java Pattern Loop/GroupTail processing. The pre-fix unit run failed in45s; inspected XML recorded3 tests,1 failure,0 skipped,0 errors, timestamp=2026-11-04T01:57:10.315Z, suite seconds=0.106. The approved minimal fix makes only the outer repeated dot/label groups possessive (bare-host ++, explicit-host *+), preserving the inner hostLabel backtracking needed to validate its final alphanumeric character. The regression verifies a4096-letter ordinary label, many labels with approved/unknown bare suffixes, long HTTPS/www unknown-suffix hosts, a long explicit valid host and an allowed split label. No tight timing benchmark, full HTTP maximum-input acceptance or unrelated profanity-boundary acceptance is claimed.

Final affected-unit command on04.11.2026: `./gradlew.bat test --tests com.example.chats.LinkBoundaryTest --tests com.example.chats.MessageContentPolicyTest --tests com.example.chats.ContentPolicyVersionTest --offline --no-daemon --max-workers=1`, PASS49s, exit0. Inspected final XML: LinkBoundaryTest3/0/0/0, timestamp=2026-11-04T02:00:29.033Z, suite seconds=0.091; MessageContentPolicyTest4/0/0/0, timestamp=2026-11-04T02:00:29.193Z, suite seconds=0.010; ContentPolicyVersionTest3/0/0/0, timestamp=2026-11-04T02:00:29.129Z, suite seconds=0.062 (counts are tests/skipped/failures/errors). No exception occurred in these assertions. Final required HTTP command `./gradlew.bat integrationTest --tests com.example.api.UrlFilterIntegrationTest --offline --no-daemon --max-workers=1` passed in56s, exit0; inspected final URL XML tests=1/skipped=0/failures=0/errors=0, timestamp=2026-11-04T02:01:46.789Z, suite seconds=7.424, exercising60 blocked forms twice each and34 allowed forms.

The previously closed task-review T079-IPV6-EDGE and group-review T079-SCHEME-HOST, T079-HOST-LABEL, T079-LINK-STACK retain their separate provenance and reproduction records; none is a final-block audit ID or counted as that later audit. The approved source, suffix set, policy version chat-content-v1.0 and email semantics remain unchanged. Final targeted task re-review and renewed independent group review APPROVE on 04.11.2026; all four findings are closed in the integrated corrections.

Task review: APPROVE, 04.11.2026. External accounting pending group acceptance.

## Group 9 accepted integration and accounting ledger — 04.11.2026

Working branch `feat/chat-content-policy`, accepted clean target/origin base `b41e5ee3e2155d79a9bc9849e91066b325ccf403`. Task commits: T077 `9b06fa24742d8fd6834aec9366b577acdffde71f`, T078 `61cac87723c119d4b60a63f02c0a35d42d9005ee`, T079 `a897af4818f470e5cdf0c74e74546fb5d91f5168`. Independent task and renewed whole-branch reviews: APPROVE, no remaining confirmed findings. Lead confirmed fast-forward integration, successful non-force push and ls-remote verification; local `feat/back-part-3` and `origin/feat/back-part-3` resolved to `a897af4818f470e5cdf0c74e74546fb5d91f5168`, clean at the gate. Main before/after remained `2c1de31de2a5481e90e3c84e93a8abdb38f16169`.

Four internal T079 findings are closed by the integrated corrections and independent re-review: task-review `T079-IPV6-EDGE`; group-review `T079-SCHEME-HOST`, `T079-HOST-LABEL`, `T079-LINK-STACK`. Their separate reproductions/fixes/checks remain in the execution record above. These four IDs belong to task/group review, not to the later full-block audit and its finding counts. The approved source, dictionary, suffix set, email semantics and `chat-content-v1.0` version remain unchanged.

Actually updated external accounting paths:

- `spec/plans/tasks/M3/T5/B01-policy-version.md`: T077 local policy ГОТОВО, checked criteria, unit evidence/source version and task commit; no DB claim for pure units.
- `spec/plans/tasks/M3/T5/B02-phone-filter.md`: T078 local server ГОТОВО, checked criteria, HTTP/persistence/captured-log evidence and task commit.
- `spec/plans/tasks/M3/T5/B03-url-filter.md`: T079 local server ГОТОВО, checked criteria, final URL/edge regressions and task commit.
- `spec/plans/tasks/M3/T5/README.md`: B01–B03 checked with date and task commits.
- `spec/plans/roadmap/execution-checklist.md`: T077–T079 checked; M3.T5 partial backend 3/6 and client open; fresh checked total 77/455, unrelated accounting preserved with optimistic reread.
- `spec/plans/tasks/M3/M3-T5.md`: B01–B03 locally complete; B04–B06/C and whole-parent acceptance open.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: matching partial M3.T5 server summary.

HTTP evidence uses the owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`, V001–V015 and actual JdbcSessionRepository sessions. Captured logging and bounded policy regressions do not establish every external sink, every text fragment or all maximum-size HTTP behavior. All seven accounting files remain outside backend Git and their physical contents are not included in target/origin history. This versioned ledger records exact paths and topology limitation without moving or duplicating user plans. Mobile, BG-1, overall parents/M3 and external integration acceptance remain open.

## T080 — M3.T5.B04 approved profanity patterns — 04.11.2026

Group10 branch `feat/chat-content-rate-limits`, clean accepted target/origin base `bfece64dff43be30579bf8982b0a16a0220277d5`. The approved read-only policy remains revision1.0, chat-content-v1.0, source SHA256 `85e1bd739f5dda5cea14048d527b6f756a93be4c2d84d59c14198218997179c0`. No dictionary, version, stemming, ML, inferred derivatives or external classifier was added. Portable profanity-v1.0.json pins version/source identity and80 explicitly approved table forms (including the English phrase),15 normalization/mask cases and41 allowed exceptions/unconfirmed-pattern cases.

ProfanityPolicyTest checks deterministic exact forms, register/NFC/yo/format removal/local lookalikes, approved bounded masks and ordinary/ambiguous exceptions. Every single-word form also has letter- and digit-boundary negatives; an over-eight-mask whole token with an embedded approved-word prefix remains allowed rather than being truncated. The baseline three corpus units passed in1m27s, exit0 (XML timestamp2026-11-04T02:24:44.083Z, suite seconds0.113).

Focused ProfanityBoundaryTest directly exercises the approved detector on inputs at or below4096 code points: ordinary long word, long alternating masked token, long spaced single-letter runs with allowed and exact-approved-form outcomes. The pre-production boundary run failed in1m34s, exit1: XML3 tests/0 skipped/1 failure/0 errors, timestamp2026-11-04T02:22:23.232Z, suite seconds0.119, StackOverflowError in Java Pattern recursion on the masked-token case. Independent read-only review confirmed P2 `T080-PROFANITY-STACK`. Following the exact approved recommendation, only the outer maskedToken repetition changed from * to possessive *+; inner mask bound{1,2}, whole-token matching, total masks<=8, normalization and dictionary remain unchanged. The regex was not capped at eight groups, which could incorrectly split a long candidate and reject its dictionary prefix. This task-review finding is distinct from later final-block audit IDs/counts.

Lead executed `./gradlew.bat test --tests com.example.chats.ProfanityBoundaryTest --tests com.example.chats.ProfanityPolicyTest --tests com.example.chats.LinkBoundaryTest --tests com.example.chats.MessageContentPolicyTest --tests com.example.chats.ContentPolicyVersionTest --offline --no-daemon --max-workers=1`: fixed affected-unit PASS in1m54s, exit0. Inspected final XML (tests/skipped/failures/errors): ProfanityBoundaryTest3/0/0/0, timestamp2026-11-04T02:27:41.703Z, seconds0.043; ProfanityPolicyTest3/0/0/0, timestamp2026-11-04T02:27:41.748Z, seconds0.006; LinkBoundaryTest3/0/0/0, timestamp2026-11-04T02:27:41.675Z, seconds0.018; MessageContentPolicyTest4/0/0/0, timestamp2026-11-04T02:27:41.694Z, seconds0.008; ContentPolicyVersionTest3/0/0/0, timestamp2026-11-04T02:27:41.562Z, seconds0.109. All16 assertions/tests executed without exception; no timing benchmark or full maximum-input HTTP acceptance is claimed.

ProfanityFilterIntegrationTest uses real HTTP, actual JdbcSessionRepository authentication and the owned disposable PostgreSQL18.6/PostGIS3.6.4 roy_m3_chat, V001–V015. All95 blocked synthetic forms are refused twice with422 MESSAGE_REJECTED_PATTERN and only generic phone_or_url_or_profanity details. Message/rate/outcome/notification counts, chat sequence and sender cursor remain unchanged. Error responses omit complete rejected text and pattern/match fields. A temporary root Logback ListAppender checks formatted messages and throwable text for complete rejected synthetic strings, then detaches/stops in finally. This proves the captured application scope, not all infrastructure sinks or possible fragments. All41 allowed forms persist exact text in order; an edited allowed request reuses a rejected key; every successful replay preserves DTO/database counts/sequence/cursor. Rate events are explicitly aged301 seconds between allowed sends to isolate both separate flood windows, not to claim flood acceptance.

Lead executed `./gradlew.bat integrationTest --tests com.example.api.ProfanityFilterIntegrationTest --offline --no-daemon --max-workers=1`: required HTTP PASS in1m43s, exit0. Inspected XML tests=1/skipped=0/failures=0/errors=0, timestamp2026-11-04T02:29:42.280Z, suite seconds9.294. Local evidence covers these server assertions; mobile, BG-1, external integrations and overall M3 remain open.

Task review: APPROVE, 04.11.2026. External accounting pending group acceptance.
## T081 — M3.T5.B05

Working branch `feat/chat-content-rate-limits`, preceding accepted task T080 `93f8e775541c87249103660e8df721628b958ef7`. Added the last defaulted ChatSendService transaction-clock constructor dependency and a named two-second constant. Production defaults to the same PostgreSQL clock_timestamp query. Clock reads retain their positions: rate checking after actor lock and membership validation, message/rate timestamp after point/chat locks. No migration changes; schema V001–V015.

`ShortMessageFloodIntegrationTest` uses a controlled server transaction clock with real PostgreSQL transactions, persistent sessions, two authenticated devices/HTTP clients and two chats. At100ms,1.5s and2s-minus1microsecond new sends receive429 MESSAGE_FLOOD with ceil Retry-After2/1/1 and unchanged message/sequence/cursor/rate/outcome/notification/job snapshots. Exact2s and just-after2s sends succeed. An actual PostgreSQL actor-row lock is held until pg_blocking_pids confirms the pending send waits; clock advancement past the boundary before release demonstrates clock sampling after that lock. At one free actor-wide position two readiness/start-barrier requests across devices/chats yield exactly one201 and one429. Same-key replay in the full window preserves the original201 DTO and all snapshots. Content rejection at a free position does not consume it; a valid send at that same clock succeeds. The final ledger contains exactly six accepted sends with checked earliest/latest timestamps.

The controlled clock belongs only to this test setup. Default PostgreSQL-clock regressions were executed separately. These executed barriers do not prove every scheduler/database interleaving, and the HTTP two-device test does not assert a WebSocket business-send path (send remains REST).

Actual Lead commands on04.11.2026, JDK17/Gradle9.5.1, owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`:

- `./gradlew.bat integrationTest --tests com.example.api.ShortMessageFloodIntegrationTest --offline --no-daemon --max-workers=1`: PASS1m25s. Inspected XML tests1/skipped0/failures0/errors0, timestamp `2026-11-04T02:44:27.440Z`, suite3.084s.
- `./gradlew.bat integrationTest --tests com.example.chats.SendTransactionIntegrationTest --tests com.example.api.SendReplayIntegrationTest --tests com.example.api.MutedSendIntegrationTest --offline --no-daemon --max-workers=1`: PASS1m18s for the two actual matching classes only. SendReplay XML1/0/0/0, timestamp `2026-11-04T02:46:02.724Z`,2.401s; SendTransaction XML1/0/0/0, timestamp `2026-11-04T02:46:05.127Z`,0.485s. The MutedSend filter matched no class and is not mute-guard PASS evidence.
- `./gradlew.bat integrationTest --tests com.example.api.MuteSendGuardIntegrationTest --offline --no-daemon --max-workers=1`: actual guard regression PASS47s, exit0. Current XML inspected tests=1, skipped=0, failures=0, errors=0, timestamp `2026-11-04T02:47:15.475Z`, suite2.789s.

Local T081 server PASS for the stated cases. Independent task review APPROVE on04.11.2026; external accounting pending group acceptance. No mobile, BG-1 or overall M3 acceptance is claimed.

## T082 — M3.T5.B06

Working branch `feat/chat-content-rate-limits`, preceding accepted T081 `87d8e29861fcbd6cd658798625128e9f4a1a40b5`. Added named 300-second and 30-event constants without changing production query semantics: successful events satisfy accepted_at > now minus300seconds, so the exact oldest edge is excluded. Transaction-clock injection remains the T081 port; production defaults to PostgreSQL time. Schema V001–V015 is unchanged.

`LongMessageFloodIntegrationTest` uses real HTTP, JDBC-backed sessions, two devices/clients, two chats and real PostgreSQL actor locks. All accepted rate events come from successful HTTP sends, not bulk ledger insertion. After29 sends spaced by two controlled-clock seconds, a unique fixture-key outcome trigger forces one send failure; unchanged message/chat/point/membership/rate/outcome/notification/job snapshots prove that rollback leaves the30th position free. Trigger/function cleanup runs in finally. Two readiness/shared-start-barrier requests then compete for that position: exactly one201 and one429, with Retry-After242=max(short2,long242). Same-key replay in the full window returns the original201 DTO without new writes. New31st requests are refused, including Retry-After240 at base+60seconds.

A fresh ChatSendService instance reads the same persisted full ledger and returns Flood240 without mutation. This proves service-instance independence, not process-restart/network acceptance. Content rejection before the accepted sequence leaves snapshots unchanged and consumes no frequency position.

Controlled UTC base `2030-01-01T00:00:00Z`: at299.999999seconds refusal has Retry-After1; at exactly300seconds the oldest event is excluded and a send succeeds. At300.000001seconds refusal rounds to2; at301.2 it rounds to1; at302 a second oldest event ages out and a send succeeds; at304.000001 another succeeds. Active-window counts remain30, demonstrating rolling releases rather than a fixed bucket reset. Final persisted counts are33 messages/rate events/outcomes after those additional successful sends. No five-minute wall-clock sleep or fake successful ledger rows are used. Executed barrier cases do not claim all database/scheduler interleavings.

Lead executed04.11.2026 with JDK17/Gradle9.5.1 in the owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat`: `./gradlew.bat integrationTest --tests com.example.api.LongMessageFloodIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in59s. Current XML inspected `build/test-results/integrationTest/TEST-com.example.api.LongMessageFloodIntegrationTest.xml`: tests=1, skipped=0, failures=0, errors=0, timestamp `2026-11-04T02:54:34.114Z`, suite3.711s.

Local T082 server PASS for these assertions. Task review: APPROVE, 04.11.2026. External accounting pending group acceptance. Mobile, BG-1 and overall M3 remain open.

## Group 10 accepted integration and accounting ledger — 04.11.2026

Working branch `feat/chat-content-rate-limits`, accepted clean target/origin base `bfece64dff43be30579bf8982b0a16a0220277d5`. Task commits: T080 `93f8e775541c87249103660e8df721628b958ef7`, T081 `87d8e29861fcbd6cd658798625128e9f4a1a40b5`, T082 `e871c810b659ef8f80ebd5663578bd33e96c804a`. Independent task and whole-group read-only reviews: APPROVE, no remaining confirmed findings. Lead confirmed fast-forward integration, successful non-force push, equality of local `feat/back-part-3` and `origin/feat/back-part-3` at `e871c810b659ef8f80ebd5663578bd33e96c804a`, and clean working tree. Main before/after remained `2c1de31de2a5481e90e3c84e93a8abdb38f16169`.

Internal task-review P2 `T080-PROFANITY-STACK` is closed by the integrated possessive outer-repeat correction, reproduced boundary regression, affected units/HTTP acceptance and independent re-review. This is distinct from future final-block audit IDs/counts. No dictionary, policy version, normalization, stemming or ML change was made.

Actually updated external accounting paths:

- `spec/plans/tasks/M3/T5/B04-profanity-filter.md`: T080 local server ГОТОВО, checked criteria, approved corpus/boundary/HTTP evidence and task commit.
- `spec/plans/tasks/M3/T5/B05-short-flood.md`: T081 local server ГОТОВО, checked criteria, exact short-window/actor-lock/concurrent-slot/default-clock evidence and task commit.
- `spec/plans/tasks/M3/T5/B06-long-flood.md`: T082 local server ГОТОВО, checked criteria, actual successful ledger/rollback/rolling boundary evidence and task commit.
- `spec/plans/tasks/M3/T5/README.md`: B04–B06 checked with date and commits.
- `spec/plans/roadmap/execution-checklist.md`: T080–T082 checked; M3.T5 backend 6/6 locally complete, C open; fresh total 80/455 checked tasks, unrelated statuses preserved using optimistic reread.
- `spec/plans/tasks/M3/M3-T5.md`: backend B01–B06 locally complete, C and whole-parent acceptance open.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: matching M3.T5 backend 6/6, partial whole-parent summary.

The owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat` remains V001–V015. Controlled transaction clocks test exact flood boundaries with real HTTP, persisted sessions and PostgreSQL locks; production defaults to PostgreSQL clock time, with separate actual regression commands recorded above. Service-instance independence is not process-restart proof, the executed races are not every schedule, and the log-capture scope is not every infrastructure sink. All seven accounting files remain outside backend Git and their physical contents are not in target/origin history. This versioned ledger records exact updated paths and topology limitation without moving or duplicating plans. Mobile, BG-1, overall parent/M3 and external integrations remain open.

## T083 — M3.T6.B01 synthetic server acceptance fixture — 04.11.2026

Group11 branch `tests/chat-server-acceptance`, accepted target/origin base `7a2a7baeea424307d4173575724d6e15d0bae7f4`. New test-only ChatAcceptanceFixture and ChatAcceptanceFixtureIntegrationTest allocate two points/topics/chats, nineteen synthetic accounts including sixteen reporters, actual persisted sessions and distinct TEXT1/SYSTEM2 history. No production or applied migration changed. The versioned `docs/m3-server-fixture-passport.md` records the symbolic identity/membership map, completed HTTP/DB observations and cleanup scope without JWTs, credentials or source user data.

Actual HTTP history confirms member/peer access and repeated outsider/adjacent-chat403 without history echo. Sixteen distinct real report requests and their successful-key replays produce exactly sixteen reports; target mute remains absent through15 and equals trigger+604800seconds at16. Transactional own-ID cleanup is repeatable: owned users/points/chats/messages/reports each0 afterward; the simultaneously live disjoint sentinel retains19 users,2 points,4 messages,0 reports before its own cleanup. SQL fixture preparation is not claimed as REST join/send acceptance.

Lead executed `./gradlew.bat integrationTest --tests com.example.acceptance.ChatAcceptanceFixtureIntegrationTest --offline --no-daemon --max-workers=1` on04.11.2026, exit0, BUILD SUCCESSFUL in1m2s. JDK17/Gradle9.5.1, owned disposable PostgreSQL18.6/PostGIS3.6.4 roy_m3_chat, V001–V015. Inspected XML tests1/skipped0/failures0/errors0, timestamp2026-11-04T03:15:33.862Z, suite3.278s. Local T083 fixture PASS for these assertions. Build XML can be replaced by subsequent Gradle runs; the passport preserves this observed summary. No mobile, BG-1, overall M3 or subsequent REST/WS/race acceptance is claimed.

Task review: APPROVE, 04.11.2026. External accounting pending group acceptance.

## T084 — M3.T6.B02 REST chain protocol — 04.11.2026

Branch tests/chat-server-acceptance, accepted T083 commit77c55d72561d7c7af2e34ca291c6e77ca0b27281. New ChatRestAcceptanceIntegrationTest reuses the isolated fixture and actual JdbcSessionRepository/HTTP routes for join/history/send/read/search/unread/report/mute/leave. Minimal fixture cleanup expands reporter IDs to all owned accounts because the joining outsider now submits a report; no external namespace or production code changed. Completed protocol docs/m3-server-rest-cases.md links the safe38-step actual trace docs/evidence/m3-rest-chain.json with concrete synthetic UUIDs, request IDs/keys, bodies/statuses and DB snapshot column names; JWTs/phones/credentials/private source text are excluded.

Observed join200 creates systemseq3, repeat preserves it; blank send422 changes no snapshot; send201seq4 is checked by exact DB message ID/text/seq and following delta/search GET. Replay preserves canonical JSON and counts. Peer unread4 becomes0 after read-cursor4, confirmed by SQL and GET. Sixteen real reports (first replayed without duplication) lead to peer send403 MESSAGE_MUTED with unchanged snapshots. Outsider leave204 appends seq5, repeat is unchanged, later history/send403 and own chats empty. Muted peer's last leave on the second fixture point removes that point/chat, checked by SQL and following403. Both test fixtures clean up scoped data successfully; sequential functional observations are not concurrency or WS evidence.

Lead executed `./gradlew.bat integrationTest --tests com.example.acceptance.ChatRestAcceptanceIntegrationTest --tests com.example.acceptance.ChatAcceptanceFixtureIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in1m3s. JDK17/Gradle9.5.1, owned disposable PostgreSQL18.6/PostGIS3.6.4 roy_m3_chat, V001–V015. Inspected XML ChatRestAcceptanceIntegrationTest1/0/0/0 timestamp2026-11-04T03:25:15.967Z suite2.600s; ChatAcceptanceFixtureIntegrationTest1/0/0/0 timestamp2026-11-04T03:25:12.551Z suite3.414s. Local T084 REST-chain PASS. No mobile/BG-1/overall M3, deployed transport, load or process-restart acceptance is claimed.

Task review: APPROVE, 04.11.2026. External accounting pending group acceptance.

### Group11 correction evidence — G11-FIXTURE-SYSTEM-SCHEMA — 04.11.2026

Independent whole-group review REQUEST CHANGES, P2: T083's original seed payload contained userId only and did not satisfy M0.T5 SystemMessage. Original T083/T084 PASS results recorded above remain valid only for their executed assertions; they did not check canonical SYSTEM shape. This confirmed test-fixture/evidence finding is separate from future final-block audit IDs/counts and is not a production writer defect.

Minimal T083 correction: seed lastseq1, lock owned point, append fixed-ID seq2 with existing JdbcSystemMessageWriter; contract assertion verifies all nine payload keys, kind, expected actor/point/chat IDs, null optional fields and Message author/text null. Both fixture chats are checked against distinct expected IDs. T084 now asserts canonical seeded seq2 and actual join seq3, plus peer GET of leave seq5/member_left. No production or migration changed. The single versioned REST trace was regenerated to39 steps; fixture/REST passports explicitly retain the original evidence limitation.

Lead reran `./gradlew.bat integrationTest --tests com.example.acceptance.ChatRestAcceptanceIntegrationTest --tests com.example.acceptance.ChatAcceptanceFixtureIntegrationTest --offline --no-daemon --max-workers=1`: PASS1m3s, exit0. Inspected corrected XML fixture1/0/0/0 timestamp2026-11-04T03:35:56.127Z suite3.053s; REST1/0/0/0 timestamp2026-11-04T03:35:59.183Z suite2.968s. Environment remains JDK17/Gradle9.5.1, PostgreSQL18.6/PostGIS3.6.4 roy_m3_chat, V001–V015. Independent targeted schema re-review APPROVE on04.11.2026 closes G11-FIXTURE-SYSTEM-SCHEMA; no final-audit finding count is implied.

Task review: APPROVE, 04.11.2026. External accounting pending group acceptance.

## Group 11 accepted integration, final identities and accounting ledger — 04.11.2026

Working branch `tests/chat-server-acceptance`, accepted clean target/origin base `7a2a7baeea424307d4173575724d6e15d0bae7f4`. Definitive published task commits: T083 `e8abbe4b34f80badcb5c00fcbc5c1979c27a34f6`, T084 `51f5095262e45ce13b814f7fa2ff7d26f757d4c1`. Earlier references `77c55d7`, `ac8cb3b`, `f299b07` are historical unpublished snapshots, superseded by these final identities; earlier execution observations retain their expressly recorded scope. Lead confirmed independent whole-group APPROVE, successful fast-forward integration/non-force target push, local and origin `feat/back-part-3` equality at `51f5095262e45ce13b814f7fa2ff7d26f757d4c1`, and clean working tree. Main before/after remained `2c1de31de2a5481e90e3c84e93a8abdb38f16169`.

Internal P2 `G11-FIXTURE-SYSTEM-SCHEMA` is closed after canonical seed/production join/leave SYSTEM assertions, regenerated 39-step REST trace, actual combined fixture+REST PASS1m3s and independent targeted plus whole-group APPROVE. This is a fixture/evidence task/group finding, distinct from later full-block audit IDs/counts. The original narrower PASS observations do not retroactively claim canonical schema coverage.

Actually updated external accounting paths:

- `spec/plans/tasks/M3/T6/B01-chat-fixture.md`: T083 local server ГОТОВО, checked criteria, corrected canonical fixture/ACL/threshold/scoped cleanup evidence and final commit.
- `spec/plans/tasks/M3/T6/B02-chat-rest-cases.md`: T084 local server ГОТОВО, checked criteria, corrected REST/DB/SYSTEM/39-step trace evidence and final commit.
- `spec/plans/tasks/M3/T6/README.md`: B01–B02 checked with date and final task commits.
- `spec/plans/roadmap/execution-checklist.md`: T083–T084 checked; M3.T6 partial backend 2/4 and client open; fresh total 82/455 checked tasks, unrelated statuses preserved with optimistic reread.
- `spec/plans/tasks/M3/M3-T6.md`: B01–B02 complete locally, B03–B04/C and overall parent acceptance open.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: corresponding partial M3.T6 server summary.

The same owned disposable PostgreSQL18.6/PostGIS3.6.4 `roy_m3_chat` remains V001–V015 with actual JdbcSessionRepository sessions. Fixture/REST sequential acceptance is not WS, concurrent send/leave, deployed transport, load or process-restart acceptance. All six accounting files remain outside backend Git and their physical contents are not versioned by target/origin. This ledger records exact updated paths and topology limitation; user plans were not moved or duplicated. No stalled backlog files were created or retrospectively added. Mobile, BG-1, overall parent/M3 and external integrations remain open.

## T085 — M3.T6.B03

Branch `tests/chat-realtime-races` from accepted clean target/origin `e2e3ca1cfc4d30b01334def7bd6e403ccbbd46e8`. Completed protocol: [m3-server-websocket-cases.md](m3-server-websocket-cases.md); actual safe trace [m3-ws-isolation.json](evidence/m3-ws-isolation.json). New acceptance test reuses the canonical nineteen-account fixture, real JDBC sessions, native loopback Netty/JDK clients and production HTTP/WS paths. Two participants receive matching committed Message events, adjacent topic isolation passes correlated sentinel+250ms observations; abort/missed seq4 is recovered by REST delta matching ordered DB, reconnect receives seq5, concrete-session logout yields wire4401 while same-actor second session and peer receive seq6. Scoped fixture cleanup users/points/chats/messages was zero before trace write. No production or migration SQL changed.

Preparation history: initial FAIL1m8 before body (JUnit non-void inferred Path, fixed explicit Unit), then FAIL1m4 before fixture (DB connection refused while Docker daemon was down after tool restore). The same owned container `90957e...` / `codex.owner=m3-chat-20261104` was restarted with fresh tmpfs DB, loopback32795; actual runMigrations PASS40s applied/validated15 existing migrations empty→V015. These preparation failures are not acceptance PASS or API defects. Recorded PostgreSQL18.6/PostGIS3.6.4 environment and disposable guard remain the local server boundary.

Lead actually ran `./gradlew.bat integrationTest --tests com.example.acceptance.ChatWebSocketAcceptanceIntegrationTest --offline --no-daemon --max-workers=1`, JDK17/Gradle9.5.1, final exit0 BUILD SUCCESSFUL48s. XML tests=1, skipped=0, failures=0, errors=0, timestamp `2026-11-04T08:34:06.065Z`, suite 13.214s. Local T085 server PASS for the enumerated observed assertions; no unbounded exactly-once, exhaustive network, heartbeat-repeat or five-minute policy claims. Final task review: APPROVE, 04.11.2026. External accounting pending group acceptance; mobile, BG-1 and overall M3 remain open.

## T086 — M3.T6.B04 concurrent send and leave acceptance — 04.11.2026

Branch tests/chat-realtime-races, preceding accepted T085 commit12115cbd2d9fc7a0c3282f6526c7dd2c3cda9368. New ChatSendLeaveRaceIntegrationTest has three explicit Unit-returning required tests with fresh synthetic namespaces, actual JdbcSessionRepository authentication, two HTTP clients and real PostgreSQL actor/point locks. No production/shared-fixture/migration code changed. Completed docs/m3-server-send-leave-cases.md links three versioned safe traces under docs/evidence/m3-send-leave-*.json, recording actual request IDs/keys/statuses/Message bodies, backend lock chain and numeric snapshots without JWTs, credentials or phone data.

An external point-row holder blocks the first command; pg_blocking_pids identifies that backend, then the second command blocked by its actor lock. Both HTTP jobs remain incomplete before blocker commit. Send-first produces201seq3 then leave204/member_leftseq4, exactly4 unique sequences and one rate/outcome. Leave-first produces204/member_leftseq3 then send403 NOT_MEMBER, exactly3 unique sequences and zero rate/outcomes. Replays/repeated leave preserve snapshots; left actor history403 and peer history200 verify persisted state/canonical SYSTEM shape. Last-member leave-first deletes point/chat/messages/memberships before send403, leaves zero Message/rate/success outcome, repeated send403/leave404, both history403, while the adjacent fixture point/chat retains its two original rows. These observed overlapping orders are concurrency acceptance, not sequential execution labelled as a race.

Lead executed `./gradlew.bat integrationTest --tests com.example.acceptance.ChatSendLeaveRaceIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in1m13s. Inspected XML tests3/skipped0/failures0/errors0, timestamp2026-11-04T08:43:01.307Z, suite9.103s. JDK17/Gradle9.5.1, owned disposable PostgreSQL18.6/PostGIS3.6.4 roy_m3_chat loopback32795, V001–V015. Actual three trace artifacts inspected before copying from build/m3-evidence. Local T086 PASS; no implementation defect observed or speculative fix made.

No WS transport exists in this test application, so runtime non-publication is not claimed. Code inspection establishes Created-only chat.message.created publication; the exercised NotMember branch returns403. Separate T085 has its own bounded WS evidence. These deterministic lock schedules are not every scheduler/stress case, mobile, BG-1, deployed transport or overall M3 acceptance.

Task review: APPROVE, 04.11.2026. External accounting pending group acceptance.

## Group 12 accepted integration and accounting ledger — 04.11.2026

Working branch `tests/chat-realtime-races`, accepted clean target/origin base `e2e3ca1cfc4d30b01334def7bd6e403ccbbd46e8`. Task commits: T085 `12115cbd2d9fc7a0c3282f6526c7dd2c3cda9368`, T086 `67939ebb321c968f95423857a4d25805ec75f9d0`. Independent task and whole-group read-only reviews: APPROVE, no findings. Lead confirmed fast-forward integration, successful non-force push, local/origin `feat/back-part-3` equality at `67939ebb321c968f95423857a4d25805ec75f9d0` and clean working tree. Main before/after remained `2c1de31de2a5481e90e3c84e93a8abdb38f16169`.

T085's actual safe WS trace has22 rows (not23), with required PASS48s and XML1/0/0/0. It uses the restarted owned container and fresh restored tmpfs database, loopback32795, with all15 migrations applied/validated. T086 required PASS1m13s/XML3/0/0/0 covers three true overlapping PostgreSQL lock schedules. These are bounded WS observations and deterministic executed race schedules, not every fault/interleaving. T086 has no WS transport, so it does not claim runtime WS event absence. No production or applied migration change was made by this group.

Actually updated external accounting paths:

- `spec/plans/tasks/M3/T6/B03-chat-socket-cases.md`: T085 local server ГОТОВО, checked criteria, real WS/REST resync/specific-session isolation evidence and task commit.
- `spec/plans/tasks/M3/T6/B04-chat-race-cases.md`: T086 local server ГОТОВО, checked criteria, actual lock-chain/three-race/DB evidence and task commit.
- `spec/plans/tasks/M3/T6/README.md`: B03–B04 checked with date and commits.
- `spec/plans/roadmap/execution-checklist.md`: T085–T086 checked, M3.T6 backend4/4 locally complete, C open; fresh total84/455 checked tasks, unrelated statuses preserved with optimistic reread.
- `spec/plans/tasks/M3/M3-T6.md`: backend B01–B04 locally complete, C/whole-parent/BG-1 open; full audit/fix stage pending.
- `spec/plans/roadmap/M3/M3-chat-moderation.md`: matching T6 backend4/4 and explicit B30/30 local server progress; full audit/fix stage, C/BG-1/overall M3 remain open.

Runtime: owned disposable PostgreSQL18.6/PostGIS3.6.4 roy_m3_chat loopback32795, V001–V015 and real JdbcSessionRepository sessions. All six accounting files remain outside backend Git; their physical contents are not versioned by target/origin. This ledger records the exact updates/topology boundary without moving or duplicating user plans. All30 backend task components have local grouped acceptance, but the separately required full detailed audit and subsequent audit-fix stage have not yet been completed. No final-audit file is created by this accounting step, and overall M3/mobile/BG-1/external integration acceptance is not claimed.

## Final selected server verification before full audit — 04.11.2026

After all twelve groups and their accounting ledgers were integrated/pushed, clean source `a5d4edc86f40d38405447cfde787ae266775d803` was verified on the same owned disposable container `90957e770adceb5c7979cc2edef8833b27da5ba60fc8f378892a34ef3e4c1a00`, ownership label codex.owner=m3-chat-20261104, loopback32795, database roy_m3_chat. Actual pre-run inspection confirmed running ownership, PostgreSQL18.6, PostGIS library3.6.4 and successful schema015. Existing V001–V015 had been reapplied/validated after the tmpfs restart; no applied migration was edited.

Lead discovered integration classes from the actual M3 Git diff and package declarations, required each test's disposable guard and either its roy_m3_chat scope or shared guarded fixture, and ran only those29 selected classes through the required integrationTest task. Actual Gradle PASS2m8s, exit0; exact observed XML class set matched the selected list:32 tests,0 skipped/failures/errors. Separately, five actual policy classes ran through test: ContentPolicyVersionTest, LinkBoundaryTest, MessageContentPolicyTest, ProfanityBoundaryTest and ProfanityPolicyTest. Actual PASS33s, exit0; XML16 tests,0 skipped/failures/errors. Total48 tests executed; no legacy integration aliases were invoked.

Safe machine-readable `docs/evidence/m3-final-verification.json` preserves the tested source commit, exact command arguments, runtime protection/version facts, all34 suite headers and48 test names/times, plus primary task-to-class-to-commit mapping for every T057–T086. It contains no raw test logs, credentials or request tokens. Its generation is evidence collection, not an additional test run. The dated task-specific REST/WS/race JSON snapshots remain historical observations from their recorded runs; repeating the same test code creates fresh random namespaces in build copies, so later build traces may differ by UUID. Their versioned SHA256 values and this boundary are recorded in the summary.

This is final selected local server verification, not the independent full audit, audit-fix closure, Android/iOS, BG-1, deployed/external integration, load or exhaustive schedule acceptance. Those remaining audit gates follow separately; no final audit result is claimed here.
## Full-audit finding correction and post-fix regression — 04.11.2026

The separately committed single audit file was published at `f0c1ecaff4f089fc80bc4efe4f19cbecbce358e0` before creating `fix/chat-search-validation`. The two fresh auditors found exactly one final-audit P2, AUD-LOW-001/T060: embedded NUL search reached PostgreSQL and became500. Only its regression was first added; actual required HistorySearchIntegrationTest RED1m11s/exit1 observed expected400 versus500, XML1/0/1/0. Minimal one-line route and application guards then produced GREEN1m14s/exit0/XML1/0/0/0 with the original safe400 INVALID_FIELD schema, unchanged3–256 codepoint bounds, no echo/persisted/read-cursor effects, and direct application rejection before SQL. Independent read-only schema_review point verdict: APPROVE.

Exactly one finding commit, subject `Reject NUL in chat history search`, contains this correction, regression and the same single audit record marked ИСПРАВЛЕНО after APPROVE. Its own final SHA is resolved later; no fabricated circular self-reference is embedded. No other production, schema or policy change was made. Current final-audit counts:1 found,1 fixed,0 open; independent whole-fix-branch audit_closure verdict is APPROVE after checking every finding acceptance criterion, full diff, all48 current XML results, source binding and accounting.

Lead then repeated the complete selected M3 regression on tested snapshot `a21376d48abadab9d3a7314a0b0fd732082b2fcd`: exact29 integration classes/32 tests PASS1m38s and5 unit classes/16 tests PASS33s, each exit0 and0 skipped/failures/errors. Current safe `docs/evidence/m3-final-verification.json` records the post-fix actual XML, command arguments,30 task mappings and stable executable-input Git objects. Only audit/evidence/ledger metadata is amended after this run; those executable objects bind the tested content across amendment of the unpublished finding commit. The original pre-audit summary is retained in Git75f14e3. No broader runtime acceptance is claimed.

Independent final read-only audit_closure approved the complete one-finding branch on a276e2120d66dbfed367a9b0dab7f5e2e27e73b0, with no remaining confirmed findings. Historical RED/GREEN raw XML was overwritten by later Gradle runs; the captured observations remain in the single audit record, while all48 current regression XML and eight executable-input objects were independently verified directly. Only evidence/approval metadata is amended afterward; no additional finding or code change is introduced. Definitive published fix SHA and final accounting follow integration.
