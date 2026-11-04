# M4.T5.B04 / T109 — pairwise priority snapshots

Дата: 04.11.2026. Результат: локальный backend PASS. База опубликованного main `b29e7afc5551b7afdf7ff6765f4a9b7f80815135`, ветка `feat/booking-priority-close`; subject `Verify pairwise booking priority snapshots`, commit `2cb164bcdf9a24bea2ebdda14b3ac0ecee7ac62f`; final task review contracts APPROVE подтверждён Lead. Исполнитель pool7/group10. T110 данным результатом не закрыт.

## Сверка и завершённые изменения

Прочитаны B04, родительский контракт M4.T5, M0.T4/T5 и фактические MeetingProjection/MeetingModels/MeetingRoutes. Оба существующих пути MeetingProjection.read/readMany выбирают только same-Point direct ACTIVE overlaps, исключают собственный bookingId, используют strict `[start,end)` и ORDER BY booking_order; корректная реализация не переписана. Добавлен только `BookingPairwisePriorityIntegrationTest`, production/schema изменений и подтверждённых production findings нет.

## Реальный native API/WS сценарий

Fixture создаёт A=[0,10), B=[5,15), C=[10,20) в двух темах одной Point, ещё adjacent=[20,30), широкую CANCELLED booking и широкую ACTIVE booking другой Point. A/B/C имеют одинаковый bookedAt; UUID A специально больше UUID C при меньшем bookingOrder, поэтому ожидаемый порядок списка B [A,C] отличается от UUID sorting и не определяется timestamp.

1. Настоящие loopback Netty/JDK HTTP GET proposal и GET chat proposals совпали по полному canonical JSON. Exact BookingConflict[] из шести полей bookingId/proposalId/startsAt/endsAt/bookedAt/bookingOrder дал A:[B], B:[A,C], C:[B]. Транзитивных A↔C/global rank нет. Adjacent, CANCELLED и different Point не попали в эти списки; у adjacent/cancelled/otherPoint собственные списки пусты. Exact conflict JSON не содержит private account fields. Сравнение SQL snapshots до/после canonical reads подтвердило отсутствие мутаций.
2. Native PUT vote=false для B уменьшил count до1 и отменил её booking. Настоящие WS chat-scoped proposal.updated(revision4) и booking.changed с точным proposalId/bookingId/status=cancelled/cancelReason=insufficient_votes доставлены после операции. Canonical GET/list заново дали A:[], C:[], B cancelled/[]; orders и bookedAt A/C не изменились. Native повтор desired false не изменил SQL state и не создал новые WS events перед упорядоченным USER marker.
3. Native vote=true восстановил ready revision5; WS proposal.updated это подтвердил. Native POST booking с Boolean true/current revision5 создал новую booking B revision6 и order больше всех шести исходных orders. ID новый, list новой B [A,C] в server order, A:[newB], C:[newB], survivors A/C сохранили прежние orders. Отменённая старая booking не используется как active conflict. Adjacent/CANCELLED/otherPoint по-прежнему исключены. Native WS booking.changed активной новой booking и canonical GET/list согласованы.
4. Same-key retry POST вернул тот же committed response без изменения snapshot/effects/sequence; WS перед следующим ordered USER marker не содержал дубликатов. Shared WS payload proposal.updated не содержит viewerGoing. Сообщения booking.changed остались в исходном chat scope; рассылка private booking ID соседним чатам или новый WS snapshot contract не вводились. Fresh соседние pairwise значения доказаны canonical GET/list после операции.

Это локальное исполнение API/DB/WS. Приоритет отображения попарный, физическое монопольное право площадки не утверждается. BG-1, provider/M5 delivery, mobile, T110/остальные компоненты и общий M4 не закрыты; публичность routes/deployment не менялись.

## Изоляция и точные команды

Workdir каждой команды `C:\Users\lolut\.codex\worktrees\booking-overlap\backend`. Это переиспользованный managed worktree. Переиспользован собственный контейнер `codex-m4-overlap` ID `0cfd2641af1133496d396305d69ecadeaab5a1237330f668eca803166c5b80a7`, историческая label `codex.group=9`, `codex.task=m4-t087-t114`; текущее исполнение group10, label не переименовывалась. Targeted inspect подтвердил единственный loopback binding127.0.0.1:55487. Новая disposable alias `roy_m4_booking_priority` создана только внутри этого контейнера; чужие DB/ports не использовались. Credentials/JWT/env содержимое не выводились.

