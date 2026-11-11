# T149. Локальные HTTP-сценарии B6

Входной backend — `0a2ac71`, API v1, Flyway V001–V016. ID закреплены в порядке операций OpenAPI этого кандидата. Новые маршруты добавляются с новыми ID. Это программа проверки. Результаты выполнения фиксируются отдельно.

- Источники — [паспорт](baseline.md), [требования](requirements.md), [фикстуры](fixtures.md), [OpenAPI](../openapi.json) и [исполняемое покрытие T142](../mvp-backend-coverage.md).
- Поднять локальный backend и новый одноразовый PostGIS из [руководства](../../README.md). До теста сверить loopback, disposable alias и V016. Использовать G без access JWT, A владельца/автора, B участника, C вошедшего неучастника и D дальнего или без согласия по роли конкретного случая. K — chatId общего чата, P — pointId, Q — proposalId, N — notificationId, I — installationId, R — revision. UUID, cursor, ETag, текущее время UTC и версию политики получать из fixture и предыдущих ответов, а не копировать из примеров.
- Для JSON отправить `Content-Type: application/json` и `Accept: application/json`, для бинарных и текстовых ответов взять Accept из случая. Под `Bearer A` подразумевается `Authorization: Bearer <access A>`. Заголовок `Idempotency-Key` — новый UUID на логическую операцию и тот же UUID для точного повтора. Подставлять числа и JSON boolean без кавычек.
- Перед `.P` и `.N` сохранять состояние затронутых таблиц. Негативные варианты, кроме явно обозначенного replay, запускать на исходной копии fixture. Общий oracle отказа — прежние строки/счётчики, отсутствие новых business WS-событий и jobs, если ниже нет предусмотренного эффекта. Для конкуренции использовать барьеры T150.
- Для ответов с JSON-ошибкой сверять `error.code`, безопасные поля `message`, `details`, `retryAfterSeconds` и `requestId`. На `204`, `304`, `406` тела нет. Ошибка login `401 INVALID_CREDENTIALS` относится к форме входа. Защищённый `401 AUTH_REQUIRED` означает отсутствие access, `401 SESSION_EXPIRED` означает истёкшую или отозванную сессию. Соединение к недоступному локальному порту даёт сетевой отказ без HTTP-статуса. Клиент не должен превращать его в истечение сессии. Мутацию с потерянным ответом сверять по сохранённому ключу и данным БД.
- Для каждого прогона записать caseId с суффиксом `.P` или `.N`, requirementIds, SHA, Flyway, fixture alias, среду, UTC начала, исполнителя, ожидаемый и фактический HTTP/code, обезличенный requestId, строки/счётчики до и после, отчёт JUnit или локальный HTTP/DB-протокол, status и defectId. В логи и артефакты токены, пароли, номера и частный текст не сохранять. После случая удалить только данные его fixture или уничтожить собственный disposable экземпляр. `PASS` требует выполненной проверки. `NOT_RUN` означает, что запуск ещё не выполнялся, `BLOCKED` требует указания отсутствующего входа.

## Каталог 48 операций

### API-001 — `GET /api/v1/me/chats`

- `B6-CHAT-01`. Fixture — A с активным членством в K.
- Запрос — ?limit=1, затем тот же limit и выданный cursor.
- `API-001.P` — 200, только активные чаты A, без повтора на следующей странице.
- `API-001.N` — 400 INVALID_CURSOR при cursor=broken.
- БД и события — Членства, курсоры чтения и сообщения без изменений.
- Исполняемые ориентиры — `ChatListPaginationIntegrationTest`, `UnreadCountIntegrationTest`.

### API-002 — `GET /api/v1/chats/{chatId}`

- `B6-CHAT-01`. Fixture — A участник K, C не участник.
- Запрос — Подставить K в chatId.
- `API-002.P` — 200, агрегаты и членство A.
- `API-002.N` — 403 NOT_MEMBER при запросе C по K.
- БД и события — Чтение не меняет seq, членство и unread.
- Исполняемые ориентиры — `ChatSummaryIntegrationTest`, `MembershipConstraintsIntegrationTest`.

### API-003 — `GET /api/v1/chats/{chatId}/messages`

