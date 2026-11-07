# T142. Матрица серверного MVP

Матрица связывает контракт, инвариант и исполняемый сценарий. Суффикс `IntegrationTest` означает проверку в изолированной локальной PostgreSQL/PostGIS, если класс использует `testDatabaseUrl()`. Фактические результаты прошлых прогонов сохранены в [M1](m1-server-result.md), [M2](m2-server-result.md), [M3](m3-server-execution.md), [M4](m4-server-execution.md) и [B5.1–B5.3](m5-local-acceptance.md). Матрица перечисляет случаи покрытия. Свежий набор и его результат приведены в протоколе T145.

## Служебные маршруты и доступ

Публичные GET-маршруты — `/`, `/health`, `/api/v1/legal/privacy`, `/api/v1/legal/privacy/document`, `/api/v1/topics/presets`, `/api/v1/points` и `/api/v1/points/{pointId}`. Регистрация, вход и обновление сессии (`POST /api/v1/auth/register`, `/login`, `/refresh` в пространстве `/api/v1/auth`) доступны без access JWT. Для обновления токен refresh передаётся в теле запроса. Swagger UI, OpenAPI и их ресурсы публичны. Остальные бизнес-маршруты REST и подключение WebSocket требуют действующей bearer-сессии.

- `GET /` — служебный ответ без доступа к частным данным. `ServerTest`.
- `GET /health` — готовность зависит от БД, версия схемы проверяется до приёма трафика. `HealthRouteTest`, `DatabaseBootstrapIntegrationTest`.
- `GET /api/v1/legal/privacy` — только включённая локальная metadata версии, в production черновик закрыт. `DraftPrivacyRouteTest`.
- `GET /api/v1/legal/privacy/document` — неизменяемый документ соответствует версии и контрольной сумме. `DraftPrivacyRouteTest`.
- `POST /api/v1/auth/register` — нормализация, версия согласия, уникальность и атомарное создание сессии. `RegistrationRouteTest`, `JdbcRegistrationRepositoryIntegrationTest`.
- `POST /api/v1/auth/login` — проверка хеша, одинаковый отказ для неверных учётных данных, сессия только после подтверждения. `LoginRouteTest`, `JdbcLoginRepositoryIntegrationTest`.
- `POST /api/v1/auth/refresh` — ротация refresh, повтор отзывает сессию. `RefreshRouteTest`, `JdbcRefreshRepositoryIntegrationTest`, `RefreshHistoryMigrationIntegrationTest`.
- `POST /api/v1/auth/logout` — отзыв текущего сеанса. `SessionRouteTest`, `JdbcSessionRepositoryIntegrationTest`.
- `GET /api/v1/me` — JWT и действующая сессия, собственный профиль. `SessionRouteTest`, `JdbcSessionRepositoryIntegrationTest`.

## Карта, точки и членство

- `GET /api/v1/topics/presets` — серверный каталог ключей и labels. `TopicPresetsRouteIntegrationTest`.
- `GET /api/v1/points` — публичный bbox, фильтры, устойчивые курсоры, DTO без личных полей. `ViewportFixtureIntegrationTest`, `PointMapFilterValidationIntegrationTest`, `PointFilterCursorIntegrationTest`.
- `POST /api/v1/points` — атомарный граф point/topic/chat/member, 100 м, квота и повтор с тем же ключом. `M2CreateGeocoderAcceptanceIntegrationTest`, `M2AtomicCreateFailureIntegrationTest`, `M2LostTcpCreateResponseIntegrationTest`, `PointQuotaBlockRouteIntegrationTest`.
- `GET /api/v1/points/{pointId}` — публичная карточка, темы и агрегаты. `PointRoutesIntegrationTest`, `PointDetailProjectionIntegrationTest`.
- `GET /api/v1/points/creation-context` — защищённый preflight, ближайшие точки и квота. `PointCreationContextRoutesIntegrationTest`.
- `GET /api/v1/me/point-quota` — собственные скользящие 7/30 суток и блокировка. `PointCreationContextRoutesIntegrationTest`, `PointQuotaSqlIntegrationTest`, `M2MonthlyQuotaBoundaryIntegrationTest`.
- `POST /api/v1/geocoding/reverse` — защищённый запрос, контролируемый отказ и ручной адрес. `GeocodingRouteTest`, `DaDataReverseGeocoderTest`, `M2GeocoderEdgeAcceptanceIntegrationTest`.
- `POST /api/v1/points/{pointId}/topics` — уникальность и autojoin в той же транзакции. `PointWriteSnapshotIntegrationTest`, `M2QuotaTopicFilterAcceptanceIntegrationTest`, `AddTopicAutojoinIntegrationTest`.
- `PUT /api/v1/chats/{chatId}/membership` — идемпотентное членство и системное событие. `PointChatJoinRoutesIntegrationTest`, `PointJoinLastLeaveRaceIntegrationTest`.
- `DELETE /api/v1/chats/{chatId}/membership` — уход, связанные голоса, удаление пустой точки. `PointChatLeaveRoutesIntegrationTest`, `LeaveLifecycleIntegrationTest`.

