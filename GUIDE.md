# CLIP report 개념 가이드

Clipsoft 공식 교육가이드(초급/중급/고급) 요약. 이 어시스턴트가 리포트를 **설명·제안**할 때 쓰는 정식 용어/개념이며,
MCP 서버는 이 내용을 `instructions` 로 Claude에 자동 주입합니다.

---

## 1. 섹션(밴드) — 출력 영역

| 섹션 | 출력 시점 |
|---|---|
| 보고서 머리글 / 바닥글 | 첫 페이지 / 마지막 페이지에 **1회** |
| 페이지 머리글 / 바닥글 | **매 페이지** 상단 / 하단 (※ 보고서 머리글 우선) |
| 데이터 머리글 / 바닥글 | 본문 상단 / 하단에 1회 |
| **그룹 머리글 / 바닥글** | 그룹핑 단위 (그룹마다 반복) |
| **본문(Detail)** | **레코드 수만큼 반복** |

출력 순서: 보고서머리글 → (페이지머리글 → 데이터머리글 → [그룹머리글 → 본문×N → 그룹바닥글]×그룹 → 데이터바닥글 → 페이지바닥글) → 보고서바닥글

---

## 2. 데이터셋

- 커넥션: **JDBC**(DB), **XML**, **CSV**, **JSON**.
- 쿼리: 정적 SQL 또는 **동적쿼리(JavaScript)** — `var sql=""; sql+="..."; if(...){...}` 로 조립.
- **매개변수**로 SQL에 조건: `WHERE col = '{parameter.X}'`. 입력 없을 때 전체 출력은 동적쿼리로 처리.
- **마스터-디테일**: 상위 섹션(master) + 리포트 서브섹션(detail 데이터셋), `{dataset.키}`(데이터셋 파라미터) 또는 데이터필터로 조인.

---

## 3. 필드 종류

| 종류 | 설명 | 스크립트 접두사 |
|---|---|---|
| 데이터 | DB 컬럼 값 | `Data` |
| 매개변수 | 사용자 입력(→SQL 조건). 토큰 `{parameter.X}` (대문자·언더바 보존) | `Parameter` |
| 시스템 | 페이지/레코드/날짜 등 (아래 표) | `system` |
| 공식 | JS/VBScript 계산식 | `Formula` |
| 누적합산 | 누적 요약값(그룹마다 초기화 가능) | `Runningtotal` |
| 그룹 이름 / 인덱스 | 그룹 기준 값 / 순번 | `Groupname` / `Groupindex` |
| 상위리포트 | 부모 리포트 값 | `Parent` |

### 시스템 필드
`PageNofM`(N/M), `PageNumber`, `PageCount`, `PrintDateTime`, `RecordNumber`, `RecordCount`,
`RepeatedSectionNumber`, `ResettableRecordNumber`, `TotalPageNofM`, `TotalPageNumber`, `TotalPageCount`

### 공식 필드 (formula) — JavaScript 계산식

- **엔진 = JavaScript, 식 끝에 `return` 필수.** 모든 내장기능은 **`rexpert.*`** 객체로 호출한다.
- **필드 참조**: `rexpert.field("ns.NAME")` — `ns` = `data`(DB컬럼) · `system`(시스템필드) · `parameter`(매개변수) · `formula`(다른 공식) · `runningtotal`(누적합산) · `parent`(상위리포트) · `dataset`. (작은·큰따옴표 모두 가능)
- **요약함수**(`sum` `avg` `count` `min` `max` `var` `varp` `stddev` `stddevp`) — **5인자** 형태:
  `rexpert.sum(범위, "data.COL", 옵션, "그룹기준|''", "조건식|''")`
  - 1·3번째 정수 = 집계범위 / 리셋레벨, 4번째 = 그룹 기준 필드(없으면 `""`),
  - **5번째 = 조건식** → 있으면 **조건부 집계**: 예 `rexpert.sum(0,"data.AMT",0,"","data.ITEM_CD01=1")`, `and(data.A=..,data.B=..)`.
