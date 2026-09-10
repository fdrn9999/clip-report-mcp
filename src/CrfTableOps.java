import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.controls.*;
import com.clipsoft.clipreport.base.controls.Tables.*;
import com.clipsoft.clipreport.base.datas.fields.Field;
import com.clipsoft.clipreport.base.RexObjectList;
import com.clipsoft.clipreport.common.enums.*;
import org.json.simple.*;
import java.util.*;

/** 표(ControlTable) 구조 편집: 행/열 삽입·삭제·복제·이동·크기·균등 분배, 표 속성, 전체 병합 해제, 격자 정보.
 *  CLIP SDK 에는 행/열 삽입·삭제 API 가 없어서 TableRow/TableColumn 리스트와 각 행·열의 셀 리스트를 직접 재구성한다.
 *  불변식: 행 r 의 셀 리스트는 열 순서, 열 c 의 셀 리스트는 행 순서, 셀.tableRow/tableColumn 은 소속 행/열, 병합 자리(TableCellDumy)는 기준 셀과 그 위치 인덱스를 가진다. */
class CrfTableOps {
  // ---- CrfMcpServer 헬퍼 alias ----
  static String s(JSONObject a,String k){ return CrfMcpServer.s(a,k); }
  static String q(String x){ return CrfMcpServer.q(x); }
  static int pInt(Object o,int d){ return CrfMcpServer.pInt(o,d); }
  static Object go(Object o,String m){ return CrfMcpServer.go(o,m); }
  static String g(Object o,String m){ return CrfMcpServer.g(o,m); }
  static int ix(Object o,String m){ return CrfMcpServer.ix(o,m); }
  static String nameOf(Object o){ return CrfMcpServer.nameOf(o); }

  static final class Ctx { TheReportFile rf; String path, output, name; ControlTable t; Object[] loc; }
  static Ctx ctx(JSONObject args) throws Exception {
    Ctx c=new Ctx(); c.path=s(args,"path"); c.output=s(args,"output"); c.name=q(s(args,"table")).trim();
    if(c.name.isEmpty()) throw new RuntimeException("table 인자가 비어 있습니다 (crf_describe_layout / crf_table_info 로 이름 확인)");
    c.rf=CrfMcpServer.open(c.path); Control t=CrfMcpServer.findTable(c.rf,c.name);
    if(t==null) throw new RuntimeException("table '"+c.name+"' not found (crf_describe_layout 로 이름 확인)");
    if(!(t instanceof ControlTable)) throw new RuntimeException("'"+c.name+"' 은 표가 아닙니다 ("+t.getClass().getSimpleName()+")");
    c.t=(ControlTable)t; c.loc=CrfMcpServer.findControlLoc(c.rf,c.name); return c;
  }
  static String band(Ctx c){ return c.loc==null?"?":((Section)c.loc[0]).getClass().getSimpleName().replace("Section",""); }

  // ---- 격자 기본 ----
  static TableCellNormal baseOf(TableCell c){ if(c instanceof TableCellNormal) return (TableCellNormal)c; if(c instanceof TableCellDumy) return ((TableCellDumy)c).getBaseCell(); return null; }
  static TableCell cellAt(ControlTable t,int r,int c){ return t.getTableRow(r).getTableCellList().get(c); }
  static Map<TableCell,int[]> positions(ControlTable t){ Map<TableCell,int[]> m=new IdentityHashMap<>(); for(int r=0;r<t.getRowCount();r++){ RexObjectList<TableCell> l=t.getTableRow(r).getTableCellList(); for(int c=0;c<l.size();c++) m.put(l.get(c),new int[]{r,c}); } return m; }
  /** 병합 자리의 기준 셀 인덱스를 실제 위치로 다시 맞춘다(구조 변경 후 필수). */
  static void reindex(ControlTable t){
    Map<TableCell,int[]> pos=positions(t);
    for(int r=0;r<t.getRowCount();r++){ RexObjectList<TableCell> l=t.getTableRow(r).getTableCellList(); for(int c=0;c<l.size();c++){ TableCell cell=l.get(c); if(cell instanceof TableCellDumy){ TableCellDumy d=(TableCellDumy)cell; TableCellNormal b=d.getBaseCell(); int[] p=b==null?null:pos.get(b); if(p!=null){ d.setBaseCellRowIndex(p[0]); d.setBaseCellColIndex(p[1]); } } } }
    try{ t.linkBaseCell(); t.setBaseCellRowColIndex(); }catch(Throwable e){}
    int th=0; for(int r=0;r<t.getRowCount();r++) th+=t.getTableRow(r).getHeight(); int tw=0; for(int c=0;c<t.getColumnCount();c++) tw+=t.getTableColumn(c).getWidth(); t.setHeight(th); t.setWidth(tw);
  }
  /** 격자 불변식 검사: 문제 목록(비어 있으면 정상). */
  static List<String> checkGrid(ControlTable t){
    List<String> bad=new ArrayList<>(); int rows=t.getRowCount(), cols=t.getColumnCount(); Map<TableCell,int[]> pos=positions(t);
    for(int r=0;r<rows;r++){ TableRow row=t.getTableRow(r); RexObjectList<TableCell> l=row.getTableCellList(); if(l.size()!=cols){ bad.add("행 "+r+" 셀 수 "+l.size()+"≠"+cols); continue; }
      for(int c=0;c<cols;c++){ TableCell cell=l.get(c); if(cell.getTableRow()!=row) bad.add("["+r+","+c+"] 행 참조 불일치"); if(cell.getTableColumn()!=t.getTableColumn(c)) bad.add("["+r+","+c+"] 열 참조 불일치");
        if(cell instanceof TableCellDumy){ TableCellNormal b=((TableCellDumy)cell).getBaseCell(); int[] p=b==null?null:pos.get(b); if(p==null){ bad.add("["+r+","+c+"] 병합 자리의 기준 셀 없음"); continue; }
          if(r<p[0]||r>=p[0]+Math.max(1,b.getRowSpan())||c<p[1]||c>=p[1]+Math.max(1,b.getColSpan())) bad.add("["+r+","+c+"] 기준 셀 ["+p[0]+","+p[1]+"] span "+b.getRowSpan()+"×"+b.getColSpan()+" 밖");
          if(((TableCellDumy)cell).getBaseCellRowIndex()!=p[0]||((TableCellDumy)cell).getBaseCellColIndex()!=p[1]) bad.add("["+r+","+c+"] 기준 인덱스 "+((TableCellDumy)cell).getBaseCellRowIndex()+","+((TableCellDumy)cell).getBaseCellColIndex()+"≠"+p[0]+","+p[1]); }
        else if(cell instanceof TableCellNormal){ TableCellNormal n=(TableCellNormal)cell; int rs=Math.max(1,n.getRowSpan()), cs=Math.max(1,n.getColSpan()); if(r+rs>rows||c+cs>cols) bad.add("["+r+","+c+"] span "+rs+"×"+cs+" 이 표를 벗어남");
          for(int rr=r;rr<r+rs&&rr<rows;rr++) for(int cc=c;cc<c+cs&&cc<cols;cc++){ if(rr==r&&cc==c) continue; TableCell o=cellAt(t,rr,cc); if(!(o instanceof TableCellDumy)||((TableCellDumy)o).getBaseCell()!=n) bad.add("["+rr+","+cc+"] 은 ["+r+","+c+"] 의 병합 자리여야 함"); } }
        else bad.add("["+r+","+c+"] 알 수 없는 셀 "+cell.getClass().getSimpleName()); } }
    for(int c=0;c<cols;c++){ RexObjectList<TableCell> l=t.getTableColumn(c).getTableCellList(); if(l.size()!=rows){ bad.add("열 "+c+" 셀 수 "+l.size()+"≠"+rows); continue; } for(int r=0;r<rows;r++) if(l.get(r)!=cellAt(t,r,c)) bad.add("열 "+c+" 행 "+r+" 셀이 행 리스트와 다름"); }
    return bad;
  }
  static Set<String> cellNames(ControlTable t){ Set<String> n=new HashSet<>(); for(int r=0;r<t.getRowCount();r++){ RexObjectList<TableCell> l=t.getTableRow(r).getTableCellList(); for(int c=0;c<l.size();c++){ String x=g(l.get(c),"getName"); if(x!=null) n.add(x); } } return n; }
  static String uniqueCellName(Set<String> used,String base){ if(!used.contains(base)){ used.add(base); return base; } for(int i=2;;i++) if(!used.contains(base+"_"+i)){ used.add(base+"_"+i); return base+"_"+i; } }

