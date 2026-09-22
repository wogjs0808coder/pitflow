# 1단계 검증 기록

검증일: 2026-09-11

## 완료

| 검사 | 결과 |
|---|---|
| Spring Boot Java 17 컴파일·패키징 | 통과 |
| JUnit/MockMvc 통합 테스트 | 9개 통과, 실패 0, 오류 0 |
| Flyway 초기 스키마 적용 | H2 PostgreSQL 모드에서 통과 |
| Next.js production build | 통과, 화면 경로 생성 |
| TypeScript 타입 검사 | 통과 |
| Next.js standalone → Spring Boot 실제 HTTP | 통과 |
| Docker Compose / GitHub Actions YAML 구문 | 통과 |
| Bash 실행 스크립트 구문 | 통과 |

실제 HTTP 검사는 한 로컬 테스트 환경에서 Next.js standalone 서버와 Spring Boot를 동시에 실행하고 H2 테스트 DB를 사용했습니다. 회원가입, 로그인, 세션 쿠키 유지, 내 정보 조회, 차량 등록·조회·삭제, 정비 항목 조회, 로그아웃 후 401 반환을 확인했습니다.

## 통합 테스트 항목
1. 미인증 요청 401 및 CSRF 없는 변경 요청 403
2. 회원가입 비밀번호 해시, 이메일 중복 방지, role 입력 차단
3. 실제 로그인 필터와 CSRF 토큰, 세션 ID 교체, 로그아웃 세션 무효화
4. 잘못된 비밀번호 거절
5. 다른 고객 차량 조회·수정·삭제 차단
6. 본인 차량 수정·삭제
7. 공백 정규화 후 차량번호 중복 및 음수 주행거리 거절
8. 관리자 항목 추가·비활성화 및 일반 고객 관리자 접근 차단
9. 30분 배수가 아닌 정비 시간 거절

## 아직 확인하지 못한 항목

- 실제 PostgreSQL에서의 테스트: 이 작업 환경에 PostgreSQL/Docker 실행 환경이 없어 미실행. CI는 PostgreSQL 17을 사용하도록 구성함.
- Docker 이미지 빌드와 전체 Compose 실행: 미실행. YAML 구문 확인은 이미지 실행 검증과 다름.
- Windows PowerShell 실행: Windows 환경이 없어 스크립트 실제 실행은 미확인.
- 브라우저 시각적 QA 및 브라우저 클릭 흐름: 브라우저가 로컬 주소 접근을 차단하여 미완료. HTTP 검사는 이를 대체한 시각적 검사로 간주하지 않음.
- GitHub CI: 아직 원격 저장소에 업로드하지 않아 미실행.

## 현재 범위

1단계 당시에는 예약·작업지시서·부품재고·수납 기능을 구현하지 않았습니다. 이후 구현·검증 기록은 아래 단계별 기록과 docs/ROADMAP.md를 참고합니다.


## Phase 2 검증 기록 (2026-09-11)

- 백엔드: 기존 9개 + 예약 13개, H2 PostgreSQL 모드에서 22개 테스트 통과. 예약 생성·취소, 접근 권한, CSRF, 가격 보존, 운영일·시각, 같은 공간/차량 중복, 중간 충돌의 전체 롤백, 동시 생성 및 취소/확정 경쟁을 검사했습니다.
- 프론트엔드: Next.js 프로덕션 빌드와 TypeScript 타입 검사 통과. 예약 신청·내역·관리자 캘린더 경로 생성 확인.
- Flyway: 기존 V1 유지, V2 추가 및 테스트 DB에서 적용 성공.
- Windows에서 업로드된 Maven Wrapper에 실행 비트가 없어 Linux CI가 실행하지 못할 수 있어 `backend/mvnw`의 실행 비트를 복구했습니다.
- 전체 Docker 설정은 사용자에게서 실행이 확인된 noble 이미지, 외부 API 8081, 비공개 DB 설정을 유지했습니다.
- 개발 실행 문서를 실제 설정과 맞추기 위해 선택적인 compose.dev.yaml(DB 외부 5433), 로컬 API 8081을 반영했습니다.
- GitHub Actions는 PostgreSQL 17 서비스로 동일한 백엔드 테스트와 프론트 빌드·타입 검사를 실행합니다. 이 커밋의 원격 실행 결과는 해당 브랜치/PR의 Checks에서 확인합니다.
- 이 작업 환경에는 Docker/로컬 PostgreSQL 서버가 없어 전체 Compose 실행은 재현하지 않았습니다. 브라우저에서의 2단계 화면 동작과 Windows Docker 시연은 사용자 확인 항목으로 남깁니다.

