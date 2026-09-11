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
Push-Location (Join-Path $projectRoot "backend")
try { & .\mvnw.cmd spring-boot:run; if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" } }
finally { Pop-Location }
