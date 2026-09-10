#!/usr/bin/env python3
"""회귀 스모크 테스트: 실 리포트 2종에 읽기/쓰기 도구를 돌려 기대 동작을 assert.

  python tools/smoke.py            # 전부
  python tools/smoke.py -v         # 각 도구 응답 출력
픽스처 경로는 CLIP_SMOKE_A / CLIP_SMOKE_B / CLIP_SMOKE_C 환경변수로 바꿀 수 있다. 없으면 해당 케이스 skip.
쓰기 결과는 <proj>/.smoke-out/ 에 기록(gitignore).
"""
import os, sys, json, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mcpcall

PROJ = mcpcall.PROJ
A = os.environ.get("CLIP_SMOKE_A", "C:/eGovFrameDev-4.3.1/workspace/report/meta/adm/ahrm/ahrmrc/ahrmrc0460_prn01.crf")
B = os.environ.get("CLIP_SMOKE_B", "C:/eGovFrameDev-4.3.1/workspace/report/meta/sch/ssrm/ssrmva/ssrmva0350_prn17.crf")
C = os.environ.get("CLIP_SMOKE_C", "C:/eGovFrameDev-4.3.1/workspace/report/meta/sch/ssrm/ssrmet/ssrmet0220_prn01.crf")  # 문서형(양식) 리포트: 표_신고자 2x4, 표_동의 체크박스
D = os.environ.get("CLIP_SMOKE_D", "C:/eGovFrameDev-4.3.1/workspace/report/meta/sch/ssrm/ssrmet/ssrmet0230_prn02.crf")  # 서약서: 글상자3,4,5 / 7,8 세로 연속(v0.7.1 merge/split/font 검증용)
OUT = os.path.join(PROJ, ".smoke-out").replace("\\", "/")
os.makedirs(OUT, exist_ok=True)
VERBOSE = "-v" in sys.argv

CASES = []  # (name, tool, args, check(text,is_err)->str|None)


def case(name, tool, args, check):
    CASES.append((name, tool, args, check))


def ok(text, err):      return None if not err and text.startswith("OK") else f"expected OK, got err={err}: {text[:200]}"
def error(text, err):   return None if err and text.startswith("ERROR") else f"expected ERROR, got err={err}: {text[:200]}"
def contains(*subs):
    def chk(text, err):
        miss = [s for s in subs if s not in text]
        return None if not miss and not err else f"missing {miss} err={err}: {text[:300]}"
    return chk
def err_contains(*subs):
    def chk(text, err):
        miss = [s for s in subs if s not in text]
        return None if not miss and err else f"expected ERROR with {subs}, got err={err}: {text[:300]}"
    return chk


# ---- 읽기 ----
if os.path.isfile(A):
    case("summary A: dataset count", "crf_summary", {"path": A}, contains("datasets=22", "EXMPT_RESN_NM"))
    case("describe A", "crf_describe_layout", {"path": A}, contains("ControlTable", "표3"))
if os.path.isfile(B):
    case("summary B: 41 fields no truncation + types", "crf_summary", {"path": B},
         lambda t, e: None if not e and all(x in t for x in ("datasets=12", "groups=1 [STUDENT_CD]", "PASS_CDT_NUM05", "fields(41)")) and ":Null" not in t else f"summary B: {t[:300]}")
case("summary missing file", "crf_summary", {"path": "C:/definitely/missing.crf"}, err_contains("ERROR", "missing.crf"))
case("summary empty path", "crf_summary", {"path": ""}, error)

# ---- 쓰기: 셀 ----
if os.path.isfile(A):
    case("set_cell merged(dummy) cell -> ERROR", "crf_set_cell",
         {"path": A, "table": "표3", "row": "1", "col": "0", "text": "X", "output": OUT + "/a_dummy.crf"}, err_contains("병합"))
    case("set_cell normal cell -> OK", "crf_set_cell",
         {"path": A, "table": "표3", "row": "0", "col": "0", "text": "주소TEST", "format": "#,##0", "output": OUT + "/a_ok.crf"}, contains("OK", "verified[", "format=#,##0"))
    case("set_cell verify written", "crf_describe_layout", {"path": OUT + "/a_ok.crf"}, contains('"주소TEST"{#,##0}'))
    case("set_cell out of range -> ERROR", "crf_set_cell",
         {"path": A, "table": "표3", "row": "9", "col": "0", "text": "X", "output": OUT + "/a_oor.crf"}, error)
    case("set_cell missing table -> ERROR", "crf_set_cell",
         {"path": A, "table": "없는표", "row": "0", "col": "0", "text": "X", "output": OUT + "/a_nt.crf"}, error)
    case("output == input -> ERROR", "crf_set_cell",
         {"path": A, "table": "표3", "row": "0", "col": "0", "text": "X", "output": A}, err_contains("원본"))
    case("set_cell_style merged -> ERROR", "crf_set_cell_style",
         {"path": A, "table": "표3", "row": "1", "col": "0", "bgcolor": "#FFFF00", "output": OUT + "/a_style_dummy.crf"}, error)
    case("set_cell_style ok", "crf_set_cell_style",
         {"path": A, "table": "표3", "row": "0", "col": "0", "bgcolor": "#FFFF00", "merge": "true", "output": OUT + "/a_style.crf"}, ok)