```powershell
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_booking_priority -DatabasePort 55487
docker inspect codex-m4-overlap --format '{{.Id}} {{json .Config.Labels}} {{json .HostConfig.PortBindings}}'
docker exec codex-m4-overlap createdb -U $env:DATABASE_USER roy_m4_booking_priority
.\gradlew.bat integrationTest --tests com.example.meetings.BookingPairwisePriorityIntegrationTest --offline --no-daemon --max-workers=1
Get-Content build/test-results/integrationTest/TEST-com.example.meetings.BookingPairwisePriorityIntegrationTest.xml -TotalCount 5
docker exec codex-m4-overlap psql -U $env:DATABASE_USER -d roy_m4_booking_priority -Atc "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT count(*),min(version),max(version),bool_and(success) FROM flyway_schema_history; SELECT 'users',count(*) FROM users UNION ALL SELECT 'auth_sessions',count(*) FROM auth_sessions UNION ALL SELECT 'points',count(*) FROM points UNION ALL SELECT 'point_topics',count(*) FROM point_topics UNION ALL SELECT 'chats',count(*) FROM chats UNION ALL SELECT 'chat_memberships',count(*) FROM chat_memberships UNION ALL SELECT 'meeting_proposals',count(*) FROM meeting_proposals UNION ALL SELECT 'votes',count(*) FROM votes UNION ALL SELECT 'bookings',count(*) FROM bookings UNION ALL SELECT 'messages',count(*) FROM messages UNION ALL SELECT 'notification_events',count(*) FROM notification_events UNION ALL SELECT 'operation_idempotency',count(*) FROM operation_idempotency; SELECT last_value,is_called FROM bookings_booking_order_seq; SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock';"
```

Первый реальный focused run: BUILD SUCCESSFUL36s, 6 actionable tasks (4executed/2up-to-date). Actual XML `tests=1 skipped=0 failures=0 errors=0`, timestamp `2026-11-04T15:44:52.916Z`, duration5.429s (case5.426s). Required integrationTest реально исполнен на PostgreSQL, DB/HTTP/WS не mocked. JDK17/Gradle9.5.1/backend1.0.0-SNAPSHOT; только существующие Gradle deprecation и ChatSendLeaveRace unused-expression warnings.

После run SQL: PostgreSQL18.6 (Debian18.6-1.pgdg13+2), PostGIS3.6.4; Flyway15rows/min001/max015/all success=true. Все12 перечисленных fixture tables count0, lockwaiters0. bookings_booking_order_seq last_value7/is_called=true; identity gaps сохранены. WebSocket/server закрыты, cleanup удалил только UUID fixture. Остаточных блокеров direct pairwise projection не обнаружено; соседние ещё не выполненные задачи не заявлены готовыми.

T109 independent read-only task review by contracts: APPROVE before commit, no confirmed findings. Lead synchronized all five external status places: T5 backend4/5, checklist105/455, M4 backend21/28 at this checkpoint.


# M4.T5.B05 / T110 — author close eligibility boundaries

Дата: 04.11.2026. Локальный backend PASS; база T109 `2cb164bcdf9a24bea2ebdda14b3ac0ecee7ac62f`; subject `Enforce author close eligibility boundaries`. Фактический application owner close — BookingService, историческая ссылка карточки на ProposalService не использовалась как основание переноса кода.

## Actual RED и два раздельно согласованных production изменения

До изменений новый native Netty/JDK HTTP тест отправил `POST /api/v1/proposals/{id}/close` с `expectedRevision:"3"`. Результат200 вместо ожидаемого422: стандартная JSON Long coercion фактически закрыла proposal. Actual RED XML сохранён в ignored `build/t110-red/BookingAuthorCloseIntegrationTest.xml`: tests1/skipped0/failures1/errors0, timestamp `2026-11-04T15:51:04.959Z`, duration3.667s, BUILD FAILED18s. Lead и независимый contracts reviewer прочитали сохранённый XML, подтвердили P2 и явно одобрили только CloseProposalInput.expectedRevision аннотацию существующим StrictBookingRevisionSerializer. Применена ровно эта annotation; negative guard, native Long/encodeLong, BookingInput и global JSON не менялись.

