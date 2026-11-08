$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-docker.ps1')
. (Join-Path $PSScriptRoot 'distribution.ps1')
Assert-LocalDocker
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a local JDK 17' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$distribution = Build-B6Distribution
$env:POSTGRES_USER = 'b6_smoke'
$env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:DATABASE_USER = $env:POSTGRES_USER
$env:DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
$env:MIGRATION_DATABASE_USER = $env:POSTGRES_USER
$env:MIGRATION_DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
$env:JWT_SECRET = ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
$env:APP_ENV = 'local'
$env:AUTH_DRAFT_POLICY_ENABLED = 'true'
$env:SERVER_HOST = '127.0.0.1'
Get-ChildItem Env: | Where-Object Name -Match '^(AVATAR_STORAGE_|APNS_|RUSTORE_|DADATA_|AUTH_PRIVACY_POLICY_|PUSH_TOKEN_ENCRYPTION_KEY_BASE64$|PUBLIC_BASE_URL$)' |
    ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$env:SERVER_PORT = $listener.LocalEndpoint.Port.ToString()
$listener.Stop()
$env:PUBLIC_BASE_URL = "http://127.0.0.1:$env:SERVER_PORT"
$name = 'codex-b6-smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$id = docker run --pull=never -d --rm --name $name --label 'roy.purpose=b6-local-disposable' -p '127.0.0.1::5432' -e POSTGRES_USER -e POSTGRES_PASSWORD postgis/postgis:18-3.6
if ($LASTEXITCODE -ne 0 -or $id -notmatch '^[0-9a-f]{64}$') { throw 'Could not start local database' }
$server = $null
try {
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        docker exec $id pg_isready -h 127.0.0.1 -U $env:POSTGRES_USER -d postgres 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Local database did not become ready' }
    $db = docker port $id 5432/tcp
    if ($db -notmatch '^127\.0\.0\.1:\d+$') { throw 'Database is not bound to loopback' }
    docker exec $id createdb -U $env:POSTGRES_USER roy_b6_smoke
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create local smoke database' }
    $env:DATABASE_URL = "jdbc:postgresql://$db/roy_b6_smoke"
    Assert-B6DistributionUnchanged $distribution
    & $java -cp 'build/install/roy-backend/lib/*' com.example.persistence.MigrationMainKt
    if ($LASTEXITCODE -ne 0) { throw 'Smoke migrations failed' }
    $dir = New-Item -ItemType Directory -Force 'build/b6-evidence'
    $server = Start-Process $java -ArgumentList '-cp', 'build/install/roy-backend/lib/*', 'io.ktor.server.netty.EngineMain' -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $dir 'smoke.stdout.log') -RedirectStandardError (Join-Path $dir 'smoke.stderr.log')
    $http = "http://127.0.0.1:$env:SERVER_PORT"
    $health = $null
    for ($i = 0; $i -lt 30; $i++) {
        try { $health = Invoke-RestMethod "$http/health" -TimeoutSec 2 -MaximumRedirection 0 -NoProxy; break } catch {
            if ($server.HasExited) { throw 'Packaged backend exited before becoming healthy' }
            Start-Sleep -Seconds 1
        }
    }
    if ($health.status -ne 'ok') { throw 'Packaged backend health failed' }
    $spec = Invoke-RestMethod "$http/openapi.json" -TimeoutSec 5 -MaximumRedirection 0 -NoProxy
    if ($spec.openapi -ne '3.1.0') { throw 'Packaged OpenAPI mismatch' }
    $wsDoc = Invoke-WebRequest "$http/docs/websocket" -TimeoutSec 5 -MaximumRedirection 0 -NoProxy
    if ($wsDoc.StatusCode -ne 200 -or $wsDoc.Content -notmatch '/api/v1/ws') { throw 'Packaged WS documentation mismatch' }
    $privacy = Invoke-WebRequest "$http/api/v1/legal/privacy" -TimeoutSec 5 -MaximumRedirection 0 -NoProxy
    if ($privacy.StatusCode -ne 200) { throw 'Local draft policy is unavailable' }
    $ws = [Net.WebSockets.ClientWebSocket]::new()
    $ws.Options.CollectHttpResponseDetails = $true
    $ws.Options.Proxy = [Net.WebProxy]::new()
    $timeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(5))
    try {
        try {
            $ws.ConnectAsync([Uri]"ws://127.0.0.1:$env:SERVER_PORT/api/v1/ws", $timeout.Token).GetAwaiter().GetResult()
            throw 'Packaged WS accepted a missing token'
        } catch {
            $upgradeStatus = [int]$ws.HttpStatusCode
            if ($upgradeStatus -ne 401) { throw }
        }
    } finally {
        $ws.Dispose()
        $timeout.Dispose()
    }
    Assert-B6DistributionUnchanged $distribution
    [ordered]@{
        source = $distribution.Source
        source_worktree_clean = $true
        jar_sha256 = $distribution.JarSha256
        health = 200
        openapi = $spec.openapi
        websocket_docs = $wsDoc.StatusCode
        draft_privacy = $privacy.StatusCode
        missing_token_ws_upgrade = $upgradeStatus
        transport = 'loopback HTTP and WS'
    } | ConvertTo-Json | Set-Content -Encoding utf8 'build/b6-evidence/smoke.json'
    'PASS packaged loopback HTTP and WS smoke'
} finally {
    if ($null -ne $server -and -not $server.HasExited) { Stop-Process -InputObject $server }
    docker stop -t 2 $id | Out-Null
}