  // ---- 셀 속성 복사(리플렉션) ----
  static final Set<String> SKIP=new HashSet<>(Arrays.asList("Name","RowSpan","ColSpan","TableRow","TableColumn","Table","ObjectID","BaseCell","BaseCellRowIndex","BaseCellColIndex","RefInfomationStorage","Class"));
  static final Set<String> CONTENT=new HashSet<>(Arrays.asList("ApplyValueType","ApplyValueText","ApplyValueField","OutputFormat","CellContent","CheckType","CheckColor","CheckSize","CheckValueDefault","CheckShapeType","CheckShapeColor","CheckValueTrueCondition","CheckValueFalseCondition","ExtendFormatOption","Summary","Function","WebLinkInfo","BackImageInfo","VerifyBarcodeKey","BrailleAlternativeValueType","BrailleAlternativeText","BrailleAlternativeField","BrailleCommand","BraillePreTitle","BrailleFooter","HtmlFieldValue","SuppressReplaceText"));
  /** from → to 속성 복사. withContent=false 면 값/체크박스/공식 관련은 빼고 스타일(글꼴·정렬·테두리·배경·확장·자동합치기)만. 중첩 RexObject(TextInfo/LineInfo/Condition…)는 재귀, 리스트는 건너뜀. */
  static void copyProps(Object from,Object to,boolean withContent,int depth){
    if(from==null||to==null||depth>3) return;
    for(java.lang.reflect.Method gm: from.getClass().getMethods()){ if(gm.getParameterCount()!=0) continue; String n=gm.getName(), p; if(n.startsWith("get")) p=n.substring(3); else if(n.startsWith("is")) p=n.substring(2); else continue;
      if(p.isEmpty()||SKIP.contains(p)||(!withContent&&CONTENT.contains(p))) continue; Class<?> rt=gm.getReturnType(); if(rt==void.class||RexObjectList.class.isAssignableFrom(rt)) continue;
      Object v; try{ v=gm.invoke(from); }catch(Throwable e){ continue; }
      java.lang.reflect.Method sm=null; try{ sm=to.getClass().getMethod("set"+p,rt); }catch(NoSuchMethodException e){}
      if(rt.isPrimitive()||rt.isEnum()||rt==String.class||Field.class.isAssignableFrom(rt)||rt==byte[].class){ if(sm!=null){ try{ sm.invoke(to,v); }catch(Throwable e){} } }
      else if(v instanceof com.clipsoft.clipreport.base.RexObject){ Object tv; try{ tv=gm.invoke(to); }catch(Throwable e){ continue; } if(tv!=null&&tv.getClass()==v.getClass()) copyProps(v,tv,withContent,depth+1); } }
  }
  static TableCellNormal newCell(ControlTable t,TableRow row,TableColumn col,TableCellNormal styleFrom,boolean withContent,String name){
    TableCellNormal n=new TableCellNormal(); n.setName(name); n.setTableRow(row); n.setTableColumn(col); n.setRowSpan(1); n.setColSpan(1);
    n.setApplyValueType(com.clipsoft.clipreport.base.enums.ApplyValueType.Text); n.setApplyValueText(""); CrfMcpServer.noDiagonal(n);
    if(styleFrom!=null) copyProps(styleFrom,n,withContent,0); n.setRowSpan(1); n.setColSpan(1); return n;
  }
  static TableCellDumy newDummy(TableRow row,TableColumn col,TableCellNormal base){ TableCellDumy d=new TableCellDumy(); d.setTableRow(row); d.setTableColumn(col); d.setBaseCell(base); return d; }
  static void redirectDummies(ControlTable t,TableCellNormal from,TableCellNormal to){ for(int r=0;r<t.getRowCount();r++){ RexObjectList<TableCell> l=t.getTableRow(r).getTableCellList(); for(int c=0;c<l.size();c++) if(l.get(c) instanceof TableCellDumy&&((TableCellDumy)l.get(c)).getBaseCell()==from) ((TableCellDumy)l.get(c)).setBaseCell(to); } }
  static void replaceInLists(ControlTable t,int r,int c,TableCell old,TableCell nw){ CrfMcpServer.replaceCell(t,r,c,old,nw); }

