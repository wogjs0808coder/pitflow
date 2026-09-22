# PitFlow

자동차 정비 예약부터 입고, 정비 작업, 부품 재고, 정산, 출고까지 연결하는 정비소 운영 관리 시스템입니다.

단순한 예약 서비스가 아니라 실제 정비소에서 예약 이후 발생하는 업무까지 하나의 흐름으로 관리하는 것을 목표로 개발했습니다.

고객은 차량을 등록하고 정비를 예약할 수 있으며, 정비사는 배정된 작업을 처리합니다. 관리자는 예약 확인, 차량 입고, 정비사 배정, 부품 및 재고 관리, 정산, 수납, 재무 현황과 출고까지 관리할 수 있습니다.

예약, 작업, 재고, 정산을 각각 독립된 기능으로 만들기보다 한 업무의 변화가 다음 단계의 데이터와 자연스럽게 연결되도록 구현하는 데 중점을 두었습니다.

---

## 서비스

Frontend

https://pitflow-orcin.vercel.app/

Backend

https://pitflow-api.onrender.com

Database

Neon PostgreSQL

Render 무료 환경을 사용하고 있어 오랫동안 요청이 없었던 경우 첫 접속에 시간이 걸릴 수 있습니다.

---

## 주요 기능

### 고객

- 회원가입 및 로그인
- 차량 등록 및 관리
- 정비 서비스 조회
- 예상 정비 금액 확인
- 정비 예약 및 취소
- 본인 차량의 정비 이력 확인
- 발행된 정산 명세 확인
- Toss Payments 테스트 환경을 이용한 카드 결제

### 예약 및 입고

- 30분 단위 예약
- 동일 차량 중복 예약 방지
- 작업 공간별 중복 예약 방지
- 휴무일 및 특별 영업일 관리
- 예약일 이전 조기 입고
- 입고 주행거리 기록
- 예약 시점의 서비스명, 공임, 소요시간 snapshot 보존

### 정비 작업

- 입고 차량의 WorkOrder 생성
- 정비사 배정, 재배정 및 배정 해제
- 정비 항목별 상태 관리
- 작업 시작, 부품 대기, 완료 및 작업 제외 처리
- 부품 부족 신고
- 실제 사용 부품 및 반환 부품 기록
- 정비 완료
- 차량 출고 관리

WorkOrder 전체 상태와 개별 정비 항목 상태를 분리해 관리합니다.

개별 정비 항목은 다음 상태를 가질 수 있습니다.

- PENDING
- IN_PROGRESS
- WAITING_PARTS
- COMPLETED
- SKIPPED

이를 통해 일부 정비 항목이 부품을 기다리는 동안 다른 작업을 계속 진행할 수 있도록 구성했습니다.

### 정비사

- 관리자에 의한 정비사 등록
- 사용자 계정과 정비사 프로필 연결
- 본인에게 배정된 작업만 접근
- 작업 상태 변경
- 정비 항목 처리
- 실제 부품 사용 및 반환
- 부품 부족 신고
- 작업 완료

사용자 계정과 정비사 ID는 별도로 관리하며, 서버에서 현재 로그인한 사용자의 작업 소유권을 확인합니다.

### 부품 및 재고

- 부품 등록 및 수정
- 부품 설명 관리
- 비활성화, 삭제 및 복원
- 부품 입고
- 재고 실사 보정
- 정비 작업 부품 사용
- 사용 부품 반환
- 음수 재고 방지
- 수량 단위 검증
- 재고 이동 이력 보존
- 입고 lot 단위 실제 매입원가 기록
- FIFO 방식 원가 계산
- 원가가 없는 기존 재고의 UNKNOWN 상태 관리
- 기존 UNKNOWN 재고의 원가 확정

재고는 실제 물리적인 이동이 발생했을 때만 변경합니다.

부품 부족 신고나 작업 취소가 발생했다는 이유만으로 재고를 자동으로 증가시키거나 감소시키지 않습니다.

### 부품 부족 신고

정비사는 담당 작업에 필요한 부품이 부족하면 관리자에게 신고할 수 있습니다.

신고에는 다음 정보를 보존합니다.

- 작업
- 정비 항목
- 부품
- 필요한 수량
- 신고 당시 재고
- 신고자
- 사유
- 신고 시각
- 해결 처리자
- 해결 시각

동일한 작업, 정비 항목, 부품 조합에는 동시에 하나의 OPEN 신고만 존재할 수 있습니다.