## Phase 3 검증 기록 (2026-09-12)

- 기준 코드: 사용자 제공 bundle의 `62f8cc3`, 브랜치 `feat/work-orders`.
- 백엔드 Maven `verify` 통과: 기존 22개 + 3단계 16개, 총 38개 테스트, 실패·오류·건너뜀 0. H2 PostgreSQL 모드에서 실행했습니다.
- 3단계 테스트는 동시 재고 차감, 동시 중복 키, 역순 다중 부품 요청, 동시 부분 반환, 취소와 사용의 경합, 재고 부족 시 전체 롤백, 재고 갱신 후 DB 제약 실패 시 롤백을 포함합니다.
- 입고 중복·주행거리, 소수 수량, 비활성 부품과 가격 스냅샷, CSRF·관리자 권한·고객 소유 조회도 확인했습니다.
- Flyway V1/V2 소스는 보존했으며 V3가 테스트 DB에서 적용되었습니다.
- Next.js 프로덕션 빌드와 `npm run typecheck` 통과. 관리자 작업·재고 및 고객 작업 내역 경로가 생성되었습니다.
- 실제 PostgreSQL 17 동시성 검증은 GitHub Actions 실행 후 확인해야 합니다. H2 통과만으로 PostgreSQL 잠금 동작까지 검증했다고 판단하지 않습니다.
- 이 변경의 Windows Docker 실행, 브라우저 클릭·시각 검증, 원격 GitHub CI는 아직 실행하지 않았습니다. Windows 적용과 시연 절차는 [PHASE3.md](PHASE3.md)를 따릅니다.

## Phase 3 추가 수정 검증 (2026-09-13, 한국 시간)

- H2 PostgreSQL 모드: 총 44개 테스트 통과(기존 38개 + 재고 보정·삭제 관련 5개 + V3→V4 업그레이드 1개).
- 보정과 부품 사용의 동시 실행, 동시 보정, 중복 요청, 소수 수량, 강제 DB 오류 이후 원장·잔량·키 롤백을 확인했습니다.
- 이름 수정 시 과거 이름 보존, 재고가 남은 부품 삭제 거절, 삭제·복원과 반환 시 목록 복원을 확인했습니다.
- 별도 스키마에서 V3를 적용하고 사용자 수정 데이터와 입고 이력을 만든 다음 V4를 적용했습니다. 기존 재고·정비 설정·이력 보존, 신규 부품 재고 0, 중복 초기화 방지를 확인했습니다.
- Next.js 프로덕션 빌드와 타입 검사 통과. 날짜별 전체 예약과 방문 가능 안내를 표시하도록 변경했고 기존 예약 상태·시간 정책 테스트도 통과했습니다.
- 실제 PostgreSQL CI, Windows Docker, 브라우저 클릭 검증은 미실행입니다. 추가 패치 적용 절차는 [PHASE3-FIXES.md](PHASE3-FIXES.md)에 있습니다.

## V4 PostgreSQL 호환 수정 (2026-09-13, 한국 시간)

