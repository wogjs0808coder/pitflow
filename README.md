# PitFlow

자동차 정비 예약 및 부품 재고 통합 관리 시스템 — 졸업작품 프로젝트.

현재는 **1단계: 계정 · 차량 · 정비 항목 관리**를 구현했습니다. 예약, 작업지시서, 부품 재고, 수납은 다음 단계입니다.

## 구현 기능

- 고객 회원가입, 세션 로그인, 로그아웃, 내 정보 조회
- BCrypt 비밀번호 해시, CSRF 보호, 로그인 시 세션 ID 교체
- 본인 차량 등록·목록·수정·삭제, 소유자 권한 검사
- 정비 항목 조회, 관리자 정비 항목 등록·수정·비활성화
- 환경변수 기반 최초 관리자 생성 (기본 비밀번호 없음)
- PostgreSQL + Flyway 스키마 관리
- Next.js 한국어 화면과 반응형 레이아웃
- Docker Compose 및 GitHub Actions 검사 구성

엔진오일·타이어·배터리 교체 3개 항목은 **시연용 공임과 예상 시간**으로 초기화됩니다. 실제 가격표가 아닙니다. 부품 비용은 별도입니다.

## 기술 구성

| 구분 | 구성 |
|---|---|
| 프론트엔드 | Next.js 16.3.4, React 19.3.0, TypeScript |
| 백엔드 | Java 17, Spring Boot 3.5.16, Spring Security, Spring Data JPA |
| 데이터베이스 | PostgreSQL 17, Flyway |
| 인증 | HttpOnly 세션 쿠키 + CSRF 토큰 |
| 자동 검사 | JUnit/MockMvc 통합 테스트, PostgreSQL CI, Next.js 빌드·타입 검사 |

브라우저는 Next.js의 `/api/*` 경로만 호출합니다. Next.js가 Spring Boot로 프록시하므로 별도 CORS 설정과 브라우저 토큰 저장이 필요하지 않습니다.

## 가장 간단한 실행 — Windows / Docker Desktop

먼저 Docker Desktop을 실행하고 Linux 컨테이너 모드를 사용하세요. 아래 경로는 바탕화면에 `pitflow` 폴더를 둔 경우입니다.

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup.ps1
docker compose up --build -d
docker compose logs -f backend
```

`Started PitflowApplication` 로그가 나오면 [http://localhost:3000](http://localhost:3000)을 여세요. 로그 조회는 `Ctrl+C`로 종료해도 컨테이너는 계속 실행됩니다.

- 일반 고객: 화면에서 회원가입합니다.
- 관리자: `.env`의 `PITFLOW_ADMIN_EMAIL`과 `PITFLOW_ADMIN_PASSWORD`로 로그인합니다.
- `setup.ps1`은 임의 비밀번호를 생성하고 기존 `.env`는 덮어쓰지 않습니다.
- `.env`는 Git에서 제외됩니다. 비밀번호를 README·소스·스크린샷에 올리지 마세요.
- 관리자 환경변수는 **최초 생성용**입니다. 이후 값을 바꿔도 기존 계정 비밀번호는 변경되지 않습니다.
- 포트 3000, 8080, 5432는 다른 프로그램이 사용하지 않아야 합니다.
- 기본 포트 바인딩은 본인 컴퓨터에서만 접속할 수 있는 `127.0.0.1`입니다.

```powershell
# 상태 확인
docker compose ps
# 실행 종료. DB 데이터는 유지됩니다.
docker compose down
```

DB 볼륨이 생성된 뒤 `.env`의 DB 비밀번호만 바꾸면 기존 DB 비밀번호와 달라져 접속에 실패합니다. 기존 데이터를 보존하려면 DB 계정 비밀번호를 별도로 변경해야 합니다.

## 개발 중 개별 실행 — Windows

필수: JDK 17, Node.js 22 이상, Docker Desktop. Maven은 Wrapper가 자동으로 준비합니다.

### 1. DB

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup.ps1
docker compose up -d db
```

