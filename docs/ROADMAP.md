# PitFlow Roadmap

현재 기준:

- Phase 1 — Catalog / Estimate / Snapshot: 완료
- Phase 2 — Mechanic Workflow: 완료
- Phase 2 Hotfix — mechanic suggested parts: 완료
- Phase 3 — Admin Operations & Notifications: 완료
- Phase 4A — Inventory Cost Core: 완료
- Phase 4B — Finance Integration: 완료
- Phase 4C — Final Validation / Production Readiness / Documentation: 완료
- Phase 4 — Finance & Cost: 완료
- 현재 단계: Phase 5A — Treasury Core & Rebalancing 완료

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

Phase 4B 완료 범위:

- 정비사 시간당 원가와 WorkOrder 완료 시점 인건비 snapshot
- invoice 매출, FIFO 부품원가, 인건비원가, 총원가, 기여이익 조회
- UNKNOWN 원가를 확정 이익으로 계산하지 않는 관리자 Finance API/UI
- 입고 매입단가와 정비사 원가 관리 UI

### Phase 4C — Final Validation / Production Readiness / Documentation 완료

- 과거 UNKNOWN 부품·인건비 원가의 관리자 수동 확정 및 append-only 정정 이력
- 자동 원가와 수동 확정 원가를 구분하는 Finance API/UI
- 정비사별 월급·기준시간과 완료 WorkOrder 인건비 snapshot 연계
- 달력 일수 안분 기간 급여, 배부 차이, 급여 비율 및 활용률 분석
- append-only 운영비·기타 손익 전표와 reversal, 관리 손익·KPI
- PostgreSQL 17 통합 전체 테스트 (`compose.test.yaml`)
- Backend / Frontend 최종 회귀 검증
- ADMIN / MECHANIC / CUSTOMER 권한 및 finance 원가 비노출 검토
- 로컬 브라우저 finance 입력·조회 및 청구 불변성 확인
- production readiness 경계와 미검증 항목 문서화
- 검증 결과는 `docs/VALIDATION.md`에 기록

Phase 4C는 2026-09-21 기준 완료했다.

최종 검증에서 PostgreSQL 17 전체 테스트 119/119, frontend typecheck 및 production build 21/21, 로컬 브라우저 E2E, production Vercel/Render/Neon smoke, ADMIN/MECHANIC/CUSTOMER 권한 분리를 확인했다.

법정 재무제표가 아닌 관리 손익·원가 분석이라는 제품 경계와 현재 급여 이력 모델의 한계는 유지하며, 실제 PG 결제 연동은 Phase 5에서 진행한다.

---

## Phase 5 — Payments & Treasury

### 5A — Treasury Core & Rebalancing 진행 중

- Phase 5 시작 시점 현재 회사자산 1,000,000,000원 opening baseline
- OPERATING / DEPOSIT / INVESTMENT 현재 잔액과 목표 40/30/30 비중
- append-only Treasury 원장과 관리자 명시적 재조정
- deterministic row lock, 총자산 보존, Idempotency-Key 및 rollback 검증
- 관리자 Finance 화면 Treasury summary card
- 로컬 Backend 125/125 및 Frontend typecheck/build 통과
- PostgreSQL 17 전체 검증 대기; 통과 전까지 완료로 기록하지 않음

### 5B — Daily Deposit / Investment Simulation

- 은행예치 일복리와 투자자산 일별 수익률 확정
- 날짜별 중복 방지, catch-up, 일별 원장

### 5C — Toss Payments Test Integration

- Toss Payments test 결제 승인·취소·환불
- server-side confirm, webhook deduplication, idempotency, reconciliation

### 5D — Business Cashflow Integration

- 고객 결제·환불과 실제 운영비·급여 지급을 OPERATING에 연결
- Phase 4 관리 손익과 실제 cash movement의 중복 반영 방지

### 5E — Final Finance Dashboard / Validation

- Finance와 Treasury 통합 표시 및 최종 재무 경계 검증

---

## Phase 6 — Hardening & Finalization

새로운 핵심 비즈니스 기능을 추가하지 않고 전체 안정화와 마무리를 수행한다.

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
- 모바일 반응형 보완
- 코드 정리
- 최종 문서
- 포트폴리오 자료 정리

---

## 현재 이후 구현 순서

Phase 5A → Phase 5B → Phase 5C → Phase 5D → Phase 5E
→ Phase 6
