# 4단계 검증 — 2026-09-13

## 실행 완료

- backend 전체 `mvn test`: 51개, 실패 0, 오류 0, 건너뜀 0. H2 PostgreSQL 호환 모드.
- 1단계 9개, 2단계 13개, 3단계 21개 regression 통과.
- 기존 V3→V4 업그레이드 1개, V4→V5 업그레이드 1개 통과.
- 4단계 6개 통합 테스트: 소수 금액·스냅샷, 동일 키 재전송·다른 사용자 키 거절, 단가 0원 승인, DB 실패 후 명세·항목·키 롤백, 전액 수납·취소·명세 재발행, 반환 후 오래된 명세의 수납 거절, 소유권·관리자·CSRF, 기간 집계, 동시 발행·수납·동일 키 취소.
- frontend `npm run build`: 성공, `/admin/billing`과 `/history` 포함.
- frontend `npm run typecheck`: 성공.
- V1~V3 SQL 및 V4 Java migration에 대한 기준 트리 대비 diff 없음. README 변경 없음.
- `git diff --check`: 통과.

## 검증 범위의 한계

Work에는 Docker 및 native PostgreSQL 실행 도구가 없어 실제 PostgreSQL, Windows Docker, 실제 브라우저 상호작용은 검증하지 않았습니다. 현재 브랜치 GitHub push 및 CI 실행도 수행하지 않았습니다. PostgreSQL CI와 사용자 PC 시연은 로컬 패치 적용 후 필요합니다.

패키지까지 확인하려고 실행한 offline `mvn verify`는 캐시에 없는 maven-jar-plugin 때문에 실행되지 않았습니다. 그 후 필수 backend 전체 `mvn test`는 캐시된 의존성으로 51개 모두 통과했습니다. backend JAR 패키징 성공을 주장하지 않습니다.

## 기준과 전달

GitHub main `7e12b9a`의 트리 `0df9badb9aa573caee10c2be0c4d5540ea661f94`와 Work 기준 HEAD의 트리가 일치합니다. Work 기준 커밋 `36d8170`은 로컬에서 구성된 커밋이므로 실제 GitHub 커밋과 SHA가 다릅니다. 과거 4단계 변경을 이 트리에 적용해 검토했습니다.

`PHASE4-APPLY.md`의 절차는 실제 origin/main을 fetch하고 7e12b9a를 확인한 뒤 새 브랜치에 이번 패치만 적용합니다. Work에서 생성된 기준 커밋 이력은 전달하지 않습니다.