  // ---- 행 삽입/복제 ----
  /** at 위치(0-based; rows 면 맨 끝)에 count 개 행 삽입. 구조/스타일은 srcRow 를 따르고(가로 병합 복제), withContent 면 값도 복사. 삽입 지점을 가로지르는 세로 병합은 그대로 늘어난다. */
  static void insertRows(ControlTable t,int at,int count,int height,int srcRow,boolean withContent){
    int cols=t.getColumnCount(); Set<String> used=cellNames(t); TableRow src=srcRow>=0&&srcRow<t.getRowCount()?t.getTableRow(srcRow):null;
    for(int k=0;k<count;k++){ int i=at+k; int rows=t.getRowCount(); TableRow nr=new TableRow(); nr.setHeight(height>0?height:(src!=null?src.getHeight():60)); t.getTableRowList().add(i,nr);
      Map<TableCellNormal,TableCellNormal> copied=new IdentityHashMap<>(); Set<TableCellNormal> grown=Collections.newSetFromMap(new IdentityHashMap<>());
      for(int c=0;c<cols;c++){ TableColumn col=t.getTableColumn(c); RexObjectList<TableCell> cl=col.getTableCellList();
        TableCell above=i>0?cl.get(i-1):null, below=i<rows?cl.get(i):null; TableCellNormal ba=baseOf(above), bb=baseOf(below);
        TableCell nc;
        if(above!=null&&below!=null&&ba!=null&&ba==bb&&ba.getRowSpan()>1){ nc=newDummy(nr,col,ba); if(grown.add(ba)) ba.setRowSpan(ba.getRowSpan()+1); }
        else { TableCell sc=src!=null?src.getTableCellList().get(c):(above!=null?above:below); TableCellNormal sb=baseOf(sc); int bc=sb==null?c:sb.getTableColumn()==null?c:t.getTableColumnList().indexOf(sb.getTableColumn());
          boolean srcRowOwns=sb!=null&&src!=null&&sb.getTableRow()==src;
          if(sb!=null&&sb.getColSpan()>1&&bc<c&&copied.containsKey(sb)) nc=newDummy(nr,col,copied.get(sb));
          else { TableCellNormal n=newCell(t,nr,col,sb,withContent&&srcRowOwns,uniqueCellName(used,t.getName()+"_r"+i+"c"+c)); if(sb!=null&&sb.getColSpan()>1&&bc==c&&c+sb.getColSpan()<=cols){ n.setColSpan(sb.getColSpan()); copied.put(sb,n); } nc=n; } }
        nr.getTableCellList().add(nc); cl.add(i,nc); } }
    reindex(t);
  }
  /** 행 삭제: 세로 병합의 기준 셀이 지워지면 바로 아랫 셀로 승격, 병합 자리가 지워지면 기준 셀 rowSpan 감소. */
  static void deleteRow(ControlTable t,int i){
    int cols=t.getColumnCount(); TableRow row=t.getTableRow(i); Set<TableCellNormal> shrunk=Collections.newSetFromMap(new IdentityHashMap<>()); Map<TableCell,int[]> pos=positions(t);
    for(int c=0;c<cols;c++){ TableCell cell=row.getTableCellList().get(c);
      if(cell instanceof TableCellNormal){ TableCellNormal n=(TableCellNormal)cell; if(n.getRowSpan()>1&&i+1<t.getRowCount()){ TableRow br=t.getTableRow(i+1); TableCell bc=br.getTableCellList().get(c); TableCellNormal p=new TableCellNormal(); p.setName(n.getName()); p.setTableRow(br); p.setTableColumn(t.getTableColumn(c)); copyProps(n,p,true,0); p.setRowSpan(n.getRowSpan()-1); p.setColSpan(n.getColSpan()); replaceInLists(t,i+1,c,bc,p); redirectDummies(t,n,p); } }
      else if(cell instanceof TableCellDumy){ TableCellNormal b=((TableCellDumy)cell).getBaseCell(); int[] bp=b==null?null:pos.get(b); if(b!=null&&bp!=null&&bp[0]!=i&&b.getRowSpan()>1&&shrunk.add(b)) b.setRowSpan(b.getRowSpan()-1); } }
    for(int c=0;c<cols;c++){ RexObjectList<TableCell> cl=t.getTableColumn(c).getTableCellList(); for(int k=0;k<cl.size();k++) if(cl.get(k)==row.getTableCellList().get(c)){ cl.remove(k); break; } }
    t.getTableRowList().remove(i); reindex(t);
  }
  // ---- 열 삽입/삭제 ----
  static void insertCols(ControlTable t,int at,int count,int width,int srcCol,boolean withContent){
    int rows=t.getRowCount(); Set<String> used=cellNames(t); TableColumn src=srcCol>=0&&srcCol<t.getColumnCount()?t.getTableColumn(srcCol):null;
    for(int k=0;k<count;k++){ int i=at+k; int cols=t.getColumnCount(); TableColumn ncol=new TableColumn(); ncol.setWidth(width>0?width:(src!=null?src.getWidth():300)); t.getTableColumnList().add(i,ncol);
      Map<TableCellNormal,TableCellNormal> copied=new IdentityHashMap<>(); Set<TableCellNormal> grown=Collections.newSetFromMap(new IdentityHashMap<>());
      for(int r=0;r<rows;r++){ TableRow row=t.getTableRow(r); RexObjectList<TableCell> rl=row.getTableCellList();
        TableCell left=i>0?rl.get(i-1):null, right=i<cols?rl.get(i):null; TableCellNormal bl=baseOf(left), br=baseOf(right);
        TableCell nc;
        if(left!=null&&right!=null&&bl!=null&&bl==br&&bl.getColSpan()>1){ nc=newDummy(row,ncol,bl); if(grown.add(bl)) bl.setColSpan(bl.getColSpan()+1); }
        else { TableCell sc=src!=null?src.getTableCellList().get(r):(left!=null?left:right); TableCellNormal sb=baseOf(sc); int brr=sb==null?r:sb.getTableRow()==null?r:t.getTableRowList().indexOf(sb.getTableRow());
          boolean srcColOwns=sb!=null&&src!=null&&sb.getTableColumn()==src;
          if(sb!=null&&sb.getRowSpan()>1&&brr<r&&copied.containsKey(sb)) nc=newDummy(row,ncol,copied.get(sb));
          else { TableCellNormal n=newCell(t,row,ncol,sb,withContent&&srcColOwns,uniqueCellName(used,t.getName()+"_r"+r+"c"+i)); if(sb!=null&&sb.getRowSpan()>1&&brr==r&&r+sb.getRowSpan()<=rows){ n.setRowSpan(sb.getRowSpan()); copied.put(sb,n); } nc=n; } }
        ncol.getTableCellList().add(nc); rl.add(i,nc); } }
    reindex(t);
  }
  static void deleteCol(ControlTable t,int i){
    int rows=t.getRowCount(); TableColumn col=t.getTableColumn(i); Set<TableCellNormal> shrunk=Collections.newSetFromMap(new IdentityHashMap<>()); Map<TableCell,int[]> pos=positions(t);
    for(int r=0;r<rows;r++){ TableCell cell=col.getTableCellList().get(r);
      if(cell instanceof TableCellNormal){ TableCellNormal n=(TableCellNormal)cell; if(n.getColSpan()>1&&i+1<t.getColumnCount()){ TableColumn rc=t.getTableColumn(i+1); TableCell rcell=rc.getTableCellList().get(r); TableCellNormal p=new TableCellNormal(); p.setName(n.getName()); p.setTableRow(t.getTableRow(r)); p.setTableColumn(rc); copyProps(n,p,true,0); p.setColSpan(n.getColSpan()-1); p.setRowSpan(n.getRowSpan()); replaceInLists(t,r,i+1,rcell,p); redirectDummies(t,n,p); } }
      else if(cell instanceof TableCellDumy){ TableCellNormal b=((TableCellDumy)cell).getBaseCell(); int[] bp=b==null?null:pos.get(b); if(b!=null&&bp!=null&&bp[1]!=i&&b.getColSpan()>1&&shrunk.add(b)) b.setColSpan(b.getColSpan()-1); } }
    for(int r=0;r<rows;r++){ RexObjectList<TableCell> rl=t.getTableRow(r).getTableCellList(); for(int k=0;k<rl.size();k++) if(rl.get(k)==col.getTableCellList().get(r)){ rl.remove(k); break; } }
    t.getTableColumnList().remove(i); reindex(t);
  }
  // ---- 이동 ----
  static String verticalMergeIn(ControlTable t,int r0,int r1){ for(int r=r0;r<=r1;r++) for(int c=0;c<t.getColumnCount();c++){ TableCellNormal b=baseOf(cellAt(t,r,c)); if(b!=null&&b.getRowSpan()>1) return "["+r+","+c+"]"; } return null; }
  static String horizontalMergeIn(ControlTable t,int c0,int c1){ for(int c=c0;c<=c1;c++) for(int r=0;r<t.getRowCount();r++){ TableCellNormal b=baseOf(cellAt(t,r,c)); if(b!=null&&b.getColSpan()>1) return "["+r+","+c+"]"; } return null; }
  static void moveRow(ControlTable t,int from,int to){
    TableRow row=t.getTableRow(from); t.getTableRowList().remove(from); t.getTableRowList().add(to,row);
    for(int c=0;c<t.getColumnCount();c++){ RexObjectList<TableCell> cl=t.getTableColumn(c).getTableCellList(); TableCell cell=cl.get(from); cl.remove(from); cl.add(to,cell); }
    reindex(t);
  }
  static void moveCol(ControlTable t,int from,int to){
    TableColumn col=t.getTableColumn(from); t.getTableColumnList().remove(from); t.getTableColumnList().add(to,col);
    for(int r=0;r<t.getRowCount();r++){ RexObjectList<TableCell> rl=t.getTableRow(r).getTableCellList(); TableCell cell=rl.get(from); rl.remove(from); rl.add(to,cell); }
    reindex(t);
  }
  // ---- 크기 ----
  /** "60" / "60,80,40"(전체) / "0:60,2:80"(인덱스:값) / "all:60" 파싱 → 인덱스→값. */
  static Map<Integer,Integer> parseSizes(String spec,int n,String what){
    Map<Integer,Integer> m=new LinkedHashMap<>(); if(spec==null||spec.trim().isEmpty()) return m; String[] parts=spec.split(",");
    if(parts.length==1&&!parts[0].contains(":")){ int v=Integer.parseInt(parts[0].trim()); if(v<=0) throw new RuntimeException(what+" 는 양수"); for(int i=0;i<n;i++) m.put(i,v); return m; }
    boolean indexed=spec.contains(":");
    if(!indexed){ if(parts.length!=n) throw new RuntimeException(what+" 목록 "+parts.length+"개 ≠ "+n+"개 — 전체를 나열하거나 '인덱스:값' 형식을 쓰세요"); for(int i=0;i<n;i++){ int v=Integer.parseInt(parts[i].trim()); if(v<=0) throw new RuntimeException(what+"["+i+"] 는 양수"); m.put(i,v); } return m; }
    for(String p: parts){ String[] kv=p.split(":"); if(kv.length!=2) throw new RuntimeException(what+" 형식: '인덱스:값,…' 또는 '값,값,…'"); int v=Integer.parseInt(kv[1].trim()); if(v<=0) throw new RuntimeException(what+" 는 양수"); String k=kv[0].trim();
      if(k.equalsIgnoreCase("all")){ for(int i=0;i<n;i++) m.put(i,v); } else { int i=Integer.parseInt(k); if(i<0||i>=n) throw new RuntimeException(what+" 인덱스 "+i+" 범위 밖 (0.."+(n-1)+")"); m.put(i,v); } }
    return m;
  }
  static int[] equalize(int total,int n){ int[] v=new int[n]; int each=total/n, rem=total-each*n; for(int i=0;i<n;i++) v[i]=each+(i==n-1?rem:0); return v; }
  static int[] scaleTo(int[] cur,int target){ int n=cur.length, tot=0; for(int x: cur) tot+=x; int[] v=new int[n]; int acc=0; for(int i=0;i<n;i++){ v[i]=i==n-1?target-acc:(tot==0?target/n:(int)Math.round((double)cur[i]*target/tot)); if(v[i]<1) v[i]=1; acc+=v[i]; } return v; }
  static List<Integer> parseIndexList(String spec,int n,String what){ List<Integer> out=new ArrayList<>(); if(spec==null) return out; for(String p: spec.split(",")){ p=p.trim(); if(p.isEmpty()) continue; if(p.contains("-")){ String[] ab=p.split("-"); int a=Integer.parseInt(ab[0].trim()), b=Integer.parseInt(ab[1].trim()); for(int i=a;i<=b;i++) out.add(i); } else out.add(Integer.parseInt(p)); }
    for(int i: out) if(i<0||i>=n) throw new RuntimeException(what+" "+i+" 범위 밖 (0.."+(n-1)+")"); return out; }

