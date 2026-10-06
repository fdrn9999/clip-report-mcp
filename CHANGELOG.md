# Changelog

이 프로젝트의 주요 변경을 기록합니다. 버전은 [유의적 버전](https://semver.org/lang/ko/)을 따르며,
릴리스마다 git 태그 `vX.Y.Z` 를 답니다. 실행 중인 버전은 `/mcp` 의 clip-report **serverInfo.version** 으로 확인할 수 있습니다.

## [0.12.0] - 2026-10-06
사내 "CLIP Report 제작 하네스"(벤더 JAR 로 CRF 를 읽고 쓰는 Java 묶음)와 비교해 나온 4가지: **저장 무결성**(쿼리 암호화·크로스탭 저장), **실제 엔진 출력**, **크로스탭 생성**, **누적합산**. 새 코드는 `src/CrfIO.java`·`src/CrfRender.java`·`src/CrfCrosstab.java`. 도구 53 → 56.
### Fixed
- **저장하면 쿼리가 평문이 되던 문제**: 엔진 기본 writer(`Rexpert4.write`)는 쿼리 본문(SQL 객체의 속성 900)을 평문 문자열로 쓴다(pre/post SQL 만 암호화). 디자이너가 저장한 리포트는 쿼리도 암호화돼 있으므로(저장소 2,549개 중 2,534개), 지금까지 이 MCP 로 저장한 파일은 쿼리가 전부 평문이 됐다 — 예: 데이터셋 22개 리포트가 암호화 22 → 평문 22. 이제 모든 저장이 `CrfIO.write` 를 거쳐 엔진의 암호화 함수로 쓴다(리더는 두 형식을 다 읽으므로 기존 파일 호환). 두 번 이어 저장해도 암호화 22 유지, `crf_diff` 차이 없음.
  - **이미 평문으로 저장된 리포트**는 아무 수정 도구로 한 번 다시 저장하거나 `java -cp "<CLIP jar>\*;clip-report-mcp.jar" CrfIO in.crf out.crf` 로 되돌린다.
- **크로스탭이 든 리포트를 저장하면 깨지던 문제**: `CrosstabCellNormalWriter` 가 헤더에 속성 23개를 선언하고 24개를 써서 되읽기에서 `EOFException`. 저장소의 크로스탭 리포트 48개 전부가 엔진 기본 writer 로는 저장 후 열리지 않았다 → 그 객체·그 개수일 때만 24 로 보정.
- **웹 링크 팝업 크기가 저장할 때 바뀌던 문제**: `WebLinkInfoWriter` 가 팝업 너비를 팝업 높이의 속성 ID 로 써서 되읽으면 높이가 너비 값으로 덮였다(저장소 8개) → 한 객체 안의 두 번째 값을 너비 ID 로 쓴다.
- 저장은 **임시 파일에 쓰고 되읽어 본 뒤** 제자리로 옮긴다 — 쓰다 실패하거나 되읽기에 실패하면 기존 파일이 그대로 남는다(`in_place=true` 에서 원본이 깨지는 경우 방지).
- 검증: 저장소 2,549개 전부 읽기 → 저장 → 읽기 → 저장 했을 때 내용이 같다(보정 전: 크로스탭 48개 되읽기 실패, 8개 값 변경).
- **DB 도구를 한 번 쓰면 다른 도구가 엉뚱하게 깨지던 문제**(간헐적): `DriverManager` 는 처음 쓸 때 classpath 의 모든 JDBC 드라이버를 올리는데, CLIP 디자이너 jar 폴더의 Sybase jConnect(`jconn4.jar`)가 생성자에서 `DriverManager` 를 다시 불러 무한 재귀 → `StackOverflowError`(JDK 가 삼킴). 그 순간 초기화 중이던 JDK 클래스가 그 프로세스에서 영구히 깨졌다 — 실제로 본 증상은 `doc_text` 의 엑셀 날짜에서 `NoClassDefFoundError: Could not initialize class java.time.LocalDateTime`. JVM 예외 로그로 재귀를 확인하고, `db_*`·`crf_sync_fields mode=db`·`fields_from_db` 가 `DriverManager.getConnection` 을 거치지 않고 Tibero/Oracle 드라이버 인스턴스에 직접 연결하도록 바꿨다(그 밖의 DB URL 만 종전 경로; 드라이버가 스스로 하는 `registerDriver` 는 드라이버 일괄 로딩을 일으키지 않음). 수정 후 예외 로그의 StackOverflowError 0건, DB 를 켠 smoke 2회 연속 360/360.
- `crf_validate` 가 크로스탭 라벨 셀을 "바인딩 비어 있음" 으로 잘못 보고하던 것(필드 없는 Field 타입 = 그 축의 그룹 이름, 정상).
### Added
- **`crf_render` — 실제 CLIP 서버 엔진으로 PDF 출력**(WAS 없이): 리포트 서버 웹앱 폴더의 엔진 jar·설정·라이선스를 빌려 **별도 java 프로세스**에서 `ClipReportExport.createExportForPDF` 를 부르고(서블릿 요청은 동적 프록시), 한 쪽을 그림(MCP image) + 글자로 돌려준다. `crf_preview`(설계 화면)와 달리 데이터 행 반복·셀 확장·페이지 나눔·그룹 소계·누적합산·크로스탭·서브리포트(공통 로고)가 반영된 진짜 출력.
  - `mode=sample`(기본): 리포트 **사본**의 모든 데이터셋을 CSV 메모 데이터로 바꿔 가상 행을 넣는다 — DB 접속 없음, 쿼리 실행 안 함. `data={"데이터셋":[{필드:값},…]}`(필드 순서 배열·TSV 문자열도), `data_file`, 또는 필드 이름·타입으로 `rows` 행 자동 생성(그룹 필드는 같은 값이 이어지게, 칸마다 다른 값이라 잘못 연결된 칸이 보임). 데이터셋 여러 개(22개 리포트 확인)는 데이터셋마다 연결을 하나씩 만들어 넘긴다. 링크 서브리포트는 사본 옆 같은 상대 위치에 복사.
  - `mode=db`: 원본을 서버가 하는 그대로 출력 — 서버 설정(`DataConnection.properties`)의 DB 에서 쿼리 실행, `params={"이름":"값"}`. 데이터셋별 읽은 행 수를 응답에 표시. 확인: 같은 조건의 `db_query COUNT(*)` 1,050 = 엔진이 읽은 1,050행(43쪽).
  - 엔진 설정 폴더는 임시 폴더에 복사해 쓴다 — 엔진이 시작할 때 temp 폴더를 비우는데, 원본 폴더를 그대로 주면 웹앱 소스 트리의 자리표시 파일이 지워졌다.
  - 설정: `.env`/환경변수 `CLIP_RENDER_WEBAPP`(또는 `CLIP_RENDER_LIB`+`CLIP_RENDER_PROPS`), `CLIP_RENDER_CP`, `CLIP_RENDER_DBNAME`, `CLIP_RENDER_TIMEOUT`. 없으면 설정 안내와 함께 ERROR.
- **`crf_add_crosstab` — 크로스탭(교차표) 생성**: `row` × `column` × `value`(`Sum|Count|Avg|Min|Max`), 행/열 합계, 표시 이름 필드(`row_name`/`column_name`), 정렬, 머리 칸 글자, 출력 양식, 열·행 크기. 데이터 머리글 밴드에 둔다(없으면 생성, 밴드 높이 자동). 새 셀의 대각선은 None 으로(표 셀과 같은 함정). 실제 엔진 출력으로 5행 표본의 교차 집계(2·0·1=3 / 1·1·0=2 / 합계 3·1·1=5)를 대조.
- **`crf_add_running_total` — 누적합산 필드**: 레코드마다 누적, `reset_group` 이면 그룹이 바뀔 때 초기화(그룹 바닥글에 두면 소계), 없으면 전체 누계. `crf_set_cell field=` 로 바인딩. 실제 엔진 출력에서 기존 `rexpert.sum()` 공식 소계와 같은 값(3=3, 2=2), 본문에 두면 줄 누계 1…5.
- **`crf_describe_layout` 이 크로스탭 내부를 보여 줌**: 행/열 그룹(그룹 필드·표시 필드·정렬·합계 표시), 네 행렬(머리/행 라벨/열 라벨/값)의 열 너비·행 높이와 셀별 종류·바인딩·집계 함수·출력 양식(`detail=true` 면 글꼴·정렬).
- **서버 instructions `[★고친 뒤에는 실제 출력으로 확인]`·`[★크로스탭·누적합산]`**, `/clipreport` 명령의 ⑤단계, GUIDE 의도→도구 표, 문서형 레시피 §5.
- `CrfIO` CLI(`java -cp … CrfIO in.crf out.crf`), 환경변수 `CLIP_SQL_PLAIN=1`(평문 저장, 디버깅용).
- **Codex 리뷰(13건) 재검토 후 반영 11건**:
  - `crf_render` 가 **원본 `.crf` 를 PDF 로 덮어쓸 수 있던 문제**(HIGH): 인자 이름이 `output` 이라 공통 `in_place` 처리가 붙어 `in_place=true` 나 `output=<리포트>` 면 리포트 자리에 PDF 가 써졌다 → 인자를 `pdf` 로 바꾸고 `.pdf` 가 아니거나 리포트와 같은 경로면 거부.
  - 저장 교체를 **원자적 이동**으로(안 되는 파일시스템만 일반 교체), 교체에 실패하면 검증까지 끝난 내용을 `<이름>.unsaved` 로 남기고 경로를 알린다.
  - `crf_render` 작업 폴더: 리포트 사본·설정 사본(DB 접속정보·라이선스)·넘긴 데이터를 **실패·시간 초과 때도** 지움, 시간 초과 시 자식·손자 프로세스 종료를 기다려 확인, 엔진 로그는 통째로 올리지 않고 줄 단위로 읽음, `data_file` 20MB 상한. README 의 보존 기간 설명을 실제 동작(다음 실행 때 하루 지난 것 삭제)에 맞춤.
  - `mode=sample`: 연결을 먼저 다 지우지 않고 바꾸지 못한 데이터셋의 연결은 남김(+⚠), 필드 수보다 긴 배열 행은 잘라내고 ⚠, 필드 없는 데이터셋은 0행. 링크 서브리포트는 `.crf` 만 복사.
  - `crf_add_crosstab`/`crf_add_running_total`: 데이터·공식 필드만 받고(매개변수 등 거부), 크로스탭의 세 필드가 서로 다른 데이터셋이면 거부. `total=false` 면 컨트롤·밴드 크기도 합계 칸만큼 줄임(실제 출력에서 합계가 빠지는 것 확인).
  - `crf_validate` 의 크로스탭 예외를 행/열 **라벨 셀**로만 좁힘(머리·합계 칸의 빈 바인딩은 계속 보고).
  - smoke: "원본 그대로" 검사가 자기 자신과 비교하던 것을 **픽스처 원본 해시 비교**로.
  - 미반영 2건: ① "드라이버 클래스 로딩만으로도 DriverManager 재귀가 난다" — JDK 17 `registerDriver` 바이트코드에 드라이버 일괄 로딩이 없고, 수정 후 MCP 서버와 `crf_render mode=db` 자식 프로세스 모두 JVM 예외 로그의 StackOverflowError 0건이라 기각(CHANGELOG 문구만 정확하게 고침). ② `mode=db` 가 모든 데이터셋을 연결 이름 하나로 보내는 것 — 이 ERP 의 리포트 서버 JSP 와 같은 동작이라 유지하고 한계로 명시.
- `tools/smoke.py` 366 케이스(+36): 크로스탭 리포트 저장·되읽기 4, `crf_render` 11, 크로스탭/누적합산 14(실제 엔진 수치 대조 포함), **이번 실행이 쓴 모든 `.crf` 에 평문 SQL 이 없는지** 바이트 검사 1.
### 한계(미반영)
- `crf_render`: 서버 엔진 5.0.343·Windows·JDK 17 에서만 확인. `mode=sample` 은 쿼리를 실행하지 않고, 링크 서브리포트 안의 쿼리 데이터셋은 비어 나온다. 화면이 넘기는 세션 값은 `params` 로 직접 줘야 한다.
- 크로스탭 생성은 행 그룹 1 × 열 그룹 1 × 값 1 까지. 만든 크로스탭·누적합산을 **디자이너에서 열어 다시 저장**하는 호환성은 확인하지 않았다(엔진 되읽기·실제 출력만 확인).
- 하네스에 있고 여기 없는 것: 수정 시 현재 값이 예상과 같은지 확인하는 `expected` 잠금, 제작 Java 의 네트워크 차단(SecurityManager, JDK 17 한정), 보고서별 작업 기록 폴더 구조.

## [0.11.0] - 2026-10-02
양식을 어떤 형식으로 받아도 읽고 **그림으로 대조 검증**한 뒤 작업하는 흐름, JS 동적쿼리를 **실제로 실행**해 보는 기능, v0.10.0 에서 한계로 남긴 MyBatis 변환 3건. 새 코드는 `src/CrfDocs.java`·`src/CrfPreview.java`·`src/CrfJsEval.java`. 도구 50 → 53.
### Added
- **`doc_text` — 양식 문서 파서(PDF · HWP 5.x · HWPX · DOCX · XLSX/XLSM · XLS · PPTX · HTML · 이미지)**: 확장자가 아니라 파일 내용으로 형식을 판별하고, 글자와 함께 **배치를 mm 로** 돌려준다(리포트 좌표 0.1mm = mm×10) — 용지·여백·본문 너비, 문단(y·정렬·글꼴 크기·굵게/기울임/밑줄·글꼴·색), 표(셀 `[행,열]`·병합·너비×높이, 셀 안 문단), 도형/그림(크기·위치·글상자 안 글), 엑셀(시트 목록·병합 범위·열 너비·셀 값·날짜 서식·수식), 슬라이드 도형(x·y·크기·표), HTML(헤드리스 Edge/Chrome 으로 A4 폭에 배치한 요소별 x·y·크기·글꼴·정렬, 입력칸·체크박스). `detail=true`, `sheet=`, `pages=`, `max_chars=`. 예전에 형식마다 파이썬 스크립트(`parse_hwpx.py`)·PyMuPDF·`document.xml` 수작업으로 하던 판독을 대체한다.
  - **HWP(바이너리)** 는 한글 없이 직접 해석한다: CLIP 엔진 jar 폴더의 poi(POIFS)로 OLE2 를 열고 DocInfo(글꼴·글자 모양·문단 모양)와 BodyText 레코드(문단 머리/글자/글자 모양/줄 배치, 표·개체 컨트롤, 셀 목록, 용지 정의)를 읽는다. 같은 문서의 HWP 와 HWPX 판독 결과가 문단·표·좌표까지 일치함을 실파일로 확인. 암호·배포용 문서, HWP 3.x, 구버전 `.doc`/`.ppt` 는 무엇으로 다시 저장하면 되는지 알려 주는 ERROR.
  - HTML 은 헤드리스 브라우저가 표준출력을 돌려주지 않아(실행 파일이 0.1초 만에 끝나고 뒤에서 처리), 페이지 안에서 좌표를 잰 뒤 문서를 결과 글자로 바꿔 **인쇄된 PDF 의 텍스트로 회수**한다. 브라우저가 없으면 JDK HTML 파서로 글자·표 구조만.
- **`doc_image` — 문서를 그림으로(비전 검증용)**: 이미지는 그대로(축소), PDF 는 쪽 렌더, HWP/HWPX/DOCX/XLSX/XLS/PPTX/HTML 은 **PC 에 설치된 프로그램으로 PDF 변환**(한글 COM / MS Office COM — 매크로 강제 차단·읽기 전용 / LibreOffice / Edge·Chrome 헤드리스 A4 인쇄; 결과는 임시 폴더에 캐시) 후 `page` 쪽을 렌더해 MCP image 로 돌려준다. 변환기가 없으면 파일 안의 첫 쪽 미리보기 그림(HWP `PrvImage`, HWPX `Preview/PrvImage.png`, OOXML 썸네일). `source=auto|convert|preview`, `max_px`. 이 PC 에서 MS Word/Excel/PowerPoint·Edge·HWP 미리보기 경로를 실파일로 확인했다(한글·LibreOffice 경로는 미검증 — README 한계).
- **`crf_preview` — 리포트 배치를 그림으로**: 용지 한 장에 밴드를 한 번씩 쌓고 글상자·표 셀(병합·셀별 테두리·배경색)·선·그림/서브리포트 자리를 0.1mm 좌표대로, 글꼴·크기·굵게·가로/세로 정렬·줄바꿈까지 그린다. 정적 텍스트는 검정, 필드 바인딩은 파란 `«필드명»`, 체크박스 셀은 상자. 원본 양식 그림과 나란히 놓고 요소 누락·위치·정렬·병합을 대조하는 용도(설계 화면 — 데이터 반복·확장·페이지 나눔 없음).
- **서버 instructions `[★양식은 파싱 + 그림으로 교차 검증한 뒤 작업]`**: 양식을 받으면 ① `doc_text` ② `doc_image` 로 그림을 받아 직접 보고 ③ 둘을 대조(파싱에 빠진 도장·선·체크칸, 병합·정렬·테두리, 쪽 수)한 뒤 작업을 시작하고, 작업 후 `crf_preview` 를 원본 그림과 대조한다. 그림을 못 얻으면 사용자에게 알린다. `/clipreport` 명령(1-1 절)·GUIDE 9-1-1·문서형 레시피에도 같은 순서를 넣었다.
- **JS 동적쿼리 실제 실행(`src/CrfJsEval.java`)**: CLIP 엔진 jar 폴더의 `batik.jar` 에 들어 있는 Rhino 로 쿼리 스크립트를 돌린다(별도 의존성 없음; Java 접근 차단 + 3초 제한 샌드박스). 저장소 실측 — JS 쿼리 1,495개 중 1,494개가 그대로 실행됐고 나머지 1개(saplap0210_prn01)는 `SQL`/`sql` 을 섞어 쓴 실제 버그였다.
  - **`crf_get_query params={…}` / `mode=eval`** → 그 매개변수 값으로 실행한 **실행 SQL**(if 분기 반영). "0건/조건이 안 먹는다" 확인용.
  - **`crf_validate`** 가 JS 쿼리의 **문법 오류·실행 오류**(매개변수 전부 빈 값 기준)를 ✖ 로. `crf_set_query`/`crf_append_query_condition`/`crf_generate` 도 저장한 JS 에 같은 검사를 해 응답에 알린다.
  - **`crf_sync_fields mode=db` / `crf_generate fields_from_db`** 는 JS 쿼리를 실제 실행한 SQL 로 메타데이터를 읽고(전에는 모든 if 조각을 이어 붙인 평문 복원본이라 if/else·foreach 가 있으면 깨짐), 실패하면 평문 복원본으로 재시도한다.
- **MyBatis 변환(v0.10.0 미반영 3건)**:
  - `<foreach>` 안의 `<if test>`/`<when test>` 가 **루프 변수·index 변수**를 쓰면 매개변수가 아니라 현재 원소(`__feN[__iN]`)/인덱스(`__iN`)로 변환. 본문의 `#{index}`, 바깥 foreach 변수(중첩), `#{item,jdbcType=…}` 도 처리. `list.size() > 0`·`list.size == 0`·`list.isEmpty()`·`!list.isEmpty()` 조건 변환. `collection="vo.deptList"` 는 마지막 이름(`DEPTLIST`)을 매개변수로(전에는 `VO`).
  - `<where>`/`<trim>`/`<set>` 일반 처리: 조건 조각이 전부 `AND` 로 시작하는 흔한 형태는 그대로 `WHERE 1=1`, 무조건 조각으로 시작하면 `WHERE` + 앞머리 AND/OR 만 변환 시점에 제거, 그 밖(첫 조각에 AND 가 없거나 OR 로 시작, `prefix="AND ("`·`suffix`·`suffixOverrides`, `<set>` 의 끝 쉼표)은 스크립트에 `__trim` 도우미 함수를 넣어 **실행 시점에 MyBatis 와 같은 규칙으로** 정리. `crf_get_query` 평문 복원은 도우미 함수를 숨기고 `/*TRIM*/…/*END TRIM*/` 로 표시.
- `tools/mcpcall.py`: 도구가 돌려준 그림을 `[image mime WxH KB]` 로 요약하고 `CLIP_MCP_SAVE_IMAGES=<폴더>` 면 파일로 저장.
- **Codex 리뷰(16건) 재검토 후 반영 14건**:
  - 보안/자원 — HTML 을 여는 헤드리스 브라우저의 **네트워크 차단**(죽은 프록시 + DNS 차단; 로컬 서버로의 요청이 차단 전에는 닿고 후에는 안 닿는 것을 대조 확인, `CLIP_DOC_ALLOW_NETWORK=1` 로 해제) · JS 실행 전 스크립트 크기 상한과 메모리 부족 처리 · `'%{parameter.Q}%'` 처럼 **더 큰 SQL 리터럴 안의 토큰**도 값의 `'` 를 `''` 로(전에는 리터럴이 깨져 `mode=db` 로 보내는 SQL 이 바뀔 수 있었음) · 그림은 풀기 전에 화소 수 확인, zip 은 호출당 총량 예산, HWP 스트림 크기 상한 · 변환 캐시 키를 `String.hashCode`(경로·크기·시각) → **문서 내용 SHA-256** 으로, 변환은 임시 파일에 쓰고 완성 후 이름 변경 · LibreOffice 임시 폴더·변환 스크립트 정리.
  - MyBatis — `<where>` 의 `WHERE 1=1` 단순화는 **맨 앞에 올 수 있는 조각이 전부 AND 로 시작할 때만**(둘째 조각이 OR 면 `WHERE 1=1 OR …` 가 되어 전체 행이 나오던 문제 → `__trim`) · **빈 `<foreach>` 는 open/close 도 내지 않음**(`IN ()` 방지) · foreach 본문에 `<if>` 가 있으면 **구분자를 실제로 내용이 나온 회차 사이에만**(`('A',,'B')` 방지).
  - 파서 — HWP 레코드 길이 넘침/짧은 FileHeader 를 ERROR 로, XLSX 날짜의 1900 체계 60 미만·`date1904` 통합 문서, DOCX `gridBefore` 행의 열 번호·너비, 평문 복원이 사용자 도우미 함수 안의 SQL 을 지우던 것(변환기가 넣은 `__trim` 만 숨김), `/function/` 정규식 리터럴이 return 검사를 속이던 것, `mcpcall.py` 의 그림 base64 검증.
  - 미반영 2건: JS 실행을 별도 JVM 으로 격리(내장 함수 한 번으로 큰 문자열을 만드는 경우까지 끊으려면 필요 — 상한·오류 처리만 넣고 README 보안 절에 명시), 시간 초과 시 남는 Office 프로세스 자동 종료(사용자가 띄운 프로그램과 구분 불가 — README 한계에 명시).
- `tools/smoke.py` 330 케이스(+56): 문서 파서(픽스처를 테스트가 직접 생성)·미리보기·실행 SQL·JS lint·foreach/trim, 그리고 **문서 정합성 4건** — README 의 도구 수·도구 이름·케이스 수, CHANGELOG 의 현재 버전 항목이 코드와 어긋나면 FAIL.
### Fixed
- **`<where>` 가 무조건 조각으로 시작하면 깨진 SQL 을 만들던 문제**: `<where> USE_YN = 'Y' <if …>AND …</if></where>` → 전에는 `WHERE 1=1 USE_YN = 'Y'`.
- `<foreach>` 에 `collection` 이 없으면 태그 문자열이 SQL 로 새고 중괄호가 안 맞아 JS 문법 오류가 나던 문제 → 빈 목록 루프로 처리.
- `crf_append_query_condition` 등의 `return` 검사가 도우미 함수 안의 `return` 을 쿼리의 `return` 으로 세지 않게.
- DB 오류 메시지 정리: Tibero 가 붙이는 수백 자 공백 제거, 접속 계열 오류(timeout 등)면 네트워크/`.env` 확인 안내.
- README 를 현상에 맞춤: 도구 수(40/43 → 53), 구성요소(CrfTableOps·CrfDerive 등 누락), 테스트 케이스 수(152 → 321)와 픽스처, jar 크기, 릴리스 절차(소스의 VERSION 상수는 없어짐), `in_place`, 한계 절의 지난 내용(표 생성 미구현, foreach/choose 부분 지원, PLAN v0.7).
- `tools/smoke.py` 의 엑셀 격자 케이스가 실 리포트(ahrmhr0120_prn01)의 열 구성 변경으로 깨져 있던 것 → 경계 값을 고정하지 않게 수정.
### 한계(미반영)
- `__trim` 도우미가 들어간 쿼리와 foreach 변환 쿼리는 MCP 의 JS 엔진으로만 검증했고 **실서버 렌더 확인은 못 했다**(이번 작업 중 로컬 WAS·개발 DB 접속 불가).
- `doc_image` 의 한글(HWPFrame.HwpObject)·LibreOffice 변환 경로는 설치된 PC 가 없어 미검증. OCR 없음. `crf_preview` 는 설계 화면 그림(실제 출력 아님).
- `<include>`/`<bind>` 는 여전히 수동. foreach 원소의 속성(`item.code`)은 원소 자체로 대체(경고).

## [0.10.0] - 2026-09-10
v0.9.0 에서 미뤘던 ui-f6 요청 3건 + PLAN §3 생성기 개선을 한 번에. 새 코드는 `src/CrfDerive.java`.
### Added
- **`crf_generate` v2 — 목록형 리포트 한 방 파생**: `template` + `sql`(SQL/MyBatis/JS) + `columns=[{field|text,title,width,align,format,total,merge}]` + `title` + `cond`/`cond_right`(공식 또는 텍스트) + `groups=none|auto|A,B` 로 ① 템플릿 정리(그룹 밴드·그룹 객체·그룹이름/그룹인덱스 필드 제거, 누적합산의 그룹 기준 해제, SELECT 에 없는 필드는 셀/글상자 바인딩을 풀고 제거) ② 쿼리 적용(매개변수 선언, 필드 SELECT 순서; `fields_from_db=true` 또는 `SELECT *` 는 DB 메타데이터로 컬럼·타입 확정) ③ 본문 표 + 제목 표 + 합계 표를 columns 로 재구성(본문 표가 없으면 새로 생성) ④ 제목 글상자(머리글에서 가장 큰 정적 글상자)·조건 글상자(공식 바인딩 글상자 왼쪽/오른쪽) ⑤ 그룹 추가(`crf_add_group` 재사용: 머리글 라벨 + 소계 공식, 소계 라벨은 해당 열 위치·너비로 정렬하고 **'소 계' 라벨 자동 추가**) ⑥ 로고: 페이지 바닥글에 서브리포트가 이미 있으면 유지(예전엔 문자열 검색이라 임베디드는 못 봄) ⑦ 머리글 글상자를 본문 열 격자에 맞춤(엑셀 규칙) ⑧ 저장 후 되읽어 `crf_validate excel=true` 결과를 응답에 포함. 실서버 렌더(ahrmhr0240 템플릿 → 부서별 급여 현황, 그룹 DEPT_NM)로 제목·조건·그룹·소계·합계·바닥글 확인. `legacy=true` 면 v1(CrfGen3).
- **`crf_set_columns` — 열 세트 전체 교체**: 본문 표를 columns 로 재구성하고, 같은 열 구조의 제목 표(페이지/데이터 머리글)·합계 표(데이터 바닥글)·그룹 바닥글 표도 같이. 열 수 증감은 `crf_table_cols` 의 삽입/삭제를 재사용해 **기존 열 스타일(글꼴·테두리·정렬)을 상속**, 너비 미지정 열은 남은 너비 균등, 숫자 필드는 오른쪽 정렬, `total` 열은 `F_TOTAL_<필드>`/`F_SUB_<필드>`(그룹 기준) 공식 + 합 계/소 계 라벨. `merge`(셀합치기)는 기본 끔(템플릿이 전 셀 켜 둔 경우가 많아 파생 리포트에서 엉뚱한 병합이 생김). `snap=true` 면 머리글 글상자를 격자에 맞춤. 저장 후 격자 불변식 + 엑셀 격자 검사. 13열×2표 26호출 → 1호출.
- **`crf_append_query_condition`**: 기존 쿼리에 `if('{parameter.P}' != ''){ sql += "…"; }` 블록 하나 추가. 기본은 ORDER BY 앞(한 줄에 WHERE…ORDER BY 가 붙어 있으면 줄을 나눔), `position=end|after:<문자열>`(기준 줄이 if 안이면 그 블록이 닫힌 뒤). 평문 SQL(NotScript)은 JS 로 변환, `return sql;` 보장, `:p/#{p}` 정규화, 매개변수 선언, 저장 후 검증 + 삽입 부근 표시. 파이썬으로 raw 를 붙이다 `\r\n` 이스케이프가 깨져 0건 나던 문제의 대안.
- **MyBatis `<foreach>`·`<trim>`·`<set>`**: `<foreach collection=deptList item=d open="(" separator="," close=")">#{d}</foreach>` → `var __fe1 = ('{parameter.DEPTLIST}'=='')?[]:'{parameter.DEPTLIST}'.split(','); for(...){ if(i>0) sql+=","; sql += "'" + __fe1[i] + "'"; }` (매개변수는 `A,B,C` 처럼 쉼표 문자열로 전달, 경고로 안내; `${item}` 은 따옴표 없이). `<trim prefix="WHERE" prefixOverrides>` → `WHERE 1=1`, 그 외 prefix/suffix 는 붙이되 overrides 는 경고. `crf_get_query` 평문 복원이 `var`/`for` 를 `/*FOREACH*/` 로 표시.
- **Codex 리뷰(2회, 총 19건) 재검토 후 반영 16건**: 합계 공식은 함수·그룹까지 같을 때만 재사용(`F_TOTAL_AVG_*` 처럼 함수별 이름) · 제목/합계/그룹바닥글 표 자동 감지는 열 수 + **경계 2개 이상 일치**일 때만 · 세로 병합이 걸린 행도 안전하게 해제 · 글상자 격자 맞춤은 너비 70% 미만으로 줄이지 않음 · `crf_append_query_condition` 은 앞 줄에서 열린 괄호(`OVER (` / 서브쿼리) 안의 ORDER BY 를 무시하고, `…; return sql;` 한 줄 JS 도 문장 단위로 나눠 return 앞에 넣으며, `after:` 기준 줄이 if 안이면 else 분기까지 지난 뒤에 삽입 · 평문 SQL→JS 변환은 XML 엔티티를 풀지 않음(`'&lt;'` 보존) · foreach 본문의 `'#{item}'` 은 따옴표를 겹치지 않고 줄 끝 `\r\n` 유지, `<trim prefix=WHERE prefixOverrides=…OR>` 는 경고 · 모든 열이 집계면 합계 라벨 생략, 소계 라벨은 집계가 아닌 열에만 · 그룹 소계 공식에 열의 `total` 함수(avg 등) 반영 · `fields_from_db` 뒤에는 DB 메타데이터 기준으로만 필드 정리(파서 결과로 지우지 않음) · 템플릿 그룹 제거 시 누적합산의 평가/리셋 모드를 ForEachRecord/ResetNever 로 되돌리고, 지우는 필드를 집계하던 누적합산은 함께 제거 · `dbMeta` 는 못 쓰는/중복 컬럼명도 `COL_n` 자리 필드로 위치 보존(대소문자 무시). 미반영(한계로 문서화): foreach 안의 `<if test="item…">` 루프 변수 참조, `<trim>` overrides 일반 처리, JS 평문 복원으로 실행하는 DB 메타데이터(foreach 포함 쿼리는 실패 시 오류 안내).
- 서버 instructions `[★목록형 리포트는 crf_generate 한 방]`, README/GUIDE/`/clipreport` 도구 매핑, `tools/release_notes.py`(CHANGELOG → GitHub 릴리스 노트), `tools/smoke.py` 274 케이스(+38).
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