- `B6-CHAT-02`. Fixture — A участник K с историей, C не участник.
- Запрос — Подставить K, запросить ?limit=2&afterSeq=0 и продолжить с последним messageSeq.
- `API-003.P` — 200, сообщения идут по seq и принадлежат K.
- `API-003.N` — 403 NOT_MEMBER для C, 400 INVALID_FIELD при одновременных beforeSeq и afterSeq.
- БД и события — GET не обновляет lastReadMessageSeq.
- Исполняемые ориентиры — `HistoryAccessIntegrationTest`, `HistoryPaginationIntegrationTest`.

### API-004 — `POST /api/v1/chats/{chatId}/messages`

- `B6-CHAT-02`, `B6-CHAT-03`. Fixture — A участник K без mute, B активный участник.
- Запрос — K, новый Idempotency-Key, тело {"text":"Тестовая встреча в субботу"}.
- `API-004.P` — 201, id и следующий messageSeq, повтор того же запроса возвращает ту же запись.
- `API-004.N` — 403 NOT_MEMBER для C, 409 IDEMPOTENCY_KEY_REUSED при другом тексте с тем же ключом, 429 MESSAGE_FLOOD при новой отправке раньше 2 с.
- БД и события — Ровно одно TEXT-сообщение, один сдвиг seq, событие WS после commit, отказ не добавляет строк.
- Исполняемые ориентиры — `ChatWriteRoutesIntegrationTest`, `SendReplayIntegrationTest`, `ShortMessageFloodIntegrationTest`, `LongMessageFloodIntegrationTest`, `MuteSendGuardIntegrationTest`, `ProfanityFilterIntegrationTest`, `UrlFilterIntegrationTest`, `PhoneFilterIntegrationTest`.

### API-005 — `GET /api/v1/chats/{chatId}/messages/search`

- `B6-CHAT-02`. Fixture — A участник K с текстом «субботу», C не участник.
- Запрос — K, ?q=субботу&limit=1, q передавать через URL encoding.
- `API-005.P` — 200, совпадения только в K, продолжение с теми же q и limit.
- `API-005.N` — 403 NOT_MEMBER для C, 400 INVALID_CURSOR при повреждённом курсоре.
- БД и события — Поиск не изменяет историю и отметки чтения.
- Исполняемые ориентиры — `HistorySearchIntegrationTest`.

### API-006 — `PUT /api/v1/chats/{chatId}/read-cursor`

- `B6-CHAT-02`. Fixture — A участник K, K содержит сообщение с seq S.
- Запрос — K и {"lastReadMessageSeq":S}, затем повтор и запрос с S−1.
- `API-006.P` — 200, lastReadMessageSeq остаётся S.
- `API-006.N` — 422 INVALID_FIELD для S больше текущего хвоста, 403 NOT_MEMBER для C.
- БД и события — Один монотонный курсор A, чужие значения и сообщения без изменений.
- Исполняемые ориентиры — `ReadCursorIntegrationTest`.

### API-007 — `GET /api/v1/chats/{chatId}/proposals`

- `B6-MEET-01`. Fixture — A участник K с предложением Q, C не участник.
- Запрос — K, ?limit=1, затем выданный cursor с прежним limit.
- `API-007.P` — 200, только предложения K.
- `API-007.N` — 403 NOT_MEMBER для C, 400 INVALID_CURSOR для cursor=broken.
- БД и события — Ревизии, голоса и брони без изменений.
- Исполняемые ориентиры — `MeetingRoutesIntegrationTest`, `ProposalReadIntegrationTest`.

### API-008 — `POST /api/v1/chats/{chatId}/proposals`

- `B6-MEET-01`, `B6-NOTIFY-01`. Fixture — A участник K, C и D из nearby-фикстуры.
- Запрос — K, новый Idempotency-Key, {"startsAt":"<UTC now+2h>","endsAt":"<UTC now+3h>"}.
- `API-008.P` — 201, новое предложение с нулём голосов, повтор ключа возвращает тот же id.
- `API-008.N` — 422 INVALID_FIELD при endsAt=startsAt, 403 NOT_MEMBER для C.
- БД и события — Один proposal, одно системное сообщение и адресные nearby-события в транзакции, отказ не создаёт граф.
- Исполняемые ориентиры — `MeetingRoutesIntegrationTest`, `ProposalEventsIntegrationTest`.

### API-009 — `GET /api/v1/proposals/{proposalId}`