  // ---- 밴드 안 다른 컨트롤 이동 / 밴드 높이 ----
  /** 표 아래(vertical) / 오른쪽 요소를 delta 만큼 옮기고, 표를 가로질러 감싸는 요소(시작<edge<끝)는 delta 만큼 늘인다. */
  @SuppressWarnings("unchecked")
  static String shiftOthers(Ctx c,boolean vertical,int edge,int delta){
    if(c.loc==null||delta==0) return ""; RexObjectList<Control> cl=(RexObjectList<Control>)c.loc[2]; List<String> moved=new ArrayList<>(), grown=new ArrayList<>();
    for(int k=0;k<cl.size();k++){ Control o=cl.get(k); if(o==c.t) continue; int p=vertical?o.getY1():o.getX1(), len=ix(o,vertical?"getHeight":"getWidth");
      if(p>=edge){ if(vertical) o.setY1(p+delta); else o.setX1(p+delta); moved.add(o.getName()); }
      else if(p+len>edge&&len+delta>0&&!(o instanceof ControlTable)){ CrfMcpServer.call(o,vertical?"setHeight":"setWidth",int.class,len+delta); grown.add(o.getName()); } }
    return (moved.isEmpty()?"":", "+(vertical?"아래":"오른쪽")+" 요소 "+moved.size()+"개 "+(delta>0?"+":"")+delta+" 이동("+String.join(",",moved)+")")+(grown.isEmpty()?"":", 감싸는 요소 "+grown.size()+"개 "+(vertical?"높이":"너비")+" "+(delta>0?"+":"")+delta+"("+String.join(",",grown)+")");
  }
  /** 밴드 높이: 내용이 넘치면 키우고, shrinkBy<0 이면 그만큼 줄이되 내용 아래로는 안 줄인다. */
  @SuppressWarnings("unchecked")
  static String fitBand(Ctx c,int shrinkBy){
    if(c.loc==null||!(c.loc[1] instanceof SubSectionDefault)) return ""; SubSectionDefault sub=(SubSectionDefault)c.loc[1]; RexObjectList<Control> cl=(RexObjectList<Control>)c.loc[2]; int maxB=0;
    for(int k=0;k<cl.size();k++){ Control o=cl.get(k); maxB=Math.max(maxB,o.getY1()+ix(o,"getHeight")); }
    int h=sub.getHeight(), target=shrinkBy<0?Math.max(maxB,h+shrinkBy):Math.max(h,maxB); if(target==h) return ""; sub.setHeight(target); return ", 밴드 높이 "+h+"→"+target;
  }
  static String verify(Ctx c,int rows,int cols) throws Exception {
    Control v=CrfMcpServer.findTable(CrfMcpServer.open(c.output),c.name); if(!(v instanceof ControlTable)) return "ERROR: 저장 후 되읽기 검증 실패 — 표가 없음";
    ControlTable vt=(ControlTable)v; if(vt.getRowCount()!=rows||vt.getColumnCount()!=cols) return "ERROR: 저장 후 되읽기 검증 실패 — "+vt.getRowCount()+"행×"+vt.getColumnCount()+"열 (기대 "+rows+"×"+cols+")";
    List<String> bad=checkGrid(vt); if(!bad.isEmpty()) return "ERROR: 저장 후 격자 검증 실패 — "+bad.get(0)+(bad.size()>1?" 외 "+(bad.size()-1)+"건":"");
    return null;
  }

