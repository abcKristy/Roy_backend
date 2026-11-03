# M2.T6.B01: server acceptance fixture passport

This passport fixes the input data and expected observations for the M2 server cases. It does not record a server PASS by itself. The expectation table below comes from the stated time intervals, WGS84 coordinates, and inserted rows, rather than from a response returned by the implementation.

## Isolated dataset

- Source: `src/test/resources/m2-server-acceptance-fixture.sql` on the backend branch containing this passport. Apply it **once** to a disposable PostgreSQL/PostGIS database named exactly `roy_m2t6_fixture`, cloned from the migrated V013 `roy_m1t4_viewport` fixture database. The SQL rejects another database, a missing viewport base point, and a second application. It does not alter the viewport rows.
- Verify the actual `flyway_schema_history` and fixture SQL revision when running acceptance. Source migration files or a listening server alone do not prove the target database's schema.
- Synthetic users `50000000-0000-4000-8000-000000000001` through `...000003` are the anchor, inside, and outside actors. Their phone numbers are synthetic, their password hashes are unusable, and their privacy version is fixture-only. Each actor owns one of the corresponding `60000000-0000-4000-8000-000000000001` through `...000003` Points.
- Each Point has one `football` topic (`70000000-...000001` through `...000003`), one Chat (`80000000-...000001` through `...000003`), and one active membership for its owner. Thus each new Chat has `participant_count=1`, each new Point has one distinct active person, and the three new Points have three distinct members in total. These counts exclude the viewport Points and Chats.
- The fixed clock is **2026-11-03 12:00:00 UTC**. Tests and quota calculations pass this instant explicitly; host wall time is not an oracle for the boundary cases.

## Independent expected values

The rolling windows are `[now − 168 hours, now)` and `[now − 720 hours, now)`. The anchor actor has the following six creation ledger rows; `point_id` is null intentionally because the ledger must survive Point removal.

| Event at UTC | 7 days | 30 days |
| --- | --- | --- |
| 2026-10-04 11:59:59 | Outside | Outside |
| 2026-10-04 12:00:00 | Outside | Inside, inclusive lower bound |
| 2026-10-27 11:59:59 | Outside | Inside |
| 2026-10-27 12:00:00 | Inside, inclusive lower bound | Inside |
| 2026-10-27 12:00:01 | Inside | Inside |
| 2026-11-03 12:00:00 | Outside, exclusive upper bound | Outside, exclusive upper bound |

At the fixed clock, the expected quota is **2/3 weekly**, **4/10 monthly**, with **1 weekly** and **6 monthly** remaining and the last-available warning set. The distance fixture uses an equatorial anchor at `(lon=0, lat=0)` and comparison Points at `(0.00089, 0)` and `(0.00091, 0)`. WGS84 gives approximately **99.08 m** and **101.30 m** respectively: only the former is inside the inclusive 100 m nearby radius. The expected distance is derived from the coordinates and earth model, not a previous API or SQL result.

`M2ServerFixturePassportIntegrationTest` reads the exact database without mutation and checks ledger instants, the two quota windows, distances and `ST_DWithin`, Chats, and active memberships. It is safe to rerun against the same seeded database. The fixture SQL itself is deliberately apply-once.

## Preparation and cleanup boundary

Use a dedicated disposable database, never a shared application database. Clone the clean migrated viewport database to `roy_m2t6_fixture`, confirm the target name and the source Flyway state, then apply the SQL file with `psql -v ON_ERROR_STOP=1 -f ...`; a failed transaction must be treated as a failed fixture setup. Execute the test with `TEST_DATABASE_URL` ending in `/roy_m2t6_fixture` and the isolated test account credentials. Its exact URL guard prevents a different target.

After evidence has been captured, drop **only** the confirmed disposable `roy_m2t6_fixture` database or recreate it from the clean viewport source for another pass. Preserve the SQL source file and this passport as the versioned fixture. Do not delete the viewport source database as part of M2 cleanup. Record the backend commit, fixture revision, DB alias, actual Flyway history, test command, and result in the server acceptance protocol.

## Observed server run

On 2026-11-03, the fixture SQL from the initial T053 revision was applied once to the guarded `roy_m2t6_fixture` PostGIS clone at Flyway V013. The resulting synthetic subset had three users, three Points, three Topics, three Chats, three active Memberships, and six creation ledger events. After review made the database environment mandatory, the focused `M2ServerFixturePassportIntegrationTest` was rerun with `--rerun-tasks` against the same database: Gradle **BUILD SUCCESSFUL**, JUnit **1 test, 0 skipped, 0 failures, 0 errors**. The final T053 commit hash is recorded in the M2 server result protocol after the task commit is updated. This server fixture result does not imply a full M2 or mobile PASS.
