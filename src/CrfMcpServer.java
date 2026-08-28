import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.reports.Report;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.page.MainPage;
import com.clipsoft.clipreport.base.controls.*;
import com.clipsoft.clipreport.base.enums.*;
import com.clipsoft.clipreport.base.datas.*;
import com.clipsoft.clipreport.base.datas.fields.Field;
import com.clipsoft.clipreport.base.datas.fields.FieldData;
import com.clipsoft.clipreport.base.datas.fields.FieldFormula;
import com.clipsoft.clipreport.base.datas.fields.FieldGlobalParameter;
import com.clipsoft.clipreport.common.enums.BackStyleType;
import java.sql.*;
import com.clipsoft.clipreport.base.groups.Group;
import com.clipsoft.clipreport.base.RexObjectList;
import java.util.*;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import java.io.*;
import java.nio.file.*;

/** Minimal stdio MCP server (hand-rolled JSON-RPC 2.0) exposing CLIP-report tools to Claude.
 *  Tools: crf_summary, crf_generate (delegates to CrfGen3), crf_set_query. No API key needed (server side). */
public class CrfMcpServer {
  static PrintStream mcp;   // protocol channel (real stdout)
  static JSONParser P = new JSONParser();

  // Server version = the VERSION file, embedded into the jar as resource /VERSION by build.ps1 (single source of truth; no constant to drift).
  static final String VERSION = readVersion();
  static String readVersion(){ try(InputStream in=CrfMcpServer.class.getResourceAsStream("/VERSION")){ if(in!=null){ String v=new String(in.readAllBytes(),"UTF-8").trim(); if(!v.isEmpty()) return v; } }catch(Exception e){} return "0.0.0-dev"; }

  // CLIP report concept primer surfaced to the LLM via MCP `instructions` so it explains/suggests in proper terms.
  static final String INSTRUCTIONS =
    "이 서버는 Clipsoft CLIP report(.crf) 리포트를 읽고 생성·수정하며, 사용자에게 설명/개선제안하는 도구를 제공합니다. 아래 개념·용어로 답하세요.\n"+
    "[섹션(밴드)] 보고서 머리글/바닥글(첫·끝 페이지 1회), 페이지 머리글/바닥글(매 페이지 상·하단), 데이터 머리글/바닥글(본문 상·하단 1회), 그룹 머리글/바닥글(그룹핑), 본문(레코드 수만큼 반복).\n"+
    "[필드] 데이터(DB컬럼), 매개변수(사용자입력→SQL조건), 시스템(PageNofM/PageNumber/RecordNumber/PrintDateTime 등), 공식(JS/VBScript 계산: sum/avg/count/min/max/field/prev/next/format 등), 누적합산(누적요약, 그룹마다 초기화 가능), 그룹이름/그룹인덱스.\n"+
    "[★공식필드(JavaScript·식 끝에 return 필수)] 필드참조=rexpert.field(\"ns.NAME\") (ns=data/system/parameter/formula/runningtotal/parent/dataset, 작은·큰따옴표 모두 가능). 요약=rexpert.sum|avg|count|min|max(범위Int, \"data.COL\", 옵션Int, \"그룹기준|빈\", \"조건식|빈\")—5번째 인자에 조건식 주면 조건부 집계(예 \"data.ITEM_CD01=1\", and(data.A=..,data.B=..)). 이웃행=rexpert.prev|next(\"data.COL\"), N번째=rexpert.fieldat(\"data.COL\",n), 정수화=rexpert.fieldbyint(\"data.COL\"). 출력형식=rexpert.format(값, \"#,##0\"|\"yyyy.mm.dd\"|\"=[1-4].[5-6].[7-8]\")—'=[시작-끝]…'은 문자열을 자리수로 잘라 조립(예 날짜 2026.06.08). 동적문장=var s=\"\"; s+=\"…\"+rexpert.field(\"data.X\")+\"…\"; return s; 패턴. 공식식 안에서 :col/#{}/${}/? 금지(그건 쿼리 파라미터용).\n"+
    "[컨트롤] 글상자(라벨), 표(셀=행×열), 선, 이미지, 서브리포트, 차트, 바코드. 위치=왼쪽/위쪽/너비/높이.\n"+
    "[셀/라벨 값] 데이터·공식·시스템 필드 바인딩 또는 정적 텍스트. 출력양식=일반/숫자/백분율/통화/날짜/시간/사용자정의. 확장가능(텍스트 넘치면 아래로), 글꼴크기조정, 셀 자동합치기.\n"+
    "[그룹] 그룹 머리글=타이틀, 그룹 바닥글=요약함수(합계 등)로 소계. [조건스타일] 조건 충족 시 배경색 등 변경. [서브리포트] 다른 데이터셋. [다단] 레코드를 단으로 연속 출력.\n"+
    "[★쿼리 파라미터] CLIP 리포트 쿼리에서 파라미터는 반드시 '{parameter.COLNM}' 형식(대문자, 언더바는 유지: empNm→EMPNM, emp_nm→EMP_NM)으로 작성하세요. 문자열 조건은 작은따옴표로 감싸 \"= '{parameter.X}'\". 절대 :colNm, #{colNm}, ${colNm}, ? 같은 일반 SQL/MyBatis 바인드 표기를 쓰지 마세요. {dataset.X}는 다른 데이터셋 값 참조용입니다.\n"+
    "[★입력은 상황마다 다름] 화면(.xfdl/.vue)·문서양식(PDF)·쿼리(SQL/MyBatis)·백엔드·DB연결이 항상 다 주어지지는 않습니다(화면만, 쿼리 없이, 글 설명만일 수도). 프롬프트에 실제로 있는 자료만 사용하고, 적용 안 되는 단계는 건너뛰며, 도구는 '있는 입력+의도'에 맞춰 선택합니다(고정 순서 아님). 도구로 직접 확인 가능한 건 먼저 확보(파일 읽기·db_* 도구·백엔드 추적)하되, [★모르면 질문] 그래도 부족하거나 불명확한 정보(대상 파일·테이블·파라미터·조건 등)는 임의 추정·기본값으로 진행하지 말고 반드시 유저에게 질문해 확보하세요(질문은 한 번에 모아 간결히). 유저가 '추정해서 진행'을 명시한 경우에만 가정을 밝히고 진행합니다.\n"+
    "[★쿼리 읽기/찾기] 리포트의 SQL 본문은 crf_get_query(JS 동적쿼리는 평문 복원본 포함), 공식 스크립트는 crf_get_formula, '어떤 리포트가 테이블 X/매개변수 Y/문구 Z 를 쓰나'는 crf_search(dir, text, scope) 로 확인하세요. crf_summary 는 개요만 줍니다.\n"+
    "[★데이터셋 수정] 쿼리 교체는 crf_set_query(매개변수 자동 선언 + SELECT 컬럼을 필드로 추가). SELECT * 등 파싱 불가면 crf_sync_fields(mode=db)로 DB 에서 컬럼을 확정. 데이터셋 추가/삭제=crf_add_dataset/crf_remove_dataset, 매개변수=crf_set_param/crf_remove_param, 필드 이름변경/삭제=crf_rename_field/crf_remove_field(참조 검사; 참조 확인만은 crf_field_refs).\n"+
    "쓰기 도구는 항상 output 경로를 따로 받아 원본을 보존합니다(output=원본이면 거부). 도구 실패는 'ERROR: …' 메시지(isError)로 옵니다 — 그대로 유저에게 설명하고 임의로 재시도하지 마세요. crf_describe_layout 의 표 셀 중 ‹병합› 은 병합되어 숨은 자리라 편집 불가(기준 셀에 설정), {…} 는 출력양식입니다.\n"+
    "[★열린 파일 주의] .crf가 CLIP report 앱에서 열려 있는 동안 쓰기 도구로 수정하면 파일 잠금/상태 충돌(앱에서 저장 시 편집이 덮어써짐, 또는 편집이 앱에 반영 안 됨)이 납니다. 이미 만든 _edited.crf에 추가 수정이 필요할 때 그 파일이 열려 있을 수 있으면, 먼저 유저에게 '저장 후 잠깐 닫기'를 요청하고 → 수정 → '다시 열기'를 안내하세요(저장→닫기→수정→재오픈).";

  // ---- reflection helpers for rich, defensive property reads ----
  static String g(Object o,String m){ if(o==null)return null; try{ Object v=o.getClass().getMethod(m).invoke(o); return v==null?null:v.toString(); }catch(Exception e){ return null; } }
  static Object go(Object o,String m){ if(o==null)return null; try{ return o.getClass().getMethod(m).invoke(o); }catch(Exception e){ return null; } }
  static String nameOf(Object o){ if(o==null) return "-"; String n=g(o,"getName"); return n==null?o.getClass().getSimpleName():n; }
  static String fieldKindKo(Object f){ if(f==null)return null; switch(f.getClass().getSimpleName()){
    case "FieldData": return "데이터"; case "FieldFormula": return "공식"; case "FieldGlobalSpecial": return "시스템";
    case "FieldGlobalParameter": case "FieldParameter": case "FieldReportParameter": return "매개변수";
    case "FieldRunningTotal": return "누적합산"; case "FieldGroupName": return "그룹이름"; case "FieldGroupIndex": return "그룹인덱스";
    default: return f.getClass().getSimpleName(); } }
  static void inv(StringBuilder b,String label,RexObjectList<?> l){ if(l==null||l.size()==0)return; int max=l.size();
    b.append("  "+label+"필드("+l.size()+"): "); for(int i=0;i<max;i++){ b.append(nameOf(l.get(i))); if(i<max-1)b.append(", "); } if(l.size()>max)b.append(" …"); b.append("\n"); }

  public static void main(String[] x) throws Exception {
    mcp = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
    System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8"));
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
    System.err.println("[clip-report-mcp] started");
    String line;
    while((line=in.readLine())!=null){
      line=line.trim(); if(line.isEmpty()) continue;
      JSONObject req; try{ req=(JSONObject)P.parse(line); }catch(Exception e){ continue; }
      Object id=req.get("id"); String method=(String)req.get("method");
      if(method==null) continue;
      try{
        if(method.equals("initialize")) reply(id, initResult());
        else if(method.startsWith("notifications/")) { /* no response */ }
        else if(method.equals("tools/list")) reply(id, toolsList());
        else if(method.equals("tools/call")) reply(id, callTool((JSONObject)req.get("params")));
        else if(id!=null) error(id, -32601, "method not found: "+method);
      }catch(Throwable t){ if(id!=null) error(id, -32000, t.toString()); System.err.println("ERR "+t); }
    }
  }

