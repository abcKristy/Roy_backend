# M4 booking cancellation — выполненные результаты

## T101 / M4.T4.B01 — 04.11.2026

Subject: `Unify transactional booking cancellation policy`.
Исходный backend: `ae43668958e3034ada605809d29f1d09a95969d4`. Результат T101: commit `d71a65caaa2a9c7efdeb417c341188e6439d8db4`; независимый task review `group_review` — APPROVE (подтверждено Lead).

`BookingCancellationPolicy` выполняет один guarded UPDATE внутри caller-owned JDBC transaction: сохранённые действительные votes после удаления пересчитываются, ACTIVE booking при count≤1 становится CANCELLED с PostgreSQL cancelled_at и INSUFFICIENT_VOTES. Строка, id, booking_order и created_at остаются. Ограничения по starts_at нет: потеря голоса после старта также отменяет бронь. TIME ALONE сохраняет booked history; поздний PUT vote остаётся Started. COUNT использует существующие vote rows: callers удаляют голос под существующими actor/point/proposal locks, до пересчёта. Helper не запускает отдельную транзакцию и не меняет locks.

Один путь вызывается из ProposalService.vote, PointWriteService.leave и AccountDeletionService.delete. Существующие revision, reminder-job invalidation, system messages, notifications и projections остаются у callers. Возвращаются только фактические ACTIVE→CANCELLED переходы, повторный пересчёт не меняет cancelled_at и не инициирует повторных side effects. Новых миграций нет.

### Реальное RED → GREEN

Обе команды выполнены из `C:\Users\lolut\.codex\worktrees\booking-cancellation\backend`, на собственном disposable контейнере `codex-m4-cancel`, ID `437d7d852031a32f18fb4ccc251d9f7e8e9a401e3bdfeae82625991182c97aaf`, labels `codex.group=7`, `codex.task=m4-t087-t114`, единственный binding `127.0.0.1:55486`, БД `roy_m4_cancel_policy`. PostgreSQL18.6, PostGIS3.6.4, Flyway V001–V015: 15 успешных миграций, max015.

```powershell
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_cancel_policy -DatabasePort 55486
.\gradlew.bat integrationTest --tests com.example.meetings.BookingCancellationPolicyIntegrationTest --offline --no-daemon --max-workers=1
```

RED на неизменённом production: BUILD FAILED, 2м18с; фактический XML tests=3/skipped=0/failures=2/errors=0. Leave после startsAt и account deletion после startsAt: `expected CANCELLED but was ACTIVE` при 2→1. Контроль future PUT vote=false PASS. Причина — расходящиеся starts_at>clock_timestamp() guards в leave/deletion. После RED SQL users/points/proposals/votes/bookings/messages=0/0/0/0/0/0.

GREEN после общего пути: BUILD SUCCESSFUL, 1м18с; `build/test-results/integrationTest/TEST-com.example.meetings.BookingCancellationPolicyIntegrationTest.xml`: tests=3/skipped=0/failures=0/errors=0, suite4.029с, timestamp2026-11-04T14:56:00.806Z.

Фактически выполнены:

- Leave после startsAt: denied late PUT и время сами по себе не меняют bookings; 2→1 отменяет, 3→2 оставляет ACTIVE; оставшиеся участники и общая точка сохраняются; чужая точка/чат и booking не меняются; повтор leave AlreadyLeft без дубликата.
- Account deletion после startsAt: 2→1 отменяет, 3→2 сохраняет ACTIVE и анонимизирует владельца; общие точки не удаляются; чужая точка/чат не меняются; повтор deletion SessionExpired без новой отмены.
- Future PUT vote=false: 2→1 отменяет, 3→2 сохраняет; повтор desired=false Unchanged; прямой повтор общего recalculation возвращает пустой список, сохраняет booking identity, timestamp/history и одно cancellation SYSTEM сообщение.

После GREEN проверены фактические SQL counts: users/points/meeting_proposals/votes/bookings/messages/notification_events/push_delivery_jobs/operation_idempotency = 0/0/0/0/0/0/0/0/0. Проверка Flyway: count15/bool_and(success)=true/max(version)=015.

### Область результата

Это локальная core-приёмка общего правила T101. Полная специализированная приёмка hooks T102/T103, событий T104 и jobs T105 этим результатом не закрыта. Базовые public routes не расширялись, deployment не выполнялся. M5 delivery/provider, клиент Android/iOS, общая T3–T5/T6 и BG-1 не закрыты. Остаточных дефектов в проверенных сценариях T101 нет. Применённые миграции, конфигурация безопасности и внешние сервисы не изменялись.

Lead: independent read-only task review APPROVE before commit; all five external status places synchronized. Checkpoint including parallel T099/T101/T106: checklist99/455, M4 backend15/28.

## T102 / M4.T4.B02 — 04.11.2026

Subject: `Verify atomic booking cancellation on unvote`.
Исходный backend: `d71a65caaa2a9c7efdeb417c341188e6439d8db4`. Production не менялся: после T101 существующий ProposalService.vote под согласованными locks удаляет только голос actor, повышает revision на действительном изменении, вызывает общий пересчёт и возвращает canonical Proposal после commit.

### Реальная native HTTP приёмка