- `B6-MEET-01`, `B6-MEET-02`. Fixture — A участник K, Q существует, C не участник.
- Запрос — Подставить Q в proposalId, прочитать до и после брони.
- `API-009.P` — 200, актуальные goingCount, revision и booking.
- `API-009.N` — 403 NOT_MEMBER для C.
- БД и события — Проекция соответствует состоянию БД, GET не меняет его.
- Исполняемые ориентиры — `MeetingRestChainIntegrationTest`, `ProposalReadIntegrationTest`.

### API-010 — `GET /api/v1/proposals/{proposalId}/votes`

- `B6-MEET-01`. Fixture — A участник K, A и B проголосовали в Q.
- Запрос — Q, ?limit=1, продолжить с тем же limit и cursor.
- `API-010.P` — 200, оба голоса ровно по одному в стабильных страницах.
- `API-010.N` — 403 NOT_MEMBER для C, 400 INVALID_CURSOR при повреждённом курсоре.
- БД и события — Голоса и счётчики без изменений.
- Исполняемые ориентиры — `VoterPageIntegrationTest`.

### API-011 — `PUT /api/v1/proposals/{proposalId}/vote`

- `B6-MEET-01`, `B6-MEET-02`. Fixture — A участник K, Q открыт и ещё не начался.
- Запрос — Q и {"going":true}, повторить с true, затем снять голос через {"going":false}.
- `API-011.P` — 200, первый голос меняет счётчик, повтор не увеличивает его.
- `API-011.N` — 409 MEETING_ALREADY_STARTED для открытого Q с наступившим startsAt, 403 NOT_MEMBER для C.
- БД и события — Уникальный голос A, повтор не двигает revision, уменьшение до ≤1 отменяет активную бронь и ожидающий reminder.
- Исполняемые ориентиры — `VoteWriteIntegrationTest`, `BookingCancellationPolicyIntegrationTest`.

### API-012 — `POST /api/v1/proposals/{proposalId}/booking`

- `B6-MEET-02`. Fixture — A и B проголосовали, A бронирует Q, R — свежая revision Q.
- Запрос — Q, новый Idempotency-Key, {"expectedRevision":R,"confirmOverlap":false}.
- `API-012.P` — 201 и один bookingId, точный повтор возвращает тот же id.
- `API-012.N` — 409 STALE_STATE для устаревшей revision, 409 VOTE_REQUIRED при A без голоса и двух иных активных голосах, 409 OVERLAP_CONFIRM_REQUIRED при пересечении без согласия.
- БД и события — Одна активная бронь с серверным bookingOrder, отказ без дополнительной брони, success-события или назначения reminder.
- Исполняемые ориентиры — `MeetingRestChainIntegrationTest`, `BookingOverlapConfirmIntegrationTest`, `MeetingConcurrencyIntegrationTest`.

### API-013 — `POST /api/v1/proposals/{proposalId}/close`

- `B6-MEET-02`. Fixture — A автор Q до startsAt, B член K без авторства.
- Запрос — Q, новый Idempotency-Key, {"expectedRevision":R}, R из GET Q.
- `API-013.P` — 200, closed, точный повтор сохраняет результат.
- `API-013.N` — 403 FORBIDDEN для B, 409 MEETING_ALREADY_STARTED для A при startsAt ≤ now.
- БД и события — Закрытие, отмена активной брони, системное событие и инвалидирование ожидающих reminder атомарны.
- Исполняемые ориентиры — `BookingAuthorCloseIntegrationTest`, `BookingCancellationEventsIntegrationTest`.

### API-014 — `GET /`

- `B6-BASE-01`. Fixture — G, backend запущен.
- Запрос — Accept: text/plain.
- `API-014.P` — 200 и Hello, World!.
- `API-014.N` — 406 без тела при Accept: application/xml.
- БД и события — Нет изменений БД.
- Исполняемые ориентиры — `ServerTest`.

### API-015 — `GET /health`

- `B6-BASE-01`. Fixture — G, V016 и доступная БД.
- Запрос — Accept: application/json.
- `API-015.P` — 200 и {"status":"ok"}.
- `API-015.N` — 503 SERVICE_UNAVAILABLE при недоступной тестовой БД на уже запущенном экземпляре либо в HealthRouteTest.
- БД и события — Проверяется readiness, запись данных отсутствует.
- Исполняемые ориентиры — `HealthRouteTest`, `DatabaseBootstrapIntegrationTest`.

### API-016 — `GET /api/v1/legal/privacy`