## Чат и модерация

- `GET /api/v1/me/chats` — только активные членства, стабильная страница и unread. `ChatListPaginationIntegrationTest`, `UnreadCountIntegrationTest`.
- `GET /api/v1/chats/{chatId}` — карточка только активному участнику. `ChatSummaryIntegrationTest`, `MembershipConstraintsIntegrationTest`.
- `GET /api/v1/chats/{chatId}/messages` — права и последовательность истории. `HistoryAccessIntegrationTest`, `HistoryPaginationIntegrationTest`.
- `POST /api/v1/chats/{chatId}/messages` — сохранение сообщения и seq, повтор, ограничения mute/flood/контента. `ChatWriteRoutesIntegrationTest`, `SendReplayIntegrationTest`, `ShortMessageFloodIntegrationTest`, `LongMessageFloodIntegrationTest`, `MuteSendGuardIntegrationTest`, `ProfanityFilterIntegrationTest`, `UrlFilterIntegrationTest`, `PhoneFilterIntegrationTest`.
- `GET /api/v1/chats/{chatId}/messages/search` — scoped поиск и курсор. `HistorySearchIntegrationTest`.
- `PUT /api/v1/chats/{chatId}/read-cursor` — монотонный курсор текущего участника. `ReadCursorIntegrationTest`.
- `POST /api/v1/reports` — контекст, уникальная жалоба, mute на 16-й уникальной. `ReportContextIntegrationTest`, `ReportTransactionIntegrationTest`, `MuteThresholdIntegrationTest`.

## Встречи и брони

- `GET /api/v1/chats/{chatId}/proposals` — список только участнику. `MeetingRoutesIntegrationTest`, `ProposalReadIntegrationTest`.
- `POST /api/v1/chats/{chatId}/proposals` — ноль голосов, время, запись системы и nearby атомарно. `MeetingRoutesIntegrationTest`, `ProposalEventsIntegrationTest`.
- `GET /api/v1/proposals/{proposalId}` — членство и актуальная проекция брони. `MeetingRestChainIntegrationTest`, `ProposalReadIntegrationTest`.
- `GET /api/v1/proposals/{proposalId}/votes` — права, стабильная пагинация. `VoterPageIntegrationTest`.
- `PUT /api/v1/proposals/{proposalId}/vote` — отдельный голос автора, ревизия и отмена при ≤1. `VoteWriteIntegrationTest`, `BookingCancellationPolicyIntegrationTest`.
- `POST /api/v1/proposals/{proposalId}/booking` — собственный голос, ≥2 голоса, проверка revision/overlap и повтор. `MeetingRestChainIntegrationTest`, `BookingOverlapConfirmIntegrationTest`, `MeetingConcurrencyIntegrationTest`.
- `POST /api/v1/proposals/{proposalId}/close` — только автор до начала, транзакционная отмена. `BookingAuthorCloseIntegrationTest`, `BookingCancellationEventsIntegrationTest`.

## Профиль, настройки и уведомления

