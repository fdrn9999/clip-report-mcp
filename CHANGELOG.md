# Changelog

이 프로젝트의 주요 변경을 기록합니다. 버전은 [유의적 버전](https://semver.org/lang/ko/)을 따르며,
릴리스마다 git 태그 `vX.Y.Z` 를 답니다. 실행 중인 버전은 `/mcp` 의 clip-report **serverInfo.version** 으로 확인할 수 있습니다.

## [0.6.0] - 2026-08-28
### Added
- **`crf_validate`**(lint): 끊어진 바인딩(없는 필드/빈 바인딩, 셀 좌표까지), 공식 `#unknown#`·없는 필드 참조·`return` 누락, 그룹 필드 null, 머리글/바닥글/그룹 수 불일치, 미선언·미사용 매개변수, 쿼리 SELECT↔필드 불일치, scriptType 불일치, 중복 이름, 숨김 밴드/컨트롤, 링크 서브리포트 파일 없음. 임베디드 서브리포트의 매개변수 링크 대상은 오탐하지 않음.
- **`crf_set_cell` v2**: `formula`(공식필드 자동 생성+바인딩), `clear`, `align`/`valign`, `fontsize`, `bold`, `font`, `wrap`, `cangrow`, `merge`, `bgcolor` 를 한 번에. **`crf_set_label`**: 글상자/컨트롤에 같은 속성 + 위치/크기/표시.
- **`crf_set_subsection`**: 밴드 행 높이·숨김·이름·페이지바꿈(`None|Before|After|BeforeAfter`).
- **`crf_add_table`**: `columns` JSON 으로 **표 생성**(본문 데이터 행 + 머리글 밴드 제목 행, 같은 열 너비; 셀은 TableCellNormal 로 직접 구성해 라운드트립 확인).
- **`crf_add_group` v2**: `level=inner|outer|N` 중첩 위치(머리글/바닥글 대칭 삽입, 그룹 목록 순서 동기), `label=true`, `subtotal=F1,F2`(그룹 기준 `rexpert.sum` 공식 + 바닥글 라벨), `sort`. **`crf_set_group`**(필드/정렬), **`crf_remove_group`**(대칭 밴드·그룹이름 필드 제거; 컨트롤/참조 있으면 거부).
- **`crf_remove_control`**, **`crf_remove_section`**(컨트롤 있으면 거부, 본문 불가).
- `crf_describe_layout detail=true`: 셀/컨트롤별 정렬·폰트·크기·굵게·줄바꿈·확장·셀합치기·조건스타일·배경.
- **`crf_diff` v2**: 데이터셋/필드/쿼리(줄 단위 ±, 앞 6줄)/scriptType/매개변수/공식 스크립트/그룹/섹션/컨트롤(위치·바인딩)/표 셀 그리드 변화.
### Changed
- `crf_set_cell_style` 은 `crf_set_cell` 과 같은 적용기를 사용(정렬/크기/굵게/줄바꿈도 가능).
### Fixed
- 숫자형 JSON 인자(`width`, `row` 등이 문자열이 아닌 숫자로 올 때)가 기본값으로 떨어지던 문제.


### Added
- **`crf_set_query` v2**: 미선언 `{parameter.X}` 를 전역 매개변수로 **자동 선언**(`declare_params`, 기본 true), SELECT 컬럼을 데이터 필드로 **자동 추가**(`sync_fields=add` 기본; `replace` 는 미참조 필드 제거, 참조 중이면 유지+경고; `none`). 별칭 없는 식 컬럼·`SELECT *` 는 안내.
- **`crf_sync_fields`**: `mode=sql`(파싱) / **`mode=db`**(쿼리를 `SELECT * FROM (…) WHERE 1=0` 로 실행, ResultSetMetaData 로 컬럼·타입 확정 — `SELECT *`·함수테이블 해결; JS 동적쿼리는 평문 복원본 실행; `{parameter.X}`/`{dataset.X}` 는 `params` JSON 또는 `''`/NULL 바인딩). `set_types`, `remove_unused`.
- **`crf_add_dataset`** / **`crf_remove_dataset`**: 데이터셋 추가(첫 데이터셋의 연결·접근방식 복제, 쿼리 변환·매개변수 선언·필드 생성) / 삭제(필드 참조 있으면 거부, `force`).
- **`crf_set_param`** / **`crf_remove_param`**: 전역 매개변수 생성·수정(타입/기본값/프롬프트) / 삭제(쿼리 토큰·바인딩·공식 참조 시 거부).
- **`crf_rename_field`** / **`crf_remove_field`** / **`crf_field_refs`**: 이름변경(객체 바인딩 자동 추종, 공식 `"ns.OLD"` 및 쿼리 `{parameter.OLD}` 재작성, 그룹이름 라벨 갱신) / 삭제(참조 목록 제시 후 거부, `force`) / 참조 위치 조회(셀 좌표·라벨·그룹·누적합산·서브리포트 매개변수 링크·공식·쿼리).
- `crf_add_data_field` 에 `dataset` 선택.
### Changed
- 참조 탐색은 섹션→서브섹션→컨트롤→셀 구조를 명시적으로 따라가 정확한 경로(`GroupHeader(→STUDENT_CD)/그룹 머리글1/Table"표2"[3,6].ApplyValueField`)를 보고.


### Added
- **`crf_get_query`**: 데이터셋별 **쿼리 전문** — scriptType·연결·필드·사용 `{parameter.X}`(미선언 표시)·`{dataset.X}` 참조·테이블(추정). JavaScript 동적쿼리는 원문과 **평문 복원본**(문자열 연결을 풀고 `if` 블록은 `/*IF*/…/*END IF*/` 주석)을 함께 제공. `dataset`(이름/인덱스), `mode=both|raw|plain`.
- **`crf_get_formula`**: 공식 스크립트 전문 + 참조 필드 목록(없는 필드·`#unknown#` 끊어진 참조 표시), 누적합산(함수/대상필드/평가·리셋), 그룹이름→그룹필드.
- **`crf_search`**: 폴더 재귀 검색 — `scope=query`(JS 는 평문 복원본 기준)·`field`·`formula`·`param`·`control`(라벨/셀 텍스트·바인딩)·`any`, 부분문자열/정규식, 파일명 `like`, `limit`/`max_files`. 실측 ~5 ms/파일.
- `crf_list_reports`: `like`(부분문자열/`*` 글롭)·`limit`(기본 500)·총 개수.
### Changed
- `crf_summary`: 매개변수 타입/기본값, 섹션마다 **서브섹션**(이름·높이·숨김·유형) 표시, 그룹머리글에 `→그룹필드`. 리포트 서브섹션은 링크 경로/임베디드 여부/매개변수 링크까지.
- `crf_describe_layout`: 서브섹션 단위로 출력(`sub[j]` 줄), **리포트 서브섹션(`SubSectionSubreport`)·서브리포트 컨트롤의 링크/임베디드/매개변수 링크** 표시 — 본문이 서브리포트로만 구성된 리포트에서 "본문이 비어 보이던" 문제(B12) 해소. 숨김 컨트롤 `[숨김]`, 두 번째 컨트롤 리스트는 별도 표기.
- INSTRUCTIONS 에 쿼리 읽기/찾기 도구 안내 추가.


### Fixed
- **영구 "업데이트 있음" 오탐**: jar 의 `VERSION` 상수(0.4.0)와 `VERSION` 파일(0.4.1)이 어긋나 있었음 → 상수 제거, `build.ps1` 이 `VERSION` 파일을 jar 리소스 `/VERSION` 으로 포함(단일 출처).
- **병합된 셀에 `crf_set_cell`/`crf_set_cell_style` 이 "OK" 로 거짓 보고**(`TableCellDumy`) → `ERROR` + 기준 셀 후보 안내. 리플렉션 setter 실패도 더 이상 조용히 삼키지 않음. 행/열 범위 밖도 ERROR.
- **`crf_add_label`/`crf_place_detail_fields` 가 새 `ControlListForEachSeparatedPage` 를 만들어 컨트롤을 넣던 문제** → 기존 첫 리스트 재사용. 컨트롤 이름 중복 시 `_2` 접미.
- **`crf_set_query`**: scriptType 을 안 바꿔 JS 데이터셋에 평문 SQL 을 넣으면 실행 불가하던 문제 → 자동 판별(평문 SQL→NotScript, MyBatis→JavaScript 변환, `var sql`→JavaScript; `script_type` 로 강제 가능). `'#{x}'` 가 `''{parameter.X}''` 로 이중 따옴표 되던 문제 → 문자열 리터럴 인식 치환(리터럴 안의 `:x` 는 건드리지 않음).
- **`crf_summary`** 필드 40개 절단(꼬리 콤마) 제거 — 전체 출력, 타입이 알려진 필드는 `NAME:Type`.
- 공식/데이터 필드 **이름 중복** 및 `return` 없는 공식, 공식 안의 `:col`/`#{}`/`${}` 표기를 거부(`force=true` 로 강행).
### Added
- `crf_set_query` 에 `dataset`(이름/인덱스) 선택, 미선언 `{parameter.X}` 경고, 변환 결과 미리보기.
- `crf_set_cell` 은 저장 후 **되읽어 검증**한 값을 응답에 포함. `crf_describe_layout` 표 셀에 `‹병합›`(숨은 셀)과 `{출력양식}` 표시.
- 도구 실패를 JSON-RPC error 대신 **`ERROR: …` + `isError`** 로 반환(빈 경로/없는 파일/`output`=원본 등 명확한 메시지). 쓰기 도구는 `output` 이 원본과 같으면 거부, 상위 폴더 자동 생성.
- `db_query` **읽기 전용 가드**(SELECT/WITH 외는 `allow_write=true` 필요) + 쿼리 타임아웃(`CLIP_DB_TIMEOUT`, 기본 60s).
- `tools/mcpcall.py`(stdio 직접 호출 하네스), `tools/smoke.py`(실 리포트 회귀 테스트 32건).
- `docs/PLAN-v0.5.md`: 전면 검토 결과와 v0.5~v0.7 로드맵(쿼리 읽기/검색, 데이터셋 동기화, 레이아웃 편집 강화, 생성기).

## [0.4.1] - 2026-06-08
### Added
- **별칭 슬래시 명령**: `/clipreport-version`, `/clipreport-changelog`, `/clipreport-doctor`, `/clipreport-update` — 각각 `--version`/`--changelog`/`--doctor`/`--update` 와 동일 동작. `--` 인자는 Claude Code 가 자동완성 못 하지만 별칭 명령은 **이름으로 탭 자동완성**됨.

### Changed
- `argument-hint` 에 메타 명령 목록(`--help|--version|--changelog|--doctor|--update`) 노출.
- `install.ps1` / `update.ps1` : `setup\clipreport.md` 한 개 → **`setup\clipreport*.md` 전부**(별칭 명령 포함) 복사하도록 변경.

## [0.4.0] - 2026-06-08
### Added
- **`/clipreport --` 메타 명령 세트**: `--help`, `--version`, `--changelog`, `--doctor`, `--update` (대상 `.crf` 없이 동작).
- **`update.ps1`** : 자가 업데이트 — 실행중 서버 종료 → `git pull` → (JDK 있으면)재빌드 → `/clipreport` 슬래시 명령 갱신.

### Changed
- 연결 시 업데이트 알림 문구를 **`/clipreport --update`** 안내로 변경.

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
