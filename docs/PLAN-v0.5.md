# clip-report MCP 검토 결과 및 개선 계획 (2026-08-28)

대상: `clip-report-mcp` v0.4.1 (jar 빌드는 0.4.0). 검토 범위 = `src/` 전체, CLIP SDK(Viewer.jar) 실제 API(javap), 실 리포트 2종으로 도구 실행.

- 실행 검증 파일: `meta/adm/ahrm/ahrmrc/ahrmrc0460_prn01.crf`(22 데이터셋·JS 쿼리), `meta/sch/ssrm/ssrmva/ssrmva0350_prn17.crf`(본문=서브리포트 서브섹션 11개)
- 실행 방법: stdio JSON-RPC로 `initialize` + `tools/call` 직접 전송 (하네스는 §7 부록)

---

## 1. 확정 버그 (재현 근거 있음)

| ID | 증상 | 원인 | 근거 |
|---|---|---|---|
| **B1** | 접속할 때마다 "업데이트 있음(0.4.0→0.4.1)" 알림 | `CrfMcpServer.VERSION="0.4.0"` 상수와 `VERSION` 파일(0.4.1)이 따로 논다. jar 재빌드 없이 태그만 올림 | jar 내부 문자열 `0.4.0`, initialize instructions에 알림 포함 |
| **B2** | 병합된 자리의 셀에 `crf_set_cell` → **"OK"** 인데 아무것도 안 바뀜 | `getTableCell(r,c)`가 병합 하위 셀에서 `TableCellDumy` 반환 → `setApplyValueText` 없음 | `표3[1,0]` → OK 보고, stderr `NoSuchMethodException: TableCellDumy.setApplyValueType` |
| **B3** | (구조적) setter 실패가 항상 OK | `call()`이 예외를 stderr로만 찍고 삼킴 → `crf_set_cell`/`crf_set_cell_style` 전부 해당 | 코드 `CrfMcpServer.call()` |
| **B4** | `crf_add_label`/`crf_place_detail_fields`가 넣은 컨트롤이 **별도 리스트**에 들어감 | 기존 `ControlListForEachSeparatedPage`를 재사용하지 않고 `clp[1]`을 새로 add | 결과 파일 프로브: `clp[0] controls=0 / clp[1] controls=1` (렌더 여부 미확인 → 재사용이 안전) |
| **B5** | `crf_set_query`가 **scriptType을 안 바꿈** → JS 데이터셋에 평문 SQL 넣으면 JavaScript 로 남아 실행 불가 | `setScriptType` 호출 없음 | `b_query.crf` DS[0] `type=JavaScript`, 내용은 평문 SQL |
| **B6** | `'#{x}'` → `''{parameter.X}''` (따옴표 중복) | `subParamsQuoted`가 주변 따옴표를 안 봄 | 변환 결과 `''{parameter.LIT}''` |
| **B7** | `crf_set_query` 후 새 `{parameter.X}`가 **전역 매개변수로 선언 안 됨**, **필드 미동기화** | set_query는 문자열만 교체 (Gen3의 파라미터 선언 로직 미공유) | EMPNM 미생성, 41개 옛 필드 그대로 |
| **B8** | 공식/데이터 필드 **중복 이름 허용**, `return` 없는 공식 허용 | 검사 없음 | `NO_PASS_CDT` 2개(6개로 증가), `YEAR` 중복 추가 |
| **B9** | `crf_summary` 필드 40개에서 **조용히 절단** + 꼬리 콤마 | `j<40` 하드 제한 | 41개 필드 리포트에서 마지막 필드 누락 |
| **B10** | `crf_add_group`은 기존 그룹이 있으면 **항상 안쪽**으로 삽입 | Detail 바로 앞/뒤 고정 | STUDENT_CD 그룹 안에 DEPT_CD 그룹 생김(상위여야 함) |
| **B11** | `crf_generate`: ① 템플릿의 기존 그룹 필드가 **`null`로 댕글링** ② 페이지바닥글 **로고 서브섹션 중복** ③ GROUP BY 컬럼마다 그룹 중첩(DEPT_CD ⊃ DEPT_NM) | ① `fl.removeAll()` 후 참조 정리 없음 ② 로고 감지가 `"bottom_logo.crf"` 문자열 검색 → 임베디드 서브리포트는 못 봄 ③ 정책 부재 | 생성 결과 `[0] GroupHeader -> null`, 바닥글 sub[1] 추가, groups=3 |
| **B12** | `crf_describe_layout`이 **본문 밴드를 비어 있게** 보여줌 (docs 노트의 "본문 표 못 봄" 현상) | 본문이 `SubSectionSubreport`(리포트 서브섹션)인데 `SubSectionDefault`만 처리. 서브섹션 이름/높이/**가시성(vis=false)** 도 미표시, 병합셀(Dumy)과 빈 셀을 같은 `·`로 표시 | ssrmva0350 본문 = 서브리포트 서브섹션 11개, ahrmrc0460 본문1/본문2 `vis=false` |
| **B13** | `db_query`가 **DML/DDL 실행 가능**, 타임아웃 없음 | `st.execute(sql)` 그대로 | 코드 |
| **B14** | 도구 실패가 JSON-RPC **error(-32000)** 로 나가고 메시지가 빈약 (빈 경로 → `FileNotFoundException: `) | 경로 검증 없음, MCP `isError` 미사용 | 실행 결과 |
| B15 | `:colNm` 변환이 문자열 리터럴 내부를 구분 못 함 (위험 낮음) | 리터럴 마스킹 없음 | 코드 (`'HH24:MI'`는 우연히 안전) |

## 2. 기능 공백 — 사용자가 지적한 3영역

### 2.1 쿼리 찾기/읽기 (현재 **불가능**)
- 쿼리 **본문을 돌려주는 도구가 없다**. `crf_summary`는 `queryLen`만 보여줌. 공식 필드도 이름만(스크립트 없음) → "이 리포트 쿼리 설명해줘"에 답할 수 없음.
- 폴더 단위 검색이 없다(테이블명·컬럼·파라미터·텍스트로 리포트 찾기). SDK 읽기 속도 실측 **4 ms/파일** → 2,535개 전체 ≈ 10~15 s, 실용 가능.
- 데이터셋별 **매개변수(SQLParameter/ScriptParameter)**, 서브리포트 **파라미터 링크**, 연결(Connection) 정보 미노출.

### 2.2 쿼리(데이터셋) 수정
- 첫 데이터셋(`get(0)`) 고정 — 22개 데이터셋 리포트에서 다른 데이터셋 수정 불가.
- scriptType 자동 판별 없음(B5), MyBatis 입력은 set_query에서 미변환, 필드/매개변수 동기화 없음(B7).
- `SELECT *`/`TABLE(fn)` 쿼리(실 쿼리의 ~19%)는 SQL 파싱으로 컬럼을 못 뽑음 → **DB ResultSetMetaData**로 확정하는 경로가 없음(README 자체가 1순위라고 적어둠).
- 데이터셋 추가/삭제/이름변경, 필드 삭제/이름변경(바인딩 재연결), 매개변수 속성(타입/기본값/프롬프트) 편집 없음.

### 2.3 리포트 수정
- 삭제 계열 없음(컨트롤/필드/그룹/섹션), 표(ControlTable) 생성 없음(README TODO), 셀 정렬·폰트크기·굵게·테두리 없음, 서브섹션 높이/가시성/이름 편집 없음, 조건스타일 없음.
- 검증(lint) 없음: 실 리포트에서 `rexpert.fieldbyint("#unknown#")` 같은 **깨진 공식**, 그룹 필드 `null` 발견 — 도구가 이걸 잡아줘야 수정이 안전함.
- `crf_diff`가 필드명/그룹수/섹션명만 비교 — 쿼리·공식·셀 바인딩 변화는 안 보임.

---

## 3. 로드맵

### v0.4.2 — 핫픽스 (작음, 먼저) — ✅ 2026-08-28 완료 (`tools/smoke.py` 32/32)
> 앞당겨 구현: `crf_set_query` 의 dataset 선택 + scriptType 자동 판별(B5). 남은 것: 매개변수 선언·필드 동기화(v0.5.1).

1. **B1** VERSION 단일화: `build.ps1`이 `VERSION` 파일을 jar 리소스(`/VERSION`)로 포함, 서버는 리소스에서 읽음(상수 제거). `update.ps1`/README 문구 유지.
2. **B3/B2** `call()` → 실패 시 예외 전파. `getTableCell`이 `TableCellDumy`면 `ERROR: [r,c]는 병합된 셀(기준 셀 [r0,c0]에 설정하세요)`. 기준 셀 좌표는 `getTableCellLeftTop` 등으로 역산(없으면 "왼쪽/위쪽 인접 정상 셀" 안내).
3. **B4** `add_label`/`place_detail_fields`: `getControlListForEachSeparatedPageList().get(0)` 재사용(없을 때만 생성). 이름 중복 시 `_2` 접미.
4. **B8** 이름 중복 검사(데이터/공식/매개변수 전 목록), 공식 스크립트에 `return` 없으면 ERROR(옵션 `force=true`).
5. **B9** 절단 제거(전체 출력, 데이터 타입 포함 `NAME:String`).
6. **B13** `db_query`: 첫 키워드가 `SELECT|WITH`가 아니면 거부(`allow_write=true`일 때만 허용), `setQueryTimeout(30)`, `setReadOnly(true)` 시도.
7. **B14** 응답 규약: 성공 `OK: …`, 실패는 `content.text="ERROR: …"` + `isError:true`(예외도 동일). 경로 존재/확장자 사전 검사, `output`이 원본과 같으면 거부(원본 보존 규칙).
8. **B6/B15** `subParamsQuoted`: 문자열 리터럴 마스킹 후 치환, 이미 따옴표로 감싸진 `'#{x}'`는 `'{parameter.X}'`로.

### v0.5.0 — 쿼리 읽기·찾기 (사용자 요구 1순위) — ✅ 2026-08-28 완료
> 앞당겨 구현: describe v2 의 서브섹션/서브리포트 표시(B12). `crf_search` 는 바이트 프리필터 없이 SDK 읽기만 사용(쿼리가 파일 내 암호화돼 있어 프리필터 불가; 실측 ~5ms/파일이라 충분).
| 도구 | 입력 | 출력 |
|---|---|---|
| **`crf_get_query`** | `path`, `dataset?`(이름 또는 인덱스, 기본 전체) | 데이터셋별: 이름·scriptType·연결·**쿼리 전문**·사용 `{parameter.X}` 목록·SQLParameter/ScriptParameter·필드(이름:타입)·FROM/JOIN 테이블 추정. JS 쿼리는 원문 + **평문 복원본**(문자열 연결만 풀어낸 SQL) 함께 |
| **`crf_get_formula`** | `path`, `name?` | 공식 전 스크립트(+누적합산: 요약함수/대상필드/리셋그룹, 그룹이름 필드→그룹) |
| **`crf_search`** | `dir`, `text`, `regex?`, `scope=query\|field\|formula\|param\|control\|any`, `like?`(파일명), `limit` | 파일별 히트: 데이터셋/필드/공식명 + 매칭 줄. 1차 UTF-16LE 바이트 프리필터 → 2차 SDK 정밀 확인. 예: "AHRM1234 쓰는 리포트", "파라미터 DEPTCD 받는 리포트" |
| `crf_list_reports` | `+ like?`, `limit?`(기본 500), 총계 | 이름 필터·총 개수 |
| `crf_summary` | 동일 | 데이터셋별 scriptType·필드(타입)·매개변수(타입/기본값)·그룹→필드·섹션별 서브섹션(이름/높이/가시/유형) 요약 |

### v0.5.1 — 쿼리/데이터셋 수정 — ✅ 2026-08-28 완료
> 추가: `crf_field_refs`(참조 조회). 실 DB(개발 Tibero)로 `mode=db` 검증 — 11k자 JS 동적쿼리도 평문 복원본으로 실행돼 41컬럼 확정.
- **`crf_set_query` v2**: `dataset?`(이름/인덱스), `script_type?`(`auto|sql|javascript`; auto = MyBatis 태그→JS 변환, `var sql`/`sql +=`/`+"` 패턴→JavaScript, 그 외→NotScript), `declare_params=true`(새 `{parameter.X}` 전역 매개변수 String 선언), `sync_fields=none|add|replace`(SELECT 파싱으로 필드 추가/교체, 제거 시 바인딩 참조 있으면 경고·중단), 결과에 변환 diff 요약.
- **`crf_sync_fields`**: `mode=sql|db`. `db`는 **쿼리를 실행해 ResultSetMetaData로 컬럼·타입 확정**(`{parameter.X}`는 `params` JSON 또는 `''`/NULL 대입, `WHERE 1=0` 래핑, JS 쿼리는 평문 복원본 사용) → `SELECT *`/함수테이블 해결. 타입 매핑 NUMBER→Number/Currency(이름 휴리스틱), DATE→DateTime.
- **`crf_add_dataset`**(`name`, `sql`, 첫 데이터셋 연결 복제) / **`crf_remove_dataset`**(참조 있으면 거부).
- **`crf_set_param`**(추가/수정: `name,type,default,prompt`) / `crf_remove_param`.
- **`crf_rename_field`**(바인딩·공식 문자열 `data.OLD`→`data.NEW` 동시 치환) / **`crf_remove_field`**(참조 목록 보여주고 `force` 없으면 거부).

### v0.6.0 — 레이아웃 읽기/수정 강화 — ✅ 2026-08-28 완료
> 미구현: `crf_set_cell` 의 `crf_set_cell_style` 통합(둘 다 유지, 같은 적용기). `crf_add_table` 은 1행 데이터 표 + 제목 표 방식. 소계 공식은 `rexpert.sum(0,"data.F",0,"data.그룹필드","")` 로 생성(인자 의미는 실행 미검증 → 문서에 명시).
- **`crf_describe_layout` v2**: 섹션→서브섹션(유형/이름/높이/가시성/페이지바꿈) 구조로 출력. `SubSectionSubreport`·`ControlSubreport`는 링크 경로/임베디드 여부/파라미터 링크(`FieldLink` 1↔2) 표시. 셀은 `‹병합›`(Dumy)·`·`(빈 정상셀) 구분, 병합 플래그(`getCellMergeRowDataDuplication`)·정렬·폰트크기/굵게·출력양식 표시(옵션 `detail=true`). 조건스타일 개수+조건 요약. 그룹머리글에 `→ 그룹필드`. (docs/crf-binary-notes.md의 바이트 스캔은 이걸로 대체)
- **`crf_set_cell` v2**: 병합셀 ERROR(위 2), `clear=true`, `formula="…"`(공식필드 생성+바인딩 한 번에), `align`, `valign`, `fontsize`, `bold`, `wrap`. `crf_set_cell_style`과 통합 검토.
- **`crf_set_subsection`**: `section`, `index?`, `height`, `visible`, `name`, `new_page`.
- **`crf_remove_control`** / **`crf_remove_group`**(머리·바닥글 함께) / **`crf_remove_section`**.
- **`crf_add_table`**: 본문(또는 지정 밴드)에 `columns=[{field,title,width,format}]`로 **표 생성**(머리글 행은 데이터머리글/그룹머리글, 본문 행은 Detail). 디자이너에서 열리는 최소 필수 속성은 실 리포트의 `ControlTable`을 복제(`copy`)해 확인 후 확정.
- **`crf_add_group` v2**: `level?`(`outer|inner|N`)로 위치 지정, `label=true`(머리글에 그룹이름 라벨), `subtotal=[fields]`(바닥글에 `rexpert.sum` 공식 자동 생성). `crf_set_group`(정렬/필드 변경).
- **`crf_validate`**(lint): 공식의 `#unknown#`/미존재 필드 참조, 셀·라벨의 미존재 필드 바인딩, 그룹 필드 `null`, 쿼리에서 쓰는데 미선언된 매개변수/선언됐는데 안 쓰는 매개변수, SELECT에 없는 데이터 필드(파싱 가능할 때), JavaScript scriptType인데 `var`/`+` 없는 평문, 중복 이름, `vis=false` 서브섹션 안내. 수정 도구 실행 후 자동으로 요약 한 줄 첨부.
- **`crf_diff` v2**: 데이터셋 쿼리 텍스트 diff(줄 단위), 공식 스크립트 diff, 셀 바인딩 그리드 diff, 매개변수 diff.

### v0.7.3 — XPath 검색/읽기 — ✅ 2026-09-09 완료
- `crf_search scope=xpath`, `crf_get_query` XML/JSON 경로 출력 → FindQuery(문자열찾기) 유틸 완전 대체.

### v0.7.2 — 디자인 결정 질문 규칙 — ✅ 2026-09-09 완료
- 요소 구성·체크박스 모양·레이아웃·글꼴·쿼리·저장 갈림길을 작업 전/중간에 사용자에게 묻도록 instructions·슬래시 명령·GUIDE 에 내장.

### v0.7.1 — 요소 구성 유연성 + 글꼴 상속 — ✅ 2026-09-09 완료
- `crf_merge_labels` / `crf_split_label` / `crf_add_table rows=` / `crf_set_font` / 글꼴 자동 상속 / lint(System 글꼴·세로 연속 글상자·글꼴 섞임·엑셀 격자) / `repeat=` / 로컬 메모리 규칙을 서버 instructions 에 내장. smoke 152.

### v0.7.0 — 생성기(`crf_generate`) 개선
- 템플릿 정리 옵션 `clean_template=true`: 기존 그룹·바인딩 제거 후 생성(B11①). 바닥글 로고: 페이지바닥글에 `ControlSubreport`가 이미 있으면 skip(B11②).
- `groups?` 명시(기본: GROUP BY **첫 컬럼만**, 나머지는 안내)(B11③). `fields_from_db=true`로 v0.5.1의 DB 메타데이터 사용. 본문을 라벨 대신 `crf_add_table`로 배치.
- MyBatis: `<choose>/<when>/<otherwise>` → `if / else if / else`, `<foreach>` 단순 IN 목록 변환, `<trim prefixOverrides>` 처리.

---

## 4. 공통 인프라 (v0.4.2에 같이)
- **데이터셋 셀렉터** 헬퍼: 이름(대소문자 무시)·인덱스 모두 허용, 없으면 목록과 함께 ERROR.
- **쓰기 후 재읽기 검증**: 모든 쓰기 도구가 `Rexpert4.read(output)`로 되읽어 핵심 값이 반영됐는지 확인하고 결과에 표기(현재 add_formula_field만 함).
- **리플렉션 축소**: javap로 확인된 실제 타입(`TableCellNormal`, `SubSectionSubreport`, `ControlSubreport`…)으로 직접 호출. `collectControls` 리플렉션 탐색은 describe v2에서 명시적 순회로 교체.
- **회귀 테스트**: `tools/mcpcall.py`(§7) + `tools/smoke.ps1` — 위 두 실 리포트로 읽기 도구 스냅샷 비교, 쓰기 도구는 `scratch/out`에 쓰고 되읽어 assert. `build.ps1` 끝에 smoke 실행 옵션.
- 문서: 도구 추가마다 `INSTRUCTIONS`(서버)·`setup/clipreport.md`(3) 도구 매핑)·`GUIDE.md §10`·`README 도구표`·`CHANGELOG` 동시 갱신. 도구 수가 30개 근처가 되므로 README 표를 읽기/쓰기/DB로 재편.

## 5. 리스크 · 미확인 사항 (구현 중 확인)
1. `clp[1]`(두 번째 ControlListForEachSeparatedPage)이 디자이너/뷰어에서 렌더되는지 — 확인 전엔 재사용으로만 간다.
2. **임베디드 서브리포트**(`getSubreport()!=null`) 포함 파일의 write 라운드트립 무손실 여부 — 삭제 도구 출시 전 바이트/구조 비교 필수.
3. JS 동적쿼리 → 평문 복원: 1차는 문자열 연결/`if(param)` 블록 처리하는 미니 평가기. `batik.jar`에 Rhino(`org.mozilla.javascript.Context`)가 동봉돼 있어 파라미터 스텁으로 실제 평가하는 2차 옵션 가능(라이선스/버전 확인).
4. 새 `ControlTable` 생성 시 필수 내부 속성(`linkBaseCell`, `setBaseCellRowColIndex`, LineInfo 등) — 실 리포트 표를 `copy()`로 복제해 셀만 갈아끼우는 방식이 안전할 수 있음.
5. `SectionGroupFooter`에는 `setGroup`이 없음(위치로 결정) → 그룹 삽입/삭제는 **머리글·바닥글 대칭 위치**를 반드시 유지.
6. DB 메타데이터 동기화는 DB 접속 필요(.env) — 없으면 `mode=sql`로 자동 폴백하고 안내.

## 6. 실행 순서 제안
1. v0.4.2 핫픽스 + 테스트 하네스 (반나절) → 태그 `v0.4.2`
2. v0.5.0 `crf_get_query`/`crf_get_formula`/`crf_search`/summary 개선 (1일) → `v0.5.0`
3. v0.5.1 `crf_set_query` v2 + `crf_sync_fields`(db) + 데이터셋/매개변수/필드 편집 (1~2일) → `v0.5.1`
4. v0.6.0 describe v2 → validate → set_cell v2/subsection/remove → add_table/add_group v2 → diff v2 (2~3일)
5. v0.7.0 생성기 (1일)

각 단계 완료 기준: smoke 통과 + 두 실 리포트에서 결과를 CLIP 디자이너로 열어 확인(열림·저장 가능) + CHANGELOG/README/INSTRUCTIONS 갱신.

## 7. 부록 — 테스트 하네스 (`tools/mcpcall.py`로 추가 예정)
```python
import sys, json, subprocess
JAVA=r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe"
CP=r"C:\Program Files (x86)\Clipsoft\CLIP report v5.0\bin\jar\*;C:\Users\ckato\clip-report-mcp\clip-report-mcp.jar"
calls=json.loads(sys.argv[1])          # [["crf_summary",{"path":"C:/x.crf"}], ...]
lines=[json.dumps({"jsonrpc":"2.0","id":0,"method":"initialize","params":{}})]
for i,(t,a) in enumerate(calls,1):
    lines.append(json.dumps({"jsonrpc":"2.0","id":i,"method":"tools/call","params":{"name":t,"arguments":a}}))
p=subprocess.run([JAVA,"-cp",CP,"CrfMcpServer"],input=("\n".join(lines)+"\n").encode(),capture_output=True)
for ln in p.stdout.decode("utf-8","replace").splitlines():
    o=json.loads(ln)
    if o.get("id")==0: print("serverInfo",o["result"]["serverInfo"]); continue
    print(f"[{o['id']}]", o.get("error") or o["result"]["content"][0]["text"])
print(p.stderr.decode("utf-8","replace")[-2000:])
```
실행: `PYTHONIOENCODING=utf-8 python tools/mcpcall.py '[["crf_summary",{"path":"C:/.../x.crf"}]]'` (경로는 `/` 구분자 권장).