  // ================= 도구 =================
  /** crf_table_rows */
  static String tableRows(JSONObject args) throws Exception {
    Ctx c=ctx(args); ControlTable t=c.t; String action=q(s(args,"action")).trim().toLowerCase(); int rows=t.getRowCount(), cols=t.getColumnCount(); if(action.isEmpty()) return "ERROR: action 은 insert|delete|copy|move|resize|equalize";
    boolean shift=!"false".equalsIgnoreCase(s(args,"shift")); int oldH=t.getHeight(), oldBottom=t.getY1()+oldH; String did;
    List<String> before=checkGrid(t); if(!before.isEmpty()) return "ERROR: 표 '"+c.name+"' 격자가 이미 손상되어 있습니다 — "+before.get(0)+" (디자이너에서 확인)";
    switch(action){
      case "insert": case "copy": {
        int count=pInt(args.get("count"),1); if(count<1||count>200) return "ERROR: count 는 1..200";
        int height=pInt(args.get("height"),0); int at, src;
        String pos=q(s(args,"position")).trim().toLowerCase();
        if(action.equals("copy")){ src=pInt(args.get("row"),-1); if(src<0||src>=rows) return "ERROR: copy 는 row(복제할 원본 행, 0.."+(rows-1)+") 가 필요합니다"; at=args.get("at")!=null?pInt(args.get("at"),src+1):pos.equals("before")?src:src+1; }
        else { if(args.get("at")!=null) at=pInt(args.get("at"),rows); else if(args.get("row")!=null){ int r=pInt(args.get("row"),rows-1); at=pos.equals("before")?r:r+1; } else at=rows; src=at>0?at-1:0; }
        if(at<0||at>rows) return "ERROR: at "+at+" 범위 밖 (0.."+rows+", "+rows+"=맨 끝)";
        insertRows(t,at,count,height,src,action.equals("copy"));
        did=(action.equals("copy")?"행 "+src+" 복제 → ":"행 삽입 ")+"["+at+".."+(at+count-1)+"] "+count+"개(높이 "+t.getTableRow(at).getHeight()+", 구조·스타일은 행 "+src+" 기준)"; break; }
      case "delete": {
        List<Integer> idx=parseIndexList(s(args,"row")!=null?s(args,"row"):s(args,"rows"),rows,"row"); if(idx.isEmpty()) return "ERROR: delete 는 row(인덱스, '1,3' 또는 '2-4') 가 필요합니다";
        Set<Integer> set=new TreeSet<>(Collections.reverseOrder()); set.addAll(idx); if(set.size()>=rows) return "ERROR: 모든 행을 지울 수 없습니다 (표 삭제는 crf_remove_control)";
        for(int i: set) deleteRow(t,i); did="행 "+new TreeSet<>(idx)+" 삭제"; break; }
      case "move": {
        int from=pInt(args.get("row"),pInt(args.get("from"),-1)), to=pInt(args.get("to"),-1); if(from<0||from>=rows||to<0||to>=rows) return "ERROR: move 는 row(원본)·to(목적지) 0.."+(rows-1)+" 이 필요합니다"; if(from==to) return "ERROR: row 와 to 가 같습니다";
        String vm=verticalMergeIn(t,Math.min(from,to),Math.max(from,to)); if(vm!=null) return "ERROR: 행 "+Math.min(from,to)+".."+Math.max(from,to)+" 구간에 세로 병합 셀 "+vm+" 이 있어 행을 옮길 수 없습니다 — 먼저 crf_merge_cells 로 해제하세요";
        moveRow(t,from,to); did="행 "+from+" → "+to+" 이동"; break; }
      case "resize": {
        String spec=s(args,"heights"); if(spec==null||spec.trim().isEmpty()){ if(args.get("height")==null) return "ERROR: resize 는 heights('60,80,…' 전체 | '1:80,3:40' 인덱스:값) 또는 row+height 가 필요합니다"; List<Integer> idx=s(args,"row")==null?null:parseIndexList(s(args,"row"),rows,"row"); StringBuilder sb=new StringBuilder(); if(idx==null||idx.isEmpty()) sb.append("all:").append(s(args,"height")); else for(int i: idx) sb.append(sb.length()>0?",":"").append(i).append(":").append(s(args,"height")); spec=sb.toString(); }
        Map<Integer,Integer> m=parseSizes(spec,rows,"height"); for(Map.Entry<Integer,Integer> e: m.entrySet()) t.getTableRow(e.getKey()).setHeight(e.getValue()); reindex(t); did="행 높이 "+m.size()+"개 변경 "+m; break; }
      case "equalize": {
        int each=pInt(args.get("height"),0); int[] v=each>0?null:equalize(oldH,rows); for(int r=0;r<rows;r++) t.getTableRow(r).setHeight(each>0?each:v[r]); reindex(t); did="행 "+rows+"개 높이 균등 "+(each>0?each+" 씩":"(총 "+oldH+" 유지, 각 "+v[0]+(v[rows-1]!=v[0]?"/마지막 "+v[rows-1]:"")+")"); break; }
      default: return "ERROR: action '"+action+"' — insert|delete|copy|move|resize|equalize";
    }
    List<String> bad=checkGrid(t); if(!bad.isEmpty()) return "ERROR: 편집 결과 격자 불일치 — "+bad.get(0)+" (파일은 쓰지 않음)";
    int delta=t.getHeight()-oldH; String extra=""; if(shift&&delta!=0) extra+=shiftOthers(c,true,oldBottom,delta); extra+=fitBand(c,shift&&delta<0?delta:0);
    String wrote=CrfMcpServer.save(c.rf,c.output,c.path); String err=verify(c,t.getRowCount(),cols); if(err!=null) return err;
    return "OK: 표 '"+c.name+"' ("+band(c)+") "+did+" → "+t.getRowCount()+"행×"+cols+"열, 높이 "+oldH+"→"+t.getHeight()+extra+", verified[grid ok], wrote "+wrote+(delta>0&&!shift?"\n⚠ shift=false 라 표 아래 요소는 그대로입니다 — 겹치면 crf_set_label 로 옮기세요":"");
  }
  /** crf_table_cols */
  static String tableCols(JSONObject args) throws Exception {
    Ctx c=ctx(args); ControlTable t=c.t; String action=q(s(args,"action")).trim().toLowerCase(); int rows=t.getRowCount(), cols=t.getColumnCount(); if(action.isEmpty()) return "ERROR: action 은 insert|delete|copy|move|resize|equalize";
    boolean shift="true".equalsIgnoreCase(s(args,"shift")); int oldW=t.getWidth(), oldRight=t.getX1()+oldW; String did;
    List<String> before=checkGrid(t); if(!before.isEmpty()) return "ERROR: 표 '"+c.name+"' 격자가 이미 손상되어 있습니다 — "+before.get(0)+" (디자이너에서 확인)";
    switch(action){
      case "insert": case "copy": {
        int count=pInt(args.get("count"),1); if(count<1||count>100) return "ERROR: count 는 1..100";
        int width=pInt(args.get("width"),0); int at, src;
        String pos=q(s(args,"position")).trim().toLowerCase();
        if(action.equals("copy")){ src=pInt(args.get("col"),-1); if(src<0||src>=cols) return "ERROR: copy 는 col(복제할 원본 열, 0.."+(cols-1)+") 가 필요합니다"; at=args.get("at")!=null?pInt(args.get("at"),src+1):pos.equals("before")?src:src+1; }
        else { if(args.get("at")!=null) at=pInt(args.get("at"),cols); else if(args.get("col")!=null){ int k=pInt(args.get("col"),cols-1); at=pos.equals("before")?k:k+1; } else at=cols; src=at>0?at-1:0; }
        if(at<0||at>cols) return "ERROR: at "+at+" 범위 밖 (0.."+cols+", "+cols+"=맨 끝)";
        insertCols(t,at,count,width,src,action.equals("copy"));
        did=(action.equals("copy")?"열 "+src+" 복제 → ":"열 삽입 ")+"["+at+".."+(at+count-1)+"] "+count+"개(너비 "+t.getTableColumn(at).getWidth()+", 구조·스타일은 열 "+src+" 기준)"; break; }
      case "delete": {
        List<Integer> idx=parseIndexList(s(args,"col")!=null?s(args,"col"):s(args,"cols"),cols,"col"); if(idx.isEmpty()) return "ERROR: delete 는 col(인덱스, '1,3' 또는 '2-4') 가 필요합니다";
        Set<Integer> set=new TreeSet<>(Collections.reverseOrder()); set.addAll(idx); if(set.size()>=cols) return "ERROR: 모든 열을 지울 수 없습니다 (표 삭제는 crf_remove_control)";
        for(int i: set) deleteCol(t,i); did="열 "+new TreeSet<>(idx)+" 삭제"; break; }
      case "move": {
        int from=pInt(args.get("col"),pInt(args.get("from"),-1)), to=pInt(args.get("to"),-1); if(from<0||from>=cols||to<0||to>=cols) return "ERROR: move 는 col(원본)·to(목적지) 0.."+(cols-1)+" 이 필요합니다"; if(from==to) return "ERROR: col 과 to 가 같습니다";
        String hm=horizontalMergeIn(t,Math.min(from,to),Math.max(from,to)); if(hm!=null) return "ERROR: 열 "+Math.min(from,to)+".."+Math.max(from,to)+" 구간에 가로 병합 셀 "+hm+" 이 있어 열을 옮길 수 없습니다 — 먼저 crf_merge_cells 로 해제하세요";
        moveCol(t,from,to); did="열 "+from+" → "+to+" 이동"; break; }
      case "resize": {
        String spec=s(args,"widths"); if(spec==null||spec.trim().isEmpty()){ if(args.get("width")==null) return "ERROR: resize 는 widths('300,500,…' 전체 | '1:400,3:200' 인덱스:값) 또는 col+width 가 필요합니다"; List<Integer> idx=s(args,"col")==null?null:parseIndexList(s(args,"col"),cols,"col"); StringBuilder sb=new StringBuilder(); if(idx==null||idx.isEmpty()) sb.append("all:").append(s(args,"width")); else for(int i: idx) sb.append(sb.length()>0?",":"").append(i).append(":").append(s(args,"width")); spec=sb.toString(); }
        Map<Integer,Integer> m=parseSizes(spec,cols,"width"); for(Map.Entry<Integer,Integer> e: m.entrySet()) t.getTableColumn(e.getKey()).setWidth(e.getValue()); reindex(t); did="열 너비 "+m.size()+"개 변경 "+m; break; }
      case "equalize": {
        int each=pInt(args.get("width"),0); int[] v=each>0?null:equalize(oldW,cols); for(int k=0;k<cols;k++) t.getTableColumn(k).setWidth(each>0?each:v[k]); reindex(t); did="열 "+cols+"개 너비 균등 "+(each>0?each+" 씩":"(총 "+oldW+" 유지, 각 "+v[0]+(v[cols-1]!=v[0]?"/마지막 "+v[cols-1]:"")+")"); break; }
      default: return "ERROR: action '"+action+"' — insert|delete|copy|move|resize|equalize";
    }
    List<String> bad=checkGrid(t); if(!bad.isEmpty()) return "ERROR: 편집 결과 격자 불일치 — "+bad.get(0)+" (파일은 쓰지 않음)";
    int delta=t.getWidth()-oldW; String extra=""; if(shift&&delta!=0) extra+=shiftOthers(c,false,oldRight,delta); extra+=fitBand(c,0);
    String wrote=CrfMcpServer.save(c.rf,c.output,c.path); String err=verify(c,rows,t.getColumnCount()); if(err!=null) return err;
    return "OK: 표 '"+c.name+"' ("+band(c)+") "+did+" → "+rows+"행×"+t.getColumnCount()+"열, 너비 "+oldW+"→"+t.getWidth()+extra+", verified[grid ok], wrote "+wrote+(t.getX1()+t.getWidth()>pageInnerWidth(c.rf)&&pageInnerWidth(c.rf)>0?"\n⚠ 표 오른쪽 끝("+(t.getX1()+t.getWidth())+") 이 용지 본문 너비("+pageInnerWidth(c.rf)+") 를 넘습니다 — crf_set_table width= 로 줄이거나 crf_table_cols resize":"");
  }
  static int pageInnerWidth(TheReportFile rf){ try{ Object mp=rf.getGlobe().getMainReport().getReportDesign().getMainPage(); Object pi=go(mp,"getPageInfo"); if(pi==null) return 0; int w=ix(pi,"getPaperWidth"); if(w<=0) return 0; return w-ix(pi,"getMarginLeft")-ix(pi,"getMarginRight"); }catch(Throwable e){ return 0; } }