부족 신고와 해결 처리는 실제 재고 수량을 자동으로 변경하지 않습니다.

### 정산 및 수납

- 완료된 정비 작업 기준 정산
- 작업 당시 공임 snapshot 사용
- 실제 부품 순사용량 기준 계산
- USE - RETURN 반영
- Invoice 발행
- Invoice snapshot 보존
- 현장 수납
- 수납 취소 및 reversal 기록
- Toss Payments 테스트 결제
- Toss 결제 승인 및 전액 환불
- 중복 승인 및 중복 환불 방지
- Idempotency-Key 기반 중복 요청 방지

결제 금액은 브라우저가 전달한 값을 그대로 신뢰하지 않고 서버에서 Invoice 기준으로 다시 검증합니다.

### 재무 및 원가

정비소의 매출뿐 아니라 실제 운영 중 발생하는 자산 변화를 확인할 수 있도록 구성했습니다.

현재 관리하는 자산은 다음과 같습니다.

- 운영자금
- 은행예치
- 투자자산
- 재고자산
- 미수채권

고객이 아직 결제하지 않은 금액은 미수채권으로 관리하고, 고객이 결제하면 미수채권이 감소하면서 운영자금이 증가합니다.

재고를 유상 구매하면 운영자금은 감소하고 재고자산은 증가합니다.

기존 재고의 원가를 나중에 확인해 확정하는 경우에는 실제 현금 지출이 새로 발생한 것이 아니므로 Treasury에는 영향을 주지 않습니다.

또한 다음 기능을 제공합니다.

- 실제 부품 매입원가와 고객 판매가 분리
- FIFO 기반 부품 원가 계산
- 정비사 인건비 snapshot
- 작업별 기여이익
- 기간 급여 분석
- 운영비 및 기타 손익 전표
- 전표 역분개
- 실제 급여 지급
- 금융자산 40/30/30 목표 비중 관리
- 운영자금, 예금, 투자자산 간 재조정
- 예금 일복리 정산
- 투자자산 일일 수익률 반영
- 모든 Treasury 변경의 append-only 원장 기록

이 기능은 법정 재무제표를 대체하기 위한 것이 아니라 정비소 운영 상황을 확인하기 위한 관리 기능입니다.

---

## 전체 업무 흐름

```text
고객 회원가입
        ↓
차량 등록
        ↓
정비 서비스 및 견적 확인
        ↓
예약
        ↓
관리자 예약 확인
        ↓
차량 입고
        ↓
정비사 배정
        ↓
정비 작업
        ↓
부품 사용 / 반환
        ↓
정비 완료
        ↓
정산 명세 발행
        ↓
고객 결제 / 현장 수납
        ↓
차량 출고
        ↓
고객 정비 이력
```

고객은 완료 작업 카드에서 미수 명세를 바로 결제할 수 있고, 관리자는 WorkOrder에서 명세 발행·결제·출고 순서를 이어서 처리합니다. 정비사는 정비 완료까지만 담당합니다.

---

## 기술 구성

| 구분 | 기술 |
| --- | --- |
| Frontend | Next.js 16.3.4, React, TypeScript |
| Backend | Java 17, Spring Boot 3.5.16 |
| Database | PostgreSQL 17 |
| Security | Spring Security, Session Cookie, CSRF |
| Migration | Flyway |
| Data Access | Spring Data JPA, JdbcTemplate |
| Payment | Toss Payments Test API |
| Test | JUnit, MockMvc, PostgreSQL Integration Test |
| CI | GitHub Actions |
| Frontend Deployment | Vercel |
| Backend Deployment | Render |
| Production Database | Neon PostgreSQL |
| Local Environment | Windows 11, Docker Desktop, Docker Compose |

브라우저는 Next.js의 `/api/*` 경로를 통해 Spring Boot API에 접근합니다.

비밀번호나 인증 토큰은 브라우저 localStorage에 저장하지 않습니다.

---

## 프로젝트 구조

```text
pitflow
├─ backend
│  └─ src/main/java/com/pitflow
│     ├─ auth
│     ├─ appointment
│     ├─ catalog
│     ├─ mechanic
│     ├─ notification
│     ├─ work
│     ├─ billing
│     └─ finance
│
├─ frontend
│  └─ src
│     ├─ app
│     ├─ components
│     └─ lib
│
├─ docs
└─ compose.yaml
```

