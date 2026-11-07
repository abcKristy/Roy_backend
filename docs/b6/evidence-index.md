# T173. Реестр свидетельств локального BG-1

Дата источников 07.11.2026. Исполнитель проверки — Codex.

- Backend-кандидат `becb05132ee933e36cf1a1774d8fd027bdcf942a`. Дерево runtime `b85e85afc534b48f5f5cdc9133b446d673e82a30`, тесты `f9755e85ea958bea4cecd26f76a3ec64d4c716bb`.
- Артефакт `roy-backend-1.0.0-SNAPSHOT.jar`, SHA-256 `cbd1e2fe1c81c3a7e50c6053455ef02457e99a6ca042b67c2d171b40e3d5e349`.
- OpenAPI 3.1.0, 48 операций и 456 примеров. SHA-256 `dee956a93edbbf3d2874342d8bde214e71d98ef45e126bc8158b771de980d174`. [OpenAPI](../openapi.json).
- WebSocket-контракт, SHA-256 `914296cf14f4af51c2239b3ccd4565e181eea74e44aaf700ffe9cd8dca9f9b52`. [Документация WS](../websocket-api.md).
- Среда Windows, JDK 17, Docker на локальном сокете, PostgreSQL/PostGIS/Flyway `18.6 (Debian 18.6-1.pgdg13+2)|3.6.4|16`. Порты процесса и БД ограничены loopback.
- **PASS** `test --rerun check installDist --offline`. В обычной задаче `test` выполнено 110, пропущено по отсутствующему DB fixture 236.
- SHA-256 журнала итогового `check` `2ddce9840e866579a3df45a753b5c2ebaf97a779481d1494cb58b72ff5b70c6c`. Указанные результаты `test` получены в том же запуске.
- **PASS** обязательный интеграционный набор из 152 тестов, 69 классов, 38 отдельных баз. Ошибок, пропусков и отказов 0. [XML-индекс](final-integration-evidence.json).
- **PASS** smoke собранного процесса. Health, документация WS и черновик политики вернули 200, OpenAPI вернул 3.1.0, upgrade WS без токена вернул 401. Код 4401 после отзыва действующей сессии проверен в T167. [Smoke](final-smoke-evidence.json).

## Локальные критерии и сценарии

- `B6-BASE-01` · `API-014/015` · **PASS**. Дистрибутив запустился, пустая схема мигрирует, pending/checksum блокируют запуск. [T146](baseline.md).
- `B6-AUTH-01` · `API-016/017/018/021.N` · **PASS**. Черновик политики, гостевой контур и атомарная регистрация. [T156](t156-auth-contract-run.md).
- `B6-AUTH-02` · `API-019/020/021/023` · **PASS**. Ротация, повтор, срок, отзыв и проверка подписи в REST/WS. [T167](t167-session-security.md).
- `B6-MAP-01` · `API-039/040/042` · **PASS**. Фильтры bbox, пагинация и безопасная публичная проекция. [T157](t157-points-race-run.md).
- `B6-MAP-02` · `RACE-002/003` · **PASS**. Атомарность создания, темы и идемпотентность. [T157](t157-points-race-run.md).
- `B6-MAP-03` · `RACE-001` · **PASS**. Квота и граничные случаи скользящего окна. [T157](t157-points-race-run.md).
- `B6-CHAT-01` · `RACE-005/006` · **PASS**. Членство, конкурентный выход и доступ к чату. [T158](t158-chat-moderation-run.md).
- `B6-CHAT-02` · `API-001/003/004/005/006` · **PASS**. История, поиск, seq и post-commit WS. [T158](t158-chat-moderation-run.md).
- `B6-CHAT-03` · `RACE-004` · **PASS**. Стабильные отказы ввода, flood, жалобы и mute. [T158](t158-chat-moderation-run.md).
- `B6-MEET-01` · `RACE-007` · **PASS**. Допустимое предложение и актуальные голоса. [T159](t159-meeting-race-run.md).
- `B6-MEET-02` · `RACE-008/009/011` · **PASS**. Бронь, пересечение и конкурентная отмена. [T159](t159-meeting-race-run.md).
- `B6-PROFILE-01` · `RACE-012` · **PASS**. Очищенный аватар, ACL и безопасная замена. [T170](t170-private-media-audit.md).
- `B6-PROFILE-02` · `API-027/028` · **PASS**. Пароль, отзыв, удаление приватных и обезличивание общих связей. [T171](t171-deletion-and-retention.md).
- `B6-NOTIFY-01` · `API-031–037` · **PASS**. Ревизии, история, no-store, opt-in, 1 км и приватность payload. [T172](t172-nearby-privacy-audit.md).
- `B6-NOTIFY-02` · `RACE-011` · **PASS**. Шифрование, fencing, очередь, отмена и напоминание с локальным транспортом. [T160](t160-notification-failure-run.md).
- Сквозной контракт · `API-001–048` · **PASS**. Проверены равенство опубликованного дерева Ktor, OpenAPI и схема всех 456 примеров. Выполненные HTTP/WS/DB-сценарии указаны поимённо в итоговом индексе. [T149](api-cases.md), [T168](t168-resource-authorization.md).