# ---- 쓰기: 라벨/필드/그룹/쿼리 ----
if os.path.isfile(B):
    case("add_label -> OK (clp[0] reuse)", "crf_add_label",
         {"path": B, "section": "그룹머리글", "text": "LABEL_TEST", "output": OUT + "/b_label.crf"}, ok)
    case("add_label verify", "crf_describe_layout", {"path": OUT + "/b_label.crf"}, contains('"LABEL_TEST"'))
    case("add_formula duplicate -> ERROR", "crf_add_formula_field",
         {"path": B, "name": "NO_PASS_CDT", "script": "return 1;", "output": OUT + "/b_dupf.crf"}, err_contains("중복"))
    case("add_formula no return -> ERROR", "crf_add_formula_field",
         {"path": B, "name": "NEW_F1", "script": "var x=1;", "output": OUT + "/b_noret.crf"}, err_contains("return"))
    case("add_formula ok", "crf_add_formula_field",
         {"path": B, "name": "NEW_F1", "script": "return rexpert.field(\"data.NM\");", "output": OUT + "/b_f.crf"}, ok)
    case("add_data_field duplicate -> ERROR", "crf_add_data_field",
         {"path": B, "name": "YEAR", "type": "Number", "output": OUT + "/b_dupd.crf"}, err_contains("중복"))
    case("add_data_field ok", "crf_add_data_field",
         {"path": B, "name": "NEW_COL", "type": "Number", "output": OUT + "/b_d.crf"}, ok)
    case("add_data_field verify (type shown when known)", "crf_summary", {"path": OUT + "/b_d.crf"}, contains("fields(42)", "NEW_COL:Number"))
    case("add_group ok", "crf_add_group", {"path": B, "column": "DEPT_CD", "output": OUT + "/b_g.crf"}, ok)
    case("add_group verify", "crf_summary", {"path": OUT + "/b_g.crf"}, contains("groups=2"))
    sql = "SELECT TO_CHAR(SYSDATE,'HH24:MI') A, '#{lit}' B FROM T WHERE C = #{empNm} AND D = :deptCd AND E = ':notparam' AND F='{parameter.ALREADY}'"
    case("set_query quoting", "crf_set_query", {"path": B, "sql": sql, "output": OUT + "/b_q.crf"},
         contains("'{parameter.LIT}'", "'{parameter.EMPNM}'", "'{parameter.DEPTCD}'", "':notparam'", "'HH24:MI'"))
    case("set_query no double quotes", "crf_set_query", {"path": B, "sql": sql, "output": OUT + "/b_q2.crf"},
         lambda t, e: None if "''{parameter" not in t else "double quotes present: " + t[:300])

    case("set_query dataset selector bad -> ERROR", "crf_set_query",
         {"path": B, "sql": "SELECT 1 A FROM DUAL", "dataset": "NOPE", "output": OUT + "/b_q3.crf"}, err_contains("사용 가능"))
    mb = open(os.path.join(PROJ, "samples", "mybatis_sample.xml"), encoding="utf-8").read()
    case("set_query MyBatis -> JavaScript on dataset 1", "crf_set_query",
         {"path": B, "sql": mb, "dataset": "1", "output": OUT + "/b_q4.crf"},
         contains("SQLDS2", "NotScript → JavaScript", "var sql", "'{parameter.SALYYM}'", "ORDERBY"))
    js_q = 'var sql = "SELECT A ";\nsql += " FROM T WHERE X = #{x} ";'
    case("set_query JS passthrough", "crf_set_query",
         {"path": B, "sql": js_q, "dataset": "SQLDS3", "output": OUT + "/b_q5.crf"},
         contains("SQLDS3", "→ JavaScript", "'{parameter.X}'"))
    case("generate from SQL sample", "crf_generate",
         {"template": B, "sql": open(os.path.join(PROJ, "samples", "sample_query.sql"), encoding="utf-8").read(), "output": OUT + "/gen.crf"},
         contains("written", "fields(6)"))
    case("generate output==template -> ERROR", "crf_generate", {"template": B, "sql": "SELECT 1 A FROM DUAL", "output": B}, error)

# ---- v0.5.0 읽기: 쿼리/공식/검색/목록 ----
if os.path.isfile(A):
    case("get_query JS plain reconstruction", "crf_get_query", {"path": A, "dataset": "0", "mode": "plain"},
         contains("scriptType=JavaScript", "/*IF", "END IF", "FROM ADM.AHRM810", "테이블(추정): [ADM.AHRM801, ADM.AHRM810]", "매개변수 사용: [APPCANO"))
    case("get_query raw only", "crf_get_query", {"path": A, "dataset": "SQLDS1", "mode": "raw"},
         lambda t, e: None if not e and "var sql" in t and "평문 복원" not in t else f"raw: {t[:200]}")
    case("get_query NotScript + dataset refs", "crf_get_query", {"path": A, "dataset": "SQLDS3"},
         contains("scriptType=NotScript", "데이터셋참조: [APPCASEQNO", "ADM.AHRM811", "----- query -----"))
    case("get_query bad dataset", "crf_get_query", {"path": A, "dataset": "NOPE"}, err_contains("사용 가능"))
    A_DIR = os.path.dirname(A)
    case("list_reports like+limit", "crf_list_reports", {"dir": A_DIR, "like": "ahrmrc04*", "limit": "3"}, contains("total", "ahrmrc0460_prn01.crf"))
    case("search query scope (table/code)", "crf_search", {"dir": A_DIR, "text": "AHRM0750", "scope": "query", "limit": "5"},
         contains("file(s) hit", "[query SQLDS1]", "ahrmrc0460_prn01.crf"))
    case("search any scope (label text)", "crf_search", {"dir": A_DIR, "text": "임용지원서", "limit": "3"},
         contains("[control Detail] Label", "임용지원서"))
    case("search regex", "crf_search", {"dir": A_DIR, "text": r"FN_CSYS_CODE_NM\(\'AHRM07", "regex": "true", "scope": "query", "limit": "2"}, contains("file(s) hit", "AHRM07"))
    case("search no match", "crf_search", {"dir": A_DIR, "text": "ZZZ_NOTHING_HERE_ZZZ", "scope": "field"}, contains("0 file(s) hit", "(일치 없음)"))
    case("search bad dir", "crf_search", {"dir": "C:/definitely/missing", "text": "x"}, error)
if os.path.isfile(B):
    case("get_formula all (+broken ref)", "crf_get_formula", {"path": B},
         contains("== 공식 NO_PASS_CDT", "#unknown#", "== 누적합산 NO", "== 그룹이름", "→ 그룹필드 STUDENT_CD", "참조: [data.CLASS_DIV_NM, data.DEPT_CD_NM]"))
    case("get_formula one", "crf_get_formula", {"path": B, "name": "curi_year"}, contains("== 공식 CURI_YEAR", "교과적용"))
    case("get_formula missing", "crf_get_formula", {"path": B, "name": "NOPE"}, error)
    case("summary v2 sections/subsections/params", "crf_summary", {"path": B},
         contains("[1] Detail:", "[Subreport]", "매개변수링크:", "GroupHeader(→STUDENT_CD)", 'SYY="2018"'))
    case("describe v2 subreport subsections", "crf_describe_layout", {"path": B},
         contains('sub[0] "리포트 서브섹션1" [Subreport]', "링크=../../../images/bottom_logo.crf"))

