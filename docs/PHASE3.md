# 3단계 — 정비 작업과 부품 재고

재고 실사 보정·삭제·기본 정비/부품 목록·예약 안내의 추가 변경은 [PHASE3-FIXES.md](PHASE3-FIXES.md)를 참고합니다. 아래는 최초 V3 구현 기록이며 V4에서 확장한 정책은 추가 문서가 우선합니다.

## 기준 및 보존

- 시작 커밋: `62f8cc3022f05982082e71d8ec52118494b497f1` (2단계 main 병합).
- 작업 브랜치: `feat/work-orders`. 기존 main 참조를 변경하지 않음.
- 사용자 제공 Git bundle에서 코드와 이력을 가져왔으며, 기존 작업 커밋과 비교 시 README 변경만 있었다.
- V1/V2는 변경하지 않고 `V3__work_orders_and_inventory.sql`을 추가했다.
- Windows에서 미커밋 상태였던 README는 bundle에 포함되지 않는다. 이번 변경에는 README를 포함하지 않는다.
- 기존 세션 인증, CSRF, ADMIN 경로 권한, 본인 소유 조회를 유지한다.

## DB

| 테이블 | 역할 |
|---|---|
| mechanics | 사번·이름·활성 상태. 로그인 계정과 분리된 정비사 명부 |
| work_orders | 예약당 하나의 입고, 고객·차량, 주행거리, 담당자 이름 스냅샷, 상태 |
| work_order_items | 예약 항목의 명칭·공임·소요시간 스냅샷과 완료 여부 |
| work_order_events | 입고·담당 변경·항목 처리·상태 변경의 행위자와 이력 |
| parts | 부품번호·명칭·단위·현재 잔량·안전재고·단위당 가격 |
| stock_operations | 전역 UUID 요청 키, 요청자, 요청 해시, 성공 응답 JSON |
| stock_movements | RECEIPT/USE/RETURN, 양수 수량, 처리 후 잔량, 원래 사용 ID, 당시 명칭·단위·가격 |

모든 수량은 NUMERIC(14,3)/BigDecimal을 사용한다. 0보다 큰 수량, 소수 최대 3자리, 최대 99,999,999,999.999를 허용한다. 0.1250처럼 네 자리로 보낸 요청도 API 입력 검증에서 거절한다. 수량의 요청 JSON은 문자열 또는 숫자로 보낼 수 있다. 브라우저는 입력 문자열을 그대로 전송한다.

단위 EA/L/KG/M는 등록 이후 바꾸지 않는다. 부품은 재고 0으로 생성하며 직접 잔량을 편집하는 API는 없다. 비활성 부품의 신규 입고·사용은 금지하며, 실제 반환은 허용한다. 가격 변경은 과거 이동 기록에 영향을 주지 않는다.

## 작업 흐름

1. 기존 예약 캘린더에서 예약을 방문 처리(VISITED)한다.
2. 관리자 작업 화면에서 해당 날짜의 방문 예약을 선택하여 입고 등록한다.
3. 입고 주행거리가 현재 차량 주행거리보다 작으면 409. 차량 갱신과 입고 기록은 같은 트랜잭션이다.
4. 활성 정비사를 배정한다. 과거 배정 이력은 보존한다.
5. RECEIVED → IN_PROGRESS → COMPLETED 순으로 진행한다.
6. 부품이 없으면 IN_PROGRESS → WAITING_PARTS → IN_PROGRESS로 처리한다. 부품 대기 상태에서 바로 완료할 수 없다.
7. IN_PROGRESS에서만 항목 체크와 부품 사용이 가능하다. 모든 항목을 체크해야 완료할 수 있다.
8. 미종료 상태에서 사유를 입력하여 CANCELLED로 전환할 수 있다. 완료·취소 상태는 재개할 수 없다.

취소해도 사용 부품을 자동으로 재고에 복원하지 않는다. 오일처럼 이미 소모한 물품은 재고가 아니다. 실제 회수한 수량만 원래 USE ID를 지정하여 RETURN을 추가한다. 완료·취소 이후에도 실제 반환은 가능하다. 이미 반환한 수량과 이번 반환의 합은 원래 사용량 이하여야 한다. 사용 행과 기존 반환 행은 수정·삭제하지 않는다.

