# 무료 포트폴리오 배포

확인일: 2026-09-13

대상은 개인 비상업 포트폴리오입니다. Vercel Hobby, Render Free Web Service, Neon Free의 현재 공식 정책과 각 대시보드에 표시되는 한도를 배포 직전에 다시 확인합니다. 무료 서비스의 무중단·영구 제공은 보장하지 않으며 자동 keep-alive를 사용하지 않습니다.

- Vercel Hobby: 개인·비상업 프로젝트용 무료 플랜. [공식 안내](https://vercel.com/docs/plans/hobby)
- Render Free: 15분 동안 요청이 없으면 중지되고 다음 요청 때 약 1분의 재기동 시간이 생길 수 있습니다. 로컬 파일은 영구 저장소가 아니며 무료 Render Postgres는 30일 후 만료됩니다. [공식 안내](https://render.com/docs/free)
- Neon Free: 월 비용이 없는 사용량 제한 플랜입니다. 프로젝트별 현재 compute·storage 한도는 생성 화면과 [공식 플랜 안내](https://neon.com/docs/introduction/plans)에서 다시 확인합니다.

## 1. 배포 구조와 순서

1. 기능 PR의 GitHub Actions가 성공한 뒤 `main`에 병합합니다.
2. Neon에 로컬 데이터와 분리된 빈 운영 프로젝트를 만듭니다.
3. Render가 `main`의 `render.yaml`로 Backend를 배포하고 Flyway V1부터 최신 migration까지 적용합니다.
4. Backend의 생존·DB 준비 상태와 로그를 확인합니다.
5. Vercel이 `frontend`를 빌드하면서 Render HTTPS 주소를 `/api` rewrite 대상으로 고정합니다.
6. HTTPS 사이트에서 회원가입·로그인·CSRF·로그아웃과 고객/관리자 대표 시나리오를 확인합니다.

배포 DB에는 가상 이름·가상 차량번호 등 시연 데이터만 넣습니다. 로컬 DB 덤프를 운영 Neon 프로젝트에 복원하지 않습니다.

## 2. Neon Free

1. Neon Dashboard에서 **New project**를 선택합니다.
2. 이름은 `pitflow-portfolio`, PostgreSQL 버전은 기본값을 사용합니다.
3. Render의 `singapore`와 같거나 가장 가까운 제공 리전을 선택합니다. 리전은 생성 화면에 실제로 표시되는 선택지를 기준으로 합니다.
4. **Connect**에서 애플리케이션용 role과 database를 확인합니다.
5. Backend와 Flyway가 함께 시작되므로 우선 **Direct connection**을 사용합니다. Pooler는 이번 무료 구성에 추가하지 않습니다.
6. 비밀번호를 로컬 문서나 Git에 저장하지 말고 Render의 Secret 환경변수에만 입력합니다.

Neon 문자열이 `postgresql://USER:PASSWORD@HOST/DB?sslmode=require`라면 Render에는 다음처럼 나눠 입력합니다.

| 변수 | 값의 형태 |
|---|---|
| `DB_URL` | `jdbc:postgresql://HOST/DB?sslmode=require` |
| `DB_USERNAME` | `USER` |
| `DB_PASSWORD` | Neon role 비밀번호 |

운영과 Preview를 같은 DB에 연결하지 않습니다. 별도 Preview DB를 만들지 않는 동안 Vercel Preview에는 `API_BASE_URL`을 설정하지 않습니다.

## 3. Render Free Web Service

저장소가 공개된 뒤 Render Dashboard에서 **New → Blueprint**를 선택하고 GitHub의 `wogjs0808coder/pitflow`를 연결합니다. `render.yaml`은 다음을 고정합니다.

- Backend Docker build context와 기존 Temurin 17 noble Dockerfile
- Free plan, Singapore 리전, `main` 브랜치
- GitHub 검사가 성공했을 때만 자동 배포
- 프로세스 생존 검사 `/api/health`
- 운영 프로필, secure 세션 쿠키

최초 Blueprint 생성 중 `sync: false` 변수 값을 묻는 화면에서 아래 값을 입력합니다. 이미 생성한 서비스라면 **Environment** 화면에서 직접 추가합니다.

| 변수 | 설정 위치 | 시점 | 비밀 |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | `render.yaml` | 실행 | 아니요 |
| `COOKIE_SECURE=true` | `render.yaml` | 실행 | 아니요 |
| `DB_URL` | Render Environment | 실행 | 접속 주소를 비공개 취급 |
| `DB_USERNAME` | Render Environment | 실행 | 예 |
| `DB_PASSWORD` | Render Environment | 실행 | 예 |
| `PITFLOW_ADMIN_EMAIL` | Render Environment | 최초 시작 | 예 |
| `PITFLOW_ADMIN_PASSWORD` | Render Environment | 최초 시작 | 예 |

`PITFLOW_ADMIN_PASSWORD`는 12~64자의 무작위 값을 사용합니다. 관리자 생성 후 환경변수를 바꿔도 기존 비밀번호는 자동 변경되지 않습니다.

배포 로그에서 다음을 확인합니다.

- Flyway가 V1부터 최신 migration까지 성공
- `Started PitflowApplication`
- `/api/health` → `200 {"status":"UP"}`: Java 프로세스 생존
- `/api/health/ready` → `200` 및 `database=UP`: 요청 시점의 DB 연결 가능

Render health check에는 `/api/health`를 사용합니다. `/api/health/ready`를 주기적으로 호출하는 keep-alive를 만들지 않습니다. DB가 잠들 수 있는 무료 정책을 그대로 따릅니다.

메모리 기본값은 Java heap 256MB, metaspace 128MB, Tomcat 최대 24 threads, Hikari 최대 4 connections입니다. 이는 전체 512MB 안에서 native memory 여유를 두기 위한 시작값이며 실제 Render Metrics와 OOM 로그에 따라 낮춥니다.

## 4. Vercel Hobby

1. Vercel Dashboard에서 **Add New → Project**로 GitHub 저장소를 가져옵니다.
2. 개인 Hobby scope를 선택하고 상업 서비스로 사용하지 않습니다.
3. **Root Directory**를 `frontend`로 지정합니다.
4. Framework Preset은 Next.js, Build Command는 기본 `npm run build`를 사용합니다.
5. Production 환경에만 `API_BASE_URL=https://실제서비스.onrender.com`을 추가합니다. 끝에 `/api`를 붙이지 않습니다.
6. `main`의 Backend 배포와 상태 확인이 끝난 뒤 Production deploy를 실행합니다.

`API_BASE_URL`은 비밀이 아니지만 Next.js rewrite를 정하는 **빌드 시점 변수**입니다. Vercel 빌드에서 누락되거나 HTTPS가 아니면 빌드를 실패시켜 localhost 오접속을 막습니다. Preview에 이 값을 두지 않으면 Preview 빌드는 의도적으로 실패합니다. Preview가 필요해질 때 별도 Backend와 별도 Neon branch를 먼저 만든 뒤 해당 Preview 전용 URL을 설정합니다.

## 5. Cold start와 동일 출처 보안

브라우저는 계속 Vercel의 동일 출처 `/api`만 호출합니다. Next.js가 Render로 rewrite하며 브라우저에 Backend 주소나 서버용 비밀을 주지 않습니다.

- GET/HEAD만 1.5초, 3초, 6초 간격으로 제한 재시도합니다.
- 502/503/504, 네트워크 실패, JSON이 아닌 성공 응답을 준비 중 상태로 처리합니다.
- POST/PATCH/DELETE는 자동 재시도하지 않습니다.
- 페이지 이탈 시 `AbortSignal`로 대기와 요청을 중단합니다.
- 인증 401과 연결 실패를 다른 문구로 표시합니다.
- API 응답은 `private, no-store`로 공유 캐시를 막습니다.
- 운영 쿠키는 `Secure`, `HttpOnly`, `SameSite=Lax`이고 CSRF 보호를 유지합니다.

세션 저장소는 현재 Spring Boot 프로세스 메모리입니다. Render의 중지·재시작·재배포로 프로세스가 교체되면 사용자는 다시 로그인해야 합니다. 포트폴리오 무료 범위에서는 이를 화면 안내와 시연 문서에 명시하며, 무상태 인증으로 바꾸거나 별도 유료 세션 저장소를 추가하지 않습니다.

재고·수납 등 idempotency 업무 요청은 응답이 불명확하거나 401/403/429이면 계정 ID, 같은 요청 키와 본문을 `sessionStorage`에 보존합니다. 같은 사용자가 재로그인한 후에만 다시 확인할 수 있습니다. 다른 계정의 요청은 실행하지 않습니다. 목록과 이력에서 결과를 직접 확인한 경우에만 화면의 명시적 삭제 버튼으로 보류 요청을 지웁니다.

## 6. 배포 확인표

Vercel 주소 하나만 브라우저에서 사용합니다.

1. 첫 접속에서 준비 중 안내와 다시 시도 버튼 확인
2. 회원가입 → 고객 로그인 → 차량 등록 → 예약 → 로그아웃
3. 관리자 로그인 → 예약 조기 입고 → 정비사 배정 → 부품 사용 → 완료
4. 명세 발행 → 현장 수납 → 출고 → 고객 이력
5. `/api/health`와 `/api/health/ready`의 의미가 다른지 확인
6. 응답 헤더의 세션 `Secure; HttpOnly; SameSite=Lax`, API `Cache-Control` 확인
7. 모바일 폭에서 로그인과 주요 폼 확인

로그인 상태가 유지되지 않으면 Vercel Function Logs에서 rewrite 오류를, Render Logs에서 세션·CSRF 응답을 확인합니다. `API_BASE_URL` 변경 뒤에는 반드시 새 Vercel deployment를 빌드합니다. DB 오류는 Render 로그의 JDBC/Flyway 메시지와 Neon Dashboard의 role, database, SSL URL, 사용량 한도를 확인합니다.

운영 DB 삭제·복원은 이 절차에서 수행하지 않습니다. 로컬 `scripts/backup-db.ps1`은 Docker PostgreSQL용이며 Neon 운영 백업 절차를 대신하지 않습니다.