  @SuppressWarnings("unchecked")
  static void reply(Object id, JSONObject result){ JSONObject o=new JSONObject(); o.put("jsonrpc","2.0"); o.put("id",id); o.put("result",result); mcp.println(o.toJSONString()); }
  @SuppressWarnings("unchecked")
  static void error(Object id, int code, String msg){ JSONObject e=new JSONObject(); e.put("code",(long)code); e.put("message",msg); JSONObject o=new JSONObject(); o.put("jsonrpc","2.0"); o.put("id",id); o.put("error",e); mcp.println(o.toJSONString()); }
  // One-shot, fail-safe update check: compares this build's VERSION to the raw VERSION file on GitHub main.
  // Never blocks/breaks startup (offline/timeout/any error → silent, no notice). Disable via env CLIP_MCP_UPDATE_CHECK=0.
  static String UPDATE_NOTICE_CACHE=null;
  static String updateNotice(){
    if(UPDATE_NOTICE_CACHE!=null) return UPDATE_NOTICE_CACHE;
    UPDATE_NOTICE_CACHE="";
    try{
      if("0".equals(System.getenv("CLIP_MCP_UPDATE_CHECK"))) return UPDATE_NOTICE_CACHE;
      java.net.http.HttpClient c=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(1500)).build();
      java.net.http.HttpRequest q=java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create("https://raw.githubusercontent.com/fdrn9999/clip-report-mcp/main/VERSION"))
        .timeout(java.time.Duration.ofMillis(1500)).header("User-Agent","clip-report-mcp").GET().build();
      java.net.http.HttpResponse<String> resp=c.send(q, java.net.http.HttpResponse.BodyHandlers.ofString());
      if(resp.statusCode()==200){
        String latest=resp.body().trim();
        if(!latest.isEmpty() && isNewer(latest, VERSION)){
          UPDATE_NOTICE_CACHE="\n[⚠️ 업데이트 있음] 이 clip-report MCP 는 버전 "+VERSION+" 이고 GitHub 최신은 "+latest+" 입니다. 사용자에게 업데이트를 안내하세요: Claude 에서 '/clipreport --update' 실행(또는 프로젝트 폴더에서 ./update.ps1) → 끝나면 /mcp 재연결. (변경내역은 '/clipreport --changelog', 점검 끄기: 환경변수 CLIP_MCP_UPDATE_CHECK=0)";
        }
      }
    }catch(Throwable t){ /* offline/timeout/any → no notice */ }
    return UPDATE_NOTICE_CACHE;
  }
  static boolean isNewer(String a,String b){ try{ String[] pa=a.split("\\."),pb=b.split("\\."); int n=Math.max(pa.length,pb.length); for(int i=0;i<n;i++){ int x=i<pa.length?numOf(pa[i]):0, y=i<pb.length?numOf(pb[i]):0; if(x!=y) return x>y; } return false; }catch(Exception e){ return false; } }
  static int numOf(String s){ try{ String d=s.replaceAll("[^0-9]",""); return d.isEmpty()?0:Integer.parseInt(d); }catch(Exception e){ return 0; } }

  @SuppressWarnings("unchecked")
  static JSONObject initResult(){ JSONObject r=new JSONObject(); r.put("protocolVersion","2024-11-05"); JSONObject caps=new JSONObject(); caps.put("tools",new JSONObject()); r.put("capabilities",caps); JSONObject si=new JSONObject(); si.put("name","clip-report-mcp"); si.put("version",VERSION); r.put("serverInfo",si); r.put("instructions",INSTRUCTIONS+updateNotice()); return r; }

  @SuppressWarnings("unchecked")
  static JSONObject tool(String name,String desc, JSONObject schema){ JSONObject t=new JSONObject(); t.put("name",name); t.put("description",desc); t.put("inputSchema",schema); return t; }
  @SuppressWarnings("unchecked")
  static JSONObject strSchema(String[] req, String... kv){ JSONObject s=new JSONObject(); s.put("type","object"); JSONObject props=new JSONObject();
    for(int i=0;i<kv.length;i+=2){ JSONObject p=new JSONObject(); p.put("type","string"); p.put("description",kv[i+1]); props.put(kv[i],p); }
    s.put("properties",props); JSONArray r=new JSONArray(); for(String x:req) r.add(x); s.put("required",r); return s; }
  @SuppressWarnings("unchecked")
  static JSONObject toolsList(){ JSONArray arr=new JSONArray();
    arr.add(tool("crf_summary","Read a CLIP report (.crf) and return its datasets, fields, query, groups, and section bands.",
        strSchema(new String[]{"path"}, "path","absolute path to the .crf file")));
    arr.add(tool("crf_generate","Generate a draft .crf from SQL or MyBatis: builds dataset fields, query (MyBatis->JavaScript), parameters, GROUP BY group bands, and the common page-footer logo. Writes a new file.",
        strSchema(new String[]{"template","sql","output"}, "template","path to a template .crf", "sql","the SQL or MyBatis query text", "output","path to write the generated .crf")));
    arr.add(tool("crf_set_query","Replace a dataset's query and save to a new file. scriptType is set automatically (plain SQL -> NotScript; MyBatis XML -> converted to JavaScript; JS `var sql=...` -> JavaScript). :col/#{}/${} become '{parameter.X}'. By default declares missing global parameters and ADDS data fields for new SELECT columns (parse-based; for SELECT * use crf_sync_fields mode=db).",
        strSchema(new String[]{"path","sql","output"}, "path","source .crf", "sql","new SQL / MyBatis <select> / JavaScript dynamic query (with {parameter.X} tokens)", "dataset","dataset name or 0-based index (default: first)", "script_type","auto|sql|javascript (default auto)", "declare_params","true|false: declare undeclared {parameter.X} as String global parameters (default true)", "sync_fields","none|add|replace: add missing SELECT columns as fields / also remove unreferenced fields not in SELECT (default add)", "output","destination .crf")));
    arr.add(tool("crf_sync_fields","Make a dataset's field list match its query columns. mode=sql parses the SELECT list; mode=db RUNS the query against the connected DB (wrapped in SELECT * FROM (...) WHERE 1=0, parameters bound from `params` or '' / NULL) and takes exact column names+types from ResultSetMetaData — use this for SELECT * or function/table columns. Adds missing fields; removes unreferenced extra fields only when remove_unused=true.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "dataset","dataset name or 0-based index (default: first)", "mode","sql|db (default sql)", "params","JSON object of parameter values for mode=db, e.g. {\"DEPTCD\":\"20399\"} (optional)", "set_types","true to set field DataType from DB/heuristics (default false = Null/auto)", "remove_unused","true to remove fields not in the query when nothing references them (default false)", "output","destination .crf")));
    arr.add(tool("crf_add_dataset","Add a new SQL dataset (connection copied from the first dataset) with the given query; declares parameters and creates fields like crf_set_query. Saves to a new file.",
        strSchema(new String[]{"path","name","sql","output"}, "path","source .crf", "name","new dataset name", "sql","query (SQL / MyBatis / JS)", "script_type","auto|sql|javascript (default auto)", "output","destination .crf")));
    arr.add(tool("crf_remove_dataset","Remove a dataset. Refuses if any of its fields is referenced (bindings, formulas...) unless force=true. Saves to a new file.",
        strSchema(new String[]{"path","dataset","output"}, "path","source .crf", "dataset","dataset name or 0-based index", "force","true to remove even if referenced (references become dangling)", "output","destination .crf")));
    arr.add(tool("crf_set_param","Create or update a global parameter (매개변수) used as {parameter.NAME} in queries: data type, default value, prompt. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","parameter name (e.g. DEPTCD)", "type","String|Number|Currency|DateTime|Boolean (default String; existing kept if omitted)", "default","default value (optional)", "prompt","prompt/label text (optional)", "output","destination .crf")));
    arr.add(tool("crf_remove_param","Remove a global parameter. Refuses if referenced (queries, bindings, formulas) unless force=true. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","parameter name", "force","true to remove anyway", "output","destination .crf")));
    arr.add(tool("crf_rename_field","Rename a field (data/formula/parameter/running-total). Object bindings follow automatically; formula scripts (\"ns.OLD\") and query tokens {parameter.OLD} are rewritten. Saves to a new file.",
        strSchema(new String[]{"path","name","new_name","output"}, "path","source .crf", "name","current field name", "new_name","new name", "dataset","dataset name/index when the same field name exists in several datasets (optional)", "output","destination .crf")));
    arr.add(tool("crf_remove_field","Remove a data/formula/running-total field. Lists every reference (cells, labels, groups, formulas, links) and refuses unless force=true. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","field name", "dataset","dataset name/index to disambiguate (optional)", "force","true to remove even if referenced", "output","destination .crf")));
    arr.add(tool("crf_field_refs","Show where a field (or parameter) is used: cell/label bindings, group fields, running totals, subreport links, formula scripts, query tokens. Read-only.",
        strSchema(new String[]{"path","name"}, "path",".crf file", "name","field / parameter name", "dataset","dataset name/index to disambiguate (optional)")));
    arr.add(tool("crf_list_reports","List .crf report files under a directory (recursive), with total count; filter by name.",
        strSchema(new String[]{"dir"}, "dir","directory to scan", "like","file-name filter: substring or glob with * (optional)", "limit","max files to list (default 500)")));
    arr.add(tool("crf_get_query","Return the FULL query text of a report's datasets: scriptType, connection, fields, used {parameter.X} (flags undeclared ones), {dataset.X} refs, estimated tables. JavaScript dynamic queries are also shown as reconstructed plain SQL (if-blocks as /*IF*/ comments). Use this to explain or find a report's SQL.",
        strSchema(new String[]{"path"}, "path",".crf file", "dataset","dataset name or 0-based index (default: all)", "mode","both|raw|plain — for JavaScript queries show original, plain reconstruction, or both (default both)")));
    arr.add(tool("crf_get_formula","Return formula field scripts (JavaScript), running-total definitions (function/field/reset), and group-name fields, with referenced fields and any references to missing fields.",
        strSchema(new String[]{"path"}, "path",".crf file", "name","one field name (default: all)")));
    arr.add(tool("crf_search","Search .crf files under a directory for text: in queries (plain-SQL view of JS queries), field names, formula scripts, parameters, or control/cell texts and bindings. E.g. find reports using table AHRM1234, parameter DEPTCD, or a label text.",
        strSchema(new String[]{"dir","text"}, "dir","directory to scan (recursive)", "text","text to find (case-insensitive substring; or a regex when regex=true)", "regex","true for regex (default false)", "scope","query|field|formula|param|control|any (default any)", "like","file-name filter: substring or glob (optional)", "limit","max matching files to report (default 50)", "max_files","max files to scan (default 5000)")));
    arr.add(tool("crf_describe_layout","Describe a report's section bands and the controls in each, including which field each control is bound to.",
        strSchema(new String[]{"path"}, "path",".crf file")));
    arr.add(tool("crf_add_group","Add a GROUP BY group (group header + footer bands) on a column to an existing report, and save to a new file.",
        strSchema(new String[]{"path","column","output"}, "path","source .crf", "column","field name to group by", "output","destination .crf")));
    arr.add(tool("crf_place_detail_fields","Place a field-bound data label in the DETAIL band for every field of the first dataset (a simple list row), and save to a new file.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "output","destination .crf")));
    arr.add(tool("crf_set_cell","Edit one table cell: bind it to a field (by name), and/or set static text, and/or set an output format. Use the table name and row/col from crf_describe_layout; cells shown as ‹병합› are merged-away and cannot be edited (edit the anchor cell). Saves to a new file.",
        strSchema(new String[]{"path","table","row","col","output"}, "path","source .crf", "table","ControlTable name (from describe_layout)", "row","row index (0-based)", "col","column index (0-based)", "field","field name to bind (optional)", "text","static text (optional)", "format","output format string e.g. #,##0 (optional)", "output","destination .crf")));
    arr.add(tool("crf_add_formula_field","Create a formula (computed) field with a JavaScript expression that MUST end with `return`. Refs via rexpert.field(\"data.COL\"); aggregates via rexpert.sum/avg/count/min/max(0,\"data.COL\",0,\"\",\"\"). Then bind it to a cell with crf_set_cell. Saves to a new file.",
        strSchema(new String[]{"path","name","script","output"}, "path","source .crf", "name","new formula field name", "script","JavaScript, MUST end with return;. Field ref=rexpert.field(\"ns.COL\") (ns=data/system/parameter/formula/runningtotal). Aggregate=rexpert.sum(범위,\"data.COL\",옵션,\"그룹|''\",\"조건식|''\"). e.g.  return rexpert.sum(0,\"data.PRVDD_BAL_AMT\",0,\"\",\"\");", "force","true to skip the return/bind-syntax checks (optional)", "output","destination .crf")));
    arr.add(tool("crf_set_cell_style","Style a table cell: background color (hex #RRGGBB), font name, can-grow, and merge-duplicate. Saves to a new file.",
        strSchema(new String[]{"path","table","row","col","output"}, "path","source .crf", "table","ControlTable name", "row","row index", "col","col index", "bgcolor","background hex #RRGGBB (optional)", "font","font name e.g. 굴림 (optional)", "cangrow","true/false (optional)", "merge","true/false: merge duplicate values (optional)", "output","destination .crf")));
    arr.add(tool("crf_add_data_field","Add a data field (column) to a dataset. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","field name", "type","String|Number|Currency|DateTime|Boolean (default String)", "dataset","dataset name or 0-based index (default: first)", "output","destination .crf")));
    arr.add(tool("crf_add_label","Add a 글상자(label) to a section band, bound to a field or with static text. Saves to a new file.",
        strSchema(new String[]{"path","section","output"}, "path","source .crf", "section","band: 보고서머리글|페이지머리글|데이터머리글|본문|데이터바닥글|페이지바닥글|보고서바닥글|그룹머리글|그룹바닥글 (or English ReportHeader/PageHeader/Detail/...)", "text","static text (optional)", "field","field name to bind (optional)", "left","X (optional)", "top","Y (optional)", "width","W (optional)", "height","H (optional)", "output","destination .crf")));
    arr.add(tool("crf_set_paper","Set paper type/orientation/margins. Saves to a new file.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "paper","A4|A3|B4|B5|Letter ... (optional)", "orientation","Potrait|Landscape (optional)", "marginL","left margin (optional)", "marginT","top (optional)", "marginR","right (optional)", "marginB","bottom (optional)", "output","destination .crf")));
    arr.add(tool("crf_diff","Compare two reports: datasets/fields/groups/sections and what was added or removed.",
        strSchema(new String[]{"a","b"}, "a","first .crf", "b","second .crf")));
    // ---- DB tools (도메인 테이블 조회로 업무지식 확보; 접속정보는 env CLIP_DB_URL/USER/PWD) ----
    arr.add(tool("db_query","Run SQL against the connected DB (Tibero/Oracle) and return rows as TSV (capped). SELECT 권장. Tibero: ROWNUM<=N, SYSDATE, DUAL.",
        strSchema(new String[]{"sql"}, "sql","SQL statement (SELECT/WITH only unless allow_write=true)", "max","max rows (default 200)", "allow_write","true to permit INSERT/UPDATE/DELETE/DDL (default false)")));
    arr.add(tool("db_tables","List DB tables whose name contains a keyword (ALL_TABLES). Empty keyword = my (USER) tables.",
        strSchema(new String[]{}, "like","table-name keyword, e.g. AHRMEV (optional)")));
    arr.add(tool("db_columns","Describe a table's columns: name, type, length, nullable, and COMMENT (업무 의미).",
        strSchema(new String[]{"table"}, "table","table name")));
    arr.add(tool("db_sample","Sample rows from a table (SELECT * WHERE ROWNUM<=N).",
        strSchema(new String[]{"table"}, "table","table name", "max","row count (default 20)")));
    arr.add(tool("pdf_text","Extract text from a PDF document (문서 양식/사양 읽기).",
        strSchema(new String[]{"path"}, "path","PDF file path")));
    JSONObject r=new JSONObject(); r.put("tools",arr); return r; }

  @SuppressWarnings("unchecked")
  static JSONObject textContent(String text){ JSONObject c=new JSONObject(); c.put("type","text"); c.put("text",text); JSONArray a=new JSONArray(); a.add(c); JSONObject r=new JSONObject(); r.put("content",a); if(text!=null && text.startsWith("ERROR")) r.put("isError",Boolean.TRUE); return r; }
  /** Tool failure -> MCP isError content (Claude sees the message), not a JSON-RPC error. */
  static String friendly(Throwable t){
    if(t instanceof FileNotFoundException) return "파일 없음/열 수 없음: "+(t.getMessage()==null||t.getMessage().isEmpty()?"(빈 경로)":t.getMessage());
    if(t instanceof NumberFormatException) return "숫자 인자 형식 오류: "+t.getMessage();
    if(t instanceof NullPointerException) return "필수 인자 누락 또는 리포트 구조가 예상과 다름 ("+t+")";
    if(t instanceof RuntimeException && t.getMessage()!=null && t.getClass()==RuntimeException.class) return t.getMessage();
    return t.toString();
  }
  static String s(JSONObject a,String k){ Object v=a.get(k); return v==null?null:v.toString(); }
  /** Read a .crf for a tool: clear message on missing/empty path. */
  static TheReportFile open(String path) throws IOException {
    if(path==null||path.trim().isEmpty()) throw new RuntimeException("path 인자가 비어 있습니다 (.crf 절대경로 필요)");
    File f=new File(path.trim()); if(!f.isFile()) throw new RuntimeException("파일 없음: "+path);
    return Rexpert4.read(f.getPath());
  }
  /** Write a .crf for a tool: output required, must differ from the input (원본 보존), parent dir auto-created. */
  static String save(TheReportFile rf,String output,String input) throws IOException {
    if(output==null||output.trim().isEmpty()) throw new RuntimeException("output 인자가 비어 있습니다 (원본 보존을 위해 별도 경로 필요, 예: <원본>_edited.crf)");
    File o=new File(output.trim());
    try{ if(input!=null && !input.trim().isEmpty() && o.getCanonicalPath().equalsIgnoreCase(new File(input.trim()).getCanonicalPath())) throw new RuntimeException("output 이 원본과 같습니다 — 원본 보존 규칙: 다른 경로(예 <원본>_edited.crf)를 지정하세요"); }catch(IOException e){}
    if(o.getParentFile()!=null && !o.getParentFile().isDirectory()) o.getParentFile().mkdirs();
    boolean existed=o.exists();
    Rexpert4.write(rf,o.getPath());
    return o.getPath()+(existed?" (기존 파일 덮어씀)":"");
  }

  @SuppressWarnings("unchecked")
  static JSONObject callTool(JSONObject params) throws Exception {
    String name=(String)params.get("name"); JSONObject args=(JSONObject)params.get("arguments"); if(args==null) args=new JSONObject();
    try{ return dispatch(name,args); }
    catch(Throwable t){ if(t instanceof IllegalArgumentException && String.valueOf(t.getMessage()).startsWith("unknown tool")) throw (IllegalArgumentException)t;
      System.err.println("TOOL ERR "+name+": "+t); return textContent("ERROR: "+friendly(t)); }
  }
  static JSONObject dispatch(String name,JSONObject args) throws Exception {
    switch(name){
      case "crf_summary":  return textContent(summary((String)args.get("path")));
      case "crf_generate": return textContent(runGen((String)args.get("template"),(String)args.get("sql"),(String)args.get("output")));
      case "crf_set_query":return textContent(setQuery(args));
      case "crf_list_reports":return textContent(listReports(args));
      case "crf_get_query":return textContent(getQuery(args));
      case "crf_get_formula":return textContent(getFormula(args));
      case "crf_search":return textContent(searchReports(args));
      case "crf_describe_layout":return textContent(describeLayout((String)args.get("path")));
      case "crf_add_group":return textContent(addGroup((String)args.get("path"),(String)args.get("column"),(String)args.get("output")));
      case "crf_place_detail_fields":return textContent(placeDetailFields((String)args.get("path"),(String)args.get("output")));
      case "crf_set_cell":return textContent(setCell(args));
      case "crf_add_formula_field":return textContent(addFormulaField(args));
      case "crf_set_cell_style":return textContent(setCellStyle(args));
      case "crf_add_data_field":return textContent(addDataField(args));
      case "crf_sync_fields":return textContent(syncFields(args));
      case "crf_add_dataset":return textContent(addDataset(args));
      case "crf_remove_dataset":return textContent(removeDataset(args));
      case "crf_set_param":return textContent(setParam(args));
      case "crf_remove_param":return textContent(removeParam(args));
      case "crf_rename_field":return textContent(renameField(args));
      case "crf_remove_field":return textContent(removeField(args));
      case "crf_field_refs":return textContent(fieldRefs(args));
      case "crf_add_label":return textContent(addLabel(args));
      case "crf_set_paper":return textContent(setPaper(args));
      case "crf_diff":return textContent(diff((String)args.get("a"),(String)args.get("b")));
      case "db_query":{ String sql=s(args,"sql"); if(sql==null||sql.trim().isEmpty()) return textContent("ERROR: sql 인자가 비어 있습니다");
        if(!"true".equalsIgnoreCase(s(args,"allow_write")) && !isReadOnlySql(sql)) return textContent("ERROR: db_query 는 SELECT/WITH 조회만 허용합니다. 쓰기 문장(INSERT/UPDATE/DELETE/DDL)은 allow_write=true 를 명시해야 합니다: "+sql.trim().replaceAll("\\s+"," ").substring(0,Math.min(80,sql.trim().length())));
        return textContent(runSql(sql, pInt(args.get("max"),200))); }
      case "db_tables":return textContent(dbTables((String)args.get("like")));
      case "db_columns":return textContent(dbColumns((String)args.get("table")));
      case "db_sample":{ int mx=pInt(args.get("max"),20); return textContent(runSql("SELECT * FROM "+safeName((String)args.get("table"))+" WHERE ROWNUM <= "+mx, mx+5)); }
      case "pdf_text":return textContent(pdfText((String)args.get("path")));
      default: throw new IllegalArgumentException("unknown tool "+name);
    }
  }

  static String runGen(String template,String sql,String output) throws Exception {
    if(template==null||!new File(template).isFile()) throw new RuntimeException("template 파일 없음: "+template);
    if(sql==null||sql.trim().isEmpty()) throw new RuntimeException("sql 인자가 비어 있습니다");
    if(output==null||output.trim().isEmpty()) throw new RuntimeException("output 인자가 비어 있습니다");
    if(new File(output).getCanonicalPath().equalsIgnoreCase(new File(template).getCanonicalPath())) throw new RuntimeException("output 이 template 과 같습니다 — 다른 경로를 지정하세요");
    Path tmp=Files.createTempFile("crfmcp",".sql"); Files.write(tmp, sql.getBytes("UTF-8"));
    ByteArrayOutputStream buf=new ByteArrayOutputStream(); PrintStream old=System.out;
    System.setOut(new PrintStream(buf,true,"UTF-8"));
    try{ CrfGen3.main(new String[]{template, tmp.toString(), output}); }
    finally{ System.setOut(old); Files.deleteIfExists(tmp); }
    return buf.toString("UTF-8");
  }

  @SuppressWarnings("unchecked")
  static String summary(String path) throws Exception {
    TheReportFile rf=open(path);
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    Report rep=rf.getGlobe().getMainReport();
    StringBuilder b=new StringBuilder();
    b.append("title=").append(rf.getTitle()).append("  version=").append(rf.getVersion()).append("\n");
    RexObjectList<DataSet> dss=gom.getDataSetList();
    b.append("datasets=").append(dss.size()).append("\n");
    for(int i=0;i<dss.size();i++){ DataSet ds=dss.get(i);
      RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
      b.append("  DS[").append(i).append("] ").append(ds.getName()).append("  fields(").append(fl.size()).append("): ");
      for(int j=0;j<fl.size();j++){ Object dt=fl.get(j).getDataType(); b.append(fl.get(j).getName()); if(dt!=null && !"Null".equals(dt.toString())) b.append(":").append(dt); b.append(j<fl.size()-1?", ":""); }
      b.append("\n");
      DataSetItemNormal n=ds.getDataSetItemNormal();
      if(n!=null && n.getDataAccessMethodSQL()!=null){ DataAccessMethodSQL q=n.getDataAccessMethodSQL();
        b.append("     scriptType=").append(q.getScriptType()).append("  queryLen=").append(q.getQueryString()==null?0:q.getQueryString().length()).append("\n"); }
    }
    // 용지(paper)
    MainPage mp0=rep.getReportDesign().getMainPage();
    b.append("용지: ").append(mp0.getPaperType()).append("  방향=").append(mp0.getPaperOrientationType())
     .append("  크기=").append(mp0.getPaperWidth()).append("x").append(mp0.getPaperHeight())
     .append("  여백 L").append(mp0.getLeftMargin()).append("/T").append(mp0.getTopMargin()).append("/R").append(mp0.getRightMargin()).append("/B").append(mp0.getBottomMargin()).append("\n");
    // 필드 인벤토리(분류별)
    var rom=rep.getReportObjectManager();
    { RexObjectList<?> gp=gom.getFieldGlobalParameterList(); if(gp!=null&&gp.size()>0){ b.append("  매개변수필드("+gp.size()+"): "); for(int i=0;i<gp.size();i++){ Object p=gp.get(i); String dt=g(p,"getDataType"), dv=g(p,"getDefaultValue"); b.append(i>0?", ":"").append(nameOf(p)); if(dt!=null&&!"Null".equals(dt)) b.append(":").append(dt); if(dv!=null&&!dv.isEmpty()) b.append("=\"").append(dv).append("\""); } b.append("\n"); } }
    inv(b,"시스템", gom.getFieldGlobalSpecialList());
    inv(b,"공식", rom.getFieldFormulaList());
    inv(b,"누적합산", rom.getFieldRunningTotalList());
    inv(b,"그룹이름", rom.getFieldGroupNameList());

    RexObjectList<Group> groups=rep.getReportObjectManager().getGroupList();
    b.append("groups=").append(groups.size());
    for(int i=0;i<groups.size();i++){ Field gf=groups.get(i).getGroupingField(); b.append(i==0?" [":",").append(gf==null?"-":gf.getName()); }
    if(groups.size()>0) b.append("]");
    b.append("\n");
    RexObjectList<Section> secs=rep.getReportDesign().getMainPage().getSectionList();
    b.append("sections(").append(secs.size()).append("):\n");
    for(int i=0;i<secs.size();i++){ Section sec=secs.get(i); String gf=groupFieldOf(sec); b.append("  [").append(i).append("] ").append(sec.getClass().getSimpleName().replace("Section","")).append(gf==null?"":"(→"+gf+")");
      RexObjectList<SubSection> ss=sec.getSubSectionList(); b.append(": "); for(int j=0;j<ss.size();j++) b.append(j>0?" | ":"").append(subSectionInfo(ss.get(j))); b.append("\n"); }
    return b.toString();
  }

  /** Reflective setter; a failure is an error (never a silent OK). */
  static void call(Object o,String m,Class<?> pt,Object arg){ try{ o.getClass().getMethod(m,pt).invoke(o,arg); }
    catch(NoSuchMethodException e){ throw new RuntimeException("속성 '"+m+"' 을(를) "+o.getClass().getSimpleName()+" 에 설정할 수 없습니다 (지원 안 함)"); }
    catch(Exception e){ Throwable c=e instanceof java.lang.reflect.InvocationTargetException&&e.getCause()!=null?e.getCause():e; throw new RuntimeException("속성 '"+m+"' 설정 실패: "+c); } }
  static Object tableCell(Control tbl,int r,int c){ try{ return tbl.getClass().getMethod("getTableCell",int.class,int.class).invoke(tbl,r,c); }catch(Exception e){ return null; } }
  static boolean isNormalCell(Object cell){ return cell!=null && cell.getClass().getSimpleName().equals("TableCellNormal"); }
  /** Table cell for editing: range-checked; a merged-away (TableCellDumy) cell is an error with a hint to the anchor cell. */
  static Object cellOf(Control tbl,int row,int col){
    Object rc=go(tbl,"getRowCount"), cc=go(tbl,"getColumnCount"); int rows=rc instanceof Integer?(Integer)rc:0, cols=cc instanceof Integer?(Integer)cc:0;
    if(row<0||col<0||row>=rows||col>=cols) throw new RuntimeException("셀 ["+row+","+col+"] 범위 밖 — 표 '"+tbl.getName()+"' 은 "+rows+"행×"+cols+"열 (0-based)");
    Object cell=tableCell(tbl,row,col); if(cell==null) throw new RuntimeException("셀 ["+row+","+col+"] 없음");
    if(!isNormalCell(cell)){ String hint="";
      for(int c=col-1;c>=0&&hint.isEmpty();c--) if(isNormalCell(tableCell(tbl,row,c))) hint=" 기준 셀 후보: ["+row+","+c+"]";
      for(int r=row-1;r>=0&&hint.isEmpty();r--) if(isNormalCell(tableCell(tbl,r,col))) hint=" 기준 셀 후보: ["+r+","+col+"]";
      throw new RuntimeException("셀 ["+row+","+col+"] 은 병합된(숨은) 셀이라 설정할 수 없습니다."+hint+" (crf_describe_layout 의 ‹병합› 표시 참고)"); }
    return cell;
  }

  @SuppressWarnings("unchecked")
  static Field findField(TheReportFile rf,String name){
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    var rom=rf.getGlobe().getMainReport().getReportObjectManager();
    java.util.List<RexObjectList<?>> lists=new java.util.ArrayList<>();
    RexObjectList<DataSet> dss=gom.getDataSetList();
    for(int i=0;i<dss.size();i++) lists.add((RexObjectList<?>) dss.get(i).getFieldDataList());
    lists.add((RexObjectList<?>) rom.getFieldDataList()); lists.add((RexObjectList<?>) rom.getFieldFormulaList());
    lists.add((RexObjectList<?>) rom.getFieldRunningTotalList()); lists.add((RexObjectList<?>) rom.getFieldGroupNameList());
    lists.add((RexObjectList<?>) gom.getFieldGlobalParameterList()); lists.add((RexObjectList<?>) gom.getFieldGlobalSpecialList());
    for(RexObjectList<?> l: lists){ if(l==null)continue; for(int i=0;i<l.size();i++){ Object o=l.get(i); if(name.equalsIgnoreCase(nameOf(o))) return (Field)o; } }
    return null;
  }
  static Control findTable(TheReportFile rf,String name){
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    java.util.List<Control> all=new java.util.ArrayList<>();
    for(int i=0;i<secs.size();i++) collectControls(secs.get(i),all,Collections.newSetFromMap(new IdentityHashMap<>()),0);
    for(Control c: all) if("ControlTable".equals(c.getClass().getSimpleName()) && name.equals(c.getName())) return c;
    return null;
  }
  static String setCell(JSONObject args) throws Exception {
    String path=(String)args.get("path"), table=(String)args.get("table"), output=(String)args.get("output");
    int row=Integer.parseInt(((String)args.get("row")).trim()), col=Integer.parseInt(((String)args.get("col")).trim());
    String field=(String)args.get("field"), text=(String)args.get("text"), format=(String)args.get("format");
    TheReportFile rf=open(path);
    Control tbl=findTable(rf,table); if(tbl==null) return "ERROR: table '"+table+"' not found (use crf_describe_layout for names)";
    Object cell=cellOf(tbl,row,col);
    StringBuilder did=new StringBuilder();
    if(field!=null && !field.isEmpty()){ Field f=findField(rf,field); if(f==null) return "ERROR: field '"+field+"' not found";
      call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Field); call(cell,"setApplyValueField",Field.class,f); did.append(" field="+field+"("+fieldKindKo(f)+")"); }
    else if(text!=null){ call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Text); call(cell,"setApplyValueText",String.class,text); did.append(" text=\""+text+"\""); }
    if(format!=null && !format.isEmpty()){ call(cell,"setOutputFormat",String.class,format); did.append(" format="+format); }
    if(did.length()==0) return "ERROR: nothing to set (provide field, text, or format)";
    String wrote=save(rf,output,path);
    // verify by re-reading the written file
    TheReportFile v=open(output); Control vt=findTable(v,table); Object vc=vt==null?null:cellOf(vt,row,col);
    Object vf=go(vc,"getApplyValueField"); String vtxt=g(vc,"getApplyValueText"), vfmt=g(vc,"getOutputFormat");
    String ver=" verified["+(vf!=null?"field="+nameOf(vf):"text=\""+vtxt+"\"")+(vfmt!=null&&!vfmt.isEmpty()?" format="+vfmt:"")+"]";
    if(field!=null && !field.isEmpty() && (vf==null || !field.equalsIgnoreCase(nameOf(vf)))) return "ERROR: 저장 후 되읽기 검증 실패 — 셀 바인딩이 반영되지 않음"+ver;
    if(format!=null && !format.isEmpty() && !format.equals(vfmt)) return "ERROR: 저장 후 되읽기 검증 실패 — 출력양식이 반영되지 않음"+ver;
    return "OK: "+table+"["+row+","+col+"] set"+did+ver+", wrote "+wrote;
  }
  @SuppressWarnings("unchecked")
  static String addFormulaField(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), script=s(args,"script"), output=s(args,"output"); boolean force="true".equalsIgnoreCase(s(args,"force"));
    if(name==null||name.trim().isEmpty()) return "ERROR: name 인자가 비어 있습니다";
    if(script==null||script.trim().isEmpty()) return "ERROR: script 인자가 비어 있습니다";
    name=name.trim();
    TheReportFile rf=open(path);
    Field dup=findField(rf,name); if(dup!=null) return "ERROR: 이름 중복 — '"+name+"' 은 이미 "+fieldKindKo(dup)+" 필드로 존재합니다. 다른 이름을 쓰거나 기존 필드를 사용하세요";
    if(!java.util.regex.Pattern.compile("\\breturn\\b").matcher(script).find() && !force) return "ERROR: 공식 스크립트에 return 이 없습니다 (CLIP 공식은 JavaScript 식 끝에 return 필수, 예: return rexpert.field(\"data.COL\");). 의도된 것이면 force=true";
    if(java.util.regex.Pattern.compile("(?<![\\w'\"])(:[A-Za-z_]\\w*|#\\{|\\$\\{)").matcher(script).find() && !force) return "ERROR: 공식 안에 :col/#{}/${} 바인드 표기가 있습니다 — 공식에서는 rexpert.field(\"data.COL\") 로 참조하세요 (그 표기는 쿼리 파라미터 전용). 의도된 것이면 force=true";
    var rom=rf.getGlobe().getMainReport().getReportObjectManager();
    FieldFormula ff=new FieldFormula(); ff.setName(name); ff.setScript(script); ff.setScriptType(ScriptType.JavaScript);
    ((RexObjectList<FieldFormula>) rom.getFieldFormulaList()).add(ff);
    String wrote=save(rf,output,path);
    TheReportFile v=open(output);
    int n=v.getGlobe().getMainReport().getReportObjectManager().getFieldFormulaList().size();
    return "OK: added formula field '"+name+"' = "+script+" (formula fields now "+n+"), wrote "+wrote;
  }

  static int parseColor(String s){ s=s.trim().replace("#",""); if(s.matches("[0-9a-fA-F]{6}")){ int r=Integer.parseInt(s.substring(0,2),16),g=Integer.parseInt(s.substring(2,4),16),b=Integer.parseInt(s.substring(4,6),16); return (b<<16)|(g<<8)|r; } return Integer.parseInt(s); }
  static int pInt(Object o,int def){ try{ return Integer.parseInt(((String)o).trim()); }catch(Exception e){ return def; } }

  static String setCellStyle(JSONObject args) throws Exception {
    String path=(String)args.get("path"), table=(String)args.get("table"), output=(String)args.get("output");
    int row=pInt(args.get("row"),0), col=pInt(args.get("col"),0);
    String bg=(String)args.get("bgcolor"), font=(String)args.get("font"), cg=(String)args.get("cangrow"), mg=(String)args.get("merge");
    TheReportFile rf=open(path);
    Control tbl=findTable(rf,table); if(tbl==null) return "ERROR: table '"+table+"' not found";
    Object cell=cellOf(tbl,row,col);
    StringBuilder did=new StringBuilder();
    if(bg!=null && !bg.isEmpty()){ call(cell,"setBackStyle",BackStyleType.class,BackStyleType.Normal); call(cell,"setBackColor",int.class,parseColor(bg)); did.append(" 배경="+bg); }
    if(font!=null && !font.isEmpty()){ Object ti=go(cell,"getTextInfo"); if(ti!=null){ call(ti,"setFontName",String.class,font); did.append(" 폰트="+font); } }
    if(cg!=null){ call(cell,"setCanGrow",boolean.class,Boolean.parseBoolean(cg)); did.append(" 확장가능="+cg); }
    if(mg!=null){ call(cell,"setCellMergeRowDataDuplication",boolean.class,Boolean.parseBoolean(mg)); did.append(" 셀합치기="+mg); }
    if(did.length()==0) return "ERROR: nothing to set (bgcolor/font/cangrow/merge)";
    save(rf,output,path); return "OK: "+table+"["+row+","+col+"] style"+did+", wrote "+output;
  }

  @SuppressWarnings("unchecked")
  static String addDataField(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), type=s(args,"type"), output=s(args,"output");
    if(name==null||name.trim().isEmpty()) return "ERROR: name 인자가 비어 있습니다"; name=name.trim();
    TheReportFile rf=open(path);
    DataSet ds=datasetOf(rf,s(args,"dataset"));
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    for(int i=0;i<fl.size();i++) if(name.equalsIgnoreCase(fl.get(i).getName())) return "ERROR: 이름 중복 — 데이터셋 "+ds.getName()+" 에 '"+fl.get(i).getName()+"' 필드가 이미 있습니다";
    Field other=findField(rf,name); String warn=other!=null?" ⚠ 같은 이름의 "+fieldKindKo(other)+" 필드가 다른 목록에 있음(공식/매개변수에서 혼동 주의)":"";
    FieldData f=new FieldData(); f.setName(name); try{ f.setDataType(DataType.valueOf(type==null?"String":type)); }catch(Exception e){ f.setDataType(DataType.String); } f.setIndex(fl.size()); fl.add(f);
    String wrote=save(rf,output,path);
    return "OK: added data field '"+name+"' ("+f.getDataType()+") to "+ds.getName()+", wrote "+wrote+warn;
  }

  static Section findSection(TheReportFile rf,String key){
    String k=key==null?"":key.replaceAll("\\s","").trim(); String eng=k;
    switch(k){ case "보고서머리글": eng="ReportHeader"; break; case "보고서바닥글": eng="ReportFooter"; break;
      case "페이지머리글": eng="PageHeader"; break; case "페이지바닥글": eng="PageFooter"; break;
      case "데이터머리글": eng="DataHeader"; break; case "데이터바닥글": eng="DataFooter"; break;
      case "본문": eng="Detail"; break; case "그룹머리글": eng="GroupHeader"; break; case "그룹바닥글": eng="GroupFooter"; break; }
    String want=("Section"+eng).toLowerCase();
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    for(int i=0;i<secs.size();i++) if(secs.get(i).getClass().getSimpleName().toLowerCase().equals(want)) return secs.get(i);
    for(int i=0;i<secs.size();i++) if(secs.get(i).getClass().getSimpleName().toLowerCase().contains(eng.toLowerCase())) return secs.get(i);
    return null;
  }
  static String engSection(String key){ String k=key==null?"":key.replaceAll("\\s","").trim(); switch(k){
    case "보고서머리글": return "ReportHeader"; case "보고서바닥글": return "ReportFooter"; case "페이지머리글": return "PageHeader";
    case "페이지바닥글": return "PageFooter"; case "데이터머리글": return "DataHeader"; case "데이터바닥글": return "DataFooter";
    case "본문": return "Detail"; case "그룹머리글": return "GroupHeader"; case "그룹바닥글": return "GroupFooter"; default: return k; } }
  static int rank(String simple){ switch(simple){ case "SectionReportHeader": return 0; case "SectionPageHeader": return 1; case "SectionDataHeader": return 2;
    case "SectionGroupHeader": return 3; case "SectionDetail": return 4; case "SectionGroupFooter": return 5; case "SectionDataFooter": return 6;
    case "SectionPageFooter": return 7; case "SectionReportFooter": return 8; default: return 4; } }
  @SuppressWarnings("unchecked")
  static Section findOrCreateSection(TheReportFile rf,String key){
    Section s=findSection(rf,key); if(s!=null) return s;
    String eng=engSection(key); if(eng.toLowerCase().contains("group")) return null;   // group bands need a Group (use crf_add_group)
    Section ns; try{ ns=(Section)Class.forName("com.clipsoft.clipreport.base.sections.Section"+eng).getDeclaredConstructor().newInstance(); }catch(Exception e){ return null; }
    ns.getSubSectionList().add(band("새 "+key,80));
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    int myr=rank("Section"+eng), idx=secs.size();
    for(int i=0;i<secs.size();i++) if(rank(secs.get(i).getClass().getSimpleName())>myr){ idx=i; break; }
    secs.add(idx,ns); return ns;
  }
  @SuppressWarnings("unchecked")
  static String addLabel(JSONObject args) throws Exception {
    String path=(String)args.get("path"), section=(String)args.get("section"), output=(String)args.get("output");
    String text=(String)args.get("text"), field=(String)args.get("field");
    TheReportFile rf=open(path);
    Section sec=findOrCreateSection(rf,section); if(sec==null) return "ERROR: section '"+section+"' not found (그룹 밴드는 crf_add_group 사용)";
    SubSectionDefault sd=firstSub(sec); if(sd==null) return "ERROR: section has no subsection";
    RexObjectList<Control> cl=(RexObjectList<Control>) clpOf(sd).getControlList();
    ControlLabel c=new ControlLabel(); c.setName(uniqueControlName(rf,"label_"+(field!=null?field:"text"))); c.setVisible(true);
    c.setX1(pInt(args.get("left"),0)); c.setY1(pInt(args.get("top"),0)); c.setWidth(pInt(args.get("width"),500)); c.setHeight(pInt(args.get("height"),80));
    String did;
    if(field!=null && !field.isEmpty()){ Field f=findField(rf,field); if(f==null) return "ERROR: field '"+field+"' not found"; c.setApplyValueType(ApplyValueType.Field); c.setApplyValueField(f); did="field="+field; }
    else { c.setApplyValueType(ApplyValueType.Text); c.setApplyValueText(text==null?"":text); did="text=\""+(text==null?"":text)+"\""; }
    cl.add(c); save(rf,output,path);
    return "OK: added label("+did+") to "+sec.getClass().getSimpleName().replace("Section","")+", wrote "+output;
  }

  static String setPaper(JSONObject args) throws Exception {
    String path=(String)args.get("path"), output=(String)args.get("output");
    TheReportFile rf=open(path); MainPage mp=rf.getGlobe().getMainReport().getReportDesign().getMainPage();
    StringBuilder did=new StringBuilder();
    String pp=(String)args.get("paper"); if(pp!=null && !pp.isEmpty()){ try{ mp.setPaperType(com.clipsoft.clipreport.common.enums.PaperType.valueOf(pp)); did.append(" 용지="+pp); }catch(Exception e){ return "ERROR: unknown paper '"+pp+"'"; } }
    String or=(String)args.get("orientation"); if(or!=null && !or.isEmpty()){ try{ mp.setPaperOrientationType(com.clipsoft.clipreport.common.enums.PaperOrientation.valueOf(or)); mp.setPaperOrientationUse(true); did.append(" 방향="+or); }catch(Exception e){ return "ERROR: unknown orientation '"+or+"' (Potrait|Landscape)"; } }
    if(args.get("marginL")!=null){ mp.setLeftMargin(pInt(args.get("marginL"),mp.getLeftMargin())); did.append(" L"+mp.getLeftMargin()); }
    if(args.get("marginT")!=null){ mp.setTopMargin(pInt(args.get("marginT"),mp.getTopMargin())); did.append(" T"+mp.getTopMargin()); }
    if(args.get("marginR")!=null){ mp.setRightMargin(pInt(args.get("marginR"),mp.getRightMargin())); did.append(" R"+mp.getRightMargin()); }
    if(args.get("marginB")!=null){ mp.setBottomMargin(pInt(args.get("marginB"),mp.getBottomMargin())); did.append(" B"+mp.getBottomMargin()); }
    if(did.length()==0) return "ERROR: nothing to set";
    save(rf,output,path); return "OK: paper"+did+", wrote "+output;
  }

  @SuppressWarnings("unchecked")
  static java.util.Set<String> dataFieldNames(TheReportFile rf){ java.util.Set<String> s=new java.util.TreeSet<>();
    RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    for(int i=0;i<dss.size();i++){ RexObjectList<FieldData> fl=(RexObjectList<FieldData>) dss.get(i).getFieldDataList(); for(int j=0;j<fl.size();j++) s.add(fl.get(j).getName()); } return s; }
  static String sectionsOf(TheReportFile rf){ RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList(); StringBuilder b=new StringBuilder(); for(int i=0;i<secs.size();i++) b.append(secs.get(i).getClass().getSimpleName().replace("Section","")).append(i<secs.size()-1?",":""); return b.toString(); }
  static String diff(String a,String b) throws Exception {
    TheReportFile A=open(a), B=open(b);
    java.util.Set<String> fa=dataFieldNames(A), fb=dataFieldNames(B);
    java.util.Set<String> added=new java.util.TreeSet<>(fb); added.removeAll(fa);
    java.util.Set<String> removed=new java.util.TreeSet<>(fa); removed.removeAll(fb);
    int ga=A.getGlobe().getMainReport().getReportObjectManager().getGroupList().size();
    int gb=B.getGlobe().getMainReport().getReportObjectManager().getGroupList().size();
    StringBuilder s=new StringBuilder();
    s.append("A: ").append(new File(a).getName()).append("\nB: ").append(new File(b).getName()).append("\n");
    s.append("필드 추가(B): ").append(added.isEmpty()?"-":added).append("\n");
    s.append("필드 삭제(A에만): ").append(removed.isEmpty()?"-":removed).append("\n");
    s.append("그룹 수: A=").append(ga).append("  B=").append(gb).append("\n");
    s.append("섹션 A: ").append(sectionsOf(A)).append("\n섹션 B: ").append(sectionsOf(B));
    return s.toString();
  }

  // ---- DB (Tibero/Oracle via JDBC; 접속정보는 env, 비밀번호 코드 미포함) ----
  static String safeName(String s){ return s==null?"":s.replaceAll("[^A-Za-z0-9_$.]","").toUpperCase(); }
  // .env 자동 로드(내장): 환경변수 우선, 없으면 ~/clip-report-mcp/.env → ~/.dbtools/.env → ./.env 순서.
  static java.util.Map<String,String> loadDotEnv(){
    java.util.Map<String,String> m=new java.util.LinkedHashMap<>();
    String home=System.getProperty("user.home");
    String[] cands={ System.getenv("CLIP_ENV_FILE"), home+"/clip-report-mcp/.env", home+"/.dbtools/.env", ".env" };
    for(String c: cands){ if(c==null) continue; File f=new File(c); if(!f.isFile()) continue;
      try{ for(String ln: Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)){
        String t=ln.trim(); if(t.isEmpty()||t.startsWith("#")||!t.contains("=")) continue;
        int i=t.indexOf('='); String k=t.substring(0,i).trim(); String v=t.substring(i+1).trim();
        if(v.length()>=2 && ((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'")))) v=v.substring(1,v.length()-1);
        m.put(k,v);
      } m.put("__source", f.getPath()); break; }catch(Exception ex){} }
    return m;
  }
  static Connection db() throws Exception {
    String url=System.getenv("CLIP_DB_URL"), user=System.getenv("CLIP_DB_USER"), pwd=System.getenv("CLIP_DB_PWD");
    if(url==null||user==null||pwd==null){ java.util.Map<String,String> e=loadDotEnv();
      if(url==null) url=e.get("CLIP_DB_URL"); if(user==null) user=e.get("CLIP_DB_USER"); if(pwd==null) pwd=e.get("CLIP_DB_PWD"); }
    if(url==null||url.isEmpty()||user==null) throw new RuntimeException("DB 미설정: ~/.dbtools/.env (또는 ~/clip-report-mcp/.env) 에 CLIP_DB_URL / CLIP_DB_USER / CLIP_DB_PWD 를 넣으세요. (동명 환경변수도 가능)");
    for(String drv: new String[]{"com.tmax.tibero.jdbc.TbDriver","oracle.jdbc.OracleDriver"}){ try{ Class.forName(drv); }catch(Throwable t){} }
    return DriverManager.getConnection(url, user, pwd==null?"":pwd);
  }
  /** true if the statement is a read (SELECT/WITH/EXPLAIN...) after stripping leading comments. */
  static boolean isReadOnlySql(String sql){ String t=sql.replaceAll("(?s)/\\*.*?\\*/"," ").replaceAll("(?m)--.*$"," ").trim().toUpperCase();
    return t.startsWith("SELECT")||t.startsWith("WITH")||t.startsWith("EXPLAIN")||t.startsWith("DESC")||t.startsWith("SHOW"); }
  static int DB_TIMEOUT_SEC=Integer.parseInt(System.getenv().getOrDefault("CLIP_DB_TIMEOUT","60"));
  static String runSql(String sql,int maxRows) throws Exception {
    StringBuilder b=new StringBuilder();
    try(Connection c=db(); Statement st=c.createStatement()){
      try{ st.setQueryTimeout(DB_TIMEOUT_SEC); }catch(Throwable t){}
      boolean hasRs=st.execute(sql);
      if(!hasRs) return "(updateCount="+st.getUpdateCount()+")";
      try(ResultSet rs=st.getResultSet()){
        ResultSetMetaData m=rs.getMetaData(); int n=m.getColumnCount(); int rows=0;
        for(int i=1;i<=n;i++){ if(i>1)b.append("\t"); b.append(m.getColumnLabel(i)); } b.append("\n");
        while(rs.next()){ for(int i=1;i<=n;i++){ if(i>1)b.append("\t"); String v=rs.getString(i); b.append(v==null?"":v.replace("\t"," ").replace("\n"," ").replace("\r"," ")); } b.append("\n");
          if(++rows>=maxRows){ b.append("... ("+maxRows+"행에서 잘림)\n"); break; } }
        b.append("("+rows+" rows)");
      }
    }
    return b.toString();
  }
  static String dbTables(String like) throws Exception {
    String k=like==null?"":like.toUpperCase().replaceAll("[^A-Za-z0-9_%]","");
    String sql = k.isEmpty() ? "SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME"
      : "SELECT OWNER, TABLE_NAME FROM ALL_TABLES WHERE TABLE_NAME LIKE '%"+k+"%' ORDER BY OWNER, TABLE_NAME";
    return runSql(sql, 500);
  }
  static String dbColumns(String table) throws Exception {
    String t=safeName(table);
    String sql="SELECT c.COLUMN_NAME, c.DATA_TYPE, c.DATA_LENGTH, c.NULLABLE, cc.COMMENTS "+
      "FROM ALL_TAB_COLUMNS c LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER=c.OWNER AND cc.TABLE_NAME=c.TABLE_NAME AND cc.COLUMN_NAME=c.COLUMN_NAME "+
      "WHERE c.TABLE_NAME='"+t+"' ORDER BY c.COLUMN_ID";
    return runSql(sql, 500);
  }
  // ---- PDF 텍스트 추출 (번들 pdfbox, 리플렉션으로 버전 비의존) ----
  static String pdfText(String path) throws Exception {
    Class<?> docC=Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
    Object d=docC.getMethod("load", File.class).invoke(null, new File(path));
    try{
      Class<?> stripC=Class.forName("org.apache.pdfbox.text.PDFTextStripper");
      Object s=stripC.getDeclaredConstructor().newInstance();
      String txt=(String) stripC.getMethod("getText", docC).invoke(s, d);
      if(txt==null) txt="";
      return txt.length()>20000 ? txt.substring(0,20000)+"\n...(20000자에서 잘림)" : txt;
    } finally { try{ docC.getMethod("close").invoke(d); }catch(Exception e){} }
  }

  /** Dataset by name (case-insensitive) or 0-based index; null/empty -> first. */
  static DataSet datasetOf(TheReportFile rf,String sel){
    RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    if(dss.size()==0) throw new RuntimeException("리포트에 데이터셋이 없습니다");
    if(sel==null||sel.trim().isEmpty()) return dss.get(0);
    String k=sel.trim(); for(int i=0;i<dss.size();i++) if(k.equalsIgnoreCase(dss.get(i).getName())) return dss.get(i);
    try{ int i=Integer.parseInt(k); if(i>=0&&i<dss.size()) return dss.get(i); }catch(NumberFormatException e){}
    StringBuilder names=new StringBuilder(); for(int i=0;i<dss.size();i++) names.append(i==0?"":", ").append(i).append(":").append(dss.get(i).getName());
    throw new RuntimeException("데이터셋 '"+sel+"' 없음 — 사용 가능: "+names);
  }
  static boolean looksLikeJsQuery(String q){ return q!=null && java.util.regex.Pattern.compile("(?s)\\bvar\\s+\\w+\\s*=\\s*\"|\\w+\\s*\\+=\\s*\"|\\n\\s*\\+\\s*\"").matcher(q).find(); }
  static boolean looksLikeMyBatis(String q){ return q!=null && java.util.regex.Pattern.compile("(?is)<(if|where|foreach|choose|trim|set|select)\\b").matcher(q).find(); }
  /** Result of applying a query to a dataset (shared by crf_set_query / crf_add_dataset). */
  static class QueryApply { String conv; ScriptType before, after; java.util.List<String> warns=new java.util.ArrayList<>(), declared=new java.util.ArrayList<>(), added=new java.util.ArrayList<>(), removed=new java.util.ArrayList<>(), keptRef=new java.util.ArrayList<>(), skipped=new java.util.ArrayList<>(); java.util.Set<String> undeclared=new java.util.TreeSet<>(); boolean noColumns; }
  static DataType dataTypeOrNull(String t){ try{ return DataType.valueOf(t); }catch(Exception e){ return DataType.String; } }
  static DataType nullType(){ try{ return DataType.valueOf("Null"); }catch(Exception e){ return DataType.String; } }
  @SuppressWarnings("unchecked")
  static FieldGlobalParameter addGlobalParam(TheReportFile rf,String name,DataType type,String def,String prompt){
    FieldGlobalParameter fp=new FieldGlobalParameter(); fp.setName(name); fp.setDataType(type==null?DataType.String:type); fp.setDefaultValue(def==null?"":def); fp.setValueIsNull(Boolean.FALSE); fp.setPrompt(prompt==null?name:prompt); fp.setTag("");
    ((RexObjectList<FieldGlobalParameter>)(RexObjectList<?>) rf.getGlobe().getGlobalObjectManager().getFieldGlobalParameterList()).add(fp); return fp; }
  static FieldData findDataField(DataSet ds,String name){ RexObjectList<?> fl=ds.getFieldDataList(); for(int i=0;i<fl.size();i++) if(name.equalsIgnoreCase(nameOf(fl.get(i)))) return (FieldData)fl.get(i); return null; }
  @SuppressWarnings("unchecked")
  static FieldData addDataFieldTo(DataSet ds,String name,DataType type){ RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList(); FieldData f=new FieldData(); f.setName(name); f.setDataType(type==null?nullType():type); f.setIndex(fl.size()); fl.add(f); return f; }
  static boolean validIdent(String n){ return n!=null && n.matches("[A-Za-z_가-힣][A-Za-z0-9_가-힣$#]*"); }
  /** Convert + set the query on a dataset; optionally declare missing parameters and sync fields from the SELECT list. */
  @SuppressWarnings("unchecked")
  static QueryApply applyQuery(TheReportFile rf,DataSet ds,String sql,String mode,boolean declare,String sync){
    QueryApply r=new QueryApply();
    DataAccessMethodSQL q=ds.getDataSetItemNormal()==null?null:ds.getDataSetItemNormal().getDataAccessMethodSQL();
    if(q==null) throw new RuntimeException("데이터셋 "+ds.getName()+" 은 SQL 데이터셋이 아닙니다");
    r.before=q.getScriptType(); String m=mode==null||mode.trim().isEmpty()?"auto":mode.trim().toLowerCase(); String conv; ScriptType after;
    if(m.equals("auto")){ if(looksLikeMyBatis(sql)){ conv=CrfGen2.mybatisToJs(sql,r.warns); after=ScriptType.JavaScript; } else if(looksLikeJsQuery(sql)){ conv=CrfGen2.subParamsQuoted(sql); after=ScriptType.JavaScript; } else { conv=CrfGen2.subParamsQuoted(CrfGen2.stripComments(sql)).trim(); after=ScriptType.NotScript; } }
    else if(m.equals("javascript")||m.equals("js")){ conv=looksLikeMyBatis(sql)?CrfGen2.mybatisToJs(sql,r.warns):CrfGen2.subParamsQuoted(sql); after=ScriptType.JavaScript; }
    else if(m.equals("sql")){ conv=CrfGen2.subParamsQuoted(sql); after=ScriptType.NotScript; }
    else throw new RuntimeException("script_type 은 auto|sql|javascript 중 하나");
    conv=CrfGen2.normParamTokens(conv); q.setQueryString(conv); q.setScriptType(after); r.conv=conv; r.after=after;
    java.util.Set<String> declaredNames=declaredParams(rf);
    for(String u: usedParams(conv)) if(!declaredNames.contains(u.toUpperCase())){ if(declare){ addGlobalParam(rf,u,DataType.String,"",u); declaredNames.add(u.toUpperCase()); r.declared.add(u); } else r.undeclared.add(u); }
    String sy=sync==null||sync.trim().isEmpty()?"add":sync.trim().toLowerCase();
    if(!sy.equals("none")){ String plain=after==ScriptType.JavaScript?jsToPlainSql(conv):conv; java.util.List<String> cols=CrfGen2.parseColumns(CrfGen2.stripComments(plain));
      if(cols.isEmpty()) r.noColumns=true;
      else { java.util.Set<String> want=new java.util.HashSet<>();
        for(String c: cols){ if(c.matches("COL_\\d+")||!validIdent(c)){ r.skipped.add(c); continue; } want.add(c.toUpperCase()); if(findDataField(ds,c)==null){ addDataFieldTo(ds,c,nullType()); r.added.add(c); } }
        if(sy.equals("replace")){ RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList(); for(int i=fl.size()-1;i>=0;i--){ FieldData f=fl.get(i); if(want.contains(f.getName().toUpperCase())) continue; java.util.List<String> refs=refsOf(rf,f); if(refs.isEmpty()){ fl.remove(i); r.removed.add(f.getName()); } else r.keptRef.add(f.getName()+"("+refs.size()+"곳 참조)"); } } } }
    return r;
  }
  static String applySummary(QueryApply r){ StringBuilder b=new StringBuilder();
    if(!r.declared.isEmpty()) b.append("\n+ 매개변수 선언: ").append(r.declared).append(" (String, 기본값 ''; 타입/기본값은 crf_set_param 으로)");
    if(!r.undeclared.isEmpty()) b.append("\n⚠ 쿼리가 쓰는데 선언되지 않은 매개변수: ").append(r.undeclared).append(" (declare_params=true 또는 crf_set_param)");
    if(!r.added.isEmpty()) b.append("\n+ 필드 추가: ").append(r.added);
    if(!r.removed.isEmpty()) b.append("\n- 필드 제거(미참조): ").append(r.removed);
    if(!r.keptRef.isEmpty()) b.append("\n⚠ SELECT 에 없지만 참조 중이라 유지: ").append(r.keptRef);
    if(!r.skipped.isEmpty()) b.append("\n⚠ 별칭 없는 식 컬럼은 필드로 못 만듦(AS 별칭 필요): ").append(r.skipped);
    if(r.noColumns) b.append("\n⚠ SELECT 목록을 파싱하지 못함(SELECT * / 함수테이블) — crf_sync_fields mode=db 로 DB 에서 컬럼을 확정하세요");
    if(!r.warns.isEmpty()) b.append("\n⚠ MyBatis 변환 경고: ").append(r.warns);
    return b.toString(); }
  @SuppressWarnings("unchecked")
  static String setQuery(JSONObject args) throws Exception {
    String path=s(args,"path"), sql=s(args,"sql"), output=s(args,"output");
    if(sql==null||sql.trim().isEmpty()) return "ERROR: sql 인자가 비어 있습니다";
    TheReportFile rf=open(path); DataSet ds=datasetOf(rf,s(args,"dataset"));
    boolean declare=!"false".equalsIgnoreCase(s(args,"declare_params"));
    QueryApply r=applyQuery(rf,ds,sql,s(args,"script_type"),declare,s(args,"sync_fields"));
    String wrote=save(rf,output,path);
    StringBuilder b=new StringBuilder("OK: set query on "+ds.getName()+" ("+r.conv.length()+" chars, scriptType "+r.before+" → "+r.after+")"+(r.conv.equals(sql)?"":" — 파라미터를 {parameter.X} 형식으로 정규화함")+", wrote "+wrote);
    b.append(applySummary(r)); b.append("\n필드("+ds.getFieldDataList().size()+"): "+fieldList(ds));
    b.append("\n----- query (first 600 chars) -----\n").append(r.conv.length()>600?r.conv.substring(0,600)+"\n…":r.conv);
    return b.toString();
  }

  // ===== v0.5.1: dataset / parameter / field editing =====
  static String nsOf(Field f){ switch(f.getClass().getSimpleName()){ case "FieldData": return "data"; case "FieldFormula": return "formula"; case "FieldGlobalParameter": case "FieldParameter": case "FieldReportParameter": return "parameter"; case "FieldRunningTotal": return "runningtotal"; case "FieldGroupName": return "groupname"; case "FieldGroupIndex": return "groupindex"; case "FieldGlobalSpecial": return "system"; default: return f.getClass().getSimpleName().toLowerCase(); } }
  static java.util.regex.Pattern scriptRefPattern(String ns,String name){ return java.util.regex.Pattern.compile("([\"'])"+ns+"\\."+java.util.regex.Pattern.quote(name)+"\\1", java.util.regex.Pattern.CASE_INSENSITIVE); }
  static java.util.regex.Pattern paramTokenPattern(String name){ return java.util.regex.Pattern.compile("\\{parameter\\."+java.util.regex.Pattern.quote(name)+"\\}", java.util.regex.Pattern.CASE_INSENSITIVE); }
  /** Everywhere a field object is used: bindings (labels/cells), group fields, running totals, conditions, subreport links (by identity) + formula scripts and query tokens (by name). */
  @SuppressWarnings("unchecked")
  static java.util.List<String> refsOf(TheReportFile rf,Field target){
    java.util.List<String> out=new java.util.ArrayList<>();
    walkRefs(rf.getGlobe(),target,out,Collections.newSetFromMap(new IdentityHashMap<>()),"",0);
    var rom=rf.getGlobe().getMainReport().getReportObjectManager(); java.util.regex.Pattern sp=scriptRefPattern(nsOf(target),target.getName());
    RexObjectList<FieldFormula> fl=(RexObjectList<FieldFormula>) rom.getFieldFormulaList(); for(int i=0;i<fl.size();i++) if(fl.get(i)!=target && sp.matcher(q(fl.get(i).getScript())).find()) out.add("공식 "+fl.get(i).getName()+" 스크립트");
    if(nsOf(target).equals("parameter")){ java.util.regex.Pattern pp=paramTokenPattern(target.getName()); RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
      for(int i=0;i<dss.size();i++){ DataSetItemNormal n=dss.get(i).getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL(); if(qm!=null && pp.matcher(q(qm.getQueryString())).find()) out.add("데이터셋 "+dss.get(i).getName()+" 쿼리 {parameter."+target.getName()+"}"); } }
    return out;
  }
  static boolean structural(Object v){ if(v==null) return false; String sn=v.getClass().getSimpleName(); return v instanceof Section||v instanceof SubSection||v instanceof Control||sn.startsWith("TableCell")||sn.equals("FieldLink"); }
  @SuppressWarnings("unchecked")
  static void walkRefs(Object o,Field target,java.util.List<String> out,java.util.Set<Object> seen,String ctx,int d){
    if(o==null||d>30) return;
    if(o instanceof RexObjectList){ RexObjectList<?> l=(RexObjectList<?>)o; for(int i=0;i<l.size();i++){ Object e=l.get(i); if(structural(e)) continue; walkRefs(e,target,out,seen,ctx,d); } return; }
    if(!o.getClass().getName().startsWith("com.clipsoft.clipreport")) return; if(o==target) return; if(!seen.add(o)) return;
    String sn=o.getClass().getSimpleName(); String c=ctx;
    if(o instanceof Section){ String gf=groupFieldOf((Section)o); c=sn.replace("Section","")+(gf==null?"":"(→"+gf+")"); } else if(o instanceof SubSection) c=ctx+"/"+q(nameOf(o)); else if(o instanceof Control) c=ctx+"/"+sn.replace("Control","")+"\""+q(nameOf(o))+"\""; else if(o instanceof Field) c="필드 "+nameOf(o); else if(o instanceof Group) c="그룹"; else if(sn.equals("Condition")) c=ctx+"/조건"; else if(sn.equals("ConditionalStyle")) c=ctx+"/조건스타일";
    // --- structural children first (explicit, with precise context) ---
    if(o instanceof MainPage){ RexObjectList<Section> secs=((MainPage)o).getSectionList(); for(int i=0;i<secs.size();i++) walkRefs(secs.get(i),target,out,seen,c,d+1); }
    if(o instanceof Section){ RexObjectList<SubSection> ss=((Section)o).getSubSectionList(); for(int i=0;i<ss.size();i++) walkRefs(ss.get(i),target,out,seen,c,d+1); }
    if(o instanceof SubSectionDefault){ RexObjectList<ControlListForEachSeparatedPage> cls=((SubSectionDefault)o).getControlListForEachSeparatedPageList(); for(int k=0;k<cls.size();k++){ RexObjectList<Control> cl=(RexObjectList<Control>)(RexObjectList<?>)cls.get(k).getControlList(); for(int x=0;x<cl.size();x++) walkRefs(cl.get(x),target,out,seen,c,d+1); } }
    if(o instanceof Control && "ControlTable".equals(sn)){ Object rc=go(o,"getRowCount"), cc=go(o,"getColumnCount"); if(rc instanceof Integer && cc instanceof Integer) for(int r=0;r<(Integer)rc;r++) for(int k=0;k<(Integer)cc;k++){ Object cell=tableCell((Control)o,r,k); if(cell!=null) walkRefs(cell,target,out,seen,c+"["+r+","+k+"]",d+1); } }
    Object links=go(o,"getFieldLinkListForSubReportParameter"); if(links instanceof RexObjectList){ RexObjectList<?> ll=(RexObjectList<?>)links; for(int i=0;i<ll.size();i++) walkRefs(ll.get(i),target,out,seen,c+"/매개변수링크",d+1); }
    // --- generic getters (never descend into structural objects here) ---
    for(java.lang.reflect.Method m:o.getClass().getMethods()){ if(m.getParameterCount()!=0||!m.getName().startsWith("get")||m.getName().equals("getClass")) continue; String nm=m.getName(); if(nm.equals("getParentObj")||nm.equals("getRefInfomationStorage")||nm.equals("getRefStorage")||nm.equals("getSubreport")) continue;
      Class<?> rt=m.getReturnType(); if(rt.isPrimitive()||rt==String.class||rt.isEnum()||rt==Class.class||rt.isArray()) continue;
      Object v; try{ v=m.invoke(o); }catch(Throwable t){ continue; } if(v==null) continue;
      if(v==target){ out.add(c+"."+nm.substring(3)); continue; }
      if(v instanceof Field||structural(v)) continue;
      walkRefs(v,target,out,seen,c,d+1); }
  }
  static String refsText(java.util.List<String> refs,int max){ StringBuilder b=new StringBuilder(); for(int i=0;i<refs.size()&&i<max;i++) b.append("\n   - ").append(refs.get(i)); if(refs.size()>max) b.append("\n   … +").append(refs.size()-max); return b.toString(); }
  /** Locate a field by name across datasets / formulas / running totals / parameters; dataset selector disambiguates. */
  static Field locateField(TheReportFile rf,String name,String dsSel,StringBuilder err){
    if(name==null||name.trim().isEmpty()){ err.append("name 인자가 비어 있습니다"); return null; } name=name.trim();
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); var rom=rf.getGlobe().getMainReport().getReportObjectManager(); RexObjectList<DataSet> dss=gom.getDataSetList();
    if(dsSel!=null&&!dsSel.trim().isEmpty()){ DataSet ds=datasetOf(rf,dsSel); FieldData f=findDataField(ds,name); if(f==null) err.append("데이터셋 "+ds.getName()+" 에 필드 '"+name+"' 없음"); return f; }
    java.util.List<Field> found=new java.util.ArrayList<>(); java.util.List<String> where=new java.util.ArrayList<>();
    for(int i=0;i<dss.size();i++){ FieldData f=findDataField(dss.get(i),name); if(f!=null){ found.add(f); where.add("데이터셋 "+dss.get(i).getName()); } }
    for(RexObjectList<?> l: new RexObjectList<?>[]{rom.getFieldFormulaList(),rom.getFieldRunningTotalList(),rom.getFieldGroupNameList(),gom.getFieldGlobalParameterList()}) if(l!=null) for(int i=0;i<l.size();i++) if(name.equalsIgnoreCase(nameOf(l.get(i)))){ found.add((Field)l.get(i)); where.add(fieldKindKo(l.get(i))+" 필드"); }
    if(found.isEmpty()){ err.append("필드 '"+name+"' 없음 (crf_summary 로 이름 확인)"); return null; }
    if(found.size()>1){ err.append("'"+name+"' 이 여러 곳에 있습니다: "+where+" — dataset 인자로 지정하세요"); return null; }
    return found.get(0);
  }
  /** Remove a field object from whichever list holds it. */
  static boolean removeFromLists(TheReportFile rf,Field f){
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); var rom=rf.getGlobe().getMainReport().getReportObjectManager(); RexObjectList<DataSet> dss=gom.getDataSetList();
    java.util.List<RexObjectList<?>> lists=new java.util.ArrayList<>(); for(int i=0;i<dss.size();i++) lists.add(dss.get(i).getFieldDataList());
    lists.add(rom.getFieldFormulaList()); lists.add(rom.getFieldRunningTotalList()); lists.add(rom.getFieldGroupNameList()); lists.add(gom.getFieldGlobalParameterList()); lists.add(rom.getFieldReportParameterList());
    for(RexObjectList<?> l: lists){ if(l==null) continue; for(int i=0;i<l.size();i++) if(l.get(i)==f){ l.remove(i); return true; } }
    return false;
  }
  static String fieldRefs(JSONObject args) throws Exception {
    TheReportFile rf=open(s(args,"path")); StringBuilder err=new StringBuilder(); Field f=locateField(rf,s(args,"name"),s(args,"dataset"),err); if(f==null) return "ERROR: "+err;
    java.util.List<String> refs=refsOf(rf,f);
    return fieldKindKo(f)+" 필드 '"+f.getName()+"' 참조 "+refs.size()+"곳"+(refs.isEmpty()?" (없음 — 삭제해도 안전)":":"+refsText(refs,60));
  }
  static String renameField(JSONObject args) throws Exception {
    String path=s(args,"path"), nn=s(args,"new_name"), output=s(args,"output");
    if(nn==null||nn.trim().isEmpty()) return "ERROR: new_name 인자가 비어 있습니다"; nn=nn.trim();
    TheReportFile rf=open(path); StringBuilder err=new StringBuilder(); Field f=locateField(rf,s(args,"name"),s(args,"dataset"),err); if(f==null) return "ERROR: "+err;
    if(nn.equalsIgnoreCase(f.getName())) return "ERROR: 이름이 같습니다";
    Field dup=findField(rf,nn); if(dup!=null) return "ERROR: 이름 중복 — '"+nn+"' 은 이미 "+fieldKindKo(dup)+" 필드로 존재합니다";
    String old=f.getName(), ns=nsOf(f); java.util.List<String> refs=refsOf(rf,f); f.setName(nn);
    int scripts=0, queries=0; var rom=rf.getGlobe().getMainReport().getReportObjectManager();
    java.util.regex.Pattern sp=scriptRefPattern(ns,old); RexObjectList<?> fl=rom.getFieldFormulaList();
    for(int i=0;i<fl.size();i++){ FieldFormula ff=(FieldFormula)fl.get(i); String sc=q(ff.getScript()); java.util.regex.Matcher m=sp.matcher(sc); if(m.find()){ ff.setScript(m.replaceAll("$1"+ns+"."+java.util.regex.Matcher.quoteReplacement(nn)+"$1")); scripts++; } }
    if(ns.equals("parameter")){ java.util.regex.Pattern pp=paramTokenPattern(old); RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
      for(int i=0;i<dss.size();i++){ DataSetItemNormal n=dss.get(i).getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL(); if(qm==null) continue; String qs=q(qm.getQueryString()); java.util.regex.Matcher m=pp.matcher(qs); if(m.find()){ qm.setQueryString(m.replaceAll(java.util.regex.Matcher.quoteReplacement("{parameter."+nn+"}"))); queries++; } } }
    RexObjectList<?> gn=rom.getFieldGroupNameList(); if(gn!=null) for(int i=0;i<gn.size();i++){ Field g=(Field)gn.get(i); if(q(g.getName()).contains("["+old+"]")) g.setName(g.getName().replace("["+old+"]","["+nn+"]")); }
    String wrote=save(rf,output,path);
    TheReportFile v=open(output); if(findField(v,nn)==null) return "ERROR: 저장 후 되읽기 검증 실패 — 새 이름이 반영되지 않음";
    return "OK: "+fieldKindKo(f)+" 필드 '"+old+"' → '"+nn+"' (객체 바인딩 "+refs.size()+"곳은 자동 추종, 공식 스크립트 "+scripts+"개"+(ns.equals("parameter")?", 쿼리 "+queries+"개":"")+" 재작성), wrote "+wrote+(refs.isEmpty()?"":refsText(refs,20));
  }
  static String removeField(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"); boolean force="true".equalsIgnoreCase(s(args,"force"));
    TheReportFile rf=open(path); StringBuilder err=new StringBuilder(); Field f=locateField(rf,s(args,"name"),s(args,"dataset"),err); if(f==null) return "ERROR: "+err;
    if(nsOf(f).equals("parameter")) return "ERROR: 매개변수는 crf_remove_param 으로 삭제하세요";
    if(nsOf(f).equals("groupname")||nsOf(f).equals("system")) return "ERROR: "+fieldKindKo(f)+" 필드는 삭제 대상이 아닙니다";
    java.util.List<String> refs=refsOf(rf,f);
    if(!refs.isEmpty()&&!force) return "ERROR: '"+f.getName()+"' 은 "+refs.size()+"곳에서 참조됩니다 — 먼저 바인딩을 바꾸거나 force=true:"+refsText(refs,40);
    if(!removeFromLists(rf,f)) return "ERROR: 필드 목록에서 찾지 못함";
    String wrote=save(rf,output,path);
    TheReportFile v=open(output); if(findField(v,f.getName())!=null) return "ERROR: 저장 후 되읽기 검증 실패 — 필드가 남아 있음";
    return "OK: "+fieldKindKo(f)+" 필드 '"+f.getName()+"' 삭제, wrote "+wrote+(refs.isEmpty()?"":"\n⚠ force 삭제 — 다음 참조가 끊어졌습니다(디자이너에서 정리 필요):"+refsText(refs,40));
  }
  static String setParam(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), output=s(args,"output"), type=s(args,"type"), def=s(args,"default"), prompt=s(args,"prompt");
    if(name==null||name.trim().isEmpty()) return "ERROR: name 인자가 비어 있습니다"; name=name.trim();
    TheReportFile rf=open(path); RexObjectList<?> gp=rf.getGlobe().getGlobalObjectManager().getFieldGlobalParameterList(); Object ex=null; for(int i=0;i<gp.size();i++) if(name.equalsIgnoreCase(nameOf(gp.get(i)))) ex=gp.get(i);
    DataType dt=null; if(type!=null&&!type.trim().isEmpty()){ try{ dt=DataType.valueOf(type.trim()); }catch(Exception e){ return "ERROR: type 은 String|Number|Currency|DateTime|Boolean"; } }
    String did;
    if(ex!=null){ Field other=findField(rf,name); if(other!=null && other!=ex) return "ERROR: 이름 중복 — '"+name+"' 은 "+fieldKindKo(other)+" 필드로도 존재";
      if(dt!=null) call(ex,"setDataType",DataType.class,dt); if(def!=null) call(ex,"setDefaultValue",String.class,def); if(prompt!=null) call(ex,"setPrompt",String.class,prompt);
      did="updated '"+nameOf(ex)+"'"; }
    else { Field other=findField(rf,name); if(other!=null) return "ERROR: 이름 중복 — '"+name+"' 은 이미 "+fieldKindKo(other)+" 필드로 존재";
      addGlobalParam(rf,name,dt==null?DataType.String:dt,def,prompt); did="created '"+name+"'"; }
    String wrote=save(rf,output,path);
    Object v=null; RexObjectList<?> vp=open(output).getGlobe().getGlobalObjectManager().getFieldGlobalParameterList(); for(int i=0;i<vp.size();i++) if(name.equalsIgnoreCase(nameOf(vp.get(i)))) v=vp.get(i);
    if(v==null) return "ERROR: 저장 후 되읽기 검증 실패";
    return "OK: 매개변수 "+did+" type="+g(v,"getDataType")+" default=\""+q(g(v,"getDefaultValue"))+"\" prompt=\""+q(g(v,"getPrompt"))+"\", wrote "+wrote;
  }
  static String removeParam(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), output=s(args,"output"); boolean force="true".equalsIgnoreCase(s(args,"force"));
    if(name==null||name.trim().isEmpty()) return "ERROR: name 인자가 비어 있습니다"; name=name.trim();
    TheReportFile rf=open(path); RexObjectList<?> gp=rf.getGlobe().getGlobalObjectManager().getFieldGlobalParameterList(); Field f=null; for(int i=0;i<gp.size();i++) if(name.equalsIgnoreCase(nameOf(gp.get(i)))) f=(Field)gp.get(i);
    if(f==null) return "ERROR: 매개변수 '"+name+"' 없음";
    java.util.List<String> refs=refsOf(rf,f);
    if(!refs.isEmpty()&&!force) return "ERROR: 매개변수 '"+f.getName()+"' 은 "+refs.size()+"곳에서 참조됩니다 — force=true 로 강행 가능:"+refsText(refs,40);
    removeFromLists(rf,f); String wrote=save(rf,output,path);
    return "OK: 매개변수 '"+f.getName()+"' 삭제, wrote "+wrote+(refs.isEmpty()?"":"\n⚠ force 삭제 — 끊어진 참조:"+refsText(refs,40));
  }
  @SuppressWarnings("unchecked")
  static String addDataset(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), sql=s(args,"sql"), output=s(args,"output");
    if(name==null||name.trim().isEmpty()) return "ERROR: name 인자가 비어 있습니다"; name=name.trim();
    if(sql==null||sql.trim().isEmpty()) return "ERROR: sql 인자가 비어 있습니다";
    TheReportFile rf=open(path); RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    for(int i=0;i<dss.size();i++) if(name.equalsIgnoreCase(dss.get(i).getName())) return "ERROR: 데이터셋 이름 중복: "+dss.get(i).getName();
    if(dss.size()==0) return "ERROR: 복제할 기존 데이터셋(연결 정보)이 없습니다";
    DataSet first=dss.get(0); DataSet ds=new DataSet(); ds.setName(name); ds.setDataSetType(first.getDataSetType());
    DataSetItemNormal in=ds.getDataSetItemNormal(); if(in==null) return "ERROR: 새 데이터셋 구조 생성 실패(SDK)";
    DataSetItemNormal fn=first.getDataSetItemNormal(); if(fn!=null){ in.setLinkedConnection(fn.getLinkedConnection()); in.setDataAccessMethod(fn.getDataAccessMethod()); }
    if(in.getDataAccessMethodSQL()==null) return "ERROR: 새 데이터셋에 SQL 접근방식이 없습니다(SDK)";
    QueryApply r=applyQuery(rf,ds,sql,s(args,"script_type"),true,"add");
    dss.add(ds); String wrote=save(rf,output,path);
    TheReportFile v=open(output); RexObjectList<DataSet> vd=v.getGlobe().getGlobalObjectManager().getDataSetList(); DataSet vds=null; for(int i=0;i<vd.size();i++) if(name.equalsIgnoreCase(vd.get(i).getName())) vds=vd.get(i);
    if(vds==null) return "ERROR: 저장 후 되읽기 검증 실패 — 데이터셋이 없음";
    return "OK: 데이터셋 '"+name+"' 추가 (DS["+vd.indexOf(vds)+"], scriptType "+r.after+", 연결="+nameOf(fn==null?null:fn.getLinkedConnection())+", 필드 "+vds.getFieldDataList().size()+"개: "+fieldList(vds)+"), wrote "+wrote+applySummary(r)+"\n(서브리포트/표에 바인딩하려면 crf_set_cell / crf_add_label 로 이 데이터셋 필드를 지정)";
  }
  static String removeDataset(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"); boolean force="true".equalsIgnoreCase(s(args,"force"));
    if(s(args,"dataset")==null||s(args,"dataset").trim().isEmpty()) return "ERROR: dataset 인자가 비어 있습니다";
    TheReportFile rf=open(path); RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList(); DataSet ds=datasetOf(rf,s(args,"dataset"));
    if(dss.size()<=1) return "ERROR: 마지막 데이터셋은 삭제할 수 없습니다";
    java.util.List<String> refs=new java.util.ArrayList<>(); RexObjectList<?> fl=ds.getFieldDataList(); for(int i=0;i<fl.size();i++){ for(String r: refsOf(rf,(Field)fl.get(i))) refs.add(nameOf(fl.get(i))+" ← "+r); }
    if(!refs.isEmpty()&&!force) return "ERROR: 데이터셋 "+ds.getName()+" 의 필드가 "+refs.size()+"곳에서 참조됩니다 — force=true 로 강행 가능:"+refsText(refs,40);
    dss.remove(dss.indexOf(ds)); String wrote=save(rf,output,path);
    TheReportFile v=open(output); return "OK: 데이터셋 '"+ds.getName()+"' 삭제 (남은 데이터셋 "+v.getGlobe().getGlobalObjectManager().getDataSetList().size()+"), wrote "+wrote+(refs.isEmpty()?"":"\n⚠ force 삭제 — 끊어진 참조:"+refsText(refs,40));
  }
  /** Bind {parameter.X}/{dataset.X} for a metadata-only execution: quoted tokens -> 'value' or '', bare tokens -> value or NULL. */
  static String bindForExec(String sql,java.util.Map<String,String> params){
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("'\\{(parameter|dataset)\\.([A-Za-z0-9_]+)\\}'").matcher(sql); StringBuffer b=new StringBuffer();
    while(m.find()){ String v=params==null?null:params.get(m.group(2)); if(v==null&&params!=null) v=params.get(m.group(2).toUpperCase()); m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement("'"+(v==null?"":v.replace("'","''"))+"'")); } m.appendTail(b);
    m=java.util.regex.Pattern.compile("\\{(parameter|dataset)\\.([A-Za-z0-9_]+)\\}").matcher(b.toString()); StringBuffer c=new StringBuffer();
    while(m.find()){ String v=params==null?null:params.get(m.group(2)); if(v==null&&params!=null) v=params.get(m.group(2).toUpperCase()); m.appendReplacement(c, java.util.regex.Matcher.quoteReplacement(v==null?"NULL":v)); } m.appendTail(c);
    return c.toString();
  }
  static DataType jdbcToDataType(String typeName,int scale,String col){ String t=q(typeName).toUpperCase();
    if(t.contains("DATE")||t.contains("TIME")) return DataType.DateTime;
    if(t.contains("NUM")||t.contains("DEC")||t.contains("INT")||t.contains("FLOAT")||t.contains("DOUBLE")||t.contains("REAL")) return CrfGen2.guessType(col)==DataType.Currency?DataType.Currency:DataType.Number;
    return DataType.String; }
  @SuppressWarnings("unchecked")
  static String syncFields(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), mode=q(s(args,"mode")).trim().toLowerCase(); if(mode.isEmpty()) mode="sql";
    boolean setTypes="true".equalsIgnoreCase(s(args,"set_types")), removeUnused="true".equalsIgnoreCase(s(args,"remove_unused"));
    TheReportFile rf=open(path); DataSet ds=datasetOf(rf,s(args,"dataset"));
    DataAccessMethodSQL qm=ds.getDataSetItemNormal()==null?null:ds.getDataSetItemNormal().getDataAccessMethodSQL(); if(qm==null) return "ERROR: 데이터셋 "+ds.getName()+" 은 SQL 데이터셋이 아닙니다";
    String raw=q(qm.getQueryString()); String plain=qm.getScriptType()==ScriptType.JavaScript?jsToPlainSql(raw):raw;
    java.util.List<String> cols=new java.util.ArrayList<>(); java.util.Map<String,DataType> types=new java.util.LinkedHashMap<>(); java.util.List<String> skipped=new java.util.ArrayList<>(); String source;
    if(mode.equals("sql")){ for(String c: CrfGen2.parseColumns(CrfGen2.stripComments(plain))){ if(c.matches("COL_\\d+")||!validIdent(c)) skipped.add(c); else { cols.add(c); types.put(c,CrfGen2.guessType(c)); } }
      if(cols.isEmpty()&&skipped.isEmpty()) return "ERROR: SELECT 목록을 파싱하지 못함(SELECT * / 함수테이블 등) — mode=db 를 사용하세요"; source="SQL 파싱"; }
    else if(mode.equals("db")){ java.util.Map<String,String> params=new java.util.HashMap<>(); String pj=s(args,"params"); if(pj!=null&&!pj.trim().isEmpty()){ try{ JSONObject po=(JSONObject)P.parse(pj); for(Object k: po.keySet()) params.put(String.valueOf(k),String.valueOf(po.get(k))); }catch(Exception e){ return "ERROR: params 는 JSON 객체여야 합니다: "+e; } }
      String body=bindForExec(CrfGen2.stripComments(plain),params).trim().replaceAll(";\\s*$","");
      String exec="SELECT * FROM (\n"+body+"\n) WHERE 1=0";
      try(Connection c=db(); Statement st=c.createStatement()){ try{ st.setQueryTimeout(DB_TIMEOUT_SEC); st.setMaxRows(1); }catch(Throwable t){}
        try(ResultSet rs=st.executeQuery(exec)){ ResultSetMetaData md=rs.getMetaData(); for(int i=1;i<=md.getColumnCount();i++){ String c2=md.getColumnLabel(i); if(!validIdent(c2)) { skipped.add(c2); continue; } cols.add(c2); types.put(c2,jdbcToDataType(md.getColumnTypeName(i),md.getScale(i),c2)); } }
      }catch(SQLException e){ return "ERROR: DB 실행 실패 — "+e.getMessage().trim()+"\n(params 로 매개변수 값을 주거나 쿼리를 확인하세요; 실행한 SQL 앞부분)\n"+(exec.length()>500?exec.substring(0,500)+"…":exec); }
      source="DB ResultSetMetaData"+(params.isEmpty()?"":" params="+params); }
    else return "ERROR: mode 는 sql|db";
    java.util.List<String> added=new java.util.ArrayList<>(), typed=new java.util.ArrayList<>(), removed=new java.util.ArrayList<>(), kept=new java.util.ArrayList<>(); java.util.Set<String> want=new java.util.HashSet<>();
    for(String c: cols){ want.add(c.toUpperCase()); FieldData f=findDataField(ds,c); if(f==null){ addDataFieldTo(ds,c,setTypes?types.get(c):nullType()); added.add(c+(setTypes?":"+types.get(c):"")); } else if(setTypes && f.getDataType()!=types.get(c)){ f.setDataType(types.get(c)); typed.add(c+":"+types.get(c)); } }
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    for(int i=fl.size()-1;i>=0;i--){ FieldData f=fl.get(i); if(want.contains(f.getName().toUpperCase())) continue; if(!removeUnused){ kept.add(f.getName()); continue; } java.util.List<String> refs=refsOf(rf,f); if(refs.isEmpty()){ fl.remove(i); removed.add(f.getName()); } else kept.add(f.getName()+"("+refs.size()+"곳 참조)"); }
    String wrote=save(rf,output,path);
    StringBuilder b=new StringBuilder("OK: "+ds.getName()+" 필드 동기화 ("+source+") — 쿼리 컬럼 "+cols.size()+"개, wrote "+wrote);
    b.append("\n쿼리 컬럼: "); for(String c: cols) b.append(c).append(":").append(types.get(c)).append(" ");
    if(!added.isEmpty()) b.append("\n+ 추가: ").append(added); if(!typed.isEmpty()) b.append("\n~ 타입 변경: ").append(typed);
    if(!removed.isEmpty()) b.append("\n- 제거(미참조): ").append(removed);
    if(!kept.isEmpty()) b.append("\n⚠ 쿼리에 없는 필드 유지: ").append(kept).append(removeUnused?" (참조 중)":" (remove_unused=true 면 미참조 필드 제거)");
    if(!skipped.isEmpty()) b.append("\n⚠ 필드명으로 못 쓰는 컬럼(별칭 필요): ").append(skipped);
    b.append("\n필드("+ds.getFieldDataList().size()+"): "+fieldList(ds));
    return b.toString();
  }

  static java.util.regex.Pattern likePattern(String like){ if(like==null||like.trim().isEmpty()) return null; String l=like.trim();
    return l.contains("*")||l.contains("?") ? java.util.regex.Pattern.compile("(?i)^"+l.replace(".","\\.").replace("*",".*").replace("?",".")+"$") : java.util.regex.Pattern.compile("(?i).*"+java.util.regex.Pattern.quote(l)+".*"); }
  static java.util.List<Path> crfFiles(String dir,String like,int max) throws IOException { java.util.regex.Pattern lp=likePattern(like);
    try(java.util.stream.Stream<Path> st=Files.walk(Paths.get(dir))){ return st.filter(p->p.toString().toLowerCase().endsWith(".crf")).filter(p->lp==null||lp.matcher(p.getFileName().toString()).matches()).sorted().limit(max).collect(java.util.stream.Collectors.toList()); } }
  static String listReports(JSONObject args) throws Exception {
    String dir=s(args,"dir"), like=s(args,"like"); int limit=pInt(args.get("limit"),500);
    if(dir==null||!new File(dir).isDirectory()) return "ERROR: dir 폴더 없음: "+dir;
    java.util.List<Path> all=crfFiles(dir,like,Integer.MAX_VALUE);
    StringBuilder b=new StringBuilder("reports under "+dir+(like==null||like.isEmpty()?"":" like="+like)+": total "+all.size()+(all.size()>limit?" (showing first "+limit+", use like/limit)":"")+"\n");
    int n=0; for(Path p: all){ if(n++>=limit) break; b.append("  ").append(p).append("  (").append(Files.size(p)/1024).append(" KB)\n"); }
    return b.toString();
  }

  // ===== v0.5.0: query reading / formula reading / folder search =====
  static String q(String x){ return x==null?"":x; }
  /** JavaScript dynamic query -> plain SQL: concatenates the string literals in order; if/else blocks become /*IF cond*&#47; … /*END IF*&#47; comments. */
  static String jsToPlainSql(String js){
    StringBuilder out=new StringBuilder(); int n=js.length(), i=0; java.util.ArrayDeque<Boolean> braces=new java.util.ArrayDeque<>(); boolean pendingIf=false;
    while(i<n){ char c=js.charAt(i);
      if(c=='"'||c=='\''){ char qc=c; i++; while(i<n && js.charAt(i)!=qc){ char d=js.charAt(i); if(d=='\\' && i+1<n){ char e=js.charAt(i+1); if(e=='n') out.append('\n'); else if(e=='r'){} else if(e=='t') out.append('\t'); else out.append(e); i+=2; } else { out.append(d); i++; } } i++; continue; }
      if(c=='/' && i+1<n && js.charAt(i+1)=='/'){ int e=js.indexOf('\n',i); i=e<0?n:e; continue; }
      if(c=='/' && i+1<n && js.charAt(i+1)=='*'){ int e=js.indexOf("*/",i+2); i=e<0?n:e+2; continue; }
      if(Character.isLetter(c)||c=='_'){ int s0=i; while(i<n && (Character.isLetterOrDigit(js.charAt(i))||js.charAt(i)=='_')) i++; String w=js.substring(s0,i);
        if(w.equals("if")){ int p0=js.indexOf('(',i); if(p0>=0){ int d=0,e=p0; for(;e<n;e++){ char x=js.charAt(e); if(x=='(')d++; else if(x==')'){ d--; if(d==0)break; } } String cond=js.substring(p0+1,Math.min(e,n)).trim().replace("*/","* /"); out.append("\n/*IF ").append(cond).append(" */\n"); i=e+1; pendingIf=true; } }
        else if(w.equals("else")){ out.append("\n/*ELSE*/\n"); pendingIf=true; }
        continue; }
      if(c=='{'){ braces.push(pendingIf); pendingIf=false; i++; continue; }
      if(c=='}'){ boolean wasIf=!braces.isEmpty() && braces.pop(); if(wasIf){ int k=i+1; while(k<n && Character.isWhitespace(js.charAt(k))) k++; if(!js.startsWith("else",k)) out.append("\n/*END IF*/\n"); } i++; continue; }
      i++; }
    return out.toString().replaceAll("[ \\t]+\n","\n").replaceAll("\n{3,}","\n\n").trim();
  }
  /** Table names referenced by FROM/JOIN/INTO/UPDATE (estimate; schema-qualified kept, DUAL dropped). */
  static java.util.Set<String> tablesOf(String sql){
    java.util.Set<String> t=new java.util.TreeSet<>(); if(sql==null) return t;
    String x=CrfGen2.stripComments(sql).replaceAll("'[^']*'","''");
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)\\b(FROM|JOIN|INTO|UPDATE)\\s+([A-Za-z_][\\w$#]*(?:\\.[A-Za-z_][\\w$#]*)?)").matcher(x);
    while(m.find()){ String nm=m.group(2).toUpperCase(); if(nm.equals("DUAL")||nm.equals("TABLE")||nm.equals("SELECT")) continue; t.add(nm); }
    java.util.regex.Matcher f=java.util.regex.Pattern.compile("(?is)\\bFROM\\s+([^;()]*?)(?=\\bWHERE\\b|\\bGROUP\\b|\\bORDER\\b|\\bHAVING\\b|\\bUNION\\b|\\bJOIN\\b|\\bCONNECT\\b|\\bSTART\\b|$)").matcher(x);
    while(f.find()){ for(String item: f.group(1).split(",")){ java.util.regex.Matcher im=java.util.regex.Pattern.compile("^([A-Za-z_][\\w$#]*(?:\\.[A-Za-z_][\\w$#]*)?)").matcher(item.trim()); if(im.find()){ String nm=im.group(1).toUpperCase(); if(!nm.equals("DUAL")&&!nm.equals("SELECT")) t.add(nm); } } }
    return t;
  }
  static java.util.Set<String> usedParams(String txt){ java.util.Set<String> u=new java.util.TreeSet<>(); java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\{parameter\\.([A-Za-z0-9_]+)\\}").matcher(q(txt)); while(m.find()) u.add(m.group(1)); return u; }
  static java.util.Set<String> declaredParams(TheReportFile rf){ java.util.Set<String> d=new java.util.TreeSet<>();
    RexObjectList<?> gp=rf.getGlobe().getGlobalObjectManager().getFieldGlobalParameterList(); for(int i=0;i<gp.size();i++) d.add(nameOf(gp.get(i)).toUpperCase());
    RexObjectList<?> rp=rf.getGlobe().getMainReport().getReportObjectManager().getFieldReportParameterList(); if(rp!=null) for(int i=0;i<rp.size();i++) d.add(nameOf(rp.get(i)).toUpperCase());
    return d; }
  static String fieldList(DataSet ds){ StringBuilder b=new StringBuilder(); RexObjectList<?> fl=ds.getFieldDataList(); for(int j=0;j<fl.size();j++){ Object f=fl.get(j); Object dt=go(f,"getDataType"); b.append(nameOf(f)); if(dt!=null&&!"Null".equals(dt.toString())) b.append(":").append(dt); b.append(j<fl.size()-1?", ":""); } return b.toString(); }

  @SuppressWarnings("unchecked")
  static String getQuery(JSONObject args) throws Exception {
    String path=s(args,"path"), sel=s(args,"dataset"), mode=q(s(args,"mode")).trim().toLowerCase(); if(mode.isEmpty()) mode="both";
    TheReportFile rf=open(path);
    RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    java.util.List<DataSet> targets=new java.util.ArrayList<>();
    if(sel==null||sel.trim().isEmpty()||sel.trim().equals("*")) for(int i=0;i<dss.size();i++) targets.add(dss.get(i)); else targets.add(datasetOf(rf,sel));
    java.util.Set<String> declared=declaredParams(rf);
    StringBuilder b=new StringBuilder(new File(path).getName()+"  datasets="+dss.size()+(targets.size()==dss.size()?"":"  (showing "+targets.size()+")")+"  선언된 매개변수: "+declared+"\n");
    for(DataSet ds: targets){ int idx=dss.indexOf(ds);
      DataSetItemNormal n=ds.getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL();
      b.append("\n=== DS[").append(idx).append("] ").append(ds.getName()).append("  접근=").append(n==null?"?":String.valueOf(n.getDataAccessMethod())).append("  연결=").append(n==null?"-":nameOf(n.getLinkedConnection()));
      b.append("\n  필드(").append(ds.getFieldDataList().size()).append("): ").append(fieldList(ds)).append("\n");
      if(qm==null){ b.append("  (SQL 데이터셋 아님)\n"); continue; }
      String raw=q(qm.getQueryString()); boolean js=qm.getScriptType()==ScriptType.JavaScript; String plain=js?jsToPlainSql(raw):raw;
      b.append("  scriptType=").append(qm.getScriptType()).append("  길이=").append(raw.length());
      RexObjectList<SQLParameter> sp=qm.getSQLParameterList(); if(sp!=null&&sp.size()>0){ b.append("  SQL매개변수: "); for(int k=0;k<sp.size();k++) b.append(k>0?", ":"").append(sp.get(k).getParameterName()).append("(").append(sp.get(k).getDataType()).append(")"); }
      RexObjectList<ScriptParameter> scp=qm.getScriptParameterList(); if(scp!=null&&scp.size()>0){ b.append("  스크립트매개변수: "); for(int k=0;k<scp.size();k++) b.append(k>0?", ":"").append(scp.get(k).getParameterName()); }
      java.util.Set<String> used=usedParams(raw); java.util.Set<String> und=new java.util.TreeSet<>(); for(String u:used) if(!declared.contains(u.toUpperCase())) und.add(u);
      b.append("\n  매개변수 사용: ").append(used.isEmpty()?"-":used.toString()).append(und.isEmpty()?"":"  ⚠미선언: "+und);
      java.util.Set<String> dsr=new java.util.TreeSet<>(); java.util.regex.Matcher dm=java.util.regex.Pattern.compile("\\{dataset\\.([A-Za-z0-9_]+)\\}").matcher(raw); while(dm.find()) dsr.add(dm.group(1)); if(!dsr.isEmpty()) b.append("  데이터셋참조: ").append(dsr);
      b.append("\n  테이블(추정): ").append(tablesOf(plain)).append("\n");
      if(!js) b.append("  ----- query -----\n").append(raw).append("\n");
      else { if(!mode.equals("plain")) b.append("  ----- query (JavaScript 원문) -----\n").append(raw).append("\n");
             if(!mode.equals("raw")) b.append("  ----- 평문 복원 (문자열 연결을 풀고 if 블록은 /*IF*/ 주석; 실제 실행 SQL 은 조건에 따라 달라짐) -----\n").append(plain).append("\n"); }
    }
    return b.toString();
  }

  @SuppressWarnings("unchecked")
  static String getFormula(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"); String want=name==null?"":name.trim();
    TheReportFile rf=open(path); var rom=rf.getGlobe().getMainReport().getReportObjectManager();
    StringBuilder b=new StringBuilder(); int shown=0;
    RexObjectList<FieldFormula> fl=(RexObjectList<FieldFormula>) rom.getFieldFormulaList();
    for(int i=0;i<fl.size();i++){ FieldFormula f=fl.get(i); if(!want.isEmpty()&&!want.equalsIgnoreCase(f.getName())) continue; shown++;
      String sc=q(f.getScript()); b.append("== 공식 ").append(f.getName()).append(" (").append(f.getScriptType()).append(")\n").append(sc).append("\n");
      java.util.Set<String> refs=new java.util.TreeSet<>(), missing=new java.util.TreeSet<>();
      java.util.regex.Matcher m=java.util.regex.Pattern.compile("[\"']([a-zA-Z]+)\\.([A-Za-z0-9_가-힣#]+)[\"']").matcher(sc);
      while(m.find()){ String ns=m.group(1).toLowerCase(), fn=m.group(2); if(!(ns.equals("data")||ns.equals("formula")||ns.equals("parameter")||ns.equals("runningtotal")||ns.equals("system")||ns.equals("parent")||ns.equals("dataset")||ns.equals("groupname"))) continue; refs.add(ns+"."+fn);
        if(ns.equals("data")||ns.equals("formula")||ns.equals("parameter")||ns.equals("runningtotal")){ if(findField(rf,fn)==null) missing.add(ns+"."+fn); } }
      if(sc.contains("#unknown#")) missing.add("#unknown# (끊어진 참조)");
      if(!refs.isEmpty()||!missing.isEmpty()) b.append("   참조: ").append(refs).append(missing.isEmpty()?"":"  ⚠없는 필드: "+missing).append("\n");
    }
    RexObjectList<?> rt=rom.getFieldRunningTotalList(); if(rt!=null) for(int i=0;i<rt.size();i++){ Object r=rt.get(i); if(!want.isEmpty()&&!want.equalsIgnoreCase(nameOf(r))) continue; shown++;
      b.append("== 누적합산 ").append(nameOf(r)).append(": ").append(g(r,"getSummaryFunctionType")).append("(").append(nameOf(go(r,"getSummaryField"))).append(")  평가=").append(g(r,"getRunningTotalEvaluateType"));
      Object ef=go(r,"getRunningTotalEvaluateOnChangeField"); if(ef!=null) b.append(" 변경필드=").append(nameOf(ef)); Object eg=go(r,"getRunningTotalEvaluateOnChangeGroup"); if(eg!=null) b.append(" 변경그룹=").append(nameOf(go(eg,"getGroupingField")));
      b.append("  리셋=").append(g(r,"getRunningTotalResetType")); Object rfd=go(r,"getRunningTotalResetOnChangeField"); if(rfd!=null) b.append(" 리셋필드=").append(nameOf(rfd)); Object rg=go(r,"getRunningTotalResetOnChangeGroup"); if(rg!=null) b.append(" 리셋그룹=").append(nameOf(go(rg,"getGroupingField"))); b.append("\n"); }
    RexObjectList<?> gn=rom.getFieldGroupNameList(); if(gn!=null) for(int i=0;i<gn.size();i++){ Object gf=gn.get(i); if(!want.isEmpty()&&!want.equalsIgnoreCase(nameOf(gf))) continue; shown++; Object grp=go(gf,"getLinkedGroup"); b.append("== 그룹이름 ").append(nameOf(gf)).append(" → 그룹필드 ").append(grp==null?"-":nameOf(go(grp,"getGroupingField"))).append("\n"); }
    if(shown==0) return !want.isEmpty()? "ERROR: 공식/누적합산/그룹이름 필드 '"+want+"' 없음 (crf_summary 로 이름 확인)" : "(공식·누적합산·그룹이름 필드 없음)";
    return b.toString();
  }

  static String searchReports(JSONObject args) throws Exception {
    String dir=s(args,"dir"), text=s(args,"text"), scope=q(s(args,"scope")).trim().toLowerCase(), like=s(args,"like"); boolean regex="true".equalsIgnoreCase(s(args,"regex"));
    int limit=pInt(args.get("limit"),50), maxFiles=pInt(args.get("max_files"),5000);
    if(dir==null||!new File(dir).isDirectory()) return "ERROR: dir 폴더 없음: "+dir;
    if(text==null||text.trim().isEmpty()) return "ERROR: text 인자가 비어 있습니다";
    if(scope.isEmpty()) scope="any"; if(!scope.matches("query|field|formula|param|control|any|all")) return "ERROR: scope 는 query|field|formula|param|control|any";
    java.util.regex.Pattern pat; try{ pat=java.util.regex.Pattern.compile(regex?text:java.util.regex.Pattern.quote(text), java.util.regex.Pattern.CASE_INSENSITIVE); }catch(Exception e){ return "ERROR: 정규식 오류: "+e.getMessage(); }
    java.util.List<Path> files=crfFiles(dir,like,maxFiles); Path root=Paths.get(dir);
    long t0=System.currentTimeMillis(); int scanned=0, hitFiles=0, unreadable=0; StringBuilder b=new StringBuilder();
    for(Path p: files){ if(hitFiles>=limit) break; scanned++;
      TheReportFile rf; try{ rf=Rexpert4.read(p.toString()); }catch(Throwable t){ unreadable++; continue; }
      java.util.List<String> hits=new java.util.ArrayList<>(); try{ collectHits(rf,scope,pat,hits); }catch(Throwable t){ hits.add("(탐색 오류 "+t+")"); }
      if(hits.isEmpty()) continue; hitFiles++;
      b.append("● ").append(p).append("\n"); int shown=0; for(String h: hits){ if(shown++>=8){ b.append("    … +").append(hits.size()-8).append(" more\n"); break; } b.append("    ").append(h).append("\n"); } }
    String head="search '"+text+"' scope="+scope+(regex?" (regex)":"")+" under "+dir+(like==null||like.isEmpty()?"":" like="+like)+": "+hitFiles+" file(s) hit, scanned "+scanned+"/"+files.size()+(unreadable>0?" (unreadable "+unreadable+")":"")+", "+(System.currentTimeMillis()-t0)+" ms"+(hitFiles>=limit?"  ⚠ limit "+limit+" 도달 — like/limit 으로 조정":"")+"\n";
    return head+(b.length()==0?"(일치 없음)":b.toString());
  }
  static String oneLine(String x,int max){ String y=q(x).replaceAll("\\s+"," ").trim(); return y.length()>max?y.substring(0,max)+"…":y; }
  @SuppressWarnings("unchecked")
  static void collectHits(TheReportFile rf,String scope,java.util.regex.Pattern pat,java.util.List<String> hits){
    boolean any=scope.equals("any")||scope.equals("all");
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); var rom=rf.getGlobe().getMainReport().getReportObjectManager(); RexObjectList<DataSet> dss=gom.getDataSetList();
    if(any||scope.equals("query")) for(int i=0;i<dss.size();i++){ DataSet ds=dss.get(i); DataSetItemNormal n=ds.getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL(); if(qm==null) continue;
      String raw=q(qm.getQueryString()); String txt=qm.getScriptType()==ScriptType.JavaScript?jsToPlainSql(raw):raw; int c=0;
      for(String ln: txt.split("\\r?\\n")){ if(pat.matcher(ln).find()){ if(c++<3) hits.add("[query "+ds.getName()+"] "+oneLine(ln,160)); } } if(c>3) hits.add("[query "+ds.getName()+"] … +"+(c-3)+" lines"); }
    if(any||scope.equals("field")){ for(int i=0;i<dss.size();i++){ RexObjectList<?> fl=dss.get(i).getFieldDataList(); for(int j=0;j<fl.size();j++) if(pat.matcher(nameOf(fl.get(j))).find()) hits.add("[field "+dss.get(i).getName()+"] "+nameOf(fl.get(j))); }
      for(RexObjectList<?> l: new RexObjectList<?>[]{rom.getFieldFormulaList(),rom.getFieldRunningTotalList(),rom.getFieldGroupNameList()}) if(l!=null) for(int j=0;j<l.size();j++) if(pat.matcher(nameOf(l.get(j))).find()) hits.add("["+fieldKindKo(l.get(j))+"필드] "+nameOf(l.get(j))); }
    if(any||scope.equals("formula")){ RexObjectList<FieldFormula> fl=(RexObjectList<FieldFormula>) rom.getFieldFormulaList(); for(int j=0;j<fl.size();j++){ String sc=q(fl.get(j).getScript()); if(pat.matcher(sc).find()) hits.add("[formula "+fl.get(j).getName()+"] "+oneLine(sc,140)); } }
    if(any||scope.equals("param")){ RexObjectList<?> gp=gom.getFieldGlobalParameterList(); for(int j=0;j<gp.size();j++) if(pat.matcher(nameOf(gp.get(j))).find()) hits.add("[param] "+nameOf(gp.get(j)));
      for(int i=0;i<dss.size();i++){ DataSetItemNormal n=dss.get(i).getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL(); if(qm==null) continue; for(String u: usedParams(qm.getQueryString())) if(pat.matcher(u).find()) hits.add("[param used in "+dss.get(i).getName()+"] {parameter."+u+"}"); } }
    if(any||scope.equals("control")){ RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
      for(int i=0;i<secs.size();i++){ java.util.List<Control> cs=new java.util.ArrayList<>(); collectControls(secs.get(i),cs,Collections.newSetFromMap(new IdentityHashMap<>()),0); String band=secs.get(i).getClass().getSimpleName().replace("Section","");
        for(Control c: cs){ String nm=q(c.getName()), txt=q(g(c,"getApplyValueText")); Object f=go(c,"getApplyValueField");
          if(pat.matcher(nm).find()||pat.matcher(txt).find()||(f!=null&&pat.matcher(nameOf(f)).find())) hits.add("[control "+band+"] "+c.getClass().getSimpleName().replace("Control","")+" \""+nm+"\""+(txt.isEmpty()?"":" 텍스트=\""+oneLine(txt,80)+"\"")+(f!=null?" 필드="+nameOf(f):""));
          if("ControlTable".equals(c.getClass().getSimpleName())){ Object rc=go(c,"getRowCount"), cc=go(c,"getColumnCount"); if(rc instanceof Integer&&cc instanceof Integer) for(int r=0;r<(Integer)rc;r++) for(int k=0;k<(Integer)cc;k++){ Object cell=tableCell(c,r,k); if(!isNormalCell(cell)) continue; String ct=q(g(cell,"getApplyValueText")); Object cf=go(cell,"getApplyValueField");
            if(pat.matcher(ct).find()||(cf!=null&&pat.matcher(nameOf(cf)).find())) hits.add("[cell "+band+" "+nm+"["+r+","+k+"]] "+(cf!=null?"필드="+nameOf(cf):"\""+oneLine(ct,80)+"\"")); } } } } }
  }

  /** One-line description of a subsection: name, type, height, hidden flag; subreport subsections show link/embedded + parameter links. */
  static String subSectionInfo(SubSection sb){
    String type=sb.getClass().getSimpleName().replace("SubSection",""); if(type.equals("Default")) type="";
    StringBuilder b=new StringBuilder("\""+q(sb.getName())+"\""+(type.isEmpty()?"":" ["+type+"]")+" h="+sb.getHeight()+(sb.getVisible()?"":" [숨김]"));
    if(sb instanceof SubSectionSubreport){ SubSectionSubreport ss=(SubSectionSubreport)sb; b.append(subreportInfo(ss.getLinkedSubreportPath(), ss.getSubreport(), ss.getFieldLinkListForSubReportParameter())); }
    return b.toString();
  }
  static String subreportInfo(Object webLink,Object embedded,RexObjectList<?> links){
    StringBuilder b=new StringBuilder(); String url=g(webLink,"getUrlText");
    if(url!=null&&!url.isEmpty()) b.append(" 링크=").append(url);
    if(embedded!=null){ Object rom=go(embedded,"getReportObjectManager"); Object rd=go(embedded,"getReportDesign"); int secs=-1; try{ secs=((RexObjectList<?>)go(go(rd,"getMainPage"),"getSectionList")).size(); }catch(Exception e){} if(secs>0) b.append(" 임베디드 서브리포트(섹션 ").append(secs).append(")"); }
    if(links!=null&&links.size()>0){ b.append(" 매개변수링크: "); for(int i=0;i<links.size();i++){ Object l=links.get(i); b.append(i>0?", ":"").append(nameOf(go(l,"getLinkedField1"))).append("→").append(nameOf(go(l,"getLinkedField2"))); } }
    return b.toString();
  }
  static String groupFieldOf(Section sec){ if(sec instanceof SectionGroupHeader){ Group gp=((SectionGroupHeader)sec).getGroup(); return gp==null?"-":nameOf(gp.getGroupingField()); } return null; }

  static SubSectionDefault band(String name,int h){ SubSectionDefault sd=new SubSectionDefault(); sd.setName(name); sd.setHeight(h); sd.setVisible(true); sd.getControlListForEachSeparatedPageList().add(new ControlListForEachSeparatedPage()); return sd; }
  /** First (existing) control list of a subsection — never add a second ControlListForEachSeparatedPage (controls there may not render). */
  @SuppressWarnings("unchecked")
  static ControlListForEachSeparatedPage clpOf(SubSectionDefault sd){ RexObjectList<ControlListForEachSeparatedPage> l=sd.getControlListForEachSeparatedPageList(); if(l.size()>0) return l.get(0); ControlListForEachSeparatedPage c=new ControlListForEachSeparatedPage(); l.add(c); return c; }
  static java.util.Set<String> controlNames(TheReportFile rf){ java.util.Set<String> s=new java.util.HashSet<>(); RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    for(int i=0;i<secs.size();i++){ java.util.List<Control> cs=new java.util.ArrayList<>(); collectControls(secs.get(i),cs,Collections.newSetFromMap(new IdentityHashMap<>()),0); for(Control c:cs) if(c.getName()!=null) s.add(c.getName()); } return s; }
  static String uniqueControlName(TheReportFile rf,String base){ java.util.Set<String> used=controlNames(rf); if(!used.contains(base)) return base; for(int i=2;;i++) if(!used.contains(base+"_"+i)) return base+"_"+i; }
  static SubSectionDefault firstSub(Section sec){ RexObjectList<SubSection> ss=sec.getSubSectionList(); if(ss==null||ss.size()==0)return null; SubSection s=ss.get(0); return (s instanceof SubSectionDefault)?(SubSectionDefault)s:null; }

  @SuppressWarnings("unchecked")
  static String describeLayout(String path) throws Exception {
    TheReportFile rf=open(path);
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    StringBuilder b=new StringBuilder();
    for(int i=0;i<secs.size();i++){ Section sec=secs.get(i); String gf=groupFieldOf(sec); b.append("["+i+"] "+sec.getClass().getSimpleName().replace("Section","")+(gf==null?"":"(→"+gf+")")+"\n");
      RexObjectList<SubSection> ss=sec.getSubSectionList();
      for(int j=0;j<ss.size();j++){ SubSection sb=ss.get(j); b.append("   sub["+j+"] "+subSectionInfo(sb)+"\n");
      List<Control> cs=new ArrayList<>();
      if(sb instanceof SubSectionDefault){ RexObjectList<ControlListForEachSeparatedPage> cls=((SubSectionDefault)sb).getControlListForEachSeparatedPageList(); for(int k=0;k<cls.size();k++){ RexObjectList<Control> cl=(RexObjectList<Control>)(RexObjectList<?>)cls.get(k).getControlList(); for(int x=0;x<cl.size();x++) cs.add(cl.get(x)); if(k>0&&cl.size()>0) b.append("      (컨트롤 리스트 #"+k+" — 별도 페이지 리스트)\n"); } }
      else if(!(sb instanceof SubSectionSubreport)) collectControls(sb, cs, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
      for(Control c: cs){
        Object f=go(c,"getApplyValueField"); String txt=g(c,"getApplyValueText");
        String bind="";
        if(f!=null) bind=" ["+fieldKindKo(f)+":"+nameOf(f)+"]";
        else if(txt!=null && !txt.isEmpty()) bind=" [텍스트:\""+txt+"\"]";
        String w=g(c,"getWidth"), ht=g(c,"getHeight");
        String pos="위치(왼"+g(c,"getX1")+",위"+g(c,"getY1")+",너비"+(w==null?"?":w)+",높이"+(ht==null?"?":ht)+")";
        StringBuilder ex=new StringBuilder();
        String fmt=g(c,"getOutputFormat"); if(fmt!=null && !fmt.isEmpty()) ex.append(" 출력양식="+fmt);
        if("true".equals(g(c,"getCanGrow"))) ex.append(" 확장가능");
        String font=g(go(c,"getTextInfo"),"getFontName"); if(font!=null && !font.isEmpty()) ex.append(" 폰트="+font);
        if(c instanceof ControlSubreport){ ControlSubreport sr=(ControlSubreport)c; ex.append(subreportInfo(sr.getLinkedSubreportPath(), sr.getSubreport(), sr.getFieldLinkListForSubReportParameter())); }
        if(!c.getVisible()) ex.append(" [숨김]");
        b.append("      - "+c.getClass().getSimpleName()+" \""+c.getName()+"\""+bind+"  "+pos+ex+"\n");
        if("ControlTable".equals(c.getClass().getSimpleName())){   // 표: 셀별 바인딩 그리드
          Object rc=go(c,"getRowCount"), cc=go(c,"getColumnCount");
          if(rc instanceof Integer && cc instanceof Integer){ int rows=(Integer)rc, colsN=(Integer)cc;
            for(int rr=0;rr<rows && rr<40;rr++){ StringBuilder row=new StringBuilder("          ["+rr+"] ");
              for(int cn=0;cn<colsN && cn<20;cn++){ Object cell=null; try{ cell=c.getClass().getMethod("getTableCell",int.class,int.class).invoke(c,rr,cn); }catch(Exception e){}
                Object cf=go(cell,"getApplyValueField"); String ct=g(cell,"getApplyValueText");
                String cb = !isNormalCell(cell)? "‹병합›" : cf!=null? fieldKindKo(cf)+":"+nameOf(cf) : (ct!=null && !ct.isEmpty()? "\""+ct+"\"" : "·");
                String cfmt=g(cell,"getOutputFormat"); if(isNormalCell(cell) && cfmt!=null && !cfmt.isEmpty()) cb+="{"+cfmt+"}";
                row.append(cb).append(cn<colsN-1 && cn<19?" | ":""); }
              b.append(row).append("\n"); } }
        }
      } } }
    return b.toString();
  }
  static void collectControls(Object o,List<Control> out,Set<Object> seen,int d){
    if(o==null||d>14) return;
    if(o instanceof RexObjectList){ RexObjectList<?> l=(RexObjectList<?>)o; for(int i=0;i<l.size();i++) collectControls(l.get(i),out,seen,d); return; }
    if(!o.getClass().getName().startsWith("com.clipsoft.clipreport")) return; if(!seen.add(o)) return;
    if(d>0 && o instanceof Section) return;   // don't cross into sibling/linked sections
    if(o instanceof Control){ out.add((Control)o); if(o instanceof ControlSubreport) return; }  // don't descend into a subreport's own report
    for(java.lang.reflect.Method m:o.getClass().getMethods()){ if(m.getParameterCount()!=0||!m.getName().startsWith("get")||m.getName().equals("getClass"))continue;
      Class<?> rt=m.getReturnType(); if(rt.isPrimitive()||rt==String.class||rt.isEnum()||rt==Class.class)continue;
      String nm=m.getName(); if(nm.equals("getApplyValueField")||nm.equals("getParentObj")||nm.equals("getSubreport"))continue;
      try{ collectControls(m.invoke(o),out,seen,d+1);}catch(Throwable t){} }
  }

  @SuppressWarnings("unchecked")
  static String addGroup(String path,String column,String output) throws Exception {
    TheReportFile rf=open(path);
    Report rep=rf.getGlobe().getMainReport();
    DataSet ds=rf.getGlobe().getGlobalObjectManager().getDataSetList().get(0);
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    FieldData gf=null; for(int i=0;i<fl.size();i++) if(column.equalsIgnoreCase(fl.get(i).getName())) gf=fl.get(i);
    if(gf==null) return "ERROR: column '"+column+"' not found in dataset "+ds.getName();
    Group g=new Group(2800); g.setGroupingField(gf); g.setSortMethod(SortMethod.Ascending); g.setDataTypeCasting(SortDataTypeCasting.String); g.setTotalVisible(true); g.setLabelVisible(true);
    rep.getReportObjectManager().getGroupList().add(g);
    MainPage mp=rep.getReportDesign().getMainPage(); RexObjectList<Section> secs=mp.getSectionList();
    SectionGroupHeader gh=new SectionGroupHeader(); gh.setGroup(g); gh.getSubSectionList().add(band("그룹 머리글["+column+"]",60));
    SectionGroupFooter gfoot=new SectionGroupFooter(); gfoot.getSubSectionList().add(band("그룹 바닥글["+column+"]",50));
    int di=-1; for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionDetail) di=i;
    if(di<0){ secs.add(gh); secs.add(gfoot); } else { secs.add(di,gh); secs.add(di+2,gfoot); }
    save(rf,output,path);
    return "OK: added group on "+column+" (header+footer bands), wrote "+output;
  }

  @SuppressWarnings("unchecked")
  static String placeDetailFields(String path,String output) throws Exception {
    TheReportFile rf=open(path);
    DataSet ds=rf.getGlobe().getGlobalObjectManager().getDataSetList().get(0);
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    if(fl.size()==0) return "ERROR: dataset "+ds.getName()+" has no fields";
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    SectionDetail det=null; for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionDetail) det=(SectionDetail)secs.get(i);
    if(det==null) return "ERROR: no Detail band";
    SubSectionDefault sd=firstSub(det); if(sd==null) return "ERROR: no detail subsection";
    RexObjectList<Control> cl=(RexObjectList<Control>) clpOf(sd).getControlList();
    int n=fl.size(), colW=Math.max(150, Math.min(400, 2600/Math.max(1,n)));
    for(int i=0;i<n;i++){ FieldData f=fl.get(i); ControlLabel c=new ControlLabel(); c.setName(uniqueControlName(rf,"dat_"+f.getName())); c.setVisible(true);
      c.setX1(i*colW); c.setY1(0); c.setWidth(colW); c.setHeight(55);
      c.setApplyValueType(ApplyValueType.Field); c.setApplyValueField(f); cl.add(c); }
    save(rf,output,path);
    return "OK: placed "+n+" field-bound data labels in Detail band, wrote "+output;
  }
}
