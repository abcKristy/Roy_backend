# Независимый итоговый аудит B4: T087–T114

Дата: 04.11.2026. Аудирован опубликованный код [75cf3d612cad20d81385bd10f701946379dbbb31](https://github.com/abcKristy/Roy_backend/commit/75cf3d612cad20d81385bd10f701946379dbbb31) после слияния и push всех 12 групп девяти последовательных пулов.

**Итог: APPROVE. Новых подтверждённых замечаний — 0; ОТКРЫТО — 0; ИСПРАВЛЕНО после этого аудита — 0; НЕ ПОДТВЕРДИЛОСЬ — 0.**

Независимые read-only reviewers: contracts — T087–T100 и совместимость поздних изменений; group_review — T101–T114 и межгрупповая интеграция. Оба после кодовой проверки отдельно прочитали итоговые results/manifest и все 31 сохранённый XML. Lead свёл их заключения в этот единственный файл. Reviewers не меняли код/Git, не запускали тесты или SQL. Исторические исправленные дефекты перепроверены как текущее поведение, а не записаны повторно как новые OPEN findings.

## Исполненная проверка и происхождение доказательств

- Замороженный main/origin/main: 75cf3d612cad20d81385bd10f701946379dbbb31; working tree до/после прогона чистый. Тесты выполнялись на этом опубликованном коде; последующая публикация этого аудита меняет только документацию.
- **31/31 классов PASS, 67 JUnit tests: 64 integration tests в 30 классах и 3 unit tests в одном классе; skipped/failures/errors — 0.** Все Gradle exit codes — 0; runner exit0, passed=true, fatal=null.
- UTC: 2026-11-04T17:38:58.8042039+00:00 — 2026-11-04T18:18:53.0830181+00:00; 2394.279 секунды (39м54с). Каждый XML скопирован до следующего Gradle запуска; timestamps/имя класса/число testcase проверены. Missing/stale/zero-test/skipped/отказ до тела теста не принимались за PASS.
- 27 собственных изолированных aliases в четырёх task-labelled контейнерах с проверенными исходными ID и исключительно loopback bindings 55484–55487. Никакая production/shared БД не использовалась.
- В каждой из 30 integration rows подтверждены PostgreSQL 18.6 / PostGIS 3.6.4, 15 успешных V001–V015, нулевые counts21 fixture-таблицы и отсутствие временных fault triggers/functions. Только два существующих legacy-теста оставляют synthetic fixtures: очистка выполнена явно внутри их проверенных эксклюзивных own aliases и отдельно отражена до/после. Identity gaps допустимы; sequences не сбрасывались.
- V001–V015 не менялись относительно фактической базы первого пула2f54ac0734004308aaf5e5d2f0b3a8253772c168. V010 SHA-256: D96E1DEAE94966185F3D1F07B67CD19B43EB1CD418AA7CF82C93EB2C49A00EAC.
- Manifest SHA-256: 997897308EB9B5BE217F49768C348EDFDDA7270D8676C71B06057B94D674E367.
- Итоговый results.json SHA-256: a77da579e78114911a2921c46e4f4c1ae746b7ab8a20487a6ed567fb0b7e92fb.
- Сырые 31 XML/31 logs, results.json и свежие ledgers T112(1), T113(9), T114(6) сохранены в игнорируемом рабочем каталоге `.local/m4-final-verification/run-20261104T173858804Z-0eab488f/`. Они не объявляются файлами origin/main. Версионируются этот аудит, completed execution protocol и прежние фактические task-протоколы.

C1–C6 ниже соответствуют шести пунктам раздела «Приёмка именно этого компонента» каждой карточки: fixture/входы; отдельное исполнение; основной результат; отрицательный сценарий; повтор/поздний ответ; фактический протокол. PASS означает принятую локальную серверную область, а не готовность mobile/BG-1.

## Реестр замечаний

Новых замечаний нет. Поэтому нет искусственных записей/ID или пустой ветки fix/meeting-audit-findings. Если бы замечание существовало, обязательными полями были бы уникальный ID, P0–P3, задача/постоянный M4 ID, нарушенный критерий, наблюдаемый дефект, file/line/commit, минимальное исправление, проверяемая приёмка и статус. Исторические task-review fixes находятся в соответствующих task commits и протоколах.

## T087 — M4.T1.B01

Task commit: [f841281f54ac4cbe483cfebc6a49a245cd1ee732](https://github.com/abcKristy/Roy_backend/commit/f841281f54ac4cbe483cfebc6a49a245cd1ee732). **Замечания: нет.** Тип доказательства: Реальный JDBC/schema.

- **C1 — PASS:** Отдельная JDBC fixture проверяет фактическую V010 и поля Proposal.
- **C2 — PASS:** Исполнены допустимые и запрещённые SQL-записи, обновления и удаления связанных строк.
- **C3 — PASS:** FK, UTC timestamps, end>start, revision≥0 и согласованность lifecycle/reason соблюдаются.
- **C4 — PASS:** Применённые V001–V015 не переписаны; изменение интервала отклоняется.
- **C5 — PASS:** Rollback отрицательных SQL-сценариев не оставляет строк; создание/replay отдельно подтверждены T089.
- **C6 — PASS:** Исторический протокол и свежий XML отражают реальное исполнение и очистку.

**Проверенный код/тесты:** [V010__meeting_proposals_votes_bookings.sql:1](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/resources/db/migration/V010__meeting_proposals_votes_bookings.sql#L1), [12](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/resources/db/migration/V010__meeting_proposals_votes_bookings.sql#L12), [32](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/resources/db/migration/V010__meeting_proposals_votes_bookings.sql#L32); [ProposalSchemaIntegrationTest.kt:22](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/ProposalSchemaIntegrationTest.kt#L22).

**Свежий frozen-main результат:** com.example.meetings.ProposalSchemaIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:40:32.3290000+00:00; alias roy_m4_schema, port 55484; Gradle exit 0. XML SHA-256: 092988216784e48f58f97fb4c5d94232d9839b5d5857e44cfdf83149511b9bd1.

**Границы:** Проверка схемы не заменяет transport, WS или мобильную приёмку.

## T088 — M4.T1.B02

Task commit: [bd0e71d275b6beefcbf09b560c80938e340941e2](https://github.com/abcKristy/Roy_backend/commit/bd0e71d275b6beefcbf09b560c80938e340941e2). **Замечания: нет.** Тип доказательства: Ktor testApplication + real PG + controlled Clock; SessionRepository stub.

- **C1 — PASS:** Есть реальный PostgreSQL и управляемый Clock; SessionRepository явно заменён stub.
- **C2 — PASS:** Offset нормализуется в Instant, сохранение согласовано с микросекундами, now выбирается после locks.
- **C3 — PASS:** Строгое будущее начало и положительный сохранённый интервал подтверждены, включая минимум1µs.
- **C4 — PASS:** Past/exact-now, now+1ns и схлопнувшийся после normalization интервал отклоняются.
- **C5 — PASS:** Raw-Instant hash и legacy rounded receipt сохраняют replay; эквивалентный offset не меняет бизнес-операцию.
- **C6 — PASS:** RED/fix, команды, версии и актуальное исполнение сохранены без заявления native session proof.

**Проверенный код/тесты:** [ProposalService.kt:116](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L116), [128](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L128), [146](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L146); [ProposalTimeIntegrationTest.kt:46](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/ProposalTimeIntegrationTest.kt#L46), [173](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/ProposalTimeIntegrationTest.kt#L173).

**Свежий frozen-main результат:** com.example.meetings.ProposalTimeIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:41:39.5130000+00:00; alias roy_m4_time, port 55484; Gradle exit 0. XML SHA-256: 7ac1ab27fd20e7da2411eb458ce23d6c46d3a5ea9f09b6c36c33a18235376161.

**Границы:** Это Ktor testApplication с настоящим PostgreSQL, но stub SessionRepository. Native Netty/JDBC-session цепочка дополнительно проверена T111–T114.

## T089 — M4.T1.B03

Task commit: [a8302d2d2d1b5be6a050abd72810f47487ae82b3](https://github.com/abcKristy/Roy_backend/commit/a8302d2d2d1b5be6a050abd72810f47487ae82b3). **Замечания: нет.** Тип доказательства: Реальный service/JDBC.

- **C1 — PASS:** Отдельная service/JDBC fixture содержит действующее membership и входные интервалы.
- **C2 — PASS:** Создание атомарно записывает Proposal, SYSTEM и outcome, не создавая Vote.
- **C3 — PASS:** Исходный ответ имеет count0, viewerGoing=false, voting, booking=null, revision0.
- **C4 — PASS:** Неавторизованный контекст/hash mismatch и fault после действительных uncommitted writes не дают частичного успеха.
- **C5 — PASS:** Повтор возвращает receipt без новых sequence/SYSTEM/outcome и не откатывает более новое состояние.
- **C6 — PASS:** Реальный transaction rollback и актуальный прогон документированы; literal COMMIT fault отдельно T098.

**Проверенный код/тесты:** [ProposalService.kt:128](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L128), [149](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L149), [163](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L163), [179](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L179); [ProposalCreateIntegrationTest.kt:26](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/ProposalCreateIntegrationTest.kt#L26), [80](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/ProposalCreateIntegrationTest.kt#L80).

**Свежий frozen-main результат:** com.example.meetings.ProposalCreateIntegrationTest; tests=2, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:42:52.7250000+00:00; alias roy_m4_create, port 55484; Gradle exit 0. XML SHA-256: dd6c26520aae7af728a72b4dd24b2f37c94c94d6f65ef4aca249d813db1c287b.

**Границы:** Fault callback доказывает transaction rollback; буквальный deferred COMMIT failure отдельно подтверждён T098.

## T090 — M4.T1.B04

Task commit: [a24e8793309b5ca52d3a96b75f5f8f717217825e](https://github.com/abcKristy/Roy_backend/commit/a24e8793309b5ca52d3a96b75f5f8f717217825e). **Замечания: нет.** Тип доказательства: Реальный PG service/read.

- **C1 — PASS:** Fixture включает прошлые/будущие, OPEN/closed предложения и ACTIVE booking history.
- **C2 — PASS:** Фактически пройдены страницы history, personal detail/list и выбор закрепа.
- **C3 — PASS:** Ближайший будущий OPEN pin и полная история согласованы; прошлый ACTIVE остаётся booked.
- **C4 — PASS:** Неподходящие viewer/limit/cursor/context отвергаются без раскрытия чужого состояния.
- **C5 — PASS:** Повторные чтения не мутируют данные; закрытие кандидата сдвигает pin без удаления history.
- **C6 — PASS:** Протокол различает service evidence и последующую native/reconnect приёмку.

**Проверенный код/тесты:** [ProposalService.kt:48](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L48), [78](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L78); [MeetingProjection.kt:10](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L10), [76](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L76); [ChatReadService.kt:188](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/chats/ChatReadService.kt#L188); [ProposalReadIntegrationTest.kt:27](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/ProposalReadIntegrationTest.kt#L27).

**Свежий frozen-main результат:** com.example.meetings.ProposalReadIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:44:09.7130000+00:00; alias roy_m4_read, port 55484; Gradle exit 0. XML SHA-256: 82cbb0b9997219ac4bb4e038c207e0e812711722e257149ff7ac7c49061831d6.

**Границы:** Native чтения/reconnect проверены T112. Мобильные restart/UI сценарии не объявлены пройденными.

## T091 — M4.T1.B05

Task commit: [569a8a84be98d977d4bf81189ce3036bc4d9a2e8](https://github.com/abcKristy/Roy_backend/commit/569a8a84be98d977d4bf81189ce3036bc4d9a2e8). **Замечания: нет.** Тип доказательства: Native HTTP/WS + real PG.

- **C1 — PASS:** Native HTTP/WS fixture и реальные recipient/location данные изолированы.
- **C2 — PASS:** Создание SYSTEM/recipient producer и финальный outcome gate исполняются на PostgreSQL.
- **C3 — PASS:** После commit event IDs, messageSeq и revision совпадают с persisted rows.
- **C4 — PASS:** До commit/при rollback успех не рассылается; public nearby target не раскрывает private IDs.
- **C5 — PASS:** Replay не создаёт повторных SYSTEM/events/outcomes.
- **C6 — PASS:** Фактический прогон, schema и cleanup записаны; внешняя M5 доставка не заявлена.

**Проверенный код/тесты:** [ProposalService.kt:163](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L163), [166](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L166); [JdbcNotificationRecipients.kt:31](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/persistence/JdbcNotificationRecipients.kt#L31); [NotificationService.kt:239](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/notifications/NotificationService.kt#L239); [MeetingRoutes.kt:270](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L270); [ProposalEventsIntegrationTest.kt:37](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/ProposalEventsIntegrationTest.kt#L37).

**Свежий frozen-main результат:** com.example.meetings.ProposalEventsIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:45:08.7510000+00:00; alias roy_m4_events, port 55484; Gradle exit 0. XML SHA-256: 8f59a328bff0c90d0feac1eebd902f9b928e0a76dedec594d74f9828dc31102b.

**Границы:** Bounded in-process WS и producer hooks не доказывают внешнюю M5 доставку или BG-1.

## T092 — M4.T2.B01

Task commit: [330983374d555752e1b85b4241bc29d34964cc31](https://github.com/abcKristy/Roy_backend/commit/330983374d555752e1b85b4241bc29d34964cc31). **Замечания: нет.** Тип доказательства: Native HTTP + real PG.

- **C1 — PASS:** Native fixture содержит действительные auth/session/member контексты.
- **C2 — PASS:** PUT задаёт только собственное desired going; actor берётся из auth.
- **C3 — PASS:** PK(proposal,user), корректный count и canonical Proposal без expectedRevision соблюдены.
- **C4 — PASS:** Malformed/quoted/non-Boolean/unknown-field и auth/member/gone/closed/started отказы проверены.
- **C5 — PASS:** true/true и false/false не повышают revision/effects; чужой false не удаляет чужой vote.
- **C6 — PASS:** Два реальных теста и исправленный strict Boolean подтверждены; усиленный DB-wait race отдельно T113.

**Проверенный код/тесты:** [MeetingRoutes.kt:58](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L58), [63](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L63), [154](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L154); [ProposalService.kt:185](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L185); [V010__meeting_proposals_votes_bookings.sql:45](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/resources/db/migration/V010__meeting_proposals_votes_bookings.sql#L45); [VoteWriteIntegrationTest.kt:33](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/VoteWriteIntegrationTest.kt#L33), [87](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/VoteWriteIntegrationTest.kt#L87).

**Свежий frozen-main результат:** com.example.meetings.VoteWriteIntegrationTest; tests=2, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:46:31.7270000+00:00; alias roy_m4_vote, port 55484; Gradle exit 0. XML SHA-256: d6b3526e87424a606ffe0529a0f430c3e07dbea1b879d0a1880a9982204eb434.

**Границы:** Клиентское pending UI не входит в этот серверный результат.

## T093 — M4.T2.B02

Task commit: [13ba75bdfb2152020d70326b93f73cd892f5abde](https://github.com/abcKristy/Roy_backend/commit/13ba75bdfb2152020d70326b93f73cd892f5abde). **Замечания: нет.** Тип доказательства: Native HTTP/WS + real PG.

- **C1 — PASS:** Три viewer и последовательность count0→1→2→1 заданы в реальной fixture.
- **C2 — PASS:** Native mutation/detail/list и precommit notification gate фактически исполнены.
- **C3 — PASS:** Persisted count/status/revision общие, viewerGoing персонален.
- **C4 — PASS:** До commit SQL/REST прежние и WS молчит; в общем payload нет viewerGoing.
- **C5 — PASS:** Повторные чтения и no-op не создают mutations/effects.
- **C6 — PASS:** Точные event IDs и фактические результаты сохранены с границами mobile/pending.

**Проверенный код/тесты:** [MeetingProjection.kt:10](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L10), [76](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L76); [MeetingRoutes.kt:224](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L224), [270](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L270); [VoteProjectionIntegrationTest.kt:37](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/VoteProjectionIntegrationTest.kt#L37).

**Свежий frozen-main результат:** com.example.meetings.VoteProjectionIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:47:20.4390000+00:00; alias roy_m4_vote_projection, port 55484; Gradle exit 0. XML SHA-256: 9aade22f12289cec7edca1d10f91559429fab384e852dcb12cf19941fddee309.

**Границы:** Персональные REST flags и общий WS проверены; мобильное pending UI не принималось.

## T094 — M4.T2.B03

Task commit: [fa48db960ded93ad184cb85f447fd7f10ee5f06e](https://github.com/abcKristy/Roy_backend/commit/fa48db960ded93ad184cb85f447fd7f10ee5f06e). **Замечания: нет.** Тип доказательства: Native HTTP + real PG.

- **C1 — PASS:** Fixture содержит упорядоченные voters, tie cases и действительный member.
- **C2 — PASS:** Пройдены bounded страницы с limit1..100 и подписанным cursor.
- **C3 — PASS:** Нет повторов/пропусков; UserPublic содержит только согласованные публичные поля.
- **C4 — PASS:** Tamper/wrong viewer/proposal/chat/scope/limit и приватные поля исключены.
- **C5 — PASS:** Повторный page read не меняет состояние и не создаёт effects.
- **C6 — PASS:** Native исполнение, реальные public значения и cleanup подтверждены.

**Проверенный код/тесты:** [ProposalService.kt:84](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L84); [MeetingRoutes.kt:260](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L260); [VoterPageIntegrationTest.kt:30](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/VoterPageIntegrationTest.kt#L30).

**Свежий frozen-main результат:** com.example.meetings.VoterPageIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:48:22.3390000+00:00; alias roy_m4_voters, port 55484; Gradle exit 0. XML SHA-256: cc3718b81d74fc6377df5572ba581d5690dae56bac6393956911a2c8cfb246b4.

**Границы:** Public avatar URL намеренно отличается от storage key. Мобильный voters sheet не проверялся.

## T095 — M4.T2.B04

Task commit: [08d903b4eee17186ec1a43e24879f051f0aeea5b](https://github.com/abcKristy/Roy_backend/commit/08d903b4eee17186ec1a43e24879f051f0aeea5b). **Замечания: нет.** Тип доказательства: Native HTTP/WS + real PG.

- **C1 — PASS:** Отдельная fixture задаёт threshold, повторные edges и конкурентных voters.
- **C2 — PASS:** Выполнены реальные изменения going и серия1→2→3→2→1→2.
- **C3 — PASS:** Revision меняется при mutation; каждый новый upward edge создаёт один ready SYSTEM.
- **C4 — PASS:** Fault на notification INSERT откатывает vote/revision/SYSTEM/sequence/notifications без WS.
- **C5 — PASS:** Reads/no-op не создают ready; concurrent result сравнивается по IDs/revisions без ложной гарантии порядка доставки.
- **C6 — PASS:** Реальные fault/retry/concurrent проверки и ограничения bounded WS записаны.

**Проверенный код/тесты:** [ProposalService.kt:215](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L215), [224](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L224), [255](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L255); [VoteReadinessIntegrationTest.kt:37](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/VoteReadinessIntegrationTest.kt#L37).

**Свежий frozen-main результат:** com.example.meetings.VoteReadinessIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:49:23.5710000+00:00; alias roy_m4_ready, port 55484; Gradle exit 0. XML SHA-256: 808100c5539f02366df13e17051f0ef67240f294f14c628f991383a0c4f27292.

**Границы:** Readiness относится к каждому upward edge, а не once-per-lifetime. Широкий stress/load не проводился.

## T096 — M4.T3.B01

Task commit: [67ab9a370eeec5813d68c2c2dbd7e11ac440d9ff](https://github.com/abcKristy/Roy_backend/commit/67ab9a370eeec5813d68c2c2dbd7e11ac440d9ff). **Замечания: нет.** Тип доказательства: Реальный JDBC/schema.

- **C1 — PASS:** Схема и Booking fixtures проверяются прямыми JDBC-операциями.
- **C2 — PASS:** Исполнены active insert, cancellation, rebook и owner deletion.
- **C3 — PASS:** Partial UNIQUE допускает максимум одну ACTIVE на Proposal и сохраняет cancelled history.
- **C4 — PASS:** FK/NOT NULL/CHECK/UNIQUE/GENERATED ALWAYS отклоняют недопустимые SQL.
- **C5 — PASS:** Rebook получает новый больший order, не переписывая старую строку; gaps не сбрасываются.
- **C6 — PASS:** Фактические SQLSTATE, версии и очистка описаны отдельно от application guards.

**Проверенный код/тесты:** [V010__meeting_proposals_votes_bookings.sql:55](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/resources/db/migration/V010__meeting_proposals_votes_bookings.sql#L55), [57](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/resources/db/migration/V010__meeting_proposals_votes_bookings.sql#L57), [64](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/resources/db/migration/V010__meeting_proposals_votes_bookings.sql#L64), [71](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/resources/db/migration/V010__meeting_proposals_votes_bookings.sql#L71); [BookingSchemaIntegrationTest.kt:17](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingSchemaIntegrationTest.kt#L17).

**Свежий frozen-main результат:** com.example.meetings.BookingSchemaIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:50:38.4870000+00:00; alias roy_m4_booking_schema, port 55484; Gradle exit 0. XML SHA-256: 6adddc18a8d0e891a25969239654bcd0e770660730bc155ffd8b7ac34d21633a.

**Границы:** Identity gaps допустимы и не сбрасывались. Ограничения схемы не заменяют application guards.

## T097 — M4.T3.B02

Task commit: [069c2cb535423b1330361257e2d3413a157801ac](https://github.com/abcKristy/Roy_backend/commit/069c2cb535423b1330361257e2d3413a157801ac). **Замечания: нет.** Тип доказательства: Native HTTP + real PG.

- **C1 — PASS:** Положительная fixture допускает voted booker, отличный от proposer.
- **C2 — PASS:** Каждый guard проверяется при удовлетворённых более ранних условиях.
- **C3 — PASS:** Eligible member создаёт Booking с настоящим owner; ответ и GET согласованы.
- **C4 — PASS:** Auth/member/own vote/count/revision/lifecycle/start/active и strict native Long отказы не имеют effects.
- **C5 — PASS:** Повторные guard refusals не создают Booking/SYSTEM/outcome и не расходуют успешный key.
- **C6 — PASS:** Quoted Long RED/fix и фактическая guard matrix подтверждены актуальным прогоном.

**Проверенный код/тесты:** [BookingService.kt:82](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L82), [96](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L96); [MeetingRoutes.kt:75](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L75), [81](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L81), [172](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L172); [BookingGuardsIntegrationTest.kt:29](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingGuardsIntegrationTest.kt#L29).

**Свежий frozen-main результат:** com.example.meetings.BookingGuardsIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:51:59.7560000+00:00; alias roy_m4_booking_guards, port 55484; Gradle exit 0. XML SHA-256: b532ba41fe0bcf4af0ff1849130b6ba229823966fbcd7a5cf06dd30195b78771.

**Границы:** Позднейшие strict confirmation и typed actor expiry не ослабляют guards; deployment не выполнялся.

## T098 — M4.T3.B03

Task commit: [c3744384068dc379bccfccfe9984c6b9beb7137b](https://github.com/abcKristy/Roy_backend/commit/c3744384068dc379bccfccfe9984c6b9beb7137b). **Замечания: нет.** Тип доказательства: Native HTTP/WS + real PG literal deferred COMMIT.

- **C1 — PASS:** Реальные native HTTP/WS и synthetic device делают jobs rollback непустым.
- **C2 — PASS:** Booking/revision/SYSTEM/notifications/outcome записываются в одной транзакции под point lock.
- **C3 — PASS:** 201 и события следуют за действительным connection.commit().
- **C4 — PASS:** Deferred trigger/catalog flags доказывают literal COMMIT failure500 с полным rollback и no-WS.
- **C5 — PASS:** После удаления fault тот же key успешен; replay не расходует sequence, иной proposal с key даёт409.
- **C6 — PASS:** Точные команды, actual XML, jobs/fault cleanup и ограничения publication зафиксированы.

**Проверенный код/тесты:** [BookingService.kt:82](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L82), [125](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L125); [HikariDatabaseBootstrap.kt:69](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/persistence/HikariDatabaseBootstrap.kt#L69); [JdbcIdempotencyOperations.kt:39](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/persistence/JdbcIdempotencyOperations.kt#L39); [BookingTransactionIntegrationTest.kt:35](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingTransactionIntegrationTest.kt#L35), [217](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingTransactionIntegrationTest.kt#L217).

**Свежий frozen-main результат:** com.example.meetings.BookingTransactionIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:53:12.5230000+00:00; alias roy_m4_booking_transaction, port 55484; Gradle exit 0. XML SHA-256: 7fbe5e4e05a5605aab4cda4951d9c9abe3d0d0ecad1f101e66f7b8f3be87233d.

**Границы:** Не заявляется deployment или выдуманный dev-only gate baseline. T4/T5 guards интегрированы и отдельно проверены.

## T099 — M4.T3.B04

Task commit: [43f9edccf21cc777bca0620d06b87ac7cbc8ac58](https://github.com/abcKristy/Roy_backend/commit/43f9edccf21cc777bca0620d06b87ac7cbc8ac58). **Замечания: нет.** Тип доказательства: Native HTTP + real PG.

- **C1 — PASS:** Fixture различает proposer/booker/observer и все типы прямых/непрямых conflicts.
- **C2 — PASS:** Native POST201, detail и list сверены по сохранённым данным.
- **C3 — PASS:** Canonical Booking содержит actual owner, immutable order и прямые ACTIVE overlaps.
- **C4 — PASS:** Transitive-only/adjacent/cancelled/other Point не попадают в conflicts.
- **C5 — PASS:** Cancelled history не переписывается; rebook новый ID/order; reads/replay без effects.
- **C6 — PASS:** Реальный snapshot run и schema/cleanup описаны без заявления cross-chat WS broadcast.

**Проверенный код/тесты:** [MeetingProjection.kt:76](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L76), [123](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L123), [149](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L149); [MeetingRoutes.kt:239](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L239), [251](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L251); [BookingResponseIntegrationTest.kt:31](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingResponseIntegrationTest.kt#L31).

**Свежий frozen-main результат:** com.example.meetings.BookingResponseIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:55:06.2330000+00:00; alias roy_m4_booking_response, port 55485; Gradle exit 0. XML SHA-256: a773d61b135672bfcec57942c0e4433e9bc7f5ed2b5910bd730be1227dc29273.

**Границы:** Direct-conflict DTO не разрешает рассылку private Booking IDs соседним чатам через WS.

## T100 — M4.T3.B05

Task commit: [9cf4a6746f54d8ed3fa3165d105efef1a769b8be](https://github.com/abcKristy/Roy_backend/commit/9cf4a6746f54d8ed3fa3165d105efef1a769b8be). **Замечания: нет.** Тип доказательства: Native HTTP/4WS + real PG.

- **C1 — PASS:** Четыре native WS включают target members, adjacent-only и rejected outsider.
- **C2 — PASS:** Финальный outcome INSERT удержан настоящим DB gate.
- **C3 — PASS:** Committed SYSTEM/proposal.updated/booking.changed совпадают с DB/IDs/revision/messageSeq.
- **C4 — PASS:** Precommit/COMMIT failure не публикуют успех; outsider не получает private Booking ID.
- **C5 — PASS:** Replay молчит; same-key retry создаёт одну Booking; booking.cancelled envelope не вводится.
- **C6 — PASS:** Реальные XML, bounded marker/silence и cleanup документированы без unbounded exactly-once.

**Проверенный код/тесты:** [MeetingRoutes.kt:178](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L178), [270](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L270); [RealtimeHub.kt:112](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/realtime/RealtimeHub.kt#L112); [BookingEventsIntegrationTest.kt:36](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingEventsIntegrationTest.kt#L36), [142](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingEventsIntegrationTest.kt#L142), [265](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingEventsIntegrationTest.kt#L265).

**Свежий frozen-main результат:** com.example.meetings.BookingEventsIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:56:22.1030000+00:00; alias roy_m4_booking_events, port 55485; Gradle exit 0. XML SHA-256: 5c32f618e0a78f561b71dd82565c246c94f757a1dcfc62b9518377cab00a1fcd.

**Границы:** Bounded in-process wire evidence не означает внешнюю доставку или неограниченный exactly-once.

## T101 — M4.T4.B01

Task commit: [d71a65caaa2a9c7efdeb417c341188e6439d8db4](https://github.com/abcKristy/Roy_backend/commit/d71a65caaa2a9c7efdeb417c341188e6439d8db4). **Замечания: нет.** Тип доказательства: Real JDBC service/cancellation policy.

- **C1 — PASS:** Отдельная JDBC fixture соответствует входам общего cancellation policy.
- **C2 — PASS:** Исполнены future unvote и poststart leave/account deletion.
- **C3 — PASS:** При2→1 ACTIVE отменяется, при3→2 сохраняется.
- **C4 — PASS:** Повтор recalculation не переписывает booking history и cancelledAt.
- **C5 — PASS:** Повтор и другой контекст не создают дополнительных effects.
- **C6 — PASS:** Реальный RED→GREEN, версии, команды и cleanup записаны.

**Проверенный код/тесты:** [BookingCancellationPolicy.kt:10](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingCancellationPolicy.kt#L10); [ProposalService.kt:233](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L233); [PointWriteService.kt:227](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/points/PointWriteService.kt#L227); [AccountDeletionService.kt:113](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/accounts/AccountDeletionService.kt#L113); [BookingCancellationPolicyIntegrationTest.kt:24](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationPolicyIntegrationTest.kt#L24), [43](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationPolicyIntegrationTest.kt#L43), [61](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationPolicyIntegrationTest.kt#L61).

**Свежий frozen-main результат:** com.example.meetings.BookingCancellationPolicyIntegrationTest; tests=3, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:57:39.5320000+00:00; alias roy_m4_cancel_policy, port 55486; Gradle exit 0. XML SHA-256: fb673491d09de90c3715efcefc87adb6f3437d951c7f8d5389450f6ca31dee15.

**Границы:** Потеря действительного голоса после старта отменяет бронь; TIME alone сохраняет ACTIVE. Поздний PUT vote остаётся запрещённым.

## T102 — M4.T4.B02

Task commit: [645d8422b3766aef900308d2fd91023935b7f4e7](https://github.com/abcKristy/Roy_backend/commit/645d8422b3766aef900308d2fd91023935b7f4e7). **Замечания: нет.** Тип доказательства: Native HTTP/JDBC.

- **C1 — PASS:** Изолированная native HTTP/JDBC fixture подготовлена.
- **C2 — PASS:** Фактически исполнен PUT going=false.
- **C3 — PASS:** После commit count1 и CANCELLED booking с прежней identity/history.
- **C4 — PASS:** Deferred COMMIT failure возвращает500 и восстанавливает полный SQL snapshot.
- **C5 — PASS:** Повтор false — no-op; последующие true/false сохраняют cancelled history и текущий canonical ответ.
- **C6 — PASS:** Исполнение и исправление required bookedAt/cancelledAt assertions подтверждены XML.

**Проверенный код/тесты:** [ProposalService.kt:185](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L185); [BookingCancellationUnvoteIntegrationTest.kt:29](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationUnvoteIntegrationTest.kt#L29), [61](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationUnvoteIntegrationTest.kt#L61), [64](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationUnvoteIntegrationTest.kt#L64), [203](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationUnvoteIntegrationTest.kt#L203).

**Свежий frozen-main результат:** com.example.meetings.BookingCancellationUnvoteIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:58:41.3040000+00:00; alias roy_m4_cancel_unvote, port 55486; Gradle exit 0. XML SHA-256: c25a96c34eff17496807c1096e00d80b4cf9e1272b80c70b945109e113f6fae0.

**Границы:** Это HTTP/transactional hook. Полные WS/job доказательства относятся к T104/T105.

## T103 — M4.T4.B03

Task commit: [54e2f2f3813a86085ed5a570235db1046f1a2add](https://github.com/abcKristy/Roy_backend/commit/54e2f2f3813a86085ed5a570235db1046f1a2add). **Замечания: нет.** Тип доказательства: Native API/JDBC + real password/auth.

- **C1 — PASS:** Fixture включает несколько proposals/topics и отдельную foreign Point.
- **C2 — PASS:** Исполнены membership DELETE и account deletion с password/auth проверками.
- **C3 — PASS:** Собственные votes удаляются во всех затронутых предложениях;2→1 cancel,3→2 active; sibling topic сохраняет Point.
- **C4 — PASS:** Foreign Point неизменна; COMMIT fault откатывает shared/private state.
- **C5 — PASS:** Повтор/нулевой count сохраняют timestamps/history; последнее membership корректно удаляет опустевшую Point.
- **C6 — PASS:** Два native сценария, версии, cleanup и ограничения описаны.

**Проверенный код/тесты:** [PointWriteService.kt:193](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/points/PointWriteService.kt#L193), [205](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/points/PointWriteService.kt#L205), [216](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/points/PointWriteService.kt#L216), [227](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/points/PointWriteService.kt#L227); [AccountDeletionService.kt:94](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/accounts/AccountDeletionService.kt#L94), [109](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/accounts/AccountDeletionService.kt#L109), [113](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/accounts/AccountDeletionService.kt#L113); [BookingCancellationDepartureIntegrationTest.kt:32](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationDepartureIntegrationTest.kt#L32), [97](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationDepartureIntegrationTest.kt#L97), [261](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationDepartureIntegrationTest.kt#L261), [274](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationDepartureIntegrationTest.kt#L274).

**Свежий frozen-main результат:** com.example.meetings.BookingCancellationDepartureIntegrationTest; tests=2, skipped=0, failures=0, errors=0; UTC 2026-11-04T17:59:31.5120000+00:00; alias roy_m4_cancel_leave, port 55486; Gradle exit 0. XML SHA-256: 99cb77968a80d446d94924e27165bd45ee227a4e6346c5fa052f75cc442ce3d1.

**Границы:** Проверены synthetic local API/JDBC; события/jobs отдельно подтверждены T104/T105.

## T104 — M4.T4.B04

Task commit: [ef4e0aa357a6ddc87e9dcbc04ec1b10e1c6723a9](https://github.com/abcKristy/Roy_backend/commit/ef4e0aa357a6ddc87e9dcbc04ec1b10e1c6723a9). **Замечания: нет.** Тип доказательства: Native HTTP/WS/JDBC.

- **C1 — PASS:** Три отдельные native HTTP/WS/JDBC cancellation fixture.
- **C2 — PASS:** Сверены server reason, server time и revision.
- **C3 — PASS:** Один persisted cancellation SYSTEM и согласованные booking.changed/proposal.updated.
- **C4 — PASS:** Deferred COMMIT500 сохраняет ACTIVE без успеха; SQL gate доказывает precommit silence.
- **C5 — PASS:** Повторы не создают событий/effects; scope не раскрывает private IDs.
- **C6 — PASS:** Actual3 PASS и первоначальный provisioning FAIL записаны раздельно.

**Проверенный код/тесты:** [MeetingRoutes.kt:270](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L270); [PointWriteRoutes.kt:163](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/PointWriteRoutes.kt#L163); [AccountDeletionRoutes.kt:43](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/AccountDeletionRoutes.kt#L43); [RealtimeHub.kt:112](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/realtime/RealtimeHub.kt#L112); [BookingCancellationEventsIntegrationTest.kt:40](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationEventsIntegrationTest.kt#L40), [114](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationEventsIntegrationTest.kt#L114), [123](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationEventsIntegrationTest.kt#L123), [156](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationEventsIntegrationTest.kt#L156), [198](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationEventsIntegrationTest.kt#L198), [279](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingCancellationEventsIntegrationTest.kt#L279).

**Свежий frozen-main результат:** com.example.meetings.BookingCancellationEventsIntegrationTest; tests=3, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:01:18.8850000+00:00; alias roy_m4_cancel_events, port 55485; Gradle exit 0. XML SHA-256: 24bd1a56a60451d727bd97570c2704165ecc4fc16886ebfdea1566fdd48071cc.

**Границы:** Business WS после commit проверен локально; внешняя push-доставка не заявлена.

## T105 — M4.T4.B05

Task commit: [9aa08b379b888263181450f3166647d768db66be](https://github.com/abcKristy/Roy_backend/commit/9aa08b379b888263181450f3166647d768db66be). **Замечания: нет.** Тип доказательства: Real PG worker/scheduler/callers + real AES; fake provider boundary.

- **C1 — PASS:** Реальная DB fixture, DeviceService registration и AES/GCM decode.
- **C2 — PASS:** Все четыре callers отменяют PENDING/PROCESSING jobs и очищают claims.
- **C3 — PASS:** Fresh check после decrypt подавляет отправку уже отменённой booking.
- **C4 — PASS:** Scheduler/cancel не создаёт второй in-app event; late Delivered/Retryable не оживляет job.
- **C5 — PASS:** Claim-token/status CAS и повторы сохраняют durable state.
- **C6 — PASS:** RED→GREEN и существующие Jobs/DeviceRebind регрессии подтверждены.

**Проверенный код/тесты:** [ProposalService.kt:351](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L351); [PointWriteService.kt:447](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/points/PointWriteService.kt#L447); [AccountDeletionService.kt:114](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/accounts/AccountDeletionService.kt#L114); [BookingService.kt:361](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L361); [PushDeliveryWorker.kt:134](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/jobs/PushDeliveryWorker.kt#L134), [147](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/jobs/PushDeliveryWorker.kt#L147), [184](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/jobs/PushDeliveryWorker.kt#L184), [218](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/jobs/PushDeliveryWorker.kt#L218); [ReminderScheduler.kt:67](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/jobs/ReminderScheduler.kt#L67).

**Свежий frozen-main результат:** com.example.jobs.BookingReminderCancellationIntegrationTest; tests=4, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:02:32.9190000+00:00; alias roy_m4_cancel_jobs, port 55485; Gradle exit 0. XML SHA-256: 98a83e110ce768f2068863dd981d627168701a3fa69bde58fa0df24e1bcd9585.

**Границы:** Provider локальный fake. Point-in-time pre-send check не устраняет последний DB→provider TOCTOU и не отзывает уже начатый send.

## T106 — M4.T5.B01

Task commit: [480b4ec2427b285f14258e999386987b458c3c4b](https://github.com/abcKristy/Roy_backend/commit/480b4ec2427b285f14258e999386987b458c3c4b). **Замечания: нет.** Тип доказательства: Real PG service/query.

- **C1 — PASS:** Fixture содержит несколько тем одной Point и другую Point.
- **C2 — PASS:** Исполнен действительный overlap query.
- **C3 — PASS:** Прямое пересечение строго полуоткрытое: start<other.end и end>other.start.
- **C4 — PASS:** A↔B↔C не создаёт A↔C; adjacent/CANCELLED/different Point исключены.
- **C5 — PASS:** Повтор query не меняет snapshot/order.
- **C6 — PASS:** Два реальных теста, schema и SQL evidence записаны.

**Проверенный код/тесты:** [BookingService.kt:293](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L293); [MeetingProjection.kt:123](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L123), [149](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L149); [BookingOverlapQueryIntegrationTest.kt:17](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingOverlapQueryIntegrationTest.kt#L17), [61](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingOverlapQueryIntegrationTest.kt#L61).

**Свежий frozen-main результат:** com.example.meetings.BookingOverlapQueryIntegrationTest; tests=2, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:03:34.8580000+00:00; alias roy_m4_overlap_query, port 55487; Gradle exit 0. XML SHA-256: f8b257009bd8bbfa40953da8662e72762ead76366f73eb188fc1595712c50a5b.

**Границы:** Это direct-overlap query, а не глобальная монополизация слота.

## T107 — M4.T5.B02

Task commit: [36ece2a57dd9a0e49fb1088c8fb0b9b4d0b335a7](https://github.com/abcKristy/Roy_backend/commit/36ece2a57dd9a0e49fb1088c8fb0b9b4d0b335a7). **Замечания: нет.** Тип доказательства: Native HTTP/JDBC.

- **C1 — PASS:** Native fixture содержит настоящие ACTIVE conflicts.
- **C2 — PASS:** Исполнен POST с confirmOverlap=false.
- **C3 — PASS:** 409 OVERLAP_CONFIRM_REQUIRED содержит current details.overlaps без Booking/effects.
- **C4 — PASS:** Warning не становится terminal key outcome и не запрещает explicit true.
- **C5 — PASS:** Повтор warning стабилен; same-key confirmation/replay не дублируют Booking.
- **C6 — PASS:** Strict Boolean RED/fix и actual2 PASS зафиксированы.

**Проверенный код/тесты:** [BookingService.kt:107](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L107), [119](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L119); [MeetingRoutes.kt:60](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L60), [75](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L75), [177](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L177); [BookingOverlapWarningIntegrationTest.kt:29](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingOverlapWarningIntegrationTest.kt#L29), [73](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingOverlapWarningIntegrationTest.kt#L73).

**Свежий frozen-main результат:** com.example.meetings.BookingOverlapWarningIntegrationTest; tests=2, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:04:39.1120000+00:00; alias roy_m4_overlap_warning, port 55487; Gradle exit 0. XML SHA-256: e3c6f1c6ff67739d497a3c76d947f8fcc5d16b75ed15e1606a691255e62b39c3.

**Границы:** Мобильный overlap dialog не принимался.

## T108 — M4.T5.B03

Task commit: [a395e6b446aa182557fd20bedf3d46446da46710](https://github.com/abcKristy/Roy_backend/commit/a395e6b446aa182557fd20bedf3d46446da46710). **Замечания: нет.** Тип доказательства: Native HTTP + real PG service/concurrency.

- **C1 — PASS:** Отдельная native/service/JDBC fixture подготовлена.
- **C2 — PASS:** После nonterminal warning исполнено explicit true с тем же key.
- **C3 — PASS:** Current conflicts перечитываются под point lock; выделяется новый server order.
- **C4 — PASS:** Actual lock wait с изменёнными membership/vote/count/revision/time не принимает старый warning за разрешение.
- **C5 — PASS:** Replay сохраняет orders; разные actors/topics действительно сериализуются и обе допустимые ACTIVE остаются.
- **C6 — PASS:** Четыре actual PASS, waiter/current-guard proof и границы описаны.

**Проверенный код/тесты:** [BookingService.kt:82](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L82); [BookingOverlapConfirmIntegrationTest.kt:34](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingOverlapConfirmIntegrationTest.kt#L34), [66](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingOverlapConfirmIntegrationTest.kt#L66), [86](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingOverlapConfirmIntegrationTest.kt#L86), [121](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingOverlapConfirmIntegrationTest.kt#L121), [202](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingOverlapConfirmIntegrationTest.kt#L202).

**Свежий frozen-main результат:** com.example.meetings.BookingOverlapConfirmIntegrationTest; tests=4, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:05:53.5610000+00:00; alias roy_m4_overlap_confirm, port 55487; Gradle exit 0. XML SHA-256: 1264265ffa6a28d57eb7c2d9bfcf0a8ddf3d018997670344c296f3a5d960ceeb.

**Границы:** Проверены конкретные контролируемые schedules; широкая stress/load проверка не заявлена.

## T109 — M4.T5.B04

Task commit: [2cb164bcdf9a24bea2ebdda14b3ac0ecee7ac62f](https://github.com/abcKristy/Roy_backend/commit/2cb164bcdf9a24bea2ebdda14b3ac0ecee7ac62f). **Замечания: нет.** Тип доказательства: Native HTTP/WS/JDBC.

- **C1 — PASS:** Есть A/B/C, adjacent, CANCELLED и foreign-Point fixtures.
- **C2 — PASS:** Исполнены canonical GET/list, native cancellation и rebook.
- **C3 — PASS:** Прямые conflicts отсортированы по immutable bookingOrder.
- **C4 — PASS:** Equal bookedAt и обратный UUID order не меняют приоритет; transitive rank отсутствует.
- **C5 — PASS:** Cancellation не перенумеровывает survivors; rebook новый ID/order; repeat без effects.
- **C6 — PASS:** Actual native HTTP/WS run и DB snapshots сохранены.

**Проверенный код/тесты:** [MeetingProjection.kt:123](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L123), [149](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L149); [BookingService.kt:126](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L126), [293](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L293); [BookingPairwisePriorityIntegrationTest.kt:35](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingPairwisePriorityIntegrationTest.kt#L35).

**Свежий frozen-main результат:** com.example.meetings.BookingPairwisePriorityIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:07:05.3960000+00:00; alias roy_m4_booking_priority, port 55487; Gradle exit 0. XML SHA-256: 02844b1b6245c15bb6f4445f713dd364b14b4f8d7be89750c6a0d51a86eb874f.

**Границы:** Pairwise priority не является физической блокировкой всех пересекающихся встреч.

## T110 — M4.T5.B05

Task commit: [610057ab12e5bf2948dd1cf030951d32a95c117c](https://github.com/abcKristy/Roy_backend/commit/610057ab12e5bf2948dd1cf030951d32a95c117c). **Замечания: нет.** Тип доказательства: Native HTTP/WS/JDBC + explicit closeClock boundary.

- **C1 — PASS:** Отдельные fresh booked/unbooked native fixtures.
- **C2 — PASS:** Раздельно проверены active member/proposer, revision, strict Long и server now.
- **C3 — PASS:** Prestart close атомарно меняет Proposal/Booking/jobs/SYSTEM/outcome.
- **C4 — PASS:** Exact/+1µs, nonauthor/bookedBy-only, stale/ineligible/invalid отказывают без effects.
- **C5 — PASS:** COMMIT rollback/retry и historical receipt не перезаписывают newer revision; reuse на другом proposal отклонён.
- **C6 — PASS:** Quoted revision RED→fix→4 PASS и booking regression записаны.

**Проверенный код/тесты:** [MeetingRoutes.kt:93](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L93), [204](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/MeetingRoutes.kt#L204); [BookingService.kt:152](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L152), [178](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L178), [180](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L180), [200](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L200); [BookingAuthorCloseIntegrationTest.kt:36](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingAuthorCloseIntegrationTest.kt#L36), [48](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingAuthorCloseIntegrationTest.kt#L48), [70](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingAuthorCloseIntegrationTest.kt#L70), [90](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/BookingAuthorCloseIntegrationTest.kt#L90).

**Свежий frozen-main результат:** com.example.meetings.BookingAuthorCloseIntegrationTest; tests=4, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:08:18.8910000+00:00; alias roy_m4_booking_close, port 55487; Gradle exit 0. XML SHA-256: acef8232bf898d9a586e3f84c34bac32028eed4739d7937ffe5a687b6aafd78f.

**Границы:** closeClock используется только при явной injection; default — PostgreSQL. Мобильный confirmation не принимался.

## T111 — M4.T6.B01

Task commit: [9971abe3fc19e5a6511bfa0d1e9be26c83292e4b](https://github.com/abcKristy/Roy_backend/commit/9971abe3fc19e5a6511bfa0d1e9be26c83292e4b). **Замечания: нет.** Тип доказательства: Native HTTP/JDBC test fixture.

- **C1 — PASS:** Одна UUID-owned Point, две темы, три участника и valid nonmember.
- **C2 — PASS:** Четыре UTC интервала создаются native API и сверяются JDBC.
- **C3 — PASS:** Direct/transitive/touching/disjoint отношения воспроизводимы.
- **C4 — PASS:** Outsider403 и unauthenticated401 не маскируются данными других контекстов.
- **C5 — PASS:** Повтор/read/controlled late replay не меняют persisted snapshot.
- **C6 — PASS:** Два actual PASS и scoped cleanup после committed-setup failure подтверждены.

**Проверенный код/тесты:** [MeetingAcceptanceFixture.kt:18](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingAcceptanceFixture.kt#L18), [41](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingAcceptanceFixture.kt#L41), [47](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingAcceptanceFixture.kt#L47), [88](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingAcceptanceFixture.kt#L88), [102](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingAcceptanceFixture.kt#L102); [MeetingAcceptanceFixtureIntegrationTest.kt:23](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingAcceptanceFixtureIntegrationTest.kt#L23), [113](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingAcceptanceFixtureIntegrationTest.kt#L113).

**Свежий frozen-main результат:** com.example.meetings.MeetingAcceptanceFixtureIntegrationTest; tests=2, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:09:19.5950000+00:00; alias roy_m4_acceptance_fixture, port 55485; Gradle exit 0. XML SHA-256: fec95a06c5596d377042ea5dfef592c39309d3866ef5eef437f835735c8a2c5c.

**Границы:** Сохранение другой одновременно живой fixture следует из scoped DELETE source; отдельный двухfixture runtime case не заявлен.

## T112 — M4.T6.B02

Task commit: [d07dee4f7df569ec4bda41713fb0c280c4e1149b](https://github.com/abcKristy/Roy_backend/commit/d07dee4f7df569ec4bda41713fb0c280c4e1149b). **Замечания: нет.** Тип доказательства: Native HTTP/WS/JDBC full chain/default PG clocks.

- **C1 — PASS:** Используются принятая fixture, два native peers, observer и outsider.
- **C2 — PASS:** Сохранены actual request bodies, operation keys и revisions.
- **C3 — PASS:** Create0→votes→book→overlap→cancel→rebook→close имеют DB/REST/list/WS подтверждение.
- **C4 — PASS:** SQL/IDs/personal REST/ACL/no-own-vote/count1/foreign-close отказы исключают local-fake proof.
- **C5 — PASS:** Повторы/старые receipts не откатывают новые revisions; disconnect восстанавливается GET/messages afterSeq.
- **C6 — PASS:** 27 actual entries, successful XML и отдельный test-SQL failure сохранены.

**Проверенный код/тесты:** [MeetingRestChainIntegrationTest.kt:30](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingRestChainIntegrationTest.kt#L30), [57](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingRestChainIntegrationTest.kt#L57), [191](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingRestChainIntegrationTest.kt#L191), [203](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingRestChainIntegrationTest.kt#L203), [257](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingRestChainIntegrationTest.kt#L257), [286](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingRestChainIntegrationTest.kt#L286).

**Свежий frozen-main результат:** com.example.meetings.MeetingRestChainIntegrationTest; tests=1, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:10:21.9970000+00:00; alias roy_m4_acceptance_fixture, port 55485; Gradle exit 0. XML SHA-256: da8fe9252de5debaf1473729e03fe7bfa064c23c12090dade6b09c2ffbe46e17.

**Границы:** В этой цепочке выполнена unvote cancellation; leave ей не приписывается. WS best effort; reconnect восстанавливается REST.

## T113 — M4.T6.B03

Task commit: [67ff044477d4ca9ad83488bd0aa3233714677552](https://github.com/abcKristy/Roy_backend/commit/67ff044477d4ca9ad83488bd0aa3233714677552). **Замечания: нет.** Тип доказательства: Native HTTP/real DB wait races + mapper unit.

- **C1 — PASS:** Отдельная acceptance fixture имеет явные test-only timeout options.
- **C2 — PASS:** Семь shared-start races и два staged authenticated deletion overlap исполнены native.
- **C3 — PASS:** Проверены vote PK/valid count/ACTIVE≤1/revision/order/SYSTEM/dedup после обоих ответов.
- **C4 — PASS:** Обе реальные DB waiter chains доходят до собственного gate до release; это не последовательные запросы.
- **C5 — PASS:** Same-key/desired-state/leave/late-auth не создают повторных effects.
- **C6 — PASS:** Девять actual ledgers, оба RED→approved fix→GREEN, версии и cleanup описаны.

**Проверенный код/тесты:** [MeetingConcurrencyIntegrationTest.kt:32](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingConcurrencyIntegrationTest.kt#L32), [180](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingConcurrencyIntegrationTest.kt#L180), [219](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingConcurrencyIntegrationTest.kt#L219), [240](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingConcurrencyIntegrationTest.kt#L240); [JdbcIdempotencyOperations.kt:18](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/persistence/JdbcIdempotencyOperations.kt#L18), [49](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/persistence/JdbcIdempotencyOperations.kt#L49); [ProposalService.kt:323](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L323); [ErrorMapper.kt:37](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/api/ErrorMapper.kt#L37); [SafeErrorEnvelopeTest.kt:32](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/api/SafeErrorEnvelopeTest.kt#L32).

**Свежий frozen-main результат:** com.example.meetings.MeetingConcurrencyIntegrationTest; tests=9, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:11:55.7320000+00:00; alias roy_m4_acceptance_fixture, port 55485; Gradle exit 0. XML SHA-256: 872ba3300e4e8a999e82a73102c1ce7fec1a059812ec89854b22708e5bdb5bee.

**Границы:** Первый RED BOOK raw JSON утрачен, RED XML сохранён. RED VOTE raw содержит документированную ошибку label; queries/waiters доказывают staged overlap. Все мыслимые endpoint races и stress/load не заявлены.

## T114 — M4.T6.B04

Task commit: [17270be99f32b8c0dfda021780d3ae4681f5d9aa](https://github.com/abcKristy/Roy_backend/commit/17270be99f32b8c0dfda021780d3ae4681f5d9aa). **Замечания: нет.** Тип доказательства: Native HTTP/JDBC controlled guard clock + separate real-PG history.

- **C1 — PASS:** Двенадцать fresh eligible contexts используют persisted .123456Z и +03:00 input.
- **C2 — PASS:** Vote true/false, book и close проверены отдельно при−1µs/exact/+1µs.
- **C3 — PASS:** Exact-start author close получает409 без отмены history.
- **C4 — PASS:** RAW OPEN/scheduler lag не разрешает поздние vote/book; две actual lock waits доказывают postlock sampling.
- **C5 — PASS:** Новые late keys отвергаются; old-success replay не меняет более новую DB revision.
- **C6 — PASS:** Шесть actual ledgers, real-PG past history и18 PASS регрессий сохранены.

**Проверенный код/тесты:** [ProposalService.kt:197](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/ProposalService.kt#L197); [BookingService.kt:107](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L107), [178](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/BookingService.kt#L178); [MeetingTimeBoundsIntegrationTest.kt:29](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingTimeBoundsIntegrationTest.kt#L29), [99](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingTimeBoundsIntegrationTest.kt#L99), [143](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingTimeBoundsIntegrationTest.kt#L143), [192](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/test/kotlin/meetings/MeetingTimeBoundsIntegrationTest.kt#L192); [ProposalExpiryScheduler.kt:42](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/jobs/ProposalExpiryScheduler.kt#L42); [MeetingProjection.kt:104](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L104), [111](https://github.com/abcKristy/Roy_backend/blob/75cf3d612cad20d81385bd10f701946379dbbb31/src/main/kotlin/meetings/MeetingProjection.kt#L111).

**Свежий frozen-main результат:** com.example.meetings.MeetingTimeBoundsIntegrationTest; tests=6, skipped=0, failures=0, errors=0; UTC 2026-11-04T18:13:51.5620000+00:00; alias roy_m4_acceptance_fixture, port 55485; Gradle exit 0. XML SHA-256: a3e7540b11a8b79e983da1c33a93cec3242d259837ac600991d096f6b4b6cd76.

**Границы:** Past SQL rows являются setup для history, а не успешным past booking API. Nullable guard Clocks — opt-in; production PG/projection/expiry clocks не заменены.

## Межгрупповая интеграция и учётные места

Сохранены actor→point→proposal serialization, membership/current count guards, read-only REPEATABLE READ snapshots, возврат результата после COMMIT и публикация событий только после commit. Повторяются только retryable SQL states; provider I/O остаётся вне DB transaction. Проверены vote PK, partial UNIQUE ACTIVE per Proposal и server bookingOrder. Raw request hashes/старые receipts, strict JSON properties, общий cancellation policy и позднейшие Clock/InactiveActor изменения совместимы.

Физически сверены все 28 карточек, шесть README, шесть полных родителей, execution-checklist и M4 roadmap. Фактический счётчик — 112/455, B4 backend 28/28 ГОТОВО локально. Родители остаются смешанными: клиентские части и общая M4/BG-1 не закрыты.

Учётные файлы `spec/plans/tasks/M4/...`, `spec/plans/roadmap/execution-checklist.md`, `spec/plans/roadmap/M4/M4-meetings-booking.md` находятся в родительском workspace vkr **вне backend Git**. Их обновление не выдаётся за физическое включение в main. Версионируемая сводка веток/баз/коммитов/пулов — [m4-server-execution.md](m4-server-execution.md).

## Что не проверено и не объявляется завершённым

Реальная RuStore/APNs и иная внешняя M5 delivery, M5 nearby/reminder acceptance целиком, Android/iOS UI/device сценарии, production deployment, широкая stress/load и BG-1 не выполнены этим локальным серверным аудитом. Stub SessionRepository в T088, fake provider в worker-тестах и synthetic SQL setup явно отличены от native/full-session/API доказательств. Bounded WS наблюдение не является гарантией неограниченного exactly-once; pre-send recheck не отзывает уже начатую внешнюю отправку.

Недоступного окружения для перечисленных локальных B4 проверок не было: все они реально выполнены. Внешние/клиентские ограничения относятся к отдельной приёмке и не замаскированы ложным PASS.

## Завершение B4

Все 28 карточек получили фактическое исполнение, отдельный task review и отдельный task commit; все 12 веток прошли независимое whole-branch review и опубликованы в main. Отдельный итоговый аудит не подтвердил новых дефектов. Пустая fix-ветка не создавалась. Следующие блоки не начинались. Граница завершённого результата — реализация T114 и полная локальная проверка B4, как потребовал пользователь.