## Протоколы и ограничения

- T146 · Паспорт входного backend. [Протокол](baseline.md).
- T147 · Реестр критериев и связь с F/NFR. [Протокол](requirements.md).
- T148 · Синтетические данные и защита БД. [Протокол](fixtures.md).
- T149 · Каталог всех REST-операций. [Протокол](api-cases.md).
- T150 · Оракулы и каталог конкурентных сценариев. [Протокол](race-cases.md).
- T151 · Порог и методика нагрузочных прогонов. [Протокол](measurement.md).
- T156 · Регистрация, login, refresh, logout и сессия. [Протокол](t156-auth-contract-run.md).
- T157 · Точки, фильтры, темы, повторы и квоты. [Протокол](t157-points-race-run.md).
- T158 · Чат, ACL, WS и модерация. [Протокол](t158-chat-moderation-run.md).
- T159 · Предложения, голоса и бронь. [Протокол](t159-meeting-race-run.md).
- T160 · Уведомления, очередь, напоминания и локальные транспорты. [Протокол](t160-notification-failure-run.md).
- T167 · Поддельные, неподписанные, истёкшие и отозванные JWT. [Протокол](t167-session-security.md).
- T168 · Публичный и защищённый HTTP/WS. [Протокол](t168-resource-authorization.md).
- T169 · Ввод, логи, конфигурация и Git. [Протокол](t169-input-and-secret-audit.md).
- T170 · Приватное чтение медиа и очистка объекта. [Протокол](t170-private-media-audit.md).
- T171 · Удаление аккаунта и retention. [Протокол](t171-deletion-and-retention.md).
- T172 · Минимизация центра и payload рядом. [Протокол](t172-nearby-privacy-audit.md).
- T145 · исторический сквозной прогон B5. [Протокол](../m5-b54-b55-local-handoff.md).
- Первый прогон и повторы до сборки кандидата сохранены отдельно. [Индекс исходных сценариев](functional-evidence.json).
- **NOT_RUN** фактические локальные замеры T152 и T161–164, запланированные на этап 3. [Методика](measurement.md).
- **BLOCKED** проверка целевого HTTPS/WSS, публичного согласия, живого storage/KMS, RuStore/APNs, миграции, восстановления и нагрузки на целевой среде до этапов 4–5. Локальная транспортная имитация учтена отдельно. [Разбор входов](blocker-triage.md).
- **PASS локально** NFR-011 и NFR-014–018 в пределах функциональных проверок: обход UI прямым HTTP, один процесс, REST/WS, JWT, миграции и пространственные границы, scheduler и fencing. [T147](requirements.md), [итоговый набор](final-integration-evidence.json).
- **NOT_RUN** мобильные NFR-001–005, NFR-009/010/012/013 и замеры NFR-006/007. **BLOCKED** NFR-008 и B6-EXT-01 до целевой среды. [Реестр требований](requirements.md).

## Воспроизведение

Из корня чистого backend-кандидата с JDK 17, кешем Gradle, локальным Docker и образом `postgis/postgis:18-3.6`.

Для проверки OpenAPI используется уже подготовленный локальный Python-каталог build/doc-validation через PYTHONPATH.

1. `./gradlew.bat --offline --console=plain --max-workers=1 test --rerun check installDist`.
2. `pwsh -File tools/b6/run.ps1 -Output build/b6-evidence/final-integration.json`.
3. `pwsh -File tools/b6/smoke.ps1`.
