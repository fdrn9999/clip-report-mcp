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
         {"template": B, "sql": open(os.path.join(PROJ, "samples", "sample_query.sql"), encoding="utf-8").read(), "legacy": "true", "output": OUT + "/gen.crf"},
         contains("written", "fields(6)"))
    case("generate v2 on a subreport-style template -> clear ERROR", "crf_generate",
         {"template": B, "sql": open(os.path.join(PROJ, "samples", "sample_query.sql"), encoding="utf-8").read(), "output": OUT + "/gen_v2.crf"},
         lambda t, e: None if (not e and "OK" in t) or (e and ("본문 밴드" in t or "template" in t)) else f"gen v2 on B: {t[:300]}")
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

# ---- v0.9.0: ui-f6 피드백 — 필드 위치 매핑/재정렬, format 비우기, 셀 일괄, in_place, MyBatis 변환, 삭제 도구, describe 압축, 용지 fit ----
E = os.environ.get("CLIP_SMOKE_E", "C:/eGovFrameDev-4.3.1/workspace/report/meta/adm/ahrm/ahrmhr/ahrmhr0240_prn01.crf")  # 목록형(가로), 표3 15열, 매개변수 EMPNO 와 데이터필드 EMPNO 동명
if os.path.isfile(E):
    JSQ = 'var sql="";\r\nsql += "SELECT T1.DEPT_NM, T1.EMPNO, T1.EMP_NM, TO_CHAR(SYSDATE,\'YYYY\') , T1.BIRDT FROM ADM.AHRM100 T1 WHERE T1.EMPNO=\'{parameter.EMPNO}\'";\r\n'
    case("set_query reorders fields to SELECT order + COL_n placeholder + no-return warn", "crf_set_query", {"path": E, "sql": JSQ, "output": OUT + "/e_q1.crf"},
         contains("OK", "↕ 필드 순서를 SELECT 순서로 재정렬", "자리 필드 [COL_4]", "return 문이 없습니다", "필드(18): DEPT_NM, EMPNO, EMP_NM, COL_4, BIRDT, INSTT_DIV_NM"))
    case("set_query reorder=false keeps order", "crf_set_query", {"path": E, "sql": JSQ, "reorder": "false", "output": OUT + "/e_q2.crf"},
         lambda t, e: None if not e and "↕" not in t and "필드(18): INSTT_DIV_NM, UNIV_NM, EMPNO" in t else f"reorder=false: {t[:300]}")
    case("validate flags JS query without return", "crf_validate", {"path": OUT + "/e_q1.crf"}, contains("✖ 데이터셋 SQLDS1: JavaScript 쿼리에 return 문이 없음"))
    case("reorder_fields explicit order", "crf_reorder_fields", {"path": OUT + "/e_q1.crf", "order": "EMPNO,EMP_NM", "output": OUT + "/e_r1.crf"},
         contains("OK: 필드 순서 재정렬(지정 순서)", "후: EMPNO, EMP_NM, DEPT_NM, COL_4"))
    case("reorder_fields order=query", "crf_reorder_fields", {"path": OUT + "/e_r1.crf", "output": OUT + "/e_r2.crf"},
         contains("OK: 필드 순서 재정렬(쿼리 SELECT 순서)", "후: DEPT_NM, EMPNO, EMP_NM, COL_4, BIRDT", "SELECT 에 없는 필드는 끝으로"))
    case("reorder_fields unknown field -> ERROR", "crf_reorder_fields", {"path": E, "order": "NOPE", "output": OUT + "/e_r_bad.crf"}, err_contains("필드 없음: NOPE"))
    case("sync_fields reorders too", "crf_sync_fields", {"path": OUT + "/e_r1.crf", "output": OUT + "/e_s1.crf"}, contains("OK", "↕ 필드 순서를 쿼리 컬럼 순서로 재정렬", "COL_4:Null"))
    case("set_cell format='' removes format", "crf_set_cell", {"path": E, "table": "표3", "row": "0", "col": "4", "format": "", "output": OUT + "/e_c1.crf"}, contains("OK", "format 제거(was yy.mm.dd)", "verified[field=BIRDT]"))
    case("set_cell clear_format idempotent", "crf_set_cell", {"path": OUT + "/e_c1.crf", "table": "표3", "row": "0", "col": "4", "clear_format": "true", "output": OUT + "/e_c2.crf"}, contains("OK", "format=(이미 없음)"))
    case("describe shows no format after clear", "crf_describe_layout", {"path": OUT + "/e_c1.crf"}, lambda t, e: None if not e and "BIRDT{yy.mm.dd}" not in t and "데이터:BIRDT" in t else f"format still there: {t[:300]}")
    case("set_cell cells=[] batch with defaults + text unbinds field", "crf_set_cell",
         {"path": E, "table": "표3", "align": "Right", "cells": [{"row": 0, "col": 2, "field": "EMP_NM"}, {"row": 0, "col": 3, "field": "EMPNO", "format": "#,##0"}, {"row": 0, "col": 5, "text": "고정", "align": "Center"}, {"table": "표2", "row": 0, "col": 1, "text": "소계X"}], "output": OUT + "/e_c3.crf"},
         contains("OK: 4개 셀 설정, verified", "표3[0,2] field=EMP_NM(데이터) align=Right", "format=#,##0", "(바인딩 SEX_NM 해제) text=\"고정\" align=Middle", "표2[0,1]"))
    case("describe after batch", "crf_describe_layout", {"path": OUT + "/e_c3.crf"}, contains('데이터:EMP_NM | 데이터:EMPNO{#,##0} | 데이터:BIRDT{yy.mm.dd} | "고정" |', '"소계X"'))
    case("set_cell cells out of range -> ERROR before save", "crf_set_cell", {"path": E, "table": "표3", "cells": [{"row": 0, "col": 99, "text": "x"}], "output": OUT + "/e_c_bad.crf"}, err_contains("범위 밖"))
    case("set_cell no row/col -> ERROR", "crf_set_cell", {"path": E, "table": "표3", "text": "x", "output": OUT + "/e_c_bad2.crf"}, err_contains("row/col", "cells="))
    case("remove_field with same-named parameter", "crf_remove_field", {"path": E, "name": "EMPNO", "dataset": "SQLDS1", "force": "true", "output": OUT + "/e_rf.crf"},
         contains("OK: 데이터 필드 'EMPNO' 삭제", "같은 이름의 매개변수 필드는 그대로"))
    case("remove_param missing -> ERROR lists params", "crf_remove_param", {"path": E, "name": "DSCPLCD", "output": OUT + "/e_rp_bad.crf"}, err_contains("'DSCPLCD' 없음", "있는 매개변수: G_REPORTLOG", "ignore_missing"))
    case("remove_param ignore_missing -> OK no-op", "crf_remove_param", {"path": E, "name": "DSCPLCD", "ignore_missing": "true", "output": OUT + "/e_rp.crf"}, contains("OK", "변경 없이 저장"))
    case("remove_section English name works", "crf_remove_section", {"path": E, "section": "DataFooter", "force": "true", "output": OUT + "/e_rs1.crf"}, contains("OK: DataFooter 밴드 삭제"))
    case("remove_section missing -> ERROR lists bands", "crf_remove_section", {"path": OUT + "/e_rs1.crf", "section": "DataFooter", "output": OUT + "/e_rs_bad.crf"}, err_contains("not found", "이 리포트의 밴드: PageHeader,GroupHeader,Detail,GroupFooter,PageFooter"))
    case("remove_section ignore_missing (Korean)", "crf_remove_section", {"path": OUT + "/e_rs1.crf", "section": "데이터바닥글", "ignore_missing": "true", "output": OUT + "/e_rs2.crf"}, contains("OK", "변경 없이 저장"))
    # in_place 체인: 복사본에 3단계 연속 쓰기, 첫 쓰기만 .bak
    import shutil
    IP = OUT + "/e_ip.crf"; shutil.copyfile(E, IP)
    if os.path.exists(IP + ".bak"): os.remove(IP + ".bak")
    case("in_place first write creates .bak", "crf_set_cell", {"path": IP, "table": "표3", "row": "0", "col": "2", "text": "IP1", "in_place": "true"}, contains("OK", "(in_place) (원본 백업 → e_ip.crf.bak)"))
    case("in_place second write keeps .bak", "crf_set_cell", {"path": IP, "table": "표3", "row": "0", "col": "3", "text": "IP2", "in_place": "true", "output": IP}, contains("OK", "(백업 e_ip.crf.bak 유지)"))
    case("in_place table op", "crf_table_cols", {"path": IP, "table": "표3", "action": "delete", "cols": "14", "in_place": "true"}, contains("OK", "1행×14열", "(in_place)"))
    case("in_place chain result persisted", "crf_describe_layout", {"path": IP}, contains('"IP1" | "IP2"'))
    case("output==path without in_place -> ERROR", "crf_set_cell", {"path": IP, "table": "표3", "row": "0", "col": "4", "text": "X", "output": IP}, err_contains("output 이 원본과 같습니다", "in_place=true"))
    case(".bak equals original", "crf_diff", {"a": E, "b": IP + ".bak"}, lambda t, e: None if not e and ("차이 없음" in t or "identical" in t.lower() or "0건" in t or "변경 없음" in t) else f"bak differs?: {t[:300]}")
    MB = ('<select id="x"><!-- c -->SELECT T1.EMPNO, T1.EMP_NM FROM ADM.AHRM100 T1 WHERE 1=1 AND T1.APPNM_DT <= TO_DATE(#{stdrDt}, \'YYYYMMDD\') AND T1.CNT &lt; 10'
          '<if test="isValid(jbfmCd)"> AND T1.JBFM_CD = #{jbfmCd}</if><if test="!isValid(deptCd)"> AND T1.DEPT_CD IS NOT NULL</if>'
          '<if test="@org.apache.commons.lang3.StringUtils@isNotEmpty(empNm)"> AND T1.EMP_NM LIKE #{empNm}</if><if test=\'"Y".equals(useYn)\'> AND T1.USE_YN = \'Y\'</if> ORDER BY T1.EMPNO</select>')
    case("set_query MyBatis isValid/equals/entities/<= + return", "crf_set_query", {"path": E, "sql": MB, "output": OUT + "/e_mb.crf"},
         contains("OK", "scriptType JavaScript → JavaScript", "+ 매개변수 선언: [USEYN]"))
    case("get_query converted JS", "crf_get_query", {"path": OUT + "/e_mb.crf"},
         contains("AND T1.APPNM_DT <= TO_DATE('{parameter.STDRDT}', 'YYYYMMDD') AND T1.CNT < 10", "if('{parameter.JBFMCD}' != ''){", "if('{parameter.DEPTCD}' == ''){", "if('{parameter.EMPNM}' != ''){", "if('{parameter.USEYN}' == 'Y'){", "return sql;"))
    case("summary: declared param has no type suffix (Null like designer)", "crf_summary", {"path": OUT + "/e_mb.crf"}, lambda t, e: None if not e and "USEYN" in t and "USEYN:String" not in t else f"param type: {t[:400]}")
    case("describe detail compacts row-common style", "crf_describe_layout", {"path": E, "detail": "true"},
         contains("[0] «공통: 정렬=Middle/Center, 폰트=돋움체, 크기=8, 굵게, 줄바꿈» \"구분\" | \"대학\"", "데이터:INSTT_DIV_NM «정렬=Left/Center", " | 데이터:EMPNO | 데이터:EMP_NM | "))
    case("describe detail label font not duplicated", "crf_describe_layout", {"path": E, "detail": "true"}, lambda t, e: None if not e and "폰트=돋움체 정렬=Middle/Center 폰트=돋움체" not in t else "font printed twice")
    case("describe one_per_line", "crf_describe_layout", {"path": E, "detail": "true", "one_per_line": "true"}, contains("[0,0] 데이터:INSTT_DIV_NM «정렬=Left/Center", "\n            [0,2] 데이터:EMPNO\n"))
    # Codex 리뷰 반영(v0.9.0): 중복 컬럼명 자리 보존, reorder 가드, 배치 중복 거부, format 우선순위, choose/when else-if, 속성 안의 >, return 판정
    case("set_query duplicate column names keep positions (COL_n)", "crf_set_query",
         {"path": E, "sql": 'var sql="";\r\nsql += "SELECT A.ID, B.ID, B.NAME AS EMP_NM FROM T A, T B";\r\nif(1){ return sql; }', "output": OUT + "/e_dup.crf"},
         lambda t, e: None if not e and "자리 필드 [COL_2]" in t and "필드(18): ID, COL_2, EMP_NM" in t and "return 문이 없습니다" not in t else f"dup cols: {t[:400]}")
    case("set_query sync_fields=none leaves a column without field", "crf_set_query",
         {"path": E, "sql": "SELECT T1.EMPNO, T1.NEWCOL, T1.EMP_NM FROM T T1", "sync_fields": "none", "output": OUT + "/e_q3.crf"}, contains("OK"))
    case("reorder_fields order=query refuses when a column has no field", "crf_reorder_fields",
         {"path": OUT + "/e_q3.crf", "output": OUT + "/e_r_guard.crf"}, err_contains("[NEWCOL]", "crf_sync_fields"))
    case("set_cell cells duplicate target -> ERROR", "crf_set_cell", {"path": E, "table": "표3", "cells": [{"row": 0, "col": 2, "text": "a"}, {"row": 0, "col": 2, "text": "b"}], "output": OUT + "/e_c_dup.crf"}, err_contains("두 번"))
    case("set_cell clear_format wins over format (single + batch)", "crf_set_cell",
         {"path": E, "table": "표3", "format": "#,##0", "cells": [{"row": 0, "col": 4, "clear_format": "true"}, {"row": 0, "col": 2}], "output": OUT + "/e_c_prec.crf"},
         contains("OK: 2개 셀 설정, verified", "표3[0,4] format 제거(was yy.mm.dd)", "표3[0,2] format=#,##0"))
    MB2 = ('<select id="x">SELECT T1.EMPNO, T1.EMP_NM FROM T T1 WHERE 1=1 <if test="cnt > 0"> AND T1.CNT > #{cnt}</if>'
           '<choose><when test="isValid(a)"> AND A=#{a}</when><when test="isValid(b)"> AND B=#{b}</when><otherwise> AND C=1</otherwise></choose> ORDER BY 1</select>')
    case("set_query MyBatis choose->if/else if/else, '>' inside test attr", "crf_set_query", {"path": E, "sql": MB2, "output": OUT + "/e_mb2.crf"}, contains("OK", "+ 매개변수 선언: [A, B, CNT]"))
    case("get_query: else-if chain and numeric compare", "crf_get_query", {"path": OUT + "/e_mb2.crf"},
         contains("if('{parameter.CNT}' > 0){", "if('{parameter.A}' != ''){", "else if('{parameter.B}' != ''){", "else {", "AND T1.CNT > '{parameter.CNT}'"))
    # 엑셀 격자 lint (v0.9.0): 페이지 바닥글 제외, 글상자 좌우·표 경계·선 x 를 본문 경계와 대조
    F1 = os.environ.get("CLIP_SMOKE_F1", "C:/eGovFrameDev-4.3.1/workspace/report/meta/adm/ahrm/ahrmhr/ahrmhr0120_prn01.crf")
    if os.path.isfile(F1):
        # ui-f6 가 실파일을 이미 맞춰 놓았으므로(v0.9.0), 어긋난 사본을 만들어 검사
        case("make misaligned copy (글상자2 width 620)", "crf_set_label", {"path": F1, "name": "글상자2", "width": "620", "output": OUT + "/f1_mis.crf"}, contains("OK"))
        F1 = OUT + "/f1_mis.crf"
        case("validate excel grid INFO lists misaligned label edge only", "crf_validate", {"path": F1},
             lambda t, e: None if not e and "ℹ 엑셀 격자: 본문 표 경계 11개 외에 어긋난 세로선 1개([620])" in t and '글상자2": 오른쪽 620(가까운 경계 460/840)' in t and "글상자3" not in t.split("엑셀 격자")[1].split("ℹ")[0] and "글상자4" not in t else f"excel grid: {t[:600]}")
        case("validate excel=true -> WARN", "crf_validate", {"path": F1, "excel": "true"}, contains("⚠ 엑셀 격자"))
        case("align label to body boundary clears the finding", "crf_set_label", {"path": F1, "name": "글상자2", "width": "840", "output": OUT + "/f1_al.crf"}, contains("OK"))
        case("validate after align: no excel grid finding", "crf_validate", {"path": OUT + "/f1_al.crf", "excel": "true"}, lambda t, e: None if not e and "엑셀 격자" not in t else f"still: {t[:300]}")