# ---- v0.5.1 데이터셋/매개변수/필드 편집 ----
if os.path.isfile(B):
    case("field_refs data field (group+cell+links)", "crf_field_refs", {"path": B, "name": "STUDENT_CD"},
         contains("참조 11곳", "그룹.GroupingField", 'Table"표2"[3,4].ApplyValueField', "Detail/리포트 서브섹션1/매개변수링크.LinkedField1"))
    case("field_refs param in query", "crf_field_refs", {"path": B, "name": "SYY"}, contains("데이터셋 SQLDS1 쿼리 {parameter.SYY}"))
    case("field_refs ambiguous -> ERROR", "crf_field_refs", {"path": B, "name": "VISIBLE_CHK"}, err_contains("여러 곳", "dataset"))
    case("field_refs with dataset", "crf_field_refs", {"path": B, "name": "VISIBLE_CHK", "dataset": "SQLDS2"}, contains("참조 0곳"))
    case("rename data field rewrites formula", "crf_rename_field", {"path": B, "name": "DEPT_CD_NM", "new_name": "DEPT_NM2", "output": OUT + "/b_ren.crf"},
         contains("OK", "공식 스크립트 1개"))
    case("rename verify formula text", "crf_get_formula", {"path": OUT + "/b_ren.crf", "name": "DEPT_CLASS_NM"}, contains('rexpert.field("data.DEPT_NM2")'))
    case("rename param rewrites query token", "crf_rename_field", {"path": B, "name": "DEPTCD", "new_name": "DEPT_CD2", "output": OUT + "/b_ren2.crf"}, contains("쿼리 1개 재작성"))
    case("rename verify query token", "crf_get_query", {"path": OUT + "/b_ren2.crf", "dataset": "0", "mode": "raw"},
         lambda t, e: None if not e and "{parameter.DEPT_CD2}" in t and "{parameter.DEPTCD}" not in t else f"token: {t[:200]}")
    case("rename duplicate -> ERROR", "crf_rename_field", {"path": B, "name": "NM", "new_name": "YEAR", "output": OUT + "/b_ren3.crf"}, err_contains("중복"))
    case("remove_field referenced -> ERROR", "crf_remove_field", {"path": B, "name": "NM", "output": OUT + "/b_rm.crf"}, err_contains("참조", "[3,6]"))
    case("remove_field force", "crf_remove_field", {"path": B, "name": "NM", "force": "true", "output": OUT + "/b_rm2.crf"}, contains("OK", "끊어졌습니다"))
    case("remove_field unreferenced", "crf_remove_field", {"path": B, "name": "CHK", "output": OUT + "/b_rm3.crf"}, ok)
    case("remove_field verify", "crf_summary", {"path": OUT + "/b_rm3.crf"}, lambda t, e: None if not e and "fields(40)" in t and ", CHK," not in t else f"summary: {t[:200]}")
    case("set_param create", "crf_set_param", {"path": B, "name": "NEW_P", "type": "Number", "default": "10", "prompt": "새 값", "output": OUT + "/b_p.crf"},
         contains("created", "type=Number", 'default="10"'))
    case("set_param update", "crf_set_param", {"path": OUT + "/b_p.crf", "name": "new_p", "default": "20", "output": OUT + "/b_p2.crf"}, contains("updated", 'default="20"', "type=Number"))
    case("set_param name clash -> ERROR", "crf_set_param", {"path": B, "name": "YEAR", "output": OUT + "/b_p3.crf"}, err_contains("중복"))
    case("remove_param referenced -> ERROR", "crf_remove_param", {"path": B, "name": "DEPTCD", "output": OUT + "/b_rp.crf"}, err_contains("{parameter.DEPTCD}"))
    case("remove_param ok", "crf_remove_param", {"path": OUT + "/b_p.crf", "name": "NEW_P", "output": OUT + "/b_rp2.crf"}, ok)
    sq = open(os.path.join(PROJ, "samples", "sample_query.sql"), encoding="utf-8").read()
    case("set_query v2 declares params + adds fields", "crf_set_query", {"path": B, "sql": sq, "dataset": "1", "output": OUT + "/b_sq.crf"},
         contains("매개변수 선언: [SALYYM]", "필드 추가: [DEPT_CD, DEPT_NM, EMP_CNT, TOTAL_AMT, AVG_AMT, LAST_YM]", "필드(11)"))
    case("set_query v2 sync none / no declare", "crf_set_query", {"path": B, "sql": sq, "dataset": "1", "declare_params": "false", "sync_fields": "none", "output": OUT + "/b_sq1.crf"},
         lambda t, e: None if not e and "선언되지 않은 매개변수: [SALYYM]" in t and "필드 추가" not in t else f"none: {t[:300]}")
    case("set_query v2 replace removes unreferenced", "crf_set_query", {"path": B, "sql": sq, "dataset": "1", "sync_fields": "replace", "output": OUT + "/b_sq3.crf"},
         contains("필드 제거(미참조): [", "필드(6)"))
    case("set_query SELECT * hint", "crf_set_query", {"path": B, "sql": "SELECT * FROM ADM.AHRM810 WHERE X=#{x}", "dataset": "SQLDS3", "output": OUT + "/b_sq2.crf"},
         contains("SELECT 목록을 파싱하지 못함", "mode=db"))
    case("sync_fields sql", "crf_sync_fields", {"path": OUT + "/b_sq1.crf", "dataset": "1", "mode": "sql", "set_types": "true", "output": OUT + "/b_sync3.crf"},
         contains("SQL 파싱", "쿼리 컬럼 6개", "TOTAL_AMT:Currency", "추가: [DEPT_CD"))
    case("sync_fields bad mode", "crf_sync_fields", {"path": B, "mode": "xx", "output": OUT + "/b_syncx.crf"}, error)
    case("add_dataset", "crf_add_dataset", {"path": B, "name": "NEW_DS", "sql": "SELECT A.EMP_NM, A.DEPT_CD FROM TB_EMP A WHERE A.YY = :yy", "output": OUT + "/b_ds.crf"},
         contains("DS[12]", "연결=JDBC1", "필드 2개: EMP_NM, DEPT_CD", "매개변수 선언: [YY]"))
    case("add_dataset duplicate -> ERROR", "crf_add_dataset", {"path": OUT + "/b_ds.crf", "name": "new_ds", "sql": "SELECT 1 A FROM DUAL", "output": OUT + "/b_ds2.crf"}, err_contains("중복"))
    case("add_dataset verify query", "crf_get_query", {"path": OUT + "/b_ds.crf", "dataset": "NEW_DS"}, contains("'{parameter.YY}'", "TB_EMP"))
    case("remove_dataset unreferenced", "crf_remove_dataset", {"path": OUT + "/b_ds.crf", "dataset": "NEW_DS", "output": OUT + "/b_ds3.crf"}, contains("남은 데이터셋 12"))
    case("remove_dataset referenced -> ERROR", "crf_remove_dataset", {"path": B, "dataset": "SQLDS1", "output": OUT + "/b_ds4.crf"}, err_contains("참조", "force"))
