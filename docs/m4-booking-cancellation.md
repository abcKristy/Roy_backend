# M4 booking cancellation — выполненные результаты

## T101 / M4.T4.B01 — 04.11.2026

Subject: `Unify transactional booking cancellation policy`.
Исходный backend: `ae43668958e3034ada605809d29f1d09a95969d4`; локальный source diff T101.

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