- `PATCH /api/v1/me` — собственное имя и стабильный повтор. `ProfileNameRoutesIntegrationTest`.
- `PUT /api/v1/me/avatar` — безопасное изображение, атомарная ссылка и cleanup. `AvatarUploadTest`, `AvatarSwapRoutesIntegrationTest`.
- `DELETE /api/v1/me/avatar` — идемпотентное удаление ссылки и задания. `AvatarSwapRoutesIntegrationTest`.
- `GET /api/v1/users/{userId}/avatar` — владелец или общий активный чат, повторный ACL перед 304. `AvatarReadRoutesIntegrationTest`.
- `POST /api/v1/me/password` — верный старый пароль, отзыв всех access/refresh. `ProfileServiceIntegrationTest` и сквозной случай T143.
- `POST /api/v1/me/deletion` — подтверждение, отзыв, сохранение общего обезличенного контента. `AccountDeletionRoutesIntegrationTest`, `AccountDeletionPrivateCleanupIntegrationTest`, `AccountDeletionFanoutConcurrencyIntegrationTest`.
- `PUT /api/v1/me/push-devices/{installationId}` — актуальная привязка и шифрование токена. `DeviceRegistrationSecurityIntegrationTest`, `PushTokenEncryptionIntegrationTest`.
- `DELETE /api/v1/me/push-devices/{installationId}` — отзыв только действующей привязки. `DeviceRegistrationSecurityIntegrationTest`, `DeviceRoutesIntegrationTest`.
- `GET /api/v1/me/notification-settings` — личные флаги и revision. `NotificationSettingsIntegrationTest`.
- `PUT /api/v1/me/notification-settings` — CAS ревизии, отмена ожидающих jobs и очистка центра при выключении обоих extra. `NotificationSettingsIntegrationTest`, `NotificationRoutesIntegrationTest` и T134.
- `GET /api/v1/me/notifications` — видимая история адресата, keyset и публичная nearby-проекция. `NotificationRoutesIntegrationTest` и T135.
- `PUT /api/v1/me/notifications/{notificationId}/read` — первый server readAt, безопасный повтор и 404 чужому. `NotificationRoutesIntegrationTest` и T136.
- `GET /api/v1/me/notification-location` — центр только текущему владельцу с актуальным согласием. `NotificationRoutesIntegrationTest`, `NotificationServiceIntegrationTest`.
- `PUT /api/v1/me/notification-location` — валидные координаты, версия политики и включённый extra. `NotificationRoutesIntegrationTest`.
- `DELETE /api/v1/me/notification-location` — повторяемое удаление собственного центра. `NotificationRoutesIntegrationTest`.

## WebSocket, фоновые задания и границы внешней проверки

- Служебная публикация Swagger UI, OpenAPI JSON, WebSocket-контракта и статических ресурсов проверяется отдельно от 48 операций. `DocumentationRoutesTest`.

- `WS /api/v1/ws` — активный сеанс, ACL подписки, фиксация транзакции до публикации, отсутствие повторов при replay, reconnect через REST. `WebSocketSubscriptionIntegrationTest`, `WebSocketLifecycleIntegrationTest`, `MessagePublicationIntegrationTest`, `ChatWebSocketAcceptanceIntegrationTest`, `PointLifecycleRealtimeIntegrationTest`, `ProposalEventsIntegrationTest`, `BookingEventsIntegrationTest`.
- Nearby — только согласившиеся адресаты в 1 км на момент создания. В истории публичная проекция, push-only скрыт. `JdbcNotificationWriterIntegrationTest`, `ProposalEventsIntegrationTest`, `ProposalServiceIntegrationTest`.
- Напоминания — UTC-окно, логическая дедупликация, настройки и проверка актуальной брони перед I/O. `JobsIntegrationTest`, `BookingReminderCancellationIntegrationTest` и T137–T141.
- Отзыв доставки при перепривязке и смене токена — `DeviceRebindDeliveryConcurrencyIntegrationTest`, `JobsIntegrationTest`.
- Локальное медиа и протокол Object Storage — `LocalAvatarStoreTest`, `YandexAvatarStoreTest`, `AvatarSwapRoutesIntegrationTest`. Приватный бакет, KMS и проверка анонимного отказа относятся к этапам 4–5.
- RuStore/APNs — `RuStorePushSenderTest`, `ApnsPushSenderTest` проверяют протокол на тестовых ответах. Реальные устройства и отправка относятся к этапам 4–5. Публичная политика и реальный геокодинг также проверяются после переноса.

## Связь с пользовательскими потоками

- F1–F2 — публичная карта, защита записи, регистрация и жизненный цикл сессии.
- F3–F5 — создание с квотами и подтверждением 100 м, вступление, добавление темы.
- F6 — предложение, голоса, бронь, overlap и отмена.
- F7–F8 — список чатов, unread, уход и очистка связей.
- F9 — жалоба, фильтры, flood и mute.
- F10 — история, настройки, nearby, напоминания и доставка.
- F11 — REST history/delta и WS reconnect. Офлайн-кэш и мобильное поведение принимаются на клиентских этапах.