Backend는 인증, 예약, 정비 작업, 정산, 재무처럼 업무 영역을 기준으로 나누었습니다.

Frontend는 Next.js App Router를 사용합니다.

---

## 구현하면서 중요하게 본 부분

### 과거 기록이 현재 데이터 변경의 영향을 받지 않도록 했습니다

정비 서비스의 이름이나 가격이 변경되더라도 과거 예약과 정산 금액이 함께 바뀌면 안 된다고 판단했습니다.

예약, 작업, 정산 시점에 필요한 값을 snapshot으로 저장해 과거 기록을 보존하도록 구현했습니다.

### 재고는 실제 움직임만 기록합니다

부품 부족 신고가 발생했다고 재고를 자동으로 차감하거나, 작업이 취소되었다고 임의로 재고를 복구하지 않습니다.

실제 입고, 사용, 반환, 실사 보정이 발생했을 때만 재고가 변경됩니다.

### 판매가와 실제 원가를 분리했습니다

고객에게 판매하는 가격과 정비소가 실제로 부품을 구매한 가격은 서로 다릅니다.

입고 lot 단위로 매입원가를 기록하고 FIFO 방식으로 실제 사용 원가를 계산합니다.

이를 통해 각 정비 작업의 실제 원가와 기여이익을 확인할 수 있도록 했습니다.

### 알 수 없는 원가를 임의로 0원 처리하지 않았습니다

기존 재고 중에는 수량은 존재하지만 실제 취득원가를 확인할 수 없는 데이터가 있었습니다.

이 경우 재고자산을 0원으로 계산하면 실제 자산을 과소평가하게 됩니다.

그래서 원가를 확인할 수 없는 재고는 UNKNOWN으로 관리하고, 관리자가 실제 근거를 확인한 뒤 원가를 확정할 수 있도록 구성했습니다.

기존 재고의 원가를 확정하는 작업은 새로운 구매가 아니므로 운영자금을 다시 차감하지 않습니다.

### 중요한 요청은 중복 실행되지 않도록 했습니다

수납, 재고 변경, 결제, 환불처럼 금액과 수량에 영향을 주는 요청은 중복 실행되면 데이터가 크게 틀어질 수 있습니다.

Idempotency-Key와 데이터베이스 제약, 서버 측 검증을 이용해 동일 요청이 여러 번 들어와도 실제 업무 처리는 한 번만 수행되도록 했습니다.

### 서버를 데이터의 기준으로 사용했습니다

금액, 재고 수량, 사용자 권한처럼 중요한 데이터는 브라우저에서 전달받은 값을 그대로 신뢰하지 않습니다.

최종 계산과 검증은 Backend와 Database에서 수행합니다.

---

## Toss Payments 연동

Toss Payments 테스트 환경을 이용해 실제 결제 흐름을 구현했습니다.

현재 지원하는 흐름은 다음과 같습니다.

```text
PitFlow Invoice
        ↓
결제 주문 생성
        ↓
Toss 결제창
        ↓
결제 승인
        ↓
Backend confirm
        ↓
Payment Record 생성
        ↓
Treasury CUSTOMER_PAYMENT
        ↓
미수채권 감소 / 운영자금 증가
```

전액 환불 시에는 반대 흐름으로 처리합니다.

```text
Toss 결제 취소
        ↓
Payment Reversal
        ↓
Treasury PAYMENT_REFUND
        ↓
운영자금 감소 / 미수채권 복원
```

실제 테스트 결제와 환불까지 검증했으며, 동일 승인 요청이 반복되어도 Payment Record와 Treasury 원장이 중복 생성되지 않도록 처리했습니다. 전액 환불 뒤에는 취소된 주문을 재사용하지 않고 같은 명세에 새 결제 주문을 만들어 Toss 또는 현장 결제로 다시 수납할 수 있습니다.

실제 서비스용 결제 키는 사용하지 않으며 Toss Payments 테스트 환경만 사용합니다.

---

## 트러블슈팅

### 기존 재고 수량은 있는데 원가가 없는 문제

초기 테스트 데이터에는 실제 재고 수량이 존재했지만 매입원가 기록이 없는 부품이 있었습니다.

처음에는 이런 재고가 자산 화면에서 0원처럼 보이는 문제가 있었습니다.

판매단가를 매입원가로 대신 사용할 수도 있었지만 판매가와 취득원가는 의미가 다르기 때문에 사용하지 않았습니다.