if os.path.isfile(A) and os.environ.get("CLIP_SMOKE_DB", "1") == "1":
    case("sync_fields db (NotScript, dataset refs bound)", "crf_sync_fields", {"path": A, "dataset": "SQLDS3", "mode": "db", "params": "{\"RECRUYY\":\"2024\"}", "output": OUT + "/a_sync.crf"},
         lambda t, e: None if (not e and "DB ResultSetMetaData" in t and "쿼리 컬럼 3개" in t) or (e and "DB 미설정" in t) else f"db: {t[:300]}")
    case("sync_fields db on JS query (11k chars)", "crf_sync_fields", {"path": B, "dataset": "0", "mode": "db", "output": OUT + "/b_sync2.crf"},
         lambda t, e: None if (not e and "쿼리 컬럼 41개" in t) or (e and "DB 미설정" in t) else f"db js: {t[:300]}")
    case("sync_fields db bad column -> ERROR with SQL", "crf_sync_fields", {"path": OUT + "/b_sq2.crf", "dataset": "SQLDS3", "mode": "db", "output": OUT + "/b_syncbad.crf"},
         err_contains("DB 실행 실패", "WHERE 1=0"))

# ---- v0.6.0 레이아웃 편집 / validate / diff ----
if os.path.isfile(A):
    case("validate A finds real defects", "crf_validate", {"path": A},
         contains("ERROR 3", "그룹 필드 없음(null)", "공식 COND_1: 없는 필드 참조 [parameter.TODAY]", "바인딩 비어 있음: 그룹.GroupingField"))
    case("set_cell v2 formula+style", "crf_set_cell",
         {"path": A, "table": "표3", "row": "0", "col": "0", "formula": "return rexpert.field(\"data.ADR\")+\"!\";", "align": "Right", "fontsize": "12", "bold": "true", "wrap": "true", "output": OUT + "/a_c2.crf"},
         contains("formula=F_표3_0_0", "align=Right", "크기=12", "굵게=true", "verified[field=F_표3_0_0]"))
    case("describe detail shows cell style", "crf_describe_layout", {"path": OUT + "/a_c2.crf", "detail": "true"},
         contains("공식:F_표3_0_0 «정렬=Right/Center, 폰트=나눔고딕, 크기=12, 굵게, 줄바꿈»"))
    case("set_cell formula without return -> ERROR", "crf_set_cell",
         {"path": A, "table": "표3", "row": "0", "col": "0", "formula": "1+1", "output": OUT + "/a_c3.crf"}, err_contains("return"))
    case("set_cell clear", "crf_set_cell", {"path": A, "table": "표3", "row": "0", "col": "0", "clear": "true", "output": OUT + "/a_c4.crf"}, contains("cleared", 'verified[text=""]'))
    case("set_label text+style+size", "crf_set_label",
         {"path": A, "name": "글상자2", "text": "임용지원서(수정)", "align": "Center", "fontsize": "20", "bold": "true", "width": "1700", "output": OUT + "/a_l.crf"},
         contains('"글상자2" (Detail)', "align=Middle", "width=1700", 'verified[text="임용지원서(수정)"]'))
    case("set_label missing -> ERROR", "crf_set_label", {"path": A, "name": "없음", "text": "x", "output": OUT + "/a_l2.crf"}, error)
    case("remove_control", "crf_remove_control", {"path": A, "name": "글상자1", "output": OUT + "/a_rc.crf"}, contains("삭제 (Detail/PAGE1)"))
    case("remove_control verify", "crf_describe_layout", {"path": OUT + "/a_rc.crf"}, lambda t, e: None if not e and '"글상자1"' not in t else "still present")
    case("set_subsection", "crf_set_subsection", {"path": A, "section": "본문", "index": "1", "visible": "true", "height": "800", "new_page": "After", "output": OUT + "/a_ss.crf"},
         contains('Detail sub[1] "본문2" h=800', "visible=true", "new_page=After"))
    case("set_subsection bad new_page -> ERROR", "crf_set_subsection", {"path": A, "section": "본문", "new_page": "Sideways", "output": OUT + "/a_ss2.crf"}, error)
    case("remove_section empty band", "crf_remove_section", {"path": A, "section": "페이지머리글", "output": OUT + "/a_rs.crf"}, contains("PageHeader 밴드 삭제"))
    case("remove_section with controls -> ERROR", "crf_remove_section", {"path": A, "section": "페이지바닥글", "output": OUT + "/a_rs2.crf"}, err_contains("컨트롤 3개", "force"))
    case("remove_section detail -> ERROR", "crf_remove_section", {"path": A, "section": "본문", "output": OUT + "/a_rs3.crf"}, error)
    case("add_table data+title", "crf_add_table",
         {"path": A, "columns": '[{"field":"APPCA_NM","title":"성명","width":500},{"field":"BIRDT","title":"생년월일","width":400,"align":"Center"},{"field":"APPCA_SEQNO","title":"접수번호","width":300,"format":"#,##0","align":"Right"}]', "top": "1200", "output": OUT + "/a_tbl.crf"},
         contains("표 '표_new' (3열, 너비 1200)", "제목 표 '표_new_title'", "verified[cell(0,0) field=APPCA_NM]"))
    case("add_table verify grid", "crf_describe_layout", {"path": OUT + "/a_tbl.crf"},
         contains('ControlTable "표_new_title"  위치(왼0,위0', '"성명" | "생년월일" | "접수번호"', "데이터:APPCA_NM | 데이터:BIRDT | 데이터:APPCA_SEQNO{#,##0}"))
    case("add_table bad field -> ERROR", "crf_add_table", {"path": A, "columns": '[{"field":"NOPE"}]', "output": OUT + "/a_tbl2.crf"}, err_contains("NOPE"))
    case("diff cell change", "crf_diff", {"a": A, "b": OUT + "/a_c2.crf"}, contains("공식: +1 [F_표3_0_0]", "표 표3 셀: ~1", '[0,0]: "주 소 (연락처)" → 공식:F_표3_0_0'))
