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
        # 실파일은 사용자가 격자에 맞춰 놓은 상태(열 구성은 계속 바뀌므로 경계 값은 고정하지 않는다) → 어긋난 사본(오른쪽 변 623 = 1mm 단위 경계가 아님)을 만들어 검사
        case("aligned original: no excel grid finding", "crf_validate", {"path": F1, "excel": "true"}, lambda t, e: None if not e and "엑셀 격자" not in t else f"original F1 is not grid-aligned any more: {t[:300]}")
        case("make misaligned copy (글상자2 width 623)", "crf_set_label", {"path": F1, "name": "글상자2", "left": "0", "width": "623", "output": OUT + "/f1_mis.crf"}, contains("OK"))
        F1 = OUT + "/f1_mis.crf"
        case("validate excel grid INFO lists misaligned label edge only", "crf_validate", {"path": F1},
             lambda t, e: None if not e and re.search(r"ℹ 엑셀 격자: 본문 표 경계 \d+개 외에 어긋난 세로선 1개\(\[623\]\)", t) and re.search(r'글상자2": 오른쪽 623\(가까운 경계 \d+(/\d+)?\)', t) and "글상자3" not in t.split("엑셀 격자")[1].split("ℹ")[0] and "글상자4" not in t else f"excel grid: {t[:600]}")
        case("validate excel=true -> WARN", "crf_validate", {"path": F1, "excel": "true"}, contains("⚠ 엑셀 격자"))
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

