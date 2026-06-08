# Changelog

이 프로젝트의 주요 변경을 기록합니다. 버전은 [유의적 버전](https://semver.org/lang/ko/)을 따르며,
릴리스마다 git 태그 `vX.Y.Z` 를 답니다. 실행 중인 버전은 `/mcp` 의 clip-report **serverInfo.version** 으로 확인할 수 있습니다.

## [0.3.0] - 2026-06-08
### Added
- **공식필드(rexpert.*) 지식**을 INSTRUCTIONS + `GUIDE.md` 에 정식화:
  - 필드참조 `rexpert.field("ns.NAME")` (ns=data/system/parameter/formula/runningtotal/parent/dataset)
  - 요약 5인자 `rexpert.sum|avg|count|min|max(범위, "data.COL", 옵션, "그룹|''", "조건식|''")` — 5번째 인자로 **조건부 집계**
  - `prev/next`, `fieldat(...,n)`, `fieldbyint`, `format(값, 패턴)` — 자리수분할 `"=[1-4].[5-6].[7-8]"` 포함
  - **날짜분할 / 동적문장** 실전 패턴 예제
- **열린 파일 주의 규칙**: .crf가 CLIP 앱에서 열린 채 수정 시 잠금/상태 충돌 → 저장→닫기→수정→재오픈.
- **GitHub 설치/업데이트 방법** (`git clone` / `git pull`) 을 README 에 추가.
- **버전 관리**: `VERSION` 상수(serverInfo 노출) + `VERSION` 파일 + 본 CHANGELOG + git 태그.
- **연결 시 자동 업데이트 알림**: `initialize` 때 GitHub `main` 의 `VERSION` 과 비교 → 최신이 있으면 INSTRUCTIONS 로 안내(오프라인 1.5s 타임아웃·무해 / 끄기 `CLIP_MCP_UPDATE_CHECK=0`).

### Changed
- `crf_add_formula_field` 도구 설명의 예시를 실제 호출형(`return rexpert.sum(0,"data.COL",0,"","")`)으로 정정.

## [0.2] - (이전)
- 설명·제안 강화: 분류별 필드 인벤토리, 셀 그리드 바인딩, 개념 INSTRUCTIONS 주입.
- DB/PDF 업무지식 도구, 리포트 생성/수정 도구.
