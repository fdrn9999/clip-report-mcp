import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.reports.Report;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.controls.*;
import com.clipsoft.clipreport.base.controls.Tables.*;
import com.clipsoft.clipreport.base.groups.Group;
import com.clipsoft.clipreport.base.datas.*;
import com.clipsoft.clipreport.base.datas.fields.*;
import com.clipsoft.clipreport.base.enums.*;
import com.clipsoft.clipreport.base.RexObjectList;
import org.json.simple.*;
import java.util.*;

/**
 * v0.10.0 — 목록형 리포트 파생 도구 모음.
 *  crf_set_columns            : 본문 표(+제목 표, +합계 표)를 columns[] 로 다시 구성 (열 세트 전체 교체)
 *  crf_append_query_condition : JS 동적쿼리에 매개변수 조건 if 블록 추가
 *  crf_generate v2            : 템플릿 + SQL + columns[] + 제목/조건/합계 → 목록형 리포트 한 방 파생
 * 모든 함수는 CrfMcpServer 의 헬퍼(open/save/applyProps/findField…)와 CrfTableOps(열 삽입·삭제)를 재사용한다.
 */
public class CrfDerive {
  static String s(JSONObject a,String k){ return CrfMcpServer.s(a,k); }
  static String q(String x){ return CrfMcpServer.q(x); }
  static int pInt(Object o,int d){ return CrfMcpServer.pInt(o,d); }
  static Object go(Object o,String m){ return CrfMcpServer.go(o,m); }
  static String g(Object o,String m){ return CrfMcpServer.g(o,m); }
  static String nameOf(Object o){ return CrfMcpServer.nameOf(o); }

  // ===================== 열 정의 =====================
  static final class Col { String field, title, format, align, text, total; int width=-1; Field f; boolean merge; }
  static List<Col> parseColumns(TheReportFile rf,Object colsArg,boolean requireFields){
    JSONArray arr; try{ arr=colsArg instanceof JSONArray?(JSONArray)colsArg:(JSONArray)CrfMcpServer.P.parse(String.valueOf(colsArg)); }catch(Exception e){ throw new RuntimeException("columns 는 JSON 배열이어야 합니다: "+e.getMessage()); }
    if(arr==null||arr.isEmpty()) throw new RuntimeException("columns 가 비어 있습니다");
    List<Col> out=new ArrayList<>();
    for(int i=0;i<arr.size();i++){ Object o=arr.get(i); Col c=new Col();
      if(o instanceof String){ c.field=((String)o).trim(); }
      else if(o instanceof JSONObject){ JSONObject j=(JSONObject)o; c.field=q(s(j,"field")).trim(); c.title=s(j,"title"); c.format=s(j,"format"); c.align=s(j,"align"); c.text=s(j,"text"); c.total=s(j,"total"); c.merge="true".equalsIgnoreCase(s(j,"merge")); if(j.get("width")!=null) c.width=pInt(j.get("width"),-1); }
      else throw new RuntimeException("columns["+i+"] 는 문자열(필드명) 또는 객체여야 합니다");
      if(c.field.isEmpty()&&c.text==null) throw new RuntimeException("columns["+i+"] field 또는 text 가 필요합니다");
      if(!c.field.isEmpty()){ c.f=CrfMcpServer.findField(rf,c.field); if(c.f==null&&requireFields) throw new RuntimeException("columns["+i+"] 필드 '"+c.field+"' 없음 (crf_summary 로 이름 확인; 쿼리를 먼저 crf_set_query 로 넣으면 필드가 생김)"); }
      if(c.title==null) c.title=c.field.isEmpty()?q(c.text):c.field;
      if(c.total!=null){ String t=c.total.trim().toLowerCase(); if(t.equals("true")||t.isEmpty()) t="sum"; if(!t.matches("sum|avg|count|min|max|false")) throw new RuntimeException("columns["+i+"].total 은 true|sum|avg|count|min|max"); c.total=t.equals("false")?null:t; }
      out.add(c); }
    return out;
  }
  /** 너비 배분: 지정된 열은 그대로, 나머지는 (목표 총너비 − 지정 합) 을 균등. 목표는 opts.width > 현재 표 너비 > 본문 너비. */
  static int[] widthsFor(List<Col> cols,int target){
    int n=cols.size(), fixed=0, free=0; for(Col c: cols){ if(c.width>0) fixed+=c.width; else free++; }
    int[] w=new int[n]; int rest=Math.max(0,target-fixed), each=free>0?rest/free:0, rem=free>0?rest-each*free:0; int k=0;
    for(int i=0;i<n;i++){ Col c=cols.get(i); if(c.width>0) w[i]=c.width; else { w[i]=each+(k==free-1?rem:0); k++; } if(w[i]<1) w[i]=1; }
    return w;
  }
  static boolean numeric(Col c){ if(c.f==null) return false; Object dt=go(c.f,"getDataType"); String t=String.valueOf(dt); if(t.equals("Number")||t.equals("Currency")) return true; return CrfGen2.guessType(c.field)==DataType.Number||CrfGen2.guessType(c.field)==DataType.Currency; }

