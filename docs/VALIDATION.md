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

## Phase 4C — Final Validation (2026-09-21, Asia/Seoul)

상태: **진행 중 / 미완료**. H2 전체 회귀와 frontend 검증은 통과했지만 V19·V20을 포함한 PostgreSQL 17 및 브라우저·운영 검증이 남아 있어 Phase 4C PASS로 판정하지 않는다.

기준 저장소 상태:

- Branch: `feat/phase4-final-validation`
- HEAD / main baseline: `b58a6d08b69db321dadb0823e5e2375cc7446f11` (`feat: add finance integration (#22)`)
- 검증 시작 시 working tree clean.
- V17 `inventory_cost_lots` / `inventory_cost_allocations`, V18 `mechanics.hourly_cost` 및 WorkOrder labor snapshot 확인. V1–V19 migration은 수정하지 않았다.
- V19 `work_order_cost_resolutions` 추가: 미확정 부품 원가/인건비의 append-only 관리자 확정·정정 이력.
- V20 정비사 월급·기준시간, singleton 재무 참고 설정, append-only 운영비·기타 손익 전표 및 reversal 추가.
- Docker CLI 및 Docker Desktop 실행 파일을 찾지 못해 `compose.test.yaml`의 PostgreSQL 17 실행 불가.

| 검사 | 결과 | 근거 |
|---|---|---|
| `docker compose -f compose.test.yaml up --build --abort-on-container-exit --exit-code-from backend-tests backend-tests` | 미실행 | Docker CLI 없음 |
| 재무·원가 집중 테스트 | PASS | `FinanceCostInventoryIntegrationTest`, `FinanceCostResolutionMigrationTest`, `FinanceManagementMigrationTest`: 17 tests, 실패·오류 0 |
| Backend Maven `verify` | PASS (H2) | 20 test classes; 119 tests, 실패·오류·건너뜀 0, BUILD SUCCESS |
| Frontend `npm ci` | PASS | 28 packages, 취약점 보고 0. 기존 Luna cache 산출물 제거 후 정상 설치 |
| Frontend `npm run typecheck` | PASS | TypeScript 오류 0 |
| Frontend `npm run build` | PASS | Next.js 16.3.4 production build, static generation 21/21 |
| Browser / local E2E | 미실행 | 이 작업에서 브라우저 E2E 수행 불가 |
| Production smoke / Vercel, Render, Neon | 미검증 — **MANUAL PRODUCTION CHECK REQUIRED** | 운영 환경 변경·금융 흐름을 직접 확인하지 않음 |
| `git diff --check` | PASS | 구현·테스트·문서 전체 diff 확인 |

H2 FIFO 불일치 원인: production FIFO/RETURN 계산이 아니라 테스트의 timestamp 설정이 비결정적이었다. `1 × 10,000` lot과 `1 × 20,000` lot을 FIFO로 소비한 뒤 0.5를 반환하면 반환은 마지막 소비 allocation인 20,000원 lot부터 복원하므로 순원가는 `10,000 + (0.5 × 20,000) = 20,000`이다. 두 receipt가 H2 timestamp 정밀도에서 같은 `received_at`으로 저장되면 UUID tie-breaker가 lot 순서를 바꿀 수 있어 이전 실행에서 10,000원 lot 0.5가 복원되고 25,000원이 나타났다. 테스트가 두 lot의 `received_at`을 명시적으로 다르게 설정하고 RESTORE의 `source_allocation_id`, 단가 20,000, 수량 0.5 및 lot 잔량을 검증하도록 수정했다. 같은 집중 테스트와 전체 suite가 재통과했다.

보안 코드/회귀 테스트 검토: `/api/admin/**`은 ADMIN 전용이고 수동 확정 API도 서비스에서 ADMIN을 재검사하며 CSRF와 `Idempotency-Key`를 사용한다. 미완료·미존재 작업, 음수·빈 사유·all-null 요청, 자동 확정 인건비 덮어쓰기를 거절한다. 고객 및 정비사 WorkOrder list/detail 네 경로에서 V18 snapshot 및 V19 finance 필드 비노출을 검증했다. 주문·invoice snapshot과 기존 판매가 계산은 변경하지 않았다.

