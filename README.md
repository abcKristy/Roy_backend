# roy-backend

## Local JVM configuration for M1.T1

Use JDK 17 for Gradle and the server. Copy `.env.example` to `.env`, replace all
placeholder credentials and the JWT secret, and keep `.env` outside Git. The
server requires `DATABASE_URL` (a `jdbc:postgresql://` URL), `DATABASE_USER`,
`DATABASE_PASSWORD`, and `JWT_SECRET`. `SERVER_PORT` is optional and defaults to
8080. Missing required values stop startup; their values are never included in
the error message.

Docker Compose reads `.env` for container interpolation but does **not** export
those values to a JVM launched on the host. In PowerShell, load the file into
the current process before `gradlew.bat run`:

```powershell
Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
.\gradlew.bat run
```

The loader accepts simple `KEY=value` lines as in `.env.example`; keep values
on one line, without surrounding quotes. Start a new shell or remove the
process variables to clear them. The database startup, migrations, and health
endpoint are added in subsequent M1.T1 steps; current startup does not prove
database readiness.

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:
 * [Ktor Documentation](https://ktor.io/docs/home.html)
 * [Ktor GitHub page](https://github.com/ktorio/ktor)
 * [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).


## Features
Here's a list of features included in this project:

| Name | Description |
|------|-------------|

## Building & Running
To build or run the project, use one of the following tasks:


| Task | Description |
|------|-------------|
| `./gradlew test`    | Run the tests     |
| `./gradlew build`   | Build the project |
| `./gradlew run`     | Run the server    |

If the server starts successfully, you'll see the following output:
```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```
