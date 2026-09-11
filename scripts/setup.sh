#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then
  echo '.env already exists; no changes made.'
  exit 0
fi
umask 077
python3 - <<'PYSETUP'
from pathlib import Path
import secrets
Path('.env').write_text('DB_USERNAME=pitflow\nDB_PASSWORD='+secrets.token_hex(24)+'\nPITFLOW_ADMIN_EMAIL=admin@pitflow.local\nPITFLOW_ADMIN_PASSWORD='+secrets.token_hex(24)+'\nCOOKIE_SECURE=false\n')
print('Created .env with random passwords. Open .env for administrator login.')
PYSETUP
