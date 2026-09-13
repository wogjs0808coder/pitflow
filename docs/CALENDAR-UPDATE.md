# 휴무 관리 및 조기 입고 추가

관리자 본인 차량 기능은 기존 테스트·시연 용도로 유지합니다.

## 동작

- 관리자 예약 캘린더의 ‘휴무일 관리’에서 정기 휴무 요일을 설정합니다.
- 날짜별 임시 휴무/특별 영업이 정기 요일보다 우선합니다. 예외를 삭제하면 정기 설정을 따릅니다.
- ‘예외 목록에 추가’ 후 ‘휴무 설정 저장’을 눌러야 DB에 저장됩니다.
- V6를 추가하며 이미 배포 가능한 V1~V5는 변경하지 않습니다. 첫 저장 전에는 기존 환경변수의 정기 휴무를 따릅니다. 첫 저장 후에는 DB 설정을 사용합니다.
- 휴무 변경은 신규 예약 생성과 가능 시간 조회에 적용합니다. 기존 예약을 자동 취소하지 않습니다. 휴무일에 기존 예약이 있으면 캘린더에서 확인하고 고객과 별도로 조정합니다.
- 날짜별 예외는 최대 366개이며 저장 때 revision을 검사합니다. 다른 관리자의 변경이나 응답 유실 후에는 ‘저장된 설정 다시 불러오기’로 최신 상태를 확인합니다.
- 예약 생성과 휴무 설정 저장은 공통 설정 행을 먼저 잠급니다. 짧은 예약 저장 트랜잭션도 이 행에서 직렬화되며 휴무 변경을 통과한 예약이 뒤늦게 저장되는 것을 방지합니다.

확정 예약은 예약일 이전에도 방문 처리할 수 있습니다. 대기 예약은 먼저 확정해야 하며 종료한 예약·취소·미방문 상태는 기존 전이 제한을 유지합니다. 고객의 직접 방문 처리 권한은 없습니다.

작업 관리에서 원래 예약 날짜를 선택→예약 확정→실제 도착한 고객 방문 처리→주행거리·정비사 입력→입고합니다. 예약 날짜·슬롯을 앞당겨 재배치하지 않고 원래대로 보존하며 실제 입고 시각은 work_orders.received_at에 기록합니다. 조기 작업의 실제 작업장 점유와 겹침은 자동 스케줄링하지 않으므로 담당자가 확인합니다.

## API

- `GET /api/admin/booking-calendar`: revision, closedDays, overrides 반환.
- `PUT /api/admin/booking-calendar`: 같은 구조를 저장. ADMIN과 CSRF 필요. 수정 버전 충돌은 409.
- 기존 `GET /api/appointments/availability` 및 예약 POST는 저장된 휴무일을 반영합니다.
- 기존 예약 상태 변경 API의 VISITED는 30분 전 제한이 해제됩니다. 예약 종료 전 제한은 유지됩니다.

## 적용

이미 이전 4단계 패치를 적용했다면 추가 패치 `pitflow-calendar-update.patch`만 사용합니다. 아직 4단계 패치를 적용하지 않았다면 `pitflow-phase4-with-calendar.patch`를 main 7e12b9a에서 만든 feat/billing-history에 적용합니다. 두 패치를 모두 적용하지 않습니다.

이전 4단계가 적용된 경우 VSCode PowerShell:

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"
git switch feat/billing-history
git status --short
# 변경사항이 있으면 먼저 별도 보관하고 진행하세요.
git am "$env:USERPROFILE\Downloads\pitflow-calendar-update.patch"
# 실패하면 여기서 중지하고 오류를 확인하세요.
docker compose up --build -d
docker compose logs --tail=100 backend
```

입고 테스트: 내일 날짜로 고객 예약→관리자 확정→오늘 방문 처리→입고. 휴무 테스트: 임시 휴무를 추가한 날짜에서 신규 예약 불가 확인→특별 영업으로 변경→예약 시간 재조회. 기존 예약과 차량·재고 데이터는 유지합니다. DB 볼륨을 삭제하지 않습니다.

Work에서 push는 수행하지 않습니다. 로컬 실행 확인 후에만 `git push -u origin feat/billing-history`로 올립니다.

## 검증 결과

- H2 기반 backend 전체 53개 통과(1~4단계 regression 포함).
- 정기 휴무·특별 영업일 신규 예약, 기존 예약 보존, ADMIN/CSRF, revision 충돌 및 동시 수정 테스트 통과.
- 기존 시간 전이 테스트를 예약 전날 VISITED 성공 기준으로 변경해 조기 방문과 종료 상태 제한을 함께 확인.
- frontend build 및 typecheck 통과.
- native PostgreSQL CI, Windows Docker와 실제 브라우저 조작은 이번 Work에서 실행하지 않았습니다.
