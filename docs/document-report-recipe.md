# 문서형(양식) 리포트 제작 레시피 — HWPX/PDF 양식 → .crf

통지서·서약서·신고서처럼 **레코드 1건을 양식대로 찍는 리포트**를 만들 때의 절차와 함정 모음.
2026-09 인하공전 ERP 리포트 3종(위촉통지서·업무수행서약서·회피 자진 신고서)을 만들며 실측·검증한 내용이다.
샘플 코드는 [`samples/document/`](../samples/document/) 에 있다.

## 0. 한눈에

| 단계 | 도구/방법 |
|---|---|
| 양식 읽기 | HWPX: `samples/document/parse_hwpx.py` (문단·표·도형·글자색·**정렬**·세로위치) / PDF: `pdf_text` 또는 PyMuPDF 로 페이지 이미지 추출 |
| 데이터 매핑 | 양식의 **파란 글자 = 데이터 자리**(관례). 화면(.xfdl/.vue)→백엔드 매퍼→테이블→`db_columns`/`db_sample` 로 컬럼 확정. 코드값은 `COM.CSYS011` |
| 리포트 뼈대 | 같은 저장소의 단순 .crf 를 템플릿으로 열어 필드/쿼리/매개변수/섹션을 갈아끼움(DB 연결정보 유지) — `crf_generate` 또는 SDK |
| 배치 | 본문(Detail) 밴드 하나에 글상자/표를 **0.1mm 좌표**로 배치 — `crf_add_label`(style/border) · `crf_add_table` · `crf_merge_cells` · `crf_set_cell`(color/underline/linespace/padding) · `crf_set_cell_checkbox` |
| 검증 | `crf_validate` → 실서버 렌더(아래 §5) → **정렬 체크리스트**(§6) 대조 |

## 1. 단위·색·글꼴

- **좌표/크기 단위 = 0.1mm**. A4 세로 = 2100×2970, 여백 `crf_set_paper(marginL/T/R/B)`. HWP 30mm 좌우여백이면 L300/R300, 본문 너비 1500.
- HWPUNIT(1/7200 inch) → mm 는 `×25.4/7200`. HWPX `hp:lineseg vertpos` 가 문단의 실제 세로 위치(mm)라 그대로 y 좌표로 쓰면 원본 간격이 재현된다.
- **색은 `#RRGGBB`** 로 넘기면 도구가 SDK 의 BGR int 로 바꾼다. SDK 직접 호출 시 `(b<<16)|(g<<8)|r`.
- 글꼴: 서버에 있는 글꼴만 PDF 에 박힌다. 이 ERP 는 `바탕체`(문서)·`돋움체`(표) 관례. 함초롬바탕 등 HWP 전용 글꼴은 바탕체로 대체.
- **줄바꿈 텍스트 줄간격 `linespace` 단위는 pt**. 10pt 글꼴에 HWP 160% 행간을 맞추려면 **5.5** (15 를 주면 두 배로 벌어져 잘림). 기존 계약서 리포트도 12pt 에 6.0 을 쓴다.
- 한 줄 안에서 색을 섞어야 하면(예 `(필수)` 빨강 + 검정 문장) 셀을 둘로 쪼개 오른쪽/왼쪽 정렬로 붙인다. 텍스트 태그(`TextInfoEx.setUseTag`)는 미검증.

## 2. 표(ControlTable)

- `crf_add_table` 로 만든 표/SDK 로 새로 만든 `TableCellNormal` 은 **대각선(FDiagona/BDiagona) 기본값이 Solid 라 셀마다 X 가 그려진다** → v0.7.0 부터 도구가 None 으로 초기화. SDK 직접 사용 시 `cell.getLineInfoFDiagona().setLineStyle(None)` 필수.
- 테두리는 셀마다 `setVisibleLeftLine…` + `getLineInfoLeft().setLineStyle(Solid)/setLineWidth(W050)`. 상자 하나(예 동의 표)를 3칸으로 나누되 안쪽 세로선을 없애려면 가운데 칸은 위/아래만 켠다.
- 머리글 회색 배경: `bgcolor=#D9D9D9`(양식 `winBrush faceColor` 참고).
- 병합: `crf_merge_cells(table,row,col,rowspan,colspan)` — 덮이는 자리는 `TableCellDumy`(describe 의 ‹병합›). 1×1 로 다시 부르면 해제.
- 셀 안 여백 `padding="l,t,r,b"`(0.1mm). 값 칸은 왼쪽 여백 8~20 정도가 자연스럽다.

## 3. 체크박스 — 글자로 흉내내지 말 것

CLIP 에는 **셀 내용=체크박스** 기능이 있다(디자이너: 셀 우클릭 → [셀 내용] 체크박스 → [체크박스] 대화상자). 도구: `crf_set_cell_checkbox`.

