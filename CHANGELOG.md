# Changelog

이 프로젝트의 주요 변경을 기록합니다. 버전은 [유의적 버전](https://semver.org/lang/ko/)을 따르며,
릴리스마다 git 태그 `vX.Y.Z` 를 답니다. 실행 중인 버전은 `/mcp` 의 clip-report **serverInfo.version** 으로 확인할 수 있습니다.

## [0.10.0] - 2026-09-10
v0.9.0 에서 미뤘던 ui-f6 요청 3건 + PLAN §3 생성기 개선을 한 번에. 새 코드는 `src/CrfDerive.java`.
### Added
- **`crf_generate` v2 — 목록형 리포트 한 방 파생**: `template` + `sql`(SQL/MyBatis/JS) + `columns=[{field|text,title,width,align,format,total,merge}]` + `title` + `cond`/`cond_right`(공식 또는 텍스트) + `groups=none|auto|A,B` 로 ① 템플릿 정리(그룹 밴드·그룹 객체·그룹이름/그룹인덱스 필드 제거, 누적합산의 그룹 기준 해제, SELECT 에 없는 필드는 셀/글상자 바인딩을 풀고 제거) ② 쿼리 적용(매개변수 선언, 필드 SELECT 순서; `fields_from_db=true` 또는 `SELECT *` 는 DB 메타데이터로 컬럼·타입 확정) ③ 본문 표 + 제목 표 + 합계 표를 columns 로 재구성(본문 표가 없으면 새로 생성) ④ 제목 글상자(머리글에서 가장 큰 정적 글상자)·조건 글상자(공식 바인딩 글상자 왼쪽/오른쪽) ⑤ 그룹 추가(`crf_add_group` 재사용: 머리글 라벨 + 소계 공식, 소계 라벨은 해당 열 위치·너비로 정렬하고 **'소 계' 라벨 자동 추가**) ⑥ 로고: 페이지 바닥글에 서브리포트가 이미 있으면 유지(예전엔 문자열 검색이라 임베디드는 못 봄) ⑦ 머리글 글상자를 본문 열 격자에 맞춤(엑셀 규칙) ⑧ 저장 후 되읽어 `crf_validate excel=true` 결과를 응답에 포함. 실서버 렌더(ahrmhr0240 템플릿 → 부서별 급여 현황, 그룹 DEPT_NM)로 제목·조건·그룹·소계·합계·바닥글 확인. `legacy=true` 면 v1(CrfGen3).
- **`crf_set_columns` — 열 세트 전체 교체**: 본문 표를 columns 로 재구성하고, 같은 열 구조의 제목 표(페이지/데이터 머리글)·합계 표(데이터 바닥글)·그룹 바닥글 표도 같이. 열 수 증감은 `crf_table_cols` 의 삽입/삭제를 재사용해 **기존 열 스타일(글꼴·테두리·정렬)을 상속**, 너비 미지정 열은 남은 너비 균등, 숫자 필드는 오른쪽 정렬, `total` 열은 `F_TOTAL_<필드>`/`F_SUB_<필드>`(그룹 기준) 공식 + 합 계/소 계 라벨. `merge`(셀합치기)는 기본 끔(템플릿이 전 셀 켜 둔 경우가 많아 파생 리포트에서 엉뚱한 병합이 생김). `snap=true` 면 머리글 글상자를 격자에 맞춤. 저장 후 격자 불변식 + 엑셀 격자 검사. 13열×2표 26호출 → 1호출.
- **`crf_append_query_condition`**: 기존 쿼리에 `if('{parameter.P}' != ''){ sql += "…"; }` 블록 하나 추가. 기본은 ORDER BY 앞(한 줄에 WHERE…ORDER BY 가 붙어 있으면 줄을 나눔), `position=end|after:<문자열>`(기준 줄이 if 안이면 그 블록이 닫힌 뒤). 평문 SQL(NotScript)은 JS 로 변환, `return sql;` 보장, `:p/#{p}` 정규화, 매개변수 선언, 저장 후 검증 + 삽입 부근 표시. 파이썬으로 raw 를 붙이다 `\r\n` 이스케이프가 깨져 0건 나던 문제의 대안.
- **MyBatis `<foreach>`·`<trim>`·`<set>`**: `<foreach collection=deptList item=d open="(" separator="," close=")">#{d}</foreach>` → `var __fe1 = ('{parameter.DEPTLIST}'=='')?[]:'{parameter.DEPTLIST}'.split(','); for(...){ if(i>0) sql+=","; sql += "'" + __fe1[i] + "'"; }` (매개변수는 `A,B,C` 처럼 쉼표 문자열로 전달, 경고로 안내; `${item}` 은 따옴표 없이). `<trim prefix="WHERE" prefixOverrides>` → `WHERE 1=1`, 그 외 prefix/suffix 는 붙이되 overrides 는 경고. `crf_get_query` 평문 복원이 `var`/`for` 를 `/*FOREACH*/` 로 표시.
- 서버 instructions `[★목록형 리포트는 crf_generate 한 방]`, README/GUIDE/`/clipreport` 도구 매핑, `tools/smoke.py` 265 케이스(+29).
### Fixed
- `crf_remove_field`/그룹 정리에서 **그룹인덱스 필드 목록을 안 봐서** 지운 그룹이 참조로 남던 문제(`removeFromLists` 에 FieldGroupIndex 추가).
- `crf_sync_fields mode=db` 의 DB 메타데이터 부분을 `dbMeta()` 로 분리(생성기와 공유).

## [0.9.0] - 2026-09-10
workspace/ui 세션(ahrmhr 리포트 5종 작업)의 사용 피드백을 반영한 릴리스. 원인을 코드/실파일로 확인한 것만 고쳤고, 재현되지 않은 지적은 아래 "확인 결과" 에 적었다.
### Fixed
- **필드는 이름이 아니라 위치로 SELECT 컬럼에 매핑** — 실파일 조사: 디자이너가 만든 리포트는 `FieldData.index` 가 전부 0 이고 목록 순서가 곧 컬럼 위치. `crf_set_query sync_fields=add/replace` 가 새 필드를 뒤에 붙여 "사번 칸에 부서명" 이 찍히던 문제 → 기본(`reorder=true`)으로 **SELECT 순서로 재정렬**하고 응답에 `↕` 표시. 별칭 없는 식 컬럼은 건너뛰지 않고 **`COL_n` 자리 필드**를 만들어 뒤 컬럼 위치를 지킨다(AS 별칭 주면 그 이름으로). SELECT 에 없는 필드는 끝으로 밀며 "값이 비게 됨" 경고. `crf_sync_fields` 도 동일(`reorder`).
- **`crf_remove_field` 가 매개변수와 동명인 데이터필드를 못 지우던 버그** — 저장 후 검증이 이름만 보고 매개변수 EMPNO 를 잡아 "필드가 남아 있음" 으로 실패. 종류(nsOf)까지 맞는 필드만 검증하고, 동명 필드가 남아 있으면 ℹ 로 안내.
- **`crf_set_cell` 출력양식을 지울 수 없던 문제** — `format=''`/`none`/`general` 또는 `clear_format=true` 로 제거(되읽어 검증). 예전에는 빈 값은 무시, `General` 은 문자 그대로 저장돼 렌더에 `{General}` 이 남았다.
- **`text=` 로 정적 텍스트를 넣어도 필드 바인딩 포인터가 남아** describe 에 여전히 `데이터:X` 로 보이던 문제 — 텍스트/clear 시 바인딩을 해제하고 응답에 `(바인딩 X 해제)` 표시.
- **MyBatis → JS 변환**: `isValid(x)`/`!isValid(x)`/`isEmpty`/`isNotEmpty`/`isBlank`/`@Utils@isNotEmpty(x)`/`"Y".equals(x)`/`x == 'A'`/작은따옴표 `test='…'` 를 `'{parameter.X}' != ''` 등으로 변환(전에는 `if(isValid(jbfmCd))` 그대로 나와 실행 불가). 태그 인식을 MyBatis 태그 화이트리스트로 바꿔 **본문의 `<=`, `<`, `<>` 비교연산자를 태그로 먹지 않음**(전에는 "unhandled tag: <= TO_DATE…" 와 함께 조건이 통째로 사라짐). `&lt;`/`&gt;`/`&amp;` 엔티티 복원, `<!-- -->` 주석 무시, `<include>`/`<bind>` 는 경고. 변환 결과 끝에 **`return sql;` 자동 추가**.
- `crf_set_paper orientation=` 이 방향 플래그만 바꾸고 **용지 가로/세로 크기를 교환하지 않던 문제**(디자이너는 2970x2100 으로 저장) → 교환하고 본문 너비를 응답.
- `crf_table_cols`/`crf_set_table` 의 "표가 용지 본문 너비를 넘음" 경고가 **한 번도 안 뜨던 버그**(존재하지 않는 `getPageInfo` 를 찾아 항상 0) 수정.
### Added
- **`in_place=true`** — 모든 쓰기 도구에서 원본에 직접 쓰기(`output` 생략 가능). 첫 쓰기 때 `<path>.bak` 에 원본을 백업하고 이후엔 유지. a/b 파일을 번갈아 쓰다 한 단계가 실패하면 다음 단계가 한 단계 전 파일을 읽어 변경이 조용히 유실되는 사고의 대안. `tools/mcpcall.py` 는 요청을 하나씩 보내고 **첫 isError 에서 중단(기본)**, `--continue-on-error` 로 예전 동작.
- **`crf_reorder_fields`**(`order=query`|`A,B,C`) — 필드 목록 순서만 고치는 도구(바인딩은 객체 참조라 그대로).
- **`crf_set_cell cells=[{row,col,table?,…}]`** — 여러 셀(여러 표)을 한 호출로. 최상위 인자(table/font/align…)는 기본값, 항목 인자가 우선. 저장·되읽기 검증 1회. 13열×2표=26호출 → 1호출.
- **JS 동적쿼리 `return` 누락 감지** — `crf_set_query` 경고 + `crf_validate` ✖ (저장소 JS 쿼리 1,491개 전부 return 있음을 확인).
- `crf_remove_param`/`crf_remove_section` **`ignore_missing=true`**(없으면 no-op OK) · 없을 때 오류에 **현재 매개변수/밴드 목록**을 붙임.
- **`crf_set_paper fit=true`** — 본문 너비가 바뀌면(세로↔가로) 모든 밴드의 표(열 너비 비례 배분)·글상자·선의 X/너비를 비례 조정(1500→2370→1500 왕복 시 표 너비 1460 정확 복원). fit 없이 바꾸면 ℹ 로 안내.
- **`crf_describe_layout detail=true` 압축** — 표 행마다 가장 흔한 스타일을 `«공통: …»` 로 한 번만 쓰고 다른 셀만 `«…»`; **`one_per_line=true`** 면 셀마다 `[r,c]` 한 줄. 글상자 줄의 `폰트=` 중복 표기 제거, 열 표시 상한 20→60(`…(+N열)`).
- 자동 선언 매개변수의 타입을 디자이너와 같은 **없음(Null)** 으로(전에는 String 이라 crf_summary 에서 새 것만 `:String` 이 붙어 헷갈림).
- **엑셀 격자 lint 확장 + `crf_validate excel=true`** — 예전엔 머리글/바닥글 밴드의 3열 이상 표만 봤는데, 이제 엑셀로 내보내는 모든 밴드(페이지 바닥글 제외)의 **표 열 경계·글상자/이미지/서브리포트의 좌·우·선의 x** 를 본문 표 열 경계(±1mm)와 대조하고, 요약(본문 경계 N개 + 어긋난 세로선 M개 → 엑셀 열 최대 N+M)과 요소별 어긋난 변·가까운 경계를 제시. `excel=true` 면 ⚠, 아니면 ℹ. 서버 instructions `[★엑셀 출력 격자]`(사용자 규칙: 엑셀로 내려받는 목록형은 세로선을 본문 경계에 맞춰 셀 분할 최소화, 바닥글은 엑셀에서 빠지므로 제외) 내장. ahrmhr0120_prn01 에서 사용자가 맞춘 글상자3(1900)은 통과, 글상자2 오른쪽 620 만 지적됨을 확인.
- Codex 리뷰(13건) 재검토 후 반영: SELECT 중복 컬럼명은 `COL_n` 자리로 위치 보존 · `crf_reorder_fields order=query` 는 필드 없는 컬럼이 있으면 저장 전에 거부(→ crf_sync_fields) · `cells=[]` 같은 셀 중복 항목 거부 · `clear_format` 이 `format` 보다 우선(검증도 동일 규칙) · `cells` 스키마 type=[array,string] · MyBatis `<if test="cnt > 0">` 처럼 속성 안의 `>` 처리 + `cnt > 0` 숫자 비교 변환 · `<choose>/<when>/<otherwise>` 를 if / else if / else 로(전에는 독립 if 라 여러 분기 동시 실행) · `hasReturn` 이 `{ return sql; }` 같은 한 줄 형태도 인식(문자열·주석 제거 후 토큰 검사) · `crf_remove_field` 검증을 원래 데이터셋 안에서만 · `scaleTo` 합이 목표를 넘지 않게 · `mcpcall.py` initialize/list 실패를 오류로, 자식 프로세스 정리(finally). 미반영: 조건식 문자열 리터럴 안의 `'`·and/or 이스케이프(드묾).
- `samples/document/render_report.cjs` 에 뷰포트 폭 인자(5번째)/`CLIP_RENDER_WIDTH` — 가로 리포트 오른쪽 잘림 대응.
- 서버 instructions 에 `[★필드는 위치 매핑]`·`[★JS 동적쿼리는 return 필수]`·in_place/배치 중단/ignore_missing 규칙 내장. `tools/smoke.py` 236 케이스(+49, ahrmhr0240_prn01 을 E 로, ahrmhr0120_prn01 을 F1 로 추가).
### 확인 결과(재현 안 됨)
- `crf_remove_section section=DataFooter` "not found" — 영문 이름은 원래 동작함(ahrmhr0240 에서 확인). 그 파일에 데이터바닥글이 이미 없었을 가능성이 큼(a/b 교대 중 한 단계 전 파일을 읽은 경우). 이제 오류에 현재 밴드 목록을 붙여 바로 알 수 있음.
- 다른 .crf 헤더 바이트가 바뀌는 현상 — 리포트 서버가 열어 본 파일이라 MCP 밖의 일. 미반영.
### 미반영(다음 버전 후보)
- `crf_append_query_condition`(JS 쿼리에 if 블록 추가) · "목록형 리포트 파생" 한 방 도구(템플릿+SQL+열 정의) · 열 세트 전체 교체 — PLAN §3 생성기 개선(v0.10)에서 함께 다룸.

## [0.8.0] - 2026-09-10
### Added
- **표 구조 편집(CRUD) 전면 구현** — CLIP SDK 에는 행/열 삽입·삭제 API 가 없어 `TableRow`/`TableColumn` 리스트와 행·열별 셀 리스트를 직접 재구성하는 `src/CrfTableOps.java` 추가. 모든 도구가 편집 전/후에 격자 불변식(행·열 셀 수, 행/열 참조, 병합 자리↔기준 셀 span, 기준 인덱스)을 검사하고 저장 후 되읽어 다시 검증.
  - **`crf_table_rows`** `action=insert|copy|delete|move|resize|equalize`: 행 **중간 삽입**(`at` 또는 `row`+`position=before|after`, `count`, `height`; 위 행의 구조·스타일을 물려받고 가로 병합은 복제, 삽입 지점을 가로지르는 세로 병합은 span 이 늘어남) · **복제**(내용·바인딩까지) · **삭제**(`'1,3'`/`'2-4'`; 병합 기준 셀을 지우면 바로 아래 셀로 승격, 병합 자리를 지우면 span 감소) · **이동**(세로 병합 구간은 거부) · **높이 조정**(`heights='60,80,…'` 전체 / `'1:80,3:40'` 인덱스:값 / `row`+`height`) · **모든 행 같은 높이**(`height` 없으면 총 높이 유지). `shift=true`(기본) 면 표 아래 요소를 높이 변화만큼 옮기고 표를 감싸는 글상자(배경 상자)는 늘리며 밴드 높이도 맞춘다(줄일 땐 내용 아래로는 안 줄임).
  - **`crf_table_cols`** 같은 6개 action 의 열 버전(`widths`, `width`, 가로 병합 연장/승격). 표가 용지 본문 너비를 넘으면 ⚠ 안내. `shift` 기본 false.
  - **`crf_set_table`**: 표 위치(`left/top`), **전체 너비/높이**(열·행 비례 조정), 외곽선 `border`, **모든 셀 테두리 일괄** `cell_border=true|false|left,top…` + `linewidth`/`linecolor`, `auto_merge`, `keep_together=None|Row|Table`, `visible`, **`unmerge_all=true`**(표 전체 병합 해제), `name`(이름 변경).
  - **`crf_table_info`**: 표 하나의 격자 — 위치·크기, 열 너비/행 높이 목록(합계), 셀별 내용과 병합 span `(r×c)`/`‹병합←r,c›`, `detail=true` 면 글꼴·정렬·배경·테두리(좌우상하), 격자 일관성 검사 결과. 편집 전 인덱스 확인용.
  - **셀 테두리 변별**: `crf_set_cell`/`crf_set_cell_style` 에 `border=true|false|left,top…`(나머지 변은 끔), `linewidth`, `linecolor`.
- 서버 instructions `[★레이아웃 수정/검증]` 에 표 구조 도구 경로(`crf_table_info → crf_table_rows/cols → crf_set_table`) 내장, GUIDE/README/문서형 레시피/`/clipreport` 에 반영. `tools/smoke.py` 187 케이스(병합 연장·승격·복제·이동·크기·균등·표 속성·테두리·오류 31개 추가).

## [0.7.3] - 2026-09-09
### Added
- **XPath 검색/읽기**: `crf_search` 에 `scope=xpath`(`query`/`any` 에도 포함) — XML/JSON 데이터셋의 루트 XPath(`DataAccessMethodXML/JSON.getRootPath`)·필드별 경로(`FieldData.getXMLPath`)·저장 프로시저명을 검색. `crf_get_query` 는 SQL 이 아닌 데이터셋에 "(SQL 데이터셋 아님)" 대신 루트 XPath 와 필드 경로를 출력. 이로써 클립소프트 유틸 **FindQuery(클립유틸 문자열찾기: 쿼리·XPath·보고서 텍스트 검색)** 의 기능을 모두 포함하고, 정규식·범위(field/formula/param/control)·매치 위치까지 더 제공. 저장소 실측: 데이터셋 4,800개 중 XML 22개(파일 7개)에서 루트 `rexdataset/rexrow` 검색 확인. smoke 156.

## [0.7.2] - 2026-09-09
### Changed
- **참조 양식은 요소 전부를 좌표까지 판독** 규칙(`[★참조 양식은 요소 전부를 좌표까지 판독]`, 슬래시 명령 1-1 절, GUIDE 9-1-1, 레시피): HWPX/PDF/DOCX/HTML/이미지 어떤 형식이든 모든 요소의 x,y,너비,높이·정렬·글꼴·색·테두리·데이터 자리를 인벤토리한 뒤 배치하고, 형식별 추출 방법(parse_hwpx.py / PyMuPDF bbox / document.xml / getBoundingClientRect / 이미지 비율)과 "빠진 요소가 있으면 미완성, 끝에 렌더를 원본과 요소별 대조" 를 명시.
- **디자인 결정은 사용자에게 묻기** 규칙을 서버 instructions(`[★디자인 결정은 사용자에게 묻기]`)·`/clipreport` 슬래시 명령(2-1 절)·GUIDE 9-2·문서형 레시피에 추가: 새로 만들거나 크게 고칠 때 요소 구성(표 하나/글상자 여러 개)·체크박스 모양·레이아웃(양식 없이 새로 만들 때)·글꼴·쿼리·저장 위치 같은 갈림길은 임의로 정하지 말고 **작업 전 결정 목록(3~6개, 권장안 포함)을 한 번에 제시**하고, 중간에 새 갈림길이 나오면 그 시점에 다시 묻는다. 사용자가 이미 지정했거나 "알아서" 한 항목만 생략.

## [0.7.1] - 2026-09-09
### Added
- **`crf_merge_labels`**: 같은 밴드에 세로로 놓인 글상자 여러 개를 **요소 하나로** — `into=table`(기본) 은 1열 표(글상자마다 행; 값/필드 바인딩·글꼴·정렬·줄바꿈·줄간격·여백 유지, 행 높이는 원래 세로 자리를 그대로 채워 다른 요소가 밀리지 않음), `into=label` 은 정적 텍스트를 줄바꿈으로 이어 붙인 글상자 하나(원래 줄바꿈 없던 한 줄 글상자는 너비에서 접힐 수 있어 ⚠ 안내 — 표 병합 권장). 원본 글상자는 제거, 저장 후 되읽어 검증. 실서버 렌더로 원본과 동일 확인(ssrmet0230_prn02).
- **`crf_split_label`**: 반대 방향 — 줄바꿈 글상자를 줄별 글상자로(`lines`/`heights` JSON 으로 조각·높이 지정 가능), 1열 표를 행별 글상자로(바인딩·스타일 유지). "나눌 때 나누고 붙일 때 붙이는" 두 도구가 한 쌍.
- **`crf_add_table rows=`**: 문단·번호 목록·※주석 같은 **연속 텍스트 줄을 1열 N행 표 하나**로 생성(`rows` = 문자열 배열 또는 `{text|field, align, bold, height, wrap, cangrow}`), `width`/`border`/`wrap`/`align`. 글상자를 줄마다 따로 만드는 비효율의 대안.
- **`crf_set_font`**: 글꼴 **일괄** 적용 — `font`(글자/라벨), `data_font`(필드 바인딩 데이터; 기본 = font), `size`, `only_system=true`(SDK 기본 `System` 글꼴만 교체), `section` 한정. 전/후 글꼴 사용 통계를 응답.
- **글꼴 자동 상속**: `crf_add_label` / `crf_add_table` / `crf_place_detail_fields` / `crf_add_group` 라벨이 만드는 새 글상자·셀은 SDK 기본 `System` 9pt 대신 **리포트의 지배 글꼴(라벨/데이터 각각 집계)** 을 물려받고, 리포트에 글꼴이 없으면 같은 폴더 이웃 리포트(최근 8개) 관례 → 그래도 없으면 돋움체. 응답에 `(상속:근거)` 표시. `font`/`fontsize` 를 직접 주면 그 값이 우선.
- **`crf_validate` 규칙 추가**: ⚠ `System`(SDK 기본) 글꼴 위치 목록 · ⚠ 같은 스타일(x·너비·글꼴·크기·굵게·테두리)로 세로 연속인 정적 글상자 묶음(→ `crf_merge_labels` 안내; 필드 바인딩이 섞이면 ℹ) · ℹ 라벨 글꼴 ≠ 데이터 글꼴 · ℹ 글꼴 여러 종 섞임(주 글꼴과 소수 글꼴의 위치) · ℹ 엑셀 격자(머리글/바닥글 밴드 3열 이상 표의 열 경계가 본문 표 경계 ±10 에 없으면).
- `crf_set_subsection repeat=None|OnPage|OnColumn|OnPageAndColumn`: 그룹 머리글 매 페이지 반복 설정/해제.
- `align` 에 `Both`(양쪽)·`Equal`(배분) 허용(`crf_set_cell`/`crf_set_label`/`crf_add_label`/`crf_add_table`).
- 서버 instructions 에 **[★요소 구성 원칙]**(연속 문단은 요소 하나로, 나눌 때/붙일 때 기준) · **[★글꼴 관례]**(상속 규칙, 저장소 관례 돋움체/바탕체/나눔고딕, 라벨·데이터 통일) · **[★화면→인쇄물 옮길 때]**(빨간 강조·버튼 문구 제외, 체크 모양은 문서마다 지시, 엑셀 격자, 그룹머리글 반복) 추가, **[★쿼리 파라미터]** 에 TO_DATE 먼저·디자이너 데이터셋 통째 교체 금지 추가 — 로컬 메모리에만 있던 규칙을 서버에 내장해 **어느 PC 에서 연결해도 동일하게 적용**.
- `tools/smoke.py` 152 케이스(ssrmet0230_prn02 를 4번째 실 리포트 D 로 추가).

## [0.7.0] - 2026-09-07
### Added
- **`crf_set_cell_checkbox`**: 표 셀을 CLIP **기본 체크박스**(셀 내용=체크박스)로 바꾸고 **참/거짓 조건**(`field` `operator` `true_value` / `false_value`, `Between` 은 `true_value2`)을 건다. 체크 모양 `check_type=Rectangle(색칠, 기본)|V|Ellipse|RoundRectangle`, 상자 `shape`, `color`, `size`, `default`, 되돌리기 `off`. 저장 후 되읽어 셀 내용·조건 필드를 검증. 조건 없는 체크박스는 항상 빈 상자라는 점을 도구 설명/서버 instructions 에 명시.
- **`crf_merge_cells`**: 기준 셀을 `rowspan`×`colspan` 으로 **병합**(덮이는 자리는 TableCellDumy → describe 의 ‹병합›), 1×1 이면 **해제**(복구 셀은 기준 셀 글꼴/테두리 복사). 다른 병합에 걸린 셀/표 범위 초과는 거부.
- `crf_set_cell` / `crf_set_label`: **`color`(글자색 #RRGGBB), `underline`, `italic`, `linespace`(pt), `padding`("l,t,r,b" 0.1mm)**. `crf_set_label`: **`border`(글상자 사각 테두리), `linewidth`**.
- **`crf_add_label` v2**: `formula` 바인딩 + 위 스타일 옵션 + `border/linewidth` 를 생성 시 한 번에. 새 글상자는 디자이너 기본처럼 테두리 없음·투명 배경으로 초기화.
- `crf_describe_layout`: 체크박스 셀을 `☐체크박스(모양)[필드 연산 값]` 으로 표시, 조건이 없으면 `⚠조건없음(항상 빈 상자)`.
- 서버 instructions: [★체크박스](글자 대신 기본 체크박스) · [★문서형(양식) 리포트](좌표 0.1mm·색·줄간격·병합·테두리·정렬 대조) 항목 추가.
- 문서/샘플: **[docs/document-report-recipe.md](docs/document-report-recipe.md)** — HWPX/PDF 양식 → 문서형 리포트 제작 레시피(단위·색·글꼴, 표/대각선, 체크박스 조건, 공식 패턴, 실서버 렌더 검증, 정렬 체크리스트, SDK 직접 생성). `samples/document/` — `parse_hwpx.py`(HWPX 문단/표/도형/정렬/세로위치 덤프), `render_report.cjs`(CDP Chrome 으로 callReport.jsp 렌더 스크린샷), `SampleDocumentReport.java`(SDK 로 양식 리포트 전체 생성 예제, 체크박스/병합/테두리 헬퍼 포함).
### Fixed
- **`crf_add_table` 로 만든 표의 셀마다 X(대각선)가 그려지던 문제**: SDK 가 새 `TableCellNormal` 의 FDiagona/BDiagona 를 Solid 로 초기화함 → 생성 시 None 으로 설정(표의 분할선/대각선도 None).

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