if os.path.isfile(B):
    case("validate B", "crf_validate", {"path": B}, contains("ERROR 1 / WARN 1", "끊어진 참조 #unknown#", "중복 이름 CURI_YEAR"))
    case("validate after force remove (dangling)", "crf_validate", {"path": OUT + "/b_rm2.crf"}, contains('바인딩 비어 있음: GroupHeader/그룹 머리글1/Table"표2"[3,6].ApplyValueField', "필드로 없는 SELECT 컬럼 [NM]"))
    case("add_group outer+label+subtotal", "crf_add_group",
         {"path": B, "column": "DEPT_CD", "level": "outer", "label": "true", "subtotal": "TOT_CDT_PASS,CDT_NUM_TOT", "output": OUT + "/b_g1.crf"},
         contains("level 0 of 2", "+머리글 라벨(DEPT_CD)", "+소계 SUM_TOT_CDT_PASS_BY_DEPT_CD", "sections: GroupHeader,GroupHeader,Detail,GroupFooter,GroupFooter,PageFooter"))
    case("add_group level 1 nests correctly", "crf_add_group", {"path": OUT + "/b_g1.crf", "column": "MAJOR_CD", "level": "1", "output": OUT + "/b_g2.crf"}, contains("level 1 of 3"))
    case("add_group verify order", "crf_summary", {"path": OUT + "/b_g2.crf"},
         contains("groups=3 [DEPT_CD,MAJOR_CD,STUDENT_CD]", "[0] GroupHeader(→DEPT_CD)", "[1] GroupHeader(→MAJOR_CD)", "[2] GroupHeader(→STUDENT_CD)", '[5] GroupFooter: "그룹 바닥글[MAJOR_CD]"', '[6] GroupFooter: "그룹 바닥글[DEPT_CD]"'))
    case("add_group subtotal formula", "crf_get_formula", {"path": OUT + "/b_g1.crf", "name": "SUM_TOT_CDT_PASS_BY_DEPT_CD"}, contains('rexpert.sum(0,"data.TOT_CDT_PASS",0,"data.DEPT_CD","")'))
    case("add_group duplicate -> ERROR", "crf_add_group", {"path": B, "column": "STUDENT_CD", "output": OUT + "/b_gx.crf"}, err_contains("이미 그룹"))
    case("remove_group empty bands", "crf_remove_group", {"path": OUT + "/b_g2.crf", "group": "MAJOR_CD", "output": OUT + "/b_g3.crf"}, contains("groups now 2", "sections: GroupHeader,GroupHeader,Detail,GroupFooter,GroupFooter,PageFooter"))
    case("remove_group with controls -> ERROR", "crf_remove_group", {"path": B, "group": "STUDENT_CD", "output": OUT + "/b_g4.crf"}, err_contains("컨트롤 1개", "force"))
    case("remove_group force", "crf_remove_group", {"path": B, "group": "0", "force": "true", "output": OUT + "/b_g5.crf"}, contains("groups now 0", "sections: Detail,PageFooter"))
    case("set_group sort", "crf_set_group", {"path": B, "group": "STUDENT_CD", "sort": "Descending", "output": OUT + "/b_sg.crf"}, contains("sort=Descending"))
    case("add_table on subreport-only detail -> ERROR", "crf_add_table", {"path": B, "columns": '[{"field":"NM","width":400}]', "output": OUT + "/b_tbl.crf"}, err_contains("서브섹션"))
    case("add_table into group header, no title", "crf_add_table", {"path": B, "columns": '[{"field":"NM","width":400}]', "section": "그룹머리글", "header_section": "none", "top": "460", "output": OUT + "/b_tbl2.crf"}, contains("GroupHeader 에 생성", "verified[cell(0,0) field=NM]"))
    case("diff group add", "crf_diff", {"a": B, "b": OUT + "/b_g1.crf"}, contains("그룹: +1 [DEPT_CD]", "컨트롤: +3 [grp_DEPT_CD, sub_CDT_NUM_TOT, sub_TOT_CDT_PASS]", "섹션: GroupHeader,Detail"))
    case("diff query change", "crf_diff", {"a": B, "b": OUT + "/b_sq.crf"}, contains("SQLDS2 필드: +6", "SQLDS2 쿼리 변경", "매개변수(타입=기본값): +1 [SALYYM]"))
    case("diff identical", "crf_diff", {"a": B, "b": B}, contains("차이 없음"))

# ---- v0.7.0 체크박스 / 병합 / 스타일 ----
case("set_cell_checkbox missing field -> ERROR", "crf_set_cell_checkbox",
     {"path": A if os.path.isfile(A) else C, "table": "표3" if os.path.isfile(A) else "표_동의", "row": "0", "col": "0", "output": OUT + "/chk_nofield.crf"}, err_contains("field"))