- `B6-AUTH-01`. Fixture — G, APP_ENV=local и включён черновик.
- Запрос — Accept: application/json.
- `API-016.P` — 200 с version, publishedAt и локальным document URL.
- `API-016.N` — 503 SERVICE_UNAVAILABLE при отключённом черновике.
- БД и события — Аккаунты и согласия без изменений.
- Исполняемые ориентиры — `DraftPrivacyRouteTest`.

### API-017 — `GET /api/v1/legal/privacy/document`

- `B6-AUTH-01`. Fixture — G, документ включён, версия из API-016.
- Запрос — Accept: text/markdown.
- `API-017.P` — 200, тело соответствует версии и digest DraftPrivacyPolicy.
- `API-017.N` — 503 SERVICE_UNAVAILABLE при отключённом черновике.
- БД и события — Только выдача документа, БД без записи.
- Исполняемые ориентиры — `DraftPrivacyRouteTest`.

### API-018 — `POST /api/v1/auth/register`

- `B6-AUTH-01`. Fixture — G, новый синтетический phone, V из API-016.
- Запрос — {"phone":"<phone>","password":"<password>","displayName":"Тест А","ageYears":18,"acceptedPrivacyPolicyVersion":"<V>"}.
- `API-018.P` — 201, user и пара токенов.
- `API-018.N` — 422 PRIVACY_POLICY_ACCEPTANCE_REQUIRED со старой версией, 409 DUPLICATE_PHONE при повторном phone.
- БД и события — Одна запись пользователя, политика и серверное время согласия, настройки и первая сессия. Отказ ничего не создаёт.
- Исполняемые ориентиры — `RegistrationRouteTest`, `JdbcRegistrationRepositoryIntegrationTest`.

### API-019 — `POST /api/v1/auth/login`

- `B6-AUTH-02`. Fixture — G, аккаунт A создан и знает synthetic password.
- Запрос — {"phone":"<phone A>","password":"<password A>"}.
- `API-019.P` — 200, новая пара access/refresh.
- `API-019.N` — 401 INVALID_CREDENTIALS для неверного пароля и для неизвестного номера, одна форма ошибки.
- БД и события — Создаётся сессия только успешного входа. Пароль не хранится и не возвращается в открытом виде.
- Исполняемые ориентиры — `LoginRouteTest`, `JdbcLoginRepositoryIntegrationTest`.

### API-020 — `POST /api/v1/auth/refresh`

- `B6-AUTH-02`. Fixture — G, действующий refresh A, без access JWT.
- Запрос — {"refreshToken":"<current refresh A>"}, сохранить новый refresh только в памяти.
- `API-020.P` — 200, пара ротирована.
- `API-020.N` — 401 SESSION_EXPIRED при повторном использовании старого refresh.
- БД и события — Обновление токена оставляет одну сессию, известный replay старого отзывает её, новый access для неё становится невалидным.
- Исполняемые ориентиры — `RefreshRouteTest`, `JdbcRefreshRepositoryIntegrationTest`, `RefreshHistoryMigrationIntegrationTest`.

### API-021 — `GET /api/v1/me`

- `B6-AUTH-02`, `B6-PROFILE-01`. Fixture — A с действующей access-сессией.
- Запрос — Bearer A, без тела.
- `API-021.P` — 200, только профиль A.
- `API-021.N` — 401 AUTH_REQUIRED без bearer, 401 SESSION_EXPIRED с отозванным access.
- БД и события — Чтение не создаёт сессий и не меняет профиль.
- Исполняемые ориентиры — `SessionRouteTest`, `JdbcSessionRepositoryIntegrationTest`.

### API-022 — `PATCH /api/v1/me`

- `B6-PROFILE-01`. Fixture — A с действующей access-сессией.
- Запрос — {"displayName":"Тест Новое Имя"}.
- `API-022.P` — 200, новый displayName, повтор безопасен.
- `API-022.N` — 422 INVALID_FIELD для пустого имени.
- БД и события — Меняется только профиль A. B, credentials и авторизация A остаются прежними.
- Исполняемые ориентиры — `ProfileNameRoutesIntegrationTest`.

### API-023 — `POST /api/v1/auth/logout`