- 체크 여부는 **참/거짓 조건**으로만 결정된다: `field` `operator` `true_value`(기본 Equal '1'), `false_value`(기본 '0'). **조건이 비어 있으면 값이 뭐든 항상 빈 상자**이고, 셀에 바인딩된 텍스트/필드(■ 같은 글자)는 그려지지도 않는다.
- 체크 모양 `check_type`: **Rectangle(색칠, 기본)** · V · Ellipse · RoundRectangle. 상자 모양 `shape`, 색 `color`, 크기 `size`(0=자동).
- 실측: `INDIN_PROVD_AGREE_YN Equal '1'` → 채워진 사각형, 반대 조건 → 빈 상자. 독립 컨트롤은 `ControlCheck`(글상자처럼 배치).
- 한 셀에 여러 항목(□ 수시1차 □ 수시2차 …)을 나열해야 하면 항목마다 셀/체크 컨트롤을 두는 것이 정석. 부득이 글자(■/□)로 할 때는 공식필드로 조립.

## 4. 공식(JS) 패턴

```js
// 조건부 문장 조립
var y = rexpert.field("data.VOW_YY"); if (y == null || ("" + y) == "") return "";
return y + "년 " + parseInt("" + rexpert.field("data.VOW_MM"), 10) + "월 " + parseInt("" + rexpert.field("data.VOW_DD"), 10) + "일";
```
- 문자열 비교는 `("" + rexpert.field(...)) == "1"` 처럼 문자열화. 함수 선언 대신 삼항연산자 위주가 안전.
- 날짜 매개변수는 쿼리에서 `TO_DATE` 먼저(문자열에 `TO_CHAR(str, fmt)` 하면 JDBC-5075 로 쿼리 전체 실패).

## 5. 실서버 렌더 검증 (이 ERP 기준)

- 리포트 서버: 로컬 WAS `/report/callReport.jsp` (`-Dreport.meta.path` 가 report repo 의 `meta/` 를 그대로 서빙). `.crf` 를 `meta/<모듈경로>/` 에 복사하면 즉시 반영.
- 서버가 파일을 잡고 있어 복사가 `Device or resource busy` 면 PowerShell `Copy-Item -Force` 로 몇 초 후 재시도.
- `samples/document/render_report.cjs` : Playwright 로 CDP(9222) Chrome 에 붙어 `callReport.jsp` 에 POST(`reportParams`=base64 JSON, `paramType=query`, `filePath`) → 스크린샷. 화면이 보내는 파라미터 키는 camel(`syy`, `smtCd`)이어도 `{parameter.SYY}`/`{parameter.SMTCD}` 에 매핑된다.
- 넥사크로 화면에서는 `this.utils.callReport({filePath:"sch/…/xxx_prn01", params: this.dsReport})`. 그리드 버튼 셀은 `displaytype="expr:… ? 'buttoncontrol' : 'normal'"` + `oncellclick` 에서 `getCellPropertyValue(e.row,e.cell,"displaytype")` 로 판정(2행 헤더 그리드는 `getCellText(-1, e.cell)` 이 열과 안 맞으니 헤더 텍스트로 분기하지 말 것).

## 6. 정렬 체크리스트 (사용자가 가장 민감한 부분)

렌더 후 원본과 **요소별로** 대조한다.
1. 제목/부제 — 가운데, 굵게/밑줄
2. 본문 문단 — 양쪽(JUSTIFY→`Both`) / 왼쪽
3. 표 — 열 너비 비율, 머리글 배경, **표 자체의 위치**: HWPX 에서 `treatAsChar=1` 인 표는 **앵커 문단의 정렬**(RIGHT 면 본문 오른쪽 끝)을 따른다. 문단 정렬을 덤프에 꼭 포함할 것.
4. 셀 내부 — 라벨 가운데(DISTRIBUTE 는 `Equal`), 값 왼쪽
5. 날짜/제출문 — 가운데, 서명행 — 오른쪽, 수신처(총장 귀하) — 왼쪽
6. 도형(직인 상자 등) — HWPX `hp:rect pos(horzRelTo=PAPER, horzOffset)` 는 종이 기준 절대좌표 → 여백을 빼서 본문 좌표로

## 7. SDK 로 직접 만들 때 (MCP 도구로 부족할 때)

`samples/document/SampleDocumentReport.java` 가 전체 예제다(템플릿 .crf 읽기 → 필드/쿼리/매개변수 교체 → 용지 → 본문 밴드 하나 → 라벨/표/체크박스 배치 → 저장).
- 클래스패스: `"C:\Program Files (x86)\Clipsoft\CLIP report v5.0\bin\jar\*;clip-report-mcp.jar;."` — 엔진 클래스는 `Viewer.jar`.
- 핵심 API: `ControlLabel`(TextInfo: `setFontName/Size/Bold/Underline/ForeColor/HorizontalAlignment/WordWrap/LineSpace`), `ControlTable`+`TableRow/TableColumn/TableCellNormal/TableCellDumy`, 체크박스 `setCellContent(CellContentType.Checkbox)`+`getCheckValueTrueCondition()`, 사각형 = 라벨에 `setShapeType(Rectangle)+setLineStyle(Solid)`.
