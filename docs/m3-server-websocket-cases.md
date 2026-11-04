# M3 server WebSocket isolation acceptance — T085 / M3.T6.B03

Executed 04.11.2026 (Europe/Moscow) from `tests/chat-realtime-races`, accepted clean target/origin base `e2e3ca1cfc4d30b01334def7bd6e403ccbbd46e8`. This is completed local server evidence; mobile, external integrations, BG-1 and overall M3 acceptance remain open. Final task review: APPROVE, 04.11.2026. External accounting: pending group acceptance.

## Reproduction and actual environment

- Test: `com.example.acceptance.ChatWebSocketAcceptanceIntegrationTest`.
- Actual command: `./gradlew.bat integrationTest --tests com.example.acceptance.ChatWebSocketAcceptanceIntegrationTest --offline --no-daemon --max-workers=1`.
- JDK17, Gradle9.5.1; final exit0, BUILD SUCCESSFUL in48s. XML: tests1, skipped0, failures0, errors0, timestamp `2026-11-04T08:34:06.065Z`, suite 13.214s (testcase 13.199s).
- Owned disposable container `roy-m3-chat-20261104`, ID beginning `90957e`, ownership label `codex.owner=m3-chat-20261104`; storage tmpfs. After Docker Desktop daemon recovery the same container was restarted with a fresh database and dynamic loopback port `127.0.0.1:32795`. Guarded alias `roy_m3_chat` and disposable confirmation were used; credentials omitted.
- Recorded PostgreSQL18.6/PostGIS3.6.4 image environment; `runMigrations --offline --no-daemon --max-workers=1` actually passed40s, validating/applying the 15 existing migrations from empty schema through V015. No SQL migration file changed for this task.
- Canonical `ChatAcceptanceFixture`: two points/topics/chats, 19 synthetic accounts and actual JDBC sessions, initial text/system history seq1/2. Extra same-actor session is scoped to the fixture user and removed by cleanup.
- Real loopback Netty, native JDK WebSocket/HTTP clients, production routing/ChatSendService/ChatReadService/RealtimeHub/SessionAccessService and JdbcSessionRepository. No fake WS event is used for Message publication; synthetic user sentinels mark observation barriers.

## Observed cases

1. Two participants subscribed to chat `569b7a5a-6873-4b1f-8742-8980b8bb2c9a`; a separate socket subscribed to the adjacent fixture topic. Actual POST201 produced seq3, Message `25480bab-517f-490e-ba02-20f023553535`, requestId `ce9e7974-92ce-4185-a159-28b92a97b660`. Both participants received full Message equal to REST201 with the same eventId and chat scope. The adjacent socket observed no chat Message through the correlated user sentinel and subsequent250ms window.
2. One participant deliberately aborted its transport. Its persisted session still authenticated, so transport loss was not classified as logout/auth revocation. A peer POST201 committed seq4 while that participant was disconnected. GET `messages?afterSeq=3`, requestId `dc8f2064-d02c-40f5-a336-546ac6d0acbf`, returned Message `4d828f24-7c41-4688-a71a-64b852672b0f` at seq4. Ordered independent JDBC rows for seq>3 matched this delta exactly.
3. The original JWT reconnected and resubscribed. A new real POST201 at seq5 reached both peer and reconnected participant. The adjacent-topic socket again passed the bounded isolation barrier.
4. A second persisted session of the same actor connected. Actual application logout revoked concrete original session `0f875979-97ea-4cbc-bc18-5e4ddec1ffbe`; its wire received `session.revoked` and close4401. Second session `cd050d63-137c-4654-93a1-7f9c52d9896f` remained authenticated, and it and the peer received the later seq6 Message `05f042f1-bc3f-43e7-b7ee-dc81577cb13b`. The revoked socket received no post-close Message during250ms observation. The adjacent socket again reached its user sentinel without a chat Message.
5. Every message check compared full REST and WS DTOs. Trace database snapshots are `[lastMessageSeq, messageCount]` for the main chat: `[3,3]`, `[4,4]`, `[5,5]`, `[6,6]`. Fixture-scoped users/points/chats/messages were all zero after cleanup; only then was the safe trace written.

## Evidence and limits

Actual safe trace: [m3-ws-isolation.json](evidence/m3-ws-isolation.json), copied byte-for-byte from `build/m3-evidence/ws-isolation.json` after the successful assertions and cleanup. It retains synthetic request/session/event/message UUIDs, status, scope, seq, DB snapshots and correlation barriers. It contains no bearer JWT, password, refresh token or private message text.

Absence claims are bounded: ordered user sentinel plus250ms, not unbounded delivery or exactly-once guarantees. Disconnect is deliberate native client abort, not exhaustive network fault injection. Reconnect recovery uses authoritative REST/DB; no server WS replay promise is inferred. This task does not repeat heartbeat30s or all negative handshake cases already evidenced by T065–T067 and makes no five-minute policy-window claim.

## Failed preparation attempts

The first attempt failed1m8 at JUnit initialization before the body: expression-body `runBlocking` inferred Path from `Files.writeString`; explicit `runBlocking<Unit>` fixed the fixture method return. The second attempt failed1m4 with database connection refused before fixture execution while Docker Desktop daemon was down after tool restoration. Neither attempt is PASS or an API behavior failure. The final acceptance above ran only after the restarted owned disposable DB and successful real migrations.