## 트랜잭션과 동시성

3단계 변경 API는 UUID `Idempotency-Key` 헤더가 필수다. 키의 범위는 사용자·엔드포인트별이 아닌 DB 전체이며, 해시에 경로·대상·요청 내용을 포함하고 요청자도 비교한다. 동일 키·동일 요청은 최초 성공 응답을 재전송한다. 다른 요청자·대상·내용으로 키를 재사용하면 409다. 부품 사용 목록은 부품 UUID 문자열 순으로 정렬하고 수량의 불필요한 0을 제거하여 해시한다. 맵 키도 정렬하여 서버 재시작 후 해시 순서가 달라지지 않도록 한다.

처리 순서:

1. 트랜잭션 안에서 stock_operations의 키를 UNIQUE INSERT로 확보한다.
2. 작업 관련 변경은 작업지시서 행을 FOR UPDATE로 잠근다.
3. 여러 부품은 UUID 문자열 오름차순으로 하나씩 FOR UPDATE 잠근다.
4. 전체 수량·활성 상태·부족 재고를 검사한다.
5. 잔량 갱신, 이동 이력 추가, 응답 저장을 같은 트랜잭션에서 커밋한다.

실패 시 키와 잔량·이력이 모두 롤백된다. 부족 재고를 입고한 후 같은 키로 실패한 요청을 재시도할 수 있다. 동시 중복 키 INSERT는 선행 트랜잭션을 기다린다. 중복 예외가 나면 TransactionTemplate 밖, 즉 실패 트랜잭션이 끝난 뒤 기존 성공 결과를 조회한다. 실패한 PostgreSQL 트랜잭션 안에서 SQL을 계속 실행하지 않는다.

입고는 예약 → 차량 → 정비사 순서로 잠그고 예약당 고유 제약을 적용한다. 담당 변경은 작업 → 정비사, 사용·반환은 작업 → 부품 순서다. 부품 입고는 해당 부품만 잠근다. 반환은 작업 행 잠금 아래 누적 반환량을 검사하므로 서로 다른 키의 반환 경쟁도 초과 복원할 수 없다. DB CHECK(quantity >= 0)가 음수 잔량을 추가로 방어한다. Spring 트랜잭션 제한은 15초이며 일시적인 DB 잠금 실패·타임아웃은 503으로 반환한다. 같은 키로 재시도한다.

작업/이동 기록은 애플리케이션 API에서 추가만 하며 삭제·편집 경로를 제공하지 않는다. 운영 DB 소유자가 직접 SQL로 기록을 변경하는 것까지 막는 DB 트리거/감사 체계는 이번 범위에 포함하지 않는다.

## API

변경 요청은 기존 CSRF 헤더 + `Idempotency-Key: UUID`가 필요하다. 새 생성·변경·재전송 모두 200으로 응답한다. 수량 외 입력은 camelCase, DB 기반 응답 필드는 snake_case이다.

| 메서드 | 경로 | 요청 |
|---|---|---|
| GET | /api/admin/mechanics | 명부 |
| POST/PATCH | /api/admin/mechanics[/{id}] | code, name, active |
| GET | /api/admin/parts | 부품 목록 |
| POST/PATCH | /api/admin/parts[/{id}] | sku, name, unit, minimumQuantity, unitPrice, active |
| POST | /api/admin/parts/{id}/receipts | quantity, reason |
| GET | /api/admin/parts/{id}/movements | 재고 이력 |
| POST | /api/admin/work-orders/from-appointment | appointmentId, receivedMileage, mechanicId, notes |
| GET | /api/admin/work-orders[/{id}] | 전체 목록/상세 |
| PATCH | /api/admin/work-orders/{id}/assignment | mechanicId |
| PATCH | /api/admin/work-orders/{id}/status | status, reason (취소 필수) |
| PATCH | /api/admin/work-orders/{id}/items/{itemId} | done |
| POST | /api/admin/work-orders/{id}/parts/use | lines: [{partId, quantity}], reason |
| POST | /api/admin/work-orders/{id}/parts/return | originalUseId, quantity, reason |
| GET | /api/work-orders[/{id}] | 로그인 고객 본인 목록/상세 |

