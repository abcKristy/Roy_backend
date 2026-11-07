# T160. Уведомления и ошибки доставки

Локальный прогон 07.11.2026 на `0a2ac71`, PostgreSQL/PostGIS/ранг Flyway `18.6 (Debian 18.6-1.pgdg13+2)|3.6.4|16`. Исполнитель проверки — Codex.
Обязательные тесты PostgreSQL/PostGIS — **46 PASS**, без пропусков в принятом наборе. Первый запуск и корректировки изоляции отражены в индексе. [Индекс классов и сценариев](functional-evidence.json).

- Nearby проверяется при создании предложения, с opt-in, границей 1 км и публичной целью. Push-only остаётся вне личной видимой истории.
- Напоминания проверены для окна T−1 ч, отмены, повторного claim, смены привязки и рестарта. Подставной транспорт покрывает временные и постоянные отказы. Реальная отправка на устройства относится к этапу 5.

## Воспроизведение

- `roy_b55_reminders` — `com.example.jobs.ReminderSchedulerIntegrationTest`.
- `roy_m1t2_step3notify` — `com.example.persistence.JdbcNotificationWriterIntegrationTest`.
- `roy_m1t2_step6proposal` — `com.example.meetings.ProposalServiceIntegrationTest`.
- `roy_m1t2_step7jobs` — `com.example.jobs.JobsIntegrationTest`.
- `roy_m1t2_step7notifications` — `com.example.api.NotificationHistoryIntegrationTest`, `com.example.api.NotificationRoutesIntegrationTest`, `com.example.notifications.NotificationServiceIntegrationTest`.
- `roy_m1t2_step8delete` — `com.example.devices.DeviceRebindDeliveryConcurrencyIntegrationTest`.
- `roy_m4_cancel_jobs` — `com.example.jobs.BookingReminderCancellationIntegrationTest`.
- `roy_m4_events` — `com.example.meetings.ProposalEventsIntegrationTest`.
- `roy_m5t124_settings` — `com.example.api.NotificationSettingsIntegrationTest`.
- `roy_m5t125_devices` — `com.example.api.DeviceRegistrationSecurityIntegrationTest`.
- `roy_m5t126_crypto` — `com.example.api.PushTokenEncryptionIntegrationTest`.

Дополнительные классы из обычного локального `check`:

- `com.example.external.RuStorePushSenderTest` — 2 PASS.
- `com.example.external.ApnsPushSenderTest` — 2 PASS.
- `com.example.jobs.ReminderEligibilityTest` — 2 PASS.

Для обязательного прогона используйте `pwsh -File tools/b6/run.ps1` из корня backend после офлайн-`installDist`. Параметр `-Suite` позволяет выбрать один или несколько алиасов из списка. Прогон создаёт собственный контейнер и отклоняет пропуски.
Применимые проверки целевой среды повторяются на этапе 5.

Логическая запись уведомления дедуплицируется. Job с истекшей lease не подтверждается прежним владельцем, повторный claim восстанавливает то же событие. Поздний результат транспорта не оживляет отменённую доставку. Классификация HTTP/таймаутов проверена локальными ответами адаптеров; обработка повторов проверена с PostgreSQL.
