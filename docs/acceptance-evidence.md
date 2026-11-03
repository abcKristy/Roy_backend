# Acceptance evidence retry

Date: 2026-11-04. Source baseline: e12ec33, branch fix/acceptance-evidence.
Scope: the fifteen tasks with insufficient evidence in the T001–T056 review.
All database runs use newly provisioned, owned, loopback-only disposable PostGIS
instances. Production .env files and shared databases are not used.
An executed test requires exit code zero and XML with zero failures/errors/skips.
External approval and deployment are never inferred from a local test.

## T002 — Runtime and build

`installDist runMigrations --offline --daemon --max-workers=1 --console=plain`
completed successfully using JDK 17. Every application class in the installed
application JAR has major version 61 (Java 17), independently read from the class
headers. The source uses jvmToolchain(17), catalog aliases and the pinned Wrapper.
runtime-matrix.md now documents the IDE Gradle JVM selector separately from the
compilation target, without committing a personal path. Actual IDE preferences
on another developer's machine are not a prerequisite or a measured result.

## T008 — Rechecked criteria

SafeErrorEnvelopeTest: 2 tests passed, zero failures/errors/skips. The tests exercise all typed 400/401/403/404/409/422/429 envelopes, synthetic SQL 500, typed 409, malformed JSON 400, correlation IDs and Logback capture without SQL, secret or throwable cause. Cancellation remains a static rethrow check in Transport.kt.

## T011 — Rechecked criteria

UnpublishedPrivacyDatabaseIntegrationTest: 1 real HTTP/JDBC test passed with zero skips; metadata and registration return 503 and users/sessions/settings are unchanged. DraftPrivacyRouteTest: 4 passed. BLOCKED for approval/publication: operator identity and contacts, approved immutable text/version and public HTTPS publication are absent. Local behavior is not legal approval or public acceptance.

## T019 — Rechecked criteria

ViewportFixtureIntegrationTest: 1 PostGIS test passed, zero failures/errors/skips, on the exact guarded viewport database. The SQL fixture was applied successfully once to empty migrated V014 schema before independent clones. It asserts both Points, distinct people and chat member counts, pagination, filter-bound cursor and missing Point. No load or EXPLAIN result is claimed.

## T030 — Rechecked criteria

DaDataReverseGeocoderTest: 3 tests passed; GeocodingRouteTest: 1 passed. Config key delivery is ServerConfig.kt:121 to Server.kt:132; the adapter is injected, uses bounded timeouts, sanitizes unavailable results and runs outside the database transaction. Controlled local provider tests cover request and failures. A live provider call is not part of this gateway evidence and was not made.

## T039 — Rechecked criteria

PointCreationGuardsIntegrationTest: 2 concurrent PostGIS tests passed without skips. PointWriteService takes global creation advisory then user lock before re-reading quotas and nearby points. JdbcIdempotencyOperations uses FOR NO KEY UPDATE; the no-confirmation response returns before success/idempotency record. Last quota slot and nearby races are asserted in the real database.

## T040 — Rechecked criteria

PointQuotaBlockRouteIntegrationTest: 1 HTTP/PostGIS test passed without skips. It asserts first 409, exact replay body, unchanged blockedUntil on another key, exactly 720 hours, one terminal idempotency record and no Point. JdbcIdempotencyOperations writes block and outcome on the same transaction; response construction follows the committed service result.

## T042 — Rechecked criteria

AddTopicAutojoinIntegrationTest: 3 tests passed; TopicUnicodeUniquenessIntegrationTest: 1 passed, zero skips. The new real service/SQL test covers Unicode surrounding whitespace, Cyrillic case, preserved display label, duplicate refusal and exactly one Topic/Chat. SQL lookup and UNIQUE share lower(btrim(...)); JVM key is explicitly advisory. Universal equality across arbitrary database locales is not a normative criterion.

## T049 — Rechecked criteria

PointChatLeaveRoutesIntegrationTest: 1 HTTP/PostGIS test passed without skips. Muted user leave succeeds, votes and future booking are adjusted, membership left_at and counts change, system messages are asserted and repeated leave is 204 with unchanged snapshot. User/Point/Chat locks serialize the stored operation.

## T050 — Rechecked criteria

PointEmptyDeletionIntegrationTest: 1 PostGIS test passed without skips. Non-last leave retains the Point; last leave removes topics/chats/memberships, while creation ledger survives with null Point reference. V003 cascades and V002 ledger SET NULL match the service deletion under Point lock.

## T006 — Rechecked criteria

MigrationHistoryValidationIntegrationTest passed 1/0/0/0 on a newly recreated database after asserting the concrete Flyway pending-migration error and checksum mismatch. V013 cannot start in production; local migration advances to V014; repeat migration preserves presets; corrupting one checksum prevents local and production startup; restoring it recovers. Initial broad test passed; the first strengthened assertion failed because FlywayValidateException precedes the custom schema guard. The test expectation was corrected to the observed pending-014 error and rerun successfully. This was a test-only expectation defect, not a production protection failure. V001-V014 applied successfully through the migration command on another new database.

