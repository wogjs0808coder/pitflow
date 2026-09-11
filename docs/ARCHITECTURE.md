# PitFlow 1단계 설계

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
