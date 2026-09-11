$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot ".env"
if (Test-Path $envPath) { Write-Host ".env already exists; no changes made."; exit 0 }
function New-RandomPassword {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [System.BitConverter]::ToString($bytes).Replace("-", "").ToLowerInvariant()
}
$dbPassword = New-RandomPassword
$adminPassword = New-RandomPassword
$lines = @("DB_USERNAME=pitflow", "DB_PASSWORD=$dbPassword", "PITFLOW_ADMIN_EMAIL=admin@pitflow.local", "PITFLOW_ADMIN_PASSWORD=$adminPassword", "COOKIE_SECURE=false")
[System.IO.File]::WriteAllLines($envPath, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Created .env with random passwords. Open .env to see the administrator login."
Write-Host "Start: docker compose up --build -d"
