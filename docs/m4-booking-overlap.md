# M4.T5.B01 / T106 — direct booking overlap query

Дата: 04.11.2026. Результат: локальный backend PASS для прямого overlap query. База исходников: `ae43668958e3034ada605809d29f1d09a95969d4`, ветка `feat/booking-overlap`; subject результата: `Verify direct half open booking overlaps`, commit `480b4ec2427b285f14258e999386987b458c3c4b`; task review contracts APPROVE подтверждён Lead.

## Область и фактический результат

Существующий `BookingService.overlaps` сверён: ACTIVE bookings всех тем одной Point, параметризованные строгие условия `proposal.starts_at < candidate.endsAt AND proposal.ends_at > candidate.startsAt`, `ORDER BY booking.booking_order`. Production-код и миграции не изменялись; дефекта query не обнаружено.

Добавлен `BookingOverlapQueryIntegrationTest` с настоящим BookingService, production projection serializer, JDBC и PostgreSQL/PostGIS. Это прямой вызов application service с native Boolean `false`; HTTP mapping `409 OVERLAP_CONFIRM_REQUIRED details.overlaps` сверён по MeetingRoutes, его исполнение/transport validation относятся к отдельному T107. Confirm commit-check относится к T108; pairwise projection к T109. Эти задачи данным PASS не закрыты.

Первый сценарий проверил два ACTIVE seed booking разных тем одной Point с одинаковым bookedAt и разными immutable bookingOrder. Полная ожидаемая ordered conflict list совпала для частичного пересечения слева/справа, вложенного, содержащего и равного окна. Оба соседних окна (end==start в обоих направлениях) и непересекающееся окно создали booking без conflicts. Широкая CANCELLED booking и широкая ACTIVE booking другой Point исключены во всех случаях. Предложения имеют две реальные votes и revision2.

Второй сценарий проверил A=[0,10), B=[5,15), C=[10,20): C возвращает только B, A в результат не попадает. Отдельный запрос окна A возвращает A и B в bookingOrder, без транзитивного/global rank.

Каждый отказ и его повтор сопоставлены с SQL snapshot bookings/proposals/votes, point updated_at, chat last_message_seq, messages, recipient notifications, operation_idempotency и sequence allocation. Snapshot не изменился: предупреждение не создаёт booking, outcome, message, notification, revision или order. Успешные non-overlap candidate rows удалены перед следующей проверкой; окончательная fixture cleanup подтвердила отсутствие данных.

## Изоляция и воспроизведение

Workdir каждой команды: `C:\Users\lolut\.codex\worktrees\booking-overlap\backend`. Собственный container `codex-m4-overlap`, ID `0cfd2641af1133496d396305d69ecadeaab5a1237330f668eca803166c5b80a7`, labels `codex.group=9`, `codex.task=m4-t087-t114`. Targeted docker inspect подтвердил единственный binding `127.0.0.1:55487 -> 5432/tcp`; использована только disposable alias `roy_m4_overlap_query`. Env-содержимое/credentials в протокол не выводились.

Выполненные PowerShell команды:

```powershell
docker inspect codex-m4-overlap --format '{{.Id}} {{json .Config.Labels}} {{json .HostConfig.PortBindings}}'
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_overlap_query -DatabasePort 55487
.\gradlew.bat integrationTest --tests com.example.meetings.BookingOverlapQueryIntegrationTest --offline --no-daemon --max-workers=1
Get-Content build/test-results/integrationTest/TEST-com.example.meetings.BookingOverlapQueryIntegrationTest.xml -TotalCount 8
docker exec codex-m4-overlap psql -U $env:DATABASE_USER -d roy_m4_overlap_query -Atc "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT version,success FROM flyway_schema_history ORDER BY installed_rank; SELECT 'users',count(*) FROM users UNION ALL SELECT 'points',count(*) FROM points UNION ALL SELECT 'point_topics',count(*) FROM point_topics UNION ALL SELECT 'chats',count(*) FROM chats UNION ALL SELECT 'chat_memberships',count(*) FROM chat_memberships UNION ALL SELECT 'meeting_proposals',count(*) FROM meeting_proposals UNION ALL SELECT 'votes',count(*) FROM votes UNION ALL SELECT 'bookings',count(*) FROM bookings UNION ALL SELECT 'messages',count(*) FROM messages UNION ALL SELECT 'notification_events',count(*) FROM notification_events UNION ALL SELECT 'operation_idempotency',count(*) FROM operation_idempotency; SELECT last_value,is_called FROM bookings_booking_order_seq;"
```

