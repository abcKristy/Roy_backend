# Roy Backend

## Описание проекта

Серверная часть проекта Roy. Это отдельный Kotlin/JVM-сервис на Ktor. Основа сервера настраивает подключение к PostgreSQL/PostGIS, применяет миграции Flyway, возвращает JSON-ошибки с идентификатором запроса и предоставляет endpoint проверки готовности `GET /health`.

## Стек проекта

- Kotlin 2.4.20 и JDK 17
- Ktor 3.6.1, Netty
- PostgreSQL 18 с PostGIS 3.6
- Flyway 13.11.1 для миграций базы данных
- HikariCP для пула соединений
- Gradle Wrapper 9.5.1
- Docker Compose для локального PostgreSQL/PostGIS

## Цель

Подготовить основу backend для следующих задач проекта: запуск сервера, безопасная конфигурация через переменные окружения, подключение к пространственной базе данных и проверяемое применение схемы.

Границы серверного и мобильного auth-срезов M1.T2 описаны в [docs/auth-slice-boundaries.md](docs/auth-slice-boundaries.md).
Действия перед переносом на удалённый сервер накапливаются в [docs/remote-server-readiness.md](docs/remote-server-readiness.md).
Регистрация и вход выдают короткий access JWT и обновляемый refresh token. Порядок обновления пары токенов описан в [docs/session-access.md](docs/session-access.md). Параметры `JWT_TTL_SECONDS` и `REFRESH_TTL_SECONDS` задают срок токена доступа и предельный срок сессии.

### Черновая политика для локальной разработки

Текст [черновой политики](docs/privacy-policy-draft.md) доступен через публичные `GET /api/v1/legal/privacy` и `GET /api/v1/legal/privacy/document` только при явном включении локального режима. В `.env` установите `APP_ENV=local`, `AUTH_DRAFT_POLICY_ENABLED=true`, `SERVER_HOST=127.0.0.1` и `PUBLIC_BASE_URL=http://127.0.0.1:8080`. `GET /api/v1/legal/privacy` вернёт версию `draft-2026-11-01.2`, фиксированное время локальной публикации и абсолютный URL документа. Регистрация принимает только эту версию, полученную клиентом из metadata. При изменении текста черновика обновите версию, дату публикации и SHA-256 в `DraftPrivacyPolicy` одновременно.

Без флага черновик не публикуется и регистрация возвращает `503`. Включение черновика требует явного `APP_ENV=local` и loopback-адресов в `SERVER_HOST` и `PUBLIC_BASE_URL`; `APP_ENV=production` вместе с draft-флагом останавливает сервер при запуске. Черновой режим предназначен только для тестовых аккаунтов: в политике пока нет данных оператора и подтверждённых условий обработки; локальную БД с тестовыми согласиями не переносить в production.

### Обратное геокодирование

