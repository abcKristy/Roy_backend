[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputRoot, [ValidateSet('all','chat')][string]$Mode='all')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-docker.ps1')
Assert-LocalDocker
if (Test-Path -LiteralPath $OutputRoot) { throw 'Output root must be fresh' }
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point to JDK 17' }
foreach($key in @('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS')) {
    [Environment]::SetEnvironmentVariable($key,$null,'Process')
}
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$monitorClasses = Join-Path (Get-Location).Path 'build/load-monitor'
New-Item -ItemType Directory -Path $monitorClasses -Force | Out-Null
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') --add-modules jdk.attach -d $monitorClasses 'tools/load/PoolSampler.java' *> "$OutputRoot/monitor-compile.log"
if($LASTEXITCODE -ne 0){throw 'Local pool sampler compilation failed'}
$jar = 'build/install/roy-backend/lib/roy-backend-1.0.0-SNAPSHOT.jar'
$hash = (Get-FileHash $jar).Hash
$source = git rev-parse HEAD
Get-ChildItem Env: | Where-Object Name -Match '^(AVATAR_STORAGE_|APNS_|RUSTORE_|DADATA_|AUTH_PRIVACY_POLICY_|PUSH_TOKEN_ENCRYPTION_KEY_BASE64$|PUBLIC_BASE_URL$)' |
    ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
