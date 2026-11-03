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