# ---- v0.11.0: JS 쿼리 실제 실행(Rhino) — 실행 SQL / 문법·실행 오류 lint / foreach 루프 변수 / where·trim·set 정리 ----
if os.path.isfile(E):
    def evalsql(*subs, absent=()):
        """crf_get_query mode=eval 응답의 '실행 SQL' 구간(공백 정규화)에 subs 가 있고 absent 는 없는지"""
        def chk(text, err):
            if err or "----- 실행 SQL" not in text: return f"no executed SQL section err={err}: {text[:300]}"
            sql = re.sub(r"\s+", " ", text.split("----- 실행 SQL", 1)[1].split("\n", 1)[1])
            miss = [s for s in subs if s not in sql]; bad = [s for s in absent if s in sql]
            return None if not miss and not bad else f"missing {miss} unexpected {bad}: {sql[:400]}"
        return chk
    case("get_query eval: real report JS runs (all params empty)", "crf_get_query", {"path": E, "dataset": "0", "mode": "eval"},
         lambda t, e: None if not e and "실행 SQL (매개변수 전부 빈 값으로 JS 를 실제 실행한 결과)" in t and "SELECT" in t.split("----- 실행 SQL", 1)[1] and "✖" not in t and "JavaScript 원문" not in t else f"eval E: {t[:400]}")
    case("get_query params: bad JSON -> ERROR", "crf_get_query", {"path": E, "params": "DEPTCD=1"}, err_contains("params 는 JSON 객체"))
    case("get_query: bad mode -> ERROR", "crf_get_query", {"path": E, "mode": "sql"}, err_contains("both|raw|plain|eval"))
    MB11 = ('<select id="x">SELECT T1.EMPNO, T1.EMP_NM FROM T T1 <where> T1.USE_YN = \'Y\' '
            '<if test="deptList != null and deptList.size() > 0"> AND T1.DEPT_CD IN <foreach collection="vo.deptList" item="d" index="idx" open="(" separator="," close=")">#{d}</foreach></if>'
            '<foreach collection="kwList" item="kw" index="i"><if test="kw != null and kw != \'\'"> AND T1.EMP_NM LIKE \'%\' || #{kw} || \'%\'</if><if test="i > 0"> /*kw${i}*/</if></foreach>'
            '</where> ORDER BY T1.EMPNO</select>')
    case("MyBatis: <where> static-first + foreach loop-var test", "crf_set_query", {"path": E, "sql": MB11, "output": OUT + "/v11_a.crf"},
         lambda t, e: None if not e and t.startswith("OK") and "DEPTLIST" in t and "KWLIST" in t and "매개변수 VO " not in t and "✖" not in t else f"mb11: {t[:500]}")
    case("MyBatis: loop-var test → element/index, no {parameter.KW}", "crf_get_query", {"path": OUT + "/v11_a.crf", "mode": "raw"},
         lambda t, e: None if not e and "if(__fe2[__i2] != '' && __fe2[__i2] != ''){" in t and "if(__i2 > 0){" in t and "{parameter.KW}" not in t and "{parameter.I}" not in t and 'sql += " WHERE \\r\\n";' in t and "1=1" not in t else f"mb11 js: {t[:900]}")
    case("eval: lists given", "crf_get_query", {"path": OUT + "/v11_a.crf", "mode": "eval", "params": {"DEPTLIST": "A01,B02", "kwList": "김,,이"}},
         evalsql("WHERE T1.USE_YN = 'Y' AND T1.DEPT_CD IN ('A01' ,'B02' )", "AND T1.EMP_NM LIKE '%' || '김' || '%'", "/*kw1*/", "AND T1.EMP_NM LIKE '%' || '이' || '%' /*kw2*/", "ORDER BY T1.EMPNO", absent=("/*kw0*/", "|| '' ||")))
    case("eval: lists empty → no IN, no LIKE", "crf_get_query", {"path": OUT + "/v11_a.crf", "mode": "eval", "params": "{}"},
         evalsql("WHERE T1.USE_YN = 'Y' ORDER BY T1.EMPNO", absent=("DEPT_CD IN", "LIKE")))
    case("validate: converted foreach query has no JS error", "crf_validate", {"path": OUT + "/v11_a.crf"}, lambda t, e: None if not e and "JavaScript 쿼리" not in t else f"v11_a validate: {t[:400]}")
    MB11B = ('<select id="x">SELECT EMPNO, EMP_NM FROM T <where><if test="empno != null and empno != \'\'"> EMPNO = #{empno}</if>'
             '<if test="empNm != null and empNm != \'\'"> AND EMP_NM = #{empNm}</if>'
             '<trim prefix="AND (" suffix=")" prefixOverrides="OR "><if test="a != null and a != \'\'"> OR A = #{a}</if><if test="b != null and b != \'\'"> OR B = #{b}</if></trim></where> ORDER BY EMPNO</select>')
    case("MyBatis: <where> whose first fragment has no AND → runtime __trim", "crf_set_query", {"path": E, "sql": MB11B, "output": OUT + "/v11_b.crf"}, lambda t, e: None if not e and t.startswith("OK") and "✖" not in t else f"mb11b: {t[:500]}")
    case("__trim: helper in JS, hidden in plain view", "crf_get_query", {"path": OUT + "/v11_b.crf"},
         lambda t, e: None if not e and "function __trim(b, pre, suf, po, so){" in t and 'sql = __t1 + __trim(sql, "WHERE", "", ' in t and "/*TRIM:" in t and "/*END TRIM*/" in t
         and "toUpperCase" not in t.split("평문 복원", 1)[1] else f"trim js: {t[:900]}")
    case("__trim eval: only 2nd condition → leading AND removed", "crf_get_query", {"path": OUT + "/v11_b.crf", "mode": "eval", "params": {"EMPNM": "홍길동"}},
         evalsql("FROM T WHERE EMP_NM = '홍길동' ORDER BY EMPNO", absent=("WHERE AND", "AND (", "1=1")))
    case("__trim eval: all → WHERE a AND b AND ( A OR B )", "crf_get_query", {"path": OUT + "/v11_b.crf", "mode": "eval", "params": {"EMPNO": "7", "EMPNM": "홍", "A": "1", "B": "2"}},
         evalsql("WHERE EMPNO = '7' AND EMP_NM = '홍' AND ( A = '1' OR B = '2' ) ORDER BY EMPNO"))
    case("__trim eval: none → no WHERE at all", "crf_get_query", {"path": OUT + "/v11_b.crf", "mode": "eval"}, evalsql("FROM T ORDER BY EMPNO", absent=("WHERE",)))
    case("__trim: fields still parsed from SELECT, validate clean", "crf_validate", {"path": OUT + "/v11_b.crf"}, lambda t, e: None if not e and "JavaScript 쿼리" not in t and "return 문이 없음" not in t else f"v11_b validate: {t[:400]}")
    case("append_query_condition on __trim query: before ORDER BY, still valid JS", "crf_append_query_condition",
         {"path": OUT + "/v11_b.crf", "param": "DEPTCD", "sql": "AND DEPT_CD = '{parameter.DEPTCD}'", "output": OUT + "/v11_c.crf"}, lambda t, e: None if not e and t.startswith("OK") and "ORDER BY 앞" in t and "JavaScript 쿼리" not in t else f"append on trim: {t[:500]}")
    BADJS = 'var sql = "";\nsql += "SELECT EMPNO FROM T ";\nif(\'{parameter.EMPNO}\' != \'\'){\nsql += " WHERE EMPNO = \'{parameter.EMPNO}\'"\nsql += " ORDER BY 1";\nreturn sql;'
    case("set_query: JS syntax error is reported", "crf_set_query", {"path": E, "sql": BADJS, "output": OUT + "/v11_bad.crf"}, contains("OK", "✖ JavaScript 쿼리 문법 오류"))
    case("validate: JS syntax error → ✖", "crf_validate", {"path": OUT + "/v11_bad.crf"}, lambda t, e: None if not e and "JavaScript 쿼리 문법 오류" in t and "ERROR 0" not in t else f"bad js validate: {t[:400]}")
    BADJS2 = 'var sql = "";\nsqll += "SELECT EMPNO FROM T ";\nreturn sql;'
    case("set_query: JS runtime error (undeclared variable) is reported", "crf_set_query", {"path": E, "sql": BADJS2, "script_type": "javascript", "output": OUT + "/v11_bad2.crf"}, contains("✖ JavaScript 쿼리 실행 오류", "sqll"))
    case("get_query eval on broken JS → ✖ with reason", "crf_get_query", {"path": OUT + "/v11_bad2.crf", "mode": "eval"}, contains("실행 SQL", "✖", "sqll"))
    case("eval is sandboxed: no Java access from report script", "crf_set_query",
         {"path": E, "sql": 'var sql = "SELECT 1 A FROM DUAL /*" + java.lang.System.getProperty("user.name") + "*/";\nreturn sql;', "script_type": "javascript", "output": OUT + "/v11_sbx.crf"},
         contains("✖ JavaScript 쿼리 실행 오류", '"java" is not defined'))
    case("eval NotScript query: tokens bound for display", "crf_get_query", {"path": A, "dataset": "SQLDS3", "params": {"RECRUYY": "2024"}},
         lambda t, e: None if not e and "실행 SQL (매개변수 {RECRUYY=2024}, 나머지는 빈 값을 대입)" in t and "2024" in t.split("----- 실행 SQL", 1)[1] and "{parameter.RECRUYY}" not in t.split("----- 실행 SQL", 1)[1] else f"notscript eval: {t[-500:]}") if os.path.isfile(A) else None
    # Codex 리뷰 반영: AND/OR 혼합 where, 빈 foreach, 내용 기준 구분자, 리터럴 안 토큰의 따옴표, 사용자 도우미 함수
    MBR = ('<select id="x">SELECT EMPNO FROM T <where><if test="a != null and a != \'\'">AND A = #{a}</if><if test="b != null and b != \'\'">OR B = #{b}</if></where>'
           ' AND X IN <foreach collection="ids" item="item" open="(" separator="," close=")"><if test="item != \'\'">#{item}</if></foreach></select>')
    case("review: mixed AND/OR <where> goes to runtime trim (no WHERE 1=1)", "crf_set_query", {"path": E, "sql": MBR, "output": OUT + "/v11_r.crf"}, lambda t, e: None if not e and t.startswith("OK") and "✖" not in t else f"mbr: {t[:400]}")
    case("review: only the OR fragment → WHERE B (not WHERE 1=1 OR B); foreach separator only between emitted items", "crf_get_query", {"path": OUT + "/v11_r.crf", "mode": "eval", "params": {"B": "2", "IDS": "K,,M"}},
         evalsql("FROM T WHERE B = '2' AND X IN ('K' ,'M' )", absent=("1=1", ",,", "(,")))
    case("review: empty foreach emits neither open nor close", "crf_get_query", {"path": OUT + "/v11_r.crf", "mode": "eval", "params": {"A": "1"}}, evalsql("WHERE A = '1' AND X IN", absent=("(", ")")))
    case("review: token inside a larger SQL literal — quote doubled", "crf_set_query", {"path": E, "sql": 'var sql = "SELECT EMPNO FROM T WHERE NM LIKE \'%{parameter.EMPNM}%\'";\nreturn sql;', "script_type": "javascript", "output": OUT + "/v11_q.crf"}, contains("OK"))
    case("review: quote in value stays inside the literal", "crf_get_query", {"path": OUT + "/v11_q.crf", "mode": "eval", "params": {"EMPNM": "x%' OR 1=1 --"}}, evalsql("NM LIKE '%x%'' OR 1=1 --%'"))
    case("review: user helper function is not dropped from the plain view", "crf_set_query", {"path": E, "sql": 'function mk(){ return "SELECT EMPNO, EMP_NM FROM T"; }\nvar sql = mk();\nsql += " ORDER BY 1";\nreturn sql;', "script_type": "javascript", "sync_fields": "none", "output": OUT + "/v11_fn.crf"}, lambda t, e: None if not e and "return 문이 없" not in t and "✖" not in t else f"fn: {t[:300]}")
    case("review: plain view keeps SQL built in a user function", "crf_get_query", {"path": OUT + "/v11_fn.crf", "mode": "plain"}, contains("SELECT EMPNO, EMP_NM FROM T", "ORDER BY 1"))
    if os.environ.get("CLIP_SMOKE_DB", "1") == "1":
        case("sync_fields db on JS query uses the executed SQL", "crf_sync_fields", {"path": E, "dataset": "0", "mode": "db", "output": OUT + "/v11_sync.crf"},
             lambda t, e: None if not e and t.startswith("OK") and "DB ResultSetMetaData, JS 실행 SQL" in t else f"sync db eval: {t[:400]}")

