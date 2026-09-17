# PitFlow

자동차 정비 예약부터 입고, 정비 작업, 부품 재고, 정산, 출고까지 연결하는 정비소 운영 관리 시스템입니다.

Spring Boot + PostgreSQL 백엔드와 Next.js 프론트엔드로 구성되어 있으며, 실제 정비소 업무 흐름을 기준으로 단계적으로 기능을 확장하고 있습니다.

현재 Phase 3C까지 구현 및 검증이 완료되었습니다.

현재 구현 단계:

- Phase 1 — Catalog / Estimate / Snapshot 완료
- Phase 2 — Mechanic Workflow 완료
- Phase 2 Hotfix — Mechanic Suggested Parts 완료
- Phase 3A — Notification Foundation 완료
- Phase 3B — Assignment / Completion 완료
- Phase 3C — Part Shortage 완료
- Phase 3D — Item-level Workflow 완료

자세한 개발 순서는 `docs/ROADMAP.md`를 기준으로 합니다.

## 주요 기능

### 고객

- 회원가입
- 세션 로그인 및 로그아웃
- 차량 등록·조회·수정·삭제
- 정비 서비스 조회
- 서비스 견적 확인
- 예약 생성·조회·취소
- 본인 정비 이력 확인

### 예약

- 30분 단위 예약
- 작업 공간별 예약 관리
- 동일 작업 공간 중복 예약 방지
- 동일 차량 중복 예약 방지
- 예약 시점 서비스명·공임·소요시간 snapshot 보존
- 관리자 예약 확정
- 방문 처리
- 예약일 이전 조기 방문 처리
- 휴무일 및 특별 영업일 관리

### 정비 작업

- 방문 완료 예약에서 WorkOrder 생성
- 입고 주행거리 기록
- 미배정 입고
- 정비사 즉시 배정
- 담당 정비사 재배정 및 배정 해제
- 작업 상태 관리
- 정비 항목 완료 처리
- 작업 이력 기록
- 정비 완료
- 차량 출고 처리

현재 WorkOrder 상태:

- RECEIVED
- IN_PROGRESS
- WAITING_PARTS
- COMPLETED
- CANCELLED

Phase 3D에서는 WorkOrderItem 단위 상태 관리를 추가했습니다. 각 항목은 PENDING, IN_PROGRESS, WAITING_PARTS, COMPLETED, SKIPPED 상태를 독립적으로 가지며, 전체 WorkOrder 상태와 분리해 관리합니다.

### 정비사

- 관리자 정비사 등록
- 정비사 로그인 계정 연결
- 정비사 활성·비활성 관리
- 본인에게 배정된 작업만 접근
- 담당 작업 상태 변경
- 정비 항목 처리
- 실제 부품 USE / RETURN
- 부품 부족 신고
- 작업 완료

사용자 계정 ID와 정비사 ID는 별도로 관리합니다.

서버에서 현재 로그인한 사용자의 정비사 프로필과 작업 소유권을 검증합니다.

### 부품 및 재고

- 부품 등록·수정
- 부품 설명 관리
- 활성·비활성
- 삭제 archive 및 복원
- 부품 입고
- 재고 실사 보정
- 정비 작업 부품 사용
- 사용 부품 반환
- 음수 재고 방지
- 수량 단위 검증
- 재고 이동 이력 보존
- 과거 사용 당시 부품명·단위·가격 snapshot 보존

재고 변경은 실제 물리적 움직임을 기준으로 합니다.

작업 취소나 부품 부족 신고만으로 재고를 자동 증가·감소시키지 않습니다.

### 부품 부족 신고

정비사는 담당 작업에서 필요한 부품이 부족하면 관리자에게 신고할 수 있습니다.

신고에는 다음 정보를 기록합니다.

- 작업
- 정비 항목
- 부품
- 필요한 수량
- 신고 당시 재고
- 신고자
- 신고 사유
- 접수 시각
- 해결 처리자
- 해결 시각

동일한 작업 + 정비 항목 + 부품 조합에는 동시에 하나의 OPEN 신고만 존재할 수 있습니다.

관리자가 신고를 해결한 뒤에는 동일한 조합으로 다시 신고할 수 있습니다.

부족 신고와 해결 처리는 실제 재고를 자동 변경하지 않습니다.

### 알림

DB 기반 알림 기능을 제공합니다.

현재 알림 유형:

- WORK_ASSIGNED
- WORK_COMPLETED
- PART_SHORTAGE

현재 동작:

- 정비사 배정 시 해당 정비사에게 알림
- 다른 정비사로 재배정 시 새 담당 정비사에게 알림
- 정비사가 작업 완료 시 관리자에게 알림
- 정비사가 부품 부족 신고 시 관리자에게 알림
- 읽지 않은 알림 수 표시
- 알림 목록 조회
- 읽음 처리

현재 알림은 HTTP polling 기반입니다.

WebSocket, SMS, 이메일, 카카오톡 알림은 현재 범위에 포함하지 않습니다.

### 정산 및 수납