- `B6-AUTH-02`. Fixture — A с двумя действующими сессиями A1/A2.
- Запрос — Bearer A1, без тела.
- `API-023.P` — 204 без тела.
- `API-023.N` — 401 SESSION_EXPIRED при повторе с A1.
- БД и события — Отозвана A1, A2 остаётся действующей, refresh A1 не работает.
- Исполняемые ориентиры — `SessionRouteTest`, `JdbcSessionRepositoryIntegrationTest`.

### API-024 — `PUT /api/v1/me/avatar`

- `B6-PROFILE-01`. Fixture — A, локальный AvatarStore, тестовый PNG/JPEG.
- Запрос — Accept: */*, multipart/form-data с полем file, размер ≤5 МиБ.
- `API-024.P` — 200 и защищённый avatarUrl.
- `API-024.N` — 422 INVALID_FIELD для испорченного изображения, 413 INVALID_FIELD для файла >5 МиБ.
- БД и события — Меняется ссылка A, прежний объект получает cleanup job, отказ сохраняет прежний указатель.
- Исполняемые ориентиры — `AvatarUploadTest`, `AvatarSwapRoutesIntegrationTest`.

### API-025 — `DELETE /api/v1/me/avatar`

- `B6-PROFILE-01`. Fixture — A с сохранённым аватаром.
- Запрос — Bearer A, без тела, затем точный повтор.
- `API-025.P` — 204 оба раза.
- `API-025.N` — 401 AUTH_REQUIRED без bearer.
- БД и события — Ссылка A очищается, задание удаления для прежнего объекта создаётся один раз.
- Исполняемые ориентиры — `AvatarSwapRoutesIntegrationTest`.

### API-026 — `GET /api/v1/users/{userId}/avatar`

- `B6-PROFILE-01`. Fixture — A владелец, B общий активный участник, C вне общего чата.
- Запрос — userId=A, Accept: */*, затем If-None-Match с полученным ETag.
- `API-026.P` — 200 с изображением, 304 для действующего ACL и того же ETag.
- `API-026.N` — 404 RESOURCE_GONE для C даже с ETag.
- БД и события — Нет записи БД. Ответ private и без публичного пути storage.
- Исполняемые ориентиры — `AvatarReadRoutesIntegrationTest`.

### API-027 — `POST /api/v1/me/password`

- `B6-PROFILE-02`, `B6-AUTH-02`. Fixture — A, текущий пароль и две сессии.
- Запрос — {"currentPassword":"<old A>","newPassword":"<new synthetic>"}.
- `API-027.P` — 204, новый пароль подходит для login.
- `API-027.N` — 422 INVALID_CURRENT_PASSWORD со случайным старым паролем.
- БД и события — Обновляется хеш, отзываются прежние access/refresh сессии A. Отказ не отзывает действующие сессии.
- Исполняемые ориентиры — `ProfileServiceIntegrationTest` и сквозной случай T143.

### API-028 — `POST /api/v1/me/deletion`

- `B6-PROFILE-02`, `B6-AUTH-02`. Fixture — Отдельная свежая A, участие в K и личные данные.
- Запрос — {"currentPassword":"<password A>","confirm":true}.
- `API-028.P` — 204, повтор с отозванным токеном не проходит.
- `API-028.N` — 422 INVALID_FIELD с confirm=false, 422 INVALID_CURRENT_PASSWORD с неверным паролем.
- БД и события — Сессии отзываются, личные поля очищаются, shared-история обезличена, необходимые jobs сохранены. Отказ без мутаций.
- Исполняемые ориентиры — `AccountDeletionRoutesIntegrationTest`, `AccountDeletionPrivateCleanupIntegrationTest`, `AccountDeletionFanoutConcurrencyIntegrationTest`.

### API-029 — `PUT /api/v1/me/push-devices/{installationId}`

- `B6-NOTIFY-02`. Fixture — A с действующей сессией, локальный тестовый ключ шифрования, I — новая установка UUID.
- Запрос — I, {"platform":"android","provider":"rustore","token":"<synthetic opaque token>"}.
- `API-029.P` — 200, installationId и registeredAt.
- `API-029.N` — 422 INVALID_FIELD при несовместимой платформе и провайдере, 409 DEVICE_TOKEN_IN_USE для токена другой действующей установки, 503 PUSH_REGISTRATION_FAILED без ключа.
- БД и события — Одна активная привязка, токен хранится зашифрованным, отказ не переносит чужое устройство.
- Исполняемые ориентиры — `DeviceRegistrationSecurityIntegrationTest`, `PushTokenEncryptionIntegrationTest`.