대신 원가를 확인할 수 없는 수량을 UNKNOWN으로 분리하고, 실제 원가를 확인한 뒤 부분 또는 전체 수량에 대해 원가를 확정할 수 있도록 변경했습니다.

이 과정에서 기존 재고의 원가 확정은 과거 자산의 가치를 확인하는 작업이므로 운영자금을 다시 차감하지 않도록 분리했습니다.

### 결제 승인과 내부 DB 기록의 중복 문제

PG 결제는 외부 서비스와 내부 Database가 동시에 사용되기 때문에 네트워크 오류가 발생하면 승인 결과를 다시 요청해야 하는 상황이 생길 수 있습니다.

이때 동일 결제가 두 번 기록되면 실제 수납 금액과 Treasury 자산이 모두 틀어집니다.

Provider 주문 ID, Payment Record, Idempotency 처리와 데이터베이스 제약을 함께 사용해 동일 결제 요청이 반복되더라도 내부 결제와 Treasury 원장이 한 번만 생성되도록 구성했습니다.

결제 성공 페이지를 새로고침하는 상황도 실제 브라우저에서 확인했습니다.

### Toss Payments 클라이언트 키 종류 문제

Toss Payments 테스트 결제창을 처음 연결했을 때 결제 준비 API는 정상적으로 동작했지만 결제창이 열리지 않았습니다.

브라우저에서는 모든 SDK 오류가 일반적인 서버 연결 오류 메시지로 표시되고 있어 원인을 바로 확인하기 어려웠습니다.

실제 SDK 오류 코드를 확인한 결과 `NOT_SUPPORTED_WIDGET_KEY`가 반환되고 있었고, 현재 구현 방식에서 요구하는 API 개별 연동 키가 아닌 다른 유형의 클라이언트 키를 사용한 것이 원인이었습니다.

현재 결제 방식에 맞는 테스트 키 세트로 변경한 뒤 결제 승인과 전액 환불까지 정상 동작하는 것을 확인했습니다.

### Docker와 PostgreSQL 개발 환경 분리

로컬 개발 과정에서 PostgreSQL의 기본 5432 포트가 다른 환경과 충돌하는 문제가 있었습니다.

개발용 Docker Compose에서는 별도 호스트 포트를 사용하고, 컨테이너 내부 통신은 PostgreSQL 기본 포트를 유지하도록 구성했습니다.

운영 환경에서는 Database 포트를 외부에 직접 노출하지 않습니다.

이를 통해 로컬 Spring Boot 실행, Docker PostgreSQL, 전체 Compose 실행을 분리해 사용할 수 있도록 했습니다.

### 손익과 실제 현금을 함께 계산하면서 발생하는 중복 문제

기간 급여나 감가상각 같은 손익 정보와 실제 현금 지급을 동일하게 처리하면 비용이 두 번 차감되는 문제가 발생할 수 있습니다.

그래서 관리 손익과 Treasury 현금 흐름을 분리했습니다.

예를 들어 실제 급여 지급은 운영자금을 감소시키지만 기간 급여 계산을 다시 비용 처리하지 않습니다.

감가상각은 손익에는 반영하지만 실제 현금 이동이 아니므로 Treasury에는 반영하지 않습니다.

---

## 보안 및 데이터 원칙

- 인증은 서버 세션 기반
- Session Cookie는 HttpOnly 사용
- 변경 요청은 CSRF 보호
- ADMIN 권한은 서버에서 검증
- MECHANIC은 본인에게 배정된 작업만 변경 가능
- CUSTOMER는 본인 데이터만 접근 가능
- 요청 JSON의 role이나 ownerId를 신뢰하지 않음
- 비밀번호와 API Key를 Git에 저장하지 않음
- Toss Secret Key는 Backend에서만 사용
- 적용된 Flyway migration은 수정하지 않음
- 스키마 변경은 새로운 migration으로 추가
- 서버가 금액 계산의 source of truth
- 과거 snapshot은 현재 catalog 변경으로 다시 계산하지 않음
- 재고는 음수가 될 수 없음
- 재고 이동 이력은 실제 물리적 움직임만 기록
- 주요 mutation은 idempotent하게 처리

---

## 테스트 및 검증

Backend 전체 통합 테스트를 H2와 PostgreSQL 17 환경에서 각각 실행합니다.

현재 Phase 5C 기준:

| 검사 | 결과 |
| --- | --- |
| Backend H2 전체 테스트 | 148 / 148 PASS |
| PostgreSQL 17 전체 테스트 | 142 / 142 PASS (Phase 5C provider E2E 기준) |
| Frontend TypeScript typecheck | PASS |
| Frontend production build | PASS |
| Flyway V1 → V24 migration | PASS |
| Toss 테스트 결제 승인 | PASS |
| Toss 테스트 결제 환불 | PASS |
| Toss 결제 중복 처리 방지 | PASS |
| Treasury 결제/환불 연동 | PASS |
| 일일 예금 이자 정산 | PASS |
| 일일 투자자산 정산 | PASS |
| `git diff --check` | PASS |

실제 브라우저에서도 다음 흐름을 확인했습니다.

```text
정비 완료
→ Invoice 발행
→ Toss 테스트 결제
→ Payment Record
→ CUSTOMER_PAYMENT
→ 미수채권 감소
→ 운영자금 증가
→ Toss 전액 환불
→ Payment Reversal
→ PAYMENT_REFUND
→ 미수채권 복원
```

---

## 로컬 실행

### 필요 환경

- Java 17
- Node.js 22 이상
- Docker Desktop

### 환경변수

`.env.example`을 복사해 `.env`를 생성합니다.

```bash
cp .env.example .env
```

Windows에서는 직접 `.env` 파일을 생성해도 됩니다.

실제 비밀번호와 API Key가 포함된 `.env` 파일은 Git에 포함하지 않습니다.

### Docker Compose 실행

```bash
docker compose up -d --build
```

브라우저:

```text
http://localhost:3000
```

종료:

```bash
docker compose down
```

DB volume은 유지됩니다.

---

## 개별 개발 실행

### PostgreSQL

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"

docker compose -f compose.yaml -f compose.dev.yaml up -d db
```

### Spring Boot

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"

powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1
```

기본 API:

```text
http://127.0.0.1:8081
```

### Next.js

```powershell
cd "$env:USERPROFILE\Desktop\pitflow\frontend"

npm ci
npm run dev
```

기본 화면:

```text
http://localhost:3000
```

---

## 테스트

### Backend

```powershell
cd "$env:USERPROFILE\Desktop\pitflow\backend"

.\mvnw.cmd verify
```

### Frontend

```powershell
cd "$env:USERPROFILE\Desktop\pitflow\frontend"

npm run typecheck
npm run build
```

### PostgreSQL 17 통합 테스트

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"

docker compose -f compose.test.yaml up --build `
  --abort-on-container-exit `
  --exit-code-from backend-tests `
  backend-tests
```

테스트 종료:

```powershell
docker compose -f compose.test.yaml down
```

운영 Neon Database를 테스트 Database로 사용하지 않습니다.

---

## Database Migration

Database 변경은 Flyway로 관리합니다.

현재 최신 migration은 V24입니다.

주요 migration:

- V17: 재고 원가 lot 및 allocation
- V18: 정비사 시간당 원가와 WorkOrder 인건비 snapshot
- V19: 미확정 원가 수동 확정
- V20: 급여 기준 및 운영 손익 전표
- V21: Treasury 계정 및 원장
- V22: 회사자산, 일일 예금·투자 정산 및 현금 흐름 연동
- V23: Toss Payments 및 기존 UNKNOWN 재고 원가 확정
- V24: 환불 후 같은 명세의 신규 Toss 결제 주문 허용

이미 적용된 migration은 다시 수정하지 않습니다.

새로운 schema 변경은 항상 다음 migration으로 추가합니다.

---

## 현재 개발 상태

예약, 입고, 정비 작업, 부품 재고, 정산, 결제, 재무 관리까지 주요 업무 흐름을 구현했습니다.

결제와 출고 과정의 불필요한 화면 이동을 줄이고 Customer, Mechanic, Admin의 역할을 정비 완료 → 명세 발행 → 결제 → 출고 순서로 구분했습니다.

이 작업이 끝나면 전체 서비스의 권한, 동시성, 배포 환경, 반응형 화면, 운영 안정성과 코드 구조를 다시 검증하는 Hardening 작업을 진행할 예정입니다.

---

## 개발 방향

PitFlow은 기능의 개수를 늘리는 것보다 실제 업무 흐름과 데이터의 일관성을 중요하게 생각하며 개발하고 있습니다.

예약 이후의 정비 작업, 부품 사용, 정산, 결제와 자산 변화가 서로 분리되지 않고 하나의 흐름으로 이어지는 시스템을 만드는 것이 목표입니다.