- 완료된 WorkOrder 기준 정산 미리보기
- 공임 snapshot 사용
- 실제 부품 순사용량 기준 계산
- USE - RETURN 반영
- invoice 발행
- invoice snapshot 보존
- 현장 수납 기록
- 수납 취소 reversal 기록
- invoice 취소
- invoice 재발행
- Idempotency-Key 기반 중복 요청 방지

현재 수납 기능은 실제 PG 결제가 아니라 현장에서 확인한 수납 사실을 기록하는 기능입니다.

실제 PG 결제는 이후 Phase에서 구현할 예정입니다.

## 업무 흐름

현재 기본 업무 흐름:

고객 회원가입
→ 차량 등록
→ 서비스 견적
→ 예약
→ 관리자 예약 확인
→ 방문 처리
→ 입고
→ 정비사 배정
→ 정비 진행
→ 필요 시 부품 부족 신고
→ 실제 부품 USE / RETURN
→ 작업 완료
→ 정산
→ 수납
→ 차량 출고
→ 고객 정비 이력

## 기술 구성

| 구분 | 구성 |
| --- | --- |
| Frontend | Next.js 16.3.4, React 19.3.0, TypeScript |
| Backend | Java 17, Spring Boot 3.5.16 |
| Security | Spring Security, HttpOnly Session Cookie, CSRF |
| Database | PostgreSQL 17 |
| Migration | Flyway |
| Data Access | Spring Data JPA, JdbcTemplate |
| Test | JUnit, MockMvc, PostgreSQL Integration Test |
| CI | GitHub Actions |
| Frontend Deployment | Vercel |
| Backend Deployment | Render |
| Production Database | Neon PostgreSQL |
| Local Environment | Windows 11, Docker Desktop, Docker Compose |

브라우저는 Next.js의 `/api/*` 경로를 통해 Spring Boot API에 접근합니다.

비밀번호나 인증 토큰은 브라우저 localStorage에 저장하지 않습니다.

## Production

Frontend:

https://pitflow-orcin.vercel.app/

Backend:

https://pitflow-api.onrender.com

Database:

Neon PostgreSQL

Render 무료 환경에서는 서버가 유휴 상태에서 다시 실행될 때 첫 요청이 지연될 수 있습니다.

## 보안 및 데이터 원칙

- 인증은 서버 세션 기반
- 세션 쿠키는 HttpOnly
- 변경 요청은 CSRF 보호
- ADMIN 권한은 서버에서 검증
- MECHANIC은 본인에게 배정된 작업만 변경 가능
- CUSTOMER는 본인 데이터만 접근 가능
- 요청 JSON의 role이나 ownerId를 신뢰하지 않음
- 비밀번호·API Key·DB 비밀번호를 Git에 저장하지 않음
- 적용된 Flyway migration은 수정하지 않음
- 스키마 변경은 새로운 migration으로 추가
- 서버가 금액 계산의 source of truth
- 과거 snapshot은 현재 catalog 변경으로 다시 계산하지 않음
- 재고는 음수가 될 수 없음
- 재고 이동 이력은 실제 물리적 움직임을 기록
- 주요 mutation은 Idempotency-Key 사용

## Windows / Docker Desktop 실행

프로젝트 경로:

    C:\Users\<사용자>\Desktop\pitflow

초기 설정:

    cd "$env:USERPROFILE\Desktop\pitflow"
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup.ps1

전체 실행:

    docker compose up -d --build

상태 확인:

    docker compose ps

Backend 로그 확인:

    docker compose logs -f backend

다음 로그가 나타나면 Backend가 정상 실행된 상태입니다.

    Started PitflowApplication

브라우저:

    http://localhost:3000

호스트 포트:

| 서비스 | 주소 |
| --- | --- |
| Frontend | `127.0.0.1:3000` |
| Backend | `127.0.0.1:8081` |
| PostgreSQL | 외부 미공개 |

컨테이너 내부:

| 서비스 | 주소 |
| --- | --- |
| Backend | `http://backend:8080` |
| PostgreSQL | `jdbc:postgresql://db:5432/pitflow` |

종료:

    docker compose down

DB volume은 유지됩니다.

## 관리자 계정

최초 관리자 계정은 `.env`의 다음 환경변수로 생성합니다.

    PITFLOW_ADMIN_EMAIL
    PITFLOW_ADMIN_PASSWORD

관리자 환경변수는 최초 생성용입니다.

이미 생성된 관리자 계정의 비밀번호는 `.env` 값을 바꾸는 것만으로 변경되지 않습니다.

`.env` 파일은 Git에 포함하지 않습니다.

## 개발 중 개별 실행

필수 환경:

- JDK 17
- Node.js 22 이상
- Docker Desktop

### PostgreSQL

    cd "$env:USERPROFILE\Desktop\pitflow"

    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup.ps1

    docker compose down

    docker compose -f compose.yaml -f compose.dev.yaml up -d db

개별 개발 실행에서는 PostgreSQL을 호스트 5433 포트로 사용할 수 있습니다.

### Spring Boot

별도 PowerShell:

    cd "$env:USERPROFILE\Desktop\pitflow"

    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1

기본 API:

    http://127.0.0.1:8081

### Next.js

