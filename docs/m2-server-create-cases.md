# M2.T6.B02: create and reverse geocode server cases

These cases use the backend API and a real isolated PostGIS database. They are server acceptance evidence for Point creation and the reverse geocode boundary, not a mobile or external DaData availability claim. The source baseline is the branch commit containing this document and `M2CreateGeocoderAcceptanceIntegrationTest`; record its final commit and the database's actual Flyway history when executing.

## Environment and boundary

- Use only disposable database `roy_m2t6_create` cloned from the clean V013 + viewport fixture database. The test requires the exact `TEST_DATABASE_URL` database name before any writes. It creates random synthetic actors and request keys, so repeated runs on the same database do not reuse prior rows.
- The test invokes Ktor `/api/v1/geocoding/reverse` with a controlled `ReverseGeocoder` result. This proves the API result and fallback behavior, not a live request to DaData. The separate adapter test `DaDataReverseGeocoderTest` covers provider response translation; an external service run must be logged independently if required.
- The create requests go through authenticated `POST /api/v1/points` and the real JDBC transaction. Coordinates at latitude −41/−42 and longitude −71/−72 are far from the cloned Moscow and equatorial fixture Points, so the tested paths are independent of nearby confirmation. A seeded actor has an unusable password hash and local test session identity. No real phone, provider key, or token is written to the report.

## Case expectations

| Case | Input and operation | Expected HTTP/service result | Expected durable database state |
| --- | --- | --- | --- |
| M2-CREATE-01 | Reverse lookup returns synthetic address, then `POST` with that address and `addressSource=provider`. | Reverse `200` with `source=provider`; create `201`, Point source `provider`. | Exactly one Point with `GEOCODED` and the address, one `football` topic, one Chat with `participant_count=1`, one active creator membership, one creation ledger event referencing the Point, one successful idempotency row. |
| M2-CREATE-02 | Reverse lookup reports unavailable, then an independently entered address is submitted with `addressSource=manual`. | Reverse `503 GEOCODER_UNAVAILABLE`; manual create `201`, Point source `manual`. | The same complete graph for the manual actor; Point stores `MANUAL` and the entered address. A geocoder outage does not create a partial Point or prevent an explicit manual create. |
| M2-CREATE-03 | Replay the successful manual create with the same key and payload; then change the address while keeping the key. | Replay returns the original `201` JSON; changed payload returns `409 IDEMPOTENCY_KEY_REUSED`. | Still exactly one Point, ledger event, and successful idempotency row for the manual actor. |
| M2-CREATE-04 | Force response projection to fail after Point/Topic/Chat/Membership inserts inside the write transaction, then retry the identical command and key. | First service call throws; retry creates normally. | After failure: no Point, Topic, Chat, Membership, ledger event, or idempotency row for the actor. After retry: one Point and one ledger event. The rollback check reads the committed PostGIS database from another connection. |

The expected graph is based on the atomic create contract and schema foreign keys, not on the returned body alone. The DB assertions read stored rows and compare their IDs, source, address, topic, active membership, ledger link, and key outcome with the request. A forced callback failure occurs after insertion and before commit; an empty graph demonstrates rollback of intermediate writes.

`M2CreateGeocoderAcceptanceIntegrationTest` is safe to rerun on the same guarded database. Its controlled geocoder does not establish live provider uptime, response quality, timeout behavior, or service credentials.

## Observed server run

On 2026-11-03, the focused class ran against the guarded `roy_m2t6_create` PostGIS clone with Flyway V013 present. After review refinements requiring the database environment and strengthening post-insert rollback and exact graph cardinalities, the class was rerun with `--rerun-tasks`: Gradle **BUILD SUCCESSFUL**, JUnit **2 tests, 0 skipped, 0 failures, 0 errors**. This is the observed T054 server case result. The final backend commit and full evidence set belong in the M2 server result protocol; this server case does not grant a full M2 or mobile PASS.
