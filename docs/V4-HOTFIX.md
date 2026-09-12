# V4 PostgreSQL 시작 실패 수정

사용자 로그에서 PostgreSQL 17.11의 V4가 `Expected V3 movement kind and link checks`로 실패하고 전부 롤백된 것이 확인되었습니다. DB 버전은 3이며 백엔드는 반복 재시작하고 있었습니다.

PostgreSQL information_schema는 NOT NULL도 CHECK로 제공합니다. 기존 코드가 kind 관련 제약조건을 문자열로 골라 2개라고 가정했으나 실제로는 종류 CHECK·연결 CHECK·kind NOT NULL의 3개였습니다.

수정은 PostgreSQL에서 pg_catalog.pg_constraint의 contype='c'와 kind 열을 참조하는 conkey로 정확한 CHECK만 선택합니다. H2 조회는 기존대로 유지하며 NOT NULL·양수 수량 검증은 보존합니다.

V4가 성공 적용되지 않고 롤백된 이 환경을 위한 수정입니다. 실패한 V4 코드와 체크섬을 수정하며 V1/V2/V3는 그대로 둡니다. DB 초기화, Flyway repair, schema history 수동 삭제를 하지 않습니다. 이미 V4가 성공 적용된 별도 환경에 이 수정만 적용하는 절차는 이 문서의 대상이 아닙니다.

## 검증

- H2 PostgreSQL 모드 Maven verify: 44개 테스트 통과.
- V3→V4 후 kind NOT NULL, 알 수 없는 종류 거절, 작업 없이 USE 거절, 음수 수량 거절 검사 추가.
- PostgreSQL 17.5 기반 PGlite 0.3.14: 저장소 V1/V2/V3 SQL과 V4 Java 소스에서 추출한 SQL 실행. 기존 조회 3개로 실패 재현, 수정 조회 2개, CHECK 교체와 NOT NULL 보존 통과.
- PGlite는 WASM SQL 실행 검증이며 PostgreSQL 17.11 서버의 JDBC/Flyway 전체 기동·동시성 검증을 대신하지 않습니다. 이 환경에서는 네이티브 PostgreSQL/Docker 실행이 불가능했습니다.

## 사용자 환경 복구

이전 두 패치가 적용된 feat/work-orders에서 새 pitflow-v4-hotfix.patch만 한 번 적용합니다. 남은 중복 git am 시도가 있다면 먼저 git am --abort로 종료합니다.

```powershell
cd "$env:USERPROFILE\Desktop\pitflow"
git status
git am "$env:USERPROFILE\Downloads\pitflow-v4-hotfix.patch"
```

위 적용이 성공한 경우에만 실행합니다.

```powershell
docker compose up --build -d backend
docker compose logs -f --tail=80 backend
```

V4 적용 성공과 Started PitflowApplication을 확인하면 Ctrl+C로 로그 보기를 종료합니다. 컨테이너는 계속 실행됩니다. localhost:3000에 다시 접속합니다.

## 실제 PostgreSQL 회귀 테스트

compose.test.yaml은 기존 pitflow 프로젝트와 분리된 pitflow-tests 프로젝트이며 별도 임시 DB를 사용합니다. 사용자 DB 볼륨과 포트를 공유하지 않습니다.

```powershell
docker compose -f compose.test.yaml up --build --abort-on-container-exit --exit-code-from backend-tests
docker compose -f compose.test.yaml down
```

첫 명령의 테스트가 성공했는지 확인합니다. 임시 DB는 정리 시 제거되며 실제 차량·예약 DB는 유지됩니다. 테스트 성공 후 기능 브랜치를 push하여 기존 PostgreSQL 17 CI도 실행합니다.

참고: [PostgreSQL 17 check_constraints](https://www.postgresql.org/docs/17/infoschema-check-constraints.html), [pg_constraint](https://www.postgresql.org/docs/17/catalog-pg-constraint.html).