## T012 — Rechecked criteria

PasswordHashBoundsTest 1, PasswordHasherTest 4, PasswordHashingWorkTest 1 and LoginServiceTest 5 passed without skips. The local JDK17.0.10/Windows11 sequential hash+verify benchmark medians were 66ms at19456KiB/t2/p1,124ms at46080KiB/t1/p1 and380ms at65536KiB/t3/p1. Five measured samples follow one warmup. BLOCKED for target acceptance: no identified target host/resources or approved latency/concurrency SLO. Local results do not establish target capacity or monthly cost and do not change production costs.

## T052 — Rechecked criteria

PointLifecycleRealtimeIntegrationTest passed 2/0/0/0. The real HTTP/PostGIS/WebSocket paths cover committed membership/Point deletion events, rejection of an exited subscription, absence of subsequent chat events for a former member, former-recipient Point deletion and an induced SQL-trigger rollback with HTTP500, unchanged database graph and no success event in the asserted observation window. Service commit precedes publication. Durable delivery beyond this best-effort contract is not claimed.

## T010 — Windows distribution and runbook lifecycle

The original installed Windows launcher failed with cmd.exe input-line-too-long at this long checkout path. startScripts now sets the Windows CLASSPATH to the distribution lib wildcard, expanded by Java. installDist succeeded and the patched .bat started twice. First startup was observed through application logs and Ctrl+C completed Hikari shutdown. Second startup returned health200/statusok. Pausing only the disposable database produced health503/SERVICE_UNAVAILABLE/requestId; unpause recovered health200. Second Ctrl+C also completed Hikari shutdown. A second owned container with its own volume preserved a synthetic probe row across stop/start (count1). The initial non-elevated health connection was unavailable and is not counted as a passing observation. No external deployment or real user data was involved.

## T028 — Current server evidence handoff

The protocol artifact is confirmed in its documented local scope. The current retry has45unique successful tests in10focused runs, with zero failures/errors/skips, plus a successful corrected strengthened-history rerun(1). Approved policy/public server acceptance remains BLOCKED(T011); target-host Argon2id remains BLOCKED(T012). Old M1/M2 protocol observations remain historical and are not relabeled as new runs. The unchanged privacy prerequisites also leave BG-1/mobile open.

### Observed focused run register

- unit: com.example.api.SafeErrorEnvelopeTest 2/0/0/0; com.example.auth.DraftPrivacyRouteTest 4/0/0/0; com.example.config.ServerConfigTest 6/0/0/0; com.example.auth.PasswordHashBoundsTest 1/0/0/0; com.example.auth.PasswordHasherTest 4/0/0/0; com.example.auth.PasswordHashingWorkTest 1/0/0/0; com.example.auth.LoginServiceTest 5/0/0/0; com.example.geocoding.DaDataReverseGeocoderTest 3/0/0/0; com.example.api.GeocodingRouteTest 1/0/0/0; com.example.points.CreatePointNormalizerTest 3/0/0/0 (tests/failures/errors/skipped).
- history: com.example.persistence.MigrationHistoryValidationIntegrationTest 1/0/0/0 (tests/failures/errors/skipped).
- bootstrap: com.example.persistence.DatabaseBootstrapIntegrationTest 1/0/0/0 (tests/failures/errors/skipped).
- privacy: com.example.auth.UnpublishedPrivacyDatabaseIntegrationTest 1/0/0/0 (tests/failures/errors/skipped).
- viewport: com.example.points.ViewportFixtureIntegrationTest 1/0/0/0 (tests/failures/errors/skipped).
- guards: com.example.points.PointCreationGuardsIntegrationTest 2/0/0/0; com.example.api.PointQuotaBlockRouteIntegrationTest 1/0/0/0 (tests/failures/errors/skipped).
- topic: com.example.points.AddTopicAutojoinIntegrationTest 3/0/0/0; com.example.points.TopicUnicodeUniquenessIntegrationTest 1/0/0/0 (tests/failures/errors/skipped).
- leave: com.example.api.PointChatLeaveRoutesIntegrationTest 1/0/0/0 (tests/failures/errors/skipped).
- delete: com.example.points.PointEmptyDeletionIntegrationTest 1/0/0/0 (tests/failures/errors/skipped).
- events: com.example.realtime.PointLifecycleRealtimeIntegrationTest 2/0/0/0 (tests/failures/errors/skipped).

All database results were obtained with schema V014 on newly provisioned,owned,loopback PostgreSQL18.6/PostGIS3.6.4 resources; the history test provisions its own V013 then tests the upgrade. Both disposable containers and their separate test volume were removed after checks. Retained local XML/logs are ignored build evidence; this register and the updated workspace report retain the reviewed observations.

The workspace specifications, backlog and single report are outside this Git repository. `acceptance-workspace-sync.patch` versions their actual changes for this retry; apply from workspace root to the prior snapshot. Reverse application is checked against the synchronized workspace. No root Git repository or external approval was created. Patch context-only blank lines are intrinsic unified-diff syntax.