사용자 PostgreSQL 17.11에서 V4 제약조건 조회 실패·롤백이 확인되었습니다. NOT NULL을 CHECK 개수에 포함한 원인을 수정했습니다. H2 44개 테스트 재통과, PostgreSQL 17.5 기반 PGlite에서 기존 오류 재현 및 수정 SQL·NOT NULL 보존을 검증했습니다. 네이티브 PostgreSQL 전체 회귀 검증용 compose.test.yaml과 복구 절차는 [V4-HOTFIX.md](V4-HOTFIX.md)에 추가했습니다.
## Phase 3E 검증 기록 (2026-09-18, 한국 시간)

- 구현 브랜치: `feat/phase3e-admin-workspace`.
- PR #18 `feat: Phase 3E 관리자·정비사·고객 UX 개선` merge 완료.
- `main` 기준 merge 결과 commit: `abc80ad`.
- Phase 3E 애플리케이션 변경은 Frontend 5개 파일로 제한했다.
- Backend Java source 변경 없음.
- Flyway migration 및 DB schema 변경 없음.
- 기존 API, 인증, 권한, CSRF, idempotency, WorkOrder / WorkOrderItem 상태 전이 의미를 변경하지 않았다.
- `npm run typecheck` 통과.
- `npm run build` 통과.
- Next.js production build에서 20/20 route 생성 확인.
- `git diff --check` 통과.
- CUSTOMER 예약 목록과 navigation 브라우저 확인 통과.
- MECHANIC 작업 목록·상세·항목 처리·부품 부족 신고·부품 작업 UI 브라우저 확인 통과.
- ADMIN workflow navigation·작업 현황·작업 상세·항목 처리 UI 브라우저 확인 통과.
- 작업 처리 성공/실패 feedback을 스크롤 위치와 관계없이 확인할 수 있도록 fixed notification UI로 변경했고 브라우저에서 동작을 확인했다.
- PC 화면은 역할별 정보 구조와 작업 흐름을 중심으로 UX를 개선했다.
- 모바일 환경은 고밀도 관리자·정비사 화면의 배치와 사용성이 충분하지 않아 추가 반응형 개선이 필요하다. 이 항목은 Phase 6 Hardening / Production Readiness 범위로 이관한다.
- Phase 3E에서는 Backend 회귀 테스트를 새로 요구하는 기능 변경이 없었으며, 기존 Phase 3D Backend / DB 검증 기준을 유지한다.

## Phase 4C — Final Validation 완료 (2026-09-21, Asia/Seoul)

상태: 완료 / PASS.

Phase 4A Inventory Cost Core, Phase 4B Finance Integration에 이어 V19 과거 미확정 원가 확정과 V20 재무 관리 확장, 로컬·PostgreSQL 17·production 검증까지 완료했다.

기준 저장소:

- Phase 4C feature branch: `feat/phase4-final-validation`
- PR: #23
- Phase 4C merge commit: `9970108`
- V19: `work_order_cost_resolutions`
- V20: 정비사 월급·기준시간, finance settings, append-only 운영 전표/reversal
- 기존 적용 migration은 수정하지 않고 V19, V20을 append-only migration으로 추가했다.

### 최종 자동 검증

| 검사 | 결과 |
|---|---|
| PostgreSQL 17 `compose.test.yaml` 전체 Backend 검증 | PASS — 119/119, 실패 0, 오류 0, 건너뜀 0 |
| Backend Maven verify | PASS |
| Frontend TypeScript typecheck | PASS |
| Frontend production build | PASS — 21/21 |
| `git diff --check` | PASS |
| V1–V20 migration 정합성 | PASS |
| V17 FIFO / RETURN 회귀 | PASS |
| V18 labor snapshot 회귀 | PASS |
| V19 manual cost resolution 회귀 | PASS |
| V20 finance management 회귀 | PASS |

### 로컬 브라우저 E2E

실제 PostgreSQL 17 로컬 환경에서 다음을 확인했다.