Первый focused run: `BUILD SUCCESSFUL in 2m 32s`, 6 executed tasks. XML: `tests=2`, `skipped=0`, `failures=0`, `errors=0`, timestamp `2026-11-04T14:54:22.864Z`, duration `4.683s` (cases `4.018s`, `0.661s`). Required integration task реально исполнилась; DB missing/skip не считается PASS. JDK17, Gradle9.5.1, backend1.0.0-SNAPSHOT. Обнаружены только существующие deprecation/unused warnings в RealtimeRoutes, ChatSendLeaveRaceIntegrationTest и BookingServiceIntegrationTest.

После run SQL: PostgreSQL `18.6 (Debian 18.6-1.pgdg13+2)`, PostGIS `3.6.4`; Flyway V001–V015, все success=true. Все 11 перечисленных fixture tables имеют count0. `bookings_booking_order_seq`: last_value9, is_called=true; identity gaps не сбрасывались. Перед bootstrap alias ещё не имела flyway_schema_history; диагностический запрос под несуществующей ролью postgres был отклонён, повтор выполнялся с согласованной synthetic env ролью. Эти pre-run diagnostics не являются тестовыми отказами или production findings.

## Границы результата

Нет production/schema изменений и незакрытых дефектов direct query. Существующая публичность routes и deployment не менялись. Локальные service/JDBC доказательства не подтверждают BG-1, M5 delivery/provider acceptance, Android/iOS или M4 целиком. Внешние обязательные блокеры остаются в родительском плане.

Lead: independent read-only task review APPROVE before commit; all five external status places synchronized. Checkpoint including parallel T099/T101/T106: checklist99/455, M4 backend15/28.


# M4.T5.B02 / T107 — native warning and explicit Boolean consent

Дата: 04.11.2026. Локальный backend PASS; исходная база T106 `480b4ec2427b285f14258e999386987b458c3c4b`; subject `Enforce booking overlap confirmation warning`, commit `36ece2a57dd9a0e49fb1088c8fb0b9b4d0b335a7`; final task review contracts APPROVE подтверждён Lead.

## Подтверждённый дефект и узкое исправление

С новым `BookingOverlapWarningIntegrationTest` на настоящем loopback Netty/JDK HttpClient и PostgreSQL сначала выполнен RED без production edits: native POST overlapping candidate с `confirmOverlap:"true"` вернул201, ожидаемый422. XML timestamp `2026-11-04T15:06:51.825Z`, tests2/skipped0/failures1/errors0, duration9.56s; Gradle BUILD FAILED41s. Это фактический обход explicit Boolean consent через стандартную JSON coercion. RED XML сохранён перед повтором в `build/t107-red/TEST-com.example.meetings.BookingOverlapWarningIntegrationTest.xml`; Lead и независимый contracts reviewer прочитали его и подтвердили P2.

Lead и contracts явно одобрили только `@Serializable(with = StrictVoteBooleanSerializer::class)` у `BookingInput.confirmOverlap`. Применена ровно эта аннотация: повторно используется существующий private strict Boolean serializer. Serializer не переименован, global JSON и BookingService не изменены, миграций нет. Новые Native Boolean false/true продолжают работать; quoted true/false, null,0,1,object,array и missing property дают422 INVALID_FIELD без SQL/sequence эффектов.

## Native HTTP acceptance

`POST /api/v1/proposals/{id}/booking` с native false и актуальной revision2 вернул409 OVERLAP_CONFIRM_REQUIRED. `error.details` содержит только overlaps: точные шесть полей bookingId/proposalId/startsAt/endsAt/bookedAt/bookingOrder для двух текущих ACTIVE bookings разных тем той же Point, в bookingOrder при equal bookedAt. Частичная новая booking отсутствует. Ответ не содержит private account fields.

