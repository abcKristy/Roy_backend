# T145. Локальный пакет серверных результатов B5.4–B5.5

Дата проверки — 07.11.2026. Код кандидата — `51795ce`, база — `5c5e0e9`.
Сервер проверен на Windows, JDK 17 и Gradle 9.5.1. Изолированная PostgreSQL 18.6 с PostGIS 3.6.4 получила и сверила V001–V016. Новых миграций нет.

## Реализация и ревью

- **История уведомлений.** Off→on очищает центр и не создаёт событие по старой позиции. Личный курсор устойчив к одинаковым временам и новым событиям. Чужие и скрытые события недоступны, повторное прочтение сохраняет первый server readAt.
- **Напоминания.** Точное UTC-окно и своевременная бронь, прогресс при ограниченной выборке, конкурентная дедупликация, независимая in-app история. Перед отправкой проверяется актуальность брони, предложения, членства, флагов и установки. После остановки и перезапуска актуальная работа продолжается, просроченное окно остаётся пропущенным.
- **Приёмка.** [Матрица T142](mvp-backend-coverage.md) связывает 48 REST-операций и серверные инварианты с проверками. T143 проходит гостевую карту, регистрацию, создание точки и вход в чат, сообщение после commit по WebSocket, встречу и бронь, профиль, отзыв сеанса и личное уведомление. Состояние проверено отдельными JDBC-запросами.

Для каждого шага и каждой интегрируемой ветки выполнено независимое ревью. При локальном переносе веток проверена эквивалентность патчей через `git range-diff`. Исправлены обнаруженные случаи stale nearby, голодания ограниченной выборки и закрытого предложения со старой активной бронью.

Коммиты проверенного кода и матрицы:

- `7d25750` — T134: Verify extra-off cleanup and session isolation.
- `b4e9e73` — T135: Verify and protect personal notification history.
- `5c0da17` — T136: Verify and protect idempotent notification read.
- `4c3ec64` — T137: Define exact UTC reminder eligibility.
- `c3a5024` — T138: Advance limited reminder polls past claimed recipients.
- `c72c492` — T139: Verify independent in-app and push reminder channels.
- `37385d8` — T140: Revalidate proposal state before reminder delivery.
- `b8ed93b` — T141: Verify reminder restart and missed-window behavior.
- `5a77222` — T142: Map backend MVP contracts to executable coverage.
- `51795ce` — T143: Verify backend MVP through local HTTP, WebSocket and PostGIS.

## Результаты проверок

- `gradlew.bat --offline check installDist` — PASS. Выполнено 110 из 346 JUnit-сценариев, 236 DB-сценариев пропущены этим режимом. Ошибок нет.
- Обязательный интеграционный прогон — 67 сценариев, 0 ошибок и пропусков. Каждый набор запускался со своим мигрированным fixture в новом временном PostGIS, привязанном к loopback.
- `verifyApiDocs` — 48 операций и 456 валидных примеров. `git diff --check` прошёл.
- Прежняя ошибка M4 без БД исправлена в тестовой фикстуре. Обычный прогон пропускает её по guard, а в обязательном прогоне сценарий действительно выполняется.

Для воспроизведения используйте [правила изоляции](integration-test-safety.md). После миграции запустите `integrationTest --tests com.example.<класс> --offline` с указанным алиасом одноразовой БД. Для `roy_b5_mvp_chain` каждый раз используйте новую пустую БД, подготовленную только миграциями.

- `roy_b5_mvp_chain` — `acceptance.MvpBackendChainIntegrationTest`.
- `roy_m4_cancel_jobs` — `jobs.BookingReminderCancellationIntegrationTest`.
- `roy_b55_reminders` — `jobs.ReminderSchedulerIntegrationTest`.
- `roy_m1t2_step6proposal` — `meetings.ProposalServiceIntegrationTest`.
- `roy_m4_acceptance_fixture` — `meetings.MeetingAcceptanceFixtureIntegrationTest`, `meetings.MeetingRestChainIntegrationTest`.
- `roy_m3_chat` — `acceptance.ChatRestAcceptanceIntegrationTest`, `acceptance.ChatWebSocketAcceptanceIntegrationTest`.
- `roy_m1t2_step7jobs` — `jobs.JobsIntegrationTest`.
- `roy_m1t2_step7notifications` — `api.NotificationRoutesIntegrationTest`, `api.NotificationHistoryIntegrationTest`, `notifications.NotificationServiceIntegrationTest`.
- `roy_m5t124_settings` — `api.NotificationSettingsIntegrationTest`.
- `roy_m4_events` — `meetings.ProposalEventsIntegrationTest`.
- `roy_m5t119_avatar` — `api.AvatarSwapRoutesIntegrationTest`.
- `roy_m5t120_read` — `api.AvatarReadRoutesIntegrationTest`.

## Передача на дальнейшие локальные проверки

Результаты готовы как вход для B6. Формальное решение BG-1 оформляется в T173–T175 после остальных локальных серверных проверок. Мобильная приёмка относится к следующим этапам.

T144 остаётся открытым для этапа 5. На этапах 4–5 подключаются и принимаются приватный бакет и KMS, утверждённая публичная политика, целевые ключи, внешние карта/геокодер и реальные отправки RuStore/APNs. В локальных прогонах провайдеры заменены контролируемыми ответами.