Отдельно Lead и contracts одобрили минимальный bounded server-clock seam: последний optional private constructor `closeClock: java.time.Clock? = null` в BookingService; только close после прежних locks/guards читает `closeClock?.instant() ?: databaseNow(connection)`. Production default остался PostgreSQL wall time; book/projection и клиентские DTO/time не менялись. Это позволило выполнить точные временные границы через настоящий HTTP без sleep или объявления приблизительного времени равенством. Никакой другой production/schema правки нет; cancellation callers/jobs соседней группы не редактировались.

## Полная native HTTP/WS acceptance

Добавлен `BookingAuthorCloseIntegrationTest` с четырьмя scenarios:

1. Strict revision matrix: quoted3/quoted3.0/null/Boolean/object/array/3.0/3.5/Long overflow/negative1/Long.MIN_VALUE/missing property дали422 INVALID_FIELD. Native Long.MAX_VALUE корректно распарсен и дал409 STALE_STATE. Каждый отказ сохранил scoped SQL snapshot (booking/proposal/vote rows, point timestamps, messages/chat sequence, notification/outcome/job rows и booking_order sequence) и не породил WS перед упорядоченным USER marker.
2. Роль проверена отдельно: active bookedByUserId=peer не является proposer и получил403 FORBIDDEN. Автор без active membership получил403 NOT_MEMBER; без auth401, stale revision409, закрытое предложение409 PROPOSAL_CLOSED, случайный resource404. Refusals без эффектов. Active proposer смог закрыть peer-booked booking: действие разрешено автору, не booking owner.
3. Для каждого booked/unbooked OPEN proposal startsAt прочитан из PostgreSQL как persisted Instant с microsecond precision. В реальный HTTP сервер инъецирован Clock.fixed UTC на persisted startsAt−1µs / ровно startsAt / startsAt+1µs: шесть отдельных eligible fixtures. До начала получен200 closed, updatedAt и cancelledAt (для booked) равны точному fixed UTC instant, startsAt остался canonical UTC. Ровно/после начала409 MEETING_ALREADY_STARTED, весь SQL snapshot/sequence неизменён и нет WS. Небронированное предложение до начала закрыто без создания booking; забронированное отменено. Это точная серверная equality evidence; scheduler/sleep не используются для доказательства границы.
4. Atomicity: native default-PG-clock close с активной booking и тремя синтетическими reminder jobs (PENDING, PROCESSING, SENT). Unique fixture constraint trigger DEFERRABLE INITIALLY DEFERRED на successful close operation_idempotency вызывает SQL23514 именно при COMMIT; pg_trigger flags реально проверены. Ответ500 INTERNAL_ERROR, booking/proposal/votes/messages/notifications/outcome/jobs/timestamps/sequence полностью откатились, canonical GET прежний, WS success отсутствует. Trigger удалён finally, same-key retry200. SQL подтвердил lifecycleCLOSED/reasonAUTHOR/revision4; bookingCANCELLED/reasonCLOSED_BY_AUTHOR; два votes сохранены, ровно два system messages (meeting_booking_cancelled + meeting_closed), одно booking_cancelled событие peer; pending/processing reminder jobs CANCELLED, claim/lease/sent очищены, completed_at установлен; прежний SENT timestamp/completed_at сохранены. Точный SUCCESS outcome/result_ref/response_snapshot совпал с200 Proposal. Native WS отдал два system messages, proposal.updated(revision4), booking.changed(cancelReason closed_by_author), без viewerGoing/private payload expansion. Canonical GET совпал с ответом.

После этого fixture явно увеличила сохранённую revision до5: same-key retry вернул прежний committed revision4 response, но canonical revision5 и весь более новый SQL snapshot сохранились. Reuse того же terminal key на другом proposal дал409 IDEMPOTENCY_KEY_REUSED без эффектов/WS. Это проверяет snapshot replay, а не переписывание newer state старым ответом.

## Команды, XML, regression и собственные БД

Workdir всех commands `C:\Users\lolut\.codex\worktrees\booking-overlap\backend`. Собственные новые aliases созданы в переиспользованном codex-m4-overlap ID `0cfd2641af1133496d396305d69ecadeaab5a1237330f668eca803166c5b80a7`, только127.0.0.1:55487. Историческая label group9 сохранена; текущий исполнитель group10. Чужая исходная alias/порт55484 не использованы. Env/JWT/credentials не печатались.

