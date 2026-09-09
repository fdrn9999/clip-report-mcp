# CLIP Report Assistant (MCP)

Clipsoft **CLIP report v5.0** 의 `.crf` 리포트를 **Claude로 생성·수정·편집·설명**하는 MCP 서버 + CLI.

Claude에게 "리포트 조작 도구"를 쥐여주면, 사용자는 **자연어로 시키기만** 하면 됩니다:

- **설명** — 리포트의 데이터셋·쿼리·필드·그룹·레이아웃을 읽어 Claude가 풀어서 설명
- **생성 도움** — SQL / MyBatis → 초안 `.crf` (필드·쿼리·파라미터·GROUP BY 그룹밴드·페이지바닥글 공통로고·본문 데이터 컨트롤)
- **수정 도움** — 쿼리 교체, 그룹 추가, 본문 필드 배치 등
- **편집 제안** — Claude가 레이아웃을 읽고 개선점을 자연어로 제안
- **업무지식 + 쿼리 도움** — 화면파일(xfdl/vue)·문서양식(PDF) → 백엔드 역추적 + **DB 도메인테이블 조회**(`db_*` 도구)로 업무지식 확보 → 양식에 맞는 **추천 제안 + 쿼리 작성/변환**

> ⚠️ 바이너리를 직접 조작하지 않습니다. Clipsoft **공식 Java 엔진**(`Rexpert4`)을 사용하므로
> REX30 포맷·DES 암호화·직렬화를 엔진이 보장합니다.

---

## 빠른 사용 — `/clipreport` (Claude Code)

한 줄로 시킵니다. Claude가 의도를 파악해 알맞은 도구(들)를 자동 호출합니다.

