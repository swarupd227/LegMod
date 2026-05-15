# Atlas Migrate · k6 load-test runner.
#
# Usage:
#   .\loadtest\run.ps1 -Scenario dashboard
#   .\loadtest\run.ps1 -Scenario all
#
# Defaults assume the local docker-compose stack is up. To target a
# different deployment, override the env vars:
#   GATEWAY_URL    — default http://localhost:8080
#   FAKE_IDP_URL   — default http://localhost:28093
#   PROJECT_ID     — default 11111111-1111-1111-1111-111111111111
#                     (override with a real project id from your stack)
#
# k6 runs in a containerised image — no host install required. The
# script mounts the repo so the JS files are reachable.

param(
  [ValidateSet("dashboard","project-detail","audit-browser","all")]
  [string]$Scenario = "all",
  [string]$ProjectId,
  [string]$GatewayUrl,
  [string]$FakeIdpUrl
)

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$envArgs = @()
if ($ProjectId)   { $envArgs += @("-e", "PROJECT_ID=$ProjectId") }
if ($GatewayUrl)  { $envArgs += @("-e", "GATEWAY_URL=$GatewayUrl") }
if ($FakeIdpUrl)  { $envArgs += @("-e", "FAKE_IDP_URL=$FakeIdpUrl") }
# When the script runs on the host (Windows) and the stack is in
# docker-compose, k6's containers need to reach the host network. Use
# `host.docker.internal` aliases — supported by Docker Desktop.
$envArgs += @("-e", "GATEWAY_URL=$($GatewayUrl ?? 'http://host.docker.internal:8080')")
$envArgs += @("-e", "FAKE_IDP_URL=$($FakeIdpUrl ?? 'http://host.docker.internal:28093')")

function Run-Scenario([string]$name) {
  Write-Host "▶ Scenario: $name"
  docker run --rm -i `
    -v "${root}:/loadtest" `
    @envArgs `
    --add-host=host.docker.internal:host-gateway `
    grafana/k6:0.54.0 `
    run "/loadtest/scenarios/$name.js"
}

if ($Scenario -eq "all") {
  foreach ($s in @("dashboard","project-detail","audit-browser")) {
    Run-Scenario $s
  }
} else {
  Run-Scenario $Scenario
}