Новый `BookingCancellationUnvoteIntegrationTest` поднимает Netty на loopback/случайном порту; JDK HttpClient выполняет JWT-authenticated HTTP PUT/GET через реальные routing/session/JDBC операции. Отдельная БД `roy_m4_cancel_unvote` создана внутри того же собственного `codex-m4-cancel` с binding127.0.0.1:55486; fixture строго проверяет alias. PostgreSQL18.6/PostGIS3.6.4, Flyway V001–V015 (count15/all success/max015).

```powershell
# workdir: C:\Users\lolut\.codex\worktrees\booking-cancellation\backend
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_cancel_unvote -DatabasePort 55486
.\gradlew.bat integrationTest --tests com.example.meetings.BookingCancellationUnvoteIntegrationTest --offline --no-daemon --max-workers=1
```

Фактический PASS: BUILD SUCCESSFUL41с. XML `build/test-results/integrationTest/TEST-com.example.meetings.BookingCancellationUnvoteIntegrationTest.xml`: tests=1/skipped=0/failures=0/errors=0, suite7.982с, timestamp2026-11-04T15:08:37.763Z. Первичная ошибка компиляции нового теста (generic Map против JsonObject) устранена до исполнения; production RED не обнаружен и не заявлен.

В одном сквозном сценарии выполнены:

- 401 AUTH_REQUIRED, 403 NOT_MEMBER, 404 RESOURCE_GONE, 422 INVALID_FIELD при попытке задать чужой userId; 409 MEETING_ALREADY_STARTED/PROPOSAL_CLOSED. Во всех случаях полный scoped business snapshot остаётся прежним. Active member без собственного голоса получает canonical booked с viewerGoing=false; чужой голос не снимается и revision/side effects не меняются.
- Constraint trigger `AFTER UPDATE ON bookings DEFERRABLE INITIALLY DEFERRED`, ограниченный fixture booking UUID/status=CANCELLED, отклоняет COMMIT синтетическим23514. Из pg_trigger подтверждены tgdeferrable/tginitdeferred=true. Native PUT получает500 INTERNAL_ERROR; полный snapshot bookings/votes/proposals с revision+timestamps/point.updated_at/messages+chatseq/notification_events/jobs/outcomes точно равен исходному, GET остаётся booked с двумя голосами. Fault снимается в finally.
- Тот же desired=false после снятия fault получает200: один оставшийся peer vote, revision2→3, status=voting, прежние booking id/order/author/bookedAt, status=cancelled, ненулевой cancelledAt, insufficient_votes. Ответ полностью равен последующему GET. Созданы одно cancellation SYSTEM сообщение, два booking_cancelled notifications для остальных участников, одна реальная cancellation job на synthetic installation; ранее PENDING reminder job становится CANCELLED. Provider/worker не запускался.
- Повтор desired=false с тем же transport Idempotency-Key не меняет ни одну строку, seq или timestamp. Vote — desired-state PUT, отдельного historical outcome/replay snapshot нет (operation_idempotency actor count0). После intervening true ответ получает актуальный revision4/ready_to_book, прежнюю cancelled историю; следующий false получает revision5 и ту же историю/одно cancellation сообщение/два cancellation notifications. Последний повтор равен актуальному GET и снова не меняет snapshot.

После finally cleanup фактический SQL users/points/meeting_proposals/votes/bookings/messages/notification_events/push_delivery_jobs/operation_idempotency/device_registrations/fixture triggers/fixture functions = 0/0/0/0/0/0/0/0/0/0/0/0. Broad deletes и sequence reset не применялись; удаляются только fixture point/users и именованный fault.

### Границы T102

T102 — локальная core HTTP/transaction приёмка unvote. WS delivery и полная cancellation event/job приёмка относятся к отдельным T104/T105 и этим тестом не заявлены. Полные конкурентные гонки, leave hook T103, общая T3–T5/T6, M5 delivery/provider, Android/iOS клиент и BG-1 не закрыты. Baseline public routes не расширялись, deployment не выполнялся, применённые миграции не менялись. Остаточных дефектов проверенной области T102 нет.

### T102 — исправление одобренного P2 evidence finding, 04.11.2026

Независимый task review group_review обнаружил P2: history assertion читала отсутствующее transport поле createdAt через nullable indexing, поэтому null==null не проверяло время бронирования. Lead одобрил конкретное исправление. В новом тесте history loop использует реальное bookedAt; обе стороны всех полей id/proposalId/bookedByUserId/bookingOrder/bookedAt читаются через getValue, отсутствие теперь приводит к отказу теста. cancelledAt также обязателен через getValue, отличен от JsonNull и успешно разобран Instant.parse. Production не менялся.

Повторена ровно та же focused команда выше на roy_m4_cancel_unvote:55486. Фактический rerun: BUILD SUCCESSFUL33с; XML tests1/skipped0/failures0/errors0, suite5.512с, timestamp2026-11-04T15:13:07.380Z. Повторно выполнены native HTTP COMMIT rollback/retry, history, guards, no-op и текущий desired-state. После rerun SQL прикладных данных/devices/fault trigger/function снова12×0; Flyway15/all success/max015. Исправление ограничено test evidence; границы локального T102 сохраняются.

T102 independent read-only repeat task review by group_review: APPROVE after the approved evidence correction and real rerun. Lead synchronized all five external status places; checkpoint checklist102/455, T4 backend2/5, M4 backend18/28.
