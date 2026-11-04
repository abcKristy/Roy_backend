# M3 REST chat chain: executed server cases

T084 / M3.T6.B02, executed04.11.2026 on `tests/chat-server-acceptance`, accepted T083 commit `77c55d72561d7c7af2e34ca291c6e77ca0b27281` plus the T084 test revision. The eventual task commit is recorded by the execution ledger after review. Environment: JDK17/Gradle9.5.1, owned disposable PostgreSQL18.6/PostGIS3.6.4 roy_m3_chat, V001–V015. Real HTTP routes and JdbcSessionRepository were used; no production or migration changes.

## Concrete trace

[Safe actual request/response trace](evidence/m3-rest-chain.json) contains39 executed requests, concrete synthetic actor/chat/message/request UUIDs, idempotency keys, expected/actual status, body fields and ordered DB snapshots. Headers containing credentials were excluded. No JWT, password, phone field or source private message appears. UUIDs are from this completed disposable run; subsequent executions allocate fresh IDs.

`databaseSnapshotColumns` names the six numeric DB values: message count, lastMessageSeq, outsider rate count, outsider outcome count, outsider read cursor and reports against peer. These snapshots deliberately do not represent every table or every actor. Additional direct SQL assertions verify the returned message ID/text/seq, peer read cursor and final point deletion. The file is emitted only after all assertions and fixture cleanup succeed. It is a safe functional trace, not raw infrastructure logs.

## Observed chain and persisted effects

The outsider's initial history GET returns403. PUT membership returns200 with chatId/userId/joinedAt/lastReadMessageSeq; the system join record raises history/last sequence from2 to3. Repeating PUT preserves state, and the next history GET returns three rows. A blank synthetic send returns422 without state changes.

POST messages with the safe synthetic text returns201 with Message fields id/chatId/messageSeq/type/author/text/system/createdAt. Direct SQL finds that exact ID, body and seq4. Message count becomes4, outsider rate/outcome counts each1. Same-key replay returns equal canonical JSON without changing snapshots. GET afterSeq=3 and search q=acceptance each return exactly the created Message. Peer detail reports unread4; PUT read-cursor body lastReadMessageSeq=4 returns200, direct SQL reads4, and the next detail reports unread0.

POST report body contextChatId/targetUserId/reasonText returns201. Its same-key replay preserves canonical JSON and count1; fifteen distinct additional reporters raise persisted count to16. A subsequent peer send returns403 MESSAGE_MUTED while snapshots remain unchanged; peer detail GET remains200. This is an integrated report/mute observation, complementing the separate exact lifetime/deadline tests rather than replacing them.

DELETE outsider membership returns204 and appends member_left at seq5; repeat DELETE204 preserves the resulting snapshot. Subsequent history/send return403, and GET me/chats contains no items for that actor. The muted peer can leave the separately prepared second point's only chat: DELETE204 physically removes that point, leaving exactly one fixture point/chat. Its subsequent history GET returns403. Scoped fixture cleanup then succeeds, including reports created by the joining outsider. The affected fixture acceptance test was rerun in the same required invocation.

## Actual checks and limits

Lead executed `./gradlew.bat integrationTest --tests com.example.acceptance.ChatRestAcceptanceIntegrationTest --tests com.example.acceptance.ChatAcceptanceFixtureIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in1m3s.

Inspected current XML: ChatAcceptanceFixtureIntegrationTest1/0/0/0, timestamp2026-11-04T03:25:12.551Z, suite3.414s; ChatRestAcceptanceIntegrationTest1/0/0/0, timestamp2026-11-04T03:25:15.967Z, suite2.600s (tests/skipped/failures/errors). Local REST-chain PASS for these observations. Later Gradle runs can replace XML; the versioned trace and this summary preserve this executed evidence.

The chain is sequential functional acceptance. It is not concurrency, WS isolation/resync, process-restart, network-fault, load, deployed HTTPS, Android/iOS or BG-1 evidence. Those limits do not promote overall M3 or client status.

## Corrected SYSTEM evidence and regenerated trace — 04.11.2026

Group review P2 G11-FIXTURE-SYSTEM-SCHEMA identified the initial fixture seed's incomplete userId-only SYSTEM object. The previous run and its38-step trace checked type/sequence/access but not canonical SYSTEM shape; they are not evidence of a valid SystemMessage payload. Production writer code was unchanged.

The corrected fixture uses JdbcSystemMessageWriter for seq2. The REST test now checks the seeded seq2/member_joined and actual REST join seq3/member_joined against complete M0.T5 fields, expected actor/point/chat IDs and null author/text. A peer history GET after outsider leave additionally verifies seq5/member_left with the same full schema. All nine payload fields are present; proposalId/bookingId/startsAt/endsAt/reason are null for these membership messages. The current linked artifact is the regenerated39-step trace from this corrected execution, replacing the earlier incomplete-shape trace rather than preserving a misleading duplicate.

Lead executed the same combined command above: PASS1m3s, exit0. Corrected XML: ChatAcceptanceFixtureIntegrationTest1/0/0/0, timestamp2026-11-04T03:35:56.127Z, suite3.053s; ChatRestAcceptanceIntegrationTest1/0/0/0, timestamp2026-11-04T03:35:59.183Z, suite2.968s. Trace shape/statuses and exclusion of credential/token/phone markers were inspected before copying the build artifact. Re-review of this correction is recorded in the execution protocol. Historical timestamps above retain their original, narrower evidential meaning.
