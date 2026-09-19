# PitFlow Roadmap

현재 기준:

- Phase 1 — Catalog / Estimate / Snapshot: 완료
- Phase 2 — Mechanic Workflow: 완료
- Phase 2 Hotfix — mechanic suggested parts: 완료
- Phase 3 — Admin Operations & Notifications: 완료
- Phase 4A — Inventory Cost Core: 완료
- 현재 다음 구현: Phase 4 후속 범위 — 원가·수익 조회

이 문서의 Phase 번호를 앞으로의 공식 기준으로 사용한다.
`docs/archive`의 과거 Phase 번호는 이전 개발 이력이다.

## Phase 3 — Admin Operations & Notifications 완료

목표:
관리자와 정비사 사이의 운영 흐름을 개선하고, 일부 부품 부족 때문에 전체 작업이 중단되는 구조를 개선한다.

### Phase 3A — Notification Foundation 완료

- DB 기반 알림
- 알림 목록
- 읽지 않은 알림 수
- 읽음 처리
- 최소 알림 UI

초기 알림 유형:

- WORK_ASSIGNED
- WORK_COMPLETED
- PART_SHORTAGE

SMS, 이메일, 카카오톡, WebSocket은 아직 하지 않는다.

### Phase 3B — Assignment / Completion 완료

- 관리자가 정비사를 배정하면 정비사에게 알림
- 재배정 시 새 정비사에게 알림
- 정비사가 작업을 완료하면 관리자에게 알림

### Phase 3C — Part Shortage 완료

- 정비사의 명시적 재고 부족 보고
- 단순 409 오류는 알림으로 만들지 않음
- 작업, 항목, 부품, 필요 수량, 현재 수량 기록
- 관리자에서 부족 보고 확인

### Phase 3D — Item-level Workflow 완료

WorkOrderItem 상태 도입:

- PENDING
- IN_PROGRESS
- COMPLETED
- WAITING_PARTS
- SKIPPED

기존 done boolean 및 운영 데이터와 호환되는 V16 migration을 적용했다.

전체 작업은 모든 항목이 COMPLETED 또는 SKIPPED일 때만 COMPLETED 가능하도록 구현했다.

SKIPPED에는 사유가 필요하도록 구현했다.

### Phase 3E — Admin Workspace / UX Integration 완료

기존 Backend / API / DB 동작을 유지하면서 역할별 프론트엔드 작업 환경을 정리했다.

- CUSTOMER / MECHANIC / ADMIN navigation 분리
- 관리자 업무 흐름 중심의 workflow navigation
- 관리자 작업 현황 요약 및 작업 상세 정보 가독성 개선
- 관리자 작업 상태: 입고 대기 / 작업 중 / 부품 대기 / 출고 대기 / 출고 완료 / 전체 작업
- 정비사 작업 중심 WorkOrder workspace
- 정비 항목 `다음 처리 → 적용` UI
- 작업 결과 fixed feedback notification
- 고객 예약 목록 UX 및 navigation 정리
- Backend / API / DB / Flyway migration 변경 없음

PC 운영 환경을 우선해 구현했다.
모바일 고밀도 운영 화면의 반응형 UX 개선은 Phase 6 Hardening 범위에 포함한다.

---

## Phase 4 — Finance & Cost

목표:
현재 고객 청구 기능 위에 실제 사업 운영 관점의 원가와 수익 정보를 추가한다.

주요 범위:

- 부품 판매가와 매입가 분리
- 입고 lot
- FIFO 또는 명시적인 원가 계산 방식
- 부품 원가
- 정비사 작업 원가 추정
- 매출 / 원가 / 기여이익 조회

기존 고객 invoice snapshot은 유지한다.

Phase 4A 완료 범위:

- 판매가와 분리된 매입원가 lot 및 allocation 원장
- 기존 재고 UNKNOWN opening lot
- 입고 원가, FIFO 사용, 원래 lot 반환, legacy 반환
- 재고 조정 원가 처리와 동시성·멱등성·rollback 검증

재무 dashboard, 정비사·공임 원가, 매출·원가·기여이익 조회와 원가 입력 UI는 후속 범위다.

---

## Phase 5 — Payments

목표:
현재 내부 수납 기록에 실제 PG 결제를 연결한다.

주요 범위:

- 결제 승인
- 취소
- 환불
- webhook
- 중복 webhook 방지
- 결제 idempotency
- PG transaction ID
- 실패 복구
- reconciliation

초기에는 전액 결제 / 전액 취소를 우선한다.

---

## Phase 6 — Hardening / E2E / Production Readiness

주요 범위:

- 전체 E2E
- CUSTOMER / MECHANIC / ADMIN 권한 regression
- 동시성
- idempotency
- PostgreSQL migration 검증
- production 데이터 clone migration
- backup / restore
- logging
- production smoke test
- 최종 문서
- 포트폴리오 자료 정리

---

## 현재 이후 구현 순서

Phase 4
→ Phase 5
→ Phase 6