### API-030 — `DELETE /api/v1/me/push-devices/{installationId}`

- `B6-NOTIFY-02`. Fixture — A владеет I, C — посторонняя сессия.
- Запрос — I, Bearer A, без тела.
- `API-030.P` — 204, повтор владельца безопасен. Запрос C по чужой I тоже 204 без изменения.
- `API-030.N` — 401 AUTH_REQUIRED без bearer. В контуре без настроенного ключа — 404 RESOURCE_GONE.
- БД и события — Привязка A отозвана, только её ожидающие push jobs отменены, чужая привязка не затронута.
- Исполняемые ориентиры — `DeviceRegistrationSecurityIntegrationTest`, `DeviceRoutesIntegrationTest`.

### API-031 — `GET /api/v1/me/notification-settings`

- `B6-NOTIFY-01`. Fixture — A с настройками, R — текущая ревизия.
- Запрос — Bearer A, без тела.
- `API-031.P` — 200, четыре флага и R.
- `API-031.N` — 401 AUTH_REQUIRED без bearer.
- БД и события — Нет обновления revision или опорной позиции.
- Исполняемые ориентиры — `NotificationSettingsIntegrationTest`.

### API-032 — `PUT /api/v1/me/notification-settings`

- `B6-NOTIFY-01`, `B6-NOTIFY-02`. Fixture — A с обоими extra, центром и ожидающими extra jobs.
- Запрос — {"remindersPushEnabled":true,"personalPushEnabled":true,"extraPushEnabled":false,"extraInAppEnabled":false,"expectedRevision":R}.
- `API-032.P` — 200, R+1 и оба extra=false.
- `API-032.N` — 409 STALE_STATE при повторе старой R, канонические настройки в details.
- БД и события — Обе настройки, удаление центра и отмена ожидающей extra-доставки атомарны, stale ничего не удаляет.
- Исполняемые ориентиры — `NotificationSettingsIntegrationTest`, `NotificationRoutesIntegrationTest` и T134.

### API-033 — `GET /api/v1/me/notifications`

- `B6-NOTIFY-01`. Fixture — A с двумя видимыми и одним push-only событием, B с собственной историей.
- Запрос — Bearer A, ?limit=1, затем cursor с тем же limit.
- `API-033.P` — 200, только visible_in_app для A без дублей.
- `API-033.N` — 400 INVALID_CURSOR с курсором A у B, 401 AUTH_REQUIRED без bearer.
- БД и события — Чтение не устанавливает readAt, push-only и история B скрыты.
- Исполняемые ориентиры — `NotificationRoutesIntegrationTest` и T135.

### API-034 — `PUT /api/v1/me/notifications/{notificationId}/read`

- `B6-NOTIFY-01`. Fixture — N — видимое непрочитанное уведомление A, C — посторонний.
- Запрос — PUT N/read от A, без тела, затем повтор.
- `API-034.P` — 200, одинаковые id и readAt в первом и повторном ответах.
- `API-034.N` — 404 RESOURCE_GONE для C или push-only id.
- БД и события — Серверный readAt записан один раз только у N, чужое и скрытое события без изменений.
- Исполняемые ориентиры — `NotificationRoutesIntegrationTest` и T136.

### API-035 — `GET /api/v1/me/notification-location`

- `B6-NOTIFY-01`. Fixture — A с актуальным согласием и центром, B с собственным центром.
- Запрос — Bearer A, затем Bearer B.
- `API-035.P` — 200, каждый видит только свою position и updatedAt, отсутствие или устаревшее согласие даёт null.
- `API-035.N` — 401 AUTH_REQUIRED без bearer.
- БД и события — GET не меняет timestamp и настройки.
- Исполняемые ориентиры — `NotificationRoutesIntegrationTest`, `NotificationServiceIntegrationTest`.

### API-036 — `PUT /api/v1/me/notification-location`

- `B6-NOTIFY-01`. Fixture — A с принятым V и включённым extra, B с устаревшим согласием.
- Запрос — {"position":{"lat":55.75,"lon":37.62}}.
- `API-036.P` — 200, сохранённая position и время сервера.
- `API-036.N` — 422 INVALID_FIELD для lat=91 или обоих extra=false, 403 POLICY_NOT_ACCEPTED для B.
- БД и события — Upsert только своего центра, старое значение заменяется без истории координат. При отказе сохранённый центр прежний.
- Исполняемые ориентиры — `NotificationRoutesIntegrationTest`.