부품 사용 목록은 최대 30종이며 같은 부품을 두 줄로 보낼 수 없다. 반환 한 요청은 원래 사용 기록 하나를 대상으로 한다. 작업 상세는 items/events/movements를 포함한다. 고객에게 재고 잔량·내부 행위자 ID는 노출하지 않는다. 고객 본인이 아닌 작업 조회는 404다.

## 프론트엔드

- `/admin/work-orders`: 날짜별 방문 예약 입고, 담당 배정, 항목 체크, 상태 변경, 다중 부품 사용, 개별 실물 반환, 작업 이력.
- `/admin/parts`: 부품 등록/정보 수정/비활성화, 입고, 안전재고 표시, 이동 이력, 정비사 등록/수정/비활성화.
- `/work-orders`: 본인 작업 목록과 상태·정비 항목·사용/반환 내역.
- 중복 클릭은 요청 중 차단한다. 요청 키·payload를 계정별 sessionStorage에 보관하며, 통신 오류/5xx이면 원래 요청을 재전송한다. 페이지 새로고침 후에도 미확인 요청을 복원한다. 인증·CSRF 토큰은 저장하지 않는다. 성공 또는 명확한 4xx 후 요청을 정리한다. 탭 종료/저장소 삭제 시에는 기존 내역을 먼저 확인한다.

## Windows 적용

패치 파일을 내려받은 뒤 아래 명령을 **각각 성공 여부를 확인하면서** 실행한다. README 미커밋 수정은 이 패치 대상이 아니다.

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"
git switch feat/work-orders
git status --short
git am "$env:USERPROFILE\Downloads\pitflow-phase3.patch"
docker compose up --build -d
docker compose ps
docker compose logs --tail=100 backend
```

Flyway가 V3를 적용한다. `docker compose down -v` 같은 DB 볼륨 초기화는 필요하지 않다. 백엔드 재시작 후 다시 로그인한다. 기존 포트는 프론트 3000, 백엔드 8081, DB 내부 5432이다.

```powershell
cd "$env:USERPROFILE\Desktop\pitflow\backend"
.\mvnw.cmd verify
cd ..\frontend
npm ci
npm run build
npm run typecheck
cd ..
git push -u origin feat/work-orders
```

먼저 정비사·부품 등록 → 부품 실물 입고 → 예약 방문 처리 → 작업 입고 → 작업 시작 → 항목 체크/부품 사용 → 완료를 시연한다. 취소 후 잔량 불변, 실물 일부 반환 후 잔량 증가도 확인한다.

## 검증 및 한계

PhaseThreeIntegrationTest는 입고 중복/주행거리/예약 상태, 수량 검증, CSRF/권한/본인 조회, 다중 품목 부족 시 롤백, DB 제약 실패 후 롤백, 같은 키 동시 요청, 다른 작업의 동시 차감, 역순 품목 잠금, 동시 반환, 취소·사용 경쟁, 가격 스냅샷과 원장-잔량 일치를 검사한다. 기존 V1/V2 회귀 테스트도 함께 실행한다.

로컬 기본 테스트는 H2 PostgreSQL 모드이고 CI는 별도 PostgreSQL 17 테스트 DB를 사용한다. 운영 DB에 테스트를 실행하지 않는다. 이번 변경의 실제 PostgreSQL CI, Windows Docker 및 브라우저 시연 결과는 별도로 확인해야 한다.

재고 실사 조정/공급업체/발주/수납, 작업 슬롯 연장, 정비사 로그인 역할은 후속 범위이다. 전체 목록에 페이지네이션을 추가하는 작업도 운영 규모 확장 전에 필요하다. 부품 단가와 공임은 수납 확정 금액이 아니다.