Исходный warning и его same-key повтор не изменили snapshot bookings, proposals, votes, point updated_at, chat last_message_seq, messages, notifications, operation_idempotency или booking_order sequence. После явного fixture revision bump2→3 same key со старой revision2 дал409 STALE_STATE без эффектов; с current revision3 снова получен актуальный warning. Затем same key/native true/revision3 вернул201 booked/revision4 с точными двумя overlaps. Same-key retry повторил committed response без повторных эффектов. Таким образом warning предварителен, не запрещает вторую бронь и не сохраняет terminal outcome ключа. Полный concurrent/changed-conflicts commit-check T108 данным результатом не закрыт.

## Реальные команды и результаты

Workdir прежний: `C:\Users\lolut\.codex\worktrees\booking-overlap\backend`. Собственный container ID/binding повторно проверен: `codex-m4-overlap`, `0cfd2641af1133496d396305d69ecadeaab5a1237330f668eca803166c5b80a7`, только127.0.0.1:55487. Обе новые disposable aliases созданы исключительно в нём; чужая общая БД/порт55484 не использовались.

```powershell
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_overlap_warning -DatabasePort 55487
docker exec codex-m4-overlap createdb -U $env:DATABASE_USER roy_m4_overlap_warning
.\gradlew.bat integrationTest --tests com.example.meetings.BookingOverlapWarningIntegrationTest --offline --no-daemon --max-workers=1
```

Эта Gradle команда исполнялась сначала RED, затем после approved annotation и добавления missing-property case GREEN. GREEN BUILD SUCCESSFUL42s; XML `tests=2 skipped=0 failures=0 errors=0`, timestamp `2026-11-04T15:09:36.031Z`, duration7.24s (5.74s malformed matrix,1.497s warning/continuation). GREEN XML сохранён в `build/t107-green/TEST-com.example.meetings.BookingOverlapWarningIntegrationTest.xml` перед regression run.

Targeted regression существующего strict going Boolean:

```powershell
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_vote -DatabasePort 55487
docker exec codex-m4-overlap createdb -U $env:DATABASE_USER roy_m4_vote
.\gradlew.bat integrationTest --tests '*VoteWriteIntegrationTest.HTTP desired vote is own actor idempotent and refuses unauthorized or nonopen writes' --offline --no-daemon --max-workers=1
```

Regression BUILD SUCCESSFUL33s; actual XML tests1/skipped0/failures0/errors0 timestamp `2026-11-04T15:10:23.560Z`, duration8.178s. Native going true/false и прежняя malformed matrix сохранены. Это targeted method, весь VoteWriteIntegrationTest и общий regression suite не заявлены.

После каждого GREEN SQL через `docker exec codex-m4-overlap psql -U $env:DATABASE_USER -d <alias> -Atc <query>`:

```sql
SELECT current_database(),current_setting('server_version'),postgis_lib_version();
SELECT count(*),min(version),max(version),bool_and(success) FROM flyway_schema_history;
SELECT 'users',count(*) FROM users UNION ALL SELECT 'auth_sessions',count(*) FROM auth_sessions
UNION ALL SELECT 'points',count(*) FROM points UNION ALL SELECT 'point_topics',count(*) FROM point_topics
UNION ALL SELECT 'chats',count(*) FROM chats UNION ALL SELECT 'chat_memberships',count(*) FROM chat_memberships
UNION ALL SELECT 'meeting_proposals',count(*) FROM meeting_proposals UNION ALL SELECT 'votes',count(*) FROM votes
UNION ALL SELECT 'bookings',count(*) FROM bookings UNION ALL SELECT 'messages',count(*) FROM messages
UNION ALL SELECT 'notification_events',count(*) FROM notification_events
UNION ALL SELECT 'operation_idempotency',count(*) FROM operation_idempotency;
```