### API-037 — `DELETE /api/v1/me/notification-location`

- `B6-NOTIFY-01`. Fixture — A с центром, B с отдельным центром.
- Запрос — Bearer A, без тела, затем повтор.
- `API-037.P` — 204 оба раза.
- `API-037.N` — 401 AUTH_REQUIRED без bearer.
- БД и события — Центр A удалён, центр B и настройки неизменны.
- Исполняемые ориентиры — `NotificationRoutesIntegrationTest`.

### API-038 — `POST /api/v1/reports`

- `B6-CHAT-03`. Fixture — A и B имеют общий активный K, B цель, C посторонний.
- Запрос — Новый Idempotency-Key и {"contextChatId":"<K>","targetUserId":"<B>","reasonText":"Синтетический случай"}.
- `API-038.P` — 201, уникальная пара A→B, точный повтор возвращает ранее сохранённый результат.
- `API-038.N` — 409 DUPLICATE_REPORT со свежим ключом для той же пары, 403 FORBIDDEN для C в K.
- БД и события — Одна жалоба пары. Для актуального сервера mute на 7 суток включает 15-я уникальная жалоба. Повтор не увеличивает счётчик. Исходная приёмка использовала порог в 16 жалоб.
- Исполняемые ориентиры — `ReportContextIntegrationTest`, `ReportTransactionIntegrationTest`, `MuteThresholdIntegrationTest`.

### API-039 — `GET /api/v1/topics/presets`

- `B6-MAP-01`. Fixture — G без access.
- Запрос — Accept: application/json.
- `API-039.P` — 200, items содержат серверные preset keys и labels.
- `API-039.N` — 406 без тела при Accept: application/xml.
- БД и события — Каталог только читается.
- Исполняемые ориентиры — `TopicPresetsRouteIntegrationTest`.

### API-040 — `GET /api/v1/points`

- `B6-MAP-01`. Fixture — G, известный городской набор P из T148.
- Запрос — ?west=37.58&south=55.73&east=37.64&north=55.77&limit=1, далее тот же bbox, фильтры и cursor.
- `API-040.P` — 200, только публичные поля точек в bbox, стабильная страница.
- `API-040.N` — 400 INVALID_BBOX при north<south, 400 INVALID_CURSOR при несовместимом курсоре.
- БД и события — Граф и личные поля не изменяются и не раскрываются.
- Исполняемые ориентиры — `ViewportFixtureIntegrationTest`, `PointMapFilterValidationIntegrationTest`, `PointFilterCursorIntegrationTest`.

### API-041 — `POST /api/v1/points`

- `B6-MAP-02`, `B6-MAP-03`. Fixture — A с запасом квоты, изолированная позиция без другой точки ≤100 м.
- Запрос — Новый Idempotency-Key и {"position":{"lat":55.7512,"lon":37.6184},"topic":{"presetKey":"football","customLabel":null},"address":"Синтетический адрес","addressSource":"manual","confirmNearby":false}.
- `API-041.P` — 201, новый pointId, первый topic/chat и membership, повтор с тем же ключом возвращает тот же id.
- `API-041.N` — 409 NEARBY_CONFIRM_REQUIRED при соседней точке и false, 409 POINT_QUOTA_EXCEEDED при первой превышающей попытке, затем 409 POINT_QUOTA_BLOCKED.
- БД и события — Успех атомарно создаёт граф и учёт создания. Предупреждение 100 м без записи графа. Превышение сохраняет terminal replay и blockedUntil на 30 суток без новой точки.
- Исполняемые ориентиры — `M2CreateGeocoderAcceptanceIntegrationTest`, `M2AtomicCreateFailureIntegrationTest`, `M2LostTcpCreateResponseIntegrationTest`, `PointQuotaBlockRouteIntegrationTest`.

### API-042 — `GET /api/v1/points/{pointId}`

- `B6-MAP-01`. Fixture — G, P существует, missing — новый UUID.
- Запрос — Подставить P или missing в pointId.
- `API-042.P` — 200, публичная карточка, агрегаты и темы.
- `API-042.N` — 404 RESOURCE_GONE для missing, 400 INVALID_FIELD для сломанного UUID.
- БД и события — GET не меняет граф и не раскрывает автора, телефон или частную историю.
- Исполняемые ориентиры — `PointRoutesIntegrationTest`, `PointDetailProjectionIntegrationTest`.

