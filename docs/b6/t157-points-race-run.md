# T157. Точки, темы и квоты

Локальный прогон 07.11.2026 на `0a2ac71`, PostgreSQL/PostGIS/ранг Flyway `18.6 (Debian 18.6-1.pgdg13+2)|3.6.4|16`. Исполнитель проверки — Codex.
Обязательные тесты PostgreSQL/PostGIS — **17 PASS**, без пропусков в принятом наборе. Первый запуск и корректировки изоляции отражены в индексе. [Индекс классов и сценариев](functional-evidence.json).

- Создание точки, темы, чата и членства фиксируется атомарно, а потерянный ответ и повтор ключа сохраняют одну операцию.
- Проверены rolling 7/30 суток, блокировка и гонка за последний слот, дубли темы, а также конкурентные вступление и выход последнего участника. Итог проверен через JDBC и HTTP.

## Воспроизведение

- `roy_m2t2_guards` — `com.example.api.PointQuotaBlockRouteIntegrationTest`, `com.example.points.PointCreationGuardsIntegrationTest`.
- `roy_m2t2_ledger` — `com.example.points.PointCreationLedgerIntegrationTest`, `com.example.points.PointQuotaSqlIntegrationTest`.
- `roy_m2t2_nearby` — `com.example.api.PointCreationContextRoutesIntegrationTest`.
- `roy_m2t3_autojoin` — `com.example.points.AddTopicAutojoinIntegrationTest`.
- `roy_m2t4_filter` — `com.example.api.PointMapFilterValidationIntegrationTest`, `com.example.points.PointMapFilterSqlIntegrationTest`.
- `roy_m2t5_race` — `com.example.points.PointJoinLastLeaveRaceIntegrationTest`.
- `roy_m2t6_atomic_failures` — `com.example.api.M2AtomicCreateFailureIntegrationTest`.
- `roy_m2t6_quota_edges` — `com.example.api.M2MonthlyQuotaBoundaryIntegrationTest`.
- `roy_m2t6_quota_topic_filter` — `com.example.api.M2QuotaTopicFilterAcceptanceIntegrationTest`.
- `roy_m2t6_tcp_replay` — `com.example.api.M2LostTcpCreateResponseIntegrationTest`.

Для обязательного прогона используйте `pwsh -File tools/b6/run.ps1` из корня backend после офлайн-`installDist`. Параметр `-Suite` позволяет выбрать один или несколько алиасов из списка. Прогон создаёт собственный контейнер и отклоняет пропуски.
Применимые проверки целевой среды повторяются на этапе 5.
