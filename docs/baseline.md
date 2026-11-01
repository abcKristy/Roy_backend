# Backend baseline before M1.T1

Recorded on 2026-11-01 from commit `96c7b2b` (`Initialize Ktor backend scaffold`).
The working tree was clean before creating branch `feat/m1-t1-server-foundation`.

| Area | Existing state |
| --- | --- |
| Git | Independent repository in `project/backend`; remote `origin` is `git@github.com:abcKristy/Roy_backend.git` |
| Gradle | Single `roy-backend` project; Wrapper 9.5.1; Kotlin JVM plugin 2.4.20; Ktor catalog 3.6.1; JVM toolchain 21 |
| Package | `com.example` |
| Entry point | `EngineMain` via `main.kt` and `application.conf` |
| Routes | `GET /` returns `Hello, World!` |
| Test | `ServerTest` checks status 200 for `/` |
| Logging | Existing `logback.xml` |
| Ignore rules | Build/cache/IDE outputs and local `.env` files ignored; `.env.example` allowed |

The starter has no database, migrations, health endpoint, API error contract, or
product routes. No existing source or test was regenerated for this baseline.