Для обеих aliases факт PostgreSQL18.6/PostGIS3.6.4, history15 rows/V001–V015/all success=true; все12 перечисленных fixture tables count0. T107 alias дополнительно `SELECT last_value,is_called FROM bookings_booking_order_seq` дал9/true; gaps сохранены. Native HTTP server остановлен finally, fixture cleanup удаляет только свои UUID rows. Secret/env/JWT содержимое не выводилось. Public exposure/deploy не менялись; BG-1, provider delivery, mobile, общий M4 и оставшиеся T108–T110 остаются отдельной приёмкой.

T107 independent read-only final task review by contracts: APPROVE after approved strict-Boolean fix and real GREEN/regression. Lead synchronized the two shared status registers; T5 backend2/5, checklist102/455, M4 backend18/28 at this checkpoint.


# M4.T5.B03 / T108 — fresh confirmation under actual point locks

Дата: 04.11.2026. Локальный backend PASS; база T107 `36ece2a57dd9a0e49fb1088c8fb0b9b4d0b335a7`; subject `Verify fresh booking confirmation checks`, commit `a395e6b446aa182557fd20bedf3d46446da46710`; final task review contracts APPROVE подтверждён Lead.

Добавлен только `BookingOverlapConfirmIntegrationTest`; production BookingService/MeetingRoutes и миграции не менялись. Сверка исходников показала, что существующий book при каждом новом confirmation transaction после actor-idempotency lock получает point row FOR UPDATE, proposal lock и заново читает membership/revision/lifecycle/database time/active booking/own vote/count/direct overlaps. Старый warning не сохраняет terminal outcome и не является источником доверенного списка.

## Исполненные сценарии

1. Native HTTP false сначала даёт409 OVERLAP_CONFIRM_REQUIRED. JDBC transaction держит именно point row FOR UPDATE; native HTTP same-key true запускается отдельным executor. Observer через pg_stat_activity/pg_blocking_pids подтверждает реальный point-lock wait и незавершённый future. Только после этого fixture transaction меняет по одному revision, membership, own vote, vote count, lifecycle или active booking и commits. Получены соответственно STALE_STATE409, NOT_MEMBER403, VOTE_REQUIRED409, INSUFFICIENT_VOTES409, PROPOSAL_CLOSED409, ALREADY_BOOKED409. Snapshot той же gate transaction после её mutation точно совпал со snapshot после завершения отказа: никаких дополнительных bookings/votes/proposal revisions, point timestamps, messages/chat sequence, notifications, idempotency или booking_order allocation. Повтор same-key true вернул тот же текущий отказ без эффектов; outcomes0. Adversarial SQL fixtures намеренно сохраняют revision2 в изолированных vote/membership/lifecycle/active cases, чтобы проверка не завершалась более ранним stale guard; это не заявление о поведении production mutators.

2. Отдельный near-start candidate создаётся после старта HTTP server с DB startsAt=clock_timestamp()+3seconds. False даёт warning. Пока true реально ждёт point lock, observer опрашивает SQL `clock_timestamp()>=starts_at`; gate освобождается после положительного результата. True даёт409 MEETING_ALREADY_STARTED без SQL/sequence изменений. Доказан fresh after-start guard; точное равенство clock/startsAt не заявлено, exact boundary относится к T114. Короткий bounded polling используется для наблюдения, не как свидетельство равенства времени.

3. После warning с двумя direct conflicts true ждёт point lock. Держащая lock transaction отменяет одну прежнюю booking и добавляет другую ACTIVE booking с новым order. True201 возвращает current overlaps из survivor+new booking, исключает отменённую запись; booked revision3. Все прежние booking orders/status сохраняются после confirm, включая order отменённой записи, новая бронь не отменяет survivor. Same-key true replay идентичен response и не повторяет эффекты.

4. Два разных actor одновременно подтверждают два предложения разных тем одной Point после отдельных warning. Это настоящий BookingService/JDBC concurrency, native HTTP guards отдельно покрыты выше. Разные actor idempotency locks не сериализуют друг друга; observer доказывает два ожидающих именно point-lock запроса, включая PostgreSQL queued blocker chain, до освобождения gate. Оба результата Created, original+оба новых bookings ACTIVE. Orders строго возрастают и различны, original order неизменён; ранний commit видит original, поздний original+раннюю новую booking. Нет global rank или отмены соседней брони.

