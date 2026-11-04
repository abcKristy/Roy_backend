# WebSocket API v1

URL локально: `ws://127.0.0.1:8080/api/v1/ws`. На развёрнутом сервере замените origin на публичный адрес и используйте `wss://<host>/api/v1/ws`. Это WebSocket upgrade, не REST-операция OpenAPI. Swagger UI Try it out применим только к REST.

Перед upgrade клиент передаёт `Authorization: Bearer <accessToken>`. Это placeholder, не токен. JWT из login/register/refresh проверяется вместе с записью сессии в БД. Отсутствующий header даёт HTTP 401 `AUTH_REQUIRED`; неверный формат, JWT, срок, authVersion, отозванный сеанс или удалённый аккаунт — HTTP 401 `SESSION_EXPIRED`. Непредвиденная ошибка до upgrade даёт HTTP 500 `INTERNAL_ERROR`. Формат HTTP-ошибки такой же, как REST: `{"error":{"code":"AUTH_REQUIRED","message":"Требуется вход в аккаунт","details":null,"retryAfterSeconds":null},"requestId":"77777777-7777-4777-8777-777777777777"}`. После успешного upgrade HTTP-ответы больше не используются.

Нативный WebSocket API браузера не позволяет произвольный Authorization header. Используйте мобильный/серверный WS-клиент с поддержкой заголовков; передача JWT через URL/query/subprotocol сервером не реализована. Токены не помещают в ссылку.

## Управление соединением

Все примеры ниже синтетические. JSON text frames, UTF-8, версия `v:1`. Сервер начинает с:

```json
{"v":1,"type":"hello","serverTime":"2027-02-08T12:00:00Z","heartbeatSeconds":30}
```

Пользовательский поток подписан автоматически для userId сеанса. На чаты клиент подписывается явно:

```json
{"v":1,"type":"subscribe","requestId":"11111111-1111-4111-8111-111111111111","chatIds":["22222222-2222-4222-8222-222222222222"]}
```

```json
{"v":1,"type":"subscribed","requestId":"11111111-1111-4111-8111-111111111111","acceptedChatIds":["22222222-2222-4222-8222-222222222222"],"rejectedChatIds":[]}
```

Активное членство проверяется по каждому ID; отсутствующий или чужой чат попадает в rejectedChatIds без раскрытия существования. Subscribe дедуплицирует ID и дополняет текущий набор. На одну команду максимум 100 chatIds (пустой массив разрешён), суммарный лимит подписок кодом не установлен. UUID команд должны быть в каноническом lowercase формате. requestId обязателен и должен быть UUID; массив chatIds и v=1 обязательны. Неизвестные поля команды текущий parser игнорирует.

```json
{"v":1,"type":"unsubscribe","requestId":"11111111-1111-4111-8111-111111111111","chatIds":["22222222-2222-4222-8222-222222222222"]}
```

```json
{"v":1,"type":"unsubscribed","requestId":"11111111-1111-4111-8111-111111111111","chatIds":["22222222-2222-4222-8222-222222222222"]}
```

Unsubscribe возвращает переданный массив, в том числе повторы, и удаляет эти ID из набора. Неверный JSON/версия/UUID/массив/тип команды, более 100 ID или команда сверх 16 KiB возвращают без закрытия:

```json
{"v":1,"type":"error","code":"INVALID_FIELD","requestId":null}
```

Если requestId удалось распознать, вместо null сервер отражает его. Это WS-ошибка, без REST envelope. Максимум transport frame 16 KiB; слишком большой frame может быть отклонён WebSocket транспортом до parser. Бинарные frames приложение игнорирует. Команды не выполняют бизнес-мутации: сообщения, голоса и брони идут через REST.

Heartbeat — нативный WS Ping/Pong каждые 30 секунд. Клиент отвечает Pong средствами WS-библиотеки. Idle timeout по умолчанию 60 секунд (настраивается при установке плагина в пределах 60–300 секунд). JSON ping-команды нет. Проверка сеанса выполняется каждые 30 секунд, перед обработкой text frame и перед доставкой события. Утрата членства прекращает доставку чата и удаляет подписку при следующей проверке; повторное вступление требует новой subscribe.

## События

Общий envelope: `v`=1, `eventId` UUID, `type` строка, `scope` с `kind:chat|user` и UUID `id`, `occurredAt` UTC instant, `payload` объект. Пример:

```json
{"v":1,"eventId":"33333333-3333-4333-8333-333333333333","type":"chat.message.created","scope":{"kind":"chat","id":"22222222-2222-4222-8222-222222222222"},"occurredAt":"2027-02-08T12:00:00Z","payload":{"id":"44444444-4444-4444-8444-444444444444","chatId":"22222222-2222-4222-8222-222222222222","messageSeq":41,"type":"text","author":{"id":"11111111-1111-4111-8111-111111111111","displayName":"Тестовый участник","avatarUrl":null},"text":"Встречаемся в субботу?","system":null,"createdAt":"2027-02-08T12:00:00Z"}}
```

