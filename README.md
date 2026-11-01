# roy-backend

Ktor JVM server foundation for M1.T1. The backend is an independent Gradle and Git
repository. It currently provides Flyway-managed PostgreSQL/PostGIS schema,
JSON errors with request IDs, and database-backed `GET /health`. Authentication
and product routes belong to later tasks.

## Requirements

- JDK 17 for the Gradle JVM and Kotlin toolchain. Gradle Wrapper 9.5.1,
  Kotlin 2.4.20, and Ktor 3.6.1 are pinned in the project; see
  [`docs/runtime-matrix.md`](docs/runtime-matrix.md).
- Docker with Compose for the local `postgis/postgis:18-3.6` service.
- Free local ports 5432 for PostGIS and 8080 (or `SERVER_PORT`) for Ktor.

From the backend directory, select your installed JDK 17 in PowerShell. Replace
the placeholder path with the JDK directory on **your** machine:

```powershell
$env:JAVA_HOME = '<path-to-installed-JDK-17>'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& "$env:JAVA_HOME\bin\java.exe" -version
```

The `run`, `compileKotlin`, `compileTestKotlin`, `build`, and `test` Gradle tasks
were confirmed by `.\gradlew.bat tasks --all` on 2026-11-01. Use
`.\gradlew.bat compileKotlin --no-daemon` to compile the server. The `build` and
`test` tasks are available but were not run for this implementation.

## First local start

1. Copy the safe template and edit `.env`. Replace **all** credential and JWT
   placeholders. Keep `POSTGRES_USER` equal to `DATABASE_USER`, and
   `POSTGRES_PASSWORD` equal to `DATABASE_PASSWORD` for this local setup.
   `POSTGRES_DB` must match the database name in `DATABASE_URL`.

   ```powershell
   Copy-Item .env.example .env
   # Edit .env locally; never commit or share it.
   ```

2. Start only the local database and inspect its container health:

   ```powershell
   docker compose up -d db
   docker compose ps db
   ```

   Compose reads `.env` for `POSTGRES_*` interpolation. It does **not** export
   those values to a JVM launched on the host. The named `postgres_data` volume
   retains the database across normal stops. `POSTGRES_*` initializes an empty
   volume only; changing the password in `.env` later does not change an
   existing database account.

3. In the **same PowerShell session** where you will run Gradle, export the
   simple `KEY=value` lines from `.env` into the process environment:

   ```powershell
   Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
       $name, $value = $_ -split '=', 2
       [Environment]::SetEnvironmentVariable($name, $value, 'Process')
   }
   .\gradlew.bat run
   ```

   This loader expects one unquoted value per line, as in `.env.example`.
   `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, and `JWT_SECRET` are
   required; `SERVER_PORT` defaults to 8080. The server fails startup when
   required configuration, the DB connection, or a Flyway migration fails.
   Flyway applies `V001`–`V003` before routes are installed. Do not print the
   environment variables or include `.env` in diagnostic output.

4. From another terminal, check readiness at the configured port:

   ```powershell
   $portLine = Get-Content .env | Where-Object { $_ -match '^SERVER_PORT=' } | Select-Object -Last 1
   $port = if ($portLine) { ($portLine -split '=', 2)[1] } else { '8080' }
   Invoke-RestMethod "http://127.0.0.1:$port/health"
   ```

   A ready server returns `{"status":"ok"}` and `X-Request-Id`. This endpoint
   makes a fresh DB probe on every call. A DB failure after startup returns
   `503` with the common JSON error envelope; a migration failure stops startup
   rather than returning a false `ok`.

## Repeat start and normal stop

With the existing volume, run `docker compose up -d db`, load `.env` into the
PowerShell process again, and run `.\gradlew.bat run`. Flyway checks its schema
history and does not reapply successful migrations or duplicate preset seed
rows. Stop Ktor with Ctrl+C in its terminal, then use `docker compose stop db`
if the database is no longer needed. `docker compose down` also keeps the named
volume, but **do not use `docker compose down -v` for a normal stop**: `-v`
deletes the local database volume. A new terminal does not inherit the process
variables loaded above; load them again when restarting.

## Diagnostics

- **Wrong DB password or missing env:** check that `.env` has no placeholders,
  the `DATABASE_*` values match the initialized local DB, and the JVM received
  them via the PowerShell loader. Startup must fail rather than serve health.
  Do not paste passwords, JDBC credentials, or JWT values into issue reports.
- **DB unavailable:** check `docker compose ps db` and the local port 5432.
  If the server was already running, `/health` must return `503`; if it is
  starting, Flyway or the pool prevents successful startup.
- **Flyway checksum mismatch:** an applied migration was changed. Do not edit
  it again or disable validation. Restore the applied file and place any new
  schema change in a new, higher-numbered migration. Keep the data volume when
  investigating.
- **Wrong Java selected:** run `& "$env:JAVA_HOME\bin\java.exe" -version` and
  verify JDK 17. The system `java` may point to another installation.

The Gradle source compilation and task listing were checked on Windows with
JDK 17. Docker startup, actual SQL migration, a second start with existing
data, and live `/health` responses have **not** been run in this environment;
they require the local PostGIS setup above. No production credentials or
personal data belong in the local seed or repository.