if os.path.isfile(C):
    case("set_cell_checkbox -> OK (Rectangle, cond)", "crf_set_cell_checkbox",
         {"path": C, "table": "표_동의", "row": "1", "col": "2", "field": "INDIN_PROVD_AGREE_YN", "true_value": "1", "false_value": "0", "output": OUT + "/c_chk.crf"},
         contains("OK", "체크모양=Rectangle", "참조건=INDIN_PROVD_AGREE_YN Equal '1'", "verified[content=Checkbox"))
    case("describe shows checkbox cell", "crf_describe_layout", {"path": OUT + "/c_chk.crf"}, contains("☐체크박스(Rectangle)[INDIN_PROVD_AGREE_YN Equal '1']"))
    case("set_cell_checkbox V + Between", "crf_set_cell_checkbox",
         {"path": C, "table": "표_동의", "row": "1", "col": "2", "field": "INDIN_PROVD_AGREE_YN", "operator": "Between", "true_value": "1", "true_value2": "9", "check_type": "V", "output": OUT + "/c_chk2.crf"}, contains("OK", "체크모양=V", "Between '1'~'9'"))
    case("set_cell_checkbox bad operator -> ERROR", "crf_set_cell_checkbox",
         {"path": C, "table": "표_동의", "row": "1", "col": "2", "field": "INDIN_PROVD_AGREE_YN", "operator": "Like", "output": OUT + "/c_chk3.crf"}, err_contains("operator"))
    case("set_cell_checkbox off -> text cell", "crf_set_cell_checkbox",
         {"path": OUT + "/c_chk.crf", "table": "표_동의", "row": "1", "col": "2", "off": "true", "output": OUT + "/c_chk_off.crf"}, contains("OK", "텍스트로 되돌림"))
    case("merge_cells 1x3 -> OK", "crf_merge_cells",
         {"path": C, "table": "표_신고자", "row": "0", "col": "1", "colspan": "3", "output": OUT + "/c_merge.crf"}, contains("OK", "1×3 병합(덮인 셀 2)", "verified[span=1×3]"))
    case("merge_cells describe shows ‹병합›", "crf_describe_layout", {"path": OUT + "/c_merge.crf"}, contains("데이터:EMP_NM | ‹병합› | ‹병합›"))
    case("merge_cells on covered cell -> ERROR", "crf_merge_cells",
         {"path": OUT + "/c_merge.crf", "table": "표_신고자", "row": "0", "col": "2", "colspan": "2", "output": OUT + "/c_merge_bad.crf"}, err_contains("병합된 자리"))
    case("merge_cells unmerge 1x1 -> OK", "crf_merge_cells",
         {"path": OUT + "/c_merge.crf", "table": "표_신고자", "row": "0", "col": "1", "output": OUT + "/c_unmerge.crf"}, contains("OK", "병합 해제(복구 2셀)", "verified[span=1×1]"))
    case("merge_cells out of range -> ERROR", "crf_merge_cells",
         {"path": C, "table": "표_신고자", "row": "1", "col": "3", "rowspan": "2", "output": OUT + "/c_merge_oor.crf"}, err_contains("벗어남"))
    case("set_cell color/underline/linespace/padding", "crf_set_cell",
         {"path": C, "table": "표_신고자", "row": "0", "col": "1", "color": "#0000FF", "underline": "true", "linespace": "5.5", "padding": "20,0,0,0", "output": OUT + "/c_style.crf"},
         contains("OK", "글자색=#0000FF", "밑줄=true", "줄간격=5.5pt", "여백=20,0,0,0"))
    case("set_cell bad padding -> ERROR", "crf_set_cell",
         {"path": C, "table": "표_신고자", "row": "0", "col": "1", "padding": "1,2", "output": OUT + "/c_style_bad.crf"}, err_contains("padding"))
    case("add_label styled + border", "crf_add_label",
         {"path": C, "section": "본문", "text": "테두리 글상자", "left": "0", "top": "2140", "width": "600", "height": "80", "fontsize": "12", "bold": "true", "color": "#FF0000", "align": "Center", "border": "true", "linewidth": "W150", "output": OUT + "/c_label.crf"},
         contains("OK", "글자색=#FF0000", "테두리=true", "선굵기=W150"))
    case("set_label border off + bad linewidth -> ERROR", "crf_set_label",
         {"path": OUT + "/c_label.crf", "name": "label_text", "linewidth": "W999", "output": OUT + "/c_label_bad.crf"}, err_contains("linewidth"))
    case("add_table (no diagonal) then validate", "crf_add_table",
         {"path": C, "columns": '[{"field":"EMP_NM","title":"성명","width":300},{"field":"DEPT_NM","title":"부서","width":500}]', "header_section": "none", "top": "2230", "name": "표_테스트", "output": OUT + "/c_table.crf"}, contains("OK", "표 '표_테스트'"))
    case("validate after edits", "crf_validate", {"path": OUT + "/c_table.crf"}, contains("ERROR 0", "문제 없음"))

