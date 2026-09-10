---
description: 화면/PDF/쿼리/리포트 중 "있는 것"으로 CLIP 리포트 설계·수정·쿼리생성 (clip-report MCP)
argument-hint: [.crf|화면.xfdl/.vue|PDF|SQL|테이블|폴더] "할말" (※일부만/글만도OK)  |  메타 --help|--version|--changelog|--doctor|--update
---

입력: `$ARGUMENTS` — 보통 첫 토큰=대상 경로, 나머지=자연어 요청("할말"). **단 형식은 자유** — 경로가 없을 수도, 여러 개일 수도, 첨부 없이 글로만 올 수도 있다.

> ⚠️ **상황 우선 + 모르면 질문.** 화면·PDF·쿼리·백엔드·DB가 **항상 다 있는 게 아니다**(화면만, 쿼리 없이, 그냥 말로만일 수도). 고정 파이프라인을 강요하지 말고 **실제로 있는 것**에 맞춰 도구를 골라라. 적용 안 되는 단계는 건너뛰되, **필요한데 없는 정보는 임의로 추정하지 말고 유저에게 질문**한다.

## ★ `--` 메타 명령 (리포트 작업보다 우선)
`$ARGUMENTS` 의 **첫 토큰이 `--` 로 시작**하면, 아래 0~3 리포트 플로우 **대신** 해당 메타 명령만 수행한다.
**공통 — 프로젝트 폴더 찾기**: clip-report MCP 설정 classpath 의 `clip-report-mcp.jar` 경로의 **상위 폴더**가 프로젝트다(Claude Code `~/.mcp.json`, Desktop `%APPDATA%\Claude\claude_desktop_config.json`). 못 찾으면 `~/clip-report-mcp` 시도 → 그래도 없으면 유저에게 폴더를 묻는다. (`--help` 는 폴더 없이 가능)

- **`--help`** : 사용법 한 줄 + 아래 메타 명령 목록을 출력. (폴더 불필요)
- **`--version`** : 프로젝트 `VERSION` 파일(설치본)과 GitHub `main` 의 `VERSION`(raw.githubusercontent…/main/VERSION) 을 비교해 **현재 / 최신 / 업데이트필요 여부**를 보고.
- **`--changelog`** : `<proj>/CHANGELOG.md` 의 최신 항목을 읽어 보여줌.
- **`--doctor`** : `<proj>/doctor.ps1` 실행 → 진단 결과를 그대로 보고(서버 연결 안 될 때).
- **`--update`** : `<proj>/update.ps1` 실행(실행중 서버 종료 → `git pull` → JDK 있으면 재빌드 → `/clipreport` 명령 갱신). 출력을 보고하고, 끝에 **"`/mcp` 로 clip-report 재연결(또는 Claude 재시작)하면 적용"** 안내. (git 저장소 아니면 update.ps1 이 안내하고 멈춤 → GitHub clone 권장)

미지원 `--xxx` 면 `--help` 를 보여준다. **메타 명령 분기일 땐 아래 0~3 일반 플로우를 타지 않는다.**

> 💡 위 메타 명령은 `/clipreport-version`·`/clipreport-changelog`·`/clipreport-doctor`·`/clipreport-update` 처럼 **별도 슬래시 명령으로도** 실행 가능하다(이쪽은 탭 자동완성됨). 동작은 각각 `--version`/`--changelog`/`--doctor`/`--update` 와 동일.

## 0) 상황 파악 먼저 (항상)
"실제로 무엇이 주어졌나 / 무엇을 원하나"를 먼저 식별 — 아래는 다 있을 수도, 일부만, 글 하나만 있을 수도 있다:
- 대상 `.crf`(파일/폴더)? · 화면(`.xfdl`/`.vue`)? · 문서양식(PDF)? · 쿼리(SQL/MyBatis) 텍스트? · 백엔드 경로/힌트? · 테이블명? · DB 연결(`.env`)? · 아니면 **자연어 설명만**?
- 의도? 설명 / 생성 / 수정 / 쿼리작성 / 쿼리변환 / 양식추천
→ **도구는 "있는 입력 + 의도"로 선택**(고정 순서 아님). 없는 입력을 전제로 한 단계는 그냥 건너뛴다.