# ---- v0.11.0: 양식 문서 파서(doc_text / doc_image) + 리포트 배치 미리보기(crf_preview) ----
# 픽스처는 여기서 직접 만든다(zip+XML 형식은 파이썬 표준 라이브러리만으로). HWP(바이너리)는 실파일이 있을 때만(CLIP_SMOKE_HWP).
import zipfile, zlib, struct
DOCS = OUT + "/docs"
os.makedirs(DOCS, exist_ok=True)


def _zip(name, files):
    p = DOCS + "/" + name
    with zipfile.ZipFile(p, "w", zipfile.ZIP_DEFLATED) as z:
        for k, v in files.items():
            z.writestr(k, v)
    return p


W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
DOCX = _zip("form.docx", {
    "[Content_Types].xml": '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>',
    "word/document.xml": f'<?xml version="1.0" encoding="UTF-8"?><w:document {W}><w:body>'
    '<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="40"/></w:rPr><w:t>재직증명서</w:t></w:r></w:p>'
    '<w:tbl><w:tblGrid><w:gridCol w:w="2268"/><w:gridCol w:w="3402"/><w:gridCol w:w="3402"/></w:tblGrid>'
    '<w:tr><w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>인적사항</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>성명</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>홍길동</w:t></w:r></w:p></w:tc></w:tr>'
    '<w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>기계공학과</w:t></w:r><w:r><w:tab/><w:t>&lt;교무처&gt;</w:t></w:r></w:p></w:tc></w:tr>'
    '<w:tr><w:trPr><w:gridBefore w:val="2"/></w:trPr><w:tc><w:p><w:r><w:t>끝칸</w:t></w:r></w:p></w:tc></w:tr></w:tbl>'
    '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1701" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>'})
S = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
XLSX = _zip("form.xlsx", {
    "xl/workbook.xml": f'<?xml version="1.0"?><workbook {S}><sheets><sheet name="집계" sheetId="1" r:id="rId1"/><sheet name="빈시트" sheetId="2" r:id="rId2"/></sheets></workbook>',
    "xl/_rels/workbook.xml.rels": '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="t" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="t" Target="worksheets/sheet2.xml"/></Relationships>',
    "xl/sharedStrings.xml": f'<?xml version="1.0"?><sst {S}><si><t>학과</t></si><si><r><t>인</t></r><r><t>원</t></r></si><si><t>기계공학과</t></si></sst>',
    "xl/styles.xml": f'<?xml version="1.0"?><styleSheet {S}><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>',
    "xl/worksheets/sheet1.xml": f'<?xml version="1.0"?><worksheet {S}><dimension ref="A1:C3"/><cols><col min="1" max="1" width="20" customWidth="1"/></cols><sheetData>'
    '<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="inlineStr"><is><t>기준일</t></is></c></row>'
    '<row r="2" ht="30"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>42</v></c><c r="C2" s="1"><v>46297</v></c></row>'
    '<row r="3"><c r="B3"><f>SUM(B2:B2)</f><v>42</v></c><c r="C3" s="1"><v>1</v></c></row></sheetData><mergeCells count="1"><mergeCell ref="A3:A4"/></mergeCells></worksheet>',
    "xl/worksheets/sheet2.xml": f'<?xml version="1.0"?><worksheet {S}><sheetData/></worksheet>'})
