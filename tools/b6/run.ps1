[CmdletBinding()]
param([string[]]$Suite, [string]$Output = 'build/b6-evidence/integration.json')

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-docker.ps1')
. (Join-Path $PSScriptRoot 'distribution.ps1')
Assert-LocalDocker
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a local JDK 17' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'suites.json') | ConvertFrom-Json -AsHashtable
if ($Suite) {
    $selected = [ordered]@{}
    foreach ($alias in $Suite) {
        if (-not $plan.Contains($alias)) { throw "Unknown suite $alias" }
        $selected[$alias] = $plan[$alias]
    }
    $plan = $selected
}
$distribution = Build-B6Distribution
$runDir = Join-Path (Get-Location).Path ('build/b6-run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$env:B6_PLAN = Join-Path $runDir 'suites.json'
$env:B6_RESULTS = Join-Path $runDir 'results'
ConvertTo-Json -InputObject $plan -Depth 5 | Set-Content -Encoding utf8 $env:B6_PLAN
$env:POSTGRES_USER = 'b6_local'
$env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:DATABASE_USER = $env:POSTGRES_USER
$env:DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
$env:MIGRATION_DATABASE_USER = $env:POSTGRES_USER
$env:MIGRATION_DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
$env:TEST_DATABASE_DISPOSABLE = 'true'
$name = 'codex-b6-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$id = docker run --pull=never -d --rm --name $name --label 'roy.purpose=b6-local-disposable' -p '127.0.0.1::5432' -e POSTGRES_USER -e POSTGRES_PASSWORD postgis/postgis:18-3.6
if ($LASTEXITCODE -ne 0 -or $id -notmatch '^[0-9a-f]{64}$') { throw 'Could not start disposable PostGIS' }
try {
    if ((docker inspect --format '{{.Name}}' $id) -ne "/$name" -or
        (docker inspect --format '{{index .Config.Labels "roy.purpose"}}' $id) -ne 'b6-local-disposable') {
        throw 'Container identity mismatch'
    }
    $ready = $false
    for ($i=0; $i -lt 60; $i++) {
        docker exec $id pg_isready -h 127.0.0.1 -U $env:POSTGRES_USER -d postgres 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Disposable PostGIS did not become ready' }
    $binding = docker port $id 5432/tcp
    if ($binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected PostGIS binding' }
    $env:B6_PORT = $Matches[1]
    docker exec $id createdb -U $env:POSTGRES_USER roy_b6_template
    if ($LASTEXITCODE -ne 0) { throw 'Could not create migration template' }
    $env:DATABASE_URL = "jdbc:postgresql://$binding/roy_b6_template"
    Assert-B6DistributionUnchanged $distribution
    & $java -cp 'build/install/roy-backend/lib/*' com.example.persistence.MigrationMainKt
    if ($LASTEXITCODE -ne 0) { throw 'Local migration failed' }
    $database = docker exec $id psql -X -At -U $env:POSTGRES_USER -d roy_b6_template -c "SELECT current_setting('server_version'),postgis_lib_version(),(SELECT max(installed_rank) FROM flyway_schema_history)"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify local schema' }
    if ($plan.Contains('roy_m2t4_filter')) {
        docker exec $id createdb -U $env:POSTGRES_USER -T roy_b6_template roy_m1t4_viewport
        if ($LASTEXITCODE -ne 0) { throw 'Cannot create viewport seed source' }
        Get-Content -Raw -Encoding utf8 'src/test/resources/m1-t4-viewport-fixture.sql' |
            docker exec -i $id psql -X -v ON_ERROR_STOP=1 -U $env:POSTGRES_USER -d roy_m1t4_viewport | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Viewport seed failed' }
    }
    foreach ($alias in $plan.Keys) {
        if ($alias -notmatch '^roy_[a-z0-9_]+$') { throw 'Invalid fixture alias' }
        $template = switch ($alias) {
            { $_ -in 'roy_m1t2_upgrade', 'roy_m1t2_bootstrap', 'roy_m1t2_history' } { 'template0' }
            'roy_m2t4_filter' { 'roy_m1t4_viewport' }
            default { 'roy_b6_template' }
        }
        docker exec $id createdb -U $env:POSTGRES_USER -T $template $alias
        if ($LASTEXITCODE -ne 0) { throw "Cannot create fixture $alias" }
    }
    & ./gradlew.bat --offline --console=plain --max-workers=1 --continue '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' '-Pkotlin.compiler.execution.strategy=in-process' -I (Join-Path $PSScriptRoot 'integration.gradle') b6Integration
    $gradleStatus = $LASTEXITCODE
    $records = @(
        foreach ($alias in $plan.Keys) {
            foreach ($class in $plan[$alias]) {
                $path = Join-Path $env:B6_RESULTS "$alias/TEST-com.example.$class.xml"
                if (-not (Test-Path -LiteralPath $path)) {
                    [ordered]@{ fixture=$alias; class="com.example.$class"; status='NOT_RUN'; tests=0 }
                    continue
                }
                $suiteResult = ([xml](Get-Content -Raw -LiteralPath $path)).testsuite
                $passed = [int]$suiteResult.tests -gt 0 -and [int]$suiteResult.failures -eq 0 -and [int]$suiteResult.errors -eq 0 -and [int]$suiteResult.skipped -eq 0
                [ordered]@{
                    fixture=$alias; class="com.example.$class"; status=if($passed){'PASS'}else{'FAIL'}
                    tests=[int]$suiteResult.tests; failures=[int]$suiteResult.failures; errors=[int]$suiteResult.errors; skipped=[int]$suiteResult.skipped
                    cases=@(foreach($testcase in $suiteResult.testcase){ [string]$testcase.name })
                }
            }
        }
    )
    Assert-B6DistributionUnchanged $distribution
    $evidence = [ordered]@{
        source=$distribution.Source; source_worktree_clean=$true; jar_sha256=$distribution.JarSha256
        local_database=$database; gradle_exit=$gradleStatus
        suites=$records
    }
    $reportDir = Split-Path -Parent $Output
    if ($reportDir) { New-Item -ItemType Directory -Path $reportDir -Force | Out-Null }
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 -LiteralPath $Output
    $records | ForEach-Object { "$($_.status) $($_.class) $($_.tests)" }
    if ($gradleStatus -ne 0 -or ($records | Where-Object status -ne 'PASS')) { exit 1 }
} finally {
    docker stop -t 2 $id | Out-Null
}
