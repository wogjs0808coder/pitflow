# 4단계 — 정비 이력·정산·현장 수납

기준 소스: GitHub main `7e12b9a732bf49d367bbe77ea97a08d2a08b4f4e`의 트리 `0df9badb9aa573caee10c2be0c4d5540ea661f94`.
Work의 기준 커밋은 같은 트리를 가진 로컬 커밋이므로 GitHub 커밋 SHA와 다릅니다. 배포용 패치는 GitHub main에 적용하며 Work 브랜치 이력을 직접 push하지 않습니다.

## 저장 구조

V1~V4는 그대로 유지하고 V5를 추가합니다.

- `work_orders.completed_at`: 완료 전이 때 저장. 기존 완료 작업은 상태 이벤트의 완료 시각으로 보충합니다.
- `invoices`: 완료 작업의 정산 명세. OPEN 명세는 작업별 하나만 허용하며 VOID 명세는 계속 보존합니다.
- `invoice_items`: 공임 및 실제 순사용 부품의 명칭·수량·단위·단가·금액 스냅샷.
- `payment_records`: PAYMENT와 원본 PAYMENT를 가리키는 REVERSAL. 취소는 행 추가이며 기존 수납을 삭제하지 않습니다.

공임은 예약→작업 항목의 기존 스냅샷을 이용합니다. 부품은 USE에서 연결된 RETURN 수량을 빼고 원래 USE의 단가로 계산합니다. 수량은 소수 셋째 자리까지, 금액은 원 단위이며 각 항목에서 HALF_UP 반올림 후 합산합니다. 단가 0원 항목은 발행 시 명시적으로 확인합니다. 카탈로그 변경은 과거 금액에 영향을 주지 않습니다.

## 트랜잭션과 동시성

3단계의 `stock_operations` 요청 키 예약·응답 보존·TransactionTemplate를 재사용합니다. 별도 재고 원장이나 중복 요청 저장소를 만들지 않습니다. 모든 변경 API는 UUID `Idempotency-Key`가 필요합니다. 같은 사용자·같은 경로·같은 본문은 최초 성공 응답을 재전송하며, 같은 키의 다른 요청은 409입니다. 실패하면 명세·항목·수납·요청 키를 함께 롤백합니다.

잠금 순서는 작업 행→명세 행입니다. 3단계 부품 반환도 작업 행을 먼저 잠그므로 발행·수납과 직렬화됩니다. 미리보기의 fingerprint를 발행 시 재검사하고, 수납 시에도 원본 작업과 명세가 같은지 재검사합니다. 중복 발행은 작업 잠금과 활성 명세 UNIQUE, 이중 수납은 명세 잠금과 순수납 검사로 방지합니다. 반환이 수납 뒤에 일어나면 명세가 변경됨으로 표시되며 기존 금액은 보존됩니다.

반환으로 금액이 바뀐 경우: 수납 취소 기록(이미 수납했다면)→명세 취소→미리보기→재발행. 실물 재고 반환은 기존 3단계 API로 별도 기록합니다. 명세 취소나 수납 취소는 재고를 변경하지 않습니다.

수납은 전액 한 번, 취소도 원본 전액만 지원합니다. 전액 취소 후 다시 수납할 수 있습니다. 0원 명세는 발행 가능하지만 수납 기록을 만들지 않습니다. 현금·카드·계좌이체로 현장에서 확인한 결제 사실만 기록하며 외부 카드 승인·환불을 실행하지 않습니다. 세금계산서, VAT 분리, 할인, 부분 수납·부분 환불은 포함하지 않습니다.

## API

모든 경로는 `/api` 기준입니다. 인증·ADMIN·CSRF는 기존 구조를 유지합니다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/admin/billing/work-orders/{id}/preview` | 완료 작업 정산 미리보기 |
| POST | `/admin/billing/work-orders/{id}/invoices` | expectedFingerprint, confirmZeroPrices로 발행 |
| GET | `/admin/billing/invoices` | 명세 목록 |
| GET | `/admin/billing/invoices/{id}` | 상세·수납 이력·변경 여부 |
| POST | `/admin/billing/invoices/{id}/payments` | method, reference, expectedTotal로 수납 |
| POST | `/admin/billing/payments/{id}/reverse` | reason으로 수납 취소 |
| POST | `/admin/billing/invoices/{id}/void` | reason으로 명세 취소 |
| GET | `/admin/billing/summary?from=YYYY-MM-DD&to=YYYY-MM-DD` | 한국 시간 기간별 완료·수납·취소·순수납 |
| GET | `/billing/history?vehicleId=UUID` | 본인 차량 완료·취소 작업, vehicleId 생략 가능 |
| GET | `/billing/invoices/{id}` | 본인 작업 명세 상세 |

고객은 본인 작업만 볼 수 있고 다른 고객 명세는 404입니다. 관리자 화면의 메뉴 제한 외에도 서버에서 역할을 검사합니다. 일별 순수납은 수납이 실제 기록된 날에서 취소가 기록된 날의 금액을 빼므로 음수가 될 수 있습니다.

## 화면·시연

- 관리자 `/admin/billing`: 완료 작업 선택→금액 미리보기→명세 발행→전액 수납, 수납 취소·명세 취소, 기간별 운영 현황.
- 고객 `/history`: 차량 필터, 완료·취소 작업, 공임 항목, 명세와 수납 이력.
- 불확실한 변경 응답은 3단계 `useWorkCommand`의 동일 요청 재확인을 이용합니다.
- 사용자 PC에서 예약→방문→입고→부품 사용→항목 완료→작업 완료→명세 발행→수납→고객 이력 시나리오를 확인합니다.

검증 결과는 `PHASE4-VALIDATION.md`에 기록합니다.
