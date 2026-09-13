param([Parameter(Mandatory = $true)][string]$BackupFile)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$backup = (Resolve-Path -LiteralPath $BackupFile).Path
if (!(Test-Path -LiteralPath "$backup.sha256")) { throw "Missing .sha256 sidecar file." }
$expected = (Get-Content -LiteralPath "$backup.sha256" -Raw).Trim()
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $backup).Hash -ne $expected) { throw "Backup checksum mismatch." }
Push-Location (Split-Path -Parent $PSScriptRoot)
$token = [guid]::NewGuid().ToString("N")
$testDb = "pitflow_restore_$token"
$remoteFile = "/tmp/pitflow-restore-$token.dump"
$created = $false
try {
    $dbUser = docker compose exec -T db printenv POSTGRES_USER
    if ($LASTEXITCODE -ne 0) { throw "Cannot read DB user. Start Docker Compose first." }
    $dbUser = $dbUser.Trim()
    docker compose cp $backup "db:$remoteFile"
    if ($LASTEXITCODE -ne 0) { throw "Backup copy failed." }
    docker compose exec -T db createdb -U $dbUser -T template0 $testDb
    if ($LASTEXITCODE -ne 0) { throw "Cannot create isolated verification database." }
    $created = $true
    docker compose exec -T db pg_restore -U $dbUser -d $testDb --no-owner --no-acl --exit-on-error --single-transaction $remoteFile
    if ($LASTEXITCODE -ne 0) { throw "Restore failed." }
    $sql = 'SELECT count(*) AS migration_count FROM flyway_schema_history; SELECT count(*) AS work_order_count FROM work_orders; SELECT count(*) AS stock_movement_count FROM stock_movements;'
    docker compose exec -T db psql -U $dbUser -d $testDb -v ON_ERROR_STOP=1 -c $sql
    if ($LASTEXITCODE -ne 0) { throw "Restored database query failed." }
    Write-Host "Restore and sample queries succeeded in isolated database: $testDb"
} finally {
    if ($created) {
        docker compose exec -T db dropdb -U $dbUser $testDb
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove verification database: $testDb" }
    }
    docker compose exec -T db rm -f $remoteFile | Out-Null
    Pop-Location
}