  /** crf_set_table: 위치/크기(비례)/테두리/이름/표 옵션/전체 병합 해제 */
  static String setTable(JSONObject args) throws Exception {
    Ctx c=ctx(args); ControlTable t=c.t; StringBuilder did=new StringBuilder(); int rows=t.getRowCount(), cols=t.getColumnCount();
    if(args.get("left")!=null){ t.setX1(pInt(args.get("left"),t.getX1())); did.append(" left="+t.getX1()); } if(args.get("top")!=null){ t.setY1(pInt(args.get("top"),t.getY1())); did.append(" top="+t.getY1()); }
    if(args.get("width")!=null){ int w=pInt(args.get("width"),0); if(w<cols) return "ERROR: width 는 열 수 이상"; int[] cur=new int[cols]; for(int k=0;k<cols;k++) cur[k]=t.getTableColumn(k).getWidth(); int[] v=scaleTo(cur,w); for(int k=0;k<cols;k++) t.getTableColumn(k).setWidth(v[k]); did.append(" width="+w+"(열 비례 "+Arrays.toString(v)+")"); }
    if(args.get("height")!=null){ int h=pInt(args.get("height"),0); if(h<rows) return "ERROR: height 는 행 수 이상"; int[] cur=new int[rows]; for(int k=0;k<rows;k++) cur[k]=t.getTableRow(k).getHeight(); int[] v=scaleTo(cur,h); for(int k=0;k<rows;k++) t.getTableRow(k).setHeight(v[k]); did.append(" height="+h+"(행 비례 "+Arrays.toString(v)+")"); }
    String border=s(args,"border"); if(border!=null&&!border.isEmpty()){ CrfMcpServer.tableBorder(t,Boolean.parseBoolean(border)); did.append(" 외곽선="+border); }
    String cb=s(args,"cell_border"); if(cb!=null&&!cb.isEmpty()){ int n=0; for(int r=0;r<rows;r++) for(int k=0;k<cols;k++){ TableCell cell=cellAt(t,r,k); if(cell instanceof TableCellNormal){ cellBorderSpec((TableCellNormal)cell,cb,s(args,"linewidth"),s(args,"linecolor")); n++; } } did.append(" 셀테두리="+cb+"("+n+"셀)"); }
    else if((s(args,"linewidth")!=null&&!s(args,"linewidth").isEmpty())||(s(args,"linecolor")!=null&&!s(args,"linecolor").isEmpty())){ int n=0; for(int r=0;r<rows;r++) for(int k=0;k<cols;k++){ TableCell cell=cellAt(t,r,k); if(cell instanceof TableCellNormal){ cellBorderSpec((TableCellNormal)cell,null,s(args,"linewidth"),s(args,"linecolor")); n++; } } did.append(" 셀선 굵기/색 변경("+n+"셀)"); }
    String am=s(args,"auto_merge"); if(am!=null&&!am.isEmpty()){ t.setTableAutoMerge(Boolean.parseBoolean(am)); did.append(" 자동병합="+am); }
    String kt=s(args,"keep_together"); if(kt!=null&&!kt.isEmpty()){ TableKeepTogetherType v; try{ v=TableKeepTogetherType.valueOf(kt.trim()); }catch(Exception e){ return "ERROR: keep_together 는 None|Row|Table"; } t.setTableKeepTogether(v); did.append(" 페이지나눔방지="+v); }
    String vis=s(args,"visible"); if(vis!=null&&!vis.isEmpty()){ t.setVisible(Boolean.parseBoolean(vis)); did.append(" visible="+vis); }
    if("true".equalsIgnoreCase(s(args,"unmerge_all"))){ int n=unmergeAll(t); did.append(" 전체 병합 해제("+n+"셀 복구)"); }
    String nn=q(s(args,"name")).trim(); String finalName=c.name; if(!nn.isEmpty()&&!nn.equals(c.name)){ if(CrfMcpServer.findControlLoc(c.rf,nn)!=null) return "ERROR: 이름 '"+nn+"' 은 이미 다른 컨트롤이 쓰고 있습니다"; t.setName(nn); finalName=nn; did.append(" name="+nn); }
    if(did.length()==0) return "ERROR: nothing to set (left/top/width/height/border/cell_border/linewidth/linecolor/auto_merge/keep_together/visible/unmerge_all/name)";
    reindex(t); List<String> bad=checkGrid(t); if(!bad.isEmpty()) return "ERROR: 편집 결과 격자 불일치 — "+bad.get(0); String extra=fitBand(c,0);
    String wrote=CrfMcpServer.save(c.rf,c.output,c.path); c.name=finalName; String err=verify(c,rows,cols); if(err!=null) return err;
    return "OK: 표 '"+finalName+"' ("+band(c)+") set"+did+" → 위치 "+t.getX1()+","+t.getY1()+" 크기 "+t.getWidth()+"×"+t.getHeight()+extra+", verified[grid ok], wrote "+wrote;
  }
  static int unmergeAll(ControlTable t){
    int n=0; Set<String> used=cellNames(t);
    for(int r=0;r<t.getRowCount();r++) for(int c=0;c<t.getColumnCount();c++){ TableCell cell=cellAt(t,r,c); if(cell instanceof TableCellDumy){ TableCellNormal b=((TableCellDumy)cell).getBaseCell(); TableCellNormal nc=newCell(t,t.getTableRow(r),t.getTableColumn(c),b,false,uniqueCellName(used,t.getName()+"_r"+r+"c"+c)); replaceInLists(t,r,c,cell,nc); n++; } else if(cell instanceof TableCellNormal){ ((TableCellNormal)cell).setRowSpan(1); ((TableCellNormal)cell).setColSpan(1); } }
    reindex(t); return n;
  }
  /** 셀 테두리: spec = true|false|"left,top,right,bottom" 부분집합(나머지는 off). linewidth/linecolor 는 켜진 변에 적용. */
  static void cellBorderSpec(TableCellNormal n,String spec,String lw,String lc){
    boolean[] on=null; if(spec!=null&&!spec.isEmpty()){ String x=spec.trim().toLowerCase(); if(x.equals("true")||x.equals("all")) on=new boolean[]{true,true,true,true}; else if(x.equals("false")||x.equals("none")) on=new boolean[]{false,false,false,false}; else { on=new boolean[4]; for(String p: x.split(",")){ p=p.trim(); if(p.startsWith("l")) on[0]=true; else if(p.startsWith("r")) on[1]=true; else if(p.startsWith("t")) on[2]=true; else if(p.startsWith("b")) on[3]=true; else throw new RuntimeException("border 는 true|false|left,right,top,bottom 조합"); } } }
    LineInfo_apply(n.getLineInfoLeft(),on==null?null:on[0],lw,lc); n.setVisibleLeftLine(on==null?n.getVisibleLeftLine():on[0]);
    LineInfo_apply(n.getLineInfoRight(),on==null?null:on[1],lw,lc); n.setVisibleRightLine(on==null?n.getVisibleRightLine():on[1]);
    LineInfo_apply(n.getLineInfoTop(),on==null?null:on[2],lw,lc); n.setVisibleTopLine(on==null?n.getVisibleTopLine():on[2]);
    LineInfo_apply(n.getLineInfoBottom(),on==null?null:on[3],lw,lc); n.setVisibleBottomLine(on==null?n.getVisibleBottomLine():on[3]);
  }
  static void LineInfo_apply(com.clipsoft.clipreport.base.functions.LineInfo li,Boolean on,String lw,String lc){
    if(on!=null){ li.setLineStyle(on?LineStyle.Solid:LineStyle.None); if(on&&(lw==null||lw.isEmpty())&&li.getLineWidth()==null) li.setLineWidth(LineWidth.W050); }
    if(on==null&&li.getLineStyle()==LineStyle.None) return;
    if(lw!=null&&!lw.isEmpty()){ try{ li.setLineWidth(LineWidth.valueOf(lw.trim())); }catch(Exception e){ throw new RuntimeException("linewidth 는 W025|W050|W075|W100|W150|W200|W300"); } }
    if(lc!=null&&!lc.isEmpty()) li.setLineColor(CrfMcpServer.parseColor(lc));
  }
  /** crf_set_cell / crf_set_cell_style 용 셀 테두리 인자(border/linewidth/linecolor) 처리 */
  static String cellBorderArgs(Object cell,JSONObject args){
    String b=s(args,"border"), lw=s(args,"linewidth"), lc=s(args,"linecolor"); if((b==null||b.isEmpty())&&(lw==null||lw.isEmpty())&&(lc==null||lc.isEmpty())) return "";
    if(!(cell instanceof TableCellNormal)) throw new RuntimeException("테두리는 일반 셀에만"); cellBorderSpec((TableCellNormal)cell,b,lw,lc);
    return (b!=null&&!b.isEmpty()?" 테두리="+b:"")+(lw!=null&&!lw.isEmpty()?" 선굵기="+lw:"")+(lc!=null&&!lc.isEmpty()?" 선색="+lc:"");
  }

