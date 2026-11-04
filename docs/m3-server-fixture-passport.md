# M3 synthetic server fixture passport

Executed 04.11.2026 for T083 / M3.T6.B01 on branch `tests/chat-server-acceptance`, accepted base `7a2a7baeea424307d4173575724d6e15d0bae7f4` plus the T083 fixture/test revision. The resulting task commit is recorded in the execution ledger after review. This passport describes completed local server checks; it does not certify mobile clients, BG-1, external integrations or overall M3.

## Environment and identity

JDK17, Gradle9.5.1, PostgreSQL18.6/PostGIS3.6.4, migrations V001–V015. Required integration execution used the owned disposable Docker database `roy_m3_chat`, loopback-only port35257. The helper rejects other database names and requires TEST_DATABASE_DISPOSABLE=true; the integration harness additionally enforces its disposable-database guard. Credentials and JWTs are not reproduced here.

`ChatAcceptanceFixture` allocates a fresh UUID namespace on every construction: symbolic member, peer, outsider and sixteen distinct reporters; nineteen persisted sessions; two points, each with one football topic and one chat. Point coordinates/addresses and history strings are synthetic. No source user data was imported. Token material is generated only inside the test. Each chat begins with a distinct TEXT row at messageSeq1 and SYSTEM/member_joined row at messageSeq2; last_message_seq is2. Member, peer and reporters belong to the first chat; only peer belongs to the second. Outsider belongs to neither. These SQL-created initial rows describe fixture preparation, not evidence of REST join/send acceptance.

## Executed observations

`ChatAcceptanceFixtureIntegrationTest` constructed two independently allocated namespaces simultaneously. Their user-ID sets were disjoint. The exercised namespace initially contained19 users,2 points,2 chats and4 messages.

Real HTTP history with JdbcSessionRepository authentication returned200 for the first-chat member and second-chat peer. The first history contained seq2/system followed by seq1/text. Repeated outsider→first-chat and member→second-chat reads returned403 and omitted the synthetic history text.

Each of sixteen distinct reporters submitted a real HTTP report against peer in the shared first chat. Every first request returned201; replaying its successful key returned the same JSON and left report count at index+1. After reports1–15 the target mute/trigger fields remained null. After report16 the trigger existed and mutedUntil equalled trigger+604800seconds. This fixture check complements the separately recorded concurrency/lifetime threshold tests; it does not claim every report race or transport failure.

Cleanup ran in a transaction using parameterized arrays containing only owned IDs: delete reports by owned reporters (RESTRICT user FKs), delete owned points (cascading topics/chats/messages/memberships), then owned users (cascading sessions/settings/rate/outcomes/notifications). Explicit cleanup was repeated twice and then again by use/close. Counts for owned users/points/chats/messages/reports were all0. The concurrently live sentinel namespace retained19 users,2 points,4 messages and0 reports, then its own use/close removed it. No table-wide delete or user-data discovery was used.

## Evidence and limits

Lead command: `./gradlew.bat integrationTest --tests com.example.acceptance.ChatAcceptanceFixtureIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in1m2s.

Inspected XML `build/test-results/integrationTest/TEST-com.example.acceptance.ChatAcceptanceFixtureIntegrationTest.xml`: tests1, skipped0, failures0, errors0; timestamp2026-11-04T03:15:33.862Z; suite3.278s. Local fixture acceptance PASS for the assertions above. Gradle may replace these build-directory XML files in later selected runs; this versioned passport preserves the observed summary, not an immutable raw-log archive.

The fixture is test-only and bounded to a prepared disposable database. Its cleanup assumes the generated namespace remains exclusive to its test; arbitrary external references to its identities are outside this contract. Server fixture readiness does not establish the subsequent REST chain, WS resync, send/leave races, Android/iOS UI, deployment or load acceptance.

## Canonical SYSTEM fixture correction — 04.11.2026

Independent group review found P2 G11-FIXTURE-SYSTEM-SCHEMA: the originally executed seed stored only userId in its SYSTEM payload. Its earlier PASS established type/sequence/access/counts but did not establish the M0.T5 SystemMessage schema. This was a test-fixture/evidence defect, not a demonstrated production writer defect.

The corrected seed starts last_message_seq at1, locks its owned point, and calls the existing JdbcSystemMessageWriter with the fixed seed message ID to append seq2. Required canonical fields are kind, actorUserId, pointId, chatId, proposalId, bookingId, startsAt, endsAt and reason. The fixture test now checks both chats against their distinct expected message/point/chat IDs and peer actor ID, with kind member_joined, optional values null, Message author/text null and type system. This verifies the corrected fixture rather than retrospectively changing what the earlier run proved.

Lead reran the required combined fixture/REST command recorded in the REST protocol: PASS1m3s, exit0. Corrected fixture XML1/0/0/0, timestamp2026-11-04T03:35:56.127Z, suite3.053s; corrected REST XML1/0/0/0, timestamp2026-11-04T03:35:59.183Z, suite2.968s. The current seed is canonical for these exercised assertions. Independent closure re-review is recorded in the execution protocol; overall/mobile/BG-1 scope remains open.