수동 확정 계산 검증: 자동 확인 부품원가는 보존하고 미확정 부품의 수동 값만 더한다. 자동 인건비 snapshot이 있으면 수동 값으로 대체할 수 없다. 자동 UNKNOWN에 대해서만 최신 non-null 수동 값이 유효하며, 0원도 KNOWN으로 처리한다. 한 구성요소만 정정하면 다른 구성요소의 이전 최신값을 유지하고, 이전 행은 삭제·수정하지 않는다. UNKNOWN 부품이 전부 반환되면 수동값 없이 자동으로 known 상태가 되며 부분 반환 잔량은 계속 UNKNOWN이다.

재무 관리 확장 검증: Asia/Seoul 당월 기본 기간, 월급/기준시간의 원 단위 시간당 원가 계산, 기존 완료 snapshot 보존, 월 전체·부분·다월 달력 일수 안분, null/0 급여 구분, 양·음 배부 차이를 확인했다. 운영비·기타수익·이자·세금의 손익 반영, 설정값 변경, 전표 조회·역분개, 음수/미지원 category 거절, ADMIN 외 접근 거절, UNKNOWN 부품 원가의 손익 전파도 확인했다. V20 upgrade 테스트는 V19 데이터와 invoice·allocation·resolution 보존 및 새 기본값/제약조건을 확인했다.

Historical labor snapshot 회귀 검증: 수동 확정 인건비는 배부원가와 배부 차이에 포함하되 `labor_minutes_snapshot`이 null이면 정비사별 배부시간, 기간 완료시간, 활용률을 null로 유지한다. 완료 작업이 없거나 실제 snapshot 시간이 0인 경우에는 숫자 0을 유지해 UNKNOWN과 구분한다.

현재 급여 분석은 조회 시점의 활성 정비사와 현재 설정 월급을 사용한다. 입·퇴사일 및 급여 변경 이력이 없어 과거 급여대장을 재현하지 않으며 법정 회계·세무 자료가 아닌 관리 추정치다. 작업별 기여이익은 완료 snapshot 인건비를 사용하고, 기간 손익은 기간 급여를 사용해 두 값을 중복 차감하지 않는다. 기본 월급 3,500,000원·209시간·목표 30%는 계획 참고값이며 실제 급여나 검증된 시세가 아니다.

Production readiness 정적 검토: Vercel build에서는 HTTPS `API_BASE_URL`이 필수이고 브라우저는 동일 origin `/api/*` rewrite를 사용하므로 별도 CORS 의존이 없다. Render는 prod profile, secure cookie, DB/admin 환경변수를 요구한다. Hibernate는 `ddl-auto=validate`, Flyway는 startup enabled다. 저장소 설정에서 production용 localhost 의존이나 실제 secret 값은 발견하지 않았다. 실제 Vercel/Render/Neon 동작과 migration 적용은 실행 검증하지 않았다.

### 수동 E2E / 운영 체크리스트 — 아직 수행하지 않음

- [ ] PostgreSQL 17 `compose.test.yaml` 전체 테스트 통과 및 테스트 수 기록
- [x] `npm ci`, `npm run typecheck`, `npm run build` 통과
- [ ] 로컬 ADMIN: 매입 단가 입력, 정비사 원가 설정, 완료 작업 finance 목록/상세/summary 확인
- [ ] 로컬 ADMIN: 정비사 월급 저장 후 향후 완료 snapshot 반영 및 과거 snapshot 불변 확인
- [ ] 로컬 ADMIN: 운영 전표 입력·역분개, 설정 변경, 기간 급여·배부 차이·관리 손익 UI 확인
- [ ] 로컬 MECHANIC 및 CUSTOMER: 작업 응답에서 `labor_minutes_snapshot`, `labor_hourly_cost_snapshot`, `labor_cost_snapshot`, `labor_cost_known` 비노출 확인
- [ ] 입고 매입원가가 판매가와 분리되고 FIFO 소비·부분/전체 반환·UNKNOWN opening stock에서 손실/기여이익이 명시적으로 처리되는지 확인
- [ ] invoice 발행/취소/재발행 및 수납/reversal 동작이 기존 snapshot을 보존하는지 확인
- [ ] 인증·권한·CSRF·Idempotency-Key 동작 및 두 번 제출/재시도 확인
- [ ] 브라우저 오류·레이아웃 확인 및 역할별 계정에서 finance 경로 접근 확인
- [ ] Production smoke check 수행 전 backup/restore와 실제 PG 미연동 경계를 확인하고, 운영 확인 후 별도 증거/날짜 기록
