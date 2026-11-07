# T120 / M5.T1.B06 protected avatar reads

Backend baseline `cdb03c4` on `feat/avatar-storage-access`. The route uses the existing Flyway V001–V016 schema. The API version is v1.

`GET /api/v1/users/{userId}/avatar` resolves a full user UUID to a private reference after verifying the JWT and live session. The parameterized lookup allows an active target to be read by the owner or when the viewer and target are active members of the same chat. Chat membership rows refer to current chats through the foreign key. The database connection is released before the storage read. A fresh query checks the session, membership and reference again before the response can use the read result. A change to the reference during the read returns the same `404 RESOURCE_GONE` as an absent or inaccessible image. If access still holds, a storage failure returns `503 AVATAR_STORAGE_UNAVAILABLE`. The response does not expose the private reference.

A successful read serves the stored JPEG or PNG bytes with a strong `ETag` derived from the media type and content, `Cache-Control: private, no-cache`, `Vary: Authorization` and `X-Content-Type-Options: nosniff`. `If-None-Match` supports weak comparison, a list of tags and the wildcard. A match returns an empty `304` only after the current representation is read and access is confirmed. The route sets `Cache-Control: no-store` for the expected `401`, `404` and `503` errors. The shared transport handler maps unexpected failures to `500 INTERNAL_ERROR`.

## Local verification

On 2026-11-07 the Windows JDK 17 runner applied V001–V016 to a disposable loopback PostgreSQL 18.6 / PostGIS 3.6.4 instance. `AvatarReadRoutesIntegrationTest` passed nine tests with zero failures, errors or skips. Requests exercised production Ktor routing, signed JWTs, JDBC session and membership checks, and the real `LocalAvatarStore` in an owner-only temporary directory.

- The owner and active peer received identical PNG and JPEG representations with stable validators and private cache headers. The read did not change the profile, session, memberships, jobs, messages or the storage directory listing.
- A missing avatar, outsider, member of another chat at the same point, former viewer or target member, deleted target, shortened UUID and private object key received the same `404` error. Denied requests did not read the private store. A full UUID with uppercase hex letters resolved correctly.
- Missing, invalid, mismatched and revoked sessions were rejected. A live access-only session from the V004 compatibility path remained usable.
- Matching strong and weak tags, a list containing a quoted comma and the wildcard returned an empty `304` with the protected cache headers. Malformed validators yielded a regular `200`. After replacement the old validator returned the new representation. After leaving the chat even its current validator returned `404`.
- An absent provider object returned `404`. A corrupt local object produced the sanitized `503` with no validator or private path.
- Deterministic pauses after loading the object allowed membership departure, session revocation and an avatar-reference swap to commit while the request was in flight. The paused requests returned `404`, `401` and `404` respectively, and a fresh authorized read after the swap returned the new bytes.

Before adding the route, all eight initial tests failed on expected assertions. A later regression run reproduced three failures in validator-list parsing, uppercase UUID handling and legacy sessions before the corresponding fixes. Six focused suites passed 26 tests with zero failures, errors or skips. OpenAPI 3.1 validation matched 48 operations to the Ktor source and validated 456 examples.

From the avatar worktree:

```powershell
& ..\..\m5-pool-one-test.ps1 -Class com.example.api.AvatarReadRoutesIntegrationTest -Alias roy_m5t120_read
.\gradlew.bat --offline --console=plain --max-workers=2 test --tests com.example.auth.SessionAccessTest --tests com.example.auth.SessionRouteTest --tests com.example.api.AvatarUploadTest --tests com.example.api.DocumentationRoutesTest --tests com.example.storage.LocalAvatarStoreTest --tests com.example.storage.YandexAvatarStoreTest --rerun
$env:PYTHONPATH = (Resolve-Path '../../../build/doc-validation').Path
python -X utf8 tools/api_docs.py check
```

The lead validated the branch-only Postman preview `project/backend/.local/m5-postman-group-two-proposed.json` against the official Postman v2.1 schema and compared its three avatar operations, bearer authorization, body and media types, `If-None-Match` and `304` with generated Swagger. The published collection at `project/postman/Рой.postman_collection.json` still describes published backend main. Group 2 remains branch-local pending T118 acceptance with a dedicated private Yandex bucket and KMS access. The local store evidence covers T120.