## 1) 있는 자료만 읽어 확보 (해당될 때만)
- `.crf` 있으면 → `crf_summary` (+`crf_describe_layout`): 데이터셋·필드·그룹·섹션/서브섹션·표 셀. **쿼리 본문이 필요하면 `crf_get_query`**(JS 동적쿼리는 평문 복원본 포함), 공식은 `crf_get_formula`
- "어떤 리포트가 테이블/컬럼/매개변수/문구 X 를 쓰나" → `crf_search(dir, text, scope=query|xpath|field|formula|param|control|any)` (폴더 재귀, 파일명 필터 `like`)
- 화면 있으면 → **Read**: 항목·조회조건·그리드·트랜잭션/데이터셋ID·테이블명
- **참조 양식(HWPX/PDF/DOCX/HTML/이미지)이 있으면 → 요소 전부를 좌표까지 판독**(아래 1-1)
- 쿼리 텍스트 있으면 → 그대로 파싱: 컬럼·파라미터·동적조건
- 백엔드 힌트/화면ID 있으면 → **Grep**: 매퍼 `.xml`·서비스 역추적해 실제 SQL
- DB 연결되고 테이블 단서 있으면 → `db_tables`(이름검색) → `db_columns`(주석=업무의미) → `db_sample` → `db_query`(SELECT)

## 1-1) 참조 양식은 모든 요소의 배치·좌표를 꼼꼼히 (형식 불문)
눈에 띄는 것만 옮기지 말고 **모든 요소**를 인벤토리한다: 제목·부제·문단·표(행/열/셀 크기·병합)·선·상자·도형·체크칸·서명란·직인 자리·머리글/바닥글·페이지번호·로고·용지 여백. 요소마다 **x,y,너비,높이(mm) · 정렬 · 글꼴/크기/굵게/색 · 테두리/배경 · 데이터 자리(파란 글자 등)** 를 표로 적고 나서 배치를 시작한다.
- HWPX → `samples/document/parse_hwpx.py`(문단 `vertpos`, 표 셀 크기, 도형 절대좌표, **표 앵커 문단 정렬**)
- PDF → `pdf_text` 는 위치가 사라짐 → **PyMuPDF** 로 텍스트 블록 bbox + 페이지 이미지(Read 로 눈으로 확인)
- DOCX → `word/document.xml`(문단·표·정렬·섹션 여백, EMU→mm = ÷36000)
- HTML → 렌더 스크린샷 + 요소 박스(`getBoundingClientRect`)
- 이미지 → Read 로 보고 비율로 좌표를 잡되 실제 용지 크기는 유저에게 확인
인벤토리에 있는 요소가 결과물에 없거나 위치·정렬이 다르면 **미완성**. 끝에 실서버 렌더(`render_report.cjs`)를 원본과 요소별로 나란히 대조하고 차이를 보고한다.

## 2) 부족한 정보는 질문으로 확보 (추정 금지)
도구로 알 수 있는 건 직접 확인하고(파일 Read·`db_*`·Grep), **그래도 부족하거나 불명확하면 임의 추정·기본값으로 진행하지 말고 반드시 유저에게 질문**한다. 질문은 **한 번에 모아** 간결하게(필요 항목 묶어서).
- 쿼리 작성/변환 → 대상 테이블·뷰, 조인키, 파라미터(화면→리포트), 필터·정렬·그룹 기준 중 불명확한 것
- 양식 추천/생성 → 대상 `.crf`/출력경로, 용지·방향, 어떤 항목을 어디에(머리글/본문/소계)
- 화면만 줌 → 연결 백엔드(매퍼 경로)·트랜잭션ID가 안 잡히면 질문
- 대상 자체가 모호 → 어느 파일/폴더/테이블/화면인지 질문
- **예외**: 유저가 "추정해서 진행"을 명시한 경우에만 가정을 밝히고 진행.

## 2-1) 디자인 결정은 반드시 유저에게 묻기 (중간중간)
정보가 다 있어도 **"어떻게 만들지"의 갈림길은 유저의 취향/업무 판단**이다. 새로 만들거나 크게 고칠 때는 작업 전에 **결정 목록(3~6개)을 한 번에** 제시하고, 항목마다 **권장안(기본값)과 한 줄 이유**를 붙여 답을 받은 뒤 진행한다. 진행 중 새 갈림길이 나오면 그 시점에 다시 묻는다. 유저가 이미 지정했거나 "알아서/추정해서" 라고 한 항목만 생략.
- **요소 구성**: 연속 줄(목록·문단·※주석)을 1열 표 하나 / 줄바꿈 글상자 하나 / 글상자 여러 개 중 무엇으로? (권장: 표 하나)
- **체크박스**: 셀 체크박스 vs 독립 컨트롤, 체크 모양 색칠(Rectangle)/V/원, 상자 모양·크기, 항목 여러 개면 항목별 칸?
- **레이아웃(양식 없이 새로 만들 때)**: 목록형(표+머리글) vs 문서형(양식), 용지·방향·여백, 제목/부제/출력일시/페이지번호/로고 위치, 열 순서·너비·정렬·출력양식(숫자/날짜), 그룹·소계·총계, 서명란·비고란
- **글꼴**: 이 리포트/모듈 관례(돋움체·바탕체·나눔고딕)와 크기, 라벨·데이터 통일 여부
- **쿼리**: 매개변수 이름·정렬·조건, 코드→명칭 변환 여부
- **저장**: output 파일명·위치, 기존 파일 덮어쓸지
형식: `항목: 선택지 A / B / C (권장 A — 이유)`. 답을 받으면 결정 요약을 한 줄로 되짚고 진행. 답 없이 임의로 정해 놓고 나중에 "이렇게 했다"고 통보하지 말 것.