- **이웃/위치 참조**: `rexpert.prev("data.COL")` / `next(...)`(이전·다음 레코드) · `rexpert.fieldat("data.COL", n)`(N번째 레코드) · `rexpert.fieldbyint("data.COL")`(정수화).
- **출력형식**: `rexpert.format(값, 패턴)` — 숫자 `"#,##0"` `"0.0"`, 날짜 `"yyyy.mm.dd"` `"yyyy-mm-dd HH:MM"`, **자리수 분할** `"=[1-4].[5-6].[7-8]"`(문자열을 자리범위로 잘라 조립 → `2026.06.08`), `"=[1-4]년 [5-6]월"`.
- 기타: `getsubreportdata`(서브리포트 데이터).

#### 실전 패턴 2가지
**1) 날짜 분할** — 오늘 날짜를 `2026 . 06 . 08` 꼴로:
```javascript
var dt = rexpert.field("system.PrintDateTime");
return dt.substring(0,4) + ' . ' + dt.substring(5,7) + ' . ' + dt.substring(8,10);
// 더 간단히: return rexpert.format(rexpert.field("system.PrintDateTime"), "=[1-4] . [5-6] . [7-8]");
```
**2) 동적 문장** — 데이터를 본문 문장에 합성(계약/공문 본문 등). **확장가능 셀**에 바인딩:
```javascript
var txt = "";
txt += "제1조 ... (" + rexpert.field("data.SOSOG") + ")에 " + rexpert.field("data.JNAME") + "로 임용한다.\n";
txt += "제2조 ... " + rexpert.field("data.DATE1") + " 부터 " + rexpert.field("data.DATE2") + "까지.\n";
return txt;
```

> ⚠️ 공식식 안에서 필드는 **반드시 `rexpert.field("ns.X")`** — `:col` `#{col}` `${col}` `?` 같은 바인드 표기는 금지(그건 쿼리 파라미터 전용).

---

## 4. 컨트롤

글상자(라벨), **표**(셀=행×열), 선, 이미지, **차트**, **크로스탭**, 바코드, 서브리포트.
- 위치/크기 = **왼쪽 / 위쪽 / 너비 / 높이**.
- 셀/라벨 값 = 데이터·공식·시스템·매개변수 필드 **바인딩** 또는 정적 **텍스트**.

---

## 5. 표

- 생성: [삽입]–[표]→본문. 데이터 매핑: 리본 선택 / Drag&Drop / 셀범위 순차입력.
- **요약함수**(합계/평균/개수/최소/최대 …)로 그룹 바닥글 소계.
- 스타일: 배경색, 폰트, **확장가능**(텍스트 넘치면 아래로), **글꼴 크기 조정**(작게만/크게만/자동), **셀 자동 합치기**(행 방향 중복값 병합).
  - MCP: `crf_set_cell_style` 의 `merge` 파라미터. 파일에서 **탐지**(디자이너 없이)하려면 바이트 시그니처 `AC BC 08 00 01`(ON)을 스캔 — 상세: [docs/crf-binary-notes.md](docs/crf-binary-notes.md).

---

## 6. 그룹

- 그룹 머리글 = 타이틀(연결된 표). 그룹 바닥글 = 소계(요약함수).
- 본문 우클릭 → [그룹섹션 추가] → 그룹핑 필드 선택.

---

## 7. 출력양식

`일반` / `숫자`(소수·음수·천단위) / `백분율` / `통화`(기호·천단위·음수) / `날짜` / `시간` / `기타`(주민번호 등 배열) / `사용자 정의`

---

## 8. 조건 스타일

조건(공식필드 등) 충족 시 모양(배경색·폰트 등) 변경. 섹션/표/셀별 적용. 예: 홀수행 배경색.

---

## 9. 그 외

- **크로스탭**: 행·열을 필드로 가변 생성 + 요약값. **반복 섹션엔 불가**(보고서/데이터 머리·바닥글만). 행반복/고정너비/합계숨기기/그룹추가.
- **차트**: [삽입]–[차트], 시리즈/값/라벨 필드 매핑, 팔레트.
- **서브리포트**: 리포트 서브섹션(페이지 일부처럼) / 리포트 컨트롤(고정 위치·크기). 다른 데이터셋.
- **다단**: 레코드를 페이지 안 여러 단으로 연속 출력.
- **이미지**: 파일(삽입=crf에 탑재 / 연결=경로·url), 필드데이터(BLOB/경로문자열), 공식필드로 동적 경로.
- **페이지 바꿈**: 섹션 출력 전 / 후 / 전후.