$env:POSTGRES_USER = 'roy_load'
$env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:DATABASE_USER = $env:POSTGRES_USER
$env:DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
$env:MIGRATION_DATABASE_USER = $env:POSTGRES_USER
$env:MIGRATION_DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
$env:JWT_SECRET = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$env:JWT_TTL_SECONDS = '3600'
$env:APP_ENV = 'local'
$env:AUTH_DRAFT_POLICY_ENABLED = 'true'
$env:SERVER_HOST = '127.0.0.1'
$env:DATABASE_MAX_POOL_SIZE = '5'
$env:DATABASE_CONNECTION_TIMEOUT_MS = '5000'
$env:DATABASE_STATEMENT_TIMEOUT_MS = '5000'
$env:DATABASE_LOCK_TIMEOUT_MS = '1000'
$env:DATABASE_POOL_JMX_ENABLED = 'true'
$name = 'roy-load-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$id = docker run --pull=never -d --rm --name $name --label roy.purpose=server-load-validation -p '127.0.0.1::5432' -e POSTGRES_USER -e POSTGRES_PASSWORD postgis/postgis:18-3.6 postgres -c shared_preload_libraries=pg_stat_statements
if ($LASTEXITCODE -ne 0 -or $id -notmatch '^[0-9a-f]{64}$') { throw 'Disposable container failed' }
$server = $null
function Assert-Candidate {
    if ((Get-FileHash $jar).Hash -ne $hash -or (git rev-parse HEAD) -ne $source) { throw 'Candidate changed' }
}
function Start-Backend([string]$Database,[string]$Label) {
    $env:LOAD_DB_NAME = $Database
    $env:DATABASE_URL = "jdbc:postgresql://127.0.0.1:$($env:LOAD_DB_PORT)/$Database"
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start(); $env:SERVER_PORT = $listener.LocalEndpoint.Port.ToString(); $listener.Stop()
    $env:PUBLIC_BASE_URL = "http://127.0.0.1:$($env:SERVER_PORT)"
    $script:server = Start-Process $java -ArgumentList '-Xmx1536m','-Dcom.sun.management.jmxremote.local.only=true','-Djava.rmi.server.hostname=127.0.0.1','-cp','build/install/roy-backend/lib/*','io.ktor.server.netty.EngineMain' -PassThru -WindowStyle Hidden -RedirectStandardOutput "$OutputRoot/$Label.stdout.log" -RedirectStandardError "$OutputRoot/$Label.stderr.log"
    $ready = $false
    for ($i=0;$i -lt 60;$i++) {
        try { if ((Invoke-RestMethod "$($env:PUBLIC_BASE_URL)/health" -TimeoutSec 2 -NoProxy).status -eq 'ok') {$ready=$true;break} } catch {}
        if ($server.HasExited) {throw 'Backend exited'}
        Start-Sleep -Seconds 1
    }
    if (-not $ready) {throw 'Backend not ready'}
}
function Run-Load([string]$Mode) {
    Assert-Candidate
    & python tools/load/run.py --mode $Mode --output "$OutputRoot/$Mode" --base-url $env:PUBLIC_BASE_URL --server-pid $server.Id --jar $jar *> "$OutputRoot/$Mode.log"
    $code = $LASTEXITCODE
    [ordered]@{mode=$Mode;exitCode=$code} | ConvertTo-Json -Compress | Add-Content "$OutputRoot/exit-codes.jsonl"
    Assert-Candidate
}
function Test-Telemetry([string]$Label) {
    & python tools/load/run.py --mode telemetry --output "$OutputRoot/telemetry-$Label" --base-url $env:PUBLIC_BASE_URL --server-pid $server.Id --jar $jar *> "$OutputRoot/telemetry-$Label.log"
    if($LASTEXITCODE -ne 0){throw 'Telemetry preflight failed before load'}
    $check = Get-Content "$OutputRoot/telemetry-$Label/summary.json" -Raw | ConvertFrom-Json
    if($check.evidenceCompleteness -ne 'COMPLETE' -or $check.resourceErrors.Count -gt 0){throw 'Telemetry coverage is incomplete before load'}
}
try {
    if ((docker inspect --format '{{.Name}}' $id) -ne "/$name" -or (docker inspect --format '{{index .Config.Labels "roy.purpose"}}' $id) -ne 'server-load-validation') {throw 'Container identity mismatch'}
    $env:LOAD_CONTAINER_ID = $id
    $ready = $false
    for($i=0;$i -lt 60;$i++){docker exec $id pg_isready -U $env:POSTGRES_USER -d postgres *> $null;if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep -Seconds 1}
    if(-not $ready){throw 'PostGIS not ready'}
    $binding = docker port $id 5432/tcp
    if($binding -notmatch '^127\.0\.0\.1:(\d+)$'){throw 'Database not loopback'}
    $env:LOAD_DB_PORT = $Matches[1]
    $env:TEST_DATABASE_DISPOSABLE = 'true'
    $databases = if($Mode -eq 'all'){@('roy_m1t2_step9load','roy_load_chat')}else{@('roy_load_chat')}
    foreach($db in $databases) {
        docker exec $id createdb -U $env:POSTGRES_USER $db
        if($LASTEXITCODE -ne 0){throw 'Cannot create database'}
        $env:DATABASE_URL = "jdbc:postgresql://$binding/$db"
        & $java -cp 'build/install/roy-backend/lib/*' com.example.persistence.MigrationMainKt *> "$OutputRoot/$db.migrate.log"
        if($LASTEXITCODE -ne 0){throw 'Migration failed'}
        docker exec $id psql -X -v ON_ERROR_STOP=1 -U $env:POSTGRES_USER -d $db -c 'CREATE EXTENSION pg_stat_statements' *> "$OutputRoot/$db.monitor-extension.log"
        if($LASTEXITCODE -ne 0){throw 'Local statement statistics extension unavailable'}
    }
    $identityDatabase = @($databases)[0]
    $identity = docker exec $id psql -X -At -U $env:POSTGRES_USER -d $identityDatabase -c "SELECT current_database(),current_user,current_setting('server_version'),postgis_lib_version(),(SELECT max(installed_rank) FROM flyway_schema_history)"
    if($LASTEXITCODE -ne 0){throw 'Identity query failed'}
    $statsVersion = docker exec $id psql -X -At -U $env:POSTGRES_USER -d $identityDatabase -c "SELECT extversion FROM pg_extension WHERE extname='pg_stat_statements'"
    if($LASTEXITCODE -ne 0 -or -not $statsVersion){throw 'Cannot confirm statement statistics version'}
    [ordered]@{source=$source;jarSha256=$hash;mode=$Mode;containerId=$id;containerName=$name;binding=$binding;databaseIdentity=$identity;runtime='JDK17';jvmMaxHeap='1536m';poolMax=5;poolJmxEnabled=$true;jmxAccess='local Attach only, no remote port';pgStatStatementsVersion=$statsVersion;connectionTimeoutMs=5000;statementTimeoutMs=5000;lockTimeoutMs=1000;providers='disabled';targetAcceptance=$false} | ConvertTo-Json | Set-Content "$OutputRoot/environment.json"
    if($Mode -eq 'all') {
    Get-Content src/test/resources/step9-local-fixture.sql -Raw | docker exec -i $id psql -X -v ON_ERROR_STOP=1 -U $env:POSTGRES_USER -d roy_m1t2_step9load *> "$OutputRoot/fixture.log"
    if($LASTEXITCODE -ne 0){throw 'Fixture failed'}
    & python tools/load/run.py --mode capacity --output "$OutputRoot/capacity" --jar $jar *> "$OutputRoot/capacity.log"
    if($LASTEXITCODE -ne 0){throw 'Generator capacity failed'}
    $capacity = Get-Content "$OutputRoot/capacity/summary.json" -Raw | ConvertFrom-Json
    if ($capacity.acceptance -ne 'PASS' -and $capacity.metricAcceptance -ne 'PASS') {throw 'Generator did not sustain the predeclared schedule'}
    Start-Backend 'roy_m1t2_step9load' 'bbox-server'
    Test-Telemetry 'bbox'
    Run-Load 'bbox'
    # An allowlisted JFC excludes environment, properties and process commands.
    $profile = Join-Path $PSScriptRoot 'load-profile.jfc'
    $profileXml = [xml](Get-Content $profile -Raw)
    $allowedEvents = @('jdk.ExecutionSample','jdk.NativeMethodSample','jdk.ThreadCPULoad','jdk.GarbageCollection')
    foreach($event in $profileXml.configuration.event){
        if(($event.setting | Where-Object name -eq 'enabled').'#text' -eq 'true' -and $event.name -notin $allowedEvents){throw 'Unsafe JFR profile'}
    }
    Copy-Item $profile "$OutputRoot/load-profile.jfc"
    & (Join-Path $env:JAVA_HOME 'bin/jcmd.exe') $server.Id JFR.start name=LoadDiagnostic "settings=$profile" *> "$OutputRoot/diagnostic-jfr-start.log"
    $jfrStarted = $LASTEXITCODE -eq 0
    Run-Load 'diagnostic'
    if($jfrStarted){
        & (Join-Path $env:JAVA_HOME 'bin/jcmd.exe') $server.Id JFR.stop name=LoadDiagnostic "filename=$OutputRoot/diagnostic.jfr" *> "$OutputRoot/diagnostic-jfr-stop.log"
    }
    foreach($topic in @('none','football')) {
        $filter = if($topic -eq 'football'){"AND pt.preset_key='football'"}else{''}
        $query = "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT p.id,ST_Y(p.location),ST_X(p.location),p.address,p.address_source,p.created_at,p.updated_at FROM points p WHERE p.location && ST_MakeEnvelope(37.55,55.70,37.586,55.72,4326) AND EXISTS(SELECT 1 FROM point_topics pt JOIN chats c ON c.point_topic_id=pt.id WHERE pt.point_id=p.id $filter AND c.participant_count BETWEEN 0 AND 2147483647) ORDER BY p.created_at DESC,p.id DESC LIMIT 11"
        $query | Set-Content "$OutputRoot/explain-$topic.sql"
        docker exec $id psql -X -At -v ON_ERROR_STOP=1 -U $env:POSTGRES_USER -d roy_m1t2_step9load -c $query > "$OutputRoot/explain-$topic.json"
        if($LASTEXITCODE -ne 0){throw 'Diagnostic EXPLAIN failed'}
    }
    Stop-Process -InputObject $server; $server=$null
    # Backup/restore rehearsal is restricted to this new synthetic container.
    docker exec $id pg_dump -U $env:POSTGRES_USER -Fc -f /tmp/roy-synthetic.dump roy_m1t2_step9load
    if($LASTEXITCODE -ne 0){throw 'Synthetic backup failed'}
    docker exec $id createdb -U $env:POSTGRES_USER roy_load_restored
    if($LASTEXITCODE -ne 0){throw 'Restore database creation failed'}
    docker exec $id pg_restore -U $env:POSTGRES_USER -d roy_load_restored --exit-on-error /tmp/roy-synthetic.dump
    if($LASTEXITCODE -ne 0){throw 'Synthetic restore failed'}
    $countsSql = 'SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM points),(SELECT count(*) FROM chats),(SELECT count(*) FROM messages),(SELECT count(*) FROM flyway_schema_history WHERE success)'
    $before = docker exec $id psql -X -At -U $env:POSTGRES_USER -d roy_m1t2_step9load -c $countsSql
    if($LASTEXITCODE -ne 0){throw 'Source counts query failed'}
    $after = docker exec $id psql -X -At -U $env:POSTGRES_USER -d roy_load_restored -c $countsSql
    if($LASTEXITCODE -ne 0){throw 'Restored counts query failed'}
    if($before -ne '100|350|350|2000|20' -or $before -ne $after){throw 'Source or restored counts differ from the fixed fixture'}
    $rollback = docker exec $id psql -X -At -v ON_ERROR_STOP=1 -U $env:POSTGRES_USER -d roy_load_restored -c "BEGIN; CREATE TABLE local_rollback_probe(id int); ROLLBACK; SELECT to_regclass('local_rollback_probe') IS NULL"
    if($LASTEXITCODE -ne 0 -or $rollback[-1] -ne 't'){throw 'Transactional DDL rollback failed'}
    $dumpHash = docker exec $id sha256sum /tmp/roy-synthetic.dump
    if($LASTEXITCODE -ne 0){throw 'Backup hash failed'}
    if(($dumpHash -split ' ')[0] -notmatch '^[0-9a-f]{64}$'){throw 'Invalid backup SHA256'}
    [ordered]@{scope='limited disposable synthetic DB rehearsal; equal counts do not prove full data integrity';backupRestoreCommands='PASS';sourceCounts=$before;restoredCounts=$after;dumpSha256=($dumpHash -split ' ')[0];transactionalDdlRollback='PASS';previousApplicationRollback='NOT_RUN';targetRestoreRollback='NOT_RUN'} | ConvertTo-Json | Set-Content "$OutputRoot/rehearsal.json"
    }
    Start-Backend 'roy_load_chat' 'chat-server'
    Test-Telemetry 'chat'
    Run-Load 'chat'
} finally {
    if($null -ne $server -and -not $server.HasExited){Stop-Process -InputObject $server}
    docker stop -t 2 $id | Out-Null
}