### API-043 — `GET /api/v1/points/creation-context`

- `B6-MAP-02`, `B6-MAP-03`. Fixture — A с квотой, P рядом с координатой.
- Запрос — ?lat=55.7512&lon=37.6184.
- `API-043.P` — 200, nearby с расстояниями ≤100 м и quota текущего A.
- `API-043.N` — 400 INVALID_BBOX для lat=91.
- БД и события — Read-only preflight, точка/квота/блокировка не создаются.
- Исполняемые ориентиры — `PointCreationContextRoutesIntegrationTest`.

### API-044 — `GET /api/v1/me/point-quota`

- `B6-MAP-03`. Fixture — A с учётом созданий за 7/30 суток, B отдельный аккаунт.
- Запрос — Bearer A, без тела.
- `API-044.P` — 200, weekLimit=3, monthLimit=10, свои счётчики и blockedUntil.
- `API-044.N` — 401 AUTH_REQUIRED без bearer.
- БД и события — Чтение только квоты A, нет нового ledger или продления блокировки.
- Исполняемые ориентиры — `PointCreationContextRoutesIntegrationTest`, `PointQuotaSqlIntegrationTest`, `M2MonthlyQuotaBoundaryIntegrationTest`.

### API-045 — `POST /api/v1/geocoding/reverse`

- `B6-MAP-02`, `B6-EXT-01`. Fixture — A с локальным контролируемым адаптером, в штатном local без провайдера.
- Запрос — {"position":{"lat":55.7512,"lon":37.6184}}.
- `API-045.P` — 200 с адресом/source=provider или address=null/source=none в тестовом адаптере.
- `API-045.N` — 503 GEOCODER_UNAVAILABLE с отключённым адаптером, 422 INVALID_FIELD для lat=91.
- БД и события — Нет записи БД, ручной адрес остаётся возможным. Вызов реального провайдера относится к этапу 5.
- Исполняемые ориентиры — `GeocodingRouteTest`, `DaDataReverseGeocoderTest`, `M2GeocoderEdgeAcceptanceIntegrationTest`.

### API-046 — `POST /api/v1/points/{pointId}/topics`

- `B6-MAP-02`, `B6-CHAT-01`. Fixture — A с доступом к P, выбранного custom label ещё нет.
- Запрос — P, новый Idempotency-Key, {"topic":{"presetKey":"other","customLabel":"Тест кёрлинга"}}.
- `API-046.P` — 201, topic/chat и членство A, точный повтор сохраняет id.
- `API-046.N` — 409 DUPLICATE_TOPIC со свежим ключом и тем же нормализованным label, 404 RESOURCE_GONE для missing P.
- БД и события — Один topic/chat/membership, отказ не создаёт побочных строк.
- Исполняемые ориентиры — `PointWriteSnapshotIntegrationTest`, `M2QuotaTopicFilterAcceptanceIntegrationTest`, `AddTopicAutojoinIntegrationTest`.

### API-047 — `PUT /api/v1/chats/{chatId}/membership`

- `B6-CHAT-01`. Fixture — C вошёл, ещё не участник существующего K.
- Запрос — PUT K/membership без тела, затем повтор.
- `API-047.P` — 200, членство C и исходный joinedAt при повторе.
- `API-047.N` — 404 RESOURCE_GONE для missing K.
- БД и события — Одна активная связь и один member_joined, повтор не увеличивает агрегаты.
- Исполняемые ориентиры — `PointChatJoinRoutesIntegrationTest`, `PointJoinLastLeaveRaceIntegrationTest`.

### API-048 — `DELETE /api/v1/chats/{chatId}/membership`

- `B6-CHAT-01`, `B6-MEET-02`. Fixture — A участник K, у B отдельное активное членство, отдельная копия для последнего участника.
- Запрос — DELETE K/membership без тела, затем повтор.
- `API-048.P` — 204, уход A, B продолжает видеть K.
- `API-048.N` — 404 RESOURCE_GONE для неизвестного K.
- БД и события — Уход снимает связанные голоса, при необходимости отменяет бронь и reminder. У последнего участника удаляется пустая точка.
- Исполняемые ориентиры — `PointChatLeaveRoutesIntegrationTest`, `LeaveLifecycleIntegrationTest`.
