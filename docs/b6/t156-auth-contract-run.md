# T156. Сессии и серверный auth

Локальный прогон 07.11.2026 на `0a2ac71`, PostgreSQL/PostGIS/ранг Flyway `18.6 (Debian 18.6-1.pgdg13+2)|3.6.4|16`. Исполнитель проверки — Codex.
Обязательные тесты PostgreSQL/PostGIS — **9 PASS**, без пропусков в принятом наборе. Первый запуск и корректировки изоляции отражены в индексе. [Индекс классов и сценариев](functional-evidence.json).

- Публичный auth различает отказ входа и истечение защищённой сессии. Проверены нормализация, согласие, регистрация и хеш, а также собственный /me.
- Refresh сохраняет предел сессии, ротация и повтор старого токена проверены на PostgreSQL. Отзыв одного сеанса при logout и всех сеансов при смене пароля подтверждён в отдельных проверках и сквозной цепочке.

## Воспроизведение

- `roy_b5_mvp_chain` — `com.example.acceptance.MvpBackendChainIntegrationTest`.
- `roy_m1t2_b6login` — `com.example.auth.JdbcLoginRepositoryIntegrationTest`.
- `roy_m1t2_b6refresh` — `com.example.auth.JdbcRefreshRepositoryIntegrationTest`.
- `roy_m1t2_b6register` — `com.example.auth.JdbcRegistrationRepositoryIntegrationTest`.
- `roy_m1t2_b6session` — `com.example.auth.JdbcSessionRepositoryIntegrationTest`.
- `roy_m1t2_step8delete` — `com.example.auth.ProfileServiceIntegrationTest`.
- `roy_m1t2_upgrade` — `com.example.auth.RefreshHistoryMigrationIntegrationTest`.

Дополнительные классы из обычного локального `check`:

- `com.example.auth.RegistrationRouteTest` — 2 PASS.
- `com.example.auth.LoginRouteTest` — 1 PASS.
- `com.example.auth.SessionAccessTest` — 3 PASS.
- `com.example.auth.SessionRouteTest` — 1 PASS.
- `com.example.auth.RefreshRouteTest` — 1 PASS.
- `com.example.auth.RefreshServiceTest` — 4 PASS.

Для обязательного прогона используйте `pwsh -File tools/b6/run.ps1` из корня backend после офлайн-`installDist`. Параметр `-Suite` позволяет выбрать один или несколько алиасов из списка. Прогон создаёт собственный контейнер и отклоняет пропуски.
Применимые проверки целевой среды повторяются на этапе 5.

Дополнительный локальный прогон подтвердил чистый запуск, повтор V016 и отказ при незавершённой миграции или изменённой checksum. Проверены два сценария запуска и истории миграций. До коммита запущены изменённые тестовые файлы и раннер, что отмечено полем source_worktree_clean в отчёте. [Дополнительный протокол](functional-supplement.json).