# ---- v0.8.0 표 CRUD (행/열 삽입·삭제·복제·이동·크기·균등, 표 속성, 전체 병합 해제, 셀 테두리) ----
if os.path.isfile(C):
    case("table_info grid", "crf_table_info", {"path": C, "table": "표_신고자"}, contains("2행×4열", "열 너비: [0]230 [1]473 [2]281 [3]476", "행 높이: [0]94 [1]94", "격자 정상"))
    case("table_info detail borders", "crf_table_info", {"path": C, "table": "표_신고자", "detail": "true"}, contains("테두리 전체", "폰트=바탕체"))
    case("merge 2x2 for structure tests", "crf_merge_cells",
         {"path": C, "table": "표_신고자", "row": "0", "col": "1", "rowspan": "2", "colspan": "2", "output": OUT + "/t_m.crf"}, contains("OK", "2×2 병합"))
    case("rows insert inside vertical merge -> span grows 3x2 + shift below", "crf_table_rows",
         {"path": OUT + "/t_m.crf", "table": "표_신고자", "action": "insert", "at": "1", "height": "70", "output": OUT + "/t_r1.crf"},
         contains("OK", "행 삽입 [1..1]", "3행×4열", "높이 188→258", "아래 요소 9개 +70 이동", "감싸는 요소 1개 높이 +70(글상자1)", "verified[grid ok]"))
    case("table_info after insert shows 3x2", "crf_table_info", {"path": OUT + "/t_r1.crf", "table": "표_신고자"}, contains("EMP_NM(3×2)", "행 높이: [0]94 [1]70 [2]94", "병합 자리 5개, 격자 정상"))
    case("rows delete merge anchor -> promoted 2x2", "crf_table_rows",
         {"path": OUT + "/t_r1.crf", "table": "표_신고자", "action": "delete", "row": "0", "output": OUT + "/t_r2.crf"}, contains("OK", "행 [0] 삭제", "2행×4열", "높이 258→164", "-94 이동"))
    case("table_info after delete", "crf_table_info", {"path": OUT + "/t_r2.crf", "table": "표_신고자"}, contains("EMP_NM(2×2)", "‹병합←0,1›", "격자 정상"))
    case("cols insert inside horizontal merge -> 2x3", "crf_table_cols",
         {"path": OUT + "/t_r2.crf", "table": "표_신고자", "action": "insert", "at": "2", "width": "250", "output": OUT + "/t_c1.crf"}, contains("OK", "2행×5열", "너비 1460→1710"))
    case("table_info after col insert", "crf_table_info", {"path": OUT + "/t_c1.crf", "table": "표_신고자"}, contains("EMP_NM(2×3)", "열 너비: [0]230 [1]473 [2]250 [3]281 [4]476"))
    case("cols delete merge anchor -> promoted 2x2", "crf_table_cols",
         {"path": OUT + "/t_c1.crf", "table": "표_신고자", "action": "delete", "col": "1", "output": OUT + "/t_c2.crf"}, contains("OK", "열 [1] 삭제", "2행×4열", "너비 1710→1237"))
    case("validate after structure edits", "crf_validate", {"path": OUT + "/t_c2.crf"}, contains("ERROR 0", "문제 없음"))
    case("rows copy keeps content+style", "crf_table_rows",
         {"path": C, "table": "표_신고자", "action": "copy", "row": "1", "output": OUT + "/t_cp.crf"}, contains("OK", "행 1 복제 → [2..2]", "3행×4열"))
    case("table_info copy row content", "crf_table_info", {"path": OUT + "/t_cp.crf", "table": "표_신고자", "detail": "true"},
         contains('[2] "직위/직급" «정렬=Middle/Center, 폰트=바탕체, 크기=10, 굵게, 줄바꿈, 배경=#D9D9D9, 테두리 전체» | 공식:JPOS_JGRD'))
    case("rows move 2->0", "crf_table_rows", {"path": OUT + "/t_cp.crf", "table": "표_신고자", "action": "move", "row": "2", "to": "0", "output": OUT + "/t_mv.crf"}, contains("OK", "행 2 → 0 이동"))
    case("table_info after move", "crf_table_info", {"path": OUT + "/t_mv.crf", "table": "표_신고자"}, contains('[0] "직위/직급" | 공식:JPOS_JGRD', '[1] "성명" | 데이터:EMP_NM'))
    case("rows resize index:value", "crf_table_rows", {"path": OUT + "/t_mv.crf", "table": "표_신고자", "action": "resize", "heights": "1:120", "output": OUT + "/t_rs.crf"}, contains("OK", "{1=120}", "높이 282→308"))
    case("rows equalize keep total", "crf_table_rows", {"path": OUT + "/t_rs.crf", "table": "표_신고자", "action": "equalize", "output": OUT + "/t_eq.crf"}, contains("OK", "총 308 유지, 각 102/마지막 104"))
    case("cols equalize width=300", "crf_table_cols", {"path": OUT + "/t_eq.crf", "table": "표_신고자", "action": "equalize", "width": "300", "output": OUT + "/t_eqc.crf"}, contains("OK", "너비 균등 300 씩", "너비 1460→1200"))
    case("cols resize col list + width", "crf_table_cols", {"path": OUT + "/t_eqc.crf", "table": "표_신고자", "action": "resize", "col": "0,2", "width": "200", "output": OUT + "/t_rsc.crf"}, contains("OK", "{0=200, 2=200}", "너비 1200→1000"))
    case("cols move 3->1", "crf_table_cols", {"path": OUT + "/t_rsc.crf", "table": "표_신고자", "action": "move", "col": "3", "to": "1", "output": OUT + "/t_mvc.crf"}, contains("OK", "열 3 → 1 이동"))
    case("cols copy before", "crf_table_cols", {"path": OUT + "/t_mvc.crf", "table": "표_신고자", "action": "copy", "col": "0", "position": "before", "output": OUT + "/t_cpc.crf"}, contains("OK", "열 0 복제 → [0..0]", "3행×5열"))
    case("set_table width scale + borders + rename", "crf_set_table",
         {"path": OUT + "/t_cpc.crf", "table": "표_신고자", "width": "1460", "left": "40", "cell_border": "true", "linewidth": "W100", "border": "true", "keep_together": "Row", "name": "표_신고자2", "output": OUT + "/t_st.crf"},
         contains("OK", "width=1460(열 비례 [243, 243, 365, 365, 244])", "외곽선=true", "셀테두리=true(15셀)", "페이지나눔방지=Row", "name=표_신고자2", "위치 40,260"))
    case("set_cell partial border + color", "crf_set_cell",
         {"path": OUT + "/t_st.crf", "table": "표_신고자2", "row": "0", "col": "0", "border": "left,bottom", "linecolor": "#FF0000", "output": OUT + "/t_cb.crf"}, contains("OK", "테두리=left,bottom", "선색=#FF0000"))
    case("table_info shows partial border", "crf_table_info", {"path": OUT + "/t_cb.crf", "table": "표_신고자2", "detail": "true"}, contains("테두리 좌하»"))
    case("set_table unmerge_all", "crf_set_table", {"path": OUT + "/t_m.crf", "table": "표_신고자", "unmerge_all": "true", "output": OUT + "/t_um.crf"}, contains("OK", "전체 병합 해제(3셀 복구)"))
    case("table_info after unmerge_all", "crf_table_info", {"path": OUT + "/t_um.crf", "table": "표_신고자"}, contains("병합 없음, 격자 정상"))
    case("rows move across vertical merge -> ERROR", "crf_table_rows", {"path": OUT + "/t_m.crf", "table": "표_신고자", "action": "move", "row": "0", "to": "1", "output": OUT + "/t_e1.crf"}, err_contains("세로 병합 셀"))
    case("rows delete all -> ERROR", "crf_table_rows", {"path": C, "table": "표_신고자", "action": "delete", "row": "0-1", "output": OUT + "/t_e2.crf"}, err_contains("모든 행"))
    case("cols resize short list -> ERROR", "crf_table_cols", {"path": C, "table": "표_신고자", "action": "resize", "widths": "1,2", "output": OUT + "/t_e3.crf"}, err_contains("2개 ≠ 4개"))
    case("rows insert at out of range -> ERROR", "crf_table_rows", {"path": C, "table": "표_신고자", "action": "insert", "at": "9", "output": OUT + "/t_e5.crf"}, err_contains("범위 밖"))
    case("rows bad action -> ERROR", "crf_table_rows", {"path": C, "table": "표_신고자", "action": "explode", "output": OUT + "/t_e6.crf"}, err_contains("insert|delete|copy|move|resize|equalize"))

