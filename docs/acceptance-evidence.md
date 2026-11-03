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
