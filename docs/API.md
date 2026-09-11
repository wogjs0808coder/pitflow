# API 명세 — Phase 1

기본 주소: 프론트엔드 `http://localhost:3000/api`. 직접 API 검사는 `http://localhost:8080/api`.

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

런타임 Swagger UI는 아직 포함하지 않았습니다. 이 문서가 1단계의 API 명세입니다.