# ---- v0.7.1: 글상자↔표 붙이기/나누기, 글꼴 상속/일괄, lint ----
if os.path.isfile(D):
    case("validate D: stacked labels WARN", "crf_validate", {"path": D},
         contains("글상자3,글상자4,글상자5", "글상자7,글상자8", "crf_merge_labels"))
    case("merge_labels 3,4,5 -> 1-col table", "crf_merge_labels",
         {"path": D, "names": "글상자5,글상자3,글상자4", "output": OUT + "/d_m1.crf"}, contains("OK", "→ 1열 표", "3행", "위치 0,361", "verified[rows=3"))
    case("merge_labels 7,8 -> one wrapped label", "crf_merge_labels",
         {"path": OUT + "/d_m1.crf", "names": "글상자7,글상자8", "into": "label", "output": OUT + "/d_m2.crf"}, contains("OK", "줄바꿈 글상자 하나", "2줄"))
    case("validate after merge: no stacked WARN", "crf_validate", {"path": OUT + "/d_m2.crf"},
         lambda t, e: None if not e and "세로 연속" not in t and "WARN 0" in t else f"still warns: {t[:300]}")
    case("describe merged table keeps style", "crf_describe_layout", {"path": OUT + "/d_m2.crf", "detail": "true"},
         contains('ControlTable "표_글상자3"', '"1. 업무 수행', "정렬=Both/Center, 폰트=바탕체, 크기=10, 줄바꿈"))
    case("merge_labels one name -> ERROR", "crf_merge_labels", {"path": D, "names": "글상자3", "output": OUT + "/d_m_bad.crf"}, err_contains("2개 이상"))
    case("merge_labels bound label into=label -> ERROR", "crf_merge_labels",
         {"path": D, "names": "글상자9,글상자2", "into": "label", "output": OUT + "/d_m_bad2.crf"}, err_contains("필드 바인딩"))
    case("split_label table -> labels", "crf_split_label", {"path": OUT + "/d_m2.crf", "name": "표_글상자3", "output": OUT + "/d_s1.crf"},
         contains("OK", "글상자 3개", "표_글상자3_1(57)", "표_글상자3_3(112)"))
    case("split_label wrapped label -> 2 labels", "crf_split_label", {"path": OUT + "/d_s1.crf", "name": "글상자7", "output": OUT + "/d_s2.crf"},
         contains("OK", "2개", "글상자7_2("))
    case("split_label no line break -> ERROR", "crf_split_label", {"path": D, "name": "글상자6", "output": OUT + "/d_s_bad.crf"}, err_contains("나눌 줄"))
    case("split_label multi-col table -> ERROR", "crf_split_label", {"path": D, "name": "표_서명", "output": OUT + "/d_s_bad2.crf"}, err_contains("1열 표만"))
    case("add_table rows= text list", "crf_add_table",
         {"path": D, "rows": '["1. 첫째","2. 둘째",{"text":"※ 셋째","height":112,"align":"Both"}]', "top": "2100", "name": "표_목록", "output": OUT + "/d_t1.crf"},
         contains("OK", "문단 목록 표 '표_목록'", "3행×1열", "폰트 바탕체", "verified[rows=3]"))
    case("add_table neither columns nor rows -> ERROR", "crf_add_table", {"path": D, "output": OUT + "/d_t_bad.crf"}, err_contains("columns", "rows"))
    case("add_label inherits report font (label)", "crf_add_label",
         {"path": D, "section": "본문", "text": "새 글상자", "top": "2300", "width": "600", "height": "56", "output": OUT + "/d_l1.crf"}, contains("OK", "폰트=바탕체", "상속:리포트 라벨 글꼴"))
    case("add_label inherits report font (data) keeps given size", "crf_add_label",
         {"path": D, "section": "본문", "field": "EMP_NM", "fontsize": "12", "top": "2400", "width": "600", "height": "56", "output": OUT + "/d_l2.crf"},
         lambda t, e: None if not e and "폰트=바탕체" in t and "상속:리포트 데이터 글꼴" in t and "크기=10" not in t else f"font inherit: {t[:300]}")
    case("set_font label/data split", "crf_set_font", {"path": D, "font": "돋움체", "data_font": "나눔고딕", "output": OUT + "/d_f1.crf"},
         contains("OK", "라벨 23곳→돋움체", "데이터 7곳→나눔고딕", "후: 라벨 {돋움체=23} 데이터 {나눔고딕=7}"))
    case("validate font mismatch INFO", "crf_validate", {"path": OUT + "/d_f1.crf"}, contains("라벨(글자) 글꼴 돋움체", "데이터(숫자) 글꼴 나눔고딕", "글꼴 2종 섞여 있음"))
    case("set_font only_system with none -> ERROR", "crf_set_font", {"path": D, "font": "돋움체", "only_system": "true", "output": OUT + "/d_f2.crf"}, err_contains("System 글꼴 요소가 없음"))
    case("set_font nothing given -> ERROR", "crf_set_font", {"path": D, "output": OUT + "/d_f3.crf"}, err_contains("font / data_font / size"))
    case("set_cell align Both", "crf_set_cell", {"path": D, "table": "표_서명", "row": "0", "col": "1", "align": "Both", "output": OUT + "/d_al.crf"}, contains("OK", "align=Both"))
if os.path.isfile(A):
    case("set_subsection repeat=OnPage", "crf_set_subsection", {"path": A, "section": "그룹머리글", "repeat": "OnPage", "output": OUT + "/a_rep.crf"}, contains("OK", "repeat=OnPage"))
    case("set_subsection bad repeat -> ERROR", "crf_set_subsection", {"path": A, "section": "그룹머리글", "repeat": "Always", "output": OUT + "/a_rep_bad.crf"}, err_contains("repeat"))

# ---- v0.7.3: XML 데이터셋 XPath 읽기/검색 ----
X = os.environ.get("CLIP_SMOKE_X", "C:/eGovFrameDev-4.3.1/workspace/report/meta/exm/exmn/exmnal/naplm0420_prn.crf")  # SQL 2 + XML 2 데이터셋
if os.path.isfile(X):
    case("get_query shows XPath for XML dataset", "crf_get_query", {"path": X, "dataset": "XMLDS1"}, contains("접근=XML", "경로(XPath)", "루트 = "))
    case("search scope=xpath hits XML root path", "crf_search", {"dir": os.path.dirname(X), "text": "/", "scope": "xpath", "like": "naplm0420_prn"}, contains("[xpath XMLDS"))
    case("search scope=query also covers xpath", "crf_search", {"dir": os.path.dirname(X), "text": "/", "scope": "query", "like": "naplm0420_prn"}, contains("[xpath XMLDS"))
    case("search bad scope -> ERROR", "crf_search", {"dir": os.path.dirname(X), "text": "x", "scope": "nope"}, error)

# ---- DB 가드 (DB 미연결이어도 가드가 먼저) ----
case("db_query DML refused", "db_query", {"sql": "DELETE FROM X"}, err_contains("SELECT"))
case("db_query DDL refused", "db_query", {"sql": " /*c*/ drop table x"}, error)


def main():
    calls = [(t, a) for _, t, a, _ in CASES]
    res, stderr = mcpcall.call(calls)
    print("server:", res[0][1])
    fails = 0
    for (name, tool, args, chk), (_, text, is_err) in zip(CASES, res[1:]):
        msg = chk(text, is_err)
        status = "PASS" if msg is None else "FAIL"
        if msg is not None: fails += 1
        print(f"[{status}] {name}" + (f"\n        {msg}" if msg else ""))
        if VERBOSE: print("        " + text.replace("\n", "\n        ")[:600])
    print(f"\n{len(CASES) - fails}/{len(CASES)} passed")
    if VERBOSE and stderr.strip(): print("--- stderr ---\n" + stderr[-2000:])
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    try: sys.stdout.reconfigure(encoding="utf-8")
    except Exception: pass
    main()
