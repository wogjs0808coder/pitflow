# PitFlow Workflow

현재 Phase 1과 Phase 2가 완료된 기준의 실제 업무 흐름이다.

## 전체 흐름

고객 회원가입
→ 차량 등록
→ 서비스 견적
→ 예약
→ 관리자 예약 확인
→ 방문 처리
→ 입고
→ 정비사 배정
→ 정비 진행
→ 부품 USE / RETURN
→ 작업 완료
→ 정산
→ 수납
→ 출고
→ 고객 정비 이력

## 예약

고객이 차량과 정비 서비스를 선택한다.

가격과 수량은 서버가 계산하며 예약 시점 snapshot으로 보존한다.

예약 시간 충돌은 DB가 최종 판단한다.

## 입고

VISITED 예약에서 WorkOrder를 생성한다.

입고 시:

- 실제 주행거리 기록
- 차량 주행거리 갱신
- 예약 서비스 snapshot을 작업 항목으로 생성
- 필요하면 정비사 배정

## 정비사

ADMIN이 작업 담당자를 배정한다.

MECHANIC은 본인에게 배정된 작업만 조회하고 수정할 수 있다.

다른 정비사의 작업이나 미배정 작업은 접근할 수 없다.

## 작업 상태

현재 WorkOrder 상태:

- RECEIVED
- IN_PROGRESS
- WAITING_PARTS
- COMPLETED
- CANCELLED

현재 WAITING_PARTS는 작업 전체 상태다.

항목별 WAITING_PARTS는 Phase 3에서 추가한다.

## 부품

실제로 사용한 부품만 USE한다.

재고 부족 시 음수 재고를 허용하지 않는다.

여러 부품을 한 번에 사용하면 전부 성공하거나 전부 실패한다.

서비스의 필요 부품 정보는 준비용 정보이며 실제 사용량을 자동 차감하지 않는다.

## 반환

실제로 회수한 부품만 RETURN한다.

작업이 CANCELLED됐다고 기존 USE를 자동으로 재고에 복구하지 않는다.

RETURN 누계는 원래 USE 수량을 초과할 수 없다.

## 작업 완료

현재 완료 조건을 만족하면 WorkOrder를 COMPLETED로 변경한다.

MECHANIC은 작업 완료까지 가능하다.

차량 RELEASE는 ADMIN만 가능하다.

## 정산

공임은 작업 snapshot을 사용한다.

부품 비용은 실제 순사용량을 사용한다.

USE - RETURN

invoice를 발행한 뒤에는 현재 catalog 가격이 변경돼도 과거 invoice를 다시 계산하지 않는다.

## 수납

현재 payment는 외부 PG가 아니다.

현장에서 확인된 수납 사실을 시스템에 기록한다.

취소할 경우 기존 기록을 삭제하지 않고 reversal 이력을 남긴다.

## 출고

정비 완료 후 ADMIN이 실제 차량 인도를 확인하고 출고 처리한다.

출고는 정비 완료와 별도 기록으로 관리한다.

## Phase 3에서 변경될 부분

- 알림
- 재고 부족 보고
- WorkOrderItem status
- 부분 작업
- WAITING_PARTS item
- SKIPPED item