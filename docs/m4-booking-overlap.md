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

Дата: 04.11.2026. Локальный backend PASS; исходная база T106 `480b4ec2427b285f14258e999386987b458c3c4b`; subject `Enforce booking overlap confirmation warning`.

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
