---
description: 화면/PDF/쿼리/리포트 중 "있는 것"으로 CLIP 리포트 설계·수정·쿼리생성 (clip-report MCP)
argument-hint: [.crf | 화면.xfdl/.vue | PDF | SQL | 테이블명 | 폴더] "할말"  (※ 일부만/글만 줘도 됨)
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

## 0) 상황 파악 먼저 (항상)
"실제로 무엇이 주어졌나 / 무엇을 원하나"를 먼저 식별 — 아래는 다 있을 수도, 일부만, 글 하나만 있을 수도 있다:
- 대상 `.crf`(파일/폴더)? · 화면(`.xfdl`/`.vue`)? · 문서양식(PDF)? · 쿼리(SQL/MyBatis) 텍스트? · 백엔드 경로/힌트? · 테이블명? · DB 연결(`.env`)? · 아니면 **자연어 설명만**?
- 의도? 설명 / 생성 / 수정 / 쿼리작성 / 쿼리변환 / 양식추천
→ **도구는 "있는 입력 + 의도"로 선택**(고정 순서 아님). 없는 입력을 전제로 한 단계는 그냥 건너뛴다.

## 1) 있는 자료만 읽어 확보 (해당될 때만)
- `.crf` 있으면 → `crf_summary` (+`crf_describe_layout`): 데이터셋·필드·그룹·섹션·표 셀
- 화면 있으면 → **Read**: 항목·조회조건·그리드·트랜잭션/데이터셋ID·테이블명
- PDF 있으면 → `pdf_text`: 양식 항목·레이아웃
- 쿼리 텍스트 있으면 → 그대로 파싱: 컬럼·파라미터·동적조건
- 백엔드 힌트/화면ID 있으면 → **Grep**: 매퍼 `.xml`·서비스 역추적해 실제 SQL
- DB 연결되고 테이블 단서 있으면 → `db_tables`(이름검색) → `db_columns`(주석=업무의미) → `db_sample` → `db_query`(SELECT)

## 2) 부족한 정보는 질문으로 확보 (추정 금지)
도구로 알 수 있는 건 직접 확인하고(파일 Read·`db_*`·Grep), **그래도 부족하거나 불명확하면 임의 추정·기본값으로 진행하지 말고 반드시 유저에게 질문**한다. 질문은 **한 번에 모아** 간결하게(필요 항목 묶어서).
- 쿼리 작성/변환 → 대상 테이블·뷰, 조인키, 파라미터(화면→리포트), 필터·정렬·그룹 기준 중 불명확한 것
- 양식 추천/생성 → 대상 `.crf`/출력경로, 용지·방향, 어떤 항목을 어디에(머리글/본문/소계)
- 화면만 줌 → 연결 백엔드(매퍼 경로)·트랜잭션ID가 안 잡히면 질문
- 대상 자체가 모호 → 어느 파일/폴더/테이블/화면인지 질문
- **예외**: 유저가 "추정해서 진행"을 명시한 경우에만 가정을 밝히고 진행.

## 3) 수행 + 보고
- 의도→도구: 설명·제안=`crf_summary`+`crf_describe_layout` · 쿼리 `crf_set_query` · 그룹 `crf_add_group` · 본문필드 `crf_place_detail_fields` · 셀 값/형식 `crf_set_cell` · 셀 스타일 `crf_set_cell_style` · 계산필드 `crf_add_formula_field` · 필드/라벨 `crf_add_data_field`/`crf_add_label` · 용지 `crf_set_paper` · 비교 `crf_diff` · 생성 `crf_generate`
- 쓰기는 `<원본>_edited.crf` 로 **원본 보존**. 끝에 **[사용한 입력 / 가정·추정 / 건너뛴 단계 / 출력경로]** 를 한 번에 보고.

## 규칙
- **쿼리 파라미터는 반드시 `'{parameter.COLNM}'`**(대문자·언더바 유지: `empNm`→`EMPNM`, `emp_nm`→`EMP_NM`). 문자열 조건은 `= '{parameter.X}'`. **`:colNm`·`#{}`·`${}`·`?` 금지.**
- CLIP 개념·용어(섹션 7종/필드종류/출력양식/요약함수/조건스타일 등)는 서버 instructions·GUIDE.md를 따름.
- DB는 **SELECT 위주(읽기)**. MCP 미연결이면 `/mcp` 확인 후 README 안내. DB 도구는 `.env`(`CLIP_DB_*`)·Tibero JDBC jar 필요.