---

## 9-1. 체크박스

셀 우클릭 → **[셀 내용] 체크박스** (또는 체크박스 컨트롤). [체크박스] 대화상자의 **체크 조건**(필드 · 연산자 · 값)이 TRUE 면 체크, FALSE 면 빈 상자.
- 조건을 비워 두면 **값이 무엇이든 항상 빈 상자**이고 셀에 바인딩한 텍스트/필드는 그려지지 않는다 → ■/□ 글자를 넣는 방식과 섞지 말 것.
- 체크 모양: 색칠(사각형 채움)·V·원·둥근사각형. 상자 모양/색/크기 별도.
- 도구: `crf_set_cell_checkbox(field, true_value, false_value, check_type=Rectangle|V|Ellipse|RoundRectangle)`. `crf_describe_layout` 은 `☐체크박스(모양)[조건]` 으로 표시하고 조건이 없으면 ⚠ 경고.

## 9-1-1. 참조 양식은 요소 전부를 좌표까지 판독한다

HWPX·PDF·DOCX·HTML·이미지 등 어떤 형식이든 참조 양식을 받으면 **모든 요소**(제목·부제·문단·표와 셀·선·상자·도형·체크칸·서명란·직인 자리·머리글/바닥글·페이지번호·로고·여백)를 **x,y,너비,높이(mm)·정렬·글꼴/크기/굵게/색·테두리/배경·데이터 자리 여부**까지 표로 인벤토리한 뒤 배치한다. HWPX 는 `samples/document/parse_hwpx.py`, PDF 는 PyMuPDF 블록 bbox + 페이지 이미지, DOCX 는 `word/document.xml`(EMU÷36000=mm), HTML 은 스크린샷 + 요소 박스, 이미지는 비율 + 용지 크기 확인. 인벤토리의 요소가 결과물에서 빠지거나 위치가 다르면 미완성이며, 마지막에 렌더 결과를 원본과 요소별로 대조한다(9-6 의 정렬 체크리스트).

## 9-2. 디자인 결정은 사용자에게 묻는다

리포트를 새로 만들거나 크게 고칠 때 "어떻게 만들지"의 갈림길은 도구가 아니라 **사용자의 취향·업무 판단**이다. 작업 전에 결정 목록(3~6개)을 한 번에 제시하고 항목마다 권장안과 한 줄 이유를 붙여 답을 받은 뒤 진행하며, 중간에 새 갈림길이 나오면 그 시점에 다시 묻는다(사용자가 이미 지정했거나 "알아서" 한 항목만 생략).

| 갈림길 | 물어볼 것 (권장안 예) |
|---|---|
| 요소 구성 | 연속 줄을 1열 표 하나 / 줄바꿈 글상자 / 글상자 여러 개 (권장 표 하나) |
| 체크박스 | 셀 체크박스 vs 독립 컨트롤, 색칠(Rectangle)/V/원, 상자 모양·크기, 항목별 칸 |
| 레이아웃(양식 없이) | 목록형 vs 문서형, 용지·방향·여백, 제목/출력일시/페이지번호/로고 위치, 열 순서·너비·정렬·출력양식, 그룹·소계·총계, 서명란·비고란 |
| 글꼴 | 리포트/모듈 관례(돋움체·바탕체·나눔고딕)·크기, 라벨·데이터 통일 |
| 쿼리 | 매개변수 이름·정렬·조건, 코드→명칭 |
| 저장 | output 파일명·위치, 덮어쓸지 |

## 9-3. 요소 구성 원칙 — 나눌 때 나누고, 붙일 때 붙인다