```
/clipreport <파일경로 또는 폴더>  "할말"
```
예시:
```
/clipreport C:\report\aactch0400_prn02.crf  "이 리포트 구조랑 표 구성 설명해줘"
/clipreport C:\report\aactch0400_prn02.crf  "금액 칸들 천단위 통화로 바꾸고 그룹 바닥글에 합계 추가해"
/clipreport C:\report                        "급여 관련 리포트 찾아서 학과별 그룹 잡아줘"
```
- 첫 토큰=파일/폴더, 나머지=요청. 쓰기 작업은 `<원본>_edited.crf` 로 **원본 보존**.
- 설치: 아래 [MCP 설정](#mcp-설정) 으로 clip-report 서버 등록 + 슬래시 명령은 `~/.claude/commands/clipreport.md` (동봉).

---

## 동작 원리

```
사용자(자연어) ──▶ Claude (MCP 클라이언트) ──JSON-RPC(stdio | HTTP)──▶ MCP 서버 ──▶ 공식 엔진(Rexpert4) ──▶ .crf
                      ▲ 설명·제안은 Claude의 추론          (CrfMcpServer / CrfMcpHttp)      (읽기/쓰기, 자동 암복호)
                      └──────────────── 결과를 해석해 답변 ◀────────────────────────────────────────┘
```

- **읽기 도구**(요약/레이아웃 설명)로 Claude가 리포트를 "보고" → **설명 & 편집 제안**을 자연어로 생성.
- **쓰기 도구**(생성/수정)는 항상 `output` 을 따로 받아 **원본 비파괴**.

---

## 요구사항

- **CLIP report v5.0 설치** (엔진 jar + 라이선스). 엔진 jar: `C:\Program Files (x86)\Clipsoft\CLIP report v5.0\bin\jar\*.jar`
  → 본 프로젝트는 이 jar들을 **재배포하지 않습니다**. 실행 호스트에 CLIP report가 설치돼 있어야 합니다.
- **JDK/JRE 17+** (빌드는 JDK, 실행은 JRE)

---

## 설치 — GitHub (권장)

저장소: **https://github.com/fdrn9999/clip-report-mcp**

### 신규 설치
```powershell
git clone https://github.com/fdrn9999/clip-report-mcp.git
cd clip-report-mcp
# (DB 도구 쓸 때만) copy .env.example .env  → .env 편집  +  Tibero JDBC jar 준비
./install.ps1 -Target Code      # Claude Code(~/.mcp.json) | Desktop | Print(설정 출력만)
```
`install.ps1` 이 Java/CLIP 경로 자동탐지 → (JDK 있으면) 빌드, 없으면 **동봉된 `clip-report-mcp.jar` 사용** → MCP 설정 + `/clipreport` 명령 설치. 이후 **Claude 재시작 → `/mcp` 에서 clip-report 승인/확인**. **연결이 안 되면 → 아래 [`doctor.ps1` 진단](#설치연결-문제--doctorps1) 을 먼저 돌리세요.**

### 업데이트 (이미 설치한 사람)
**가장 쉬운 방법 — Claude 에서 `/clipreport --update`**: 서버종료→`git pull`→재빌드→슬래시명령 갱신까지 자동. 끝나면 `/mcp` 재연결만 하면 됩니다.

수동으로 하려면:
```powershell
cd clip-report-mcp
git pull                         # 최신 소스 + 동봉 jar 갱신
./build.ps1 -Jdk "<JDK>\bin" -Clip "<CLIP>\bin\jar"   # JDK 있으면 새로 빌드(권장). 없으면 git pull 받은 jar 그대로 사용
# 그다음 Claude 재시작 또는 /mcp 재연결  → 새 INSTRUCTIONS(개념·규칙) 적용
```
> ⚠️ **빌드/교체 전에 Claude(=MCP 서버 java 프로세스)를 종료**하세요. 실행 중이면 `clip-report-mcp.jar` 가 잠겨 갱신이 조용히 실패합니다(`build.ps1` 은 jar를 잠근 java 프로세스를 자동 종료함).
> ⚠️ 변경은 **재연결/재시작 후** 적용됩니다 — 실행 중인 세션에는 자동 반영되지 않습니다(INSTRUCTIONS 는 `initialize` 때 주입).

### 설치·연결 문제 → `doctor.ps1`
`/mcp` 에서 clip-report 가 안 잡히거나 "서버 연결 불가" 면 **먼저 doctor 를 돌립니다**:
```powershell
cd clip-report-mcp
./doctor.ps1            # 필요시 -Java / -Clip / -Tibero 로 경로 지정
```
점검: Java 17+ · CLIP 엔진(`Viewer.jar` + `json-simple`) · `clip-report-mcp.jar` · (DB쓰면) Tibero JDBC·`.env` → **실제 서버 기동 테스트**로 죽는 원인 분류 + 등록 명령까지 출력. 흔한 원인: Java 17 미만(`UnsupportedClassVersion`), `-Clip` 가 엔진 `bin\jar`(Viewer.jar 위치)를 안 가리켜 `json-simple` 을 못 찾음.

### 메타 명령 — `/clipreport --…`
대상 `.crf` 없이 동작하는 유지보수 명령:
- `--help` (목록) · `--version` (설치본 vs GitHub 최신 비교) · `--changelog` · `--doctor` (진단) · `--update` (자가 업데이트)

### 버전 / 자동 업데이트 알림
- **현재 버전**: `/mcp` → clip-report 의 `serverInfo.version`. 이력은 [CHANGELOG.md](CHANGELOG.md), 릴리스는 git 태그 `vX.Y.Z`.
- **자동 알림**: MCP 연결(`initialize`) 때 서버가 GitHub `main` 의 [`VERSION`](VERSION) 과 자기 버전을 비교해, **더 최신이 있으면 업데이트 안내를 INSTRUCTIONS 로 띄웁니다** → Claude 가 사용자에게 `git pull` 을 안내. (오프라인/사내망 차단이면 1.5초 타임아웃 후 조용히 건너뜀)
- **끄기**: 환경변수 `CLIP_MCP_UPDATE_CHECK=0`.
- **릴리스 절차(메인테이너)**: `VERSION` 파일 + `src/CrfMcpServer.java` 의 `VERSION` 상수 + `CHANGELOG.md` 를 **같은 값**으로 올림 → `build.ps1` → 커밋 → `git tag vX.Y.Z` → `git push && git push --tags`.

> 폴더를 직접 복사해 `install.ps1` 을 돌리는 **오프라인 배포**도 그대로 됩니다(git 없이). 단 `git pull` 자동 업데이트와 위 버전 알림은 GitHub 연결이 있을 때만 동작합니다.

---

## 빌드

```powershell
./build.ps1 -Jdk "C:\path\to\jdk\bin" -Clip "C:\Program Files (x86)\Clipsoft\CLIP report v5.0\bin\jar"
# => clip-report-mcp.jar (stdio + http 서버 + 생성기 + DB/PDF 도구 포함, ~56KB). VERSION 파일이 jar 리소스로 들어가 serverInfo.version 이 됨(빌드 없이 VERSION 만 올리면 안 됨)
```

---

## 테스트

```powershell
$env:PYTHONIOENCODING="utf-8"
python tools/smoke.py -v        # 실 리포트 4종으로 읽기/쓰기 도구 회귀 152 케이스 (경로는 CLIP_SMOKE_A~D 로 변경)
python tools/mcpcall.py --list '[["crf_summary",{"path":"C:/path/x.crf"}]]'   # 서버를 stdio JSON-RPC 로 직접 호출
```
`tools/mcpcall.py` 는 `clip-report.mcp.json`(install.ps1 생성)에서 java/classpath 를 읽는다(없으면 `CLIP_JAVA`/`CLIP_CP`). 쓰기 결과는 `.smoke-out/`.

---

## 구성요소 (`src/`)

| 파일 | 역할 |
|---|---|
| **CrfMcpServer.java** | **MCP 서버 (로컬 stdio)** — 도구 43개 (.crf 38 + DB 4 + PDF 1). 클립소프트 FindQuery(문자열찾기) 유틸의 쿼리·XPath·텍스트 검색을 `crf_search` 가 포함 |
| **CrfMcpHttp.java** | **MCP 서버 (원격 Streamable HTTP)** — 같은 도구 |
| **CrfGen3.java** | SQL/MyBatis → 초안 생성기 (필드·쿼리·파라미터·그룹·푸터) |
| **CrfGen2.java** | 파싱/변환 코어 (SELECT 컬럼 파서, MyBatis→JS, 파라미터 정규화, 타입추정) |
| **CrfDump.java** | 리포트 데이터셋/SQL/필드 덤프 (CLI 점검) |
| **CrfParserValidate.java** | 컬럼 파서 정확도 검증 하네스 (실 리포트 대조) |

`src/analysis/` : 리버스엔지니어링·분석 스캐폴딩(빌드 제외, 참고용).
`samples/` : `mybatis_sample.xml`, `sample_query.sql`, `param_test.sql`

---

## MCP 도구 (40)

### 리포트(.crf) 도구

| 분류 | 도구 | 설명 |
|---|---|---|
| 설명 | `crf_summary(path)` | 데이터셋·필드·쿼리·그룹·섹션 요약 |
| 설명 | `crf_describe_layout(path, [detail])` | 섹션→서브섹션(유형/높이/숨김/서브리포트 링크)→컨트롤 + 표 셀 그리드(`‹병합›`, `{출력양식}`). `detail=true` 면 셀/컨트롤별 정렬·폰트·크기·굵게·줄바꿈·확장·셀합치기·조건스타일 |
| 설명 | `crf_validate(path)` | **lint** — 끊어진 바인딩(없는 필드/빈 바인딩), 공식의 `#unknown#`·없는 필드 참조·`return` 누락, 그룹 필드 null, 미선언/미사용 매개변수, 쿼리 컬럼↔필드 불일치, scriptType 불일치, 중복 이름, 숨김 밴드, 링크 서브리포트 파일 없음 |
| 설명 | `crf_get_query(path, [dataset|mode])` | **쿼리 본문** — 데이터셋별 scriptType·연결·필드·사용 매개변수(미선언 표시)·`{dataset.X}` 참조·테이블(추정). JS 동적쿼리는 원문 + **평문 복원본**(if 블록은 `/*IF*/` 주석) |
| 설명 | `crf_get_formula(path, [name])` | **공식 스크립트** 전문 + 참조 필드(없는 필드·`#unknown#` 표시), 누적합산 정의(함수/필드/리셋), 그룹이름→그룹필드 |
| 설명 | `crf_search(dir, text, [regex|scope|like|limit])` | 폴더 **검색** — scope=`query`(JS는 평문으로; XML/JSON 데이터셋의 **XPath** 포함)·`xpath`·`field`·`formula`·`param`·`control`(라벨/셀 텍스트·바인딩)·`any`. "테이블 X 쓰는 리포트", "매개변수 Y 받는 리포트" 찾기 (~5ms/파일) |
| 설명 | `crf_list_reports(dir, [like|limit])` | 폴더의 .crf 목록 + 총 개수, 이름 필터(부분문자열/`*` 글롭) |
| 생성 | `crf_generate(template, sql, output)` | SQL/MyBatis → 초안 .crf |
| 수정 | `crf_set_query(path, sql, output, [dataset|script_type|declare_params|sync_fields])` | 데이터셋 쿼리 교체 — 데이터셋 선택, **scriptType 자동**(평문→NotScript, MyBatis→JS, `var sql`→JavaScript), **미선언 `{parameter.X}` 자동 선언**(String), **SELECT 컬럼을 필드로 추가**(`sync_fields=add` 기본 / `replace`=미참조 필드 제거 / `none`) |
| 수정 | `crf_sync_fields(path, output, [dataset|mode|params|set_types|remove_unused])` | 필드 목록을 쿼리 컬럼에 맞춤. `mode=sql` 파싱 / **`mode=db`: 쿼리를 `SELECT * FROM (…) WHERE 1=0` 로 실행해 ResultSetMetaData 로 컬럼·타입 확정**(`SELECT *`·함수테이블 해결, 매개변수는 `params` JSON 또는 `''`/NULL) |
| 수정 | `crf_add_dataset(path, name, sql, output)` / `crf_remove_dataset(path, dataset, output, [force])` | 데이터셋 추가(첫 데이터셋 연결 복제, 매개변수 선언·필드 생성) / 삭제(필드 참조 있으면 거부) |
| 수정 | `crf_set_param(path, name, output, [type|default|prompt])` / `crf_remove_param(...)` | 전역 매개변수 생성·수정 / 삭제(쿼리·바인딩 참조 시 거부) |
| 수정 | `crf_rename_field(path, name, new_name, output, [dataset])` / `crf_remove_field(path, name, output, [dataset|force])` | 필드 이름변경(객체 바인딩 자동 추종 + 공식 `"ns.OLD"`·쿼리 `{parameter.OLD}` 재작성) / 삭제(참조 목록 제시, `force` 없으면 거부) |
| 설명 | `crf_field_refs(path, name, [dataset])` | 필드/매개변수가 쓰이는 곳: 셀 좌표·라벨·그룹·누적합산·서브리포트 링크·공식·쿼리 |
| 수정 | `crf_add_group(path, column, output, [level|label|subtotal|sort])` | 그룹 머리/바닥글 추가 — `level=inner|outer|N` 로 중첩 위치, `label=true` 머리글에 그룹필드 라벨, `subtotal=F1,F2` 바닥글에 `rexpert.sum(0,"data.F",0,"data.그룹필드","")` 소계 공식 |
| 수정 | `crf_set_group(path, group, output, [column|sort])` / `crf_remove_group(path, group, output, [force])` | 그룹 필드/정렬 변경 / 그룹 삭제(머리·바닥글 밴드 대칭 제거, 컨트롤·그룹이름 참조 있으면 거부) |
| 수정 | `crf_place_detail_fields(path, output)` | 본문에 필드 바인딩 데이터 라벨 배치 |
| 수정 | `crf_set_cell(path, table, row, col, output, [field|text|formula|clear|format|align|valign|fontsize|bold|font|wrap|cangrow|merge|bgcolor|color|underline|italic|linespace|padding])` | **표 셀** 편집 — 필드/텍스트/**공식(새 공식필드 생성+바인딩)**/지우기, 출력양식, 정렬, 폰트·크기·굵게·줄바꿈, 확장·셀합치기·배경. 저장 후 되읽어 검증. `‹병합›` 셀은 기준 셀 안내 |
| 수정 | `crf_set_label(path, name, output, [같은 속성 + left|top|width|height|visible|border|linewidth])` | **글상자/컨트롤** 편집 — 값·공식·스타일(글자색/밑줄/줄간격/여백)·위치·크기·표시·**테두리** |
| 수정 | `crf_set_cell_checkbox(path, table, row, col, field, output, [true_value|false_value|operator|true_value2|check_type|shape|color|size|default|off])` | 셀을 **기본 체크박스**(셀 내용=체크박스)로 — `field operator true_value` 면 체크(기본 `Equal '1'`), `false_value` 면 해제. 조건이 없으면 항상 빈 상자. `check_type=Rectangle(색칠, 기본)|V|Ellipse|RoundRectangle`. ■/□ 글자 대신 이걸 쓸 것 |
| 수정 | `crf_merge_cells(path, table, row, col, output, [rowspan|colspan])` | 셀 **병합**(기준 셀이 rowspan×colspan 차지, 덮인 자리는 ‹병합›) / 1×1 로 **해제** |
| 수정 | `crf_set_subsection(path, section, output, [index|height|visible|name|new_page|repeat])` | 밴드 행(서브섹션) 높이·숨김·이름·페이지바꿈·그룹머리글 반복(`repeat=None|OnPage`) |
| 수정 | `crf_merge_labels(path, names, output, [into=table\|label|name|border])` / `crf_split_label(path, name, output, [lines|heights])` | 세로로 놓인 **글상자 여러 개 → 1열 표 하나(또는 줄바꿈 글상자 하나)** / 반대로 **줄바꿈 글상자·1열 표 → 줄/행별 글상자** — 값·글꼴·정렬 유지, 세로 자리 그대로 |
| 수정 | `crf_set_font(path, output, [font|data_font|size|only_system|section])` | **글꼴 일괄** — 라벨(글자)/데이터(숫자) 따로, `only_system=true` 면 SDK 기본 `System` 글꼴만 교체. 전/후 통계 응답 |
| 수정 | `crf_add_table(path, output, columns \| rows, [section|header_section|left|top|row_height|name|width|border|wrap|align])` | **표 생성** — `columns` JSON(`field,title,width,format,align`)로 본문 1행 데이터 표 + 머리글 밴드 제목 표(같은 열 너비); **`rows` JSON 으로 문단·목록·※주석용 1열 N행 텍스트 표**(글상자를 줄마다 만들지 말 것). 새 셀은 리포트 글꼴 상속. SDK 생성이라 디자이너에서 한 번 확인 권장 |
| 수정 | `crf_remove_control(path, name, output)` / `crf_remove_section(path, section, output, [force])` | 컨트롤 삭제 / 밴드 삭제(컨트롤 있으면 거부) |
| 수정 | `crf_set_cell_style(path, table, row, col, [bgcolor|font|cangrow|merge], output)` | 셀 **스타일** — 배경색/폰트/확장가능/셀합치기 |
| 수정 | `crf_add_formula_field(path, name, script, output, [force])` | **공식필드** 생성 (JS, 끝에 `return`; 예: `return rexpert.sum(0,"data.AMT",0,"","")`) → 셀에 바인딩. 이름 중복 / `return` 누락 / `:col` `#{}` 바인드 표기는 거부(`force=true`로 강행) |
| 수정 | `crf_add_data_field(path, name, [type], output)` | 데이터셋에 **필드(컬럼)** 추가 |
| 수정 | `crf_add_label(path, section, [text|field|formula], [위치], output, [fontsize|bold|underline|color|font|align|valign|wrap|linespace|border|linewidth])` | 밴드에 **글상자** 추가(없는 표준밴드는 자동생성) — 글꼴/정렬/글자색/테두리까지 한 번에 |
| 수정 | `crf_set_paper(path, [paper|orientation|margin*], output)` | **용지** 종류/방향/여백 |
| 설명 | `crf_diff(a, b)` | 두 리포트 **비교** — 데이터셋/필드/쿼리(줄 단위 ±)/scriptType/매개변수/공식 스크립트/그룹/섹션/컨트롤/표 셀 바인딩 |

### 업무지식 도구 — DB & 문서 (양식→백엔드→DB→쿼리 파이프라인)

| 분류 | 도구 | 설명 |
|---|---|---|
| DB | `db_tables(like?)` | 테이블 목록 + **코멘트(업무명)** 검색 (`ALL_TAB_COMMENTS`) |
| DB | `db_columns(table)` | 컬럼·타입·코멘트(`ALL_COL_COMMENTS`) → **컬럼 업무의미** 파악 |
| DB | `db_sample(table, n?)` | 상위 N행 샘플 (값 형태·코드값 확인) |
| DB | `db_query(sql, max?, allow_write?)` | `SELECT`/`WITH` 실행 (TSV, 행수 캡, 타임아웃 `CLIP_DB_TIMEOUT` 기본 60s) — 쓰기 문장은 `allow_write=true` 명시 필요 |
| 문서 | `pdf_text(path)` | PDF 문서양식의 **텍스트 추출**(pdfbox) → 항목·레이아웃 파악 |

> **파이프라인**: 화면(xfdl/vue)·양식(PDF)을 받으면 → 화면/문서를 읽고(`pdf_text`+Read) → 백엔드 매퍼·서비스를 **Grep으로 역추적** → `db_tables`/`db_columns`/`db_sample`/`db_query`로 **도메인 테이블·코드값** 확보 → 양식에 맞는 **리포트 양식 추천 + 쿼리 작성/변환**(파라미터는 `'{parameter.COLNM}'` 규칙). DB 접속정보는 **`.env`** 로 분리(아래 [DB 설정](#db-설정-env) 참조).

> **편집 제안 → 실제 수정**: Claude가 *설명* 도구로 읽고 개선을 제안한 뒤, *수정* 도구(`crf_set_cell`, `crf_add_formula_field`, `crf_set_query`, `crf_add_group` …)로 **실제로 적용**합니다. 예) "본문 3번째 칸을 천단위 구분 통화로" → `crf_set_cell(... format="#,##0")`, "합계 열 추가" → `crf_add_formula_field` + `crf_set_cell`.

### 설명·제안 강화 (v0.2)

- **풍부한 읽기**: `crf_summary` 는 용지(크기·방향·여백)와 **필드 인벤토리를 분류별**(데이터/매개변수/시스템/공식/누적합산/그룹이름)로, `crf_describe_layout` 은 컨트롤별 **위치·바인딩분류(텍스트/데이터/공식/시스템)·출력양식·폰트·확장가능** 과 **표의 셀 그리드(어느 칸에 어느 필드)** 까지 보여줍니다. → Claude가 칸 단위로 정확히 설명·제안.
- **개념 주입**: MCP `initialize` 의 `instructions` 로 CLIP 개념·용어 가이드를 Claude에 자동 주입 → 항상 정식 용어로 답변.
- **참고 문서**: 개념 전반은 **[GUIDE.md](GUIDE.md)** (공식 교육가이드 요약: 섹션·필드종류·출력양식·요약함수·조건스타일·크로스탭·마스터디테일 등).

---

## MCP 설정

### A. 로컬 (stdio) — Claude Desktop / Claude Code
`claude_desktop_config.json` (Windows: `%APPDATA%\Claude\claude_desktop_config.json`) 또는 Claude Code `.mcp.json`:
```json
{
  "mcpServers": {
    "clip-report": {
      "command": "C:\\path\\to\\jdk\\bin\\java.exe",
      "args": ["-cp",
        "C:\\Program Files (x86)\\Clipsoft\\CLIP report v5.0\\bin\\jar\\*;C:\\Users\\<you>\\clip-report-mcp\\clip-report-mcp.jar",
        "CrfMcpServer"]
    }
  }
}
```
→ 클라이언트 재시작 → 도구 43개 노출. **MCP 서버에는 API 키 불필요**(키는 Claude 쪽).
> DB 도구(`db_*`)를 쓰려면 classpath 에 **Tibero JDBC 드라이버 jar** 도 추가하세요. 접속정보는 `.env` 분리(아래).

### B. 원격 (HTTP) — 팀 공유 / claude.ai 웹
```powershell
$env:CLIP_MCP_TOKEN = "secret-token"     # 선택: Bearer 인증
java -cp "C:\...\bin\jar\*;clip-report-mcp.jar" CrfMcpHttp 3333 0.0.0.0
# => POST http://<host>:3333/mcp (Streamable HTTP, JSON-RPC)
```
```json
{ "mcpServers": { "clip-report": {
    "url": "http://<host>:3333/mcp",
    "headers": { "Authorization": "Bearer secret-token" }
} } }
```

### DB 설정 (.env)

DB 도구(`db_query`/`db_tables`/`db_columns`/`db_sample`)용 접속정보는 **`.mcp.json` 에 넣지 말고 `.env` 로 분리**합니다. 서버(CrfMcpServer/CrfMcpHttp)가 **자동 로드**(내장)합니다.

1. `.env.example` 를 `.env` 로 복사 후 값 입력:
   ```
   CLIP_DB_URL=jdbc:tibero:thin:@<host>:<port>:<sid>
   CLIP_DB_USER=<DB_USER>
   CLIP_DB_PWD=<DB_PASSWORD>
   ```
2. 로드 순서(앞이 우선): 동명 **환경변수** → `$CLIP_ENV_FILE` → `~/clip-report-mcp/.env` → `~/.dbtools/.env` → `./.env`
3. classpath 에 **Tibero JDBC jar** 추가 (예: `...\tibero7-1.0.jar`).

> ⚠️ `.env` 는 **자격증명** — `.gitignore` 로 커밋 제외, 배포 zip 에도 미포함. **사내에서만 별도 공유**. (드라이버는 `oracle.jdbc.OracleDriver` 도 자동 시도)

---

## 예시 대화 (자연어 → Claude가 도구 호출)

- *"report 폴더에 리포트 뭐뭐 있어?"* → `crf_list_reports`
- *"이 리포트 구조랑 쿼리 설명해줘: C:\...\xxx.crf"* → `crf_summary` + `crf_get_query` + `crf_describe_layout` → Claude가 풀이
- *"AHRM1234 테이블 쓰는 리포트 찾아줘"* / *"DEPTCD 매개변수 받는 리포트?"* → `crf_search(dir, "AHRM1234", scope="query")` / `scope="param"`
- *"이 공식 뭐 하는 거야?"* → `crf_get_formula`
- *"이 리포트 레이아웃 보고 개선점 제안해줘"* → 설명 도구로 읽고 **편집 제안**
- *"이 SQL로 학과별 그룹 잡힌 초안 만들어줘, 출력은 out.crf"* → `crf_generate`
- *"방금 거 본문에 필드 깔고 직급으로 그룹 하나 더 추가해"* → `crf_place_detail_fields` + `crf_add_group`
- *"WHERE에 학기 조건 넣어서 다시 저장해"* → `crf_set_query`

---

## CLI (도구 없이 직접)

```powershell
$CP = "C:\...\CLIP report v5.0\bin\jar\*;clip-report-mcp.jar"
java -cp $CP CrfGen3  template.crf  samples\mybatis_sample.xml  out.crf   # 생성
java -cp $CP CrfDump  some.crf                                            # 점검
java -cp $CP CrfParserValidate  "C:\...\report"  3000                     # 파서 검증
```
> **템플릿(`template.crf`)**: 표준 밴드 + 페이지바닥글을 갖춘 빈 리포트를 Designer로 1회 만들어 재사용 권장.

---

## 핵심 노하우 (리버스 엔지니어링으로 확인)

- **.crf 포맷**: 매직 `REX30`, 문자열 = `[uint32 바이트길이][UTF-16LE]`, 타입 태그 속성 트리. **SQL·민감 문자열은 DES 암호화** → 엔진이 자동 복호.
- **엔진 진입점**: `Rexpert4.read(path)` / `Rexpert4.write(report, path)` (Viewer.jar, `com.clipsoft.clipreport.base`).
- **동적쿼리**: `DataAccessMethodSQL.scriptType = JavaScript` 면 `queryString` 은 `var sql=""; sql+="..."; if(...){...}` 로 SQL을 조립하는 JS. MyBatis `<if>`→`if`, `#{x}`/`${x}`→`{parameter.X}`.
- **파라미터 이름**: 화면(camel)→리포트 토큰은 **대문자화 + 언더바 보존**(`empNm`→`EMPNM`, `emp_nm`→`EMP_NM`). 환경변수 `CLIP_PARAM_MODE`=`upper`(기본)|`asis`|`uppernosep`.
- **컬럼 소스**: 실 쿼리의 약 19%가 `SELECT *`/`TABLE(func)` 라 SQL에서 컬럼을 못 뽑음 → **1순위 = DB 결과셋 메타데이터**, 2순위 = SQL 파싱(명시적 컬럼목록에서 ~97% 정확, CrfParserValidate로 검증).
- **그룹**: `new Group(2800)` + `setGroupingField(field)` + `SectionGroupHeader.setGroup(g)` + `SectionGroupFooter`.
- **필드↔컨트롤 바인딩**: `control.setApplyValueType(ApplyValueType.Field)` + `setApplyValueField(field)` (정적텍스트는 `Text`+`setApplyValueText`).
- **페이지바닥글 공통**: `ControlSubreport.getLinkedSubreportPath().setUrlText("../../../images/bottom_logo.crf")`.
- **좌표 단위 0.1mm**(A4=2100×2970), **색은 BGR int**(`(b<<16)|(g<<8)|r`; 도구엔 `#RRGGBB`), 줄바꿈 텍스트 **`LineSpace` 단위 pt**(10pt 글꼴 HWP 160% ≈ 5.5).
- **새 표 셀은 대각선(FDiagona/BDiagona) 기본 Solid** → None 으로 안 바꾸면 셀마다 X. v0.7.0 부터 `crf_add_table`/`crf_merge_cells` 가 처리.
- **새 글상자/셀의 글꼴 기본값은 `System` 9pt**(`new ControlLabel()`/`new TableCellNormal()`) — 그대로 두면 다른 리포트와 글꼴이 안 맞는다. v0.7.1 부터 생성 도구가 리포트 지배 글꼴(라벨/데이터 각각)을 상속하고 `crf_validate` 가 `System` 을 경고, `crf_set_font(only_system=true)` 로 일괄 정리.
- **체크박스 셀**(`CellContentType.Checkbox`)은 `CheckValueTrueCondition/FalseCondition`(`Condition`: 필드·연산자·값)으로만 체크가 결정된다 — 조건이 비면 값이 뭐든 항상 빈 상자. 체크 모양 `CheckType` Rectangle(색칠)/V/Ellipse/RoundRectangle.
- **양식(HWPX/PDF) → 문서형 리포트** 절차·정렬 체크리스트·실서버 렌더 검증: [docs/document-report-recipe.md](docs/document-report-recipe.md), 샘플 [samples/document/](samples/document/).

---

## 한계 / TODO

- 본문은 데이터 **라벨** 배치까지. 정식 **표(ControlTable)** 생성은 미구현(셀 바인딩은 동일 `setApplyValueField`라 기계적 확장).
- 필드 동기화는 SELECT 파싱(별칭 없는 식은 제외) 또는 DB 실행 기반. `mode=db` 는 `.env` DB 연결이 필요하고, 매개변수 값이 없으면 `''`/NULL 로 바인딩해 메타데이터만 읽는다(조건에 따라 타입 오류가 나면 `params` 로 값 지정).
- `crf_add_table` 로 만든 표는 SDK 기본 속성(테두리/여백)을 쓴다(대각선은 v0.7.0 부터 자동 제거, 글꼴은 v0.7.1 부터 리포트 글꼴 상속) — 디자이너에서 한 번 열어 확인. 그룹 소계 공식의 `rexpert.sum` 인자 의미(범위/리셋)는 실행으로 확인한 값이 아니므로 결과가 다르면 `crf_set_cell formula=` 로 조정.
- 남은 로드맵(생성기 개선 등)은 [docs/PLAN-v0.5.md](docs/PLAN-v0.5.md) v0.7.
- `crf_get_query` 의 JS→평문 복원은 문자열 연결을 풀고 `if` 블록을 주석으로 표시한 **추정본**(실제 SQL 은 매개변수 조건에 따라 달라짐). 테이블 목록도 FROM/JOIN 정규식 추정.
- 도구 실패는 `ERROR: …`(MCP `isError`)로 반환. 쓰기 도구는 `output` 이 원본과 같으면 거부.
- MyBatis `<foreach>`/`<choose>` 부분 지원(경고).
- 원격 HTTP: 운영 시 **TLS + 강한 인증**, localhost 바인딩 또는 사내망 한정 권장.

---

## 보안 & 라이선스

- **Clipsoft 엔진 jar는 라이선스 자산** — 공개 저장소 포함 금지. 본 프로젝트(래퍼 코드)만 공유, 실행 호스트에 CLIP report 설치 필요.
- 쓰기 도구는 항상 `output` 별도 지정 → **원본 비파괴**.
- 원격 서버 기본 `127.0.0.1` 바인딩. 외부 공개 시 `CLIP_MCP_TOKEN` + 리버스 프록시(TLS).
- **DB 자격증명**은 `.env`(또는 환경변수)로만 — `.gitignore`·배포 zip 제외, 사내 한정 공유. `db_query` 는 운영 DB 직결이므로 **읽기전용 계정 + SELECT 한정** 권장.
