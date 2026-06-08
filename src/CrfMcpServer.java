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
    "쓰기 도구는 항상 output 경로를 따로 받아 원본을 보존합니다.\n"+
    "[★열린 파일 주의] .crf가 CLIP report 앱에서 열려 있는 동안 쓰기 도구로 수정하면 파일 잠금/상태 충돌(앱에서 저장 시 편집이 덮어써짐, 또는 편집이 앱에 반영 안 됨)이 납니다. 이미 만든 _edited.crf에 추가 수정이 필요할 때 그 파일이 열려 있을 수 있으면, 먼저 유저에게 '저장 후 잠깐 닫기'를 요청하고 → 수정 → '다시 열기'를 안내하세요(저장→닫기→수정→재오픈).";

  // ---- reflection helpers for rich, defensive property reads ----
  static String g(Object o,String m){ if(o==null)return null; try{ Object v=o.getClass().getMethod(m).invoke(o); return v==null?null:v.toString(); }catch(Exception e){ return null; } }
  static Object go(Object o,String m){ if(o==null)return null; try{ return o.getClass().getMethod(m).invoke(o); }catch(Exception e){ return null; } }
  static String nameOf(Object o){ String n=g(o,"getName"); return n==null?o.getClass().getSimpleName():n; }
  static String fieldKindKo(Object f){ if(f==null)return null; switch(f.getClass().getSimpleName()){
    case "FieldData": return "데이터"; case "FieldFormula": return "공식"; case "FieldGlobalSpecial": return "시스템";
    case "FieldGlobalParameter": case "FieldParameter": case "FieldReportParameter": return "매개변수";
    case "FieldRunningTotal": return "누적합산"; case "FieldGroupName": return "그룹이름"; case "FieldGroupIndex": return "그룹인덱스";
    default: return f.getClass().getSimpleName(); } }
  static void inv(StringBuilder b,String label,RexObjectList<?> l){ if(l==null||l.size()==0)return; int max=Math.min(l.size(),30);
    b.append("  "+label+"필드("+l.size()+"): "); for(int i=0;i<max;i++){ b.append(nameOf(l.get(i))); if(i<max-1)b.append(", "); } if(l.size()>max)b.append(" …"); b.append("\n"); }

  public static void main(String[] x) throws Exception {
    mcp = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
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
  @SuppressWarnings("unchecked")
  static JSONObject initResult(){ JSONObject r=new JSONObject(); r.put("protocolVersion","2024-11-05"); JSONObject caps=new JSONObject(); caps.put("tools",new JSONObject()); r.put("capabilities",caps); JSONObject si=new JSONObject(); si.put("name","clip-report-mcp"); si.put("version","0.2"); r.put("serverInfo",si); r.put("instructions",INSTRUCTIONS); return r; }

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
    arr.add(tool("crf_set_query","Replace the SQL of a report's first dataset and save to a new file.",
        strSchema(new String[]{"path","sql","output"}, "path","source .crf", "sql","new SQL (with {parameter.X} tokens)", "output","destination .crf")));
    arr.add(tool("crf_list_reports","List .crf report files under a directory (recursive).",
        strSchema(new String[]{"dir"}, "dir","directory to scan")));
    arr.add(tool("crf_describe_layout","Describe a report's section bands and the controls in each, including which field each control is bound to.",
        strSchema(new String[]{"path"}, "path",".crf file")));
    arr.add(tool("crf_add_group","Add a GROUP BY group (group header + footer bands) on a column to an existing report, and save to a new file.",
        strSchema(new String[]{"path","column","output"}, "path","source .crf", "column","field name to group by", "output","destination .crf")));
    arr.add(tool("crf_place_detail_fields","Place a field-bound data label in the DETAIL band for every field of the first dataset (a simple list row), and save to a new file.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "output","destination .crf")));
    arr.add(tool("crf_set_cell","Edit one table cell: bind it to a field (by name), and/or set static text, and/or set an output format. Use the table name and row/col from crf_describe_layout. Saves to a new file.",
        strSchema(new String[]{"path","table","row","col","output"}, "path","source .crf", "table","ControlTable name (from describe_layout)", "row","row index (0-based)", "col","column index (0-based)", "field","field name to bind (optional)", "text","static text (optional)", "format","output format string e.g. #,##0 (optional)", "output","destination .crf")));
    arr.add(tool("crf_add_formula_field","Create a formula (computed) field with a JavaScript expression that MUST end with `return`. Refs via rexpert.field(\"data.COL\"); aggregates via rexpert.sum/avg/count/min/max(0,\"data.COL\",0,\"\",\"\"). Then bind it to a cell with crf_set_cell. Saves to a new file.",
        strSchema(new String[]{"path","name","script","output"}, "path","source .crf", "name","new formula field name", "script","JavaScript, MUST end with return;. Field ref=rexpert.field(\"ns.COL\") (ns=data/system/parameter/formula/runningtotal). Aggregate=rexpert.sum(범위,\"data.COL\",옵션,\"그룹|''\",\"조건식|''\"). e.g.  return rexpert.sum(0,\"data.PRVDD_BAL_AMT\",0,\"\",\"\");", "output","destination .crf")));
    arr.add(tool("crf_set_cell_style","Style a table cell: background color (hex #RRGGBB), font name, can-grow, and merge-duplicate. Saves to a new file.",
        strSchema(new String[]{"path","table","row","col","output"}, "path","source .crf", "table","ControlTable name", "row","row index", "col","col index", "bgcolor","background hex #RRGGBB (optional)", "font","font name e.g. 굴림 (optional)", "cangrow","true/false (optional)", "merge","true/false: merge duplicate values (optional)", "output","destination .crf")));
    arr.add(tool("crf_add_data_field","Add a data field (column) to the first dataset. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","field name", "type","String|Number|Currency|DateTime|Boolean (default String)", "output","destination .crf")));
    arr.add(tool("crf_add_label","Add a 글상자(label) to a section band, bound to a field or with static text. Saves to a new file.",
        strSchema(new String[]{"path","section","output"}, "path","source .crf", "section","band: 보고서머리글|페이지머리글|데이터머리글|본문|데이터바닥글|페이지바닥글|보고서바닥글|그룹머리글|그룹바닥글 (or English ReportHeader/PageHeader/Detail/...)", "text","static text (optional)", "field","field name to bind (optional)", "left","X (optional)", "top","Y (optional)", "width","W (optional)", "height","H (optional)", "output","destination .crf")));
    arr.add(tool("crf_set_paper","Set paper type/orientation/margins. Saves to a new file.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "paper","A4|A3|B4|B5|Letter ... (optional)", "orientation","Potrait|Landscape (optional)", "marginL","left margin (optional)", "marginT","top (optional)", "marginR","right (optional)", "marginB","bottom (optional)", "output","destination .crf")));
    arr.add(tool("crf_diff","Compare two reports: datasets/fields/groups/sections and what was added or removed.",
        strSchema(new String[]{"a","b"}, "a","first .crf", "b","second .crf")));
    // ---- DB tools (도메인 테이블 조회로 업무지식 확보; 접속정보는 env CLIP_DB_URL/USER/PWD) ----
    arr.add(tool("db_query","Run SQL against the connected DB (Tibero/Oracle) and return rows as TSV (capped). SELECT 권장. Tibero: ROWNUM<=N, SYSDATE, DUAL.",
        strSchema(new String[]{"sql"}, "sql","SQL statement", "max","max rows (default 200)")));
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
  static JSONObject textContent(String text){ JSONObject c=new JSONObject(); c.put("type","text"); c.put("text",text); JSONArray a=new JSONArray(); a.add(c); JSONObject r=new JSONObject(); r.put("content",a); return r; }

  @SuppressWarnings("unchecked")
  static JSONObject callTool(JSONObject params) throws Exception {
    String name=(String)params.get("name"); JSONObject args=(JSONObject)params.get("arguments"); if(args==null) args=new JSONObject();
    switch(name){
      case "crf_summary":  return textContent(summary((String)args.get("path")));
      case "crf_generate": return textContent(runGen((String)args.get("template"),(String)args.get("sql"),(String)args.get("output")));
      case "crf_set_query":return textContent(setQuery((String)args.get("path"),(String)args.get("sql"),(String)args.get("output")));
      case "crf_list_reports":return textContent(listReports((String)args.get("dir")));
      case "crf_describe_layout":return textContent(describeLayout((String)args.get("path")));
      case "crf_add_group":return textContent(addGroup((String)args.get("path"),(String)args.get("column"),(String)args.get("output")));
      case "crf_place_detail_fields":return textContent(placeDetailFields((String)args.get("path"),(String)args.get("output")));
      case "crf_set_cell":return textContent(setCell(args));
      case "crf_add_formula_field":return textContent(addFormulaField((String)args.get("path"),(String)args.get("name"),(String)args.get("script"),(String)args.get("output")));
      case "crf_set_cell_style":return textContent(setCellStyle(args));
      case "crf_add_data_field":return textContent(addDataField((String)args.get("path"),(String)args.get("name"),(String)args.get("type"),(String)args.get("output")));
      case "crf_add_label":return textContent(addLabel(args));
      case "crf_set_paper":return textContent(setPaper(args));
      case "crf_diff":return textContent(diff((String)args.get("a"),(String)args.get("b")));
      case "db_query":return textContent(runSql((String)args.get("sql"), pInt(args.get("max"),200)));
      case "db_tables":return textContent(dbTables((String)args.get("like")));
      case "db_columns":return textContent(dbColumns((String)args.get("table")));
      case "db_sample":{ int mx=pInt(args.get("max"),20); return textContent(runSql("SELECT * FROM "+safeName((String)args.get("table"))+" WHERE ROWNUM <= "+mx, mx+5)); }
      case "pdf_text":return textContent(pdfText((String)args.get("path")));
      default: throw new RuntimeException("unknown tool "+name);
    }
  }

  static String runGen(String template,String sql,String output) throws Exception {
    Path tmp=Files.createTempFile("crfmcp",".sql"); Files.write(tmp, sql.getBytes("UTF-8"));
    ByteArrayOutputStream buf=new ByteArrayOutputStream(); PrintStream old=System.out;
    System.setOut(new PrintStream(buf,true,"UTF-8"));
    try{ CrfGen3.main(new String[]{template, tmp.toString(), output}); }
    finally{ System.setOut(old); Files.deleteIfExists(tmp); }
    return buf.toString("UTF-8");
  }

  @SuppressWarnings("unchecked")
  static String summary(String path) throws Exception {
    TheReportFile rf=Rexpert4.read(path);
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    Report rep=rf.getGlobe().getMainReport();
    StringBuilder b=new StringBuilder();
    b.append("title=").append(rf.getTitle()).append("  version=").append(rf.getVersion()).append("\n");
    RexObjectList<DataSet> dss=gom.getDataSetList();
    b.append("datasets=").append(dss.size()).append("\n");
    for(int i=0;i<dss.size();i++){ DataSet ds=dss.get(i);
      RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
      b.append("  DS[").append(i).append("] ").append(ds.getName()).append("  fields(").append(fl.size()).append("): ");
      for(int j=0;j<fl.size() && j<40;j++) b.append(fl.get(j).getName()).append(j<fl.size()-1?",":"");
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
    inv(b,"매개변수", gom.getFieldGlobalParameterList());
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
    b.append("sections(").append(secs.size()).append("): ");
    for(int i=0;i<secs.size();i++) b.append(secs.get(i).getClass().getSimpleName().replace("Section","")).append(i<secs.size()-1?", ":"");
    return b.toString();
  }

  static void call(Object o,String m,Class<?> pt,Object arg){ try{ o.getClass().getMethod(m,pt).invoke(o,arg); }catch(Exception e){ System.err.println("call "+m+": "+e); } }

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
    TheReportFile rf=Rexpert4.read(path);
    Control tbl=findTable(rf,table); if(tbl==null) return "ERROR: table '"+table+"' not found (use crf_describe_layout for names)";
    Object cell=null; try{ cell=tbl.getClass().getMethod("getTableCell",int.class,int.class).invoke(tbl,row,col); }catch(Exception e){}
    if(cell==null) return "ERROR: cell ["+row+","+col+"] not found";
    StringBuilder did=new StringBuilder();
    if(field!=null && !field.isEmpty()){ Field f=findField(rf,field); if(f==null) return "ERROR: field '"+field+"' not found";
      call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Field); call(cell,"setApplyValueField",Field.class,f); did.append(" field="+field+"("+fieldKindKo(f)+")"); }
    else if(text!=null){ call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Text); call(cell,"setApplyValueText",String.class,text); did.append(" text=\""+text+"\""); }
    if(format!=null && !format.isEmpty()){ call(cell,"setOutputFormat",String.class,format); did.append(" format="+format); }
    if(did.length()==0) return "ERROR: nothing to set (provide field, text, or format)";
    Rexpert4.write(rf,output);
    return "OK: "+table+"["+row+","+col+"] set"+did+", wrote "+output;
  }
  @SuppressWarnings("unchecked")
  static String addFormulaField(String path,String name,String script,String output) throws Exception {
    TheReportFile rf=Rexpert4.read(path);
    var rom=rf.getGlobe().getMainReport().getReportObjectManager();
    FieldFormula ff=new FieldFormula(); ff.setName(name); ff.setScript(script); ff.setScriptType(ScriptType.JavaScript);
    ((RexObjectList<FieldFormula>) rom.getFieldFormulaList()).add(ff);
    Rexpert4.write(rf,output);
    TheReportFile v=Rexpert4.read(output);
    int n=v.getGlobe().getMainReport().getReportObjectManager().getFieldFormulaList().size();
    return "OK: added formula field '"+name+"' = "+script+" (formula fields now "+n+"), wrote "+output;
  }

  static int parseColor(String s){ s=s.trim().replace("#",""); if(s.matches("[0-9a-fA-F]{6}")){ int r=Integer.parseInt(s.substring(0,2),16),g=Integer.parseInt(s.substring(2,4),16),b=Integer.parseInt(s.substring(4,6),16); return (b<<16)|(g<<8)|r; } return Integer.parseInt(s); }
  static int pInt(Object o,int def){ try{ return Integer.parseInt(((String)o).trim()); }catch(Exception e){ return def; } }

  static String setCellStyle(JSONObject args) throws Exception {
    String path=(String)args.get("path"), table=(String)args.get("table"), output=(String)args.get("output");
    int row=pInt(args.get("row"),0), col=pInt(args.get("col"),0);
    String bg=(String)args.get("bgcolor"), font=(String)args.get("font"), cg=(String)args.get("cangrow"), mg=(String)args.get("merge");
    TheReportFile rf=Rexpert4.read(path);
    Control tbl=findTable(rf,table); if(tbl==null) return "ERROR: table '"+table+"' not found";
    Object cell=null; try{ cell=tbl.getClass().getMethod("getTableCell",int.class,int.class).invoke(tbl,row,col); }catch(Exception e){}
    if(cell==null) return "ERROR: cell ["+row+","+col+"] not found";
    StringBuilder did=new StringBuilder();
    if(bg!=null && !bg.isEmpty()){ call(cell,"setBackStyle",BackStyleType.class,BackStyleType.Normal); call(cell,"setBackColor",int.class,parseColor(bg)); did.append(" 배경="+bg); }
    if(font!=null && !font.isEmpty()){ Object ti=go(cell,"getTextInfo"); if(ti!=null){ call(ti,"setFontName",String.class,font); did.append(" 폰트="+font); } }
    if(cg!=null){ call(cell,"setCanGrow",boolean.class,Boolean.parseBoolean(cg)); did.append(" 확장가능="+cg); }
    if(mg!=null){ call(cell,"setCellMergeRowDataDuplication",boolean.class,Boolean.parseBoolean(mg)); did.append(" 셀합치기="+mg); }
    if(did.length()==0) return "ERROR: nothing to set (bgcolor/font/cangrow/merge)";
    Rexpert4.write(rf,output); return "OK: "+table+"["+row+","+col+"] style"+did+", wrote "+output;
  }

  @SuppressWarnings("unchecked")
  static String addDataField(String path,String name,String type,String output) throws Exception {
    TheReportFile rf=Rexpert4.read(path);
    DataSet ds=rf.getGlobe().getGlobalObjectManager().getDataSetList().get(0);
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    FieldData f=new FieldData(); f.setName(name); try{ f.setDataType(DataType.valueOf(type==null?"String":type)); }catch(Exception e){ f.setDataType(DataType.String); } f.setIndex(fl.size()); fl.add(f);
    Rexpert4.write(rf,output);
    return "OK: added data field '"+name+"' ("+f.getDataType()+") to "+ds.getName()+", wrote "+output;
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
    TheReportFile rf=Rexpert4.read(path);
    Section sec=findOrCreateSection(rf,section); if(sec==null) return "ERROR: section '"+section+"' not found (그룹 밴드는 crf_add_group 사용)";
    SubSectionDefault sd=firstSub(sec); if(sd==null) return "ERROR: section has no subsection";
    ControlListForEachSeparatedPage clp=new ControlListForEachSeparatedPage(); sd.getControlListForEachSeparatedPageList().add(clp);
    RexObjectList<Control> cl=(RexObjectList<Control>) clp.getControlList();
    ControlLabel c=new ControlLabel(); c.setName("label_"+(field!=null?field:"text")); c.setVisible(true);
    c.setX1(pInt(args.get("left"),0)); c.setY1(pInt(args.get("top"),0)); c.setWidth(pInt(args.get("width"),500)); c.setHeight(pInt(args.get("height"),80));
    String did;
    if(field!=null && !field.isEmpty()){ Field f=findField(rf,field); if(f==null) return "ERROR: field '"+field+"' not found"; c.setApplyValueType(ApplyValueType.Field); c.setApplyValueField(f); did="field="+field; }
    else { c.setApplyValueType(ApplyValueType.Text); c.setApplyValueText(text==null?"":text); did="text=\""+(text==null?"":text)+"\""; }
    cl.add(c); Rexpert4.write(rf,output);
    return "OK: added label("+did+") to "+sec.getClass().getSimpleName().replace("Section","")+", wrote "+output;
  }

  static String setPaper(JSONObject args) throws Exception {
    String path=(String)args.get("path"), output=(String)args.get("output");
    TheReportFile rf=Rexpert4.read(path); MainPage mp=rf.getGlobe().getMainReport().getReportDesign().getMainPage();
    StringBuilder did=new StringBuilder();
    String pp=(String)args.get("paper"); if(pp!=null && !pp.isEmpty()){ try{ mp.setPaperType(com.clipsoft.clipreport.common.enums.PaperType.valueOf(pp)); did.append(" 용지="+pp); }catch(Exception e){ return "ERROR: unknown paper '"+pp+"'"; } }
    String or=(String)args.get("orientation"); if(or!=null && !or.isEmpty()){ try{ mp.setPaperOrientationType(com.clipsoft.clipreport.common.enums.PaperOrientation.valueOf(or)); mp.setPaperOrientationUse(true); did.append(" 방향="+or); }catch(Exception e){ return "ERROR: unknown orientation '"+or+"' (Potrait|Landscape)"; } }
    if(args.get("marginL")!=null){ mp.setLeftMargin(pInt(args.get("marginL"),mp.getLeftMargin())); did.append(" L"+mp.getLeftMargin()); }
    if(args.get("marginT")!=null){ mp.setTopMargin(pInt(args.get("marginT"),mp.getTopMargin())); did.append(" T"+mp.getTopMargin()); }
    if(args.get("marginR")!=null){ mp.setRightMargin(pInt(args.get("marginR"),mp.getRightMargin())); did.append(" R"+mp.getRightMargin()); }
    if(args.get("marginB")!=null){ mp.setBottomMargin(pInt(args.get("marginB"),mp.getBottomMargin())); did.append(" B"+mp.getBottomMargin()); }
    if(did.length()==0) return "ERROR: nothing to set";
    Rexpert4.write(rf,output); return "OK: paper"+did+", wrote "+output;
  }

  @SuppressWarnings("unchecked")
  static java.util.Set<String> dataFieldNames(TheReportFile rf){ java.util.Set<String> s=new java.util.TreeSet<>();
    RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    for(int i=0;i<dss.size();i++){ RexObjectList<FieldData> fl=(RexObjectList<FieldData>) dss.get(i).getFieldDataList(); for(int j=0;j<fl.size();j++) s.add(fl.get(j).getName()); } return s; }
  static String sectionsOf(TheReportFile rf){ RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList(); StringBuilder b=new StringBuilder(); for(int i=0;i<secs.size();i++) b.append(secs.get(i).getClass().getSimpleName().replace("Section","")).append(i<secs.size()-1?",":""); return b.toString(); }
  static String diff(String a,String b) throws Exception {
    TheReportFile A=Rexpert4.read(a), B=Rexpert4.read(b);
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
  static String runSql(String sql,int maxRows) throws Exception {
    StringBuilder b=new StringBuilder();
    try(Connection c=db(); Statement st=c.createStatement()){
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

  @SuppressWarnings("unchecked")
  static String setQuery(String path,String sql,String output) throws Exception {
    TheReportFile rf=Rexpert4.read(path);
    DataSet ds=rf.getGlobe().getGlobalObjectManager().getDataSetList().get(0);
    DataAccessMethodSQL q=ds.getDataSetItemNormal().getDataAccessMethodSQL();
    String conv=CrfGen2.normParamTokens(CrfGen2.subParamsQuoted(sql));   // :colNm/#{}/${} -> '{parameter.COLNM}'
    q.setQueryString(conv);
    Rexpert4.write(rf, output);
    return "OK: set query on "+ds.getName()+" ("+conv.length()+" chars)"+(conv.equals(sql)?"":" — 파라미터를 {parameter.X} 형식으로 정규화함")+", wrote "+output;
  }

  static String listReports(String dir) throws Exception {
    StringBuilder b=new StringBuilder("reports under "+dir+":\n"); int[] n={0};
    try(java.util.stream.Stream<Path> s=Files.walk(Paths.get(dir))){
      s.filter(p->p.toString().toLowerCase().endsWith(".crf")).limit(200).forEach(p->{ n[0]++;
        try{ b.append("  ").append(p).append("  (").append(Files.size(p)/1024).append(" KB)\n"); }catch(Exception e){} }); }
    b.append("total shown: ").append(n[0]); return b.toString();
  }

  static SubSectionDefault band(String name,int h){ SubSectionDefault sd=new SubSectionDefault(); sd.setName(name); sd.setHeight(h); sd.setVisible(true); sd.getControlListForEachSeparatedPageList().add(new ControlListForEachSeparatedPage()); return sd; }
  static SubSectionDefault firstSub(Section sec){ RexObjectList<SubSection> ss=sec.getSubSectionList(); if(ss==null||ss.size()==0)return null; SubSection s=ss.get(0); return (s instanceof SubSectionDefault)?(SubSectionDefault)s:null; }

  @SuppressWarnings("unchecked")
  static String describeLayout(String path) throws Exception {
    TheReportFile rf=Rexpert4.read(path);
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    StringBuilder b=new StringBuilder();
    for(int i=0;i<secs.size();i++){ Section sec=secs.get(i); b.append("["+i+"] "+sec.getClass().getSimpleName().replace("Section","")+"\n");
      List<Control> cs=new ArrayList<>(); collectControls(sec, cs, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
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
        b.append("      - "+c.getClass().getSimpleName()+" \""+c.getName()+"\""+bind+"  "+pos+ex+"\n");
        if("ControlTable".equals(c.getClass().getSimpleName())){   // 표: 셀별 바인딩 그리드
          Object rc=go(c,"getRowCount"), cc=go(c,"getColumnCount");
          if(rc instanceof Integer && cc instanceof Integer){ int rows=(Integer)rc, colsN=(Integer)cc;
            for(int rr=0;rr<rows && rr<40;rr++){ StringBuilder row=new StringBuilder("          ["+rr+"] ");
              for(int cn=0;cn<colsN && cn<20;cn++){ Object cell=null; try{ cell=c.getClass().getMethod("getTableCell",int.class,int.class).invoke(c,rr,cn); }catch(Exception e){}
                Object cf=go(cell,"getApplyValueField"); String ct=g(cell,"getApplyValueText");
                String cb = cf!=null? fieldKindKo(cf)+":"+nameOf(cf) : (ct!=null && !ct.isEmpty()? "\""+ct+"\"" : "·");
                row.append(cb).append(cn<colsN-1 && cn<19?" | ":""); }
              b.append(row).append("\n"); } }
        }
      } }
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
    TheReportFile rf=Rexpert4.read(path);
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
    Rexpert4.write(rf, output);
    return "OK: added group on "+column+" (header+footer bands), wrote "+output;
  }

  @SuppressWarnings("unchecked")
  static String placeDetailFields(String path,String output) throws Exception {
    TheReportFile rf=Rexpert4.read(path);
    DataSet ds=rf.getGlobe().getGlobalObjectManager().getDataSetList().get(0);
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    if(fl.size()==0) return "ERROR: dataset "+ds.getName()+" has no fields";
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    SectionDetail det=null; for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionDetail) det=(SectionDetail)secs.get(i);
    if(det==null) return "ERROR: no Detail band";
    SubSectionDefault sd=firstSub(det); if(sd==null) return "ERROR: no detail subsection";
    ControlListForEachSeparatedPage clp=new ControlListForEachSeparatedPage(); sd.getControlListForEachSeparatedPageList().add(clp);
    RexObjectList<Control> cl=(RexObjectList<Control>) clp.getControlList();
    int n=fl.size(), colW=Math.max(150, Math.min(400, 2600/Math.max(1,n)));
    for(int i=0;i<n;i++){ FieldData f=fl.get(i); ControlLabel c=new ControlLabel(); c.setName("dat_"+f.getName()); c.setVisible(true);
      c.setX1(i*colW); c.setY1(0); c.setWidth(colW); c.setHeight(55);
      c.setApplyValueType(ApplyValueType.Field); c.setApplyValueField(f); cl.add(c); }
    Rexpert4.write(rf, output);
    return "OK: placed "+n+" field-bound data labels in Detail band, wrote "+output;
  }
}
