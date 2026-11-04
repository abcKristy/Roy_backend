# Полный аудит серверного блока T057–T086

Дата: 04.11.2026. Проверенный срез: `feat/back-part-3` = `origin/feat/back-part-3` = `75f14e30fbc129d395a449e40f36c463451fe07f`.

**Первичный итог: REQUEST CHANGES. Найдено 1 подтверждённое замечание P2; исправлено 0; открыто 1.** Это итог отдельного полного аудита после интеграции всех двенадцати групп. Этап исправления начат на отдельной `fix/chat-search-validation` от опубликованного audit-коммита `f0c1eca`. **Текущее состояние после point review: найдено1, исправлено1, открыто0.** Точечное независимое ревью APPROVE; повторные регрессии48 PASS; независимый audit_closure повторно проверил единственный finding и всю fix-ветку: APPROVE.

Независимые read-only аудиторы `audit_membership_send` (T057–T071) и `audit_moderation_acceptance` (T072–T086) ранее не реализовывали и не утверждали эти задачи. Они независимо прочитали каждую карточку, родителей, применимые M0-контракты, текущий код, SQL, assertions, результаты, историю и статусы. Lead интегрировал их выводы в этот единственный файл. Аудиторы не меняли файлы/БД, не запускали тесты и не заменяли проверку ссылкой на прежние APPROVE.

## Срез, доказательства и правила чтения

- Проверенный запуск выполнен на исходниках `a5d4edc86f40d38405447cfde787ae266775d803`. Между ним и `75f14e3` изменились только финальный JSON результатов и execution ledger, без изменения исполняемого кода.
- [Безопасная машиночитаемая сводка](evidence/m3-final-verification.json) содержит точные аргументы команд, все 34 suite headers, 48 имён тестов/времён, полный mapping каждой T057–T086 к primary class и полному task commit. Ниже для читаемости приведены короткие Git IDs.
- [Execution protocol](m3-server-execution.md) содержит фактические команды/результаты, даты, исправленные замечания промежуточных ревью и двенадцать branch/integration/status ledgers.
- Lead выполнил выбранные 29 M3 integration-классов: **32 теста, PASS 2м8с, exit0, skipped/failures/errors=0**. Затем пять policy unit-классов: **16 тестов, PASS 33с, exit0, skipped/failures/errors=0**. Это 48 выполненных тестов, а не число всех возможных сценариев.
- Обязательная Gradle-задача задаёт `integration.required=true` и не разрешает skipped: `build.gradle.kts:60–70`. Защита disposable/loopback описана в `src/test/kotlin/testing/TestDatabase.kt:10–27`.
- Проверенная среда: JDK17, Gradle9.5.1, собственный контейнер `90957e770adceb5c7979cc2edef8833b27da5ba60fc8f378892a34ef3e4c1a00`, label `codex.owner=m3-chat-20261104`, только `127.0.0.1:32795`, БД `roy_m3_chat`, PostgreSQL18.6/PostGIS3.6.4, успешная schema015. После перезапуска tmpfs применены и валидированы все существующие V001–V015; Git diff показывает только добавление V015, без редактирования V001–V014.
- Аудиторы сопоставили все 34 записи и 48 имён/результатов с текущими XML; UTC timestamp instants согласованы. SHA256 всех пяти исторических versioned REST/WS/race traces совпали со сводкой. Эти traces относятся к датированным запускам задач; повтор запуска создаёт новые UUID в build-копиях и не делает исторический trace ложным.
- Ниже `main/…` означает `src/main/kotlin/…`, `test/…` — `src/test/kotlin/…`; номера строк относятся к проверенному срезу. SQL-пути указаны полностью. К1–К6 соответствуют шести acceptance checkbox карточки: fixture; независимый проход; положительный результат; отрицательный сценарий; repeat/late без лишних эффектов; фактический протокол.
- Предыдущие внутренние IDs `T062-INPUT-NUL`, `G3-DOC-NAME-BOUND`, `T079-IPV6-EDGE`, `T079-SCHEME-HOST`, `T079-HOST-LABEL`, `T079-LINK-STACK`, `T080-PROFANITY-STACK`, `G11-FIXTURE-SYSTEM-SCHEMA` относятся к ревью задач/групп. Их текущие исправления проверены; эти IDs не включаются в число находок данного полного аудита.

## T057 — M3.T1.B01 — ограничения чата и членства

Коммит `8a78bc1`. Подтверждённых замечаний нет.

- К1: отдельная UUID fixture и обязательный guarded alias: `test/api/MembershipConstraintsIntegrationTest.kt:32–39`.
- К2: реальные FK/UNIQUE и join/rejoin: `src/main/resources/db/migration/V003__point_chats.sql:20–39`, `main/points/PointWriteService.kt:163–188`.
- К3: canonical Membership, одна membership, один join SYSTEM и cursor после него: тест `:96–106`.
- К4: duplicate chat/member дают SQLSTATE23505; каскад одной точки изолирован от соседней; повтор join удалённого chat даёт404 RESOURCE_GONE без строк: `:140–175`.
- К5: repeat join сохраняет тело/snapshot; rejoin меняет joinedAt, сохраняя одну membership и один дополнительный SYSTEM: `:109–137`.
- К6: execution protocol, секция T057; final primary XML1/0/0/0. Карточка/README/checklist согласованы как локальная серверная готовность; родитель частичный.

