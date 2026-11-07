# API contract reconciliation

The published OpenAPI describes the routes registered by the production backend. It is not a list of every future M0 operation. The following differences were established by reading M0.T4/M0.T5, the actual Ktor routes, DTOs, services and their tests.

## Authentication: later M1 contract supersedes the M0 token model

M0.T4 §3.1 and M0.T5 §1 describe a single access JWT lasting 30 days with no refresh token. The implemented and subsequently handed-off M1 contract in [session-access.md](session-access.md) and [auth-slice-boundaries.md](auth-slice-boundaries.md) uses an access JWT (default 15 minutes) plus a rotating opaque refresh token with fixed session expiry (default 30 days). `POST /api/v1/auth/refresh` is implemented; `Session` includes `refreshToken` and `refreshExpiresAt`. Replay of a consumed refresh token revokes that session. OpenAPI follows this later delivered contract; no token behavior was changed for documentation.

Evidence: `auth/Registration.kt`, `Login.kt`, `Refresh.kt`, `SessionAccess.kt`; `auth/RegistrationRouteTest.kt`, `LoginRouteTest.kt`, `RefreshRouteTest.kt`, `SessionRouteTest.kt`, `JdbcRefreshRepositoryIntegrationTest.kt`.

## Avatar routes are future M0 scope

M0.T5 §3 lists `PUT /api/v1/me/avatar`, `DELETE /api/v1/me/avatar`, and `GET /api/v1/users/{userId}/avatar`. No corresponding Ktor routes exist. They therefore do not appear as implemented OpenAPI operations. `UserSelf.avatarUrl` remains a nullable field in the actual DTO; profile projection can construct `/api/v1/users/{id}/avatar` for an existing stored object key, but this backend has no downloader at that URL. This gap is explicit rather than represented by a fictional endpoint. Account deletion still schedules deletion of a stored avatar object if one exists.

Evidence: `api/ProfileRoutes.kt`, `api/Routing.kt`, `auth/ProfileService.kt`, `accounts/AccountDeletionService.kt`; no avatar route in `src/main/kotlin`.

## Privacy publication is local-draft only

M0 describes an active published policy as a registration prerequisite. The current backend only publishes a local development draft behind `APP_ENV=local` and `AUTH_DRAFT_POLICY_ENABLED=true`, with loopback host and public URL. Outside that mode, both policy routes and registration return `503 SERVICE_UNAVAILABLE`. OpenAPI documents this reachable refusal, including its response body. A production policy publisher is not implemented.

Evidence: `auth/DraftPrivacyPolicy.kt`, `config/ServerConfig.kt`, `api/Routing.kt`, `auth/Registration.kt`; `auth/DraftPrivacyRouteTest.kt` and `UnpublishedPrivacyDatabaseIntegrationTest.kt`.

## Push-device registration does not call the push provider

The authenticated `PUT /api/v1/me/push-devices/{installationId}` returns `503 PUSH_REGISTRATION_FAILED` when the backend has no encryption key. With a configured key it validates the platform/provider pair and token syntax, encrypts the token and checks local uniqueness. It returns `409 DEVICE_TOKEN_IN_USE` for a duplicate active token and `409 STALE_STATE` for an older session or a previously replaced token. Calls to RuStore/APNs are deferred until the provider adapters and push worker are wired into the server runtime.

Evidence includes `api/DeviceRoutes.kt`, `devices/DeviceService.kt`, `jobs/PushDeliveryWorker.kt`, `api/DeviceRegistrationSecurityIntegrationTest.kt`, and `api/PushTokenEncryptionIntegrationTest.kt`.

## Transport limits and notification location add implementation detail

M0's common error list is not exhaustive. Every strict-JSON request has a 65,536-byte limit (`413 INVALID_FIELD`), malformed JSON/UTF-8 gives `400 INVALID_JSON`, and invalid/missing/unknown DTO fields give `422 INVALID_FIELD`. All encoded error responses explicitly contain nullable `details` and `retryAfterSeconds`. Unexpected exceptions map to `500 INTERNAL_ERROR`, including database failures; they are not automatically `503`.

Notification-location writes additionally return `403 POLICY_NOT_ACCEPTED` when the account has not accepted the configured policy version, or `503 SERVICE_UNAVAILABLE` if that version is unavailable. Device mutations use `400 INVALID_FIELD` for an invalid path UUID. These existing outcomes are included per operation.

An `Accept` header excluding `application/json` makes Ktor return an empty `406 Not Acceptable` when serializing a JSON success or error; even an authentication error can be replaced by this response. It has no JSON error envelope or `error.code`. This was verified against the running backend and is listed per operation. Successful `204` and explicitly rendered text/Markdown responses are unaffected.

Evidence: `api/StrictJsonRequest.kt`, `Transport.kt`, `ErrorMapper.kt`, `NotificationRoutes.kt`, `notifications/NotificationService.kt`; `api/NotificationRoutesIntegrationTest.kt` and auth transport tests.

## Existing notification-location route wiring corrected

The service supports the agreed location write contract, and its integration tests construct it with an explicit accepted privacy version. Production wiring previously omitted `locationPrivacyVersion`, leaving it null and making every location write return 503 even in configured local draft mode. `Server.kt` now passes `draftPolicy?.version` to `NotificationService`, matching the policy supplied to registration. Accepted local draft accounts can use the existing location route when extra notifications are enabled; without a published policy the route still deliberately returns 503. No new endpoint or policy bypass was added. Existing `NotificationRoutesIntegrationTest` covers successful location writes and disabled-channel validation; no source-text test was added for a constructor argument.

## Existing point lifecycle WebSocket events corrected

`point.changed` existed in the event enum but had no publication call. The post-commit membership publisher now reads the retained point's committed `updatedAt` and emits `{pointId,updatedAt}` in the automatic user scope to active members across all its chats. It covers point creation, topic creation, join and leave when the point remains present. Account deletion invokes the same helper for retained chats from which membership was removed. Deleted points are skipped by that lookup and use `point.deleted`; account deletion now includes the agreed `reason:"no_members"` field in that payload. Delivery remains best effort with REST recovery and no replay. The focused `RealtimePointChangedTest` exercises real WebSocket delivery with a synthetic JDBC fixture; a real database run is reported separately by the main task.

## Scope of this reconciliation

No absent M0 product operation was implemented as part of exposing documentation. No source M0 document was overwritten. Swagger schemas and examples are the current transport contract; the explicit differences above preserve the distinction between delivered M1 behavior and future product scope.
