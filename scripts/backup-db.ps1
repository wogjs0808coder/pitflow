param()
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Push-Location (Split-Path -Parent $PSScriptRoot)
$token = [guid]::NewGuid().ToString("N")
$remoteFile = "/tmp/pitflow-$token.dump"
$localFile = Join-Path (Get-Location) "backups/pitflow-$((Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss'))-$token.dump"
try {
    $dbUser = docker compose exec -T db printenv POSTGRES_USER
    if ($LASTEXITCODE -ne 0) { throw "Cannot read DB user. Start Docker Compose first." }
    $dbName = docker compose exec -T db printenv POSTGRES_DB
    if ($LASTEXITCODE -ne 0) { throw "Cannot read DB name." }
    New-Item -ItemType Directory -Force -Path (Split-Path $localFile) | Out-Null
    docker compose exec -T db pg_dump -U $dbUser.Trim() -d $dbName.Trim() -Fc --no-owner --no-acl -f $remoteFile
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed." }
    docker compose exec -T db pg_restore --list $remoteFile | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Archive listing failed." }
    docker compose cp "db:$remoteFile" $localFile
    if ($LASTEXITCODE -ne 0) { throw "Backup copy failed; do not use a partial file." }
    if ((Get-Item $localFile).Length -eq 0) { throw "Empty backup." }
    $hash = (Get-FileHash -Algorithm SHA256 $localFile).Hash
    Set-Content -Encoding ASCII -Path "$localFile.sha256" -Value $hash
    Write-Host "Backup saved: $localFile"
    Write-Host "Run scripts/verify-db-backup.ps1 -BackupFile with this path to test restoration."
} finally {
    docker compose exec -T db rm -f $remoteFile | Out-Null
    Pop-Location
}