별도 PowerShell:

    cd "$env:USERPROFILE\Desktop\pitflow\frontend"

    npm ci
    npm run dev

기본 화면:

    http://localhost:3000

전체 Docker 실행과 개별 개발 실행을 같은 포트에서 동시에 실행하지 마세요.

## Linux / macOS

    bash scripts/setup.sh
    docker compose up --build -d

`setup.sh`는 Python 3을 사용합니다.

## 검사

### Backend

    cd "$env:USERPROFILE\Desktop\pitflow\backend"

    .\mvnw.cmd test

### Frontend

    cd "$env:USERPROFILE\Desktop\pitflow\frontend"

    npm ci
    npm run build
    npm run typecheck

### PostgreSQL Docker 통합 테스트

    cd "$env:USERPROFILE\Desktop\pitflow"

    docker compose -f compose.test.yaml up --build --abort-on-container-exit --exit-code-from backend-tests backend-tests

종료:

    docker compose -f compose.test.yaml down

운영 Neon DB를 테스트 DB로 사용하지 마세요.

## 현재 검증 상태

Phase 3D 완료 시점 기준:

| 검사 | 결과 |
| --- | --- |
| PostgreSQL 17 Flyway V1 → V16 | PASS |
| Backend 전체 테스트 | PASS |
| Frontend production build | PASS |
| TypeScript typecheck | PASS |
| GitHub Actions CI | PASS |
| Windows Docker Compose | PASS |
| Local browser E2E | PASS |
| Production Vercel / Render / Neon E2E | PASS |

Phase 3D Production E2E에서 확인한 흐름:

WorkOrder 작업 시작
→ WorkOrderItem PENDING → IN_PROGRESS
→ IN_PROGRESS → WAITING_PARTS
→ 항목 부품 대기 중에도 WorkOrder IN_PROGRESS 유지
→ 미완료 항목 존재 시 WorkOrder 완료 차단
→ SKIPPED 사유 필수 검증
→ WAITING_PARTS → SKIPPED
→ COMPLETED / SKIPPED 항목만 남은 경우 WorkOrder 완료
→ SKIPPED 공임 정산 제외
→ 고객 화면 읽기 전용 확인
→ 부품 부족 신고 후 항목 상태 유지
→ 부족 신고 해결 후 항목 상태 유지
→ 해결 처리자 / 처리 시각 보존

## 현재 DB migration

현재 최신 migration:

    V16__work_order_item_status.sql

Phase 3D에서 V16 migration으로 WorkOrderItem 상태와 건너뜀 사유를 추가했습니다.

기존 V1~V16 migration은 수정하지 않습니다.

## 프로젝트 구조

| 경로 | 역할 |
| --- | --- |
| `backend/src/main/java/com/pitflow/auth` | 인증·세션·보안 |
| `backend/src/main/java/com/pitflow/appointment` | 예약 |
| `backend/src/main/java/com/pitflow/catalog` | 정비 서비스 catalog |
| `backend/src/main/java/com/pitflow/mechanic` | 정비사 계정·프로필 |
| `backend/src/main/java/com/pitflow/notification` | 알림 |
| `backend/src/main/java/com/pitflow/work` | 작업지시서·부품·재고 |
| `backend/src/main/java/com/pitflow/billing` | 정산·수납 |
| `backend/src/main/resources/db/migration` | Flyway migration |
| `backend/src/test` | Backend 통합 테스트 |
| `frontend/src/app` | Next.js routes |
| `frontend/src/components` | 화면 및 공통 UI |
| `frontend/src/lib` | API·업무 타입·공통 함수 |
| `docs/ROADMAP.md` | 공식 개발 순서 |
| `docs/ARCHITECTURE.md` | 설계 |
| `docs/API.md` | API |
| `docs/WORKFLOW.md` | 업무 흐름 |
| `docs/VALIDATION.md` | 검증 기록 |
| `.github/workflows/ci.yml` | GitHub Actions |

## Roadmap

### 완료

- Phase 1 — Catalog / Estimate / Snapshot
- Phase 2 — Mechanic Workflow
- Phase 2 Hotfix — Mechanic Suggested Parts
- Phase 3A — Notification Foundation
- Phase 3B — Assignment / Completion
- Phase 3C — Part Shortage
- Phase 3D — Item-level Workflow

### 다음

Phase 3E — Admin Workspace

### 이후

- Phase 3E — Admin Workspace
- Phase 4 — Finance & Cost
- Phase 5 — Payments
- Phase 6 — Hardening / E2E / Production Readiness

세부 범위는 `docs/ROADMAP.md`를 기준으로 합니다.

## 프로젝트 원칙

PitFlow은 기능 수를 늘리는 것보다 데이터 일관성과 업무 이력 보존을 우선합니다.

- 서버가 금액의 source of truth
- 과거 snapshot은 immutable
- 재고는 음수가 될 수 없음
- 재고 원장은 실제 움직임을 기록
- 사용·반환을 자동 추정하지 않음
- 인증·권한은 서버에서 검증
- 중요한 변경 요청은 idempotent
- 기존 migration은 수정하지 않음