function Assert-LocalDocker {
    $localEndpoint = '^(?:npipe:/{4}\./pipe/[^/]+|unix:///(?!/).+)$'
    if ($env:DOCKER_HOST -and $env:DOCKER_HOST -notmatch $localEndpoint) {
        throw 'B6 requires a local Docker socket. Remove the remote DOCKER_HOST.'
    }
    $context = $env:DOCKER_CONTEXT
    if (-not $context) {
        $context = docker context show
        if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve Docker context' }
    }
    $endpoint = docker context inspect --format '{{.Endpoints.docker.Host}}' -- $context
    if ($LASTEXITCODE -ne 0 -or $endpoint -notmatch $localEndpoint) {
        throw 'B6 requires a Docker context using a local socket.'
    }
}
