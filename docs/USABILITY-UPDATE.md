# 관리 화면 개선 및 출고

- 부품 재고/정비사 탭 분리, 부품명·번호 검색, 안전재고 이하·사용 중지 필터.
- 삭제 부품 표시를 포함한 체크박스를 18px로 고정하고 라벨 클릭 영역 확보.
- 데스크톱 목록에 스크롤 영역을 적용하고 부품 정보 수정·재고 보정 폼을 접어서 표시.
- 작업은 진행 중 / 정비 완료·출고 대기 / 출고 완료 / 취소 / 전체로 필터링. 차량번호·차종·정비사 검색과 항목 완료 개수 표시.
- 정비 완료 후 실제 차량 인도를 확인하고 출고 완료 처리. 기존 완료 작업을 자동으로 출고 처리하지 않음.
- V7에 released_at 및 완료 상태 제약 추가. V1~V6 유지. 작업 행 잠금과 기존 요청 키 처리를 사용해 출고 시각·이벤트 중복 방지.
- 출고와 수납은 별도 기록이며 출고 버튼 옆 정산 화면에서 수납을 확인할 수 있음. 미수납 출고를 서버가 차단하는 정책은 적용하지 않음.

## 적용 및 GitHub 저장

휴무·조기 입고 패치까지 적용된 feat/billing-history에서 사용합니다. pitflow-usability-update.patch를 Downloads에 다운로드합니다. 명령 오류가 나면 이후 명령은 실행하지 않습니다.

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"
git switch feat/billing-history
if ($LASTEXITCODE -ne 0) { throw "브랜치 확인 필요" }
git status --short
# 기존 수정이 있다면 먼저 따로 보관합니다. 예: git stash push -u -m "before-ui-update"
git am "$env:USERPROFILE\Downloads\pitflow-usability-update.patch"
if ($LASTEXITCODE -ne 0) { throw "패치 오류를 확인하세요. 필요 시 git am --abort" }
docker compose up --build -d
if ($LASTEXITCODE -ne 0) { throw "Docker 실행 오류 확인 필요" }
docker compose logs --tail=100 backend
```

정상 확인 후:

```powershell
git branch --show-current
git log -3 --oneline
git status --short
git push -u origin feat/billing-history
```

패치는 git am으로 커밋되므로 git add -A 및 별도 commit은 필요하지 않습니다. 사용자가 따로 수정한 파일은 검토 후 별도로 커밋합니다. stash를 만들었다면 적용 후 git stash pop으로 복원하며 충돌 시 stash는 보존됩니다.

GitHub 저장소에서 Compare & pull request를 눌러 base main / compare feat/billing-history로 PR을 만듭니다. 제목 예: `feat: 정산·수납·휴무 관리와 출고 기능`. CI의 backend(PostgreSQL)와 frontend 검사가 통과하고 로컬 시연을 확인한 후 병합합니다. Work에서는 push하거나 main을 수정하지 않았습니다.

적용 완료된 patch 파일은 삭제해도 됩니다. Git 커밋과 실행 데이터는 patch 파일과 별개입니다. patch 삭제는 .env나 DB 볼륨 삭제와 관련이 없습니다.

다음 단계는 PHASE5.md의 배포·발표 준비 순서를 따릅니다. 실제 배포는 아직 수행하지 않았습니다.

## 검증

2026-09-13 frontend build·typecheck 성공. backend 전체 테스트 실행 종료 코드 0, 테스트 보고서 합계 54개·실패 0·오류 0. 실제 PostgreSQL CI와 Windows 브라우저 시연은 로컬 적용 후 확인해야 합니다. V1~V6 migration 및 main 브랜치는 변경하지 않았습니다.