- **세로로 이어지는 문단·번호 목록·※주석**처럼 x·너비·글꼴·크기가 같은 줄들은 글상자를 줄마다 따로 만들지 않는다. **1열 표 하나**(`crf_add_table rows=[…]`, 줄마다 행 — 줄 높이를 따로 줄 수 있어 목록에 적합) 또는 **줄바꿈 글상자 하나**(`crf_add_label wrap=true`, 텍스트 안 `\n`) 로 만든다.
- **별개 요소로 나누는 경우**는 정렬·글꼴·굵기·글자색·필드 바인딩·가로 위치가 서로 다를 때뿐이다(예: 제목 14pt 굵게 + 본문 10pt, 라벨 "소속:" + 데이터 `DEPT_NM`).
- 이미 쪼개진 글상자는 `crf_merge_labels(names="글상자3,글상자4,글상자5", into=table|label)` 로 합치고, 반대로 덩어리를 줄/행별로 손봐야 하면 `crf_split_label(name)` 로 나눈다. 두 도구 모두 값·글꼴·정렬·줄간격을 유지하고 세로 자리를 그대로 두므로 다른 요소가 밀리지 않는다.
- `crf_validate` 가 같은 스타일로 세로 연속인 정적 글상자 묶음을 ⚠ 로 알려 준다(필드 바인딩이 섞이면 ℹ).

## 9-4. 글꼴 관례

- SDK 로 새로 만든 글상자/셀의 기본 글꼴은 **`System` 9pt** 라 그대로 두면 다른 리포트와 어긋난다. 생성 도구(`crf_add_label`/`crf_add_table`/`crf_place_detail_fields`/`crf_add_group` 라벨)는 **리포트의 지배 글꼴을 라벨(글자)·데이터(숫자) 따로 집계해 상속**하고, 리포트에 글꼴이 없으면 같은 폴더 이웃 리포트 관례, 그래도 없으면 돋움체를 쓴다. `font`/`fontsize` 를 직접 주면 그 값이 우선.
- 이 저장소 관례(표본 40개): **목록형 = 돋움체**, **문서형(통지서·서약서) = 바탕체**, 일부 모듈(apur/ahrmrc/sles 등) = 나눔고딕. 라벨과 데이터 글꼴은 **같은 리포트 안에서 통일**한다(데이터만 다른 글꼴이면 인쇄물이 어수선하다).
- 일괄 정리: `crf_set_font(font=돋움체)` / 데이터만 `data_font=` / `only_system=true`(System 만) / `size=`. `crf_validate` 가 System 글꼴 위치, 라벨≠데이터 글꼴, 여러 글꼴 섞임(소수 글꼴 위치)을 알려 준다.

## 9-5. 화면(.vue/.xfdl) → 인쇄물로 옮길 때

- 화면의 **빨간 강조·버튼 문구('제출하기' 등)·안내 배너**는 인쇄물에 넣지 않는다(화면 조작용 요소).
- 체크 모양(**색칠 Rectangle / V**)은 문서마다 사용자 지시를 따르고, 지시가 없으면 묻는다.
- **엑셀 저장 격자**: CLIP 엑셀 export 는 페이지 위 모든 컨트롤의 x 경계 합집합으로 열을 만든다(`coordinateErrorLimit=10`). 머리글/바닥글 표의 열 경계는 **본문 표 경계의 부분집합**이어야 본문 셀이 쪼개지지 않는다 → `crf_validate` ℹ 엑셀 격자.
- 그룹 머리글이 매 페이지 반복되면 `crf_set_subsection(section=그룹머리글, repeat=None)`; 반복시키려면 `OnPage`.
- 쿼리의 `{parameter.X}` 는 항상 `'문자열'` 로 치환된다 → 날짜 포맷은 `TO_CHAR(TO_DATE('{parameter.DT}','YYYYMMDD'), …)` 처럼 **TO_DATE 먼저**(곧장 TO_CHAR 하면 Tibero JDBC-5075 로 쿼리 전체가 0건). 쿼리 수정은 디자이너에서 데이터셋을 통째로 다시 만들지 말고(셀 바인딩이 끊김) `crf_set_query` 로 문자열만 교체.

## 9-6. 문서형(양식) 리포트