## Фактические runs и исправление test harness

Собственная alias `roy_m4_overlap_confirm` создана только в контейнере codex-m4-overlap/127.0.0.1:55487. Повторный targeted inspect подтвердил ID `0cfd2641af1133496d396305d69ecadeaab5a1237330f668eca803166c5b80a7` и единственный loopback binding. Workdir каждой команды `C:\Users\lolut\.codex\worktrees\booking-overlap\backend`.

```powershell
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_overlap_confirm -DatabasePort 55487
docker exec codex-m4-overlap createdb -U $env:DATABASE_USER roy_m4_overlap_confirm
.\gradlew.bat integrationTest --tests com.example.meetings.BookingOverlapConfirmIntegrationTest --offline --no-daemon --max-workers=1
Get-Content build/test-results/integrationTest/TEST-com.example.meetings.BookingOverlapConfirmIntegrationTest.xml -TotalCount 8
```

Первый реальный run: BUILD FAILED45s, XML timestamp `2026-11-04T15:17:58.775Z`, tests4/skipped0/failures2/errors0, duration21.131s. Changed guards и changed conflicts прошли. Near-start test держал gate3seconds при default production lock budget1second, поэтому получил500 из lock timeout; concurrency observer ошибочно требовал обоих непосредственных blockers на gate, хотя PostgreSQL ставит второго за первым waiter, и удержал gate до timeout. Это установленные test harness причины, production дефект не подтверждён.

С согласованием Lead исправлен только тест: явные bounded fixture budgets lock10seconds/statement15seconds (production defaults не менялись), observer учитывает queued point waiters с подтверждённым root gate blocker; first-refusal no-effects дополнительно усилен snapshot внутри gate transaction. Та же focused команда повторена.

GREEN: BUILD SUCCESSFUL25s, actual XML tests4/skipped0/failures0/errors0 timestamp `2026-11-04T15:19:14.346Z`, duration10.523s; cases changed-conflicts3.455s, after-start3.506s, guards3.134s, concurrent actors0.425s. Никаких skips или mocks DB/lock/HTTP. JDK17/Gradle9.5.1/backend1.0.0-SNAPSHOT, существующие Gradle deprecation warnings.

SQL после GREEN выполнен через `docker exec codex-m4-overlap psql -U $env:DATABASE_USER -d roy_m4_overlap_confirm -Atc <query>`; использован тот же versions/history/12-table-cleanup query, что в T107, дополнительно:

```sql
SELECT last_value,is_called FROM bookings_booking_order_seq;
SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock';
```

Факт PostgreSQL18.6/PostGIS3.6.4, Flyway15 rows/V001–V015/all success=true. Все12 fixture tables (users,auth_sessions,points,point_topics,chats,chat_memberships,meeting_proposals,votes,bookings,messages,notification_events,operation_idempotency) count0; lock waiters0; identity last_value28/is_called=true, gaps не сбрасывались. Servers/executors закрыты finally, cleanup удаляет свои UUID fixtures. Production finding/изменений нет. Public routes/deploy не менялись; T109/T110/T114, BG-1, delivery/provider/mobile и общий M4 этим PASS не закрыты.

T108 independent read-only task review by contracts: APPROVE before commit; no confirmed findings. Lead synchronized all five external status places: T5 backend3/5, checklist103/455, M4 backend19/28. Updated main integration and independent whole-branch review are separate.


# Main integration d5cfce8 — group9 focused regression

04.11.2026. Lead подготовил clean automatic merge опубликованного main `d5cfce8` в ветку с T106 `480b4ec2427b285f14258e999386987b458c3c4b`, T107 `36ece2a57dd9a0e49fb1088c8fb0b9b4d0b335a7`, T108 `a395e6b446aa182557fd20bedf3d46446da46710`. На момент этих checks merge находился pending до Lead commit. Product/tests не редактировались; повторно исполнены только три затронутых focused classes на прежних собственных aliases контейнера codex-m4-overlap/127.0.0.1:55487. Это integration evidence этих checks, не новый общий milestone acceptance или group review.

