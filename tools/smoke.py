#!/usr/bin/env python3
"""회귀 스모크 테스트: 실 리포트 2종에 읽기/쓰기 도구를 돌려 기대 동작을 assert.

  python tools/smoke.py            # 전부
  python tools/smoke.py -v         # 각 도구 응답 출력
픽스처 경로는 CLIP_SMOKE_A / CLIP_SMOKE_B 환경변수로 바꿀 수 있다. 없으면 해당 케이스 skip.
쓰기 결과는 <proj>/.smoke-out/ 에 기록(gitignore).
"""
import os, sys, json, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mcpcall

PROJ = mcpcall.PROJ
A = os.environ.get("CLIP_SMOKE_A", "C:/eGovFrameDev-4.3.1/workspace/report/meta/adm/ahrm/ahrmrc/ahrmrc0460_prn01.crf")
B = os.environ.get("CLIP_SMOKE_B", "C:/eGovFrameDev-4.3.1/workspace/report/meta/sch/ssrm/ssrmva/ssrmva0350_prn17.crf")
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