  // ===================== 표 다시 구성 =====================
  /** 표의 열 수를 n 으로 맞춘다(뒤에서 삽입/삭제 — 기존 열 스타일을 물려받음). */
  static void resizeCols(ControlTable t,int n){
    int cur=t.getColumnCount();
    if(cur<n) CrfTableOps.insertCols(t,cur,n-cur,0,cur-1,false);
    else while(t.getColumnCount()>n) CrfTableOps.deleteCol(t,t.getColumnCount()-1);
  }
  static void setWidths(ControlTable t,int[] w){ for(int i=0;i<w.length;i++) t.getTableColumn(i).setWidth(w[i]); CrfTableOps.reindex(t); }
  static TableCellNormal cell(ControlTable t,int r,int c){ TableCell x=CrfTableOps.cellAt(t,r,c); TableCellNormal b=CrfTableOps.baseOf(x); return b; }
  static int rowOf(ControlTable t,TableCellNormal b){ return b==null||b.getTableRow()==null?-1:t.getTableRowList().indexOf(b.getTableRow()); }
  /** r 행의 병합을 모두 푼다: r 행 기준 셀의 span 은 1×1 로, r 행에서 시작한 세로 병합의 아래 자리들과 r 행의 병합 자리는 일반 셀로 승격, 위에서 내려온 세로 병합은 r 행 직전까지로 줄인다. */
  static void unmergeRow(ControlTable t,int r){
    int rows=t.getRowCount(), cols=t.getColumnCount(); Set<TableCellNormal> basesInRow=Collections.newSetFromMap(new IdentityHashMap<>());
    for(int c=0;c<cols;c++){ TableCell x=CrfTableOps.cellAt(t,r,c); if(x instanceof TableCellNormal) basesInRow.add((TableCellNormal)x); }
    for(int rr=0;rr<rows;rr++) for(int c=0;c<cols;c++){ TableCell x=CrfTableOps.cellAt(t,rr,c); if(!(x instanceof TableCellDumy)) continue; TableCellNormal base=((TableCellDumy)x).getBaseCell(); int br=rowOf(t,base);
      boolean promote = rr==r || basesInRow.contains(base);   // r 행의 자리, 또는 r 행에서 시작한 병합의 아래 자리
      if(!promote) continue;
      TableCellNormal n=CrfTableOps.newCell(t,t.getTableRow(rr),t.getTableColumn(c),base,false,CrfTableOps.uniqueCellName(CrfTableOps.cellNames(t),t.getName()+"_r"+rr+"c"+c)); CrfTableOps.replaceInLists(t,rr,c,x,n);
      if(rr==r && br>=0 && br<r && base.getRowSpan()>r-br) base.setRowSpan(r-br);   // 위에서 내려온 세로 병합은 r 직전까지
      if(rr==r && br>=0 && br<r){ // 같은 기준 셀이 r 행 아래로도 이어졌다면 그 자리들도 승격(기준이 r 행을 건너뛸 수 없음)
        for(int r2=r+1;r2<rows;r2++){ TableCell y=CrfTableOps.cellAt(t,r2,c); if(y instanceof TableCellDumy && ((TableCellDumy)y).getBaseCell()==base){ TableCellNormal n2=CrfTableOps.newCell(t,t.getTableRow(r2),t.getTableColumn(c),base,false,CrfTableOps.uniqueCellName(CrfTableOps.cellNames(t),t.getName()+"_r"+r2+"c"+c)); CrfTableOps.replaceInLists(t,r2,c,y,n2); } } } }
    for(TableCellNormal b: basesInRow){ b.setColSpan(1); b.setRowSpan(1); }
    CrfTableOps.reindex(t); }
  static void bindField(TheReportFile rf,Object cell,Col c){
    if(c.f!=null){ CrfMcpServer.call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Field); CrfMcpServer.call(cell,"setApplyValueField",Field.class,c.f); }
    else { CrfMcpServer.call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Text); CrfMcpServer.call(cell,"setApplyValueText",String.class,q(c.text)); try{ CrfMcpServer.call(cell,"setApplyValueField",Field.class,null); }catch(Throwable e){} }
    CrfMcpServer.call(cell,"setOutputFormat",String.class,c.format==null?"":c.format);
    if(c.align!=null&&!c.align.isEmpty()){ JSONObject a=new JSONObject(); a.put("align",c.align); CrfMcpServer.applyProps(rf,cell,a,"F"); }
  }
  static void setText(TheReportFile rf,Object cell,String text,String align){
    CrfMcpServer.call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Text); CrfMcpServer.call(cell,"setApplyValueText",String.class,q(text)); try{ CrfMcpServer.call(cell,"setApplyValueField",Field.class,null); }catch(Throwable e){}
    CrfMcpServer.call(cell,"setOutputFormat",String.class,"");
    if(align!=null&&!align.isEmpty()){ JSONObject a=new JSONObject(); a.put("align",align); CrfMcpServer.applyProps(rf,cell,a,"F"); }
  }
  /** 합계 공식 필드 생성(있으면 재사용): F_TOTAL_<fn> = return rexpert.sum(0,"data.FN",0,"","") (그룹 기준 groupField 가 있으면 4번째 인자) */
  @SuppressWarnings("unchecked")
  static FieldFormula totalFormula(TheReportFile rf,String fn,String func,String groupField){
    String script="return rexpert."+func+"(0,\"data."+fn+"\",0,\""+(groupField==null?"":"data."+groupField)+"\",\"\");";
    String base=(groupField==null?"F_TOTAL_":"F_SUB_")+(func.equals("sum")?"":func.toUpperCase()+"_")+fn; String name=base;
    for(int i=1;;i++){ Field ex=CrfMcpServer.findField(rf,name); if(ex==null) break; if(ex instanceof FieldFormula && script.equals(q(((FieldFormula)ex).getScript()))) return (FieldFormula)ex; name=base+"_"+(i+1); }
    FieldFormula ff=new FieldFormula(); ff.setName(name); ff.setScript(script); ff.setScriptType(ScriptType.JavaScript);
    ((RexObjectList<FieldFormula>) rf.getGlobe().getMainReport().getReportObjectManager().getFieldFormulaList()).add(ff); return ff;
  }

  /** 본문 표 찾기: 이름이 주어지면 그것, 아니면 본문 밴드에서 가장 넓은 표. */
  static ControlTable bodyTable(TheReportFile rf,String name){
    if(name!=null&&!name.trim().isEmpty()){ Control t=CrfMcpServer.findTable(rf,name.trim()); if(!(t instanceof ControlTable)) throw new RuntimeException("표 '"+name+"' 없음"); return (ControlTable)t; }
    ControlTable best=null; for(Object[] e: CrfMcpServer.allControls(rf)) if(e[0] instanceof SectionDetail && e[3] instanceof ControlTable){ ControlTable t=(ControlTable)e[3]; if(best==null||t.getWidth()>best.getWidth()) best=t; }
    return best;
  }
  /** 같은 열 구조(열 수 같고 경계가 ±10 안)로 보이는 표를 밴드 종류별로 찾는다. */
  static ControlTable siblingTable(TheReportFile rf,ControlTable body,String name,Class<?> bandType,boolean header){
    if(name!=null&&!name.trim().isEmpty()){ if(name.trim().equalsIgnoreCase("none")) return null; Control t=CrfMcpServer.findTable(rf,name.trim()); if(!(t instanceof ControlTable)) throw new RuntimeException("표 '"+name+"' 없음"); return (ControlTable)t; }
    List<Integer> bb=CrfMcpServer.tableBounds(body); ControlTable best=null; int bestScore=-1;
    for(Object[] e: CrfMcpServer.allControls(rf)){ if(!(e[3] instanceof ControlTable)||e[3]==body) continue; Section sec=(Section)e[0]; boolean ok=header?(sec instanceof SectionPageHeader||sec instanceof SectionDataHeader||sec instanceof SectionReportHeader):(sec instanceof SectionDataFooter||sec instanceof SectionReportFooter);
      if(!ok) continue; ControlTable t=(ControlTable)e[3]; if(t.getColumnCount()!=body.getColumnCount()) continue; List<Integer> tb=CrfMcpServer.tableBounds(t); int score=0; for(int i=0;i<tb.size();i++) if(Math.abs(tb.get(i)-bb.get(i))<=10) score++;
      if(score>bestScore){ best=t; bestScore=score; } }
    return bestScore>=2?best:null;   // 경계가 2개 미만이면 다른 용도의 표로 보고 건드리지 않음
  }

  static final class Rebuild { List<String> notes=new ArrayList<>(); List<String> warns=new ArrayList<>(); int[] widths; ControlTable body, head, foot; }
  /** 그룹 바닥글의 표 중 본문과 열 수가 같은 것 → [표, 그룹 필드명] (그룹 머리글 표는 건드리지 않음) */
  static List<Object[]> groupFooterTables(TheReportFile rf,ControlTable body){
    List<Object[]> out=new ArrayList<>(); RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList(); int di=CrfMcpServer.detailIndex(secs);
    List<Section> heads=new ArrayList<>(); for(int i=0;i<di;i++) if(secs.get(i) instanceof SectionGroupHeader) heads.add(secs.get(i));
    List<Section> foots=new ArrayList<>(); for(int i=di+1;i<secs.size();i++) if(secs.get(i) instanceof SectionGroupFooter) foots.add(secs.get(i));
    for(Object[] e: CrfMcpServer.allControls(rf)){ if(!(e[0] instanceof SectionGroupFooter)||!(e[3] instanceof ControlTable)) continue; ControlTable t=(ControlTable)e[3]; if(t.getColumnCount()!=body.getColumnCount()) continue;
      int fi=foots.indexOf(e[0]); int hi=heads.size()-1-fi; String gf=null; if(hi>=0&&hi<heads.size()){ Group gr=((SectionGroupHeader)heads.get(hi)).getGroup(); if(gr!=null&&gr.getGroupingField()!=null) gf=nameOf(gr.getGroupingField()); }
      out.add(new Object[]{t,gf}); }
    return out;
  }
  /** 엑셀 격자: 본문 표 열 경계에서 tol 안에 있는 글상자/이미지의 좌·우를 경계로 붙인다(페이지 바닥글 제외). 옮긴 요소 설명 목록 반환. */
  static List<String> snapToGrid(TheReportFile rf,ControlTable body,int tol){
    List<String> out=new ArrayList<>(); TreeSet<Integer> grid=new TreeSet<>(CrfMcpServer.tableBounds(body));
    for(Object[] e: CrfMcpServer.allControls(rf)){ Control c=(Control)e[3]; if(c instanceof ControlTable||!c.getVisible()||e[0] instanceof SectionPageFooter) continue; int x=c.getX1(), w=CrfMcpServer.ix(c,"getWidth"); if(w<=0) continue;
      Integer nl=nearest(grid,x), nr=nearest(grid,x+w); boolean ml=nl!=null&&nl!=x&&Math.abs(nl-x)<=tol, mr=nr!=null&&nr!=x+w&&Math.abs(nr-(x+w))<=tol;
      if(!ml&&!mr) continue; int nx=ml?nl:x, nright=mr?nr:x+w; if(nright-nx<50||nright-nx<w*7/10) continue;   // 글자가 잘릴 만큼 줄이진 않음
      c.setX1(nx); CrfMcpServer.call(c,"setWidth",int.class,nright-nx); out.add(c.getClass().getSimpleName().replace("Control","")+" \""+c.getName()+"\" "+x+"~"+(x+w)+" → "+nx+"~"+nright); }
    return out;
  }
  static Integer nearest(TreeSet<Integer> g,int x){ Integer lo=g.floor(x), hi=g.ceiling(x); if(lo==null) return hi; if(hi==null) return lo; return x-lo<=hi-x?lo:hi; }
  /** 핵심: 본문 표(+제목 표 +합계 표)를 columns 로 재구성. 저장은 호출자가. */
  static Rebuild rebuildTables(TheReportFile rf,List<Col> cols,ControlTable body,ControlTable head,ControlTable foot,int targetWidth,String totalLabel,String groupField){
    Rebuild r=new Rebuild(); r.body=body; r.head=head; r.foot=foot; int n=cols.size();
    List<Object[]> gfoots=groupFooterTables(rf,body);
    int target=targetWidth>0?targetWidth:body.getWidth(); if(target<=0) target=CrfTableOps.pageInnerWidth(rf); if(target<=0) target=2670;
    r.widths=widthsFor(cols,target); { int sum=0; for(int w: r.widths) sum+=w; if(sum!=target) r.warns.add("열 너비 합 "+sum+" ≠ 목표 "+target+" (지정 너비가 너무 큼 — 표가 본문을 넘을 수 있음)"); }
    // 본문
    if(body.getRowCount()>1) r.warns.add("본문 표 '"+body.getName()+"' 가 "+body.getRowCount()+"행 — 0행만 바인딩");
    unmergeRow(body,0); resizeCols(body,n); setWidths(body,r.widths);
    for(int i=0;i<n;i++){ Col c=cols.get(i); TableCellNormal cell=cell(body,0,i); if(cell==null) continue; bindField(rf,cell,c);
      JSONObject a=new JSONObject(); a.put("merge",c.merge?"true":"false"); if(c.align==null&&numeric(c)) a.put("align","Right"); CrfMcpServer.applyProps(rf,cell,a,"F"); }
    r.notes.add("본문 표 '"+body.getName()+"' "+n+"열 재구성(너비 "+Arrays.toString(r.widths)+" 합 "+target+")");
    // 제목 표
    if(head!=null){ int hr=head.getRowCount()-1; if(hr>0) r.warns.add("제목 표 '"+head.getName()+"' 가 "+head.getRowCount()+"행 — 마지막 행에 제목을 넣고 위 행은 그대로(직접 정리 필요)");
      unmergeRow(head,hr); resizeCols(head,n); setWidths(head,r.widths); head.setX1(body.getX1());
      for(int i=0;i<n;i++){ TableCellNormal cell=cell(head,hr,i); if(cell!=null) setText(rf,cell,cols.get(i).title,null); }
      r.notes.add("제목 표 '"+head.getName()+"' 제목 "+n+"개"); }
    // 합계 표
    if(foot!=null){ int fr=foot.getRowCount()-1; unmergeRow(foot,fr); resizeCols(foot,n); setWidths(foot,r.widths); foot.setX1(body.getX1()); boolean any=false; int labelAt=-1;
      for(int i=0;i<n;i++){ Col c=cols.get(i); TableCellNormal cell=cell(foot,fr,i); if(cell==null) continue;
        if(c.total!=null&&c.f!=null){ FieldFormula ff=totalFormula(rf,c.field,c.total,groupField); CrfMcpServer.call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Field); CrfMcpServer.call(cell,"setApplyValueField",Field.class,ff); CrfMcpServer.call(cell,"setOutputFormat",String.class,c.format==null?"#,##0":c.format); JSONObject a=new JSONObject(); a.put("align","Right"); CrfMcpServer.applyProps(rf,cell,a,"F"); any=true; }
        else { setText(rf,cell,"",null); if(labelAt<0&&c.total==null) labelAt=i; } }
      if(any){ int at=labelAt<0?0:labelAt; TableCellNormal lc=cell(foot,fr,at); if(lc!=null) setText(rf,lc,totalLabel==null?(groupField==null?"합 계":"소 계"):totalLabel,"Center"); r.notes.add("합계 표 '"+foot.getName()+"' — "+(totalLabel==null?(groupField==null?"합 계":"소 계"):totalLabel)+" 라벨 ["+at+"], 집계 열 "+countTotals(cols)+"개"); }
      else r.notes.add("합계 표 '"+foot.getName()+"' 열 맞춤(집계 열 없음 — columns[].total 로 지정)"); }
    // 그룹 바닥글 소계 표: 같은 열 구조면 그룹 필드 기준 rexpert.sum 으로 재구성
    for(Object[] gt: gfoots){ ControlTable t=(ControlTable)gt[0]; String gf=(String)gt[1]; int fr=t.getRowCount()-1; unmergeRow(t,fr); resizeCols(t,n); setWidths(t,r.widths); t.setX1(body.getX1()); boolean any=false; int labelAt=-1;
      for(int i=0;i<n;i++){ Col c=cols.get(i); TableCellNormal cell=cell(t,fr,i); if(cell==null) continue;
        if(c.total!=null&&c.f!=null&&gf!=null){ FieldFormula ff=totalFormula(rf,c.field,c.total,gf); CrfMcpServer.call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Field); CrfMcpServer.call(cell,"setApplyValueField",Field.class,ff); CrfMcpServer.call(cell,"setOutputFormat",String.class,c.format==null?"#,##0":c.format); JSONObject a=new JSONObject(); a.put("align","Right"); CrfMcpServer.applyProps(rf,cell,a,"F"); any=true; }
        else { setText(rf,cell,"",null); if(labelAt<0&&c.total==null) labelAt=i; } }
      if(any){ int at=labelAt<0?0:labelAt; TableCellNormal lc=cell(t,fr,at); if(lc!=null) setText(rf,lc,"소 계","Center"); r.notes.add("그룹 바닥글 표 '"+t.getName()+"' — 소 계("+gf+" 기준) 집계 열 "+countTotals(cols)+"개"); }
      else r.notes.add("그룹 바닥글 표 '"+t.getName()+"' 열 맞춤"+(gf==null?"(그룹 필드 없음)":"(집계 열 없음)")); }
    return r;
  }
  static int countTotals(List<Col> cols){ int k=0; for(Col c: cols) if(c.total!=null&&c.f!=null) k++; return k; }

  // ===================== 쿼리에 조건 추가 =====================
  /** 평문 SQL → JS 동적쿼리(var sql=""; sql += "…\r\n"; … return sql;) */
  static String plainToJs(String sql){ StringBuilder js=new StringBuilder("var sql = \"\";\r\n"); CrfGen2.emitText(js,CrfGen2.stripComments(sql)); js.append("return sql;\r\n"); return js.toString(); }
  static int braceDelta(String line){ String t=line.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'",""); int d=0; for(char ch: t.toCharArray()){ if(ch=='{') d++; else if(ch=='}') d--; } return d; }
  /** crf_append_query_condition */
  @SuppressWarnings("unchecked")
  static String appendQueryCondition(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), param=q(s(args,"param")).trim(), frag=s(args,"sql"), cond=q(s(args,"condition")).trim(), pos=q(s(args,"position")).trim();
    if(frag==null||frag.trim().isEmpty()) return "ERROR: sql(추가할 SQL 조각, 예 \"AND T1.DEPT_CD = '{parameter.DEPTCD}'\") 이 비어 있습니다";
    if(param.isEmpty()&&cond.isEmpty()) return "ERROR: param(매개변수 이름) 또는 condition(JS 조건식) 중 하나는 필요합니다";
    TheReportFile rf=CrfMcpServer.open(path); DataSet ds=CrfMcpServer.datasetOf(rf,s(args,"dataset"));
    DataAccessMethodSQL qm=ds.getDataSetItemNormal()==null?null:ds.getDataSetItemNormal().getDataAccessMethodSQL(); if(qm==null) return "ERROR: 데이터셋 "+ds.getName()+" 은 SQL 데이터셋이 아닙니다";
    String raw=q(qm.getQueryString()); boolean wasJs=qm.getScriptType()==ScriptType.JavaScript; List<String> notes=new ArrayList<>();
    String js=wasJs?raw:plainToJs(raw); if(!wasJs) notes.add("평문 SQL 을 JavaScript 동적쿼리로 변환(scriptType NotScript → JavaScript)");
    if(wasJs&&!CrfMcpServer.hasReturn(js)){ js=js.replaceAll("\\s+$","")+"\r\nreturn sql;\r\n"; notes.add("return sql; 이 없어 끝에 추가"); }
    // 조각 → sql += 줄들 (파라미터 표기 정규화)
    String pname=param.isEmpty()?null:CrfGen2.rp(param);
    String fragN=CrfGen2.normParamTokens(CrfGen2.subParamsQuoted(frag));
    if(pname!=null&&!fragN.toUpperCase().contains("{PARAMETER."+pname.toUpperCase()+"}")) notes.add("⚠ sql 조각에 {parameter."+pname+"} 가 없습니다 — 조건만 걸리고 값은 쓰이지 않음");
    StringBuilder blk=new StringBuilder(); String c=cond.isEmpty()?"'{parameter."+pname+"}' != ''":CrfGen2.normParamTokens(CrfGen2.subParamsQuoted(cond)); blk.append("if(").append(c).append("){\r\n"); CrfGen2.emitText(blk,fragN); blk.append("}\r\n");
    // 삽입 위치
    String[] lines=js.replace("\r\n","\n").replace("\r","\n").split("\n",-1); List<String> L=new ArrayList<>(Arrays.asList(lines)); int at=-1; String how;
    java.util.regex.Pattern orderBy=java.util.regex.Pattern.compile("(?i)^\\s*sql\\s*\\+=\\s*\"(.*?)\\bORDER\\s+BY\\b(.*)$");
    if(pos.isEmpty()||pos.equalsIgnoreCase("before_order")){ for(int i=L.size()-1;i>=0&&at<0;i--){ java.util.regex.Matcher m=orderBy.matcher(L.get(i)); if(m.find()){ String before=m.group(1); if(before.chars().filter(ch->ch=='(').count()!=before.chars().filter(ch->ch==')').count()) continue;   // OVER (ORDER BY …) 같은 괄호 안은 최종 ORDER BY 가 아님
          if(before.trim().isEmpty()) at=i; else { // 한 줄에 WHERE … ORDER BY 가 같이 있으면 둘로 나눔
            L.set(i,"sql += \""+before.replaceAll("\\s+$","")+"\\r\\n\";"); L.add(i+1,"sql += \"ORDER BY"+m.group(2)); at=i+1; } } }
      how=at>=0?"ORDER BY 앞":"ORDER BY 없음 → return 앞"; }
    else if(pos.equalsIgnoreCase("end")) how="return 앞";
    else if(pos.toLowerCase().startsWith("after:")){ String key=pos.substring(6).trim(); int hit=-1; for(int i=0;i<L.size()&&hit<0;i++) if(L.get(i).contains(key)) hit=i; if(hit<0) return "ERROR: position=after: 기준 문자열 '"+key+"' 이 쿼리에 없습니다";
      // 기준 줄이 if 블록 안이면 그 블록이 닫힌 뒤에 넣는다(조건 블록이 다른 조건 안에 중첩되지 않게)
      int depth=0; for(int i=0;i<=hit;i++) depth+=braceDelta(L.get(i)); at=hit+1; if(depth>0){ int d=depth; for(int i=hit+1;i<L.size();i++){ d+=braceDelta(L.get(i)); if(d<depth){ at=i+1; break; } } notes.add("기준 줄이 if 블록 안이라 그 블록이 닫힌 뒤에 삽입"); }
      how="'"+key+"' 다음"; }
    else return "ERROR: position 은 before_order|end|after:<문자열>";
    if(at<0){ for(int i=L.size()-1;i>=0;i--) if(L.get(i).trim().startsWith("return")){ at=i; break; } if(at<0) at=L.size(); }
    List<String> blkLines=new ArrayList<>(Arrays.asList(blk.toString().replace("\r\n","\n").split("\n"))); L.addAll(at,blkLines);
    String out=String.join("\r\n",L); qm.setQueryString(out); qm.setScriptType(ScriptType.JavaScript);
    // 매개변수 선언
    List<String> declared=new ArrayList<>(); Set<String> have=CrfMcpServer.declaredParams(rf);
    for(String u: CrfMcpServer.usedParams(out)) if(!have.contains(u.toUpperCase())){ CrfMcpServer.addGlobalParam(rf,u,CrfMcpServer.nullType(),"",u); have.add(u.toUpperCase()); declared.add(u); }
    String wrote=CrfMcpServer.save(rf,output,path);
    TheReportFile v=CrfMcpServer.open(output); DataSet vds=CrfMcpServer.datasetOf(v,String.valueOf(CrfMcpServer.indexOfDataset(rf,ds))); String vq=q(vds.getDataSetItemNormal().getDataAccessMethodSQL().getQueryString());
    if(!vq.contains(c)) return "ERROR: 저장 후 되읽기 검증 실패 — 조건 블록이 없음"; if(!CrfMcpServer.hasReturn(vq)) return "ERROR: 저장 후 검증 실패 — return 없음";
    StringBuilder b=new StringBuilder("OK: 데이터셋 "+ds.getName()+" 쿼리에 조건 블록 추가("+how+", "+blkLines.size()+"줄), wrote "+wrote);
    for(String n: notes) b.append("\n").append(n.startsWith("⚠")?n:"ℹ "+n);
    if(!declared.isEmpty()) b.append("\n+ 매개변수 선언: ").append(declared);
    int from=Math.max(0,at-2), to=Math.min(L.size(),at+blkLines.size()+2); b.append("\n----- 삽입 부근 -----"); for(int i=from;i<to;i++) b.append("\n").append(i>=at&&i<at+blkLines.size()?"+ ":"  ").append(L.get(i));
    return b.toString();
  }
  // ===================== 생성기 v2 =====================
  /** 필드 참조를 모두 풀고(셀/글상자 → 빈 텍스트) 필드를 목록에서 제거. 공식 스크립트 참조는 못 풀므로 경고 목록에. */
  static void dropField(TheReportFile rf,Field f,List<String> warns){
    for(Object[] e: CrfMcpServer.allControls(rf)){ Control c=(Control)e[3];
      if(c instanceof ControlTable){ ControlTable t=(ControlTable)c; for(int r=0;r<t.getRowCount();r++) for(int k=0;k<t.getColumnCount();k++){ TableCellNormal cell=cell(t,r,k); if(cell!=null&&cell.getApplyValueField()==f) setText(rf,cell,"",null); } }
      else if(go(c,"getApplyValueField")==f) setText(rf,c,"",null); }
    for(String r: CrfMcpServer.refsOf(rf,f)) if(r.startsWith("공식")||r.contains("그룹")||r.contains("누적")) warns.add("필드 "+f.getName()+" 제거 — 남은 참조: "+r);
    CrfMcpServer.removeFromLists(rf,f);
  }
  /** 템플릿의 그룹(밴드·그룹 객체·그룹이름 필드) 전부 제거 */
  @SuppressWarnings("unchecked")
  static int dropAllGroups(TheReportFile rf,List<String> warns){
    Report rep=rf.getGlobe().getMainReport(); RexObjectList<Section> secs=rep.getReportDesign().getMainPage().getSectionList(); int n=0;
    for(int i=secs.size()-1;i>=0;i--) if(secs.get(i) instanceof SectionGroupHeader||secs.get(i) instanceof SectionGroupFooter){ secs.remove(i); n++; }
    RexObjectList<Group> gl=rep.getReportObjectManager().getGroupList(); int g=gl.size(); gl.removeAll();
    RexObjectList<?> gn=rep.getReportObjectManager().getFieldGroupNameList(); if(gn!=null) for(int i=gn.size()-1;i>=0;i--){ Field f=(Field)gn.get(i); dropField(rf,f,warns); }
    RexObjectList<?> gi=rep.getReportObjectManager().getFieldGroupIndexList(); if(gi!=null) for(int i=gi.size()-1;i>=0;i--){ Field f=(Field)gi.get(i); dropField(rf,f,warns); }   // 그룹인덱스 필드도 그룹을 가리킴
    // 누적합산의 그룹 기준(리셋/평가)은 그룹이 사라지므로 해제 — 안 풀면 지워진 Group 이 참조로 남는다
    RexObjectList<?> rt=rep.getReportObjectManager().getFieldRunningTotalList(); if(rt!=null) for(int i=0;i<rt.size();i++){ Object r=rt.get(i); for(String m: new String[]{"RunningTotalResetOnChangeGroup","RunningTotalEvaluateOnChangeGroup"}) if(go(r,"get"+m)!=null){ try{ CrfMcpServer.call(r,"set"+m,Group.class,null); warns.add("누적합산 "+nameOf(r)+": 그룹 기준("+m.replace("RunningTotal","")+") 해제 — 리포트 전체 기준으로 동작"); }catch(Throwable t){} } }
    return g;
  }
  static ControlLabel biggestLabel(TheReportFile rf,boolean staticOnly){ ControlLabel best=null; int bs=-1;
    for(Object[] e: CrfMcpServer.allControls(rf)){ if(!(e[0] instanceof SectionPageHeader||e[0] instanceof SectionReportHeader)||!(e[3] instanceof ControlLabel)) continue; ControlLabel l=(ControlLabel)e[3]; if(staticOnly&&go(l,"getApplyValueField")!=null) continue;
      int fs=0; try{ fs=Integer.parseInt(q(g(go(l,"getTextInfo"),"getFontSize")).trim()); }catch(Exception x){} int score=fs*10000+CrfMcpServer.ix(l,"getWidth"); if(score>bs){ bs=score; best=l; } }
    return best; }
  /** 머리글의 공식 바인딩 글상자들을 x 순으로 */
  static List<ControlLabel> condLabels(TheReportFile rf){ List<ControlLabel> out=new ArrayList<>();
    for(Object[] e: CrfMcpServer.allControls(rf)){ if(!(e[0] instanceof SectionPageHeader||e[0] instanceof SectionReportHeader||e[0] instanceof SectionDataHeader)||!(e[3] instanceof ControlLabel)) continue; ControlLabel l=(ControlLabel)e[3]; Object f=go(l,"getApplyValueField"); if(f instanceof FieldFormula) out.add(l); }
    out.sort(Comparator.comparingInt(Control::getX1)); return out; }
  @SuppressWarnings("unchecked")
  static String setCondLabel(TheReportFile rf,ControlLabel l,String spec,String autoName){
    if(l==null) return null; spec=spec==null?"":spec;
    Object f=go(l,"getApplyValueField");
    if(spec.matches("(?s).*\\breturn\\b.*")){ if(f instanceof FieldFormula){ ((FieldFormula)f).setScript(spec); return "공식 "+nameOf(f)+" 스크립트 교체"; }
      FieldFormula ff=new FieldFormula(); String nm=autoName; int i=2; while(CrfMcpServer.findField(rf,nm)!=null) nm=autoName+"_"+(i++); ff.setName(nm); ff.setScript(spec); ff.setScriptType(ScriptType.JavaScript); ((RexObjectList<FieldFormula>) rf.getGlobe().getMainReport().getReportObjectManager().getFieldFormulaList()).add(ff);
      l.setApplyValueType(ApplyValueType.Field); l.setApplyValueField(ff); return "공식 "+nm+" 생성·바인딩"; }
    setText(rf,l,spec,null); if(f instanceof FieldFormula && CrfMcpServer.refsOf(rf,(Field)f).isEmpty()) CrfMcpServer.removeFromLists(rf,(Field)f);
    return spec.isEmpty()?"비움":"텍스트 \""+spec+"\"";
  }
  static boolean footerHasSubreport(TheReportFile rf){ for(Object[] e: CrfMcpServer.allControls(rf)) if(e[0] instanceof SectionPageFooter && e[3] instanceof ControlSubreport) return true; return false; }

  /** crf_generate v2 */
  @SuppressWarnings("unchecked")
  static String generate(JSONObject args) throws Exception {
    String template=s(args,"template"), sql=s(args,"sql"), output=s(args,"output");
    if(template==null||!new java.io.File(template).isFile()) return "ERROR: template 파일 없음: "+template;
    if(sql==null||sql.trim().isEmpty()) return "ERROR: sql 인자가 비어 있습니다";
    if(output==null||output.trim().isEmpty()) return "ERROR: output 인자가 비어 있습니다";
    try{ if(new java.io.File(output).getCanonicalPath().equalsIgnoreCase(new java.io.File(template).getCanonicalPath())) return "ERROR: output 이 template 과 같습니다 — 다른 경로를 지정하세요"; }catch(Exception e){}
    boolean clean=!"false".equalsIgnoreCase(s(args,"clean_template")), snap=!"false".equalsIgnoreCase(s(args,"snap")), fromDb="true".equalsIgnoreCase(s(args,"fields_from_db"));
    String groupsArg=q(s(args,"groups")).trim(), logo=q(s(args,"logo")).trim();
    List<String> did=new ArrayList<>(), warns=new ArrayList<>();
    TheReportFile rf=CrfMcpServer.open(template);
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); if(gom.getDataSetList().size()==0) return "ERROR: 템플릿에 데이터셋이 없습니다";
    DataSet ds=gom.getDataSetList().get(0);
    // 1) 템플릿 정리: 그룹 제거
    if(clean){ int g=dropAllGroups(rf,warns); if(g>0) did.add("템플릿 그룹 "+g+"개 제거(밴드·그룹이름 필드 포함)"); }
    // 2) 쿼리 적용 (매개변수 선언, 필드 SELECT 순서)
    CrfMcpServer.QueryApply r=CrfMcpServer.applyQuery(rf,ds,sql,s(args,"script_type"),true,clean?"replace":"add",true);
    did.add("쿼리 설정: scriptType "+r.after+", 매개변수 선언 "+r.declared+(r.noReturn?" ⚠ return 없음":""));
    if(!r.warns.isEmpty()) warns.add("MyBatis 변환: "+r.warns);
    // 2b) DB 메타데이터
    if(fromDb||r.noColumns){ java.util.Map<String,String> params=new HashMap<>(); String pj=s(args,"params"); if(pj!=null&&!pj.trim().isEmpty()){ try{ JSONObject po=(JSONObject)CrfMcpServer.P.parse(pj); for(Object k: po.keySet()) params.put(String.valueOf(k),String.valueOf(po.get(k))); }catch(Exception e){ return "ERROR: params 는 JSON 객체여야 합니다"; } }
      String plain=r.after==ScriptType.JavaScript?CrfMcpServer.jsToPlainSql(r.conv):r.conv; List<String> skipped=new ArrayList<>();
      java.util.Map<String,DataType> meta; try{ meta=CrfMcpServer.dbMeta(plain,params,skipped); }catch(RuntimeException e){ if(r.noColumns) return "ERROR: SELECT 목록을 파싱하지 못했고(SELECT * 등) DB 메타데이터도 실패 — "+e.getMessage(); warns.add("fields_from_db 실패, SQL 파싱 결과 사용: "+e.getMessage().split("\n")[0]); meta=null; }
      if(meta!=null){ for(java.util.Map.Entry<String,DataType> e: meta.entrySet()){ FieldData f=CrfMcpServer.findDataField(ds,e.getKey()); if(f==null) f=CrfMcpServer.addDataFieldTo(ds,e.getKey(),e.getValue()); else f.setDataType(e.getValue()); }
        List<String> cols=new ArrayList<>(meta.keySet()); List<String> orphan=new ArrayList<>(); CrfMcpServer.reorderFields(ds,cols,orphan); did.add("DB 메타데이터로 필드 "+cols.size()+"개 확정(타입 포함)"+(skipped.isEmpty()?"":" ⚠ 건너뜀 "+skipped));
        if(clean) for(String o: orphan){ Field f=CrfMcpServer.findDataField(ds,o); if(f!=null) dropField(rf,f,warns); } } }
    // 2c) SELECT 에 없는 필드는 바인딩을 풀고 제거(clean)
    if(clean&&!r.orphan.isEmpty()){ List<String> dropped=new ArrayList<>(); for(String o: r.orphan){ Field f=CrfMcpServer.findDataField(ds,o); if(f!=null){ dropField(rf,f,warns); dropped.add(o); } } if(!dropped.isEmpty()) did.add("SELECT 에 없는 필드 "+dropped.size()+"개 바인딩 해제·제거: "+dropped); }
    else if(!r.orphan.isEmpty()) warns.add("SELECT 에 없는 필드가 남아 있음(값이 빔): "+r.orphan);
    if(!r.placeholders.isEmpty()) warns.add("별칭 없는 식 컬럼 → 자리 필드 "+r.placeholders+" (AS 별칭 권장)");
    // 3) 열 정의
    List<Col> cols; if(args.get("columns")!=null&&!String.valueOf(args.get("columns")).trim().isEmpty()) cols=parseColumns(rf,args.get("columns"),true);
    else { cols=new ArrayList<>(); RexObjectList<?> fl=ds.getFieldDataList(); for(int i=0;i<fl.size();i++){ Col c=new Col(); c.field=nameOf(fl.get(i)); c.f=(Field)fl.get(i); c.title=c.field; cols.add(c); } did.add("columns 미지정 → SELECT 컬럼 "+cols.size()+"개 전부, 제목=필드명, 너비 균등"); }
    // 4) 표 재구성
    ControlTable body=bodyTable(rf,s(args,"table")); ControlTable head, foot;
    if(body==null){ int bw=CrfTableOps.pageInnerWidth(rf); if(bw<=0) bw=2670; int[] w=widthsFor(cols,bw); Section det=CrfMcpServer.findOrCreateSection(rf,"본문"); SubSectionDefault dsub=det==null?null:CrfMcpServer.firstSub(det);
      if(dsub==null) return "ERROR: 템플릿 본문 밴드에 표도 기본 서브섹션도 없습니다(서브리포트형 본문?) — 목록형 리포트를 template 으로 쓰거나 legacy=true(v1)";
      Control bt=CrfMcpServer.buildTable(CrfMcpServer.uniqueControlName(rf,"표_본문"),w,52); ((RexObjectList<Control>)(RexObjectList<?>)CrfMcpServer.clpOf(dsub).getControlList()).add(bt); if(dsub.getHeight()<52) dsub.setHeight(52);
      Section hs=CrfMcpServer.findOrCreateSection(rf,"데이터머리글"); SubSectionDefault hsub=hs==null?null:CrfMcpServer.firstSub(hs); if(hsub==null) return "ERROR: 데이터 머리글 밴드를 만들 수 없습니다"; Control ht=CrfMcpServer.buildTable(CrfMcpServer.uniqueControlName(rf,"표_제목"),w,60); ((RexObjectList<Control>)(RexObjectList<?>)CrfMcpServer.clpOf(hsub).getControlList()).add(ht); if(hsub.getHeight()<60) hsub.setHeight(60);
      String[] fdD=CrfMcpServer.defaultFont(rf,template,true), fdL=CrfMcpServer.defaultFont(rf,template,false); for(int i=0;i<w.length;i++){ CrfMcpServer.inheritFont(CrfMcpServer.tableCell(bt,0,i),fdD,true); CrfMcpServer.inheritFont(CrfMcpServer.tableCell(ht,0,i),fdL,true); JSONObject a=new JSONObject(); a.put("align","Center"); a.put("bold","true"); CrfMcpServer.applyProps(rf,CrfMcpServer.tableCell(ht,0,i),a,"F"); }
      body=(ControlTable)bt; head=(ControlTable)ht; foot=null; did.add("템플릿에 본문 표가 없어 본문/데이터머리글에 새 표 생성"); }
    else { head=siblingTable(rf,body,s(args,"header_table"),null,true); foot=siblingTable(rf,body,s(args,"footer_table"),null,false); }
    Rebuild rb=rebuildTables(rf,cols,body,head,foot,pInt(args.get("width"),0),s(args,"total_label"),null); did.addAll(rb.notes); warns.addAll(rb.warns);
    // 5) 제목·조건 글상자
    String title=s(args,"title"); if(title!=null){ ControlLabel tl=biggestLabel(rf,true); if(tl!=null){ setText(rf,tl,title,null); did.add("제목 글상자 \""+tl.getName()+"\" = \""+title+"\""); } else warns.add("제목을 넣을 머리글 글상자가 없음(crf_add_label 로 추가)"); }
    List<ControlLabel> cl=condLabels(rf); String cond=s(args,"cond"), condR=s(args,"cond_right");
    if(cond!=null){ ControlLabel l=cl.isEmpty()?null:cl.get(0); if(l==null) for(Object[] e: CrfMcpServer.allControls(rf)) if(e[3] instanceof ControlLabel && "글상자2".equals(((Control)e[3]).getName())) l=(ControlLabel)e[3]; String x=setCondLabel(rf,l,cond,"COND"); if(x!=null) did.add("조건 글상자(왼쪽) \""+l.getName()+"\": "+x); else warns.add("왼쪽 조건 글상자를 찾지 못함(머리글의 공식 바인딩 글상자 없음)"); }
    if(condR!=null){ ControlLabel l=cl.size()>1?cl.get(cl.size()-1):null; if(l==null) for(Object[] e: CrfMcpServer.allControls(rf)) if(e[3] instanceof ControlLabel && "글상자3".equals(((Control)e[3]).getName())) l=(ControlLabel)e[3]; String x=setCondLabel(rf,l,condR,"COND_R"); if(x!=null) did.add("조건 글상자(오른쪽) \""+l.getName()+"\": "+x); else warns.add("오른쪽 조건 글상자를 찾지 못함"); }
    // 6) 로고
    boolean hasLogo=footerHasSubreport(rf);
    if(logo.equalsIgnoreCase("none")) did.add("로고: 건드리지 않음"+(hasLogo?"(있음)":"(없음)"));
    else if(hasLogo) did.add("로고: 페이지 바닥글에 서브리포트가 이미 있어 유지");
    else { String rel=logo.isEmpty()||logo.equalsIgnoreCase("auto")?"../../../images/bottom_logo.crf":logo; java.io.File lf=new java.io.File(new java.io.File(output).getAbsoluteFile().getParentFile(),rel);
      if(lf.isFile()||!(logo.isEmpty()||logo.equalsIgnoreCase("auto"))){ Section pf=CrfMcpServer.findOrCreateSection(rf,"페이지바닥글"); SubSectionDefault psub=CrfMcpServer.firstSub(pf); ControlSubreport sr=new ControlSubreport(); sr.setName(CrfMcpServer.uniqueControlName(rf,"공통하단로고")); sr.setVisible(true); sr.setX1(0); sr.setY1(0); sr.setWidth(300); sr.setHeight(100);
        com.clipsoft.clipreport.base.functions.WebLinkInfo wi=sr.getLinkedSubreportPath(); if(wi!=null) wi.setUrlText(rel); ((RexObjectList<Control>)(RexObjectList<?>)CrfMcpServer.clpOf(psub).getControlList()).add(sr); if(psub.getHeight()<100) psub.setHeight(100); did.add("로고 서브리포트 추가: "+rel+(lf.isFile()?"":" ⚠ 파일 없음")); }
      else did.add("로고: 바닥글에 없고 "+rel+" 도 없어 추가 안 함(logo=<경로> 로 지정 가능)"); }
    // 7) 엑셀 격자 맞춤
    if(snap){ List<String> moved=snapToGrid(rf,body,pInt(args.get("snap_tolerance"),450)); for(String x: moved) did.add("엑셀 격자 맞춤: "+x); }
    // 8) 저장
    String wrote=CrfMcpServer.save(rf,output,template);
    // 9) 그룹 (저장된 파일에 crf_add_group 재사용)
    List<String> groupCols=new ArrayList<>();
    if(groupsArg.equalsIgnoreCase("auto")){ String plain=r.after==ScriptType.JavaScript?CrfMcpServer.jsToPlainSql(r.conv):r.conv; List<String> gb=CrfGen3.parseGroupBy(CrfGen2.stripComments(plain)); if(!gb.isEmpty()){ groupCols.add(gb.get(0)); if(gb.size()>1) warns.add("GROUP BY 컬럼 "+gb+" 중 첫 컬럼만 그룹으로(나머지는 groups=A,B 로 명시)"); } else did.add("groups=auto: GROUP BY 없음 → 그룹 없음"); }
    else if(!groupsArg.isEmpty()&&!groupsArg.equalsIgnoreCase("none")) for(String gc: groupsArg.split(",")) if(!gc.trim().isEmpty()) groupCols.add(gc.trim());
    StringBuilder totals=new StringBuilder(); for(Col c: cols) if(c.total!=null&&c.f!=null) totals.append(totals.length()>0?",":"").append(c.field);
    for(String gc: groupCols){ String tmp=output+".gen.tmp"; JSONObject a=new JSONObject(); a.put("path",output); a.put("output",tmp); a.put("column",gc); a.put("label","true"); a.put("level","inner"); if(totals.length()>0) a.put("subtotal",totals.toString());
      JSONObject prev=CrfMcpServer.CUR_ARGS.get(); CrfMcpServer.CUR_ARGS.set(a); String res; try{ res=CrfMcpServer.addGroup(a); } finally{ CrfMcpServer.CUR_ARGS.set(prev); }
      java.io.File tf=new java.io.File(tmp); if(tf.isFile()){ if(res.startsWith("OK")){ try{ java.nio.file.Files.move(tf.toPath(),new java.io.File(output).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); }catch(java.io.IOException ioe){ tf.delete(); res="ERROR: 그룹 결과를 output 에 쓰지 못함(파일 잠김?) — "+ioe.getMessage(); } } else tf.delete(); }
      if(res.startsWith("OK")) did.add("그룹 "+gc+": "+res.substring(0,Math.min(res.length(),res.indexOf(", wrote")>0?res.indexOf(", wrote"):res.length()))); else warns.add("그룹 "+gc+" 실패: "+res); }
    // 9b) 그룹 밴드의 라벨을 본문 열 격자에 맞춤(소계 라벨은 해당 열 위치·너비, 그룹 라벨은 오른쪽을 경계로)
    if(!groupCols.isEmpty()){ TheReportFile g2=CrfMcpServer.open(output); ControlTable b2=bodyTable(g2,body.getName()); if(b2!=null){ List<Integer> bounds=CrfMcpServer.tableBounds(b2); int moved=0;
        for(Object[] e: CrfMcpServer.allControls(g2)){ if(!(e[0] instanceof SectionGroupFooter||e[0] instanceof SectionGroupHeader)||!(e[3] instanceof ControlLabel)) continue; ControlLabel l=(ControlLabel)e[3]; Object f=go(l,"getApplyValueField");
          int colIdx=-1; if(f instanceof FieldFormula){ java.util.regex.Matcher mm=java.util.regex.Pattern.compile("\"data\\.([A-Za-z0-9_$#가-힣]+)\"").matcher(q(((FieldFormula)f).getScript())); if(mm.find()) for(int i=0;i<cols.size();i++) if(cols.get(i).field.equalsIgnoreCase(mm.group(1))) colIdx=i; }
          if(colIdx>=0){ l.setX1(bounds.get(colIdx)); l.setWidth(bounds.get(colIdx+1)-bounds.get(colIdx)); JSONObject a=new JSONObject(); a.put("align","Right"); CrfMcpServer.applyProps(g2,l,a,"F"); moved++; }
          else { int x=l.getX1(), right=x+l.getWidth(); Integer nr=new TreeSet<>(bounds).ceiling(right); if(nr!=null&&nr!=right){ l.setWidth(nr-x); moved++; } } }
        // 그룹 바닥글에 정적 텍스트가 없으면 첫 소계 라벨 왼쪽 열에 "소 계" 라벨 추가(스타일은 소계 라벨에서 복사)
        int added=0; for(Object[] e: CrfMcpServer.allControls(g2)){ if(!(e[0] instanceof SectionGroupFooter)||!(e[3] instanceof ControlLabel)) continue; ControlLabel l=(ControlLabel)e[3]; if(!(go(l,"getApplyValueField") instanceof FieldFormula)) continue;
          boolean hasText=false; RexObjectList<Control> cl2=(RexObjectList<Control>)e[2]; for(int i=0;i<cl2.size();i++){ Control o=cl2.get(i); if(o instanceof ControlLabel && go(o,"getApplyValueField")==null && !q(g(o,"getApplyValueText")).trim().isEmpty()) hasText=true; } if(hasText) continue;
          int idx=-1; for(int i=0;i<bounds.size()-1;i++) if(bounds.get(i)==l.getX1()) idx=i; int li=idx>0?idx-1:(idx==0&&bounds.size()>2?1:-1); if(li<0) continue;
          ControlLabel t=new ControlLabel(); t.setName(CrfMcpServer.uniqueControlName(g2,"lbl_subtotal")); t.setVisible(true); t.setX1(bounds.get(li)); t.setY1(l.getY1()); t.setWidth(bounds.get(li+1)-bounds.get(li)); t.setHeight(l.getHeight());
          t.setApplyValueType(ApplyValueType.Text); t.setApplyValueText("소 계"); CrfMcpServer.copyTextInfo(l,t); JSONObject a=new JSONObject(); a.put("align","Center"); a.put("bold","true"); CrfMcpServer.applyProps(g2,t,a,"F");
          try{ Object li1=go(l,"getLineInfo"), li2=go(t,"getLineInfo"); if(li1!=null&&li2!=null){ CrfMcpServer.call(t,"setShapeType",com.clipsoft.clipreport.common.enums.ShapeType.class,go(l,"getShapeType")); CrfMcpServer.call(li2,"setLineStyle",com.clipsoft.clipreport.common.enums.LineStyle.class,go(li1,"getLineStyle")); CrfMcpServer.call(t,"setLineStyle",com.clipsoft.clipreport.common.enums.LineStyle.class,go(l,"getLineStyle")); } }catch(Throwable x){}
          cl2.add(t); added++; moved++; }
        if(added>0) did.add("그룹 바닥글에 '소 계' 라벨 "+added+"개 추가");
        if(moved>0){ CrfMcpServer.save(g2,output,template); did.add("그룹 밴드 라벨 "+moved+"개를 본문 열 격자에 맞춤"); } } }
    // 10) 검증
    TheReportFile v=CrfMcpServer.open(output); JSONObject va=new JSONObject(); va.put("path",output); va.put("excel","true"); String val=CrfMcpServer.validate(va);
    StringBuilder b=new StringBuilder("OK: 목록형 리포트 파생 완료 → "+wrote+"\n템플릿: "+new java.io.File(template).getName()+"\n");
    for(String x: did) b.append("  ✔ ").append(x).append("\n"); for(String x: warns) b.append("  ⚠ ").append(x).append("\n");
    b.append("필드("+ds.getFieldDataList().size()+"): ").append(CrfMcpServer.fieldList(CrfMcpServer.datasetOf(v,"0"))).append("\n");
    b.append(CrfMcpServer.cellGridLine(v,body.getName())).append("\n");
    b.append("----- crf_validate excel=true -----\n").append(val.length()>1800?val.substring(0,1800)+"\n…":val);
    return b.toString();
  }
  /** crf_set_columns */
  static String setColumns(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output");
    TheReportFile rf=CrfMcpServer.open(path);
    List<Col> cols=parseColumns(rf,args.get("columns"),true);
    ControlTable body=bodyTable(rf,s(args,"table")); if(body==null) return "ERROR: 본문 밴드에 표가 없습니다 — crf_add_table 로 먼저 만들거나 table= 로 지정";
    ControlTable head=siblingTable(rf,body,s(args,"header_table"),null,true), foot=siblingTable(rf,body,s(args,"footer_table"),null,false);
    int target=pInt(args.get("width"),0);
    Rebuild r=rebuildTables(rf,cols,body,head,foot,target,s(args,"total_label"),null);
    if("true".equalsIgnoreCase(s(args,"snap"))){ for(String x: snapToGrid(rf,body,pInt(args.get("snap_tolerance"),200))) r.notes.add("엑셀 격자 맞춤: "+x); }
    String wrote=CrfMcpServer.save(rf,output,path);
    TheReportFile v=CrfMcpServer.open(output); Control vb=CrfMcpServer.findTable(v,body.getName()); if(!(vb instanceof ControlTable)||((ControlTable)vb).getColumnCount()!=cols.size()) return "ERROR: 저장 후 되읽기 검증 실패 — 본문 표 열 수";
    List<String> grid=CrfTableOps.checkGrid((ControlTable)vb); if(!grid.isEmpty()) return "ERROR: 저장 후 격자 검증 실패: "+grid;
    StringBuilder b=new StringBuilder("OK: 열 세트 교체 → "+cols.size()+"열, wrote "+wrote); for(String x: r.notes) b.append("\n  ").append(x); for(String x: r.warns) b.append("\n⚠ ").append(x);
    b.append("\n").append(CrfMcpServer.cellGridLine(v,body.getName()));
    for(String x: CrfMcpServer.excelGridIssues(v)) b.append("\nℹ ").append(x);
    return b.toString();
  }
}