Для защищённого `POST /api/v1/geocoding/reverse` сервер использует [DaData Geolocate](https://dadata.ru/api/geolocate/). Укажите `DADATA_API_KEY` только в серверном окружении. Пустой ответ провайдера возвращает `200` с `address:null` и `source:"none"`, после чего клиент запрашивает обязательный ручной адрес. При отсутствии ключа, таймауте или отказе провайдера сервер возвращает безопасный `503 GEOCODER_UNAVAILABLE`; ручной адрес в `POST /api/v1/points` остаётся доступным. Запрос к провайдеру выполняется вне транзакции создания точки.

## Запуск и проверка

### Интерактивная документация API

После запуска по инструкции ниже откройте [Swagger UI](http://127.0.0.1:8080/docs).
Машиночитаемая [OpenAPI 3.1 спецификация](http://127.0.0.1:8080/openapi.json)
описывает все 45 реализованных REST-операций: служебные и legal, auth/профиль,
точки/темы/квоты/геокодирование, членство/чаты/сообщения, встречи/голоса/брони,
жалобы, уведомления и устройства.
Контракты сгруппированы в отдельные сворачиваемые тематические блоки:
авторизация, профиль, уведомления, карта и точки, темы и членство, чаты,
сообщения, встречи и бронирования, жалобы, правовая информация и служебные.
У каждого ответа раскрываются условия статуса
и отдельные примеры `error.code`; `204` не имеет тела. `406` при несовместимом
`Accept` также возвращается без тела; используйте `Accept: application/json` или `*/*`.
Ресурсы Swagger UI
поставляются внутри backend через WebJar, CDN и внешний валидатор в браузере не используются.

Для **Try it out** получите свою тестовую пару токенов через `POST /api/v1/auth/login`
или регистрацию. Нажмите **Authorize** и вставьте только `accessToken`, без `Bearer`:
UI сам добавляет `Authorization: Bearer <accessToken>`. Примеры токенов в документации
являются неработающими заглушками. Авторизация не сохраняется после перезагрузки страницы.
По истечении access JWT вызовите `/api/v1/auth/refresh` с текущим refresh token,
сохраните новую пару и обновите **Authorize**. Повтор уже использованного refresh token
отзывает сеанс. На операциях создания заполните `Idempotency-Key` новым UUID;
при повторе неопределённого результата сохраните тот же ключ и бизнес-поля запроса.

По умолчанию сервер Try it out — `/`, то есть origin открытой страницы.
Для развёрнутого backend задайте `PUBLIC_BASE_URL=https://api.example.invalid`
своим реальным HTTPS origin без пути, query и fragment, и перезапустите backend:
он подставит этот URL в `servers` опубликованного JSON. Документация будет доступна
по `https://<ваш-домен>/docs` и `https://<ваш-домен>/openapi.json`.
Reverse proxy должен проксировать корень origin, включая `/docs/assets`;
размещение под дополнительным path prefix не поддерживается.
В локальном draft-режиме `PUBLIC_BASE_URL` обязан быть loopback, например
`http://127.0.0.1:8080`. Изменение `SERVER_PORT` требует соответствующей ссылки.

[WebSocket-контракт](docs/websocket-api.md) доступен отдельно в браузере по
[/docs/websocket](http://127.0.0.1:8080/docs/websocket); WS не представлен REST-операцией.
[Различия с ранним M0 и границы реализации](docs/api-contract-differences.md)
доступны по [/docs/contract-differences](http://127.0.0.1:8080/docs/contract-differences).
Аватары и production-публикация политики пока не реализованы. При отсутствии
`PUSH_TOKEN_ENCRYPTION_KEY_BASE64` (32 байта в Base64) аутентифицированный PUT
с корректным ID установки возвращает `503 PUSH_REGISTRATION_FAILED`, а DELETE
возвращает `404 RESOURCE_GONE`. Не используйте реальные пользовательские данные для примеров.

Исходники спецификации: `docs/openapi/*.json`; `docs/openapi.json` — проверяемый
собранный файл, именно его backend включает в ресурсы. После правки фрагментов:

```powershell
python tools/api_docs.py build
python -m pip install --target build/doc-validation -r tools/requirements-api-docs.txt
python tools/api_docs.py check
.\gradlew.bat verifyApiDocs test --tests com.example.api.DocumentationRoutesTest
```

`verifyApiDocs` включён в Gradle `check`. Он проверяет OpenAPI 3.1, JSON Schema,
все примеры запросов/ответов/параметров, отсутствие тела у `204`, уникальность
операций и тематических блоков, актуальность собранного JSON и совпадение методов/путей с исходниками Ktor.
`DocumentationRoutesTest` дополнительно сравнивает спецификацию с реально установленным
деревом Ktor-маршрутов и проверяет HTTP-публикацию JSON/UI/локальных ресурсов без БД.
Зависимости Python нужны только для проверки документации, не для запуска сервера.

Выполняйте команды PowerShell из каталога `project/backend`.

### 1. Подготовить настройки

Создайте локальный `.env` из шаблона:

```powershell
Copy-Item .env.example .env
```

Откройте `.env` и заполните `POSTGRES_USER`, `POSTGRES_PASSWORD`, `DATABASE_USER`, `DATABASE_PASSWORD` и `JWT_SECRET`. Для локальной базы укажите одинаковые значения для `POSTGRES_USER` и `DATABASE_USER`, а также для `POSTGRES_PASSWORD` и `DATABASE_PASSWORD`. `POSTGRES_DB` должен совпадать с именем базы в `DATABASE_URL`. Оставьте `SERVER_PORT=8080`, если порт не нужно менять.

### 2. Запустить базу данных

```powershell
docker compose up -d db
docker compose ps db
```

### 3. Запустить сервер

В том же окне PowerShell загрузите настройки `.env` в окружение текущего процесса и запустите сервер:

```powershell
Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
.\gradlew.bat run
```

Оставьте это окно работающим. Успешный запуск сопровождается сообщением о том, что сервер отвечает на `http://127.0.0.1:8080` (или на порту из `SERVER_PORT`).

### 4. Проверить работу

Откройте второе окно PowerShell и выполните:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health
```

Ожидаемый результат:

```text
status
------
ok
```

### 5. Остановить сервер и базу

В окне сервера нажмите **Ctrl+C**. Когда база больше не нужна, остановите контейнер:

```powershell
docker compose stop db
```

Обычная остановка сохраняет volume PostgreSQL. Для повторного запуска выполните
`docker compose up -d db`, заново загрузите переменные в процесс PowerShell и
запустите сервер. Flyway повторно проверит историю, не создавая дубликаты seed;
проверяйте `GET /health` после каждого запуска. Не используйте `down -v` для
обычной остановки: эта команда удаляет данные.

При неверных credentials или недоступной БД сервер не должен становиться ready.
Сверьте локально имена переменных и доступность контейнера через `docker compose
ps db`; не публикуйте содержимое `.env`, строки подключения и полные журналы с
секретами. В production миграции выполняются отдельным шагом `runMigrations`
перед стартом API; подробности и разделение DDL/runtime credentials описаны в
[docs/database-runtime.md](docs/database-runtime.md).
