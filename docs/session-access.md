# M1.T2.B08 — auth contract for the client

Source: backend `main` at commit `bd8d2d4` (`Routing.kt`, `Registration.kt`, `Login.kt`, `Refresh.kt`, `SessionAccess.kt`, `ErrorMapper.kt`, `Transport.kt`). All examples are synthetic. Token markers are intentionally invalid, the phone uses the reserved 555 example range, and the password is an example string. Do not use these fixtures as credentials.

All paths have the `/api/v1` prefix. JSON requests use `Content-Type: application/json`. Register, login and refresh responses have `Cache-Control: no-store`. Timestamps are UTC ISO-8601 strings. Null fields are encoded explicitly.

## Register

`POST /api/v1/auth/register` needs no bearer token. First fetch `GET /api/v1/legal/privacy`, show its document URL, and send its exact current `version` as `acceptedPrivacyPolicyVersion`. The draft policy is available only in a configured local environment, not as a production publication. Phone is trimmed E.164; display name is trimmed and 1–80 Unicode code points; password is 8–128 Unicode code points; age is at least 12.

```json
{"phone":"+12025550123","password":"ExamplePasswordOnly123!","displayName":"Example User","ageYears":18,"acceptedPrivacyPolicyVersion":"draft-2026-11-01.2"}
```

Success: `201 Created` with the `Session` shape below. Failures: `422 INVALID_FIELD` for invalid or missing fields (including an omitted `acceptedPrivacyPolicyVersion`), `422 PRIVACY_POLICY_ACCEPTANCE_REQUIRED` with `details.currentVersion` when the supplied version is stale or empty, `409 DUPLICATE_PHONE`, and `503 SERVICE_UNAVAILABLE` when registration policy is unavailable. Refresh policy metadata after a version mismatch.

```json
{"error":{"code":"PRIVACY_POLICY_ACCEPTANCE_REQUIRED","message":"Примите актуальную политику конфиденциальности","details":{"currentVersion":"draft-2026-11-01.2"},"retryAfterSeconds":null},"requestId":"synthetic-request-id"}
```

## Login

`POST /api/v1/auth/login` needs no bearer token:

```json
{"phone":"+12025550123","password":"ExamplePasswordOnly123!"}
```

Success: `200 OK` with `Session`. Unknown phone, invalid phone and wrong password all return **`401 INVALID_CREDENTIALS`**, a form error. Do not classify it as session expiry or run refresh for it.

```json
{"error":{"code":"INVALID_CREDENTIALS","message":"Неверный номер телефона или пароль","details":null,"retryAfterSeconds":null},"requestId":"synthetic-request-id"}
```

## Session response

Register, login and successful refresh return the same fields. The token markers below are not valid tokens. UUID and timestamps are illustrative; the server generates them at runtime.

```json
{"accessToken":"SYNTHETIC_ACCESS_TOKEN_NOT_VALID","expiresAt":"2026-11-02T12:15:00Z","refreshToken":"SYNTHETIC_REFRESH_TOKEN_NOT_VALID","refreshExpiresAt":"2026-12-02T12:00:00Z","user":{"id":"00000000-0000-4000-8000-000000000001","displayName":"Example User","avatarUrl":null,"phone":"+12025550123","ageYears":18,"messageMutedUntil":null,"createdAt":"2026-11-02T12:00:00Z"}}
```

Persist the token pair, both expiry fields and user as one session snapshot. The access JWT is short lived (default 15 minutes); the opaque refresh token rotates and has a fixed session expiry (default 30 days). Both tokens are secrets: do not log, display or include them in error reports. A new login creates a separate session.

## Refresh and client state

`POST /api/v1/auth/refresh` needs no bearer token and receives the current opaque refresh token:

```json
{"refreshToken":"SYNTHETIC_REFRESH_TOKEN_NOT_VALID"}
```

Success: `200 OK` with a complete new `Session`. Replace the **entire** stored token pair and expiry fields together before waiting requests resume. The old refresh token becomes invalid; `refreshExpiresAt` does not move. Serialize refresh calls for one session (single flight) and share the result with concurrent requests. Reusing a rotated token can be treated as replay and revoke the session.

Invalid, expired, replayed or revoked refresh returns **`401 SESSION_EXPIRED`**. Only this definitive refresh response clears the stored token pair and requires login. A network failure has no HTTP response and must preserve the pair; a retry may still get `401` if the first rotation succeeded but its response was lost. Do not map network failure directly to `SessionExpired` or automatically queue/replay online mutations.

```json
{"error":{"code":"SESSION_EXPIRED","message":"Сеанс истёк. Войдите снова","details":null,"retryAfterSeconds":null},"requestId":"synthetic-request-id"}
```

## Protected access and logout

Send `Authorization: Bearer <access JWT>` to `GET /api/v1/me` and `POST /api/v1/auth/logout`. `/me` returns `200 OK` with the same `UserSelf` shape as `Session.user`. Logout returns `204 No Content` with no body and revokes only the current session (`jti`); other sessions remain active. The revoked session's refresh token also stops working. Repeated logout with that access token returns `401 SESSION_EXPIRED`.

Missing Authorization returns `401 AUTH_REQUIRED`; invalid, expired, forged or revoked access returns `401 SESSION_EXPIRED`. These differ from login's `401 INVALID_CREDENTIALS`. After access expiry, use single-flight refresh; if refresh returns `401 SESSION_EXPIRED`, require login.

```json
{"error":{"code":"AUTH_REQUIRED","message":"Требуется вход в аккаунт","details":null,"retryAfterSeconds":null},"requestId":"synthetic-request-id"}
```

## Transport errors

Every API error has `{ "error": { "code", "message", "details", "retryAfterSeconds" }, "requestId" }`. Malformed JSON or invalid UTF-8 returns `400 INVALID_JSON`; unknown keys, missing required fields and invalid field types return `422 INVALID_FIELD`; an oversized JSON body returns `413 INVALID_FIELD`. Treat `requestId` as diagnostic metadata, never as an auth credential. Error payloads contain no password, access token, refresh token or SQL.
