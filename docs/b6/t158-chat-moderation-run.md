# T158. Чат, WebSocket и модерация

Локальный прогон 07.11.2026 на `0a2ac71`, PostgreSQL/PostGIS/ранг Flyway `18.6 (Debian 18.6-1.pgdg13+2)|3.6.4|16`. Исполнитель проверки — Codex.
Обязательные тесты PostgreSQL/PostGIS — **21 PASS**, без пропусков в принятом наборе. Первый запуск и корректировки изоляции отражены в индексе. [Индекс классов и сценариев](functional-evidence.json).

- Сообщение после commit приходит по WebSocket нужным участникам. Повтор ключа сохраняет тот же результат, переподключение использует историю и sequence delta.
- Проверены доступ после выхода, два flood-окна, фильтры, уникальность жалобы и mute на 16-й уникальной жалобе. Отклонённое содержимое не должно появляться в логах.

## Воспроизведение

- `roy_m3_chat` — `com.example.acceptance.ChatRestAcceptanceIntegrationTest`, `com.example.acceptance.ChatSendLeaveRaceIntegrationTest`, `com.example.acceptance.ChatWebSocketAcceptanceIntegrationTest`, `com.example.api.HistoryAccessIntegrationTest`, `com.example.api.HistorySearchIntegrationTest`, `com.example.api.LongMessageFloodIntegrationTest`, `com.example.api.MuteSendGuardIntegrationTest`, `com.example.api.MuteThresholdIntegrationTest`, `com.example.api.PhoneFilterIntegrationTest`, `com.example.api.ProfanityFilterIntegrationTest`, `com.example.api.ReportContextIntegrationTest`, `com.example.api.ReportLifetimeIntegrationTest`, `com.example.api.ReportTransactionIntegrationTest`, `com.example.api.SendInputIntegrationTest`, `com.example.api.SendReplayIntegrationTest`, `com.example.api.ShortMessageFloodIntegrationTest`, `com.example.api.UrlFilterIntegrationTest`, `com.example.realtime.WebSocketLifecycleIntegrationTest`, `com.example.realtime.WebSocketSubscriptionIntegrationTest`.

Дополнительные классы из обычного локального `check`:

- `com.example.chats.MessageContentPolicyTest` — 4 PASS.
- `com.example.chats.ProfanityPolicyTest` — 3 PASS.

Для обязательного прогона используйте `pwsh -File tools/b6/run.ps1` из корня backend после офлайн-`installDist`. Параметр `-Suite` позволяет выбрать один или несколько алиасов из списка. Прогон создаёт собственный контейнер и отклоняет пропуски.
Применимые проверки целевой среды повторяются на этапе 5.

Отдельный PostgreSQL-сценарий модерации проверил эффект жалобы и mute на уровне репозитория. [Дополнительный протокол](functional-supplement.json).
