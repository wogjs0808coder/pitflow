# Windows 로컬 적용

`pitflow-phase4.patch`를 다운로드하여 Downloads 폴더에 저장합니다. 패치는 4단계 변경만 포함하며 V1~V4, README, .env는 포함하지 않습니다. Work에서는 GitHub push를 수행하지 않았습니다.

아래는 VSCode PowerShell에서 실행합니다. 기존 변경은 stash에 보관하고 패치 적용 후 복원합니다. 이미 같은 이름의 브랜치가 있으면 덮어쓰지 않고 중지합니다. 오류가 나면 이후 명령을 실행하지 말고 출력 내용을 확인합니다.

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"
$ErrorActionPreference = "Stop"
$patchPath = "$env:USERPROFILE\Downloads\pitflow-phase4.patch"
if (!(Test-Path $patchPath)) { throw "Downloads의 pitflow-phase4.patch를 확인하세요." }

git fetch origin
if ($LASTEXITCODE -ne 0) { throw "fetch 실패" }
$base = git rev-parse origin/main
if ($base -ne "7e12b9a732bf49d367bbe77ea97a08d2a08b4f4e") {
    throw "origin/main이 7e12b9a 이후 변경됐습니다. 새 main과 패치 충돌을 먼저 검토하세요."
}
git show-ref --verify --quiet refs/heads/feat/billing-history
if ($LASTEXITCODE -eq 0) { throw "feat/billing-history가 이미 있습니다. 기존 작업을 확인하세요." }

$hadChanges = [bool](git status --porcelain)
if ($hadChanges) {
    git stash push -u -m "before-phase4-local-apply"
    if ($LASTEXITCODE -ne 0) { throw "기존 변경 보관 실패" }
}
git switch -c feat/billing-history origin/main
if ($LASTEXITCODE -ne 0) { throw "브랜치 생성 실패. 보관한 stash는 유지됩니다." }
git am $patchPath
if ($LASTEXITCODE -ne 0) {
    throw "패치 적용 실패. git am --abort로 패치 적용을 취소할 수 있습니다. 기존 stash는 유지됩니다."
}
if ($hadChanges) {
    git stash pop
    if ($LASTEXITCODE -ne 0) { throw "기존 변경 복원 충돌. stash는 보존되므로 충돌부터 해결하세요." }
}
git status --short
git log -1 --oneline

docker compose up --build -d
if ($LASTEXITCODE -ne 0) { throw "Docker 실행 결과를 확인하세요." }
docker compose ps
docker compose logs --tail=100 backend
```

관리자 `http://localhost:3000/admin/billing`, 고객 `http://localhost:3000/history`에서 시연합니다. 데이터 볼륨은 삭제하지 않습니다. 기존 완료 작업이 없으면 입고→작업 중→항목 완료→작업 완료를 먼저 진행합니다. V5는 백엔드 시작 때 자동 적용됩니다.

확인 후 4단계 커밋만 push합니다. `git add -A`는 필요하지 않습니다. 기존 README 수정이 복원됐다면 미커밋 상태로 남습니다.

```powershell
git branch --show-current
# feat/billing-history인지 확인
git push -u origin feat/billing-history
```

그다음 GitHub에서 `feat/billing-history` → `main` PR을 만들고 PostgreSQL CI를 확인합니다. main 직접 push나 강제 push는 하지 않습니다.