## T058 — M3.T1.B02 — доступ к истории

Коммит `6c38b07`. Подтверждённых замечаний нет.

- К1: отдельные member/outsider/left/session fixtures: `test/api/HistoryAccessIntegrationTest.kt:23–30`.
- К2: общий isActiveMember проверяется до чтения detail/history/search: `main/chats/ChatReadService.kt:50–60,92–93,159–164`; actor берётся из session: `main/api/ChatRoutes.kt:39–46,59–61,90–91`.
- К3: member получает доступный текст: тест `:88–89`.
- К4: outsider/left получают403 NOT_MEMBER без private fields; missing/revoked auth —401: `:93–105`.
- К5: read-only repeatable-read и неизменный cursor: `main/persistence/HikariDatabaseBootstrap.kt:84–99`, тест `:109`.
- К6: execution T058, primary XML1/0/0/0; внешние учётные места согласованы, C/BG-1 открыты.

## T059 — M3.T1.B03 — история по messageSeq

Коммит `2f3ea97`. Подтверждённых замечаний нет.

- К1: отдельная fixture: `test/api/HistoryPaginationIntegrationTest.kt:23–30`.
- К2: mutually exclusive seq/ranges/limit: `main/api/ChatRoutes.kt:63–68`; chat-scoped SQL, строгие границы, sort и limit+1: `main/chats/ChatReadService.kt:55–80`.
- К3: SYSTEM между страницами, отсутствие duplicate IDs, ASC delta: тест `:95–120`.
- К4: malformed/negative/overflow/both seq дают400 INVALID_FIELD без сдвига cursor: `:129–137`.
- К5: terminal boundaries и повтор страницы воспроизводимы: `:122–128`; чтение не пишет.
- К6: execution T059, primary XML1/0/0/0; статусы согласованы.

## T060 — M3.T1.B04 — поиск истории

Коммит `2b7e5a6`. Одно замечание AUD-LOW-001 обнаружено и исправлено отдельным finding-коммитом ниже; прежний primary PASS не доказывал NUL edge case.

