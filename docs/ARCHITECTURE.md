# PitFlow 설계 (1·2단계)

3단계 DB, 재고 잠금 순서, idempotency, 실물 반환 정책은 [PHASE3.md](PHASE3.md)를 따릅니다.

## 요청 구조
브라우저 → Next.js `/api/*` rewrite → Spring Boot → PostgreSQL.

인증은 서버 세션을 사용합니다. `PITFLOW_SESSION`은 HttpOnly, SameSite=Lax 쿠키이며 운영 HTTPS에서는 COOKIE_SECURE=true로 설정합니다. 세션은 서버 메모리에 있으므로 백엔드를 재시작하면 재로그인이 필요합니다.

클라이언트는 변경 요청 전에 `/api/auth/csrf`에서 토큰을 받고 반환된 헤더 이름과 토큰으로 요청합니다. 로그인 시 Spring Security가 세션 ID와 CSRF 상태를 교체하므로 변경 요청마다 토큰을 다시 읽습니다. 암호나 인증 토큰은 localStorage에 저장하지 않습니다.

## 현재 엔티티
| 테이블 | 핵심 필드 | 제약 |
|---|---|---|
| users | id, email, password_hash, name, role | 이메일 고유, 역할 CUSTOMER/ADMIN |
| vehicles | id, owner_id, plate_number, manufacturer, model, model_year, mileage | 소유자 FK, 차량번호 고유, 비음수 주행거리 |
| service_items | id, name, description, labor_price, duration_minutes, active | 이름 고유, 30분 단위 시간, 비음수 공임 |

차량 번호는 입력 공백 제거와 영문 대문자화를 적용합니다. 차량 등록 단계는 고객이 직접 입력하는 정보이며 실차 소유권을 외부에서 검증하지 않습니다. 향후 차량 양도 시에는 관리자 확인을 포함한 별도 흐름이 필요합니다.

## 권한
- `/api/auth/csrf`, `/api/auth/register`, `/api/auth/login`, `/api/health`: 로그인 없이 접근 가능. 변경 요청에는 CSRF 필요.
- `/api/vehicles`: 로그인한 사용자의 ID를 서버에서 결정. 요청 JSON에 ownerId를 받지 않음.
- 차량 수정·삭제: id와 ownerId를 함께 조회. 타인 차량은 404.
- `/api/services`: 로그인 사용자에게 활성 항목만 반환.
- `/api/admin/**`: Spring Security ADMIN 역할 확인.
- 클라이언트 메뉴 숨김은 편의 기능이며 보안 통제는 백엔드에서 수행.

## 오류
입력 오류 400, 미인증 401, 권한·CSRF 오류 403, 미존재·타인 소유 404, 중복 정보 409.
민감한 서버 스택과 DB 오류 원문은 응답에 포함하지 않습니다.

## 다음 단계의 변경 원칙
- 기존 Flyway migration은 배포 후 수정하지 않고 V2부터 추가합니다.
- 예약 또는 이력이 연결되면 차량 삭제 API를 검토하여 참조된 차량은 삭제 제한 또는 보관 처리합니다.
- 정비 항목은 비활성화하고 과거 작업 시점의 명칭·단가를 작업 테이블에 보존합니다.
- 원재료·부품 재고와 예약 가능 시간을 1단계의 차량 주행거리와 혼합하지 않습니다.
- 프록시 대상 API_BASE_URL은 서버 설정이며 브라우저 공개 환경변수로 만들지 않습니다.


## 예약 저장과 동시성

예약 모듈은 JdbcTemplate로 SQL과 행 잠금을 명시하고, 기존 계정·차량·카탈로그 JPA 저장소와 같은 DataSource 및 Spring 트랜잭션을 사용합니다.

- `work_bays`: 시연용 공간 2개.
- `appointments`: 고객·차량·공간 FK, 시작/종료, 상태, 예약 당시 차량정보·총 공임·소요시간.
- `appointment_items`: 예약 당시 항목 이름·공임·시간을 복사. 이후 카탈로그 수정과 무관하게 유지.
- `slot_allocations`: 30분 단위 점유. `(work_bay_id, starts_at)` PK로 공간 중복, `(vehicle_id, starts_at)` UNIQUE로 동일 차량의 중복을 막음.

