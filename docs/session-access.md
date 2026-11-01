# M1.T2 protected session access

`GET /api/v1/me` and `POST /api/v1/auth/logout` require `Authorization: Bearer <access JWT>`. Missing authorization returns `401 AUTH_REQUIRED`. Invalid, expired, forged or revoked sessions return the same `401 SESSION_EXPIRED` response. Login's public `401 INVALID_CREDENTIALS` remains a separate form error.

The verifier checks the HS256 signature, `sub`, `jti`, `authVersion`, `iat` and `exp`. Every protected call then queries `auth_sessions` joined with the active `users` row, requiring a non-revoked, unexpired session and matching `auth_version`. The repository runs JDBC work off the event loop. Logout updates only the current `jti`; another session belonging to the same account remains active. A repeated logout with the revoked token receives `401`.

`GET /me` returns the full v1 `UserSelf` shape. These routes do not depend on the draft privacy policy. Unit/route checks cover token rejection, profile response and local revocation. The JDBC integration test runs only when `TEST_DATABASE_URL` points to a disposable `roy_m1t2_*` database; it checks one-session revocation, database expiry, auth version and deleted users. It skips when that variable is absent.
