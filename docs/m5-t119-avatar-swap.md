# T119 avatar reference swap

Backend baseline `2d399b3` on `feat/avatar-storage-access`. The avatar mutation routes use the existing Flyway V001–V016 schema.

`PUT /api/v1/me/avatar` accepts a processed JPEG or PNG from the bounded T117 multipart receiver and returns `200 {"avatarUrl":"/api/v1/users/{userId}/avatar"}`. `DELETE /api/v1/me/avatar` returns an empty `204`. Repeating DELETE when the profile has no avatar leaves the profile and job queue unchanged. The server checks the access JWT and live session before accepting the upload body. It checks the JWT expiry and session under the user lock before committing either mutation. Legacy V004 sessions with a null session auth version remain eligible while the access JWT and current account version are valid and the session remains active. A semaphore allows two requests at a time to buffer and process uploads. Each body must finish within 60 seconds of the start of reading. An incomplete body returns `408 REQUEST_TIMEOUT` and releases its permit.

PUT compares the normalized bytes and media type with the current private object. An exact retry while that image is current reuses the same object. The server captures the previous key and profile timestamp at authenticated admission, before reading the body. The final compare uses that key and the profile's `updated_at` to detect a reference that changed and later returned to its initial value. An overlapping write that changes the profile timestamp can also produce `409 STALE_STATE`. Its details contain only the current `avatarUrl`, which can be null. A delayed response carries the same public path and performs no additional write.

The backend allocates a UUID v4 and commits a cleanup intent with a fifteen-minute grace period before saving the new object. The local and Yandex adapters create the object at that key conditionally. Provider calls run outside JDBC transactions. The final transaction consumes the pending and unexpired intent, swaps the reference and enqueues the previous object for immediate worker eligibility. DELETE clears the pointer and queues the old object in the same transaction. The queued old object stays intact through the commit.

After a failed PUT or swap, compensation checks the intent and live references in a fresh transaction. If a commit succeeded despite a lost confirmation, its consumed intent prevents deletion of the current object. Compensation attempts deletion outside the transaction. A failed cleanup leaves the durable intent. After a failed provider PUT the intent survives even when immediate deletion succeeds, so later cleanup can remove an object whose remote write completed after the failure. For the configured Yandex adapter the SDK call timeout is twelve seconds. A staged object whose intent has expired or been claimed cannot become current. The swap verifies zero cleanup attempts, no claim or lease, and both the scheduled eligibility and the original deadline derived from `created_at`. Moving a retry or resetting its attempts cannot extend that deadline.

T118 private Yandex bucket and KMS acceptance remain a separate external dependency. This evidence uses the real local file adapter. T120 owns authorized avatar reads and T123 owns cleanup worker composition.

## Local verification

On 2026-11-07 the isolated JDK 17 runner applied Flyway V001–V016 to disposable loopback PostgreSQL 18.6 / PostGIS 3.6.4. `AvatarSwapRoutesIntegrationTest` passed 18 cases with zero failures, errors, or skips. Requests went through the Ktor profile routes, JWT and JDBC session checks, actual PostgreSQL transactions, and an owner-only temporary directory. Faults were injected at the storage and commit-confirmation boundaries.

- PUT saved a readable normalized image, an identical repeat kept its key, and two replacements each recorded one old-key job. Another user remained untouched.
- DELETE cleared the pointer and recorded one old-key job. Repeating it returned an empty `204`.
- An account-scoped deferred PostgreSQL trigger rejected the commit and restored the old pointer and object. The staged object was removed and the old object had no cleanup job.
- Storage failure before and after a real write returned `503 AVATAR_STORAGE_UNAVAILABLE` and preserved the old object. Immediate cleanup failure left the unbound object and its durable intent.
- A legacy session with null `auth_sessions.auth_version` uploaded an avatar with `200` and deleted an avatar with `204`. The deletion queued exactly one cleanup job and left the old file readable until cleanup.
- Missing, mismatched, and revoked sessions were rejected. Revocation after the storage write prevented the final swap and cleaned the staged object.
- A valid multipart file of exactly 5,242,880 bytes succeeded. One extra byte returned `413`, and a mismatched media type returned `422`.
- An older upload finishing its body or its storage call after a newer commit returned `409 STALE_STATE`. Its details exposed only the current avatar URL. Delayed delivery of an earlier successful response left the newer pointer in place.
- Two authenticated requests stalled while streaming multipart bodies. Both returned `408 REQUEST_TIMEOUT` and released their permits, allowing the next upload to complete.
- A simulated loss of commit confirmation returned `500` after a real commit. The new pointer and object survived, and the old job remained. An expired staging intent prevented publication and the previous avatar survived.
- A cleanup attempt deleted the staged file before its provider response was confirmed. Rescheduling the intent did not let the upload publish it. Resetting attempts after the original deadline also preserved the old avatar.

The focused `SessionAccessTest`, `SessionRouteTest`, `AvatarUploadTest`, `LocalAvatarStoreTest`, `YandexAvatarStoreTest`, and `DocumentationRoutesTest` passed 26 cases with zero failures, errors, or skips. OpenAPI 3.1 validation matched 47 REST operations to the Ktor source and validated 446 examples. In the race-review pre-fix run the four added regressions failed and the original 12 scenarios passed. In the legacy-session pre-fix run both new scenarios returned `401` and the previous 16 passed.

After the legacy-session fix, `ProfileServiceIntegrationTest` and `ProfileNameRoutesIntegrationTest` passed 10 cases on disposable PostGIS. `SessionAccessTest` and `SessionRouteTest` passed four cases on JDK 17. All four suites reported zero failures, errors, or skips.

```powershell
& ..\..\m5-pool-one-test.ps1 -Class com.example.api.AvatarSwapRoutesIntegrationTest -Alias roy_m5t119_avatar
& ..\..\m5-pool-one-test.ps1 -Class com.example.auth.ProfileServiceIntegrationTest -Alias roy_m1t2_avatarprofile
& ..\..\m5-pool-one-test.ps1 -Class com.example.api.ProfileNameRoutesIntegrationTest -Alias roy_m5t115_profile
.\gradlew.bat --offline --console=plain --max-workers=2 test --tests com.example.auth.SessionAccessTest --tests com.example.auth.SessionRouteTest --tests com.example.storage.LocalAvatarStoreTest --tests com.example.storage.YandexAvatarStoreTest --tests com.example.api.AvatarUploadTest --tests com.example.api.DocumentationRoutesTest
python tools/api_docs.py check
```
