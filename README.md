# CLIP Report Assistant (MCP)

Clipsoft **CLIP report v5.0** 의 `.crf` 리포트를 **Claude로 생성·수정·편집·설명**하는 MCP 서버 + CLI.

Claude에게 "리포트 조작 도구"를 쥐여주면, 사용자는 **자연어로 시키기만** 하면 됩니다:

- **설명** — 리포트의 데이터셋·쿼리·필드·공식·그룹·레이아웃(표 셀 격자까지)을 읽어 Claude가 풀어서 설명. JS 동적쿼리는 **매개변수 값을 넣어 실제 실행한 SQL** 까지 확인
- **검색·점검** — 폴더 전체에서 테이블/매개변수/문구로 리포트 찾기, 끊어진 바인딩·쿼리 오류·엑셀 격자 **lint**, 두 리포트 **diff**
- **생성 도움** — 비슷한 목록형 리포트(템플릿) + SQL / MyBatis → 새 `.crf` 한 번에 파생(필드·쿼리·매개변수·본문/제목/합계 표·제목·조건·그룹 소계·공통로고)
- **수정 도움** — 쿼리 교체·조건 추가, 필드/매개변수/데이터셋, 표 행·열 삽입/삭제/병합, 셀·글상자 값과 서식, 그룹, 용지 등
- **편집 제안** — Claude가 레이아웃을 읽고 개선점을 자연어로 제안 → 수정 도구로 실제 적용
- **양식 읽기 + 비전 검증** — 양식을 **PDF·HWP·HWPX·DOCX·XLSX·PPTX·HTML·이미지** 어느 것으로 받아도 글자와 배치(좌표 mm)를 파싱하고, 같은 문서를 **그림으로 변환해 Claude 가 눈으로 대조**한 뒤 작업. 끝나면 리포트 배치 미리보기 그림을 원본과 다시 대조
- **실제 출력 확인** — `crf_render` 가 리포트를 **실제 CLIP 서버 엔진**으로 PDF 출력해 그림과 글자로 돌려준다(WAS 를 띄우지 않음). 가상 데이터로 행 반복·확장·페이지 나눔·소계·크로스탭을 보거나(`mode=sample`), 서버 설정의 DB 로 쿼리를 실제 실행해(`mode=db`) 수치까지 확인
- **업무지식 + 쿼리 도움** — 화면파일(xfdl/vue)·문서양식 → 백엔드 역추적 + **DB 도메인테이블 조회**(`db_*` 도구)로 업무지식 확보 → 양식에 맞는 **추천 제안 + 쿼리 작성/변환**