if os.path.isfile(C):
    case("set_paper Landscape swaps size, reports body width", "crf_set_paper", {"path": C, "orientation": "Landscape", "output": OUT + "/c_p1.crf"},
         contains("OK", "크기 2100x2970→2970x2100", "본문 너비=2370", "fit=true 를 주면"))
    case("set_paper fit=true scales tables/controls", "crf_set_paper", {"path": C, "orientation": "Landscape", "fit": "true", "output": OUT + "/c_p2.crf"},
         contains("OK", "↔ fit: 본문 너비 1500→2370", "표 3개(열 비례)"))
    case("fit result: table width scaled", "crf_table_info", {"path": OUT + "/c_p2.crf", "table": "표_신고자"}, contains("(합 2307)"))
    case("set_paper fit back to Potrait restores width", "crf_set_paper", {"path": OUT + "/c_p2.crf", "orientation": "Potrait", "fit": "true", "output": OUT + "/c_p3.crf"}, contains("OK", "2970x2100→2100x2970", "본문 너비=1500"))
    case("fit round trip exact", "crf_table_info", {"path": OUT + "/c_p3.crf", "table": "표_신고자"}, contains("(합 1460)"))
    case("set_paper bad orientation -> ERROR", "crf_set_paper", {"path": C, "orientation": "Sideways", "output": OUT + "/c_p_bad.crf"}, err_contains("orientation"))