- К1: изолированная fixture: `test/api/HistorySearchIntegrationTest.kt:24–31`.
- К2: длина q, actor/chat/limit/query fingerprint, HMAC cursor, membership, parameterized literal substring и верхняя seq snapshot: `main/chats/ChatReadService.kt:84–125`, `main/chats/ChatCursorCodec.kt:19–35`.
- К3: только TEXT своего чата, страницы, literal `%`/`_`/`\` и Unicode: тест `:100–122`.
- К4: query/limit/actor/chat mismatch и испорченный cursor дают INVALID_CURSOR без snippets, outsider/left — NOT_MEMBER: `:126–141`. На исходном срезе валидация U+0000 была недостаточна; исправление принято point review ниже.
- К5: repeat cursor совпадает, новые сообщения не попадают в старую snapshot, read cursor не двигается: `:113–118,143–146`.
- К6: execution T060 и primary XML1/0/0/0 подтверждают существующие assertions. Локальные статусы согласованы с прежним запуском; выявленный полным аудитом finding закрыт последующим point review.

### AUD-LOW-001

- **Задача:** T060, постоянный ID M3.T1.B04.
- **Приоритет:** P2.
- **Замечание:** поиск принимает внутренний U+0000 в q и передаёт его в PostgreSQL text-параметр, превращая ожидаемый отказ в ошибку500.
- **Обоснование:** `main/api/ChatRoutes.kt:47–52` проверяет только длину3–256 и перехватывает IllegalArgumentException; `main/chats/ChatReadService.kt:86–100` не отвергает NUL и строит pattern, `:105–112` передаёт его через setString в ILIKE. SQLException становится500 INTERNAL_ERROR в `main/api/ErrorMapper.kt:40–42`. Пример пути: активный участник запрашивает `GET /api/v1/chats/{chatId}/messages/search?q=a%00b`; decoded q имеет три codepoint и проходит длину. Это нарушает валидацию поиска родителя M3-T1 и стабильные безопасные field errors M0.T5 §1.1. Идентичное ограничение PostgreSQL text/U+0000 уже фактически воспроизведено для send и записано в execution protocol, T062-INPUT-NUL. Search assertions `test/api/HistorySearchIntegrationTest.kt:123–124` проверяют длину/пустоту, но не NUL. Срез дефекта — `75f14e3`, task commit `2b7e5a6`.
- **Совет к исправлению:** отвергать U+0000 до SQL с400 INVALID_FIELD; согласовать route и application validation, сохранив текущую границу3–256. Не позволять application guard ошибочно превращаться в INVALID_CURSOR.
- **Критерий приёмки:** authenticated member запрос с q=a%00b возвращает400 INVALID_FIELD поля q, без items/snippets/эха запроса; read cursor и persisted state не меняются. Обычный Unicode-поиск, literal escaping, cursor scope и отрицательный ACL продолжают проходить. Сначала воспроизвести дефект фактическим required HTTP/DB regression на изолированной БД, затем выполнить исправленный targeted test и независимое point review.
- **Статус:** ИСПРАВЛЕНО.
- **Граница доказательства на момент выявления:** подтверждён статический текущий путь; новый HTTP/DB reproducer аудитор не запускал. Первичный полный suite48 не включает этот новый edge case. Это не утверждение о выполненном red-test.
- **Фактическое воспроизведение после публикации аудита:** на ветке `fix/chat-search-validation` от опубликованного audit-коммита `f0c1ecaff4f089fc80bc4efe4f19cbecbce358e0` сначала изменён только regression test, production оставлен исходным. Lead выполнил `./gradlew.bat integrationTest --tests com.example.api.HistorySearchIntegrationTest --offline --no-daemon --max-workers=1`: RED, exit1,1м11с. XML1 test/0 skipped/1 failure/0 errors, timestamp2026-11-04T09:16:09.700Z, suite7.215s. Новый member-запрос q=a%00b дал500 InternalServerError вместо ожидаемого400 BadRequest; assertion остановился в helper на строке95. Это фактическое HTTP/DB подтверждение finding, отдельное от первоначального статического вывода.
- **Минимальное исправление, принятое point review:** `main/api/ChatRoutes.kt:48` отвергает NUL существующим invalidChatField():400 INVALID_FIELD и безопасное сообщение «Некорректные параметры», без новых обязательных details. `main/chats/ChatReadService.kt:88` проверяет тот же запрет через require до fingerprint/SQL. Граница3–256 codepoint, Unicode-поиск и прочая обработка ошибок не менялись. `test/api/HistorySearchIntegrationTest.kt:127–151` проверяет отсутствие items/nextCursor/snippets/эха q, неизменность chat/membership/message snapshot и rate/outcome, а также прямой application вызов с IllegalArgumentException без изменения состояния.
- **Фактическая проверка исправления:** та же required integration-команда на изолированной `roy_m3_chat`, loopback32795, PostgreSQL18.6/PostGIS3.6.4, V001–V015: GREEN, exit0,1м14с. Текущий XML1 test/0 skipped/0 failures/0 errors, timestamp2026-11-04T09:19:34.143Z, suite7.021s/test7.009s. Выполнены новый NUL regression и прежние Unicode/literal escaping/cursor scope/ACL/read-cursor assertions. Независимый read-only schema_review проверил код, контракт, regression и текущий XML: APPROVE, 04.11.2026. Finding закрыт по его критерию; полное повторное ревью ветки отдельно завершено audit_closure: APPROVE.
- **Коммит исправления:** этот отдельный finding-коммит с уникальным subject `Reject NUL in chat history search`, база `f0c1ecaff4f089fc80bc4efe4f19cbecbce358e0`. Это Git-идентификация текущего коммита без невозможной самоссылки на собственный SHA; точный hash добавляется последующим closure-свидетельством.

## T061 — M3.T1.B05 — leave и пересчёт точки

Коммит `1d13f3c`. Подтверждённых замечаний нет.

- К1: fixture с mute/proposals/votes/booking и отдельный race: `test/api/LeaveLifecycleIntegrationTest.kt:35–42,208`.
- К2: один lifecycle; actor→point→chat/proposal locks, vote removal/revision/booking recalculation, left marker и общий подсчёт тем: `main/points/PointWriteService.kt:192–305`.
- К3: muted actor выходит; голоса/booking/SYSTEM корректны; другая тема сохраняет point, последний выход каскадно удаляет ресурсы: тест `:162–202`.
- К4: failure откатывает snapshot (`:152–153`), concurrent join/last leave оставляет только допустимое состояние (`:283–319`).
- К5: repeat DELETE204 не добавляет сообщений (`:179–182`); deleted chat404 согласован с `main/api/PointWriteRoutes.kt:147–158`.
- К6: execution T061, primary XML2/0/0/0; client/offline acceptance не заявлена.

## T062 — M3.T2.B01 — вход команды send

Коммит `44892a5`. Подтверждённых замечаний нет.

- К1: guarded fixture: `test/api/SendInputIntegrationTest.kt:23–31`.
- К2: session actor/path/key UUID/strict JSON/один service: `main/api/ChatWriteRoutes.kt:35–41`, `main/api/StrictJsonRequest.kt:17–33`; text guard `main/chats/ChatSendService.kt:33–35`.
- К3: trim-normalized4096-codepoint Message с правильными actor/chat/seq и единичными эффектами: тест `:117–126`.
- К4: malformed/unknown/null/type/empty/whitespace/NUL/oversized text, identifiers/auth/nonmember сохраняют snapshot: `:89–116`.
- К5: same-key возвращает прежний Message: `:127–130`. U+0001/escaped maximum и DB snapshot cap проверены `:131–171`.
- К6: execution T062, XML1/0/0/0. V015 ограничена send snapshots; актуальные4096, а не исторические2000, подтверждены `docs/database-migration-execution.md:15`. Статусы согласованы.

## T063 — M3.T2.B02 — атомарные TEXT и sequence

Коммит `d7408e9`. Подтверждённых замечаний нет.

- К1: guarded fixture `test/chats/SendTransactionIntegrationTest.kt:16–24`.
- К2: actor lock/idempotency/member/mute/flood/content, point/chat locks и повторная membership: `main/chats/ChatSendService.kt:38–65`.
- К3: concurrent send дают distinct IDs/seq1,2, timestamps/rate/cursor/result_ref/snapshot согласованы: тест `:67–86`.
- К4: SQLSTATEP0001 на outcome INSERT откатывает сообщения/seq/point/memberships/rate/outcome/notifications: `:91–116`.
- К5: retry после снятия failure создаётseq3, replay не меняет snapshot: `:122–131`.
- К6: execution T063, XML1/0/0/0. Общая atomic boundary `ChatSendService.kt:66–120`; commit-before-return `main/persistence/HikariDatabaseBootstrap.kt:69–79`. Статусы согласованы.

## T064 — M3.T2.B03 — идемпотентный send

Коммит `78352e7`. Подтверждённых замечаний нет.

- К1: fixture `test/api/SendReplayIntegrationTest.kt:27–35`.
- К2: normalized text+chat hash, actor/operation/key lookup: `main/chats/ChatSendService.kt:34–45`, `main/persistence/JdbcIdempotencyOperations.kt:39–78`.
- К3: simultaneous same-key requests дают один canonical201 и один комплект DB effects: тест `:76–97`.
- К4: другой text/chat даёт409 IDEMPOTENCY_KEY_REUSED: `:99–102`.
- К5: другой actor независим; replay после discarded response/rename/mute/read/leave не пишет; expired JWT401: `:104–150`.
- К6: execution T064, XML1/0/0/0. Discarded body честно не называется TCP fault injection. Статусы согласованы.

## T065 — M3.T2.B04 — авторизация WS subscriptions

Коммит `7a8a8c4`. Подтверждённых замечаний нет.

- К1: guarded fixture и реальные JDBC sessions: `test/realtime/WebSocketSubscriptionIntegrationTest.kt:35–42,85`.
- К2: pre-upgrade session auth `main/realtime/RealtimeRoutes.kt:46–56`; identity содержит конкретный sessionId, membership filter `main/realtime/RealtimeHub.kt:84–103`.
- К3: requestId/accepted own/rejected adjacent+absent IDs, допустимая доставка: тест `:118–126`.
- К4: missing/revoked handshake401 и чужой chat не подписывается: `:96–110`.
- К5: unsubscribe/resubscribe/leave/rejected new subscription/current-session logout4401 со surviving sibling: `:128–159`; event повторно проверяет session/membership `RealtimeHub.kt:117–130`.
- К6: execution T065, XML1/0/0/0. Durable replay/exactly-once контрактом не обещаны; статусы согласованы.

## T066 — M3.T2.B05 — chat.message.created

Коммит `f25cfea`. Подтверждённых замечаний нет.

- К1: guarded multi-user/socket fixture `test/realtime/MessagePublicationIntegrationTest.kt:32–40`.
- К2: только Created после committed service result `main/api/ChatWriteRoutes.kt:43–59`; envelope `main/realtime/RealtimeHub.kt:36–54`.
- К3: REST body равен WS payload обоим участникам, единые eventId/scope/occurredAt; отдельное соединение видит committed row: тест `:121–139`.
- К4: outcome-trigger failure500 сохраняет DB snapshot, post-failure sentinel не наблюдает success event: `:152–158,177–183`.
- К5: replay не меняет DB/не публикует второй success: `:145–147,177–183`; replay branch не публикует.
- К6: execution T066, XML1/0/0/0; bounded absence указана, статусы согласованы.

## T067 — M3.T2.B06 — heartbeat и окончание сеанса

Коммит `42f4b45`. Подтверждённых замечаний нет.

- К1: native Netty/JDK WS и JDBC sessions `test/realtime/WebSocketLifecycleIntegrationTest.kt:30–53`.
- К2: heartbeat30s/configured idle60–300s `main/realtime/RealtimeRoutes.kt:35–42`; auth watcher/command checks `:60–80`; collector/channel cleanup `:104–107`, `RealtimeHub.kt:79–81`.
- К3: logout только конкретного jti, sibling survives: тест `:79–82,100–102`; callback `main/Server.kt:61–65`.
- К4: revoked/expired close4401 без последующего marker: `:91–99`.
- К5: abrupt abort не отзывает session, reconnect работает; native ping/pong в обе стороны и close1000: `:72–78,83–90,105–106`.
- К6: execution T067, XML1/0/0/0. Timer tolerance/bounded observation не выданы за stress guarantee. Статусы согласованы.

## T068 — M3.T3.B01 — проекция своего чата

Коммит `8c13554`. Подтверждённых замечаний нет.

- К1: actor/neighbor/emptyActor, text/system/empty/left fixtures: `test/api/ChatSummaryIntegrationTest.kt:25–32`.
- К2: active actor membership filter и повторный scoped summary: `main/chats/ChatReadService.kt:25–30,171–194`; whitelist `main/api/ChatRoutes.kt:120–148`.
- К3: exact active set/address/topic/counts, безопасный SYSTEM kind/text/empty preview: тест `:100–130`; mapping `ChatReadService.kt:205–219,235–244`.
- К4: чужие/left chats и private profile values отсутствуют, emptyActor пуст: `:103,133–138`.
- К5: repeat GET тот же и cursor не меняется: `:131,142`.
- К6: execution T068, XML1/0/0/0; статусы согласованы.

## T069 — M3.T3.B02 — unread

Коммит `cdf6441`. Подтверждённых замечаний нет.

- К1: actor/recipient fixture `test/api/UnreadCountIntegrationTest.kt:25–32`.
- К2: настоящий COUNT строк `message_seq>last_read_seq`, не разность seq: `main/chats/ChatReadService.kt:175`; sender cursor в send transaction `main/chats/ChatSendService.kt:89–95`.
- К3: holes/TEXT/SYSTEM и107/108 unread; own send даёт0 автору/+1 peer: тест `:103–130`.
- К4: repeated list/detail/history/search не двигают cursor: `:106–109`.
- К5: stale read не снижает progress, replay не меняет unread, DB cursor/count согласованы: `:131–149`.
- К6: execution T069, XML1/0/0/0.99+ остаётся клиентским отображением; статусы согласованы.

## T070 — M3.T3.B03 — монотонное прочтение

Коммит `78b5609`. Подтверждённых замечаний нет.

- К1: JDBC session fixture `test/api/ReadCursorIntegrationTest.kt:26–33`.
- К2: actor/membership/chat lock/upper bound/GREATEST/canonical Membership `main/chats/ChatReadService.kt:129–153`; negative422 `main/api/ChatRoutes.kt:75–85`.
- К3: same/stale canonical; concurrent6/9 заканчивается9, затем10: тест `:127–150`.
- К4: negative/beyond/fractional/shape/JSON/auth/nonmember, left/revoked сохраняют snapshot: `:110–125,151–166`.
- К5: повторные/stale значения не понижают cursor: `:132,143–150`.
- К6: execution T070, XML1/0/0/0; статусы согласованы.

## T071 — M3.T3.B04 — страница списка чатов

Коммит `8199271`. Подтверждённых замечаний нет.

- К1: timestamps/ties/actor/neighbor/empty fixtures `test/api/ChatListPaginationIntegrationTest.kt:26–33`.
- К2: immutable created_at/id DESC keyset, actor+limit fingerprint/signed scope/limit+1: `main/chats/ChatReadService.kt:19–46`, `main/chats/ChatCursorCodec.kt:19–35`.
- К3: complete2+2+1 и limit1 без duplicate; activity не меняет порядок: тест `:109–138`.
- К4: malformed/unsigned/cross-actor/changed-limit/search cursor →400 INVALID_CURSOR без items, invalid limit→INVALID_FIELD: `:144–159`.
- К5: repeated page/terminal null, GET не меняет read progress: `:123–138,164`.
- К6: execution T071, XML1/0/0/0. Frozen membership snapshot не обещан (`:161`); статусы согласованы.

## T072 — M3.T4.B01 — контекст жалобы

Коммит `644b8c0`. Подтверждённых замечаний нет.

- К1–К2: actual session actor, self/reason guards `main/api/ReportRoutes.kt:23,28`; reporter active, target member либо TEXT-author доступного контекста `main/moderation/ModerationService.kt:63,133`; fixture `test/api/ReportContextIntegrationTest.kt`.
- К3: допустимый текущий/бывший видимый автор принимается; canonical response/DB: тест `:96–106`.
- К4: outsider/no-history/deleted/random target, outsider/left reporter, foreign/deleted chat, self/JSON/UUID/key/reason ошибки без записи: `:76–95`.
- К5: успешный replay не меняет snapshot: `:96–106`.
- К6: execution T072, primary XML1/0/0/0; task/README/checklist/родитель согласованы локально.

## T073 — M3.T4.B02 — единственная lifetime-пара

Коммит `575f1a0`. Подтверждённых замечаний нет.

- К1–К2: UNIQUE/self CHECK и context ON DELETE SET NULL при сохранении user IDs: `src/main/resources/db/migration/V009__user_reports.sql:5,8`; duplicate после доступного context guard `main/moderation/ModerationService.kt:68–69,151`.
- К3: barrier POST201/409, один report/outcome и реальный SQLSTATE23505: `test/api/ReportLifetimeIntegrationTest.kt:79–108`.
- К4: другой reason/key/context не создаёт новую пару, удаление chat сохраняет report/count: `:98–120`.
- К5: исходный replay, включая после удаления контекста, сохраняет ответ: `:98–120`.
- К6: execution T073, XML1/0/0/0; статусы согласованы.

## T074 — M3.T4.B03 — атомарная запись жалобы

Коммит `f8b1f6e`. Подтверждённых замечаний нет.

- К1–К2: hash context/target/trimmed reason, reporter/target в UUID-порядке до point/chat; report/mute/outcome одна transaction: `main/moderation/ModerationService.kt:41,46–47,73–99`; commit-before-return `main/persistence/HikariDatabaseBootstrap.kt:69`.
- К3: SQL сверяет id/reason/time/target/deadline/result_ref/snapshot/count16: `test/api/ReportTransactionIntegrationTest.kt:99–117`.
- К4: deferred constraint-trigger даёт именно COMMIT failure/HTTP500 и полный исходный count15/no mute snapshot: `:81–98`.
- К5: после снятия trigger retry canonical201; replay/mismatch без изменений: `:99–125`.
- К6: execution T074, XML1/0/0/0; статусы согласованы.

## T075 — M3.T4.B04 — порог16 и срок mute

Коммит `682bfb0`. Подтверждённых замечаний нет.

- К1–К2: lifetime count под target lock, однократный trigger при count≥16, server clock +семь суток UTC: `main/moderation/ModerationService.kt:159,169,177`.
- К3:15 без mute, сохранение15 после context delete, parallel distinct16/17 и один earliest trigger: `test/api/MuteThresholdIntegrationTest.kt:88–117`.
- К4: ровно604800секунд, duplicate/18-я не продлевают, после fixture expiry19-я не вызывает новый mute: `:121–134`.
- К5: replay не продлевает deadline: те же assertions.
- К6: execution T075, XML1/0/0/0; статусы согласованы. Реальное семидневное ожидание не заявляется.

## T076 — M3.T4.B05 — send запрет при mute

Коммит `936086d`. Подтверждённых замечаний нет.

- К1–К2: actor row lock, until.isAfter до flood/content/insert `main/chats/ChatSendService.kt:39,51–58`; safe403 MESSAGE_MUTED/mutedUntil `main/api/ChatWriteRoutes.kt:64`.
- К3: два persisted сеанса получают один deadline, free-after-deadline send проходит: `test/api/MuteSendGuardIntegrationTest.kt:85–125`.
- К4: blocked snapshot неизменен; list/detail/history/search/read/leave доступны: `:85–125`.
- К5: прежний успешный key остаётся replay без записи: `:85–99`; GET me использует persisted mute `main/persistence/JdbcSessionRepository.kt:18,149`.
- К6: execution T076, XML1/0/0/0; статусы согласованы.

## T077 — M3.T5.B01 — версия правил

Коммит `9b06fa2`. Подтверждённых замечаний нет.

- К1–К2: `chat-content-v1.0`, независимые detectors `main/chats/MessageContentPolicy.kt:11–16`; portable approved fixture и `test/chats/ContentPolicyVersionTest.kt:21`.
- К3: version/normalization/exception-set/source path/revision/approval date и deterministic corpus проверяются unit assertions.
- К4: согласованная политика не объявлена снова открытой; фактический внешний SHA256 дополнительно вычислен аудитором и совпал с approved источником.
- К5: повтор corpus/инъекции не создаёт side effects. Сам unit проверяет формат SHA, а не читает внешний source; это не скрыто.
- К6: execution T077, XML3/0/0/0, только pure unit без ложной DB acceptance; статусы согласованы.

## T078 — M3.T5.B02 — телефонный detector

Коммит `61cac87`. Подтверждённых замечаний нет.

- К1–К2: standalone numeric candidate/separators/length/prefix/short-contact context `main/chats/MessageContentPolicy.kt:78–93`; gate до inserts `main/chats/ChatSendService.kt:58`.
- К3:14 blocked/14 allowed synthetic date/time/coordinates/number/context cases `test/api/PhoneFilterIntegrationTest.kt:88–118`.
- К4: повтор отказа сохраняет snapshot; safe error и captured logs без whole rejected strings/regex/match: `:98–112`.
- К5: exact allowed text сохраняется, edited failed key принимается, successful replay без изменений: `:119–137`.
- К6: execution T078, XML1/0/0/0; captured logging не означает все внешние sinks, rate ageing не является flood acceptance. Статусы согласованы.

## T079 — M3.T5.B03 — URL и ссылки

Коммит `a897af4`. Подтверждённых замечаний нет.

- К1–К2: approved suffix set, independent explicit valid-host path, bounded masks, локальные IPv4/IPv6 без network I/O: `main/chats/MessageContentPolicy.kt:96–140`.
- К3: все suffixes/Unicode/NFC/explicit unknown suffix/email/IP/masks: `test/api/UrlFilterIntegrationTest.kt:88–127`.
- К4:60 blocked форм дважды без DB effects и безопасные errors/logs;34 allowed incomplete/invalid/split/unknown-bare формы: `:103–146`.
- К5: исходный text/seq, edited key и replay сохраняют историю/snapshot: `:128–146`.
- К6: execution T079, primary XML1/0/0/0; `test/chats/LinkBoundaryTest.kt:11` и XML3/0/0/0 проверяют maximum4096/many-label/long-explicit без прежнего stack failure. Политика/suffixes не расширены; статусы согласованы.

## T080 — M3.T5.B04 — запрещённые паттерны

Коммит `93f8e77`. Подтверждённых замечаний нет.

- К1–К2: закрытый RU/EN corpus без ML/stemming/неподтверждённого substring blacklist `main/chats/MessageContentPolicy.kt:20–33`; normalization/lookalikes `:60–74`.
- К3: whole-token/bounded masks/точное х*й/spaced letters `:35–56`;95 blocked/41 allowed HTTP форм `test/api/ProfanityFilterIntegrationTest.kt:89–130`.
- К4: approved exceptions, letter/digit boundaries каждой однословной формы `test/chats/ProfanityPolicyTest.kt:25`; max ordinary/masked/spaced `test/chats/ProfanityBoundaryTest.kt:10`.
- К5: repeated refusal без persistence, safe logs/error, edited key/replay/history проверены HTTP assertions. Текущий possessive repeat исправляет прежний stack finding, не создавая новый словарь.
- К6: execution T080, primary XML1/0/0/0, оба дополнительных unit-класса без skips/fail/errors; статусы согласованы.

## T081 — M3.T5.B05 — одно сообщение за2с

Коммит `87d8e29`. Подтверждённых замечаний нет.

- К1–К2: server transaction clock, production default PG wall time, actor lock до окна; user-only ledger и ceil Retry-After: `main/chats/ChatSendService.kt:30,39,171–194`, `main/api/ChatWriteRoutes.kt:67`.
- К3:1.999999s/2s/после2s и точные Retry-After: `test/api/ShortMessageFloodIntegrationTest.kt:98–115`.
- К4: реальные два клиента/чата не обходят one free slot; clock читается после фактического ожидания actor lock: `:118–158`.
- К5: success replay не расходует слот, rejected content не расходует rate: `:138–158`.
- К6: execution T081, XML1/0/0/0; managed boundary clock отделён от отдельно выполненных default DB clock regressions. Статусы согласованы.

## T082 — M3.T5.B06 —30 сообщений за300с

Коммит `e871c81`. Подтверждённых замечаний нет.

- К1–К2: только accepted send ledger в общей transaction `main/chats/ChatSendService.kt:69–119`; строгая accepted_at>now−300, oldest expiry/max short+long `:171–185`.
- К3:29 настоящих accepted HTTP sends; race за30-й слот даёт201/429 Retry242: `test/api/LongMessageFloodIntegrationTest.kt:116–161`.
- К4: injected commit failure сохраняет слот;31-я и rejected content не тратят budget;299.999999/300/следующий rolling release/ceil проверены: `:116–183`.
- К5: replay и новый service instance не обходят persistent ledger; итог33 accepted events: `:140–183`.
- К6: execution T082, XML1/0/0/0. Новый service instance не назван process restart; статусы согласованы.

## T083 — M3.T6.B01 — серверная fixture

Коммит `e8abbe4`. Подтверждённых замечаний нет.

- К1–К2: guarded roy_m3_chat,2points/topics/chats,19 synthetic accounts/16reporters/actual sessions/TEXT1/SYSTEM2: `test/acceptance/ChatAcceptanceFixture.kt:10–52`.
- К3: seed через production JdbcSystemMessageWriter `:45–52`; shared assertion всех9keys/expectedIDs/nulls `:99–111`; ACL и реальные16report/replay/threshold `test/acceptance/ChatAcceptanceFixtureIntegrationTest.kt:28–73`.
- К4: реальные пользовательские данные не используются; соседний namespace сохраняется.
- К5: parameterized owned-ID cleanup повторяем и сохраняет sentinel: fixture `:82–92`, integration `:78–81`.
- К6: execution T083 и G11 correction, primary XML1/0/0/0; [fixture passport](m3-server-fixture-passport.md). Старый урезанный seed не выдан за canonical PASS, текущее исправление проверено. Статусы согласованы.

## T084 — M3.T6.B02 — REST цепочка

Коммит `51f5095`. Подтверждённых замечаний нет.

- К1–К2: reusable fixture/реальные routes/sessions/request body/key/requestId/status/snapshots: `test/acceptance/ChatRestAcceptanceIntegrationTest.kt:22–65`.
- К3: join/history/canonical SYSTEM/invalid send через SQL+GET (`:67–79`); stored Message/rate/outcome/replay/delta/search/unread/read (`:80–97`).
- К4: HTTP201 подтверждён row/seq, report16/mute/leave/last-empty-point lifecycle проверены: `:99–123`.
- К5: repeated commands/replay сохраняют snapshots и следующие authoritative GET.
- К6: execution T084/correction, XML1/0/0/0; [REST cases](m3-server-rest-cases.md) и historical39-step [trace](evidence/m3-rest-chain.json), SHA согласован. Последовательный run не назван concurrency. Статусы согласованы.

## T085 — M3.T6.B03 — WS isolation/resync

Коммит `12115cb`. Подтверждённых замечаний нет.

- К1–К2: реальный local Netty/native JDK WebSocket +HTTP/JDBC: `test/acceptance/ChatWebSocketAcceptanceIntegrationTest.kt:36`.
- К3: два участника получают committed canonical Message/same eventId, соседний scope отделён: `:79–110,162–165`.
- К4: abort сохраняет session; missed4 восстановлен afterSeq3+SQL, reconnect видит future event: `:111–123`.
- К5: concrete session получает session.revoked/wire4401, второй session того же actor остаётся live: `:125–140`; eligibility `main/realtime/RealtimeHub.kt:92,122,203`, route `RealtimeRoutes.kt:59`.
- К6: execution T085, XML1/0/0/0; [WS cases](m3-server-websocket-cases.md), historical22-row [trace](evidence/m3-ws-isolation.json)/schema015/cleanup и SHA согласованы. Sentinel+250ms — bounded proof. Статусы согласованы.

## T086 — M3.T6.B04 — конкурирующие send/leave

Коммит `67939eb`. Подтверждённых замечаний нет.

- К1–К2: три actual tests, два HTTP клиента; point holder→first-command→second-command через pg_blocking_pids, distinct PIDs/оба unfinished до release: `test/acceptance/ChatSendLeaveRaceIntegrationTest.kt:21–24,65–88`.
- К3: send-first201/204; leave-first и last-member204/403 NOT_MEMBER: `:91–94`.
- К4: messages/distinctSeq/lastSeq/rate/outcomes/left и deleted point/chat/messages/membership/соседняя точка: `:95–131`. Это реальные overlapping schedules, не последовательный прогон.
- К5: replays/повторные отказы/GET history и canonical SYSTEM: `:95–131`.
- К6: execution T086, XML3/0/0/0; [race cases](m3-server-send-leave-cases.md) и три historical traces/SHA согласованы. Runtime WS absence здесь не заявляется; source подтверждает Created-only publication. Actor→point→chat: `main/chats/ChatSendService.kt:39,61–63`, `main/points/PointWriteService.kt:192–196`. Статусы согласованы.

## Межгрупповая интеграция

- Join/rejoin устанавливает cursor после собственного SYSTEM (`main/points/PointWriteService.kt:184–188`), history сохраняет старые строки, unread считает реальные строки после cursor.
- Send/leave используют согласованный actor→point→chat порядок; send повторно проверяет membership после locks. Message/sequence/read cursor/rate/notifications/outcome фиксируются одной транзакцией. Последовательности и last-member deletion проверены тремя действительными lock schedules.
- Report блокирует target user; send блокирует того же actor user. Threshold/mute и accepted send сериализуются, не используют unlocked stale mute-read.
- Сохранённый success replay возвращается до повторных mute/flood/content/member gates и не является новой отправкой. Отказы content не создают Message/rate/outcome; Created-only публикация идёт после service commit (`main/api/ChatWriteRoutes.kt:43–59`).
- Удаление report context не удаляет lifetime-пару; rate ledger не зависит от chat FK (`src/main/resources/db/migration/V007__messages_and_rate_events.sql:35`). Удаление чата не освобождает account window.
- Account tombstone обезличивает причины, сохраняя lifetime IDs; личный rate очищается (`main/accounts/AccountDeletionService.kt:196,229`).
- REST end-to-end, native WS resync и send/leave race assertions сопоставлены друг с другом. Историческая no-refresh формулировка M0 не перенесена на актуальные M1/session contracts. Backend — Kotlin/JVM; Compose/MVI/source-set требования не использовались как вымышленные дефекты.

## Git, учётные места и границы выводов

Lead независимо подтвердил по Git: все30 task commits достижимы из target и origin; ни один не достижим из main. Все двенадцать рабочих branch heads сохранены. На срезе main и origin/main — `2c1de31de2a5481e90e3c84e93a8abdb38f16169`; наши merge/push меняли только target. Первоначальный согласованный M3 base — `e12ec3302ae9cb74fedc59a6b7ab555742fa8e77`; main ранее двигался отдельной соседней работой, поэтому аудит не приписывает нам его чужие изменения.

Карточки/README/родители/roadmap и checklist независимо сверены; пересчитано84 checked rows из455. T057–T086 отмечены как локально принятые серверные B-компоненты, родители остаются частичными, C/BG-1/общая M3 не закрыты. AUD-LOW-001 исправлен отдельным finding-коммитом с независимым point APPROVE; итоговое независимое повторное ревью ветки audit_closure завершено APPROVE. Прежний локальный статус не считался доказательством отсутствия этого edge case.

`spec/plans/` физически находится вне backend Git. Его содержимое не включено в feat/back-part-3; точный перечень внешних обновлений для каждой группы сохранён в versioned execution ledger. Планы не переносились и не дублировались. Этот единственный audit-файл находится внутри backend Git по прямому требованию пользователя; второй копии под other/reports нет.

Выполнены: независимое чтение всех30 карточек/родителей/контрактов, code/SQL/test/commit/status mapping; сверка34 XML/48test names/UTC instants; сверка5 historical trace hashes; чтение production boundaries и межгрупповых связей. Runtime48 выполнен Lead до аудита; аудиторы его не перезапускали. На момент исходного аудиторского вывода новый NUL reproducer не выполнялся. После публикации audit-файла Lead фактически подтвердил RED500 и GREEN400; результаты и point APPROVE указаны в единственной записи AUD-LOW-001 выше.

Не выполнялись: Android/iOS, общий BG-1, deployment/external providers, load/stress, exhaustive schedules, real7-day wait, process-restart acceptance. Bounded WS observation не означает неограниченное exactly-once. Установка/disposable guard failure/пропуск теста не засчитаны PASS; прежние initialization/DB preparation failures T085 имеют отдельную атрибуцию. Эти ограничения не подменены успешной общей M3-приёмкой.

## Повторная регрессия после AUD-LOW-001

После point APPROVE Lead повторно выполнил весь тот же выбранный M3 набор на исправленных исходниках:29 integration-классов,32 теста, PASS1м38с/exit0; затем5 policy unit-классов,16 тестов, PASS33с/exit0. Все48 executed tests имеют0 skipped/failures/errors. Новый NUL regression входит в HistorySearchIntegrationTest; фактические suite headers/имена/времена сохранены в текущем `docs/evidence/m3-final-verification.json`.

Запуск относится к unpublished tested snapshot `a21376d48abadab9d3a7314a0b0fd732082b2fcd`. Единственный finding-коммит затем дополняется только audit/evidence/ledger metadata через amend, поэтому собственный commit SHA не используется как невозможная самоссылка. Сводка связывает фактически проверенный код с неизменными Git object IDs восьми executable inputs: src, gradle, build/settings/properties/wrappers и включаемый privacy resource. Эти объекты должны совпасть с окончательным finding-коммитом и целевой веткой. Предаудитная сводка отдельно доступна в Git `75f14e3:docs/evidence/m3-final-verification.json`; исторические task traces остаются датированными наблюдениями, а не подменяются свежими UUID.

Это повторные фактические регрессии после исправления, не новый запуск аудиторами. Полное независимое re-review одного finding по критерию приёмки и всей fix-ветки выполнено новым audit_closure: APPROVE на a276e2120d66dbfed367a9b0dab7f5e2e27e73b0. После этого только фиксация вердикта в metadata дополняется через amend; executable inputs не меняются. Ограничения локального сервера/BG-1/mobile/external/load/расписаний сохраняются.