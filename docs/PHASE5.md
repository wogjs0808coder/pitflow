# 5단계 — 배포·발표 준비

4단계 정산, 휴무 관리, 출고 기록까지 로컬 구현했습니다. 이 문서는 배포 전 실행 순서입니다. 호스팅 대상은 아직 결정하지 않았으며 실제 배포는 수행하지 않았습니다.

## 1. 이번 브랜치 저장 및 CI

추가 UI 패치를 적용한 뒤 feat/billing-history를 push하고 main 대상으로 PR을 생성합니다. PostgreSQL backend 테스트 및 frontend build/typecheck CI가 통과해야 병합합니다. .env와 기존 사용자의 미커밋 README 변경은 추가 커밋에 섞지 않습니다.

## 2. 시연 확인표

- 고객: 차량 등록, 정비 항목 선택, 예약 신청.
- 관리자: 휴무일 지정 및 특별 영업 설정, 기존 예약 보존 확인.
- 관리자: 미래 확정 예약의 조기 방문·입고, 정비사 배정.
- 부품 화면: 검색·안전재고 필터·정비사 탭·삭제 부품 표시·복원 확인.
- 작업: 소수 부품 사용, 부족 재고 거절, 반환 기록.
- 작업 완료 후 출고 대기 표시, 명세 발행·수납, 실제 인도 후 출고 완료.
- 고객: 정비 이력·명세 확인. 관리자 작업 목록에서 출고 완료 시각 확인.
- 수납 취소·명세 취소·재발행의 기존 이력 보존 확인.
- 모바일 폭에서 검색, 상태 필터, 상세 폼 및 체크박스 조작 확인.

## 3. 배포 구성 확정 후 진행할 일

Next.js·Spring Boot·PostgreSQL 운영 환경과 HTTPS 도메인을 선정합니다. 서버 내부 API 주소, secure 세션 쿠키, 운영 비밀번호를 환경변수로 설정하고 DB 외부 노출을 제한합니다. PostgreSQL 백업을 만들고 별도 DB로 복원하는 검증을 한 뒤 실제 서비스 데이터를 이전합니다. 백엔드 재시작 시 메모리 세션이 사라져 재로그인이 필요합니다.

## 4. 발표 자료

예약 슬롯의 고유 제약, 재고 행 잠금·소수 수량·idempotency, 정산 스냅샷·취소 원장, 휴무 변경 경쟁, 출고 시각 보존을 중심으로 시연합니다. 실제 CI 통과 화면과 사용자 PC의 성공 시나리오를 증거로 남깁니다. 온라인 카드 승인, 자동 차종별 부품 호환 검증, 작업장 실시간 점유 최적화는 구현 범위에 포함하지 않습니다.

## 5. DB 백업 및 별도 DB 복원 확인 (Windows)

Docker Compose의 DB가 실행 중인 상태에서 프로젝트 루트 터미널을 사용합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-db.ps1
```

`backups`에 생성된 `.dump`와 `.dump.sha256` 파일을 함께 보관합니다. 출력된 실제 파일 경로를 사용합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-db-backup.ps1 -BackupFile .\backups\실제파일명.dump
```

- 백업은 pg_dump custom 형식입니다. Windows PowerShell의 바이너리 출력 리다이렉션 대신 컨테이너 파일을 복사합니다.
- 복원 검사는 SHA256을 확인하고 임의 이름의 새 DB를 생성합니다. 복원은 단일 트랜잭션으로 실행되며 오류가 나면 실패합니다. 기존 애플리케이션 DB는 복원 대상으로 사용하지 않습니다.
- 복원 후 migration·작업·재고 원장 조회를 확인하고 검사 DB를 제거합니다. 중간에 터미널을 강제 종료하면 검사 DB가 남을 수 있습니다. 제거 실패 시 표시되는 이름을 기록하세요.
- 이 검사는 복원 가능성과 일부 테이블 조회만 확인합니다. 로그인·예약·수납까지의 복구 시연이나 운영 DB 교체를 수행하지 않습니다.
- 백업에는 사용자 정보가 들어갑니다. Git에서 제외한 `backups/`에 보관하고, 별도 접근 제한 저장소에도 복사하세요. SHA256은 전송 오류 검사용이며 암호화가 아닙니다.
- 역할·비밀번호·서버 설정은 백업에 포함하지 않습니다. 복구 대상에 DB 역할과 환경설정을 별도로 준비해야 합니다.
- 운영 복구는 서비스 쓰기를 중지하고, 현 DB를 먼저 백업한 뒤, 별도 DB 복원과 검증을 마치고 접속 대상을 전환하는 순서로 진행합니다. V1~V7을 되돌려 수정하거나 데이터 볼륨을 삭제하지 않습니다.

## 6. 이 준비 패치 적용

2026-09-13 확인: `feat/billing-history`의 `1302eec` push CI 성공. 당시 main은 `7e12b9a`이며 열린 PR은 없었습니다. 이 준비 변경은 4단계 최종 코드에 의존합니다.

먼저 4단계 PR을 main에 병합한 뒤, 아래 순서로 **실제 최신 main**에서 분기합니다. `git status --short`에 변경이 있으면 본인 변경을 먼저 별도로 보관하세요.

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"
git status --short
git fetch origin
git switch main
git pull --ff-only origin main
git switch -c chore/release-readiness
git am "$env:USERPROFILE\Downloads\pitflow-release-readiness.patch"
if ($LASTEXITCODE -ne 0) { throw "Patch failed. Stop and inspect git status." }
```

위 백업·복원 검사를 Windows에서 실행한 후:

```powershell
git push -u origin chore/release-readiness
```

실제 서버 배포와 HTTPS 검사는 호스팅 대상 확정 후 진행합니다. 이 패치에는 운영 인프라 생성이나 앱 DB schema 변경이 없습니다.

## 7. 준비 변경 검증 기록

2026-09-13 Work 검사: backend 전체 54개 테스트 성공(H2), frontend build 및 typecheck 성공, git diff 공백 검사 성공. 애플리케이션 소스와 V1~V7은 변경하지 않았습니다. 이 환경에 PowerShell과 Docker 실행 환경이 없어 신규 백업·복원 스크립트의 실제 실행 검증은 미완료입니다. 앞서 확인한 GitHub CI 성공은 4단계 커밋에 대한 결과이며 이 준비 패치의 CI 결과가 아닙니다.