# ---- v0.10.0: 목록형 파생 — set_columns / append_query_condition / generate v2 / MyBatis foreach·trim ----
if os.path.isfile(E):
    NEWSQL = "SELECT T1.EMPNO, T2.EMP_NM, T1.DEPT_NM, T1.JGRD_NM, TO_CHAR(T1.APPNM_DT,'YYYY-MM-DD') AS APPNM_DT, T1.SAL_AMT, T1.REMRK FROM ADM.AHRM100 T1, ADM.AHRM110 T2 WHERE T1.EMPNO=T2.EMPNO AND T1.DEPT_CD='{parameter.DEPTCD}' ORDER BY T1.EMPNO"
    COLS = ["EMPNO", {"field": "EMP_NM", "title": "성명", "width": 300}, {"field": "DEPT_NM", "title": "부서"}, {"field": "JGRD_NM", "title": "직급", "width": 250},
            {"field": "APPNM_DT", "title": "임용일자", "width": 300, "align": "Center"}, {"field": "SAL_AMT", "title": "급여", "width": 350, "format": "#,##0", "total": "sum"}, {"text": "", "title": "비고", "width": 400}]
    case("set_query(replace) for set_columns", "crf_set_query", {"path": E, "sql": NEWSQL, "sync_fields": "replace", "output": OUT + "/v10_a.crf"}, contains("OK", "+ 필드 추가: [DEPT_NM, SAL_AMT]"))
    case("set_columns rebuilds body/title/total/group-footer tables + snap", "crf_set_columns", {"path": OUT + "/v10_a.crf", "columns": COLS, "snap": "true", "output": OUT + "/v10_b.crf"},
         contains("OK: 열 세트 교체 → 7열", "본문 표 '표3' 7열 재구성(너비 [535, 300, 535, 250, 300, 350, 400] 합 2670)", "제목 표 '표1' 제목 7개", "합계 표 '표4' — 합 계 라벨 [0], 집계 열 1개", "그룹 바닥글 표 '표2' — 소 계(UNIV_NM 기준)", "엑셀 격자 맞춤: Label \"글상자2\" 0~620 → 0~535", "표3: 데이터:EMPNO | 데이터:EMP_NM | 데이터:DEPT_NM | 데이터:JGRD_NM | 데이터:APPNM_DT | 데이터:SAL_AMT{#,##0} | ·"))
    case("set_columns result: describe", "crf_describe_layout", {"path": OUT + "/v10_b.crf"}, contains('[0] "EMPNO" | "성명" | "부서" | "직급" | "임용일자" | "급여" | "비고"', '"합 계" | · | · | · | · | 공식:F_TOTAL_SAL_AMT{#,##0} | ·', '"소 계" | · | · | · | · | 공식:F_SUB_SAL_AMT{#,##0} | ·'))
    case("set_columns result: excel grid clean", "crf_validate", {"path": OUT + "/v10_b.crf", "excel": "true"}, lambda t, e: None if not e and "엑셀 격자" not in t and "ERROR 0" in t else f"grid: {t[:400]}")
    case("set_columns unknown field -> ERROR", "crf_set_columns", {"path": OUT + "/v10_a.crf", "columns": ["NOPE"], "output": OUT + "/v10_bad.crf"}, err_contains("필드 'NOPE' 없음"))
    case("set_columns bad total -> ERROR", "crf_set_columns", {"path": OUT + "/v10_a.crf", "columns": [{"field": "EMPNO", "total": "median"}], "output": OUT + "/v10_bad2.crf"}, err_contains("total 은"))
    # 재검토 반영: 병합된 제목 행도 재구성, 합계 함수별 공식 이름, 괄호 안 ORDER BY 무시
    case("merge title cells before set_columns", "crf_merge_cells", {"path": OUT + "/v10_a.crf", "table": "표1", "row": "0", "col": "0", "rowspan": "1", "colspan": "3", "output": OUT + "/v10_m.crf"}, contains("OK"))
    case("set_columns unmerges the title row and keeps the grid valid", "crf_set_columns", {"path": OUT + "/v10_m.crf", "columns": ["EMPNO", {"field": "SAL_AMT", "title": "급여평균", "total": "avg", "format": "#,##0.0"}], "output": OUT + "/v10_m2.crf"},
         contains("OK: 열 세트 교체 → 2열", "제목 표 '표1' 제목 2개", "verified" if False else "OK"))
    case("set_columns avg total → F_TOTAL_AVG_ formula, grid ok", "crf_table_info", {"path": OUT + "/v10_m2.crf", "table": "표1"}, contains("2열", "격자 정상", '"EMPNO" | "급여평균"'))
    case("avg formula name/script", "crf_get_formula", {"path": OUT + "/v10_m2.crf", "name": "F_TOTAL_AVG_SAL_AMT"}, contains('rexpert.avg(0,"data.SAL_AMT",0,"","")'))
    # Codex 재점검 반영: 한 줄 JS(`…; return sql;`), 앞 줄에서 열린 괄호, if/else 체인 뒤 삽입, 평문 SQL 의 &lt; 보존, foreach '#{item}' 따옴표
    case("append_query_condition: compact one-line JS gets split before return", "crf_set_query", {"path": E, "sql": "var sql=\"SELECT T1.EMPNO FROM ADM.AHRM100 T1 WHERE 1=1\"; return sql;", "output": OUT + "/v10_cmp.crf"}, contains("OK"))
    case("append_query_condition: inserted before the effective return", "crf_append_query_condition", {"path": OUT + "/v10_cmp.crf", "param": "DEPTCD", "sql": "AND T1.DEPT_CD = '{parameter.DEPTCD}'", "output": OUT + "/v10_cmp2.crf"},
         lambda t, e: None if not e and t.index("+ if('{parameter.DEPTCD}'") < t.index("  return sql;") else f"compact: {t[:400]}")
    case("append_query_condition: OVER ( opened on a previous line is not the final ORDER BY", "crf_set_query", {"path": E, "sql": "var sql=\"\";\r\nsql += \"SELECT ROW_NUMBER() OVER (\\r\\n\";\r\nsql += \"ORDER BY T1.EMPNO) AS RN, T1.EMPNO FROM ADM.AHRM100 T1\\r\\n\";\r\nreturn sql;", "output": OUT + "/v10_ov.crf"}, contains("OK"))
    case("append_query_condition: multi-line OVER → before return", "crf_append_query_condition", {"path": OUT + "/v10_ov.crf", "param": "DEPTCD", "sql": "AND 1=1", "output": OUT + "/v10_ov2.crf"}, contains("return 앞"))
    case("append_query_condition: after: inside if/else chain → after the chain", "crf_set_query", {"path": E, "sql": "var sql=\"SELECT 1 FROM DUAL WHERE 1=1\";\r\nif('{parameter.A}' != ''){\r\nsql += \" AND A=1\";\r\n}\r\nelse {\r\nsql += \" AND A=2\";\r\n}\r\nsql += \" ORDER BY 1\";\r\nreturn sql;", "output": OUT + "/v10_el.crf"}, contains("OK"))
    case("append_query_condition: after:A=1 lands after the else block", "crf_append_query_condition", {"path": OUT + "/v10_el.crf", "param": "B", "sql": "AND B=1", "position": "after:A=1", "output": OUT + "/v10_el2.crf"},
         lambda t, e: None if not e and t.index("AND A=2") < t.index("+ if('{parameter.B}'") and t.index("+ }") < t.index("ORDER BY 1") else f"else chain: {t[:500]}")
    case("plain SQL '&lt;' literal preserved (no XML decode)", "crf_append_query_condition", {"path": E, "param": "X", "sql": "AND T1.NM <> '&lt;'", "output": OUT + "/v10_lt.crf"}, contains("'&lt;'"))
    MBFE2 = '<select id="x">SELECT 1 FROM T WHERE X IN <foreach collection="ids" item="i" open="(" separator="," close=")">\'#{i}\'</foreach></select>'
    case("foreach: already-quoted '#{item}' is not double-quoted", "crf_set_query", {"path": E, "sql": MBFE2, "output": OUT + "/v10_fe2.crf"}, contains("OK"))
    case("foreach: quoted placeholder JS", "crf_get_query", {"path": OUT + "/v10_fe2.crf"}, lambda t, e: None if not e and "sql += \"'\" + __fe1[__i1] + \"'\\r\\n\";" in t and "''\" + __fe1" not in t else f"quoted item: {t[:600]}")
    case("append_query_condition ignores ORDER BY inside OVER(...)", "crf_append_query_condition",
         {"path": E, "param": "X", "sql": "AND 1=1", "output": OUT + "/v10_over.crf", "dataset": "0"}, contains("OK"))
    case("set_query with window ORDER BY only", "crf_set_query", {"path": E, "sql": "SELECT ROW_NUMBER() OVER (ORDER BY T1.EMPNO) AS RN, T1.EMPNO FROM ADM.AHRM100 T1", "output": OUT + "/v10_win.crf"}, contains("OK"))
    case("append_query_condition: no final ORDER BY → before return", "crf_append_query_condition",
         {"path": OUT + "/v10_win.crf", "param": "DEPTCD", "sql": "AND T1.DEPT_CD = '{parameter.DEPTCD}'", "output": OUT + "/v10_win2.crf"},
         lambda t, e: None if not e and "return 앞" in t and "OVER (ORDER BY T1.EMPNO) AS RN, T1.EMPNO FROM ADM.AHRM100 T1" in t else f"window order by: {t[:400]}")
    case("append_query_condition: plain SQL → JS, before ORDER BY, splits line", "crf_append_query_condition",
         {"path": OUT + "/v10_a.crf", "param": "jgrdCd", "sql": "AND T1.JGRD_CD = #{jgrdCd}", "output": OUT + "/v10_c.crf"},
         contains("OK", "(ORDER BY 앞, 3줄)", "평문 SQL 을 JavaScript 동적쿼리로 변환", "+ if('{parameter.JGRDCD}' != ''){", "+ sql += \"AND T1.JGRD_CD = '{parameter.JGRDCD}'\\r\\n\";", "  sql += \"ORDER BY T1.EMPNO\\r\\n\";"))
    case("append_query_condition: after:<text> inside if → after the block", "crf_append_query_condition",
         {"path": OUT + "/v10_c.crf", "param": "EMPNM", "sql": "AND T2.EMP_NM LIKE '%' || '{parameter.EMPNM}' || '%'", "position": "after:JGRD_CD", "output": OUT + "/v10_d.crf"},
         contains("OK", "('JGRD_CD' 다음, 3줄)", "기준 줄이 if 블록 안이라 그 블록(else 분기 포함)이 닫힌 뒤에 삽입"))
    case("append_query_condition: declares new param", "crf_append_query_condition",
         {"path": OUT + "/v10_d.crf", "param": "NATNCD", "sql": "AND T1.NATN_CD = '{parameter.NATNCD}'", "output": OUT + "/v10_e.crf"}, contains("OK", "+ 매개변수 선언: [NATNCD]"))
    case("append_query_condition: validate has return + no errors", "crf_validate", {"path": OUT + "/v10_e.crf"}, contains("ERROR 0"))
    case("append_query_condition: neither param nor condition -> ERROR", "crf_append_query_condition", {"path": OUT + "/v10_a.crf", "sql": "AND 1=1", "output": OUT + "/v10_bad3.crf"}, err_contains("param", "condition"))
    case("append_query_condition: bad position -> ERROR", "crf_append_query_condition", {"path": OUT + "/v10_a.crf", "param": "X", "sql": "AND 1=1", "position": "middle", "output": OUT + "/v10_bad4.crf"}, err_contains("position"))
    MBFE = ('<select id="x">SELECT T1.EMPNO, T1.EMP_NM FROM T T1 <trim prefix="WHERE" prefixOverrides="AND |OR "><if test="deptList != null"> AND T1.DEPT_CD IN '
            '<foreach collection="deptList" item="d" open="(" separator="," close=")">#{d}</foreach></if></trim> ORDER BY T1.EMPNO</select>')
    case("MyBatis foreach → split loop, trim WHERE → 1=1", "crf_set_query", {"path": E, "sql": MBFE, "output": OUT + "/v10_fe.crf"}, contains("OK", "<foreach collection=deptList> → 매개변수 DEPTLIST 를 쉼표로"))
    case("MyBatis foreach: generated JS", "crf_get_query", {"path": OUT + "/v10_fe.crf"},
         contains("sql += \" WHERE 1=1 \\r\\n\";", "var __fe1 = ('{parameter.DEPTLIST}' == '') ? [] : '{parameter.DEPTLIST}'.split(',');", "for(var __i1=0; __i1<__fe1.length; __i1++){", "if(__i1>0) sql += \",\";", "sql += \"'\" + __fe1[__i1] + \"'\\r\\n\";", "/*FOREACH"))
    GENSQL = "SELECT T1.DEPT_NM, T1.EMPNO, T2.EMP_NM, T1.JGRD_NM, TO_CHAR(T1.APPNM_DT,'YYYY-MM-DD') AS APPNM_DT, T1.SAL_AMT FROM ADM.AHRM100 T1, ADM.AHRM110 T2 WHERE T1.EMPNO=T2.EMPNO AND T1.STDR_DT = '{parameter.STDRDT}' ORDER BY T1.DEPT_NM, T1.EMPNO"
    GENCOLS = [{"field": "DEPT_NM", "title": "부서", "width": 500}, {"field": "EMPNO", "title": "사번", "width": 300}, {"field": "EMP_NM", "title": "성명", "width": 300}, {"field": "JGRD_NM", "title": "직급"},
               {"field": "APPNM_DT", "title": "임용일자", "align": "Center", "width": 350}, {"field": "SAL_AMT", "title": "급여", "format": "#,##0", "total": "sum", "width": 400}]
    case("generate v2: full derivation with group", "crf_generate",
         {"template": E, "sql": GENSQL, "columns": GENCOLS, "title": "부서별 급여 현황", "cond": "var s=\"기준일자: \"+rexpert.field(\"parameter.STDRDT\"); return s;", "cond_right": "", "groups": "DEPT_NM", "output": OUT + "/v10_gen.crf"},
         contains("OK: 목록형 리포트 파생 완료", "템플릿 그룹 1개 제거", "SELECT 에 없는 필드 11개 바인딩 해제·제거", "본문 표 '표3' 6열 재구성", "제목 글상자 \"글상자1\" = \"부서별 급여 현황\"", "조건 글상자(왼쪽) \"글상자2\": 공식 COND 스크립트 교체", "조건 글상자(오른쪽) \"글상자3\": 비움",
                  "로고: 페이지 바닥글에 서브리포트가 이미 있어 유지", "그룹 DEPT_NM: OK: added group on DEPT_NM", "그룹 바닥글에 '소 계' 라벨 1개 추가", "필드(6): DEPT_NM, EMPNO, EMP_NM, JGRD_NM, APPNM_DT, SAL_AMT", "ERROR 0 / WARN 1"))
    case("generate v2: no leftover group refs / excel grid clean", "crf_validate", {"path": OUT + "/v10_gen.crf", "excel": "true"}, lambda t, e: None if not e and "엑셀 격자" not in t and "남은 참조" not in t and "그룹 필드 null" not in t else f"gen validate: {t[:500]}")
    case("generate v2: layout", "crf_describe_layout", {"path": OUT + "/v10_gen.crf"},
         contains('[텍스트:"부서별 급여 현황"]', 'GroupHeader(→DEPT_NM)', '"grp_DEPT_NM" [데이터:DEPT_NM]  위치(왼0,위0,너비800', '"sub_SAL_AMT" [공식:SUM_SAL_AMT_BY_DEPT_NM]  위치(왼2270,위0,너비400', '"lbl_subtotal" [텍스트:"소 계"]  위치(왼1920,위0,너비350', '"합 계" | · | · | · | · | 공식:F_TOTAL_SAL_AMT{#,##0}'))
    case("generate v2: columns omitted → all SELECT columns", "crf_generate", {"template": E, "sql": "SELECT EMPNO, EMP_NM, DEPT_CD FROM ADM.AHRM100 WHERE ROWNUM < 5", "output": OUT + "/v10_gen2.crf"},
         contains("OK", "columns 미지정 → SELECT 컬럼 3개 전부", "본문 표 '표3' 3열 재구성(너비 [890, 890, 890] 합 2670)", "groups" if False else "OK"))
    case("generate v2: output==template -> ERROR", "crf_generate", {"template": E, "sql": "SELECT 1 FROM DUAL", "output": E}, err_contains("output 이 template 과 같습니다"))
    case("generate v2: missing template -> ERROR", "crf_generate", {"template": "C:/nope.crf", "sql": "SELECT 1 FROM DUAL", "output": OUT + "/v10_gen3.crf"}, err_contains("template 파일 없음"))

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
