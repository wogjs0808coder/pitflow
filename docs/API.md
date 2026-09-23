# API

PitFlow Backend는 Spring Boot REST API로 구성됩니다.

전체 endpoint 목록보다 기능 영역과 공통 규칙을 빠르게 확인하기 위한 문서입니다.

## 주소

로컬:

```text
http://localhost:8081
```

배포:

```text
https://pitflow-api.onrender.com
```

## API 영역

| 영역 | 역할 |
| --- | --- |
| `/api/auth` | 회원가입, 로그인, 로그아웃, 계정 정보·복구 |
| `/api/vehicles` | 고객 차량 |
| `/api/services` | 정비 서비스 |
| `/api/parts-guide` | 고객 부품 안내 |
| `/api/appointments` | 예약 |
| `/api/work-orders` | 정비 작업 |
| `/api/billing` | 고객 정산과 결제 |
| `/api/admin/*` | 관리자 기능 |
| `/api/admin/finance/*` | 원가와 재무 |
| `/api/mechanic/*` | 정비사 작업과 부품 |
| `/api/notifications` | 업무 알림 조회, 미확인 수, 읽음 처리 |

세부 endpoint는 각 Controller source를 기준으로 합니다.

## 인증과 권한

인증은 서버 Session Cookie를 사용합니다.

역할:

```text
CUSTOMER
MECHANIC
ADMIN
```

고객은 자신의 데이터만 접근할 수 있습니다.

정비사는 자신에게 배정된 작업만 변경할 수 있습니다.

관리자 기능은 Backend에서 ADMIN 역할을 확인합니다.

관리자 계정 관리 중 메인 관리자 전용 작업은 별도로 권한을 확인합니다. 이메일·비밀번호 변경 또는 계정 비활성화 뒤 기존 세션은 다음 요청에서 무효화됩니다.

Frontend에서 버튼을 숨기는 것은 편의를 위한 것이며 실제 권한 검사는 Backend에서 수행합니다.

## 서버 검증

결제 금액이나 재고 수량처럼 중요한 값은 브라우저에서 받은 값을 그대로 최종값으로 사용하지 않습니다.

Backend가 현재 Database 상태를 다시 확인합니다.

주요 검증 대상:

- 사용자 권한
- 차량 소유권
- 예약 중복
- 재고 수량
- 결제 금액
- 남은 결제 금액
- 작업 상태

## Toss Payments

결제 준비, 승인, 환불은 Backend를 통해 처리합니다.

Toss Secret Key는 Backend에서만 사용합니다.

환불된 결제를 다시 진행하는 경우 기존 주문번호를 재사용하지 않고 새로운 Toss 주문을 생성합니다.

## 오류

| 상태 | 의미 |
| --- | --- |
| 400 | 잘못된 입력 |
| 401 | 로그인 필요 |
| 403 | 권한 또는 보안 검증 실패 |
| 404 | 데이터 없음 또는 접근 불가 |
| 409 | 예약 중복, 재고 부족, 상태 충돌 등 |

서버 내부 오류나 Database 오류 원문은 사용자 응답에 그대로 노출하지 않습니다.