```powershell
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_booking_close -DatabasePort 55487
docker exec codex-m4-overlap createdb -U $env:DATABASE_USER roy_m4_booking_close
.\gradlew.bat integrationTest --tests com.example.meetings.BookingAuthorCloseIntegrationTest --offline --no-daemon --max-workers=1
New-Item -ItemType Directory -Force build/t110-green | Out-Null
Copy-Item -LiteralPath build/test-results/integrationTest/TEST-com.example.meetings.BookingAuthorCloseIntegrationTest.xml -Destination build/t110-green/BookingAuthorCloseIntegrationTest.xml
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_booking_guards -DatabasePort 55487
docker exec codex-m4-overlap createdb -U $env:DATABASE_USER roy_m4_booking_guards
.\gradlew.bat integrationTest --tests com.example.meetings.BookingGuardsIntegrationTest --offline --no-daemon --max-workers=1
```

Первая close команда исполнялась сначала quoted-revision RED, затем после двух approved scopes с полными четырьмя tests GREEN. Actual GREEN XML tests4/skipped0/failures0/errors0, timestamp `2026-11-04T15:54:32.084Z`, duration9.065s (boundary6.245s, strict revision0.937s, rollback0.912s, role/current-state0.968s), BUILD SUCCESSFUL34s. XML сохранён перед regression. Existing BookingGuardsIntegrationTest отдельно прошёл на собственной alias roy_m4_booking_guards: actual tests1/skipped0/failures0/errors0, timestamp `2026-11-04T15:55:07.198Z`, duration4.718s, BUILD SUCCESSFUL17s. Проверена сохранность native BookingInput strict revision/eligibility после reuse serializer. Это focused regression, не весь backend suite. JDK17/Gradle9.5.1/backend1.0.0-SNAPSHOT; только существующие deprecation/unnecessary-non-null warnings.

SQL cleanup выполнялся для обеих aliases (для close дополнительно fault query):

```powershell
docker exec codex-m4-overlap psql -U $env:DATABASE_USER -d roy_m4_booking_close -Atc "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT count(*),min(version),max(version),bool_and(success) FROM flyway_schema_history; SELECT 'users',count(*) FROM users UNION ALL SELECT 'auth_sessions',count(*) FROM auth_sessions UNION ALL SELECT 'points',count(*) FROM points UNION ALL SELECT 'point_topics',count(*) FROM point_topics UNION ALL SELECT 'chats',count(*) FROM chats UNION ALL SELECT 'chat_memberships',count(*) FROM chat_memberships UNION ALL SELECT 'meeting_proposals',count(*) FROM meeting_proposals UNION ALL SELECT 'votes',count(*) FROM votes UNION ALL SELECT 'bookings',count(*) FROM bookings UNION ALL SELECT 'messages',count(*) FROM messages UNION ALL SELECT 'notification_events',count(*) FROM notification_events UNION ALL SELECT 'operation_idempotency',count(*) FROM operation_idempotency UNION ALL SELECT 'device_registrations',count(*) FROM device_registrations UNION ALL SELECT 'push_delivery_jobs',count(*) FROM push_delivery_jobs; SELECT last_value,is_called FROM bookings_booking_order_seq; SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'; SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'close_commit_%'; SELECT count(*) FROM pg_proc WHERE proname LIKE 'close_commit_%';"
```

Для regression тот же versions/history/14-table/sequence/waiters SQL выполнен с `-d roy_m4_booking_guards` (без fixture trigger queries). Обе aliases: PostgreSQL18.6/PostGIS3.6.4,V001–V015success (15rows/min001/max015/bool_and=true),14tables0,lockwaiters0. Close fault triggers/functions0. booking_order sequence close7/true, guards2/true; gaps сохранены. Все native servers/sockets и собственные UUID fixtures очищены. BG-1, внешняя delivery/provider acceptance, Android/iOS, соседние scheduler tasks и общий M4 не закрыты. M4.T5 backend B01–B05 теперь локально выполнены, смешанный родитель остаётся частичным до остальных критериев.

T110 independent read-only final task review by contracts: APPROVE after the approved revision correction, CLOSE-only Clock seam, real native GREEN and focused booking regression. Lead synchronized all five external status places: T5 backend5/5, checklist107/455, M4 backend23/28.
