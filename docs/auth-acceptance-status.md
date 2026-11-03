# Local auth acceptance and planning consistency

Date: 2026-11-04. Scope: P3 from the T001–T056 review, specifically the inconsistent status/acceptance checkboxes for M1.T2.B04–B07 (T014–T017).

## Verified result

The Lead ran a fresh disposable loopback PostGIS database suite; the recorded JUnit XML results contain 32 tests, zero failures, zero errors and zero skips. The relevant auth evidence is:

- B04 login: LoginRouteTest 1, LoginServiceTest 5, JdbcLoginRepositoryIntegrationTest 1.
- B05 access guard: SessionAccessTest 3, SessionRouteTest 1 and JdbcSessionRepositoryIntegrationTest 1. A signed token is insufficient after session revocation, expiry, version changes or deletion.
- B06 refresh: RefreshRouteTest 1, RefreshServiceTest 4 and JdbcRefreshRepositoryIntegrationTest 3. Two rotations preserve fixed expiry, oldest-token replay revokes access and current refresh, and a separate session remains valid. A successful ordinary rotation replaces the refresh token; this does not claim every previously issued access token is immediately revoked on normal rotation.
- B07 logout isolation: SessionRouteTest exercises the HTTP logout effect; JdbcSessionRepositoryIntegrationTest checks that revoking one jti preserves the other.
- Additional tests in the same run: ServerConfigTest 6, DatabaseBootstrapIntegrationTest 1, TestDatabaseTest 5.

The password persistence/logging criterion additionally uses static inspection of src/main/kotlin/auth/Login.kt and src/main/kotlin/persistence/JdbcLoginRepository.kt: request passwords go to the hasher, and persistence receives hashes; those paths contain no raw-password log calls. No runtime log-capture assertion is claimed.

## Planning synchronization

The four task files now mark their local server acceptance criteria consistently with their completion status and identify the observed tests. The T2 index, T014–T017 execution rows, parent step 5/6 summaries and M1 roadmap partial result carry the same local scope. Existing checklist completion counts do not change. Mixed parent criteria and the milestone remain open; public policy, target-host Argon2id evidence, Android/iOS and BG-1 are not accepted by this run.

The workspace spec directory is outside the backend Git repository. docs/planning-status-sync.patch preserves the actual eight external planning-file changes in this backend commit. Paths in that patch are relative to the workspace root. On a checkout containing the original specification snapshot, run git apply --check project/backend/docs/planning-status-sync.patch from the workspace root, then git apply with the same patch path. On the already synchronized workspace, a reverse check verifies the patch is applied. The patch does not create a Git repository or copy the full specification tree into backend.

The historical review file is unchanged. A missing or skipped database scenario is not used as evidence for these completed criteria.

## Fix verification and focused re-review

- P2 safe URL error — commit 7342447: ServerConfigTest includes malformed URI rejection with only the setting name, no raw value or original cause in the stack trace. Six configuration tests passed in the final suite. Focused read-only review checked the caught URISyntaxException and sanitized replacement exception.
- P1 oldest refresh replay — commit 41279d2: the final suite includes three repository tests; the separate RefreshHistoryMigrationIntegrationTest passed 1/0/0/0 against another newly created disposable database. V013 to V014 migration preserves the previous hash and revokes legacy rotated sessions whose older history is unrecoverable; never-rotated sessions stay usable. Focused review found this legacy gap during the first pass and confirmed the corrected migration and test before commit.
- P2 misleading integration PASS — commit f2406b6: five in-memory guard tests passed; with no database, the ordinary JdbcLoginRepositoryIntegrationTest run reported one explicit skip, while the required integrationTest run failed one test before JDBC, as expected. The final configured suite executed all 32 scenarios with zero skips. Focused review checked explicit disposable ownership, loopback restrictions, sanitized failures and strict-task skip rejection.
- P3 status inconsistency — this commit: acceptance criteria, local completion labels and evidence are synchronized in all eight planning locations. Static password data flow is identified separately from dynamic observations. The portable patch is checked in reverse against the synchronized workspace; no root-specification push is claimed.

No shared, user or production database was used. The upgrade regression is additional to the 32-test final auth/config/guard suite. Temporary database ownership and isolation were established by the Lead before tests. Test results above are new runs reported by the Lead, not historic PASS entries from the original review.

## Executed command records

Run from project/backend with JDK 17. Environment values are intentionally omitted. Configured DB commands used only the separately verified disposable container, with TEST_DATABASE_DISPOSABLE=true confirmation, required TEST_DATABASE_URL and test-only DATABASE_USER/DATABASE_PASSWORD. The migration test used a separate fresh database from the auth suite.

```text
./gradlew.bat test --tests com.example.config.ServerConfigTest --tests com.example.testing.TestDatabaseTest --tests com.example.auth.LoginServiceTest --tests com.example.auth.LoginRouteTest --tests com.example.auth.SessionAccessTest --tests com.example.auth.SessionRouteTest --tests com.example.auth.RefreshServiceTest --tests com.example.auth.RefreshRouteTest --tests com.example.auth.JdbcLoginRepositoryIntegrationTest --tests com.example.auth.JdbcSessionRepositoryIntegrationTest --tests com.example.auth.JdbcRefreshRepositoryIntegrationTest --tests com.example.persistence.DatabaseBootstrapIntegrationTest --offline --daemon --max-workers=1 --console=plain
./gradlew.bat integrationTest --tests com.example.auth.RefreshHistoryMigrationIntegrationTest --offline --daemon --max-workers=1 --console=plain
```

The following were run without DB configuration; first gives helper 5 passed plus database 1 skipped, second fails the required scenario before JDBC as intended:

```text
./gradlew.bat test --tests com.example.testing.TestDatabaseTest --tests com.example.auth.JdbcLoginRepositoryIntegrationTest --offline --no-daemon --max-workers=1 --console=plain
./gradlew.bat integrationTest --tests com.example.auth.JdbcLoginRepositoryIntegrationTest --offline --daemon --max-workers=1 --console=plain
```

The portable planning patch passed `git apply --reverse --check project/backend/docs/planning-status-sync.patch` from the workspace root after synchronization.
