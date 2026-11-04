# M4.T5.B04 / T109 — pairwise priority snapshots

Дата: 04.11.2026. Результат: локальный backend PASS. База опубликованного main `b29e7afc5551b7afdf7ff6765f4a9b7f80815135`, ветка `feat/booking-priority-close`; subject `Verify pairwise booking priority snapshots`. Исполнитель pool7/group10. T110 данным результатом не закрыт.

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
