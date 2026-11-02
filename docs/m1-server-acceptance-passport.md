# M1.T6.B01: backend acceptance passport

This passport identifies the exact backend and schema used for each M1 server observation. It is an input to server case execution, not a PASS for BG-1, M1.T6, or any mobile flow. A listening port alone does not establish readiness. Android and iOS builds are not inputs to the server portion of acceptance.

## Source baseline recorded on 2026-11-02

| Item | Observed source fact | Version and boundary |
| --- | --- | --- |
| Backend | `feat/m1-pool-3` commit `5a3685c` | Git revision inspected for this passport; later commits need a new observation row. |
| Build | Gradle wrapper 9.5.1, Kotlin 2.4.20, Ktor 3.6.1, Java toolchain 17 | Read from `gradle/wrapper/gradle-wrapper.properties`, `gradle/libs.versions.toml`, and `build.gradle.kts` at `5a3685c`; no build was run for this passport. |
| Schema in source | Flyway V001–V013 are present under `src/main/resources/db/migration/` | File presence at `5a3685c` is not proof that a database applied or validated them. Record actual `flyway_schema_history` separately. |
| API surface | `/health`, `/api/v1/legal/privacy`, `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/api/v1/me`, `/api/v1/topics/presets`, `/api/v1/points`, `/api/v1/points/{pointId}` | Routes and service wiring inspected in source at `5a3685c`; actual HTTP availability depends on runtime configuration and is not asserted here. |
| Synthetic data | `src/test/resources/m1-t4-viewport-fixture.sql`, `src/test/resources/step9-local-fixture.sql`; synthetic auth examples in `docs/session-access.md` and `docs/access-error-fixtures.md` | The viewport SQL is guarded for an empty disposable `roy_m1t4_viewport` database; the larger Step 9 SQL has its own `roy_m1t2_step9load` guard. These are separate datasets and have not been executed for this passport. |

The M1 server acceptance environment must record a non-secret environment label, backend Git commit and build artifact identity, deployment mode (`APP_ENV`), Java/PostgreSQL/PostGIS versions, database alias, Flyway version/checksum history, policy version and publication mode, and the exact synthetic fixture revision. Do not record credentials, token values, private hostnames, raw `.env`, personal data, or SQL connection strings. If two cases use different builds or schemas, give them separate observation rows.

## Case register

Each case below has an owner and expected output. Its `Actual` value is deliberately `NOT RUN` for this passport; the source baseline does not turn an expectation into a measured result.

| Evidence ID | Server operation and expected observation | Required fixture or condition | Backend build / schema used for actual | Actual |
| --- | --- | --- | --- | --- |
| E-M1-01 | First and repeat migration, then `/health`: only a ready DB yields `200 {"status":"ok"}`; failed DB readiness yields a safe `503 SERVICE_UNAVAILABLE` when the API is running. | Disposable empty DB, then same DB on repeat; invalid DB configuration separately. | Not recorded | NOT RUN |
| E-M1-02 | Inventory the backend modules/routes and their configured dependencies against the build and schema baseline; identify missing or disabled server modules. | Exact backend build, runtime configuration without secrets, and route inventory. | Not recorded | NOT RUN |
| S-M1-MAP | Public presets, bbox page/cursor and point detail: 11 presets, complete single/multi-topic projection, no skipped/duplicated points across pages, `404 RESOURCE_GONE` for missing point. | `m1-t4-viewport-fixture.sql` on its guarded disposable DB; `limit=1`, WGS84 bbox and changed-filter cursor cases. | Not recorded | NOT RUN |
| S-M1-AUTH | Register/login/refresh/protected access/logout, including duplicate phone, age, stale policy, wrong password, expired access, replayed refresh and one-session revocation; expected safe status/code and DB side effects are recorded per case. | Synthetic accounts and a published policy valid for the isolated environment; never use production credentials or the local draft as production publication. | Not recorded | NOT RUN |
| S-M1-GUEST | Guest read versus protected create/chat access: public point read without bearer succeeds; missing auth gives `401 AUTH_REQUIRED`, expired access `401 SESSION_EXPIRED`, authenticated nonmember `403 NOT_MEMBER`. | Synthetic point/chat membership rows and separate guest/member/nonmember sessions. | Not recorded | NOT RUN |
| E-M1-05 | Policy metadata and document URL are reachable and match the consent version used by registration. | Approved publication for public acceptance; local draft may be observed only as a local test condition. | Not recorded | NOT RUN |

The parent M1.T6 contract reserves `E-M1-03` for Android and `E-M1-04` for iOS; neither is a server case or a prerequisite for this passport. `S-M1-MAP`, `S-M1-AUTH`, and `S-M1-GUEST` are server-only case IDs, while `E-M1-01`, `E-M1-02`, and `E-M1-05` keep their parent meanings.

For each execution, append a dated observation with: evidence ID, backend commit/build artifact, environment label, schema migration versions and checksums, fixture revision, request category, expected status/code, actual status/code, sanitized `requestId`, DB side-effect assertion, PASS/FAIL/BLOCKED, and the evidence file or log location. Keep a network failure as a transport observation with no HTTP status or `requestId`; it is not a confirmed logout. Report a case as BLOCKED when its environment or policy prerequisite is absent, and record why. The later server result protocol aggregates these observations; this passport does not pre-fill them.

No live API, database, Android, or iOS run was performed while preparing this passport. The source facts above were inspected at `5a3685c`; actual acceptance remains open.
