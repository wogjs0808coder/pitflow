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

예약·작업지시서·부품재고·수납 기능은 아직 구현하지 않았습니다. 1단계 완료 후 docs/ROADMAP.md 순서로 확장합니다.
