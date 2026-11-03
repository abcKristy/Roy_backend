# M2.T1.B06 — point creation atomicity cases

These are executable case definitions for `POST /api/v1/points` and `PointWriteService.create`, not results of an acceptance run. Use a **disposable PostgreSQL/PostGIS database** migrated through the exact Flyway revision of the candidate build. Never attach failure triggers to an existing `roy` or production database. Record the backend commit, schema versions/checksums, fixture revision, case ID, HTTP/process result, and aggregate row-count differences; omit credentials, JWTs, phone numbers, and request bodies containing personal data.

## Shared fixture and assertions

Seed one synthetic active user with a valid JWT, no prior creation events, and a fresh UUID `Idempotency-Key` per case. Choose a valid WGS84 position, `football` preset, nonempty manual address and `confirmNearby=true`. For each rollback case, snapshot counts of `points`, `point_topics`, `chats`, `chat_memberships`, `messages`, `point_creation_events`, and `operation_idempotency` before the request. Keep the disposable database exclusive to the case so global count differences are meaningful. A rejected create must leave every count unchanged and no new point visible through public bbox/detail. Reuse the same key after removing the injected failure: exactly one `201 {point,chat,quota}` must commit, with the creator in the first chat, one ledger event, one stored success snapshot, and `quota.weekUsed=1`.

For R01–R04, install a test-only PostgreSQL `BEFORE INSERT` trigger on the named table that raises a fixed exception. Install one trigger at a time, after the actor fixture is committed and before the create call. Remove it in `finally` before retrying; discard the whole database after the run. The trigger is an actual DB failure inside the create transaction, not a mock service return. Do not assert a particular public 5xx body for this injected internal failure; inspect the sanitized error and ensure it reveals neither SQL nor secrets.

| Case | Failure point or transport condition | Expected durable result |
| --- | --- | --- |
| R01 | Reject insert into `point_topics` after `points` insert | Entire graph, ledger and idempotency snapshot roll back; same-key retry after trigger removal creates once. |
| R02 | Reject insert into `chats` after point and topic inserts | No point, topic, chat, membership, message, ledger or idempotency row remains; retry creates once. |
| R03 | Reject insert into `chat_memberships` after point/topic/chat inserts | No orphan point, topic or chat; no ledger or idempotency row; retry creates once. |
| R04 | Reject insert into `point_creation_events` after point/topic/chat/membership inserts | No graph, join message, ledger or idempotency row remains; quota is not spent; retry creates once. |
| R05 | Throw from the success-response projection after the graph and ledger are inserted but before commit | No graph/ledger/idempotency rows remain; retry with the same key creates once. `PointWriteServiceIntegrationTest` has a focused DB-backed probe for this boundary when its guarded `TEST_DATABASE_URL` is supplied. |
| R06 | Let the first POST commit, then drop the client connection before reading its response; retry the identical business payload and key | The retry returns the same saved `201` JSON value, including point/chat IDs and quota snapshot. Exactly one graph, one ledger event and one successful idempotency record exist. A changed business payload with that key returns `409 IDEMPOTENCY_KEY_REUSED`; changing only `confirmNearby` does not create again. A transport failure itself has no trustworthy HTTP status or `requestId`. |
| R07 | Reverse geocoder returns `503 GEOCODER_UNAVAILABLE` or a `200` result with `address:null` | Reverse lookup creates no point. A later create with a nonempty manually entered address and `addressSource=manual` succeeds and stores `MANUAL`; an empty manual address is rejected with `422 INVALID_FIELD` and no writes. Do not put the provider call inside the create transaction. |

R01–R04 are distinct insertion boundaries. R05 covers snapshot failure and R06 covers a committed result with a lost response; neither substitutes for the other. Provider failure in R07 is not a failed point transaction because the create call has not started. No mobile/UI observation is inferred from these server cases.

## Recording the run

For each case, record `PASS`, `FAIL`, `BLOCKED`, or `NOT RUN` against the candidate build and disposable DB. A test that skips because `TEST_DATABASE_URL` is absent is **NOT RUN**, even if Gradle reports `BUILD SUCCESSFUL`. Preserve only redacted HTTP status/code/`requestId`, aggregate before/after counts and Flyway history. If a rollback assertion fails, capture the exact surviving table and key category, fix the owning service/transaction task, and rerun the affected case before marking it PASS.