Каждая последовательность выполнялась с явным workdir `C:\Users\lolut\.codex\worktrees\booking-overlap\backend`; XML каждого успешного run скопирован до следующего Gradle в ignored `build/pool6-main-integration/`:

```powershell
New-Item -ItemType Directory -Force build/pool6-main-integration | Out-Null
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_overlap_query -DatabasePort 55487
.\gradlew.bat integrationTest --tests com.example.meetings.BookingOverlapQueryIntegrationTest --offline --no-daemon --max-workers=1
Copy-Item -LiteralPath build/test-results/integrationTest/TEST-com.example.meetings.BookingOverlapQueryIntegrationTest.xml -Destination build/pool6-main-integration/BookingOverlapQueryIntegrationTest.xml
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_overlap_warning -DatabasePort 55487
.\gradlew.bat integrationTest --tests com.example.meetings.BookingOverlapWarningIntegrationTest --offline --no-daemon --max-workers=1
Copy-Item -LiteralPath build/test-results/integrationTest/TEST-com.example.meetings.BookingOverlapWarningIntegrationTest.xml -Destination build/pool6-main-integration/BookingOverlapWarningIntegrationTest.xml
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_overlap_confirm -DatabasePort 55487
.\gradlew.bat integrationTest --tests com.example.meetings.BookingOverlapConfirmIntegrationTest --offline --no-daemon --max-workers=1
Copy-Item -LiteralPath build/test-results/integrationTest/TEST-com.example.meetings.BookingOverlapConfirmIntegrationTest.xml -Destination build/pool6-main-integration/BookingOverlapConfirmIntegrationTest.xml
```

Actual XML: Query tests2/skipped0/failures0/errors0, timestamp `2026-11-04T15:24:36.552Z`, duration2.967s, BUILD SUCCESSFUL18s. Warning tests2/skipped0/failures0/errors0, timestamp `2026-11-04T15:25:08.503Z`, duration4.099s, BUILD SUCCESSFUL17s. Confirm tests4/skipped0/failures0/errors0, timestamp `2026-11-04T15:25:38.377Z`, duration10.071s, BUILD SUCCESSFUL23s. Суммарно8 реально исполненных tests/0skips/failures/errors; старые task-run результаты не подменяют эти XML.

После runs тот же versions/history/12-table-cleanup SQL из T107 и sequence/lock-waiter SQL из T108 выполнен для каждой alias через следующую PowerShell последовательность (с env синтетической ролью без печати credentials):

```powershell
foreach ($dbAlias in @('roy_m4_overlap_query','roy_m4_overlap_warning','roy_m4_overlap_confirm')) {
    docker exec codex-m4-overlap psql -U $env:DATABASE_USER -d $dbAlias -Atc "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT count(*),min(version),max(version),bool_and(success) FROM flyway_schema_history; SELECT 'users',count(*) FROM users UNION ALL SELECT 'auth_sessions',count(*) FROM auth_sessions UNION ALL SELECT 'points',count(*) FROM points UNION ALL SELECT 'point_topics',count(*) FROM point_topics UNION ALL SELECT 'chats',count(*) FROM chats UNION ALL SELECT 'chat_memberships',count(*) FROM chat_memberships UNION ALL SELECT 'meeting_proposals',count(*) FROM meeting_proposals UNION ALL SELECT 'votes',count(*) FROM votes UNION ALL SELECT 'bookings',count(*) FROM bookings UNION ALL SELECT 'messages',count(*) FROM messages UNION ALL SELECT 'notification_events',count(*) FROM notification_events UNION ALL SELECT 'operation_idempotency',count(*) FROM operation_idempotency; SELECT last_value,is_called FROM bookings_booking_order_seq; SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock';"
}
```

На всех трёх aliases PostgreSQL18.6/PostGIS3.6.4, Flyway15 rows/min001/max015/bool_and(success)=true; все12 fixture tables0, lock waiters0. Identity last_value/is_called: query18/true, warning13/true, confirm43/true. Gaps сохранены. Production/schema, external statuses, shared/master документы и Git исполнителем не менялись; дополнен только этот протокол фактическими результатами.