> ⚠️ 바이너리를 직접 조작하지 않습니다. Clipsoft **공식 Java 엔진**의 reader/writer 로 읽고 씁니다(REX30 포맷·직렬화·암복호는 엔진 것).
> 단 엔진 기본 writer 에는 저장 결함이 셋 있어 `src/CrfIO.java` 가 보정합니다 — **쿼리 본문을 평문으로 쓰는 것**(디자이너는 암호화 저장), 크로스탭 셀 헤더 개수 불일치(되읽기 실패), 웹 링크 팝업 너비 속성 ID 오류. 아래 [핵심 노하우](#핵심-노하우-리버스-엔지니어링으로-확인) 참조.

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
- 첫 토큰=파일/폴더, 나머지=요청. 쓰기 작업은 `<원본>_edited.crf` 로 **원본 보존**(여러 단계를 이어 고칠 때는 `in_place=true` — 첫 쓰기 때 `<path>.bak` 백업).
- 설치: 아래 [MCP 설정](#mcp-설정) 으로 clip-report 서버 등록 + 슬래시 명령은 `~/.claude/commands/clipreport.md` (동봉).

---

## 동작 원리

```
사용자(자연어) ──▶ Claude (MCP 클라이언트) ──JSON-RPC(stdio | HTTP)──▶ MCP 서버 ──▶ 공식 엔진(Rexpert4) ──▶ .crf
                      ▲ 설명·제안은 Claude의 추론          (CrfMcpServer / CrfMcpHttp)      (읽기/쓰기, 자동 암복호)
                      └──────────────── 결과를 해석해 답변 ◀────────────────────────────────────────┘
```

- **읽기 도구**(요약/레이아웃/쿼리/공식/검색/lint)로 Claude가 리포트를 "보고" → **설명 & 편집 제안**을 자연어로 생성.
- **쓰기 도구**(생성/수정)는 기본적으로 `output` 을 따로 받아 **원본 비파괴**(`output` 이 원본과 같으면 거부). 여러 단계를 이어 고칠 때만 `in_place=true` 로 원본에 직접 쓰며, 이때 첫 쓰기 전에 `<path>.bak` 백업을 만든다. 모든 쓰기는 임시 파일에 쓰고 되읽어 본 뒤 제자리로 옮기며(되읽기에 실패하면 기존 파일을 건드리지 않음), **쿼리 본문은 디자이너와 같이 암호화해 저장**한다.

---

## 요구사항

- **CLIP report v5.0 설치** (엔진 jar + 라이선스). 엔진 jar: `C:\Program Files (x86)\Clipsoft\CLIP report v5.0\bin\jar\*.jar`
  → 본 프로젝트는 이 jar들을 **재배포하지 않습니다**. 실행 호스트에 CLIP report가 설치돼 있어야 합니다.
- **JDK/JRE 17+** (빌드는 JDK, 실행은 JRE)
- (선택) **양식 문서를 그림으로 변환**(`doc_image`)할 때만: DOCX/XLSX/PPTX → MS Office 또는 LibreOffice, HWP/HWPX → 한글(한컴오피스) 또는 LibreOffice(+H2Orestart 확장), HTML → Edge 또는 Chrome. 없어도 `doc_text`(파싱)는 전부 동작하고, HWP/HWPX/OOXML 은 파일에 든 첫 쪽 미리보기 그림으로 대체한다. 경로를 못 찾으면 환경변수 `CLIP_BROWSER`(브라우저 exe), `CLIP_SOFFICE`(soffice exe)로 지정. HTML 을 열 때 네트워크는 기본 차단(`CLIP_DOC_ALLOW_NETWORK=1` 로 해제).

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
- **릴리스 절차(메인테이너)**: `VERSION` 파일(단일 출처 — 서버는 jar 안의 `/VERSION` 을 읽으므로 소스 상수는 없음)과 `CHANGELOG.md` 의 `## [X.Y.Z]` 항목을 **같은 값**으로 올림 → `build.ps1`(동봉 `clip-report-mcp.jar` 갱신) → `python tools/smoke.py`(README 의 도구 수·도구 이름·케이스 수·CHANGELOG 버전이 코드와 맞는지도 검사) → 커밋 → `git tag vX.Y.Z` → `git push && git push --tags` → (선택) `python tools/release_notes.py X.Y.Z` 로 CHANGELOG 항목을 GitHub 릴리스 노트로.

> 폴더를 직접 복사해 `install.ps1` 을 돌리는 **오프라인 배포**도 그대로 됩니다(git 없이). 단 `git pull` 자동 업데이트와 위 버전 알림은 GitHub 연결이 있을 때만 동작합니다.

---

## 빌드

```powershell
./build.ps1 -Jdk "C:\path\to\jdk\bin" -Clip "C:\Program Files (x86)\Clipsoft\CLIP report v5.0\bin\jar"
# => clip-report-mcp.jar (stdio + http 서버 + 생성기 + DB/문서/렌더 도구 포함, 약 280KB). VERSION 파일이 jar 리소스로 들어가 serverInfo.version 이 됨(빌드 없이 VERSION 만 올리면 안 됨)
```

---

## 테스트

```powershell
$env:PYTHONIOENCODING="utf-8"
python tools/smoke.py -v        # 실 리포트 7종 + 즉석 생성 문서 픽스처로 도구 회귀 + 문서 정합성 검사, 366 케이스
python tools/mcpcall.py --list '[["crf_summary",{"path":"C:/path/x.crf"}]]'   # 서버를 stdio JSON-RPC 로 직접 호출
```
- 픽스처(실 리포트) 경로는 환경변수 `CLIP_SMOKE_A`~`E`, `CLIP_SMOKE_F1`, `CLIP_SMOKE_X` 로 바꾼다. 파일이 없으면 그 케이스는 건너뛴다(그만큼 총 케이스 수가 줄어든다).
- 문서 파서 케이스는 DOCX/XLSX/PPTX/HWPX/HTML/PNG 픽스처를 테스트가 직접 만들어 쓴다(`.smoke-out/docs/`). 바이너리 HWP 는 `CLIP_SMOKE_HWP=<실제 .hwp>` 를 주면 2 케이스가 추가된다. `doc_image` 의 변환 케이스는 변환기가 있든 없든 통과하도록(그림 또는 명확한 ERROR) 되어 있다.
- `CLIP_MCP_SAVE_IMAGES=<폴더>` 를 주면 `mcpcall.py`/`smoke.py` 가 `doc_image`·`crf_preview` 가 돌려준 그림을 그 폴더에 저장한다.
- `crf_render` 케이스(11 + 크로스탭 수치 검증 1)는 리포트 서버 웹앱이 있을 때만 돈다(`CLIP_RENDER_WEBAPP` 환경변수 또는 `.env`). 크로스탭 저장 케이스의 픽스처는 `CLIP_SMOKE_XT`.
- 끝에서 **이번 실행이 쓴 모든 `.crf` 에 평문 SQL 이 없는지**(속성 900 이 암호화 타입인지) 바이트로 검사하고, **픽스처 원본이 실행 전과 바이트 단위로 같은지** 해시로 확인한다.
- DB 를 실제로 치는 케이스(`crf_sync_fields mode=db` 4건)는 개발 DB 연결이 필요하다 — DB 가 없으면 `CLIP_SMOKE_DB=0` 으로 끈다.
- 마지막 4 케이스는 **문서 정합성**: README 의 도구 수·도구 이름·케이스 수, CHANGELOG 의 현재 버전 항목이 코드와 어긋나면 FAIL(문서가 현상과 달라지는 것을 막는다).
- `tools/mcpcall.py` 는 `clip-report.mcp.json`(install.ps1 생성)에서 java/classpath 를 읽는다(없으면 `CLIP_JAVA`/`CLIP_CP`). 여러 호출을 넘기면 첫 오류에서 멈추고(`--continue-on-error` 로 계속), 쓰기 결과는 `.smoke-out/`.

---

## 구성요소

| 파일 (`src/`) | 역할 |
|---|---|
| **CrfMcpServer.java** | **MCP 서버 (로컬 stdio)** — 도구 56개 (.crf 49 + DB 4 + 문서 3), 서버 instructions(개념·규칙 주입), 읽기/검색/lint/diff 와 셀·글상자·필드·매개변수·그룹·쿼리 편집. 클립소프트 FindQuery(문자열찾기) 유틸의 쿼리·XPath·텍스트 검색을 `crf_search` 가 포함 |
| **CrfMcpHttp.java** | **MCP 서버 (원격 Streamable HTTP)** — 같은 도구 |
| **CrfTableOps.java** | 표 구조 편집 — 행/열 삽입·복제·삭제·이동·크기·균등, 표 속성, 격자 정보(`crf_table_rows/cols`, `crf_set_table`, `crf_table_info`) |
| **CrfDerive.java** | 목록형 파생 — `crf_generate` v2, `crf_set_columns`, `crf_append_query_condition` |
| **CrfGen2.java** | 파싱/변환 코어 (SELECT 컬럼 파서, MyBatis→JS 변환, 파라미터 정규화, 타입추정) |
| **CrfJsEval.java** | JS 동적쿼리 **실제 실행**(CLIP 엔진 jar 의 Rhino, Java 접근 차단·시간 제한) — 실행 SQL, 문법/실행 오류 lint |
| **CrfDocs.java** | 양식 문서 파서(`doc_text`/`doc_image`) — HWP 5.x 레코드 직접 해석(POIFS), HWPX/DOCX/XLSX/PPTX(zip+XML), XLS(poi), PDF(pdfbox), HTML(헤드리스 브라우저), 문서→PDF 변환(한글/MS Office/LibreOffice) |
| **CrfPreview.java** | 리포트 배치 미리보기 그림(`crf_preview`) |
| **CrfIO.java** | `.crf` **저장** — 쿼리 본문 암호화, 크로스탭 셀 헤더·웹 링크 속성 보정, 임시 파일에 쓰고 되읽은 뒤 교체. CLI `java -cp … CrfIO in.crf out.crf`(그대로 다시 저장) |
| **CrfRender.java** | **실제 엔진 PDF 출력**(`crf_render`) — 리포트 서버 웹앱의 엔진을 별도 java 프로세스로 호출(가상 데이터 / DB 실행) |
| **CrfCrosstab.java** | 크로스탭 구조 읽기(`crf_describe_layout`)·생성(`crf_add_crosstab`), 누적합산 필드(`crf_add_running_total`) |
| **CrfGen3.java** | v1 초안 생성기(`crf_generate legacy=true`, CLI) |
| **CrfDump.java** | 리포트 데이터셋/SQL/필드 덤프 (CLI 점검) |
| **CrfParserValidate.java** | 컬럼 파서 정확도 검증 하네스 (실 리포트 대조) |

- `src/analysis/` : 리버스엔지니어링·분석 스캐폴딩(빌드 제외, 참고용)
- `tools/` : `smoke.py`(회귀 테스트), `mcpcall.py`(stdio JSON-RPC 호출 하네스), `release_notes.py`(CHANGELOG → 릴리스 노트)
- `setup/` : `/clipreport` 슬래시 명령(`clipreport.md` + `--update/--doctor/--version/--changelog` 바로가기), `mcp.json.example`
- `docs/` : [문서형 리포트 레시피](docs/document-report-recipe.md), [.crf 바이너리 노트](docs/crf-binary-notes.md), [로드맵](docs/PLAN-v0.5.md)
- `samples/` : `mybatis_sample.xml`, `sample_query.sql`, `param_test.sql`, `document/`(양식 → 문서형 리포트 샘플·HWPX 파서·실서버 렌더 스크립트)
- 루트 스크립트 : `install.ps1`(설치), `update.ps1`(업데이트), `doctor.ps1`(진단), `build.ps1`(빌드)

---

## MCP 도구 (56)

`.crf` 도구 49개(읽기·검색·점검·미리보기·출력 12 + 생성/수정 37) + DB 4 + 문서 3. 수정 도구는 모두 `output`(새 파일) 또는 `in_place=true`(원본 + `.bak`) 로 저장하고, 저장 후 되읽어 검증한다.

### 리포트(.crf) 도구

| 분류 | 도구 | 설명 |
|---|---|---|
| 설명 | `crf_summary(path)` | 데이터셋·필드·쿼리·그룹·섹션 요약 |
| 설명 | `crf_describe_layout(path, [detail])` | 섹션→서브섹션(유형/높이/숨김/서브리포트 링크)→컨트롤 + 표 셀 그리드(`‹병합›`, `{출력양식}`). `detail=true` 면 셀/컨트롤별 정렬·폰트·크기·굵게·줄바꿈·확장·셀합치기·조건스타일 |
| 설명 | `crf_validate(path, [excel])` | **lint** — 끊어진 바인딩(없는 필드/빈 바인딩), 공식의 `#unknown#`·없는 필드 참조·`return` 누락, **JS 동적쿼리의 문법 오류·실행 오류**(실제 JS 엔진으로 실행해 확인: 닫히지 않은 괄호, 선언 안 한 변수 등), 그룹 필드 null, 미선언/미사용 매개변수, 쿼리 컬럼↔필드 불일치, `System` 기본 글꼴·글꼴 섞임, scriptType 불일치, 중복 이름, 숨김 밴드, 링크 서브리포트 파일 없음. **`excel=true`**: 엑셀 격자(엑셀로 내보내는 밴드의 표 열 경계·글상자 좌우·선 x 가 본문 표 경계 ±1mm 에 있는지, 페이지 바닥글 제외; 어긋난 변마다 엑셀 열이 쪼개짐)를 ⚠ 로 |
| 설명 | `crf_get_query(path, [dataset|mode|params])` | **쿼리 본문** — 데이터셋별 scriptType·연결·필드·사용 매개변수(미선언 표시)·`{dataset.X}` 참조·테이블(추정). JS 동적쿼리는 원문 + **평문 복원본**(if 블록은 `/*IF*/` 주석). **`params={"DEPTCD":"20399"}`(또는 `mode=eval`) 를 주면 그 값으로 JS 를 실제 실행한 SQL** — 리포트가 DB 로 보내는 문장 그대로라 "0건/조건이 안 먹는다" 를 바로 확인(XML/JSON 데이터셋은 루트 XPath·필드 경로) |
| 설명 | `crf_get_formula(path, [name])` | **공식 스크립트** 전문 + 참조 필드(없는 필드·`#unknown#` 표시), 누적합산 정의(함수/필드/리셋), 그룹이름→그룹필드 |
| 설명 | `crf_search(dir, text, [regex|scope|like|limit])` | 폴더 **검색** — scope=`query`(JS는 평문으로; XML/JSON 데이터셋의 **XPath** 포함)·`xpath`·`field`·`formula`·`param`·`control`(라벨/셀 텍스트·바인딩)·`any`. "테이블 X 쓰는 리포트", "매개변수 Y 받는 리포트" 찾기 (~5ms/파일) |
| 설명 | `crf_list_reports(dir, [like|limit])` | 폴더의 .crf 목록 + 총 개수, 이름 필터(부분문자열/`*` 글롭) |
| 생성 | `crf_generate(template, sql, output, [columns|title|cond|cond_right|groups|clean_template|fields_from_db|params|snap|logo])` | **목록형 리포트 한 방 파생(v2)** — 템플릿 그룹·묵은 필드/바인딩 정리 → 쿼리(매개변수 선언·필드 SELECT 순서·`SELECT *` 는 DB 메타데이터) → 본문/제목/합계 표를 `columns` 로 재구성 → 제목·조건 글상자 → 그룹(`auto`=GROUP BY 첫 컬럼, 소계 라벨 열 정렬) → 로고 유지 → 엑셀 격자 맞춤 → `crf_validate excel=true`. `legacy=true` 면 v1 |
| 수정 | `crf_set_columns(path, columns, output, [table|header_table|footer_table|width|total_label|snap])` | **열 세트 전체 교체** — 본문 표 + 제목 표 + 합계 표(+그룹 바닥글 소계 표)를 `columns=[{field|text,title,width,align,format,total,merge}]` 로 재구성. 스타일은 기존 열에서 상속, 숫자 열 오른쪽 정렬, `total` 열은 `F_TOTAL_*` 공식 |
| 수정 | `crf_append_query_condition(path, sql, output, [dataset|param|condition|position])` | JS 동적쿼리에 **조건 if 블록 하나 추가**(ORDER BY 앞 기본, `end`, `after:<문자열>`; 기준 줄이 if 안이면 블록 닫힌 뒤). 평문 SQL 은 JS 로 변환, `return sql;` 보장, 매개변수 선언 |
| 수정 | `crf_set_query(path, sql, output, [dataset|script_type|declare_params|sync_fields|reorder])` | 데이터셋 쿼리 교체 — 데이터셋 선택, **scriptType 자동**(평문→NotScript, MyBatis→JS, `var sql`→JavaScript), **미선언 `{parameter.X}` 자동 선언**(디자이너와 같은 타입 없음), **SELECT 컬럼을 필드로 추가하고 SELECT 순서로 재정렬**(`sync_fields=add` 기본 / `replace`=미참조 필드 제거 / `none`; `reorder=false` 로 끔). MyBatis 는 `<if>`·`<choose>`·`<where>`/`<trim>`/`<set>`·`<foreach>`(매개변수를 `A,B,C` 쉼표 문자열로)·`isValid()`/`list.size() > 0` 등을 JS 로 변환하고, 결과 JS 를 실제로 돌려 문법/실행 오류가 있으면 ✖ 로 알림 |
| 수정 | `crf_reorder_fields(path, output, [dataset|order])` | **필드 순서 재정렬** — 필드는 이름이 아니라 **목록 위치**로 SELECT 컬럼에 대응하므로 순서가 어긋나면 엉뚱한 값이 찍힘. `order=query`(기본, SELECT 순서) / `order=A,B,C` |
| 수정 | `crf_sync_fields(path, output, [dataset|mode|params|set_types|remove_unused|reorder])` | 필드 목록을 쿼리 컬럼에 맞춤. `mode=sql` 파싱 / **`mode=db`: 쿼리를 `SELECT * FROM (…) WHERE 1=0` 로 실행해 ResultSetMetaData 로 컬럼·타입 확정**(`SELECT *`·함수테이블 해결, 매개변수는 `params` JSON 또는 `''`/NULL; JS 동적쿼리는 그 매개변수 값으로 **실제 실행한 SQL** 을 쓰고, 안 되면 평문 복원본으로 재시도) |
| 수정 | `crf_add_dataset(path, name, sql, output)` / `crf_remove_dataset(path, dataset, output, [force])` | 데이터셋 추가(첫 데이터셋 연결 복제, 매개변수 선언·필드 생성) / 삭제(필드 참조 있으면 거부) |
| 수정 | `crf_set_param(path, name, output, [type|default|prompt])` / `crf_remove_param(...)` | 전역 매개변수 생성·수정 / 삭제(쿼리·바인딩 참조 시 거부) |
| 수정 | `crf_rename_field(path, name, new_name, output, [dataset])` / `crf_remove_field(path, name, output, [dataset|force])` | 필드 이름변경(객체 바인딩 자동 추종 + 공식 `"ns.OLD"`·쿼리 `{parameter.OLD}` 재작성) / 삭제(참조 목록 제시, `force` 없으면 거부) |
| 설명 | `crf_field_refs(path, name, [dataset])` | 필드/매개변수가 쓰이는 곳: 셀 좌표·라벨·그룹·누적합산·서브리포트 링크·공식·쿼리 |
| 수정 | `crf_add_group(path, column, output, [level|label|subtotal|sort])` | 그룹 머리/바닥글 추가 — `level=inner|outer|N` 로 중첩 위치, `label=true` 머리글에 그룹필드 라벨, `subtotal=F1,F2` 바닥글에 `rexpert.sum(0,"data.F",0,"data.그룹필드","")` 소계 공식 |
| 수정 | `crf_set_group(path, group, output, [column|sort])` / `crf_remove_group(path, group, output, [force])` | 그룹 필드/정렬 변경 / 그룹 삭제(머리·바닥글 밴드 대칭 제거, 컨트롤·그룹이름 참조 있으면 거부) |
| 수정 | `crf_place_detail_fields(path, output)` | 본문에 필드 바인딩 데이터 라벨 배치 |
| 수정 | `crf_set_cell(path, table, row, col, output, [field|text|formula|clear|format|clear_format|align|valign|fontsize|bold|font|wrap|cangrow|merge|bgcolor|color|underline|italic|linespace|padding])` · **일괄** `cells=[{row,col,table?,…}]`(최상위 인자는 기본값, 저장·검증 1회) | **표 셀** 편집 — 필드/텍스트/**공식(새 공식필드 생성+바인딩)**/지우기, 출력양식, 정렬, 폰트·크기·굵게·줄바꿈, 확장·셀합치기·배경. 저장 후 되읽어 검증. `‹병합›` 셀은 기준 셀 안내 |
| 수정 | `crf_set_label(path, name, output, [같은 속성 + left|top|width|height|visible|border|linewidth])` | **글상자/컨트롤** 편집 — 값·공식·스타일(글자색/밑줄/줄간격/여백)·위치·크기·표시·**테두리** |
| 수정 | `crf_set_cell_checkbox(path, table, row, col, field, output, [true_value|false_value|operator|true_value2|check_type|shape|color|size|default|off])` | 셀을 **기본 체크박스**(셀 내용=체크박스)로 — `field operator true_value` 면 체크(기본 `Equal '1'`), `false_value` 면 해제. 조건이 없으면 항상 빈 상자. `check_type=Rectangle(색칠, 기본)|V|Ellipse|RoundRectangle`. ■/□ 글자 대신 이걸 쓸 것 |
| 수정 | `crf_merge_cells(path, table, row, col, output, [rowspan|colspan])` | 셀 **병합**(기준 셀이 rowspan×colspan 차지, 덮인 자리는 ‹병합›) / 1×1 로 **해제** |
| 설명 | `crf_table_info(path, table, [detail])` | 표 **격자**: 위치·크기, 열 너비·행 높이 목록, 셀별 내용과 병합 span `(r×c)`/`‹병합←r,c›`, `detail=true` 면 글꼴·정렬·테두리, 격자 일관성 검사 |
| 수정 | `crf_table_rows(path, table, action, output, [row|at|position|count|height|heights|to|shift])` | 표 **행** 편집 — `insert`(중간 삽입: `at` 또는 `row`+`position=before\|after`, 위 행의 구조·스타일 복제, 삽입 지점을 가로지르는 세로 병합은 늘어남) · `copy`(내용까지 복제) · `delete`(`'1,3'`/`'2-4'`, 병합 기준 셀 삭제 시 아래 셀로 승격) · `move`(`to`) · `resize`(`heights='60,80,…'` 전체 / `'1:80,3:40'` / `row`+`height`) · `equalize`(모든 행 같은 높이; `height` 없으면 총 높이 유지). `shift=true`(기본) 면 표 아래 요소를 높이 변화만큼 옮기고 감싸는 글상자·밴드 높이도 맞춤 |
| 수정 | `crf_table_cols(path, table, action, output, [col|at|position|count|width|widths|to|shift])` | 표 **열** 편집 — 행과 동일한 `insert`/`copy`/`delete`/`move`/`resize`(`widths`)/`equalize`(모든 열 같은 너비). 표가 용지 본문 너비를 넘으면 ⚠ |
| 수정 | `crf_set_table(path, table, output, [left|top|width|height|border|cell_border|linewidth|linecolor|auto_merge|keep_together|visible|unmerge_all|name])` | 표 **자체** — 위치, 전체 너비/높이(열·행 **비례** 조정), 외곽선, **모든 셀 테두리** 일괄(`cell_border=true\|false\|left,top`), 자동병합, 페이지나눔방지(`None\|Row\|Table`), **전체 병합 해제**, 이름 변경 |
| 수정 | `crf_set_subsection(path, section, output, [index|height|visible|name|new_page|repeat])` | 밴드 행(서브섹션) 높이·숨김·이름·페이지바꿈·그룹머리글 반복(`repeat=None|OnPage`) |
| 수정 | `crf_merge_labels(path, names, output, [into=table\|label|name|border])` / `crf_split_label(path, name, output, [lines|heights])` | 세로로 놓인 **글상자 여러 개 → 1열 표 하나(또는 줄바꿈 글상자 하나)** / 반대로 **줄바꿈 글상자·1열 표 → 줄/행별 글상자** — 값·글꼴·정렬 유지, 세로 자리 그대로 |
| 수정 | `crf_set_font(path, output, [font|data_font|size|only_system|section])` | **글꼴 일괄** — 라벨(글자)/데이터(숫자) 따로, `only_system=true` 면 SDK 기본 `System` 글꼴만 교체. 전/후 통계 응답 |
| 수정 | `crf_add_table(path, output, columns \| rows, [section|header_section|left|top|row_height|name|width|border|wrap|align])` | **표 생성** — `columns` JSON(`field,title,width,format,align`)로 본문 1행 데이터 표 + 머리글 밴드 제목 표(같은 열 너비); **`rows` JSON 으로 문단·목록·※주석용 1열 N행 텍스트 표**(글상자를 줄마다 만들지 말 것). 새 셀은 리포트 글꼴 상속. SDK 생성이라 디자이너에서 한 번 확인 권장 |
| 수정 | `crf_remove_control(path, name, output)` / `crf_remove_section(path, section, output, [force])` | 컨트롤 삭제 / 밴드 삭제(컨트롤 있으면 거부) |
| 수정 | `crf_set_cell_style(path, table, row, col, [bgcolor|font|cangrow|merge|border|linewidth|linecolor], output)` | 셀 **스타일** — 배경색/폰트/확장가능/셀합치기/테두리(`border=true\|false\|left,top`, 선 굵기·색) |
| 수정 | `crf_add_formula_field(path, name, script, output, [force])` | **공식필드** 생성 (JS, 끝에 `return`; 예: `return rexpert.sum(0,"data.AMT",0,"","")`) → 셀에 바인딩. 이름 중복 / `return` 누락 / `:col` `#{}` 바인드 표기는 거부(`force=true`로 강행) |
| 수정 | `crf_add_data_field(path, name, [type], output)` | 데이터셋에 **필드(컬럼)** 추가 |
| 수정 | `crf_add_label(path, section, [text|field|formula], [위치], output, [fontsize|bold|underline|color|font|align|valign|wrap|linespace|border|linewidth])` | 밴드에 **글상자** 추가(없는 표준밴드는 자동생성) — 글꼴/정렬/글자색/테두리까지 한 번에 |
| 수정 | `crf_set_paper(path, [paper|orientation|margin*|fit], output)` | **용지** 종류/방향/여백. 방향을 바꾸면 디자이너처럼 가로/세로 크기를 교환하고, `fit=true` 면 모든 밴드의 표(열 비례)·글상자·선을 새 본문 너비에 비례 조정 |
| 설명 | `crf_preview(path, [max_px|guides])` | 리포트 **배치를 그림으로**(MCP image) — 용지 한 장에 밴드를 한 번씩 쌓고 글상자·표 셀(병합·테두리·배경)·선·그림 자리를 좌표대로, 글꼴·정렬·줄바꿈까지 그린다. 정적 텍스트는 검정, 필드는 파란 `«필드명»`. 원본 양식 그림(`doc_image`)과 나란히 놓고 비전으로 대조하는 용도(설계 화면 — 데이터 반복·확장·페이지 나눔·서브리포트 내용 없음) |
| 설명 | `crf_render(path, [mode|params|data|data_file|rows|page|max_px|pdf|db])` | **실제 CLIP 서버 엔진으로 PDF 출력** → 한 쪽을 그림(MCP image)과 글자로. `crf_preview` 와 달리 진짜 출력이다: 데이터 행 반복, 셀 확장, 페이지 나눔, 그룹 소계·누적합산·크로스탭·조건 스타일·서브리포트(공통 로고)까지. **`mode=sample`**(기본): 모든 데이터셋에 가상 행을 넣는다(DB 접속 없음, 쿼리 실행 안 함) — `data={"데이터셋":[{필드:값,…},…]}`(또는 필드 순서 배열, TSV 문자열)로 직접 주거나, 안 주면 필드 이름·타입으로 `rows` 행 자동 생성(그룹 필드는 묶이게, 칸마다 다른 값). **`mode=db`**: 서버가 하는 그대로 서버 설정(`DataConnection.properties`)의 DB 에서 쿼리를 실행(`params={"이름":"값"}`) — 읽은 행 수를 데이터셋별로 알려 준다. PDF 를 따로 남기려면 `pdf=<경로.pdf>`. 원본 `.crf` 는 건드리지 않는다(`pdf` 가 리포트 파일이거나 `.pdf` 가 아니면 거부). [설정](#실제-엔진-출력-설정-crf_render) 필요 |
| 수정 | `crf_add_crosstab(path, row, column, value, output, [function|row_name|column_name|sort|total|header|format|section|left|top|row_width|col_width|row_height|…])` | **크로스탭(교차표) 생성** — `row` 필드 값이 행, `column` 필드 값이 열(값 개수만큼 열이 늘어남), 교차 칸에 `value` 를 `Sum|Count|Avg|Min|Max` 로 집계 + 행/열 합계. 예: 부서 × 직급 인원수. 데이터 머리글 밴드에 두고(없으면 생성), 코드와 표시명이 다르면 `row_name`/`column_name`. `crf_describe_layout` 이 크로스탭의 행·열 그룹과 네 행렬(머리/행 라벨/열 라벨/값)을 보여 준다 |
| 수정 | `crf_add_running_total(path, name, field, output, [function|reset_group])` | **누적합산 필드** 생성 — 레코드마다 `Sum|Count|Avg|Min|Max` 누적. `reset_group`(그룹 필드 이름 또는 번호)을 주면 그 그룹이 바뀔 때 초기화(=그룹 바닥글에 두면 소계), 안 주면 전체 누계. 만든 뒤 `crf_set_cell field=<이름>` 으로 바인딩. `crf_add_group subtotal=` 의 `rexpert.sum()` 공식 소계와 같은 값을 내는 디자이너 기본 방식이고, 본문에 두면 줄마다 누계가 찍힌다 |
| 설명 | `crf_diff(a, b)` | 두 리포트 **비교** — 데이터셋/필드/쿼리(줄 단위 ±)/scriptType/매개변수/공식 스크립트/그룹/섹션/컨트롤/표 셀 바인딩 |

### 업무지식 도구 — DB & 문서 (양식→백엔드→DB→쿼리 파이프라인)

| 분류 | 도구 | 설명 |
|---|---|---|
| DB | `db_tables(like?)` | 테이블 목록 + **코멘트(업무명)** 검색 (`ALL_TAB_COMMENTS`) |
| DB | `db_columns(table)` | 컬럼·타입·코멘트(`ALL_COL_COMMENTS`) → **컬럼 업무의미** 파악 |
| DB | `db_sample(table, n?)` | 상위 N행 샘플 (값 형태·코드값 확인) |
| DB | `db_query(sql, max?, allow_write?)` | `SELECT`/`WITH` 실행 (TSV, 행수 캡, 타임아웃 `CLIP_DB_TIMEOUT` 기본 60s) — 쓰기 문장은 `allow_write=true` 명시 필요 |
| 문서 | `doc_text(path, [detail|sheet|pages|max_chars])` | **양식 문서 읽기 — PDF · HWP(5.x) · HWPX · DOCX · XLSX/XLSM · XLS · PPTX · HTML · 이미지**(내용으로 형식 자동 인식). 글자뿐 아니라 **배치를 mm 로**: 용지·여백, 문단(y·정렬·글꼴 크기·굵게·색), 표(셀 `[행,열]`·병합·너비×높이), 도형/그림 위치, 엑셀(병합 범위·열 너비·셀 값·수식), 슬라이드 도형(x·y·크기), HTML 요소(헤드리스 Edge/Chrome 으로 A4 폭에 배치한 x·y·크기). 리포트 좌표(0.1mm) = mm×10. 이미지는 크기만(OCR 없음 → `doc_image`) |
| 문서 | `doc_image(path, [page|max_px|source])` | 그 문서를 **그림으로**(MCP image) 돌려줘 Claude 가 직접 보고 파싱 결과와 대조(비전 검증): 이미지는 그대로, PDF 는 쪽 렌더, HWP/HWPX/DOCX/XLSX/XLS/PPTX/HTML 은 **PC 에 설치된 한글·MS Office·LibreOffice·Edge/Chrome 으로 PDF 변환**(매크로 차단·읽기 전용, 결과 캐시) 후 렌더. 변환기가 없으면 파일에 들어 있는 첫 쪽 미리보기 그림 |
| 문서 | `pdf_text(path)` | PDF **텍스트만** 추출(예전 도구, 호환용 — 위치·다른 형식은 `doc_text`) |

> **양식 → 리포트 흐름(비전 검증 포함)**: ① `doc_text` 로 구조·좌표 판독 → ② `doc_image` 로 그림을 받아 **눈으로 대조**(파싱에 빠진 도장·선·체크칸, 병합·정렬·테두리, 쪽 수) → ③ 리포트 작업 → ④ `crf_preview` 로 리포트 배치 그림을 원본 그림과 나란히 대조 → 고치고 반복 → ⑤ 실서버 렌더로 최종 확인. 이 순서는 서버 instructions 와 `/clipreport` 명령에 내장돼 있습니다.

> **파이프라인**: 화면(xfdl/vue)·양식(PDF/HWP/DOCX/XLSX/PPTX/HTML/이미지)을 받으면 → 화면/문서를 읽고(`doc_text`+`doc_image`+Read) → 백엔드 매퍼·서비스를 **Grep으로 역추적** → `db_tables`/`db_columns`/`db_sample`/`db_query`로 **도메인 테이블·코드값** 확보 → 양식에 맞는 **리포트 양식 추천 + 쿼리 작성/변환**(파라미터는 `'{parameter.COLNM}'` 규칙). DB 접속정보는 **`.env`** 로 분리(아래 [DB 설정](#db-설정-env) 참조).

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
→ 클라이언트 재시작 → 도구 56개 노출. **MCP 서버에는 API 키 불필요**(키는 Claude 쪽).
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

### 실제 엔진 출력 설정 (crf_render)

디자이너 설치본(`Viewer.jar`)에는 출력 엔진이 없다. `crf_render` 는 **리포트 서버 웹앱**(WAS 에 올리는 그 프로젝트)의 엔진 jar 와 설정·라이선스를 그대로 빌려 쓴다 — 서버를 띄울 필요는 없고 폴더만 있으면 된다.

1. `.env`(위 DB 설정과 같은 파일) 또는 환경변수에:
   ```
   CLIP_RENDER_WEBAPP=C:/path/to/clipreport5/src/main/webapp
   ```
   그 안의 `WEB-INF/lib/ClipReport5.x.jar`(서버 엔진)·`ClipReport5.0-Common.jar` 와 `WEB-INF/clipreport5/clipreport5.properties`·`License.properties`·글꼴 폴더를 쓴다.
2. 폴더 구조가 다르면 `CLIP_RENDER_LIB`(jar 폴더)·`CLIP_RENDER_PROPS`(clipreport5.properties)를 따로. 추가 jar 는 `CLIP_RENDER_CP`.
3. `mode=db` 는 그 폴더의 `DataConnection.properties` 에 적힌 DB 로 접속한다(연결 이름은 `db` 인자 → `CLIP_RENDER_DBNAME` → `dataconnection1.dbname` 순). JDBC 드라이버는 서버 lib → MCP 서버 classpath 의 드라이버 → 디자이너 jar 폴더의 드라이버 순으로 찾는다.
4. 시간 제한 `CLIP_RENDER_TIMEOUT`(초, 기본 120).

동작: 엔진을 **별도 java 프로세스**로 띄우고(디자이너 jar 와 서버 jar 는 같은 패키지라 섞으면 안 됨) 서블릿 요청은 동적 프록시로 흉내 낸다. 설정 폴더는 임시 폴더(`%TEMP%/clip-report-mcp-render/`)에 복사해 쓰므로 엔진이 시작할 때 하는 temp/log 정리가 웹앱 소스 트리를 건드리지 않는다. 리포트 사본·설정 사본(DB 접속정보·라이선스 포함)·넘긴 데이터는 성공·실패·시간 초과 어느 경우든 바로 지우고, **PDF 와 엔진 로그만** 그 임시 폴더에 남는다(`pdf=` 를 주면 PDF 는 그 경로로 옮김). 남은 것은 **다음 `crf_render` 실행 때** 하루 지난 것부터 지운다 — 그 뒤로 실행하지 않으면 계속 남으므로 민감한 출력이면 폴더를 직접 지운다. 이 저장소에서 확인한 엔진은 5.0.343(Windows, JDK 17).

---

## 예시 대화 (자연어 → Claude가 도구 호출)

- *"report 폴더에 리포트 뭐뭐 있어?"* → `crf_list_reports`
- *"이 리포트 구조랑 쿼리 설명해줘: C:\...\xxx.crf"* → `crf_summary` + `crf_get_query` + `crf_describe_layout` → Claude가 풀이
- *"AHRM1234 테이블 쓰는 리포트 찾아줘"* / *"DEPTCD 매개변수 받는 리포트?"* → `crf_search(dir, "AHRM1234", scope="query")` / `scope="param"`
- *"부서코드 20399 로 조회하면 실제로 어떤 SQL 이 나가? 왜 0건이야?"* → `crf_get_query(params={"DEPTCD":"20399"})` 로 실행 SQL 확정 → `db_query` 로 실행
- *"이 한글(hwp) 양식대로 리포트 만들어줘"* → `doc_text`(문단·표·좌표) + `doc_image`(그림으로 받아 대조) → 결정 질문 → 배치 → `crf_preview` 로 원본 그림과 대조
- *"이 엑셀/파워포인트/HTML/스캔 이미지 양식 보고 어떤 항목이 있는지 정리해줘"* → `doc_text` + `doc_image`
- *"고친 리포트 실제로 어떻게 나오는지 보여줘. 60건쯤 넣어서 다음 쪽 머리글도"* → `crf_render(rows=60)` → `page=2`
- *"부서코드 20399 로 실제 데이터 넣어서 출력해 봐"* → `crf_render(mode=db, params={"DEPTCD":"20399"})`
- *"부서별·직급별 인원 교차표 만들어줘"* → `crf_add_crosstab(row=DEPT_NM, column=JGRD_NM, value=EMPNO, function=Count)` → `crf_render` 로 수치 확인
- *"이 리포트 문제 없는지 점검해줘"* → `crf_validate`(엑셀로 내려받는 목록형이면 `excel=true`)
- *"이 공식 뭐 하는 거야?"* → `crf_get_formula`
- *"이 리포트 레이아웃 보고 개선점 제안해줘"* → 설명 도구로 읽고 **편집 제안**
- *"이 SQL로 학과별 그룹 잡힌 초안 만들어줘, 출력은 out.crf"* → `crf_generate`(template=비슷한 목록형 리포트, `groups=DEPT_NM`, `columns` 로 제목·너비·합계 지정)
- *"방금 거 본문에 필드 깔고 직급으로 그룹 하나 더 추가해"* → `crf_place_detail_fields` + `crf_add_group`
- *"WHERE에 학기 조건 넣어서 다시 저장해"* → `crf_append_query_condition`(조건 하나 추가) 또는 `crf_set_query`(쿼리 통째 교체)
- *"3번째 열 뒤에 '비고' 열 하나 넣고 열 너비 균등하게"* → `crf_table_info` → `crf_table_cols(action=insert …)` → `crf_table_cols(action=equalize)`

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
- **엔진 진입점**: `Rexpert4.read(path)` / `Rexpert4.write(report, path)` (Viewer.jar, `com.clipsoft.clipreport.base`). 저장은 `Rexpert4.write` 를 직접 쓰지 말고 **`CrfIO.write`**.
- **엔진 기본 writer 의 저장 결함 3가지**(Viewer.jar·서버 5.0.343 공통, `CrfIO` 가 `RexpertFileWriter` 를 상속해 보정):
  ① `DataAccessMethodSQLWriter` 가 쿼리 본문(속성 900)을 평문 문자열(type 9)로 쓴다 — pre/post SQL(910/911)만 암호화(type 11). 디자이너가 저장한 파일은 900 도 암호화라, 그대로 저장하면 쿼리가 평문이 된다(저장소 2,549개 중 2,534개가 암호화 상태). 리더는 두 타입을 다 읽는다.
  ② `CrosstabCellNormalWriter` 가 헤더에 속성 23개를 선언하고 24개를 쓴다 → 크로스탭이 든 리포트는 저장 후 되읽기에서 `EOFException`(저장소의 크로스탭 리포트 48개 전부).
  ③ `WebLinkInfoWriter` 가 팝업 너비를 팝업 높이의 속성 ID(48116)로 쓴다 → 되읽으면 높이 값이 바뀐다(8개).
  보정 후 저장소 전체 2,549개가 읽기→저장→읽기→저장에서 내용이 같다.
- **크로스탭 구조**: `ControlCrosstab` = 행/열 `GroupCrosstab`(그룹 필드 + 표시 이름 필드) + 네 행렬(머리 1×1, 행 라벨, 열 라벨, 값). 라벨 셀은 **필드 없이 `ApplyValueType.Field`** 이면 그 축의 그룹 이름이 찍힌다. 값 셀은 필드 + `Summary.function`. 합계는 각 축 행렬의 둘째 칸(`RowTotal`/`ColumnTotal`). 보통 데이터 머리글 밴드에 두고 본문 높이는 0.
- **누적합산**: `FieldRunningTotal` = 요약 필드 + 함수 + `ForEachRecord` + (`ResetNever` | `ResetOnChangeOfGroup` + 그룹). 그룹 바닥글에 두면 `rexpert.sum(0,"data.F",0,"data.그룹필드","")` 공식과 같은 값(실제 엔진 출력으로 대조).
- **서버 엔진 직접 호출**: `ClipReportExport.createExportForPDF(HttpServletRequest, OutputStream, propertiesPath, OOFDocument, null)` — 요청 객체는 `getRealPath`·세션 속성 정도만 응답하는 프록시면 된다. 가상 데이터는 데이터셋의 접근 방식을 CSV 로 바꾸고 데이터셋마다 연결(`MEMO_i`)을 하나씩 만들어 `OOFFile.addConnectionMemo(연결이름, TSV).addContentParamCSV("*","UTF-8","\\t","\\r\\n","","0")`. 실제 DB 는 `addConnectionData("*", dbname)`. 링크 서브리포트는 리포트 파일 위치 기준 **상대경로로만** 찾는다. 엔진은 시작할 때 설정의 temp 폴더를 비운다.
- **동적쿼리**: `DataAccessMethodSQL.scriptType = JavaScript` 면 `queryString` 은 `var sql=""; sql+="..."; if(...){...}` 로 SQL을 조립하는 JS. MyBatis `<if>`→`if`, `#{x}`/`${x}`→`{parameter.X}`.
- **JS 쿼리 실행**: CLIP 엔진 jar 폴더의 `batik.jar` 에 Rhino(1.6R6)가 들어 있어 별도 의존성 없이 JS 동적쿼리를 실행해 볼 수 있다(`CrfJsEval`). `{parameter.X}` 는 스크립트 실행 **전에** 값으로 문자 치환되므로 조건식은 `'{parameter.X}' != ''` 처럼 따옴표로 감싼다. 저장소 실측: JS 쿼리 1,495개 중 1,494개가 매개변수 빈 값으로 그대로 실행되고, 나머지 1개는 `SQL`/`sql` 대소문자를 섞어 쓴 실제 버그였다.
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

- `crf_generate` v2 는 **목록형**(본문 표 한 줄 반복) 전용이다. 문서형(양식) 리포트는 [레시피](docs/document-report-recipe.md)대로 `crf_add_table`/`crf_add_label`/`crf_set_cell` 등을 조합한다. **크로스탭 생성**은 행 그룹 1 × 열 그룹 1 × 값 1(`crf_add_crosstab`, 세 필드는 같은 데이터셋의 데이터/공식 필드)까지 — 다단 그룹·값 여러 개·셀 서식 세부는 디자이너에서. 차트·바코드·서브리포트 **생성**은 미지원(읽기·위치/표시 편집만).
- 필드 동기화는 SELECT 파싱(별칭 없는 식·중복 이름은 `COL_n` 자리 필드로 위치만 지킴 — `AS` 별칭 권장) 또는 DB 실행 기반. `mode=db` 는 `.env` DB 연결이 필요하고, 매개변수 값이 없으면 `''`/NULL 로 바인딩해 메타데이터만 읽는다(조건에 따라 타입 오류가 나면 `params` 로 값 지정).
- `crf_add_table` 로 만든 표는 SDK 기본 속성(테두리/여백)을 쓴다(대각선은 v0.7.0 부터 자동 제거, 글꼴은 v0.7.1 부터 리포트 글꼴 상속) — 디자이너에서 한 번 열어 확인. 그룹 소계 공식(`rexpert.sum(0,"data.F",0,"data.그룹필드","")`)은 v0.10.0 에서 실서버 렌더로 한 차례(1단계 그룹) 확인했다 — 다단 그룹 등에서 결과가 다르면 `crf_set_cell formula=` 로 조정.
- [docs/PLAN-v0.5.md](docs/PLAN-v0.5.md) 의 로드맵 항목은 v0.10.0 까지 모두 구현됐다. 버전별 변경과 미반영 항목은 [CHANGELOG.md](CHANGELOG.md).
- `crf_get_query` 의 JS→평문 복원은 문자열 연결을 풀고 `if` 블록을 주석으로 표시한 **추정본**이다(테이블 목록도 FROM/JOIN 정규식 추정). 정확한 문장은 `params` 를 줘서 **실행 SQL** 로 본다 — 단 리포트 서버가 아니라 MCP 서버에서 같은 종류의 JS 엔진(Rhino)으로 돌린 결과이고, `{dataset.X}`(다른 데이터셋 값)는 `params` 로 주지 않으면 빈 값이다.
- JS 쿼리 lint(`crf_validate`)는 **매개변수가 전부 빈 값일 때** 한 번 실행해 본 결과다. 특정 값에서만 타는 분기의 오류는 `crf_get_query params=…` 로 그 값을 넣어 확인한다.
- 문서 파서(`doc_text`): **OCR 은 없다** — 이미지·스캔 PDF 의 글자는 `doc_image` 로 받은 그림을 Claude 가 직접 읽는다. HWP 는 5.x 만(3.x, 암호·배포용 문서는 안내 메시지), 구버전 `.doc`/`.ppt` 는 미지원(.docx/.pptx/PDF 로 저장). 문단 y 는 문서에 저장된 줄 배치 값(본문 영역 기준)이고, 표 셀·글상자 안 문단의 y 는 그 안에서의 위치다. XLSX 열 너비 mm 는 근사값. HTML 좌표는 Edge/Chrome 이 있어야 나오고(없으면 글자·표 구조만), 창 폭 794px(A4) 기준 배치다. PPTX 의 그룹 안 도형은 그룹 내부 좌표다.
- 문서 → 그림(`doc_image`): 변환은 **이 PC 에 설치된 프로그램**에 의존한다. 이 저장소에서 실제로 확인한 조합은 MS Word/Excel/PowerPoint(DOCX·XLSX·XLS·PPTX), Edge(HTML), HWP 내장 미리보기 그림이며, **한글(HWPFrame.HwpObject) 자동화와 LibreOffice 경로는 코드만 있고 실기 검증은 못 했다**(한글은 보안 모듈 미등록 시 확인 창에서 멈춰 90초 뒤 시간 초과 → 미리보기 그림으로 대체). 변환이 90초 안에 안 끝나 중단되면 Word/Excel/PowerPoint/한글 프로세스가 창 없이 남을 수 있다 — 사용자가 띄운 프로그램과 구분할 수 없어 자동으로 끄지 않으므로 작업 관리자에서 확인한다. 미리보기 그림은 첫 쪽·저해상도뿐이다.
- `crf_render`: 리포트 **서버 웹앱 폴더**가 이 PC 에 있어야 한다(없으면 설정 안내와 함께 ERROR). `mode=sample` 은 쿼리를 실행하지 않으므로 쿼리·실제 수치는 검증되지 않고, 링크 서브리포트(공통 로고 등)는 파일만 따라가므로 **그 안의 쿼리 데이터셋은 비어 나온다**(상위 폴더를 9단계 넘게 올라가는 링크는 빠짐). 자동 생성 값은 필드 이름·타입으로 추정한 것이라 날짜·코드 형식이 실제와 다를 수 있다 — 형식이 중요하면 `data` 로 준다. `mode=db` 는 리포트의 SQL(pre/post SQL 포함)을 서버 설정의 DB 계정으로 **실제 실행**하므로 개발 DB 에서만 쓴다. 화면이 넘기는 세션 값(`g_userNm` 등)은 `params` 로 직접 줘야 한다. `mode=db` 는 이 ERP 의 리포트 서버 JSP 와 같이 **모든 데이터셋을 연결 이름 하나**(`db`)로 보낸다 — 데이터셋마다 다른 DB 를 쓰는 리포트는 맞지 않는다. `mode=sample` 에서 일반 데이터셋이 아닌 것(있다면)은 가상 데이터를 넣지 못하고 ⚠ 로 알린다. 링크 서브리포트는 `.crf` 만 따라가며, 리포트가 가리키는 상대경로의 파일을 그대로 읽으므로 **출처를 모르는 `.crf` 를 렌더하면 그 PC 의 다른 리포트 디자인이 출력에 섞일 수 있다**. 엔진 5.0.343 외 버전·Linux 는 확인하지 않았다. 디자이너에서 열어 다시 저장하는 호환성은 별도 확인.
- `crf_preview` 는 **설계 화면 그림**이다: 밴드를 한 번씩 쌓아 그리므로 데이터 행 반복, 확장(CanGrow), 페이지 나눔, 서브리포트 내용, 조건 스타일, 차트/바코드 모양, 선 굵기·점선 구분은 반영하지 않는다. 글꼴이 PC 에 없으면 맑은 고딕으로 대체된다. 실제 출력은 `crf_render` 로 확인.
- 도구 실패는 `ERROR: …`(MCP `isError`)로 반환. 쓰기 도구는 `output` 이 원본과 같으면 거부(`in_place=true` 는 예외 — `.bak` 백업 후 원본에 씀).
- MyBatis 변환: `<include>`/`<bind>` 는 펼치지 않는다(경고). `<foreach>` 는 컬렉션을 **쉼표 문자열 매개변수**(`A,B,C`)로 받는 split 루프가 되므로 화면에서 그렇게 넘겨야 하고, 원소의 속성(`item.code`)은 쓸 수 없어 원소 자체로 대체한다(경고). `<where>`/`<trim>`/`<set>` 가 조건 조각의 첫머리에 `AND` 가 없거나 `OR` 로 시작하는 등 단순 `WHERE 1=1` 로 못 바꾸는 경우에는 쿼리 스크립트 안에 `__trim` 도우미 함수를 넣어 실행 시점에 정리한다 — 이 형태는 MCP 의 JS 엔진으로는 검증했지만 **실서버 렌더로는 아직 확인하지 않았다**.
- DB 도구(`db_*`, `crf_sync_fields mode=db`, `fields_from_db`)는 Tibero/Oracle URL 이면 드라이버에 직접 연결한다(`crf_render mode=db` 는 서버 엔진이 자기 방식으로 접속 — 그 자식 프로세스 classpath 에는 Sybase 드라이버를 넣지 않는다). 그 밖의 DB 는 `DriverManager` 를 거치는데, CLIP 디자이너 jar 폴더에 든 Sybase jConnect(`jconn4.jar`)가 `DriverManager` 초기화 때 무한 재귀(StackOverflowError)를 일으켜 같은 프로세스의 다른 기능이 간헐적으로 깨질 수 있다 — 그런 DB 를 쓰려면 classpath 를 `bin\jar\*` 대신 필요한 jar 만 나열한다.
- 원격 HTTP: 운영 시 **TLS + 강한 인증**, localhost 바인딩 또는 사내망 한정 권장.

---

## 보안 & 라이선스

- **Clipsoft 엔진 jar는 라이선스 자산** — 공개 저장소 포함 금지. 본 프로젝트(래퍼 코드)만 공유, 실행 호스트에 CLIP report 설치 필요.
- 쓰기 도구는 기본 `output` 별도 지정 → **원본 비파괴**. `in_place=true` 일 때만 원본에 쓰고 첫 쓰기 전에 `<path>.bak` 백업.
- **저장 시 쿼리 본문은 암호화**된다(`CrfIO`, 엔진의 암호화 함수 사용 — 디자이너 저장과 같은 형식). v0.11.0 까지의 도구로 저장한 리포트는 쿼리가 **평문**으로 들어 있다: 그런 파일은 `java -cp "<CLIP jar>\*;clip-report-mcp.jar" CrfIO in.crf out.crf` 또는 아무 수정 도구로 한 번 다시 저장하면 암호화된다. 암호화는 파일을 열어 본 사람이 쿼리를 바로 읽지 못하게 하는 수준이고(엔진 jar 가 있으면 누구나 복호), 접근 통제를 대신하지 않는다. `CLIP_SQL_PLAIN=1` 이면 평문으로 쓴다(디버깅용).
- `crf_render mode=db` 는 리포트에 든 SQL 을 서버 설정의 DB 계정으로 실행한다 — **출처를 모르는 `.crf` 에는 쓰지 말 것**(`mode=sample` 은 DB 에 접속하지 않고 쿼리도 실행하지 않는다). 출력 PDF 에는 실제 데이터가, 엔진 로그에는 실행한 SQL 이 들어가며 `%TEMP%/clip-report-mcp-render/` 에 남는다(다음 실행 때 하루 지난 것 삭제 — 위 설정 절 참조).
- `.crf` 안의 JS 쿼리를 실행해 볼 때(`crf_get_query params`, `crf_validate`, `crf_set_query` 검사 등)는 **샌드박스**: 스크립트에서 Java 클래스 접근(`java.*`/`Packages`)을 막고 3초 시간 제한을 둔다. DB 에는 아무것도 보내지 않는다(DB 를 치는 것은 `db_*` 도구와 `mode=db`/`fields_from_db` 뿐).
- 문서 파서는 XML 외부 엔티티(XXE)·DTD 를 끄고, 압축 해제 크기(항목당 64MB·호출당 256MB)와 그림 크기(1.2억 화소, 풀기 전에 머리글로 확인)를 제한한다.
- `doc_image` 의 변환과 HTML 판독은 문서를 **실제 프로그램으로 여는 것**이다. Office 는 매크로 강제 차단 + 읽기 전용으로 연다. HTML 은 임시 프로필의 헤드리스 브라우저에서 그 페이지의 스크립트가 실행되지만 **네트워크는 차단**한다(모든 요청을 죽은 프록시로 보내고 DNS 를 막음 — 로컬 서버로의 요청도 닿지 않는 것을 차단 전/후 대조로 확인). 그래서 원격 CSS·글꼴·그림은 빠진 채 그려진다. 신뢰하는 문서에 한해 환경변수 `CLIP_DOC_ALLOW_NETWORK=1` 로 풀 수 있다. **출처를 모르는 Office/HWP 문서에는 `doc_text` 만 쓰거나 `source=preview` 를 쓴다.**
- 변환 결과 PDF 는 `%TEMP%\clip-report-mcp-doc\` 에 문서 내용의 SHA-256 이름으로 캐시된다. 민감한 문서를 다뤘다면 그 폴더를 지운다.
- JS 쿼리 샌드박스는 Java 접근과 무한 루프는 막지만, 스크립트가 엔진 내장 함수 한 번으로 아주 큰 문자열을 만드는 것까지는 중간에 끊지 못한다(스크립트 1MB·결과 2MB 상한, 메모리 부족은 오류로 돌려줌). 출처를 모르는 `.crf` 를 대량으로 돌릴 때는 별도 프로세스에서 실행하는 것이 안전하다.
- 원격 서버 기본 `127.0.0.1` 바인딩. 외부 공개 시 `CLIP_MCP_TOKEN` + 리버스 프록시(TLS).
- **DB 자격증명**은 `.env`(또는 환경변수)로만 — `.gitignore`·배포 zip 제외, 사내 한정 공유. `db_query` 는 운영 DB 직결이므로 **읽기전용 계정 + SELECT 한정** 권장.
