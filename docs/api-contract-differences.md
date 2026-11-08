# API contract reconciliation

The published OpenAPI describes the routes registered by the production backend. It is not a list of every future M0 operation. The following differences were established by reading M0.T4/M0.T5, the actual Ktor routes, DTOs, services and their tests.

## Authentication: later M1 contract supersedes the M0 token model

M0.T4 §3.1 and M0.T5 §1 describe a single access JWT lasting 30 days with no refresh token. The implemented and subsequently handed-off M1 contract in [session-access.md](session-access.md) and [auth-slice-boundaries.md](auth-slice-boundaries.md) uses an access JWT (default 15 minutes) plus a rotating opaque refresh token with fixed session expiry (default 30 days). `POST /api/v1/auth/refresh` is implemented; `Session` includes `refreshToken` and `refreshExpiresAt`. Replay of a consumed refresh token revokes that session. OpenAPI follows this later delivered contract; no token behavior was changed for documentation.

Evidence: `auth/Registration.kt`, `Login.kt`, `Refresh.kt`, `SessionAccess.kt`; `auth/RegistrationRouteTest.kt`, `LoginRouteTest.kt`, `RefreshRouteTest.kt`, `SessionRouteTest.kt`, `JdbcRefreshRepositoryIntegrationTest.kt`.

## Avatar upload, removal and protected reads

T119 implements authenticated `PUT /api/v1/me/avatar` and `DELETE /api/v1/me/avatar`. PUT stores the normalized image in private storage and returns the profile's `avatarUrl`. DELETE clears that reference and returns `204`, including on a repeated request. Both mutations durably queue cleanup of the previous object. Account deletion queues its current avatar object in the account transaction.

T120 implements `GET /api/v1/users/{userId}/avatar`. Access requires a live session and an active target account. The viewer is the owner or an active member of a chat in which the target is also active. The server checks those conditions and the avatar reference on both sides of the storage read. Missing and inaccessible avatars share `404 RESOURCE_GONE`. An authorized viewer receives `503 AVATAR_STORAGE_UNAVAILABLE` for a storage failure.

Successful reads return JPEG or PNG with a strong `ETag`, `Cache-Control: private, no-cache` and `Vary: Authorization`. A matching `If-None-Match` yields an empty `304` after access is checked. Cleanup worker composition belongs to T123. Live acceptance of the dedicated private Yandex bucket and its KMS key remains an external dependency. The [T119 verification](m5-t119-avatar-swap.md) and [T120 verification](m5-t120-avatar-read.md) use PostgreSQL and the real local file adapter.

## Privacy publication requires server configuration

M0 describes an active published policy as a registration prerequisite. In production, the backend publishes a separately supplied approved document when the file, version, publication timestamp and HTTPS origin are configured. The development draft requires `APP_ENV=local`, `AUTH_DRAFT_POLICY_ENABLED=true` and a loopback host and origin. With neither publication configured, both policy routes and registration return `503 SERVICE_UNAVAILABLE` as documented by OpenAPI.

Evidence: `auth/PrivacyPolicyDocument.kt`, `auth/DraftPrivacyPolicy.kt`, `config/ServerConfig.kt`, `api/Routing.kt`, `auth/Registration.kt`, `auth/ProductionPrivacyPolicyTest.kt`, `auth/DraftPrivacyRouteTest.kt` and `auth/UnpublishedPrivacyDatabaseIntegrationTest.kt`.

## Push-device registration does not call the push provider

The authenticated `PUT /api/v1/me/push-devices/{installationId}` returns `503 PUSH_REGISTRATION_FAILED` when the backend has no encryption key. With a configured key it validates the platform/provider pair and token syntax, encrypts the token and checks local uniqueness. It returns `409 DEVICE_TOKEN_IN_USE` for a duplicate active token and `409 STALE_STATE` for an older session or a previously replaced token. Registration stores the token locally. In `APP_ENV=production`, the backend starts `PushDeliveryWorker` when an encryption key and a complete RuStore or APNs configuration are supplied. The worker sends queued notifications only for configured providers. Live delivery to devices belongs to acceptance in the target environment.

Evidence includes `api/DeviceRoutes.kt`, `devices/DeviceService.kt`, `jobs/PushDeliveryWorker.kt`, `api/DeviceRegistrationSecurityIntegrationTest.kt`, and `api/PushTokenEncryptionIntegrationTest.kt`.

## Transport limits and notification location add implementation detail

M0's common error list is not exhaustive. Every strict-JSON request has a 65,536-byte limit (`413 INVALID_FIELD`), malformed JSON/UTF-8 gives `400 INVALID_JSON`, and invalid/missing/unknown DTO fields give `422 INVALID_FIELD`. All encoded error responses explicitly contain nullable `details` and `retryAfterSeconds`. Unexpected exceptions map to `500 INTERNAL_ERROR`, including database failures; they are not automatically `503`.

Notification-location writes additionally return `403 POLICY_NOT_ACCEPTED` when the account has not accepted the configured policy version, or `503 SERVICE_UNAVAILABLE` if that version is unavailable. Device mutations use `400 INVALID_FIELD` for an invalid path UUID. These existing outcomes are included per operation.

JSON request bodies require `Content-Type: application/json` and UTF-8. A missing or incompatible type, or an explicitly unsupported charset, returns `415 UNSUPPORTED_MEDIA_TYPE` before the body is decoded.

An `Accept` header excluding `application/json` produces an empty `406 Not Acceptable` when the route needs to return JSON. Routes with a JSON mutation response check this before changing state. A refresh request rejected this way leaves the submitted token unspent. Ktor still negotiates JSON for reads and errors, including authentication errors. Successful `204` and explicitly rendered text or Markdown responses are unaffected.

Evidence: `api/StrictJsonRequest.kt`, `Transport.kt`, `ErrorMapper.kt`, `NotificationRoutes.kt`, `notifications/NotificationService.kt`; `api/NotificationRoutesIntegrationTest.kt` and auth transport tests.

## Existing notification-location route wiring corrected

The service supports the agreed location write contract, and its integration tests construct it with an explicit accepted privacy version. Production wiring previously omitted `locationPrivacyVersion`, leaving it null and making every location write return 503 even in configured local draft mode. `Server.kt` now passes `draftPolicy?.version` to `NotificationService`, matching the policy supplied to registration. Accepted local draft accounts can use the existing location route when extra notifications are enabled; without a published policy the route still deliberately returns 503. No new endpoint or policy bypass was added. Existing `NotificationRoutesIntegrationTest` covers successful location writes and disabled-channel validation; no source-text test was added for a constructor argument.

## Existing point lifecycle WebSocket events corrected

`point.changed` existed in the event enum but had no publication call. The post-commit membership publisher now reads the retained point's committed `updatedAt` and emits `{pointId,updatedAt}` in the automatic user scope to active members across all its chats. It covers point creation, topic creation, join and leave when the point remains present. Account deletion invokes the same helper for retained chats from which membership was removed. Deleted points are skipped by that lookup and use `point.deleted`; account deletion now includes the agreed `reason:"no_members"` field in that payload. Delivery remains best effort with REST recovery and no replay. The focused `RealtimePointChangedTest` exercises real WebSocket delivery with a synthetic JDBC fixture; a real database run is reported separately by the main task.

## Scope of this reconciliation

No absent M0 product operation was implemented as part of exposing documentation. No source M0 document was overwritten. Swagger schemas and examples are the current transport contract; the explicit differences above preserve the distinction between delivered M1 behavior and future product scope.