- Finance 기본 조회 기간: Asia/Seoul 기준 당월 1일 ~ 오늘
- 정비사 월급 3,500,000원 / 기준시간 209시간 저장
- 계산 시간당 원가 16,746원 확인
- 9월 1일~21일 기간 급여 2,450,000원 확인
- 과거 UNKNOWN 인건비 원가를 V19 수동 확정으로 20,000원 처리
- 자동 확인 부품원가 0원과 UNKNOWN 구분
- 과거 작업시간 UNKNOWN을 실제 0시간과 구분하여 `미확정` 유지
- 수동 확정 인건비를 배부원가 및 배부 차이에 반영
- 운영비 1,000,000원 입력 및 관리 손익 반영
- append-only 역분개 후 손익 복구
- 신규 부품 입고 시 매입원가와 판매가 분리
- 신규 WorkOrder에서 FIFO 부품원가 및 완료 시점 인건비 snapshot 자동 생성
- ADMIN / MECHANIC / CUSTOMER 역할별 화면 및 내부 재무정보 비노출

신규 자동 원가 검증값:

| 항목 | 결과 |
|---|---:|
| 매출 | 50,000원 |
| 자동 FIFO 부품원가 | 8,000원 |
| 완료 시점 시간당 원가 | 16,746원 |
| 작업시간 | 30분 |
| 자동 인건비 | 8,373원 |
| 총원가 | 16,373원 |
| 기여이익 | 33,627원 |
| 수납 | 50,000원 |
| 미수 | 0원 |

계산:

- 인건비: `16,746 × 30 / 60 = 8,373원`
- 총원가: `8,000 + 8,373 = 16,373원`
- 기여이익: `50,000 - 16,373 = 33,627원`

### Production smoke

Production frontend:

`https://pitflow-orcin.vercel.app/`

Production backend:

`https://pitflow-api.onrender.com`

Production database:

Neon PostgreSQL

production에서 다음을 확인했다.

- Vercel production frontend 정상
- Render backend API 연동 정상
- V19/V20 Finance 화면 정상
- 정비사 월급 및 기준시간 관리 정상
- 과거 UNKNOWN 인건비 V19 수동 확정 정상
- 기간 급여 및 배부 차이 계산 정상
- 정산·수납값의 Finance 수납/미수/수납률 반영 정상
- ADMIN 재무·원가 접근 정상
- MECHANIC 작업 접근 정상 및 내부 재무정보 비노출
- CUSTOMER 본인 작업/이력 접근 정상 및 관리자 재무 기능 비노출

Production 수동 원가 확정 검증값:

- 기간 매출: 887,000원
- 과거 작업 6건의 인건비를 각각 1,000원으로 수동 확정
- 작업 배부 인건비: 6,000원
- 작업별 기여이익 합계: 881,000원
- 기간 급여: 5,950,000원
- 배부 차이: 5,944,000원
- 전체 수납 처리 후 수납: 887,000원
- 미수: 0원
- 수납률: 100%

기간 관리 손익은 작업별 배부 인건비를 다시 차감하지 않고 기간 급여를 사용한다.

운영비·기타 손익이 없는 production 확인 시점:

`887,000 - 5,950,000 = -5,063,000원`

화면의 매출총이익·영업이익·순이익과 일치했다.

### 권한 검증

ADMIN:

- 정비사 월급·기준시간 관리
- Finance 원가 상세
- 수동 원가 확정
- 운영 전표 및 관리 손익 접근

MECHANIC:

- 본인 담당 작업 접근
- 정비 항목 처리
- 부품 사용·반환
- 부품 부족 신고
- 월급, 매입원가, 총원가, 기여이익, Finance 관리자 기능 비노출

CUSTOMER:

- 본인 차량·예약·작업·정비이력 접근
- 판매 공임 및 고객용 정비정보 노출
- 관리자 Finance, 정비사 월급, 매입원가, 내부 인건비, 총원가, 기여이익 비노출

### 원가·재무 의미

V17:

- 입고 lot 실제 매입원가
- FIFO 사용 allocation
- 원래 소비 lot 기준 RETURN
- UNKNOWN opening stock

V18:

- WorkOrder 완료 시점 labor snapshot
- 완료 이후 현재 정비사 원가 변경과 무관하게 과거 snapshot 보존

V19:

- 과거 UNKNOWN 부품·인건비 원가 관리자 수동 확정
- 자동 KNOWN 원가 덮어쓰기 금지
- append-only 정정 이력
- 0원도 KNOWN으로 처리

V20:

- 정비사 월급 및 기준시간
- 월급 기반 시간당 원가
- 달력 일수 기반 기간 급여
- 작업 배부 인건비 / 기간 급여 분리
- 배부 차이
- 급여 비율
- 운영비·기타 손익 전표
- append-only reversal
- 관리 손익 및 KPI

### 남아 있는 제품 경계

현재 Finance 기능은 법정 손익계산서, 재무상태표, 현금흐름표를 생성하는 회계 시스템이 아니다.

현재 범위는 정비소 운영을 위한 관리 손익·원가·수익성 분석이다.

정비사 급여 분석은 현재 설정된 급여와 활성 정비사를 기준으로 한다. 입사일·퇴사일 및 급여 변경 이력이 있는 정식 급여대장 기능은 현재 범위가 아니다.

현장 수납은 관리자가 확인한 결제 사실을 기록한다. Phase 5C는 별도로 Toss Payments 테스트 결제의 요청·서버 승인·전액 환불을 지원하며 webhook과 부분 환불은 현재 범위가 아니다.

고밀도 관리자·정비사 화면의 추가 모바일 반응형 개선, 전체 production hardening, backup/restore drill 및 최종 포트폴리오 정리는 Phase 6 범위로 유지한다.

### Phase 4C 최종 판정

Phase 4C — Final Validation / Production Readiness / Documentation: 완료.

Phase 4 — Finance & Cost: 완료.

다음 구현 단계:

Phase 5 — Payments.

## Phase 5A — Treasury Core & Rebalancing 검증 기록 (2026-09-21, Asia/Seoul)

현재 판정: 진행 중. 구현 및 로컬 검증은 완료했으나 PostgreSQL 17 전체 검증이 남아 있어 완료로 기록하지 않는다.

| 검사 | 결과 |
| --- | --- |
| Phase 5A 집중 테스트 | PASS — 6/6, 실패 0, 오류 0, 건너뜀 0 |
| H2 기반 Backend 전체 `verify` | PASS — 125/125, 실패 0, 오류 0, 건너뜀 0 |
| Frontend `npm run typecheck` | PASS |
| Frontend production build | PASS — 정적 페이지 21/21 |
| PostgreSQL 17 `compose.test.yaml` 전체 Backend 검증 | 미실행 — 현재 실행 환경에서 Docker CLI를 찾을 수 없음 |
| 로컬 PostgreSQL 18 대체 검증 | 미실행 — 서비스는 실행 중이나 테스트 전용 접속 자격 증명을 사용할 수 없음 |

검증한 범위:

- V20에서 V21로의 migration, 계정 3개와 10억원 opening allocation, migration 재실행 안전성
- 초기 잔액 4억원/3억원/3억원과 목표 비중 40/30/30
- opening 원장과 현재 잔액 정합성, 음수 잔액 DB 제약
- ADMIN 조회, CUSTOMER/MECHANIC 차단, mutation CSRF 및 Idempotency-Key 요구
- 재조정 총자산 보존, 목표 금액과 remainder, event group delta 합계 0, `balance_after` 정합성
- 동일 키 replay, 이미 균형인 상태의 no-op, 서로 다른 키의 동시 요청 직렬화
- 중간 DB 오류 시 계정·원장·멱등 기록 전체 rollback

