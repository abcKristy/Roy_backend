function Get-B6CleanSource {
    $source = git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Run from the backend repository' }
    $workingCopy = git status --porcelain --untracked-files=all
    if ($LASTEXITCODE -ne 0) { throw 'Cannot determine backend working tree state' }
    if ($workingCopy) { throw 'B6 evidence requires a clean committed backend checkout' }
    # Gradle reads src directly, including files excluded by Git ignore rules.
    $ignoredInputs = git ls-files --others --ignored --exclude-standard -- src
    if ($LASTEXITCODE -ne 0) { throw 'Cannot check for ignored backend source files' }
    if ($ignoredInputs) { throw 'B6 evidence requires a src tree without ignored files' }
    return $source
}

function Get-B6ApplicationJar {
    $jars = @(Get-ChildItem -LiteralPath 'build/install/roy-backend/lib' -Filter 'roy-backend*.jar' -File)
    if ($jars.Count -ne 1) { throw 'Expected one backend JAR in the rebuilt distribution' }
    return $jars[0]
}

function Build-B6Distribution {
    $source = Get-B6CleanSource
    & ./gradlew.bat --offline --console=plain --max-workers=1 --rerun-tasks --no-build-cache `
        '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m' `
        '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' installDist | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Cannot build installDist for B6 evidence' }
    if ((Get-B6CleanSource) -ne $source) { throw 'Backend source changed during installDist' }

    return [pscustomobject]@{
        Source = $source
        JarSha256 = (Get-FileHash -LiteralPath (Get-B6ApplicationJar).FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Assert-B6DistributionUnchanged($Distribution) {
    if ((Get-B6CleanSource) -ne $Distribution.Source -or
        (Get-FileHash -LiteralPath (Get-B6ApplicationJar).FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Distribution.JarSha256) {
        throw 'Backend source or distribution changed during B6 run'
    }
}