생성 트랜잭션에서 차량 소유권을 확인하면서 차량 행을 잠그고, 서버가 계산한 예약·항목·슬롯을 모두 저장합니다. 슬롯은 시작부터 종료 직전까지 오름차순으로 삽입합니다. 중간 슬롯이라도 고유 제약을 위반하면 전체 롤백하고 409를 응답합니다. 프론트엔드의 선행 availability 조회와 무관하게 DB가 최종 중복을 검사합니다. JVM 메모리 잠금에 의존하지 않습니다.

상태 변경·취소는 예약 행의 `SELECT ... FOR UPDATE`를 사용하고, 잠금을 얻은 뒤 최신 상태와 시간을 재검사합니다. 취소와 확정 요청이 겹쳐도 취소한 예약이 부활하거나 슬롯이 남지 않도록 상태와 슬롯 삭제를 같은 트랜잭션에서 처리합니다. 차량 삭제도 차량 행을 잠그고 예약 참조를 검사하며 FK가 최종 무결성을 유지합니다.

운영 정책은 `application.yml`의 `pitflow.booking`으로 관리합니다. 저장은 TIMESTAMP WITH TIME ZONE, 계산과 응답은 Asia/Seoul 기준입니다. 현재 정책은 자정 넘김 없는 운영시간과 30분 간격을 전제로 하며, 1차 범위에 임시 휴무일·점심시간·정비별 전용 공간·예약 자동 만료는 포함하지 않습니다. UI는 한국 시간 사용을 전제로 합니다.

예약 대기도 공간을 점유하고 자동 만료되지 않습니다. 관리자는 종료된 대기/확정 예약을 미방문 처리할 수 있습니다. 실제 입고 이후 작업시간 연장과 정비 완료는 3단계 작업지시서 범위입니다.

## 실행 주소

전체 Docker: 브라우저 → localhost:3000 → backend:8080 → db:5432. 백엔드 직접 검사용 호스트 포트는 8081이며 DB는 미공개입니다. VSCode 개별 실행 시 compose.dev.yaml로 DB만 localhost:5433에 공개하고 API를 localhost:8081에서 실행합니다.

## 정산·수납

4단계 V5 구조, 스냅샷·수납 원장·잠금·재발행 정책과 API는 [PHASE4.md](PHASE4.md)를 따릅니다.

## 재무 원가 확정

V17의 FIFO lot/allocation과 V18의 인건비 snapshot은 자동 원가의 원본이며 수정하지 않습니다. V19 `work_order_cost_resolutions`는 과거 UNKNOWN 원가를 관리자가 근거와 함께 확정·정정하는 append-only 계층입니다. 구성요소별 최신 non-null 값만 유효하고 이전 행은 감사 이력으로 남습니다. 자동 KNOWN 값은 수동 값으로 대체할 수 없으며, 모든 유효 원가가 확인된 경우에만 총원가와 기여이익을 계산합니다.

V20은 정비사별 월급과 기준시간으로 향후 WorkOrder snapshot에 사용할 시간당 원가를 계산합니다. 이미 완료된 WorkOrder snapshot은 급여 변경 후에도 보존됩니다. 기간 급여는 조회 시점에 활성인 정비사의 현재 월급을 달력 일수로 안분한 관리 추정치입니다. 입·퇴사일과 급여 변경 이력은 아직 보관하지 않으므로 과거 법정 급여대장이나 회계 원장을 대체하지 않습니다. 월급이 없는 활성 정비사가 있으면 급여와 이에 의존하는 손익을 UNKNOWN으로 유지하며, 0원은 명시적으로 확인된 값입니다.

작업별 기여이익은 WorkOrder 완료 시점의 배부 인건비와 부품원가를 사용합니다. 기간 관리 손익은 매출에서 부품원가와 기간 급여를 차감한 매출총이익을 출발점으로 운영비, 기타수익, 이자, 세금을 순서대로 반영합니다. 따라서 작업별 배부 인건비는 분석 지표이며 기간 손익에서 급여와 중복 차감하지 않습니다. `finance_entries`는 원행을 수정·삭제하지 않고 반대 부호의 reversal 행을 추가합니다. 기본 월급·시간·목표 급여율은 계획 참고값일 뿐 정비사 실제 급여를 자동 생성하지 않습니다.
