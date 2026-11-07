# T159. Предложения и конкурентные бронирования

Локальный прогон 07.11.2026 на `0a2ac71`, PostgreSQL/PostGIS/ранг Flyway `18.6 (Debian 18.6-1.pgdg13+2)|3.6.4|16`. Исполнитель проверки — Codex.
Обязательные тесты PostgreSQL/PostGIS — **24 PASS**, без пропусков в принятом наборе. Первый запуск и корректировки изоляции отражены в индексе. [Индекс классов и сценариев](functional-evidence.json).

- Предложение создаётся с нулём голосов. Бронь требует собственный голос, более одного голоса и актуальную ревизию.
- Проверены точные временные границы, пересечение и повторное подтверждение, конкуренция между голосом, бронью и закрытием. REST-результаты сверены с конечными строками БД.

## Воспроизведение

- `roy_m1t2_step6routes` — `com.example.api.MeetingRoutesIntegrationTest`.
- `roy_m4_acceptance_fixture` — `com.example.meetings.MeetingAcceptanceFixtureIntegrationTest`, `com.example.meetings.MeetingConcurrencyIntegrationTest`, `com.example.meetings.MeetingRestChainIntegrationTest`, `com.example.meetings.MeetingTimeBoundsIntegrationTest`.
- `roy_m4_booking_guards` — `com.example.meetings.BookingGuardsIntegrationTest`.
- `roy_m4_overlap_confirm` — `com.example.meetings.BookingOverlapConfirmIntegrationTest`.

Для обязательного прогона используйте `pwsh -File tools/b6/run.ps1` из корня backend после офлайн-`installDist`. Параметр `-Suite` позволяет выбрать один или несколько алиасов из списка. Прогон создаёт собственный контейнер и отклоняет пропуски.
Применимые проверки целевой среды повторяются на этапе 5.

Оракулы конечной БД включают отсутствие активной брони после конкурентного unvote при одном голосе и запрет ручного close точно в startsAt. Подтверждение пересечения и защита от устаревшей ревизии повторены в отдельной fixture.