  /** crf_table_info: 열 너비·행 높이·병합 span·내용 격자 */
  static String tableInfo(JSONObject args) throws Exception {
    Ctx c=ctx(args); ControlTable t=c.t; boolean detail="true".equalsIgnoreCase(s(args,"detail")); int rows=t.getRowCount(), cols=t.getColumnCount();
    StringBuilder b=new StringBuilder(); b.append("표 '"+c.name+"' ("+band(c)+") 위치 "+t.getX1()+","+t.getY1()+" 크기 "+t.getWidth()+"×"+t.getHeight()+", "+rows+"행×"+cols+"열, 외곽선 "+t.getLineStyle()+", 자동병합 "+t.getTableAutoMerge()+", 페이지나눔방지 "+t.getTableKeepTogether()+"\n");
    b.append("열 너비:"); int tw=0; for(int k=0;k<cols;k++){ int w=t.getTableColumn(k).getWidth(); tw+=w; b.append(" ["+k+"]"+w); } b.append("  (합 "+tw+")\n");
    b.append("행 높이:"); int th=0; for(int r=0;r<rows;r++){ int h=t.getTableRow(r).getHeight(); th+=h; b.append(" ["+r+"]"+h); } b.append("  (합 "+th+")\n");
    int merged=0; Map<TableCell,int[]> pos=positions(t);
    for(int r=0;r<rows;r++){ b.append("["+r+"] "); for(int k=0;k<cols;k++){ TableCell cell=cellAt(t,r,k); String cb;
        if(cell instanceof TableCellDumy){ TableCellNormal bs=((TableCellDumy)cell).getBaseCell(); int[] p=bs==null?null:pos.get(bs); cb="‹병합←"+(p==null?"?":p[0]+","+p[1])+"›"; merged++; }
        else { TableCellNormal n=(TableCellNormal)cell; Object cf=n.getApplyValueField(); String ct=n.getApplyValueText();
          cb="Checkbox".equals(String.valueOf(n.getCellContent()))?CrfMcpServer.checkboxText(n): cf!=null?CrfMcpServer.fieldKindKo(cf)+":"+nameOf(cf):(ct!=null&&!ct.isEmpty()?"\""+CrfMcpServer.oneLine(ct,24)+"\"":"·");
          if(n.getRowSpan()>1||n.getColSpan()>1) cb+="("+n.getRowSpan()+"×"+n.getColSpan()+")"; String fmt=n.getOutputFormat(); if(fmt!=null&&!fmt.isEmpty()) cb+="{"+fmt+"}";
          if(detail){ String st=CrfMcpServer.styleInfo(n).trim(); String bd=borderText(n); cb+=" «"+(st.isEmpty()?"":st.replace(" ",", ")+", ")+"테두리 "+bd+"»"; } }
        b.append(cb).append(k<cols-1?" | ":""); } b.append("\n"); }
    List<String> bad=checkGrid(t); b.append(merged>0?"병합 자리 "+merged+"개":"병합 없음").append(bad.isEmpty()?", 격자 정상":", ⚠ 격자 이상: "+bad.get(0)+(bad.size()>1?" 외 "+(bad.size()-1):"")).append("\n");
    b.append("편집: 행=crf_table_rows(action=insert|delete|copy|move|resize|equalize), 열=crf_table_cols, 셀 값·스타일=crf_set_cell, 병합=crf_merge_cells, 표 위치/크기/테두리=crf_set_table");
    return b.toString();
  }
  static String borderText(TableCellNormal n){ StringBuilder x=new StringBuilder(); if(n.getVisibleLeftLine()&&n.getLineInfoLeft().getLineStyle()!=LineStyle.None) x.append("좌"); if(n.getVisibleRightLine()&&n.getLineInfoRight().getLineStyle()!=LineStyle.None) x.append("우"); if(n.getVisibleTopLine()&&n.getLineInfoTop().getLineStyle()!=LineStyle.None) x.append("상"); if(n.getVisibleBottomLine()&&n.getLineInfoBottom().getLineStyle()!=LineStyle.None) x.append("하"); return x.length()==0?"없음":x.length()==4?"전체":x.toString(); }
}