`chat.message.created` имеет scope chat и payload `ChatMessage` из `/openapi.json`: text и system варианты, включая nullable автора удалённого пользователя. Системное сообщение имеет author/text null и system с обязательными nullable полями:

```json
{"id":"44444444-4444-4444-8444-444444444444","chatId":"22222222-2222-4222-8222-222222222222","messageSeq":42,"type":"system","author":null,"text":null,"system":{"kind":"meeting_proposed","actorUserId":"11111111-1111-4111-8111-111111111111","pointId":"33333333-3333-4333-8333-333333333333","chatId":"22222222-2222-4222-8222-222222222222","proposalId":"55555555-5555-4555-8555-555555555555","bookingId":null,"startsAt":"2027-02-10T15:00:00Z","endsAt":"2027-02-10T16:00:00Z","reason":null},"createdAt":"2027-02-08T12:00:00Z"}
```

system.kind: meeting_proposed, meeting_ready, meeting_booked, meeting_booking_cancelled, meeting_closed, member_joined, member_left, point_deleted. reason: insufficient_votes, point_deleted, closed_by_author или null. Все ID/времена system, кроме pointId/chatId, nullable, ключи присутствуют.

`chat.membership.changed` публикуется с scope chat, а также user для затронутого пользователя; action joined|left. Payload:

```json
{"chatId":"22222222-2222-4222-8222-222222222222","userId":"11111111-1111-4111-8111-111111111111","participantCount":2,"action":"joined"}
```

`point.deleted` — scope user для бывших участников при удалении опустевшей точки; reason no_members. Payload:

```json
{"pointId":"33333333-3333-4333-8333-333333333333","reason":"no_members"}
```

`proposal.updated` — scope chat; без персонального viewerGoing. status voting|ready_to_book|booked|closed; bookingId nullable. Payload:

```json
{"proposalId":"55555555-5555-4555-8555-555555555555","revision":3,"status":"booked","goingCount":2,"bookingId":"66666666-6666-4666-8666-666666666666"}
```

`booking.changed` — scope chat; status active|cancelled; cancelReason null|insufficient_votes|point_deleted|closed_by_author. Payload:

```json
{"proposalId":"55555555-5555-4555-8555-555555555555","bookingId":"66666666-6666-4666-8666-666666666666","status":"cancelled","cancelReason":"insufficient_votes"}
```

`notification.created` — scope user, payload совпадает с REST Notification. kind reminder_1h|chat_message|proposal_vote|proposal_ready|booking_created|booking_cancelled|nearby_meeting. Все target ID nullable; для nearby_meeting chatId/proposalId намеренно null. readAt nullable. Пример payload:

```json
{"id":"77777777-7777-4777-8777-777777777777","kind":"chat_message","title":"Новое сообщение","body":"В чате появилось сообщение","target":{"pointId":"33333333-3333-4333-8333-333333333333","chatId":"22222222-2222-4222-8222-222222222222","proposalId":null},"createdAt":"2027-02-08T12:00:00Z","readAt":null}
```

`session.revoked` — scope user, только сокетам конкретного sessionId, без разлогинивания остальных сеансов пользователя. Payload:

```json
{"reason":"logout"}
```

reason logout|password_changed|expired. Событие best effort перед закрытием 4401; истечение, обнаруженное watcher, может закрыть соединение без этого события.

`point.changed` — scope user для всех текущих участников всех тем точки, независимо от подписки на чат. Публикуется после создания точки/темы, нового вступления, выхода при сохранении точки и удаления аккаунта при сохранении его бывших чатов. Удалённая точка вместо этого имеет `point.deleted`; идемпотентный replay и повторное вступление уже активного участника новых событий не создают. `updatedAt` читается из зафиксированного состояния точки. Payload:

```json
{"pointId":"33333333-3333-4333-8333-333333333333","updatedAt":"2027-02-08T12:00:00Z"}
```

Событие — сигнал перечитать REST, доставка best effort; при reconnect карта/точка всё равно восстанавливается через REST.

## Закрытие и восстановление

Код приложения 4401, reason `Session expired`: токен/сессия недействительны, истёкли или отозваны. Код 1013, reason `Slow client`: заполнена исходящая очередь клиента (64 сообщения) при публикации. Ошибки протокола, превышение frame limit и таймауты обрабатывает Ktor WebSockets; точный код закрытия транспортом бизнес-контракт не фиксирует. Обычное закрытие клиентом завершает регистрацию сокета. Временные сетевые обрывы могут не дать close frame.

Доставка после commit, best effort, без replay/ack и общей последовательности разных чатов. Возможны пропуски/дубликаты. Дедупликация сообщения по id/messageSeq; eventId позволяет дедуплицировать конкретную доставку. После reconnect — GET /api/v1/me/chats и /api/v1/me/notifications; для открытого чата — detail и messages?afterSeq=<последний подтверждённый seq>, далее nextAfterSeq до null; для встречи — GET /api/v1/proposals/{id}. Повторить subscribe на нужные чаты. Неизвестный type игнорируется с REST resync. Повтор подключения с ограниченным backoff и действующим токеном. WS не заменяет подтверждение REST-мутации.
