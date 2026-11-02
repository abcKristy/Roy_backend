# M1.T5.B02: synthetic access error fixtures

Source baseline: backend `242e06f`. The examples below reflect `SessionAccessService`, `LoginService`, `RefreshService`, `PointWriteRoutes`, `ChatRoutes`, `PointRoutes`, and `ErrorResponse`. They are contract samples, not captured HTTP responses. No token, password, account, or database value in this file is usable. Each `requestId` is illustrative; the server creates its own value for every request.

Every HTTP error body has the same envelope. `details` and `retryAfterSeconds` are present as `null` when unset. A client distinguishes failures by HTTP status and `error.code`, and may retain `requestId` for diagnostics without logging credentials.

## Guest action: authentication required

Fixture: `POST /api/v1/points` without `Authorization`. The protected create route checks the session before request fields. Expected `401 AUTH_REQUIRED`:

```json
{"error":{"code":"AUTH_REQUIRED","message":"Требуется вход в аккаунт","details":null,"retryAfterSeconds":null},"requestId":"synthetic-guest-request"}
```

The guest may open public `GET /api/v1/points` and `GET /api/v1/points/{pointId}`. A create action needs login; a UI return target remains client state, and login does not automatically replay the POST.

## Expired or revoked access session

Fixture: `GET /api/v1/me` with an invalid, expired, forged, or revoked bearer value such as `Bearer SYNTHETIC_INVALID_ACCESS` (this marker is not a JWT). Expected `401 SESSION_EXPIRED`:

```json
{"error":{"code":"SESSION_EXPIRED","message":"Сеанс истёк. Войдите снова","details":null,"retryAfterSeconds":null},"requestId":"synthetic-access-request"}
```

The client may attempt one serialized `POST /api/v1/auth/refresh` with its current opaque refresh token and replace the complete returned session snapshot on success. The access error alone does not prove that refresh has failed.

## Login form failure is distinct

Fixture: `POST /api/v1/auth/login` with an unknown phone or wrong password. Expected `401 INVALID_CREDENTIALS`, shown on the form; it is not a session-expiry event and must not trigger refresh:

```json
{"error":{"code":"INVALID_CREDENTIALS","message":"Неверный номер телефона или пароль","details":null,"retryAfterSeconds":null},"requestId":"synthetic-login-request"}
```

## Rejected refresh is definitive

Fixture: `POST /api/v1/auth/refresh` with `{"refreshToken":"SYNTHETIC_INVALID_REFRESH"}`. The marker is intentionally not a valid opaque token. Invalid, expired, replayed, and revoked refresh credentials all yield `401 SESSION_EXPIRED`:

```json
{"error":{"code":"SESSION_EXPIRED","message":"Сеанс истёк. Войдите снова","details":null,"retryAfterSeconds":null},"requestId":"synthetic-refresh-request"}
```

Only a received, definitive refresh `401 SESSION_EXPIRED` clears the stored token pair and requires login. One refresh attempt per session runs at a time: concurrent use of the old refresh token can be treated as replay and revoke the session.

## Authenticated nonmember and missing resource

Fixture: `GET /api/v1/chats/{chatId}` with a valid access session for a user who is not a member. The route asks `ChatReadService.detail` for the actor; no accessible detail yields `403 NOT_MEMBER`:

```json
{"error":{"code":"NOT_MEMBER","message":"Нет доступа к чату","details":null,"retryAfterSeconds":null},"requestId":"synthetic-nonmember-request"}
```

Fixture: public `GET /api/v1/points/{pointId}` where `{pointId}` is a well-formed UUID absent from the database. Expected `404 RESOURCE_GONE`:

```json
{"error":{"code":"RESOURCE_GONE","message":"Точка не найдена","details":null,"retryAfterSeconds":null},"requestId":"synthetic-missing-point-request"}
```

Do not infer chat membership from authentication alone. Re-read the resource and rights before a protected action after login; a missing or inaccessible resource does not authorize automatic mutation replay.

## Network failure has no HTTP error body

A connection failure or timeout before a response supplies no reliable HTTP status, `error.code`, or `requestId`. It is not a confirmed logout and must not be mapped to `SESSION_EXPIRED`. Preserve the stored token pair, present a retryable network state, and do not queue or automatically replay an online-only mutation. A lost refresh response can leave the server rotated while the client still holds the old token; the next refresh may return `401 SESSION_EXPIRED`.

These are synthetic contract examples only. Route/service source was inspected; no live API or database request was executed for this document.
