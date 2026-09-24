# PitFlow

자동차 정비 예약부터 차량 입고, 정비 작업, 부품 재고, 정산, 결제, 출고까지 하나의 흐름으로 연결한 **자동차 정비소 운영 관리 시스템**입니다.

고객·정비사·관리자가 각자의 역할에 맞는 기능을 사용하며, 단순 CRUD보다 실제 업무 흐름과 데이터 무결성을 중심으로 구현했습니다.

<!-- 대표 화면 이미지 추가 예정 -->

## Demo

- Frontend: https://pitflow-orcin.vercel.app/
- Portfolio: Notion 공개 후 연결 예정
- GitHub Release: `v1.0.0`
- Backend: https://pitflow-api.onrender.com
- Database: Neon PostgreSQL

Render 무료 환경을 사용하므로 장시간 미사용 후 첫 요청은 다소 느릴 수 있습니다.

Toss Payments는 실제 결제가 발생하지 않는 테스트 환경을 사용합니다.

---

## 주요 업무 흐름

```text
고객 예약
    ↓
관리자 차량 입고
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
결제
    ↓
차량 출고
    ↓
고객 정비 이력
```

<!-- 고객 → 관리자 → 정비사 → 결제 흐름 콜라주 추가 예정 -->

---

## 사용자

| 역할 | 주요 기능 |
| --- | --- |
| CUSTOMER | 차량 관리, 정비 예약, 정비 이력, 정산 명세 확인, Toss 테스트 결제 |
| MECHANIC | 배정 작업 확인, 정비 진행, 부품 사용·반환, 부족 신고, 작업 완료 |
| ADMIN | 예약·입고·배정, 재고·부족 해결, 정산·결제·환불, 출고, 계정·재무 관리 |

---

## 기술적 특징

### 과거 가격 보존

서비스 가격이 변경되어도 기존 예약과 정산 금액이 달라지지 않도록 예약·정비 당시의 이름과 가격을 Snapshot으로 저장합니다.

### 실제 재고와 FIFO 원가

필요한 부품이 아니라 실제 사용된 부품만 재고에서 차감하고, 서로 다른 가격으로 입고된 재고는 먼저 입고된 구매 원가부터 사용하는 FIFO 방식으로 계산합니다.

### 동시성과 데이터 무결성

예약·재고·결제처럼 동시 요청에 민감한 영역은 처리 직전 현재 DB 상태를 다시 확인하고, 필요한 영역에는 Database Lock과 중복 요청 방지를 적용했습니다.

### 결제 무결성

중복 결제·환불을 방지하고, 환불된 Toss 주문은 기록으로 보존하면서 새로운 주문번호로 재결제할 수 있도록 구성했습니다.

### 권한과 세션

Spring Security 기반 역할·소유권 검사를 적용하고, 계정 비활성화나 인증 정보 변경 시 기존 세션을 무효화합니다.

### 업무 알림

일반 처리 결과는 Toast로 안내하고, 정비사 배정·부품 부족·부족 해결처럼 다시 확인해야 하는 업무 사건은 사용자별 Notification으로 저장합니다.

상세한 설계 과정과 Troubleshooting은 Notion Portfolio에 정리합니다.

---

## Architecture

```text
Browser
   ↓
Next.js
   ↓
Spring Boot
   ↓
PostgreSQL

Production
Vercel → Render → Neon PostgreSQL
             ↓
       Toss Payments
```

---

## Tech Stack

| 구분 | 기술 |
| --- | --- |
| Frontend | Next.js 16, React, TypeScript |
| Backend | Java 17, Spring Boot 3.5, Spring Security |
| Database | PostgreSQL, Neon, Flyway |
| Payment | Toss Payments |
| CI | GitHub Actions |
| Deploy | Vercel, Render |
| Local | Docker Compose |

---

## Verification

- Backend Integration / Regression Test **171 PASS**
- Next.js Production Build PASS
- TypeScript Typecheck PASS
- Docker Compose + PostgreSQL 17 실행 검증
- CUSTOMER / MECHANIC / ADMIN 역할별 실제 브라우저 업무 흐름 검증
- GitHub Actions 기반 CI

<!-- 테스트 또는 GitHub Actions 이미지 추가 예정 -->

---

## Known Limitations

모바일에서도 주요 기능을 사용할 수 있도록 반응형 UI를 적용했지만, 재고·정비 작업·재무처럼 정보 밀도가 높은 관리 화면은 작은 화면에서 데스크톱보다 가독성이 떨어집니다.

향후 개선한다면 기존 Desktop UI를 단순히 축소하기보다 모바일 업무에 맞는 별도의 정보 우선순위와 화면 구조를 설계하는 방향이 적합하다고 판단했습니다.

---

## Local Run

필요 환경:

- Java 17
- Node.js
- Docker Desktop

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"

Copy-Item .env.example .env
docker compose up -d --build
```

실행 후:

```text
http://localhost:3000
```

실제 비밀번호와 API Key는 `.env`에서 관리하며 Git에는 포함하지 않습니다.

---

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [API](docs/API.md)
- [Workflow](docs/WORKFLOW.md)
- [Roadmap](docs/ROADMAP.md)
