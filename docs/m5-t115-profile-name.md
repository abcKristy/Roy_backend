# T115 profile name acceptance

Backend baseline `0b0c13986e91c062ed3afbc6500f33fcf49d1cca` on `feat/profile-avatar-foundation`. On 2026-11-07, the isolated test runner applied Flyway V001–V015 to disposable loopback PostgreSQL 18.6 / PostGIS 3.6.4 databases. The fixture exercised the production Ktor routing, JWT checks, JDBC session repository, and profile transaction.

`ProfileNameRoutesIntegrationTest` exercises six scenarios for `PATCH /api/v1/me`.

- A name with surrounding whitespace returns 200 and the complete canonical `UserSelf`, matching `GET /api/v1/me`. The database snapshot confirms the requested name and a new `updated_at`, with phone, age, and the other account's profile preserved.
- Names of one and 80 supplementary Unicode code points return 200 after trimming. Empty, whitespace-only, and 81-code-point names return 422 `INVALID_FIELD` and preserve the saved profile.
- Bodies containing `phone`, `ageYears`, or an unknown field, and bodies with missing or null `displayName`, return 422 `INVALID_FIELD` and preserve the saved profile.
- A request without authorization returns 401 `AUTH_REQUIRED`. A request with a revoked session returns 401 `SESSION_EXPIRED` even when its requested name equals the saved name.
- Repeating the current normalized name returns the same profile and preserves `updated_at`.
- A test gate pauses the first response after its database commit. A later PATCH commits and returns its new name before the gate releases the first response. An independent database read and GET both return the newer name after the earlier response arrives.

The baseline run executed all six tests and failed only the normalized-repeat case. Both responses returned the same profile, but the retry advanced `updated_at` from `08:38:41.790735Z` to `08:38:41.811611Z`. The service now returns the current profile before issuing the UPDATE when the normalized name matches, after rechecking the live session under the existing locks.

After the retry fix, all six tests passed with no failures, errors, or skips. The existing `ProfileServiceIntegrationTest` for name changes, password changes, and session revocation also passed on its own freshly migrated fixture (one test, no failures, errors, or skips).

## Unicode validation after review

Three additional HTTP tests reproduced the review findings. Escaped U+0000 returned 500 with the row preserved. Escaped unpaired high and low UTF-16 surrogates each returned 200 and stored a question mark in place of the invalid character. The redo before validation ran nine tests with three failures and zero errors or skips.

The service rejects U+0000 and unpaired surrogates before the write. The follow-up run on a fresh V001–V015 fixture passed all nine tests with zero failures, errors, or skips. The three invalid inputs returned 422 `INVALID_FIELD` and preserved the row. An escaped valid surrogate pair round-tripped successfully and malformed JSON returned 400 `INVALID_JSON`.

The identity OpenAPI fragment and generated document now describe the accepted name and its 422 response. `tools/api_docs.py check` validated 45 Ktor operations, 430 examples, and all component schemas.

The response-order test covers server state. Each PATCH containing a different name is a new write under the current contract. The payload carries no operation identity or revision for identifying a replay of an older distinct name.
