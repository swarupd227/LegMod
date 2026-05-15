# PowerShell helper - equivalent of `make up` for Windows users without make.
# Targets Windows PowerShell 5.1 syntax (no ??, no ?., no ternary).
param(
    [switch]$Build
)

if (-not (Test-Path ".env")) {
    Write-Host "No .env found - copying from .env.example" -ForegroundColor Yellow
    Copy-Item ".env.example" ".env"
    Write-Host "Edit .env to set ANTHROPIC_API_KEY before bringing up agents." -ForegroundColor Yellow
}

# Docker Compose's documented precedence is:
#   1. CLI --env-file
#   2. SHELL environment variables          <-- wins
#   3. .env file in the compose project dir
#
# A shell that already has ANTHROPIC_API_KEY="" set (some IDE
# integrations and CI runners do this) will SHADOW the value from
# .env and the AI agents end up stubbed. Load .env explicitly into
# the current PS session here so the shell env mirrors the file,
# which neutralises the precedence trap.
if (Test-Path ".env") {
    Get-Content ".env" | ForEach-Object {
        if ($_ -match '^\s*([A-Z0-9_]+)\s*=\s*(.+?)\s*$') {
            $name  = $matches[1]
            $value = $matches[2]
            # Strip a trailing wrapping pair of quotes if present.
            if ($value.StartsWith('"') -and $value.EndsWith('"')) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            # Only set when the shell hasn't already set a non-empty
            # value (a developer who exports a different key in their
            # shell wins on purpose).
            $existing = [System.Environment]::GetEnvironmentVariable($name, "Process")
            if ([string]::IsNullOrEmpty($existing)) {
                Set-Item "Env:$name" $value
            }
        }
    }
}

if ($Build) {
    docker compose build
    if ($LASTEXITCODE -ne 0) {
        Write-Host "build failed" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

docker compose up -d
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Read .env into a hashtable so we can show the host ports the user actually picked.
$ports = @{}
if (Test-Path ".env") {
    Get-Content ".env" | ForEach-Object {
        if ($_ -match '^\s*([A-Z0-9_]+)\s*=\s*(.+?)\s*$') {
            $ports[$matches[1]] = $matches[2]
        }
    }
}

function Get-Port($key, $default) {
    if ($ports.ContainsKey($key) -and $ports[$key]) { return $ports[$key] }
    return $default
}

Write-Host ""
Write-Host "Stack is starting..." -ForegroundColor Green
Write-Host ("Frontend:    http://localhost:{0}" -f (Get-Port 'PORT_FRONTEND'    '3000'))
Write-Host ("API Gateway: http://localhost:{0}" -f (Get-Port 'PORT_GATEWAY'     '8080'))
Write-Host ("Temporal UI: http://localhost:{0}" -f (Get-Port 'PORT_TEMPORAL_UI' '8088'))
Write-Host ("Neo4j:       http://localhost:{0}" -f (Get-Port 'PORT_NEO4J_HTTP'  '7474'))
Write-Host ("MinIO:       http://localhost:{0}" -f (Get-Port 'PORT_MINIO_CONSOLE' '9001'))
