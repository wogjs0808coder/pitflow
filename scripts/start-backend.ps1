$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot ".env"
if (!(Test-Path $envPath)) { throw "Run scripts/setup.ps1 first." }
$allowedKeys = @("DB_USERNAME", "DB_PASSWORD", "PITFLOW_ADMIN_EMAIL", "PITFLOW_ADMIN_PASSWORD", "COOKIE_SECURE")
Get-Content $envPath | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$' -and $allowedKeys -contains $Matches[1]) {
        [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], "Process")
    }
}
# Used with compose.dev.yaml. Docker-to-Docker URLs remain unchanged.
if (!$env:DB_URL) { $env:DB_URL = "jdbc:postgresql://127.0.0.1:5433/pitflow" }
if (!$env:PORT) { $env:PORT = "8081" }
Push-Location (Join-Path $projectRoot "backend")
try { & .\mvnw.cmd spring-boot:run; if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" } }
finally { Pop-Location }