P_ = 'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
PPTX = _zip("form.pptx", {
    "ppt/presentation.xml": f'<?xml version="1.0"?><p:presentation {P_}><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/></p:presentation>',
    "ppt/_rels/presentation.xml.rels": '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId2" Type="t" Target="slides/slide1.xml"/></Relationships>',
    "ppt/slides/slide1.xml": f'<?xml version="1.0"?><p:sld {P_}><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/></p:nvGrpSpPr><p:grpSpPr/>'
    '<p:sp><p:nvSpPr><p:cNvPr id="2" name="제목 1"/></p:nvSpPr><p:spPr><a:xfrm><a:off x="360000" y="720000"/><a:ext cx="3600000" cy="1080000"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:p><a:pPr algn="ctr"/><a:r><a:rPr sz="2400" b="1"/><a:t>화면 설계</a:t></a:r></a:p></p:txBody></p:sp>'
    '<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="3" name="표 2"/></p:nvGraphicFramePr><p:xfrm><a:off x="360000" y="2160000"/><a:ext cx="7200000" cy="720000"/></p:xfrm><a:graphic><a:graphicData><a:tbl><a:tblGrid><a:gridCol w="3600000"/><a:gridCol w="3600000"/></a:tblGrid>'
    '<a:tr h="360000"><a:tc gridSpan="2"><a:txBody><a:bodyPr/><a:p><a:r><a:t>조회조건</a:t></a:r></a:p></a:txBody></a:tc><a:tc hMerge="1"><a:txBody><a:bodyPr/><a:p/></a:txBody></a:tc></a:tr>'
    '<a:tr h="360000"><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>학년도</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>2026</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame>'
    '</p:spTree></p:cSld></p:sld>'})
HP = 'xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph" xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hh="http://www.hancom.co.kr/hwpml/2011/head" xmlns:hc="http://www.hancom.co.kr/hwpml/2011/core"'
HWPX = _zip("form.hwpx", {
    "mimetype": "application/hwp+zip",
    "Contents/header.xml": f'<?xml version="1.0" encoding="UTF-8"?><hh:head {HP}><hh:refList><hh:fontfaces><hh:fontface lang="HANGUL"><hh:font id="0" face="바탕체"/></hh:fontface><hh:fontface lang="LATIN"><hh:font id="0" face="Arial"/></hh:fontface></hh:fontfaces>'
    '<hh:charProperties><hh:charPr id="0" height="1000" textColor="#000000"><hh:fontRef hangul="0"/></hh:charPr><hh:charPr id="1" height="1600" textColor="#0000FF"><hh:fontRef hangul="0"/><hh:bold/></hh:charPr></hh:charProperties>'
    '<hh:paraProperties><hh:paraPr id="0"><hh:align horizontal="JUSTIFY"/></hh:paraPr><hh:paraPr id="1"><hh:align horizontal="CENTER"/></hh:paraPr></hh:paraProperties></hh:refList></hh:head>',
    "Contents/section0.xml": f'<?xml version="1.0" encoding="UTF-8"?><hs:sec {HP}>'
    '<hp:p paraPrIDRef="1"><hp:run charPrIDRef="1"><hp:secPr><hp:pagePr landscape="WIDELY" width="59528" height="84188"><hp:margin header="4252" footer="4252" gutter="0" left="8504" right="8504" top="5668" bottom="4252"/></hp:pagePr></hp:secPr><hp:t>위촉 통지서</hp:t></hp:run><hp:linesegarray><hp:lineseg textpos="0" vertpos="0" vertsize="1600"/></hp:linesegarray></hp:p>'
    '<hp:p paraPrIDRef="0"><hp:run charPrIDRef="0"><hp:tbl rowCnt="2" colCnt="2"><hp:sz width="28346" height="4252"/><hp:pos treatAsChar="1"/>'
    '<hp:tr><hp:tc><hp:subList><hp:p paraPrIDRef="1"><hp:run charPrIDRef="0"><hp:t>소속</hp:t></hp:run></hp:p></hp:subList><hp:cellAddr colAddr="0" rowAddr="0"/><hp:cellSpan colSpan="1" rowSpan="2"/><hp:cellSz width="14173" height="4252"/></hp:tc>'
    '<hp:tc><hp:subList><hp:p paraPrIDRef="1"><hp:run charPrIDRef="1"><hp:t>입학팀</hp:t></hp:run></hp:p></hp:subList><hp:cellAddr colAddr="1" rowAddr="0"/><hp:cellSpan colSpan="1" rowSpan="1"/><hp:cellSz width="14173" height="2126"/></hp:tc></hp:tr>'
    '<hp:tr><hp:tc><hp:subList><hp:p paraPrIDRef="0"><hp:run charPrIDRef="0"><hp:t>줄1<hp:lineBreak/>줄2</hp:t></hp:run></hp:p></hp:subList><hp:cellAddr colAddr="1" rowAddr="1"/><hp:cellSpan colSpan="1" rowSpan="1"/><hp:cellSz width="14173" height="2126"/></hp:tc></hp:tr></hp:tbl></hp:run>'
    '<hp:linesegarray><hp:lineseg textpos="0" vertpos="7200" vertsize="1000"/></hp:linesegarray></hp:p></hs:sec>'})
HTMLF = DOCS + "/form.html"
with open(HTMLF, "w", encoding="utf-8") as _f:
    _f.write('<!DOCTYPE html><html><head><meta charset="utf-8"><title>양식</title><style>body{margin:10mm;font-size:11pt}td{border:1px solid #000}</style></head><body><h1 style="text-align:center">재 직 증 명 서</h1>'
             '<table><tr><th rowspan="2">구분</th><td>성명</td></tr><tr><td colspan="1">홍길동 &lt;교무처&gt;</td></tr></table><p style="display:none">숨은 글</p><script>var x="스크립트 문자열";</script></body></html>')