## 3) 수행 + 보고
- 의도→도구: 설명·제안=`crf_summary`+`crf_get_query`+`crf_describe_layout` · 리포트 찾기 `crf_search`/`crf_list_reports` · 공식 읽기 `crf_get_formula` · 쿼리 `crf_set_query`(매개변수 선언·필드 추가 자동; `SELECT *` 면 `crf_sync_fields mode=db`) · 데이터셋 `crf_add_dataset`/`crf_remove_dataset` · 매개변수 `crf_set_param`/`crf_remove_param` · 필드 `crf_rename_field`/`crf_remove_field`(참조는 `crf_field_refs`) · 그룹 `crf_add_group` · 본문필드 `crf_place_detail_fields` · 셀 값/공식/스타일 `crf_set_cell` · 글상자 `crf_set_label`(추가는 `crf_add_label`) · 밴드 행 `crf_set_subsection` · 표 생성 `crf_add_table`(columns 필드 표 / rows 문단 목록 표) · 표 구조 `crf_table_info`→`crf_table_rows`/`crf_table_cols`(action=insert|copy|delete|move|resize|equalize)·`crf_set_table`(위치/비례 크기/테두리 일괄/unmerge_all) · 글상자 합치기/나누기 `crf_merge_labels`/`crf_split_label` · 글꼴 일괄 `crf_set_font` · 그룹 `crf_add_group`(level/label/subtotal)/`crf_set_group`/`crf_remove_group` · 삭제 `crf_remove_control`/`crf_remove_section` · 계산필드 `crf_add_formula_field` · 필드 `crf_add_data_field` · 용지 `crf_set_paper` · **수정 후 `crf_validate`** · 비교 `crf_diff` · 생성 `crf_generate`
- 쓰기는 `<원본>_edited.crf` 로 **원본 보존**. 끝에 **[사용한 입력 / 가정·추정 / 건너뛴 단계 / 출력경로]** 를 한 번에 보고.

## 규칙
- **쿼리 파라미터는 반드시 `'{parameter.COLNM}'`**(대문자·언더바 유지: `empNm`→`EMPNM`, `emp_nm`→`EMP_NM`). 문자열 조건은 `= '{parameter.X}'`. **`:colNm`·`#{}`·`${}`·`?` 금지.**
- CLIP 개념·용어(섹션 7종/필드종류/출력양식/요약함수/조건스타일 등)는 서버 instructions·GUIDE.md를 따름.
- **요소 구성**: 세로로 이어지는 문단·번호 목록·※주석은 글상자를 줄마다 만들지 말고 **요소 하나**(`crf_add_table rows=[…]` 1열 표 또는 줄바꿈 글상자)로. 나누는 건 정렬·글꼴·바인딩·위치가 다를 때만. 쪼개진 건 `crf_merge_labels`, 덩어리를 나눌 땐 `crf_split_label`.
- **글꼴**: 새 글상자/셀은 리포트 기존 글꼴(라벨/데이터 각각)을 상속(SDK 기본 `System` 금지). 저장소 관례 목록형=돋움체·문서형=바탕체·일부 모듈=나눔고딕, 라벨·데이터 글꼴 통일. 일괄 정리 `crf_set_font`. `crf_validate` 경고를 남기지 말 것.
- **화면→인쇄물**: 빨간 강조·버튼 문구·안내 배너 제외. 체크 모양(색칠/V)은 문서마다 지시 따르고 없으면 질문. 엑셀 격자(머리글 표 열 경계 ⊂ 본문 표 경계). 날짜 파라미터는 `TO_DATE` 먼저. 쿼리는 디자이너 통째 교체 금지 → `crf_set_query`.
- DB는 **SELECT 위주(읽기)**. MCP 미연결이면 `/mcp` 확인 후 README 안내. DB 도구는 `.env`(`CLIP_DB_*`)·Tibero JDBC jar 필요.
