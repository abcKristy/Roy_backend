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

### Черновая политика для локальной разработки

Текст [черновой политики](docs/privacy-policy-draft.md) доступен через публичные `GET /api/v1/legal/privacy` и `GET /api/v1/legal/privacy/document` только при явном включении локального режима. В `.env` установите `APP_ENV=local`, `AUTH_DRAFT_POLICY_ENABLED=true`, `SERVER_HOST=127.0.0.1` и `PUBLIC_BASE_URL=http://127.0.0.1:8080`. `GET /api/v1/legal/privacy` вернёт версию `draft-2026-11-01.1`, фиксированное время локальной публикации и абсолютный URL документа. Регистрация принимает только эту версию, полученную клиентом из metadata. При изменении текста черновика обновите версию, дату публикации и SHA-256 в `DraftPrivacyPolicy` одновременно.

Без флага черновик не публикуется и регистрация возвращает `503`. Включение черновика требует явного `APP_ENV=local` и loopback-адресов в `SERVER_HOST` и `PUBLIC_BASE_URL`; `APP_ENV=production` вместе с draft-флагом останавливает сервер при запуске. Черновой режим предназначен только для тестовых аккаунтов: в политике пока нет данных оператора и подтверждённых условий обработки; локальную БД с тестовыми согласиями не переносить в production.

## Запуск и проверка

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