def _png(path, w, h):
    raw = b"".join(b"\x00" + b"\xff\xff\xff" * w for _ in range(h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
    return path


PNGF = _png(DOCS + "/scan.png", 420, 594)
with open(DOCS + "/junk.bin", "wb") as _f:
    _f.write(b"\x00\x01\x02 not a document")
with open(DOCS + "/old.hwp", "wb") as _f:
    _f.write(b"HWP Document File V3.00 \x1a\x01\x02\x03\x04\x05" + b"\x00" * 64)

case("doc_text DOCX: page, paragraph style, table grid + merges", "doc_text", {"path": DOCX},
     contains("[DOCX,", "용지 210×297mm", "본문 너비 159.2mm", 'P center 20pt B: "재직증명서"', "[표 3행×3열 너비 160mm 열너비[40, 60, 60]", "[0,0] 세로병합 시작 40mm", '[0,2] 60mm left: "홍길동"', "[1,0] 40mm ‹세로병합 — 위 셀에 이어짐›", '[1,1] 가로병합 2 120mm left: "기계공학과→<교무처>"', '[2,2] 60mm left: "끝칸"', "doc_image(path)"))
case("doc_text XLSX: shared/inline strings, date style, merges, formula in detail", "doc_text", {"path": XLSX, "detail": "true"},
     contains("[XLSX,", "시트 2개: 집계, 빈시트", '== 시트 1 "집계" 범위 A1:C3', "병합(1): A3:A4", 'A1="학과" | B1="인원" | C1="기준일"', 'A2="기계공학과" | B2=42 | C2=2026-10-02', "(행높이 10.6mm)", "B3=42 {=SUM(B2:B2)} | C3=1900-01-01", "(값 있는 셀 없음)"))
XLSX1904 = _zip("d1904.xlsx", {
    "xl/workbook.xml": f'<?xml version="1.0"?><workbook {S}><workbookPr date1904="1"/><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>',
    "xl/_rels/workbook.xml.rels": '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="t" Target="worksheets/sheet1.xml"/></Relationships>',
    "xl/styles.xml": f'<?xml version="1.0"?><styleSheet {S}><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>',
    "xl/worksheets/sheet1.xml": f'<?xml version="1.0"?><worksheet {S}><sheetData><row r="1"><c r="A1" s="1"><v>1</v></c></row></sheetData></worksheet>'})
case("doc_text XLSX: 1904 date system", "doc_text", {"path": XLSX1904}, contains("A1=1904-01-02"))
case("doc_text XLSX: sheet selector", "doc_text", {"path": XLSX, "sheet": "2"}, lambda t, e: None if not e and '"빈시트"' in t and '"집계" 범위' not in t else f"sheet sel: {t[:300]}")
case("doc_text XLSX: missing sheet -> ERROR lists sheets", "doc_text", {"path": XLSX, "sheet": "없는시트"}, err_contains("시트 '없는시트' 없음", "집계, 빈시트"))
case("doc_text PPTX: slide size, shape position, table with merge (r:id vs id)", "doc_text", {"path": PPTX},
     contains("[PPTX,", "슬라이드 1장  크기 254×190.5mm", '[도형 "제목 1" x=10 y=20 100×30mm]', '· ctr 24pt B: "화면 설계"', '[표 "표 2" x=10 y=60 200×20mm 2행×2열 열너비[100, 100]]', '[0,0] 병합 1×2 "조회조건"', '[1,1] "2026"'))
case("doc_text HWPX: page/margins, paragraph y+style, table cell address/span/size", "doc_text", {"path": HWPX},
     contains("[HWPX,", "용지 210×297mm  여백 왼30 오른30 위20 아래15", "본문 너비 150mm", 'P y=0 CENTER 16pt B 바탕체 #0000FF: "위촉 통지서"', "[표 2행×2열 100×15mm 글자처럼 문단정렬=JUSTIFY]", '[0,0] 병합 2×1 50×15mm CENTER 10pt 바탕체: "소속"', '[1,1] 50×7.5mm JUSTIFY 10pt 바탕체: "줄1⏎줄2"'))
case("doc_text HTML: visible text + table spans (with or without a browser)", "doc_text", {"path": HTMLF},
     lambda t, e: None if not e and "[HTML," in t and "재 직 증 명 서" in t and "홍길동 <교무처>" in t and "병합 2×1" in t and "숨은 글" not in t.replace("(브라우저", "") and "스크립트 문자열" not in t else f"html: {t[:600]}")
case("doc_text image: size + points to doc_image (no OCR)", "doc_text", {"path": PNGF}, contains("[IMAGE,", "이미지 420×594px (세로", "1px ≈ 0.5000mm", "doc_image(path)"))
case("doc_image image: returned as an MCP image", "doc_image", {"path": PNGF, "max_px": "300"}, contains("이미지 원본 420×594px", "전송 크기 212×300px", "[image image/png 212x300"))
with open(DOCS + "/huge.png", "wb") as _f:   # 머리글만 60000×60000 인 PNG — 통째로 풀기 전에 크기로 거부해야 함
    _ck = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    _f.write(b"\x89PNG\r\n\x1a\n" + _ck(b"IHDR", struct.pack(">IIBBBBB", 60000, 60000, 8, 2, 0, 0, 0)) + _ck(b"IDAT", zlib.compress(b"\x00" * 64)) + _ck(b"IEND", b""))
case("doc_image: oversized image dimensions are rejected before decoding", "doc_image", {"path": DOCS + "/huge.png"}, err_contains("그림이 너무 큼(60000×60000px)"))
case("doc_image: bad max_px -> ERROR", "doc_image", {"path": PNGF, "max_px": "big"}, err_contains("max_px"))
case("doc_image: bad source -> ERROR", "doc_image", {"path": PNGF, "source": "ocr"}, err_contains("auto|convert|preview"))
case("doc_image DOCX source=preview without embedded thumbnail -> clear ERROR", "doc_image", {"path": DOCX, "source": "preview"}, err_contains("그림으로 만들 수 없습니다", "미리보기 그림도 없습니다"))
case("doc_image DOCX auto: image (converter present) or clear ERROR (none) — never a crash", "doc_image", {"path": DOCX},
     lambda t, e: None if (not e and "DOCX → PDF(" in t and "[image image/" in t) or (e and "그림으로 만들 수 없습니다" in t and "변환 실패" in t) else f"docx image: {t[:400]}")
case("doc_text: unknown format -> ERROR lists supported formats", "doc_text", {"path": DOCS + "/junk.bin"}, err_contains("지원하지 않는 문서 형식", "HWP(5.x), HWPX, DOCX"))
case("doc_text: HWP 3.x -> ERROR with advice", "doc_text", {"path": DOCS + "/old.hwp"}, err_contains("HWP 3.x", "HWPX 또는 PDF"))
case("doc_text: missing file -> ERROR", "doc_text", {"path": DOCS + "/nope.docx"}, err_contains("파일 없음"))
case("doc_text: empty path -> ERROR", "doc_text", {"path": ""}, err_contains("path"))
case("doc_text: max_chars truncates with a hint", "doc_text", {"path": XLSX, "max_chars": "1000"}, lambda t, e: None if not e and len(t) < 1400 else f"cap: {len(t)}")
HWPF = os.environ.get("CLIP_SMOKE_HWP", "")
if os.path.isfile(HWPF):
    case("doc_text HWP 5.x (real file): page line first, paragraphs with y", "doc_text", {"path": HWPF},
         lambda t, e: None if not e and "[HWP," in t and re.search(r"== Section0\n용지 [\d.]+×[\d.]+mm  여백 왼", t) and re.search(r'\nP y=[\d.]+ \w+ [\d.]+pt', t) else f"hwp: {t[:500]}")
    case("doc_image HWP (real file): converted page or embedded preview", "doc_image", {"path": HWPF}, lambda t, e: None if (not e and "[image image/" in t) or (e and "그림으로 만들 수 없습니다" in t) else f"hwp image: {t[:400]}")
if os.path.isfile(C):
    case("crf_preview: document-type report drawn as an image", "crf_preview", {"path": C},
         lambda t, e: None if not e and "배치 미리보기 — 용지 2100×2970" in t and re.search(r"밴드 \d+개\(높이 합 \d+\), 컨트롤 \d+개", t) and re.search(r"\[image image/png \d+x1600 ", t) else f"preview: {t[:400]}")
    case("crf_preview: max_px + guides=false", "crf_preview", {"path": C, "max_px": "800", "guides": "false"}, lambda t, e: None if not e and re.search(r"\[image image/png \d+x800 ", t) else f"preview small: {t[:300]}")
    case("crf_preview: bad max_px -> ERROR", "crf_preview", {"path": C, "max_px": "x"}, err_contains("max_px"))
case("crf_preview: missing file -> ERROR", "crf_preview", {"path": "C:/nope.crf"}, err_contains("파일 없음"))

# ---- DB 가드 (DB 미연결이어도 가드가 먼저) ----
case("db_query DML refused", "db_query", {"sql": "DELETE FROM X"}, err_contains("SELECT"))
case("db_query DDL refused", "db_query", {"sql": " /*c*/ drop table x"}, error)

# ---- v0.12.0: 저장 무결성(CrfIO) — 크로스탭이 든 리포트는 엔진 기본 writer 로 저장하면 되읽기에서 EOFException ----
XT = os.environ.get("CLIP_SMOKE_XT", "C:/eGovFrameDev-4.3.1/workspace/report/meta/adm/ahrm/ahrmrc/ahrmrc0160_prn01.crf")  # 크로스탭 1개(셀 16)
if os.path.isfile(XT):
    case("crfio: save a report containing a crosstab", "crf_set_param", {"path": XT, "name": "ZZ_XT", "output": OUT + "/v12_xt.crf"}, ok)
    case("crfio: saved crosstab report reads back", "crf_describe_layout", {"path": OUT + "/v12_xt.crf"}, contains("Crosstab"))
    case("crfio: second-generation save reads back", "crf_remove_param", {"path": OUT + "/v12_xt.crf", "name": "ZZ_XT", "output": OUT + "/v12_xt2.crf"}, ok)
    case("crfio: crosstab report unchanged after two saves", "crf_diff", {"a": XT, "b": OUT + "/v12_xt2.crf"}, contains("차이 없음"))

# ---- v0.12.0: crf_render — 실제 서버 엔진 PDF 출력(서버 웹앱이 있을 때만; CLIP_RENDER_WEBAPP 또는 .env) ----
def _render_webapp():
    v = os.environ.get("CLIP_RENDER_WEBAPP", "")
    if not v:
        for f in (os.path.expanduser("~/clip-report-mcp/.env"), os.path.join(PROJ, ".env")):
            if os.path.isfile(f):
                for ln in open(f, encoding="utf-8"):
                    if ln.strip().startswith("CLIP_RENDER_WEBAPP="): v = ln.split("=", 1)[1].strip()
    return v if v and os.path.isdir(os.path.join(v, "WEB-INF", "lib")) else ""
RENDER = _render_webapp()
case("render: bad mode -> ERROR", "crf_render", {"path": E, "mode": "live"}, err_contains("mode"))
case("render: missing file -> ERROR", "crf_render", {"path": "C:/definitely/missing.crf"}, err_contains("missing.crf"))
if RENDER and os.path.isfile(E):
    case("render sample: generated rows, group subtotals + grand total, linked logo", "crf_render", {"path": E, "rows": "12", "pdf": OUT + "/v12_render.pdf"},
         contains("실제 엔진 출력(가상 데이터", "SQLDS1: 12행 (자동 생성)", "bottom_logo.crf 포함", "1/1쪽", "합 계 12", "EMP_NM12"))
    case("render sample: output pdf readable", "doc_text", {"path": OUT + "/v12_render.pdf"}, contains("퇴직교원현황", "UNIV_NM3"))
    case("render sample: page break with many rows", "crf_render", {"path": E, "rows": "80", "page": "2"}, contains("80행", "2/", "구분 대학 사번"))
    case("render sample: explicit data rows by field name", "crf_render", {"path": E, "data": {"SQLDS1": [{"UNIV_NM": "공과대학", "EMPNO": "A001", "EMP_NM": "김가상"}, {"UNIV_NM": "공과대학", "EMPNO": "A002", "EMP_NM": "이가상"}]}},
         contains("SQLDS1: 2행 (data)", "김가상", "이가상", "합 계 2"))
    case("render sample: unknown dataset in data -> warning", "crf_render", {"path": E, "rows": "1", "data": {"NOPE": []}}, contains("'NOPE' 에 해당하는 데이터셋이 없습니다"))
    case("render sample: zero rows", "crf_render", {"path": E, "rows": "0"}, contains("SQLDS1: 0행", "합 계 0"))
    if os.path.isfile(A): case("render sample: 22 datasets", "crf_render", {"path": A, "rows": "2"}, contains("SQLDS8_4: 2행", "임용지원서", "/11쪽"))
    if os.path.isfile(XT): case("render sample: crosstab report", "crf_render", {"path": XT, "rows": "6"}, contains("APPLY_FLD_NM1", "합계"))
    case("render: pdf path equal to the report -> ERROR", "crf_render", {"path": E, "pdf": E}, err_contains("pdf"))
    case("render: in_place must not turn the report into the pdf target", "crf_render", {"path": E, "in_place": "true", "pdf": OUT + "/x.crf"}, err_contains(".pdf"))
    case("render sample: array rows longer than the field list are truncated with a warning", "crf_render", {"path": E, "data": {"SQLDS1": [["a"] * 40]}}, contains("넘치는 값은 버렸습니다"))

# ---- v0.12.0: 크로스탭 생성 / 누적합산 필드 ----
if os.path.isfile(E):
    case("crosstab: add (row x column, Count) into a new data header", "crf_add_crosstab", {"path": E, "row": "UNIV_NM", "column": "JGRD_NM", "value": "EMPNO", "function": "Count", "header": "대학/직급", "output": OUT + "/v12_x1.crf"},
         contains("OK", "행=UNIV_NM", "열=JGRD_NM", "값=Count(EMPNO)", "밴드=DataHeader"))
    case("crosstab: unknown field -> ERROR", "crf_add_crosstab", {"path": E, "row": "NOPE", "column": "JGRD_NM", "value": "EMPNO", "output": OUT + "/v12_xbad.crf"}, err_contains("NOPE"))
    case("crosstab: parameter field refused", "crf_add_crosstab", {"path": E, "row": "UNIV_NM", "column": "JGRD_NM", "value": "G_REPORTLOG", "output": OUT + "/v12_xbad.crf"}, err_contains("매개변수"))
    case("crosstab: total=false -> smaller control", "crf_add_crosstab", {"path": E, "row": "UNIV_NM", "column": "JGRD_NM", "value": "EMPNO", "total": "false", "output": OUT + "/v12_xnt.crf"}, contains("OK", "합계 행=false 열=false"))
    case("crosstab: total=false geometry", "crf_describe_layout", {"path": OUT + "/v12_xnt.crf"}, contains("너비650,높이140"))
    case("crosstab: bad function -> ERROR", "crf_add_crosstab", {"path": E, "row": "UNIV_NM", "column": "JGRD_NM", "value": "EMPNO", "function": "Median", "output": OUT + "/v12_xbad.crf"}, err_contains("function"))
    case("crosstab: describe shows groups and 4 matrices", "crf_describe_layout", {"path": OUT + "/v12_x1.crf"},
         contains('ControlCrosstab "크로스탭1"', "행 그룹: UNIV_NM", "열 그룹: JGRD_NM", "RowLabel «그룹 이름»", 'RowTotal "합계"', "Summarized 데이터:EMPNO Count{#,##0}"))
    case("crosstab: label cells are not reported as empty bindings", "crf_validate", {"path": OUT + "/v12_x1.crf"}, lambda t, e: None if not e and "바인딩 비어 있음" not in t else f"validate: {t[:300]}")
    if os.path.isfile(XT): case("crosstab: real crosstab report has no empty-binding false positive", "crf_validate", {"path": XT}, lambda t, e: None if not e and "바인딩 비어 있음" not in t else f"validate: {t[:300]}")
    case("running total: reset on group", "crf_add_running_total", {"path": OUT + "/v12_x1.crf", "name": "RT_GRP", "field": "EMPNO", "function": "Count", "reset_group": "UNIV_NM", "output": OUT + "/v12_x2.crf"}, contains("OK", "Count(EMPNO)", "그룹 'UNIV_NM' 이 바뀔 때 초기화"))
    case("running total: never reset", "crf_add_running_total", {"path": OUT + "/v12_x2.crf", "name": "RT_ALL", "field": "EMPNO", "function": "Count", "output": OUT + "/v12_x3.crf"}, contains("OK", "초기화 없음"))
    case("running total: duplicate name -> ERROR", "crf_add_running_total", {"path": OUT + "/v12_x3.crf", "name": "RT_ALL", "field": "EMPNO", "output": OUT + "/v12_xbad.crf"}, err_contains("이미"))
    case("running total: unknown group -> ERROR", "crf_add_running_total", {"path": OUT + "/v12_x3.crf", "name": "RT_Z", "field": "EMPNO", "reset_group": "NOPE", "output": OUT + "/v12_xbad.crf"}, err_contains("1=UNIV_NM"))
    case("running total: bind in group footer", "crf_set_cell", {"path": OUT + "/v12_x3.crf", "table": "표2", "row": "0", "col": "3", "field": "RT_GRP", "output": OUT + "/v12_x4.crf"}, contains("OK", "RT_GRP(누적합산)"))
    case("running total: bind in detail", "crf_set_cell", {"path": OUT + "/v12_x4.crf", "table": "표3", "row": "0", "col": "14", "field": "RT_ALL", "output": OUT + "/v12_x5.crf"}, contains("OK", "RT_ALL(누적합산)"))
    case("running total: listed in summary", "crf_summary", {"path": OUT + "/v12_x5.crf"}, contains("RT_GRP", "RT_ALL"))
    if RENDER:
        _rows = [{"UNIV_NM": "공과대학", "EMPNO": "A1", "EMP_NM": "김", "JGRD_NM": "교수"}, {"UNIV_NM": "공과대학", "EMPNO": "A2", "EMP_NM": "이", "JGRD_NM": "교수"}, {"UNIV_NM": "공과대학", "EMPNO": "A3", "EMP_NM": "박", "JGRD_NM": "조교수"},
                 {"UNIV_NM": "인문대학", "EMPNO": "B1", "EMP_NM": "최", "JGRD_NM": "부교수"}, {"UNIV_NM": "인문대학", "EMPNO": "B2", "EMP_NM": "정", "JGRD_NM": "교수"}]
        # 실제 엔진 출력: 교차표 집계(공과 2·0·1=3, 인문 1·1·0=2, 합계 3·1·1=5), 그룹 소계(공식 3 = 누적합산 3), 줄 누계 1..5
        case("crosstab + running total: real engine output has the right numbers", "crf_render", {"path": OUT + "/v12_x5.crf", "data": {"SQLDS1": _rows}},
             contains("대학/직급 교수 부교수 조교수 합계", "공과대학 2 0 1 3", "인문대학 1 1 0 2", "합계 3 1 1 5", "소 계 3 3", "소 계 2 2", "합 계 5"))


def docs_check():
    """문서가 코드와 어긋나지 않는지(README 가 현상과 달라지는 것 방지): 도구 수·도구 이름·케이스 수·버전."""
    rd = lambda *p: open(os.path.join(PROJ, *p), encoding="utf-8").read()
    src, readme, version = rd("src", "CrfMcpServer.java"), rd("README.md"), rd("VERSION").strip()
    tools = re.findall(r'arr\.add\(tool\("([a-z_]+)"', src)
    crf, db = [t for t in tools if t.startswith("crf_")], [t for t in tools if t.startswith("db_")]
    out = []
    want = f"도구 {len(tools)}개 (.crf {len(crf)} + DB {len(db)} + 문서 {len(tools) - len(crf) - len(db)})"
    out.append(("docs: README tool count matches tools/list", None if want in readme and f"## MCP 도구 ({len(tools)})" in readme and f"도구 {len(tools)}개 노출" in readme else f"README 에 '{want}' / '## MCP 도구 ({len(tools)})' / '도구 {len(tools)}개 노출' 이 있어야 함"))
    miss = [t for t in tools if f"`{t}" not in readme]
    out.append(("docs: every tool is documented in README", None if not miss else f"README 에 없는 도구: {miss}"))
    total = len(CASES) + 6 - (2 if os.path.isfile(HWPF) else 0)   # README 의 숫자는 선택 픽스처(CLIP_SMOKE_HWP) 없이 센 값
    full =all(os.path.isfile(f) for f in (A, B, C, D, E, X, XT)) and bool(RENDER) and os.environ.get("CLIP_SMOKE_DB", "1") == "1"   # 픽스처가 다 있을 때만 케이스 수가 고정
    out.append(("docs: README smoke case count" + ("" if full else " (픽스처 일부 없음 — 건너뜀)"), None if not full or f"{total} 케이스" in readme else f"README 테스트 절의 케이스 수를 {total} 로"))
    out.append(("docs: CHANGELOG has the current VERSION", None if f"## [{version}]" in rd("CHANGELOG.md") else f"CHANGELOG.md 에 [{version}] 항목 없음"))
    return out


import hashlib
def _sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest() if os.path.isfile(p) else None
ORIG = {p: _sha(p) for p in (A, B, C, D, E, X, XT)}   # 픽스처 원본은 어떤 도구도 건드리면 안 된다


def files_check(since):
    """이번 실행이 쓴 .crf 에 평문 SQL(속성 900, type 9)이 없는지 — 엔진 기본 writer 는 쿼리 본문을 평문으로 쓴다(CrfIO 가 암호화)."""
    plain, n = [], 0
    for f in os.listdir(OUT):
        fp = os.path.join(OUT, f)
        if not f.lower().endswith(".crf") or os.path.getmtime(fp) < since: continue
        n += 1
        if bytes([0x84, 0x03, 9, 0]) in open(fp, "rb").read(): plain.append(f)
    changed = [os.path.basename(p) for p, h in ORIG.items() if h is not None and _sha(p) != h]
    return [("files: saved .crf keep SQL encrypted (%d files)" % n, None if n and not plain else f"평문 SQL 이 남은 파일: {plain[:8]} (검사 {n}개)"),
            ("files: fixture originals are byte-identical after the run", None if not changed else f"원본이 바뀜: {changed}")]


def main():
    import time; since = time.time() - 2
    calls =[(t, a) for _, t, a, _ in CASES]
    res, stderr = mcpcall.call(calls)
    print("server:", res[0][1])
    fails = 0
    for (name, tool, args, chk), (_, text, is_err) in zip(CASES, res[1:]):
        msg = chk(text, is_err)
        status = "PASS" if msg is None else "FAIL"
        if msg is not None: fails += 1
        print(f"[{status}] {name}" + (f"\n        {msg}" if msg else ""))
        if VERBOSE: print("        " + text.replace("\n", "\n        ")[:600])
    doc = files_check(since) + docs_check()
    for name, msg in doc:
        if msg is not None: fails += 1
        print(f"[{'PASS' if msg is None else 'FAIL'}] {name}" + (f"\n        {msg}" if msg else ""))
    total = len(CASES) + len(doc)
    print(f"\n{total - fails}/{total} passed")
    if VERBOSE and stderr.strip(): print("--- stderr ---\n" + stderr[-2000:])
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    try: sys.stdout.reconfigure(encoding="utf-8")
    except Exception: pass
    main()
