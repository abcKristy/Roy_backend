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