통지서·서약서·신고서처럼 레코드 1건짜리 양식은 **본문 밴드 하나**에 글상자/표를 좌표(0.1mm)로 배치한다. 양식(HWPX/PDF)의 세로 위치·정렬을 요소별로 옮기고, 줄바꿈 문단은 `linespace`(pt) 로 행간을 맞추며, 셀 병합·테두리·색칠 체크박스로 표를 재현한다. 절차와 함정은 [docs/document-report-recipe.md](docs/document-report-recipe.md).

## 10. 본 도구(MCP)와 개념 매핑

| 하고 싶은 것 | 도구 |
|---|---|
| 리포트 개요·필드 인벤토리·용지·섹션/서브섹션 보기 | `crf_summary` |
| **쿼리 본문** 읽기 (JS 동적쿼리는 평문 복원본 포함) | `crf_get_query` |
| **공식 스크립트**·누적합산·그룹이름 읽기 | `crf_get_formula` |
| 폴더에서 테이블/매개변수/문구로 리포트 **검색** | `crf_search` |
| 밴드별 컨트롤·표 셀 바인딩 보기 (설명/제안의 근거; `detail=true` 스타일까지) | `crf_describe_layout` |
| 리포트 **검증(lint)** — 끊어진 바인딩/공식/매개변수 | `crf_validate` |
| 셀 값·공식·정렬·폰트 / 글상자 편집 / 밴드 행 높이·숨김 | `crf_set_cell` / `crf_set_label` / `crf_set_subsection` |
| 표 생성 / 그룹 위치·라벨·소계 / 삭제(컨트롤·그룹·밴드) | `crf_add_table` / `crf_add_group`·`crf_set_group` / `crf_remove_control`·`crf_remove_group`·`crf_remove_section` |
| 폴더 리포트 찾기 | `crf_list_reports` |
| SQL/MyBatis로 초안 생성 | `crf_generate` |
| 쿼리 교체(매개변수 선언·필드 동기화) / 그룹 추가 / 본문 필드 배치 | `crf_set_query` / `crf_add_group` / `crf_place_detail_fields` |
| 쿼리 컬럼↔필드 맞추기 (`SELECT *` 는 DB 실행) | `crf_sync_fields` (`mode=sql|db`) |
| 데이터셋 추가·삭제 / 매개변수 생성·수정·삭제 | `crf_add_dataset`·`crf_remove_dataset` / `crf_set_param`·`crf_remove_param` |
| 필드 이름변경·삭제 (참조 검사) / 참조 위치 보기 | `crf_rename_field`·`crf_remove_field` / `crf_field_refs` |
| 셀을 **체크박스**로(조건 기반 체크, 색칠/V/원) / 셀 **병합·해제** | `crf_set_cell_checkbox` / `crf_merge_cells` |
| 글상자 추가(글꼴·정렬·글자색·테두리) / 글자색·밑줄·줄간격·여백 | `crf_add_label` / `crf_set_cell`·`crf_set_label` (`color|underline|linespace|padding|border`) |
| 연속 문단/목록을 **표 하나**로 / 글상자 여러 개 **합치기** / **나누기** | `crf_add_table rows=[…]` / `crf_merge_labels` / `crf_split_label` |
| 글꼴 **일괄**(라벨/데이터 따로, System 만) | `crf_set_font` |
| 그룹 머리글 매 페이지 반복 | `crf_set_subsection repeat=OnPage|None` |

> **편집 제안**: Claude가 `crf_summary` + `crf_describe_layout` 로 구조를 읽고, 위 개념에 비추어 개선점을 자연어로 제시합니다.

> ⚠️ **열린 파일 주의**: `.crf`가 CLIP report 앱에서 **열려 있는 동안** 쓰기 도구로 수정하면 파일 잠금/상태 충돌이 난다(앱에서 저장 시 편집이 덮어써지거나, 편집이 앱에 반영 안 됨). 이미 만든 `_edited.crf`에 추가 수정이 필요한데 열려 있을 수 있으면 → **저장 → 잠깐 닫기 → (MCP)수정 → 다시 열기** 순서로. 수정 전 유저에게 "저장 후 닫아 달라" 요청하고, 완료 후 "다시 열어도 된다"고 안내한다.