Phase 4 Finance 계산과 기존 수납·reversal 동작은 변경하지 않았다. V1–V20 migration도 수정하지 않았다. PostgreSQL 17 전체 검증이 통과하면 Phase 5A 완료 판정을 갱신한다.

### Phase 5A 최종 검증

Phase 5A — Treasury Core & Rebalancing: 완료.

최종 검증 결과:

- Backend Maven verify: PASS — 125/125, 실패 0, 오류 0, 건너뜀 0
- PostgreSQL 17 `compose.test.yaml`: PASS — 125/125, 실패 0, 오류 0, 건너뜀 0
- Frontend TypeScript typecheck: PASS
- Frontend production build: PASS — 21/21 routes
- `git diff --check`: PASS
- 로컬 브라우저 `/admin/finance` smoke: PASS
- 현재 회사자산 1,000,000,000원 확인
- OPERATING 400,000,000원 / 40% 확인
- DEPOSIT 300,000,000원 / 30% 확인
- INVESTMENT 300,000,000원 / 30% 확인
- 초기 목표 비중과 현재 비중이 동일하여 `목표 비중으로 재조정` 버튼 비활성화 확인
- 기존 Phase 4 Finance 화면 및 계산 회귀 없음 확인

Phase 5B에서 은행예치 일복리와 투자자산 일일 수익률 시뮬레이션을 추가한다.

## Phase 5C — Payments / Inventory Resolution / Finalization 검증 기록 (2026-09-22, Asia/Seoul)

현재 판정: Phase 5C provider E2E와 PostgreSQL 17 검증 완료. 결제·출고 UX 후속 변경은 H2 전체 회귀와 frontend build를 통과했으며 최종 browser smoke가 남아 있다.

| 검사 | 결과 |
| --- | --- |
| Phase 5C 집중 migration·결제·환불·원가 확정·동시성 테스트 | PASS — 22/22 |
| H2 기반 Backend 전체 `verify` | PASS — 148/148, 실패 0, 오류 0, 건너뜀 0 |
| Frontend `npm run typecheck` | PASS |
| Frontend production build | PASS — 23/23 routes |
| PostgreSQL 17 `compose.test.yaml` 전체 Backend 검증 | PASS — 142/142 (Phase 5C provider E2E 기준) |
| 로컬 browser 재고자산·Finance smoke | PASS |
| 실제 Toss 테스트 키 결제·환불 browser smoke | PASS — PAYMENT/환불과 Treasury 반영 확인 |

검증한 범위:

- V22→V23 migration과 기존 UNKNOWN lot 보존, V23→V24 결제 주문 다회 시도 migration, 재실행·validate
- UNKNOWN 전체/부분/KNOWN 0원 확정, duplicate key, Treasury cash-neutral, 관리 자산 증가
- Toss 금액 불일치 거절, 승인·중복 승인, 동시 승인 단일 payment/ledger, 내부 DB 실패 후 provider 조회 재시도
- Toss 전액 환불·중복 환불과 OPERATING/미수채권 복원, 환불 후 새 Toss 주문 또는 CASH/TRANSFER 재수납
- Customer 본인/타인 invoice 권한, ADMIN 카운터 주문의 invoice customer identity, paid invoice 중복 주문 차단
- MECHANIC 출고 차단, ADMIN 출고, OPEN invoice 미수금 출고 차단
- `/admin/parts` 기본 접힘, UNKNOWN-only `원가 미확정`, 예상 가치, 10 EA 중 4 EA 부분 확정 후 280,000원/UNKNOWN 6 EA 표시
- `/admin/finance` 280,000원 재고자산·UNKNOWN warning 반영 및 상세 재고 table 미노출

V1–V23 migration은 변경하지 않았고 V24만 추가했다. Customer 카드 결제 CTA, 환불 후 신규 Toss 또는 현장 재수납, ADMIN 카운터 결제, WorkOrder 명세 발행·결제·출고 UI를 수동 browser에서 최종 확인한다.
