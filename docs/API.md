# API 명세 — Phase 1·2

3단계 작업지시·부품 API 및 요청 키 계약은 [PHASE3.md](PHASE3.md)의 API 항목을 확인하세요.

기본 주소: 프론트엔드 `http://localhost:3000/api`. 직접 API 검사는 `http://localhost:8081/api`.

모든 변경 요청은 먼저 `GET /auth/csrf`를 호출하고 반환된 `headerName` 헤더에 `token`을 넣어야 합니다. 같은 세션 쿠키를 유지하세요.

| 메서드 | 경로 | 권한 | 성공 |
|---|---|---|---|
| GET | /health | 공개 | 200 |
| GET | /auth/csrf | 공개 | 200 |
| POST | /auth/register | 공개 + CSRF | 201 |
| POST | /auth/login | 공개 + CSRF | 200 |
| POST | /auth/logout | CSRF | 204 |
| GET | /auth/me | 로그인 | 200 |
| GET | /vehicles | 로그인 | 200 |
| POST | /vehicles | 로그인 + CSRF | 201 |
| PUT | /vehicles/{id} | 소유자 + CSRF | 200 |
| DELETE | /vehicles/{id} | 소유자 + CSRF | 204 |
| GET | /services | 로그인 | 200 |
| GET | /admin/services | 관리자 | 200 |
| POST | /admin/services | 관리자 + CSRF | 201 |
| PUT | /admin/services/{id} | 관리자 + CSRF | 200 |

## 회원가입
`application/json`
```json
{"email":"customer@example.com","password":"EXAMPLE-password-123!","name":"시연 고객"}
```
이메일은 대소문자 구분 없이 저장합니다. 비밀번호 12~64자, BCrypt 입력 한계 때문에 UTF-8 72바이트 이하. 이름 최대 50자. role 등 알 수 없는 필드는 거절합니다. 가입 응답에는 id, email, name, role만 포함됩니다. 가입 API 자체는 로그인하지 않으며 프론트엔드가 이어서 로그인 요청을 보냅니다.

## 로그인
`application/x-www-form-urlencoded`
```text
email=customer%40example.com&password=EXAMPLE-password-123%21
```
성공 응답은 회원 정보 JSON이며 HttpOnly 세션 쿠키가 발급됩니다. 비밀번호가 잘못되면 401.

## 차량 등록·수정
`application/json`
```json
{"plateNumber":"123가4567","manufacturer":"기아","model":"K3","modelYear":2024,"mileage":26400}
```
차량번호 최대 20자, 제조사 40자, 모델 60자, 연식 1900~2100, 주행거리 0~9999999. 빈 문자열과 중복 차량번호를 거절합니다. API가 제공하는 차량번호 입력 검사는 등록 형식 점검이며 실소유 확인이 아닙니다.

## 정비 항목 등록·수정
`application/json`
```json
{"name":"배터리 교체","description":"배터리 비용은 별도입니다.","laborPrice":15000,"durationMinutes":30,"active":true}
```
공임은 정수 원 단위이며 최대 12자리. 시간은 30~480분의 30분 배수. `active=false`로 변경하면 고객 목록에서 숨깁니다.

## 오류 응답
```json
{"message":"입력 내용을 확인해 주세요.","fields":{"password":"비밀번호는 12~64자로 입력해 주세요."}}
```

런타임 Swagger UI는 아직 포함하지 않았습니다. 이 문서가 1·2단계의 API 명세입니다.


## 2단계: 정비 예약

모든 예약 API는 로그인 필요. 관리자 경로는 ADMIN 필요. POST/PATCH에는 기존 CSRF 토큰 필요.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /appointments/policy | 운영시간, 휴무 요일, 타임존, 예약 가능 날짜 범위 |
| GET | /appointments/availability?vehicleId=UUID&date=2026-09-15&serviceIds=UUID,UUID | 본인 차량과 선택 항목을 위한 연속 시간·가능한 작업 공간 |
| POST | /appointments | 예약 신청, 201, 상태 PENDING |
| GET | /appointments?from=2026-09-01&to=2026-09-30 | 본인 예약 목록, 날짜 양끝 포함 |
| GET | /appointments/{id} | 본인 예약 상세, 다른 고객은 404 |
| POST | /appointments/{id}/cancel | 본인 예약 취소, 반복 요청도 200 |
| GET | /admin/work-bays | 활성 작업 공간 목록 |
| GET | /admin/appointments?from=2026-09-15&to=2026-09-15 | 관리자 일정과 고객 정보 |
| PATCH | /admin/appointments/{id}/status | 관리자 상태 변경 |

조회 날짜는 1900~2100년이며 종료일은 시작일에서 최대 62일 뒤까지 허용합니다. 예약 생성 날짜 범위는 별도로 policy 응답을 따릅니다.

예약 생성 (UUID는 실제 조회 응답 값 사용):
```json
{
  "vehicleId": "차량 UUID",
  "workBayId": "가능한 작업 공간 UUID",
  "serviceIds": ["정비 항목 UUID"],
  "startsAt": "2026-09-15T10:00:00+09:00",
  "notes": "오일 상태 확인 부탁드립니다."
}
```

`startsAt`은 오프셋이 있는 ISO 8601 시각입니다. 서버가 한국 시간으로 변환하여 검증하며, 응답 시각도 한국 시간 오프셋을 사용합니다. 요청에 customerId·가격·상태를 지정할 수 없습니다. 항목은 중복 없이 1~16개, 총 480분 이하, 요청사항 500자 이하입니다. 작업 공간은 availability의 `slots[].availableBays`에서 선택합니다. 프론트엔드는 선택 시각의 첫 번째 가능한 공간을 사용합니다.

availability는 `date`, `policy`, `closed`, `durationMinutes`, `totalLaborPrice`, `slots`를 반환합니다. 각 슬롯에는 `startsAt`, `endsAt`, `availableBays: [{id, name}]`가 있습니다. 검색 결과는 공간 확보를 보장하지 않으므로 생성 시 충돌하면 409를 반환합니다.

예약 응답에는 `id`, 차량 ID·예약 시점 차량번호·차종, 작업 공간, 고객 이름·이메일, 시작·종료, `status`, 요청사항, 예약 시점 총 공임·시간, 항목 목록, 현재 가능한 `allowedStatuses`가 포함됩니다. 고객은 자신의 예약만 조회할 수 있습니다.

관리자 상태 변경:
```json
{"status":"CONFIRMED"}
```

- PENDING → CONFIRMED: 종료 전.
- PENDING/CONFIRMED → CANCELLED: 관리자는 가능, 고객은 시작 전만 가능.
- CONFIRMED → VISITED: 시작 30분 전부터 종료 전까지.
- PENDING/CONFIRMED → NO_SHOW: 종료 시각 이후.
- CANCELLED/VISITED/NO_SHOW: 다른 상태로 변경 불가. 같은 상태 재요청은 성공 응답.
- 취소·미방문 처리 시 슬롯을 반환합니다. 방문 처리된 예약은 예정 종료까지 슬롯을 유지합니다.

대표 실패: 미인증 401, 관리자 권한·CSRF 실패 403, 타인 예약·차량 404, 중복 시간·금지된 상태 변경·예약 이력 차량 삭제 409, 날짜·항목·시간 형식 오류 400.