### 2. Spring Boot — 별도 PowerShell

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1
```

이 스크립트가 `.env`의 필요한 값만 읽고 `backend\mvnw.cmd spring-boot:run`을 실행합니다. Spring Boot 자체는 루트 `.env`를 자동으로 읽지 않습니다.

### 3. Next.js — 별도 PowerShell

```powershell
cd "$env:USERPROFILE\Desktop\pitflow\frontend"
npm ci
npm run dev
```

기본 API 대상은 `http://127.0.0.1:8080`입니다. 변경하려면 `frontend/.env.example`을 `frontend/.env.local`로 복사해서 수정한 뒤 개발 서버를 재시작하세요. 배포 빌드의 프록시 대상은 빌드 시점에 정해집니다.

전체 Docker 실행과 개별 개발 실행을 같은 포트에서 동시에 실행하지 마세요.

## Linux / macOS

Docker가 실행 중인 프로젝트 루트에서:

```bash
bash scripts/setup.sh
docker compose up --build -d
```

`setup.sh`는 Python 3을 사용합니다.

## 검사

```powershell
cd "$env:USERPROFILE\Desktop\pitflow\backend"
.\mvnw.cmd test

cd "$env:USERPROFILE\Desktop\pitflow\frontend"
npm ci
npm run build
npm run typecheck
```

로컬 Java 테스트는 H2 PostgreSQL 모드의 일회용 DB를 사용합니다. GitHub Actions는 별도 PostgreSQL 테스트 DB로 같은 테스트를 실행하도록 구성했습니다. 테스트에 쓰는 계정과 암호는 테스트 전용입니다.

실제 PostgreSQL로 테스트하려면 **운영 DB가 아닌 빈 테스트 DB**에 `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`를 지정해야 합니다. 테스트는 해당 DB의 사용자·차량·정비 항목 데이터를 정리합니다.

## 소스 위치

| 경로 | 내용 |
|---|---|
| `backend/src/main/java/com/pitflow/auth` | 인증, 보안, 관리자 생성 |
| `backend/src/main/java/com/pitflow/user` | 계정 엔티티·저장소 |
| `backend/src/main/java/com/pitflow/vehicle` | 차량 API·업무 규칙 |
| `backend/src/main/java/com/pitflow/catalog` | 정비 항목 API·업무 규칙 |
| `backend/src/main/resources/db/migration` | DB 스키마·초기 데이터 |
| `backend/src/test` | 통합 테스트 |
| `frontend/src/app` | Next.js 화면·레이아웃 |
| `frontend/src/components` | 인증 폼, 화면 틀, 공통 기능 |
| `frontend/src/lib/api.ts` | API 요청과 CSRF 처리 |
| `docs` | API 명세, 설계, 단계별 계획, 검증 기록 |
| `.github/workflows/ci.yml` | GitHub 자동 검사 |

## GitHub에 처음 저장

GitHub에서 새 `pitflow` 저장소를 만드세요. 최초 소스 업로드 전에는 비공개 저장소를 권장합니다. 아래 명령은 **아직 README 등을 넣지 않은 빈 원격 저장소**에 사용합니다.

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"
git init -b main
git add .
git status
git commit -m "feat: implement PitFlow phase one"
git remote add origin https://github.com/wogjs0808coder/pitflow.git
git push -u origin main
```

위 원격 주소는 계획한 저장소명 예시입니다. 실제 만든 저장소 주소에 맞추세요. 이미 원격에 커밋이 있다면 먼저 해당 저장소를 clone한 뒤 프로젝트 파일을 복사하여 커밋하세요. 강제 push는 필요하지 않습니다.

## 다음 단계

[단계별 계획](docs/ROADMAP.md), [설계](docs/ARCHITECTURE.md), [API 명세](docs/API.md), [검증 기록](docs/VALIDATION.md)을 확인하세요.

이 버전은 로컬 개발·졸업작품 시연용입니다. 인터넷 공개 운영 전에는 HTTPS 및 Secure 쿠키, 로그인 요청 제한, 계정 복구, 백업·모니터링 등을 추가로 검토해야 합니다.
