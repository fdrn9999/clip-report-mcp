import com.clipsoft.clipreport.base.RexObjectList;
import com.clipsoft.clipreport.base.controls.Control;
import com.clipsoft.clipreport.base.controls.ControlCrosstab;
import com.clipsoft.clipreport.base.controls.crosstabs.*;
import com.clipsoft.clipreport.base.datas.fields.*;
import com.clipsoft.clipreport.base.enums.*;
import com.clipsoft.clipreport.base.functions.LineInfo;
import com.clipsoft.clipreport.base.functions.TextInfo;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.groups.Group;
import com.clipsoft.clipreport.base.groups.GroupCrosstab;
import com.clipsoft.clipreport.base.reports.ReportObjectManager;
import com.clipsoft.clipreport.base.sections.Section;
import com.clipsoft.clipreport.base.sections.SubSectionDefault;
import com.clipsoft.clipreport.common.enums.HorizontalAlignmentMethod;
import com.clipsoft.clipreport.common.enums.LineStyle;
import com.clipsoft.clipreport.common.enums.VerticalAlignmentMethod;
import org.json.simple.JSONObject;
import java.util.*;

/** 크로스탭(교차표) 읽기·생성 + 누적합산 필드 생성. */
public class CrfCrosstab {
  static String s(JSONObject a,String k){ return CrfMcpServer.s(a,k); }
  static String nm(Object f){ return f==null?"-":CrfMcpServer.nameOf(f); }

  // ───────────── 읽기: crf_describe_layout 에 붙는 크로스탭 구조 ─────────────
  static String describe(ControlCrosstab t,boolean detail){
    StringBuilder b=new StringBuilder(); String in="          ";
    try{
      CrosstabObjectManager om=t.getObjectManager();
      b.append(in).append("행 그룹: ").append(groups(om.getGroupRowList())).append('\n');
      b.append(in).append("열 그룹: ").append(groups(om.getGroupColumnList())).append('\n');
      b.append(in).append("고정너비=").append(t.getFixWidth()).append(" 행라벨반복=").append(t.getRepeatRowLabel()).append(" 열라벨반복=").append(t.getRepeatColumnLabel())
       .append(t.getRowTotalOnTop()?" 행합계=위":"").append(t.getColumnTotalOnLeft()?" 열합계=왼쪽":"").append('\n');
      matrix(b,in,"머리(Header)",t.getMatrixHeader(),detail); matrix(b,in,"행 라벨",t.getMatrixRowLabel(),detail);
      matrix(b,in,"열 라벨",t.getMatrixColumnLabel(),detail); matrix(b,in,"요약(값)",t.getMatrixSummarized(),detail);
    }catch(Throwable e){ b.append(in).append("(크로스탭 구조 읽기 실패: ").append(e).append(")\n"); }
    return b.toString();
  }
  static String groups(RexObjectList<Group> gl){ if(gl==null||gl.size()==0) return "(없음)"; StringBuilder b=new StringBuilder();
    for(int i=0;i<gl.size();i++){ Group g=gl.get(i); b.append(i>0?" > ":"").append(nm(g.getGroupingField())); Field n=g.getGroupNameField(); if(n!=null&&n!=g.getGroupingField()) b.append("(표시 ").append(nm(n)).append(")");
      b.append(" 정렬=").append(g.getSortMethod()).append(g.getTotalVisible()?" 합계표시":" 합계숨김"); }
    return b.toString(); }
  static void matrix(StringBuilder b,String in,String title,CrosstabMatrix m,boolean detail){
    int rc=m.getRowCount(), cc=m.getColumnCount(); b.append(in).append(title).append(" ").append(rc).append("×").append(cc);
    if(rc>0&&cc>0){ StringBuilder w=new StringBuilder(), h=new StringBuilder(); for(int c=0;c<cc;c++) w.append(c>0?",":"").append(m.getColumn(c).getWidth()); for(int r=0;r<rc;r++) h.append(r>0?",":"").append(m.getRow(r).getHeight()); b.append(" 열너비[").append(w).append("] 행높이[").append(h).append("]"); }
    b.append('\n');
    for(int r=0;r<rc;r++){ b.append(in).append("  ");
      for(int c=0;c<cc;c++){ CrosstabCell x=m.getCell(r,c); b.append(c>0?" | ":"");
        if(!(x instanceof CrosstabCellNormal)){ b.append("‹병합›"); continue; }
        CrosstabCellNormal n=(CrosstabCellNormal)x; String v; Field f=n.getApplyValueField();
        if(n.getApplyValueType()==ApplyValueType.Field) v=f==null?"«그룹 이름»":CrfMcpServer.fieldKindKo(f)+":"+nm(f); else { String tx=n.getApplyValueText(); v=tx==null||tx.isEmpty()?"·":"\""+tx+"\""; }
        b.append(n.getCellType()).append(' ').append(v);
        if(n.getCellType()==CrosstabCellType.Summarized && n.getSummary()!=null) b.append(" ").append(n.getSummary().getFunction());
        String fmt=n.getOutputFormat(); if(fmt!=null&&!fmt.isEmpty()) b.append("{").append(fmt).append("}");
        if(n.getRowSpan()>1||n.getColSpan()>1) b.append("(").append(n.getRowSpan()).append("×").append(n.getColSpan()).append(")");
        if(detail){ TextInfo ti=n.getTextInfo(); if(ti!=null) b.append(" ‹").append(ti.getFontName()).append(' ').append(ti.getFontSize()).append(ti.getFontBold()?" 굵게":"").append(' ').append(ti.getHorizontalAlignment()).append("›"); }
      } b.append('\n'); }
  }

  // ───────────── 생성: crf_add_crosstab ─────────────
  static void lines(CrosstabCellNormal c){
    for(LineInfo l: new LineInfo[]{c.getLineInfoLeft(),c.getLineInfoRight(),c.getLineInfoTop(),c.getLineInfoBottom()}){ l.setLineStyle(LineStyle.Solid); l.setLineColor(0); }
    // 새 셀의 대각선 기본값은 Solid — None 으로 안 바꾸면 칸마다 X 가 그려진다(표 셀과 같음)
    c.getLineInfoFDiagona().setLineStyle(LineStyle.None); c.getLineInfoBDiagona().setLineStyle(LineStyle.None);
  }
  static CrosstabCellNormal cell(CrosstabCellType type,String name,String font,int size,boolean bold,HorizontalAlignmentMethod align,boolean shade){
    CrosstabCellNormal c=new CrosstabCellNormal(); c.setName(name); c.setCellType(type); c.setLabelVisible(true); c.setRowSpan(1); c.setColSpan(1);
    c.setCellContent(com.clipsoft.clipreport.common.enums.CellContentType.Text); c.setApplyValueType(ApplyValueType.Text); c.setApplyValueText(""); c.setOutputFormat(""); c.setExtendFormatOption(""); c.setCanGrow(true); c.setCanShrink(false);
    TextInfo t=c.getTextInfo(); t.setFontName(font); t.setFontSize((short)size); t.setFontBold(bold); t.setHorizontalAlignment(align); t.setVerticalAlignment(VerticalAlignmentMethod.Center); t.setWordWrap(true);
    t.setLeftMargin(20); t.setRightMargin(20);
    if(shade){ c.setBackStyle(com.clipsoft.clipreport.common.enums.BackStyleType.Normal); c.setBackColor(0xEEEEEE); }
    lines(c); return c;
  }
  static void build(CrosstabMatrix m,int rows,int cols,int width,int height,CrosstabCellType type,String pre,String font,int size,boolean bold,HorizontalAlignmentMethod align,boolean shade){
    for(int c=0;c<cols;c++){ CrosstabColumn col=new CrosstabColumn(); col.setWidth(width); m.getColumnList().add(col); }
    for(int r=0;r<rows;r++){ CrosstabRow row=new CrosstabRow(); row.setHeight(height); m.getRowList().add(row);
      for(int c=0;c<cols;c++) row.getCellList().add(cell(type,pre+"_"+r+"_"+c,font,size,bold,align,shade)); }
  }
  /** 데이터 필드가 속한 데이터셋 이름(데이터 필드가 아니면 null) */
  @SuppressWarnings("unchecked")
  static String datasetOf(TheReportFile rf,Field f){ if(!(f instanceof FieldData)) return null;
    RexObjectList<com.clipsoft.clipreport.base.datas.DataSet> dss=(RexObjectList<com.clipsoft.clipreport.base.datas.DataSet>)rf.getGlobe().getGlobalObjectManager().getDataSetList();
    for(int i=0;i<dss.size();i++){ RexObjectList<?> fl=(RexObjectList<?>)dss.get(i).getFieldDataList(); for(int j=0;j<fl.size();j++) if(fl.get(j)==f) return dss.get(i).getName(); }
    return null; }
  static boolean yes(String v,boolean d){ return v==null||v.trim().isEmpty()?d:v.trim().equalsIgnoreCase("true"); }

  @SuppressWarnings("unchecked")
  static String add(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output");
    TheReportFile rf=CrfMcpServer.open(path);
    String[] need={"row","column","value"}; Field[] fs=new Field[3];
    for(int i=0;i<3;i++){ String n=s(args,need[i]); if(n==null||n.trim().isEmpty()) return "ERROR: "+need[i]+" 인자가 필요합니다 (row=행 그룹 필드, column=열 그룹 필드, value=집계할 필드)";
      fs[i]=CrfMcpServer.findField(rf,n.trim()); if(fs[i]==null) return "ERROR: 필드 '"+n+"' 없음 (crf_summary 로 필드 이름 확인)"; }
    for(int i=0;i<3;i++) if(!(fs[i] instanceof FieldData)&&!(fs[i] instanceof FieldFormula)) return "ERROR: "+need[i]+" 의 '"+nm(fs[i])+"' 는 "+CrfMcpServer.fieldKindKo(fs[i])+" 필드입니다 — 크로스탭에는 데이터 필드(또는 공식 필드)만 씁니다";
    { String d0=null; for(int i=0;i<3;i++){ String d=datasetOf(rf,fs[i]); if(d==null) continue; if(d0==null) d0=d; else if(!d0.equals(d)) return "ERROR: row/column/value 가 서로 다른 데이터셋의 필드입니다("+d0+" / "+d+") — 크로스탭은 한 데이터셋의 필드로 만듭니다(같은 이름이 여러 데이터셋에 있으면 먼저 나온 데이터셋의 필드가 잡힘)"; } }
    Field rowName=fs[0], colName=fs[1];
    if(s(args,"row_name")!=null&&!s(args,"row_name").trim().isEmpty()){ rowName=CrfMcpServer.findField(rf,s(args,"row_name").trim()); if(rowName==null) return "ERROR: row_name 필드 '"+s(args,"row_name")+"' 없음"; }
    if(s(args,"column_name")!=null&&!s(args,"column_name").trim().isEmpty()){ colName=CrfMcpServer.findField(rf,s(args,"column_name").trim()); if(colName==null) return "ERROR: column_name 필드 '"+s(args,"column_name")+"' 없음"; }
    SummaryFunctionType fn; String fnS=s(args,"function"); fnS=fnS==null||fnS.trim().isEmpty()?"Sum":fnS.trim();
    try{ fn=null; for(SummaryFunctionType x: (SummaryFunctionType[])SummaryFunctionType.class.getMethod("values").invoke(null)) if(x.toString().equalsIgnoreCase(fnS)) fn=x; if(fn==null) throw new Exception(); }
    catch(Exception e){ return "ERROR: function 은 Sum|Count|Avg|Min|Max 중 하나 (받은 값 '"+fnS+"')"; }
    SortMethod sort; String so=s(args,"sort"); so=so==null||so.trim().isEmpty()?"Ascending":so.trim();
    try{ sort=null; for(SortMethod x: (SortMethod[])SortMethod.class.getMethod("values").invoke(null)) if(x.toString().equalsIgnoreCase(so)) sort=x; if(sort==null) throw new Exception(); }
    catch(Exception e){ return "ERROR: sort 는 Ascending|Descending|Order(데이터에 나온 순서) 중 하나"; }
    String secKey=s(args,"section"); if(secKey==null||secKey.trim().isEmpty()) secKey="DataHeader";
    Section sec=CrfMcpServer.findOrCreateSection(rf,secKey); if(sec==null) return "ERROR: section '"+secKey+"' not found";
    SubSectionDefault sd=CrfMcpServer.firstSub(sec); if(sd==null) return "ERROR: section has no subsection";
    int left=CrfMcpServer.pInt(args.get("left"),0), top=CrfMcpServer.pInt(args.get("top"),0), rowW=CrfMcpServer.pInt(args.get("row_width"),400), colW=CrfMcpServer.pInt(args.get("col_width"),250), rowH=CrfMcpServer.pInt(args.get("row_height"),60), headH=CrfMcpServer.pInt(args.get("header_height"),80);
    String tot=s(args,"total"); tot=tot==null?"true":tot.trim().toLowerCase(); boolean totRow=tot.equals("true")||tot.equals("row")||tot.isEmpty(), totCol=tot.equals("true")||tot.equals("column")||tot.isEmpty();
    String fmt=s(args,"format"); if(fmt==null) fmt=fn.toString().equals("Avg")?"#,##0.0":"#,##0";
    String rowTotL=s(args,"row_total_label"), colTotL=s(args,"column_total_label"), head=s(args,"header");
    String[] lf=CrfMcpServer.defaultFont(rf,path,false), df=CrfMcpServer.defaultFont(rf,path,true);
    int size=10; try{ if(s(args,"fontsize")!=null) size=Integer.parseInt(s(args,"fontsize").trim()); else if(lf[1]!=null) size=Integer.parseInt(lf[1].trim()); }catch(Exception e){}

    ControlCrosstab t=new ControlCrosstab(); String nm=s(args,"name"); t.setName(CrfMcpServer.uniqueControlName(rf,nm==null||nm.trim().isEmpty()?"크로스탭1":nm.trim())); t.setVisible(true);
    t.setX1(left); t.setY1(top); t.setWidth(rowW+colW*(totCol?2:1)); t.setHeight(headH+rowH*(totRow?2:1)); t.setRepeatRowLabel(true); t.setRepeatColumnLabel(true); t.setColumnKeepTogether(true);
    t.getLineInfo().setLineStyle(LineStyle.Solid);
    CrosstabObjectManager om=t.getObjectManager();
    for(int axis=0;axis<2;axis++){ boolean r=axis==0;
      GroupCrosstab g=new GroupCrosstab(r?ObjectID.OID_GroupCrosstabRow:ObjectID.OID_GroupCrosstabColumn);
      g.setGroupingField(fs[axis]); g.setGroupNameField(r?rowName:colName); g.setSortMethod(sort); g.setTotalVisible(r?totRow:totCol); g.setLabelVisible(true);
      (r?om.getGroupRowList():om.getGroupColumnList()).add(g);
      FieldGroupName gn=new FieldGroupName(r?ObjectID.OID_FieldCrosstabRowGroupName:ObjectID.OID_FieldCrosstabColumnGroupName); gn.setName(r?"ROW_NAME":"COLUMN_NAME"); gn.setLinkedGroup(g);
      (r?om.getFieldGroupNameRowList():om.getFieldGroupNameColumnList()).add(gn);
      FieldGroupIndex gi=new FieldGroupIndex(r?ObjectID.OID_FieldCrosstabRowGroupIndex:ObjectID.OID_FieldCrosstabColumnGroupIndex); gi.setName(r?"ROW_INDEX":"COLUMN_INDEX"); gi.setLinkedGroup(g);
      (r?om.getFieldGroupIndexRowList():om.getFieldGroupIndexColumnList()).add(gi); }
    HorizontalAlignmentMethod mid=HorizontalAlignmentMethod.Middle, right=HorizontalAlignmentMethod.Right;
    // 네 행렬: 머리 1×1, 행 라벨 2×1(그룹 이름 + 합계), 열 라벨 1×2(그룹 이름 + 합계), 값 2×2 — 엔진이 그룹 수만큼 행·열을 펼친다
    CrosstabMatrix h=t.getMatrixHeader(); build(h,1,1,rowW,headH,CrosstabCellType.Header,"H",lf[0],size,true,mid,true); h.getCellNormal(0,0).setApplyValueText(head==null?"":head);
    CrosstabMatrix rl=t.getMatrixRowLabel(); build(rl,2,1,rowW,rowH,CrosstabCellType.RowLabel,"R",lf[0],size,false,mid,false);
    rl.getCellNormal(0,0).setApplyValueType(ApplyValueType.Field);   // 필드 없이 Field 타입 = 그 축의 그룹 이름
    rl.getCellNormal(1,0).setCellType(CrosstabCellType.RowTotal); rl.getCellNormal(1,0).setApplyValueText(rowTotL==null?"합계":rowTotL); rl.getCellNormal(1,0).getTextInfo().setFontBold(true);
    CrosstabMatrix cl=t.getMatrixColumnLabel(); build(cl,1,2,colW,headH,CrosstabCellType.ColumnLabel,"C",lf[0],size,true,mid,true);
    cl.getCellNormal(0,0).setApplyValueType(ApplyValueType.Field);
    cl.getCellNormal(0,1).setCellType(CrosstabCellType.ColumnTotal); cl.getCellNormal(0,1).setApplyValueText(colTotL==null?"합계":colTotL);
    CrosstabMatrix sm=t.getMatrixSummarized(); build(sm,2,2,colW,rowH,CrosstabCellType.Summarized,"S",df[0],size,false,right,false);
    for(int r=0;r<2;r++) for(int c=0;c<2;c++){ CrosstabCellNormal v=sm.getCellNormal(r,c); v.setApplyValueType(ApplyValueType.Field); v.setApplyValueField(fs[2]); v.getSummary().setFunction(fn); v.setOutputFormat(fmt); if(r==1||c==1) v.getTextInfo().setFontBold(true); }
    for(CrosstabMatrix m: new CrosstabMatrix[]{h,rl,cl,sm}) m.addCellRowToColumn();

    ((RexObjectList<Control>)CrfMcpServer.clpOf(sd).getControlList()).add(t);
    String grew=""; int needH=top+t.getHeight(); if(sd.getHeight()<needH){ grew=", 밴드 높이 "+sd.getHeight()+"→"+needH; sd.setHeight(needH); }
    String wrote=CrfMcpServer.save(rf,output,path);
    String secName=sec.getClass().getSimpleName().replace("Section","");
    return "OK: 크로스탭 \""+t.getName()+"\" 추가 — 행="+nm(fs[0])+(rowName!=fs[0]?"(표시 "+nm(rowName)+")":"")+", 열="+nm(fs[1])+(colName!=fs[1]?"(표시 "+nm(colName)+")":"")+", 값="+fn+"("+nm(fs[2])+"){"+fmt+"}, 정렬="+sort
      +", 합계 행="+totRow+" 열="+totCol+", 밴드="+secName+grew+", 글꼴="+lf[0]+" "+size+", wrote "+wrote
      +"\n→ 열 수는 데이터의 열 그룹 값 개수만큼 늘어난다(열 하나 너비 "+colW+", 행 라벨 "+rowW+"; 용지 본문 너비를 넘으면 다음 쪽으로 넘어감). 같은 행·열 조합의 레코드는 "+fn+" 으로 합쳐진다."
      +(secName.equals("DataHeader")?"":"\n⚠ 크로스탭은 보통 데이터 머리글에 둔다(본문에 두면 레코드마다 반복 출력).")
      +"\n→ crf_render 로 실제 출력을 확인하세요(가상 데이터는 data 로 행·열 값이 겹치게 주면 집계를 볼 수 있음).";
  }

  // ───────────── 누적합산 필드: crf_add_running_total ─────────────
  @SuppressWarnings("unchecked")
  static String addRunningTotal(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), name=s(args,"name"), field=s(args,"field");
    if(name==null||name.trim().isEmpty()||field==null||field.trim().isEmpty()) return "ERROR: name(새 누적합산 필드 이름)과 field(집계할 필드)가 필요합니다";
    name=name.trim(); TheReportFile rf=CrfMcpServer.open(path);
    if(CrfMcpServer.findField(rf,name)!=null) return "ERROR: 이름 '"+name+"' 인 필드가 이미 있습니다";
    Field f=CrfMcpServer.findField(rf,field.trim()); if(f==null) return "ERROR: 필드 '"+field+"' 없음";
    if(!(f instanceof FieldData)&&!(f instanceof FieldFormula)) return "ERROR: '"+nm(f)+"' 는 "+CrfMcpServer.fieldKindKo(f)+" 필드입니다 — 누적합산은 데이터 필드(또는 공식 필드)에만 겁니다";
    String fnS=s(args,"function"); fnS=fnS==null||fnS.trim().isEmpty()?"Sum":fnS.trim(); SummaryFunctionType fn=null;
    for(SummaryFunctionType x: (SummaryFunctionType[])SummaryFunctionType.class.getMethod("values").invoke(null)) if(x.toString().equalsIgnoreCase(fnS)) fn=x;
    if(fn==null) return "ERROR: function 은 Sum|Count|Avg|Min|Max 중 하나";
    ReportObjectManager rom=rf.getGlobe().getMainReport().getReportObjectManager(); RexObjectList<Group> gl=(RexObjectList<Group>)rom.getGroupList();
    String rg=s(args,"reset_group"); Group grp=null;
    if(rg!=null&&!rg.trim().isEmpty()){ rg=rg.trim();
      for(int i=0;i<gl.size();i++){ Field gf=gl.get(i).getGroupingField(); if((gf!=null&&rg.equalsIgnoreCase(gf.getName()))||rg.equals(String.valueOf(i+1))) grp=gl.get(i); }
      if(grp==null){ StringBuilder have=new StringBuilder(); for(int i=0;i<gl.size();i++) have.append(i>0?", ":"").append(i+1).append("=").append(nm(gl.get(i).getGroupingField())); return "ERROR: reset_group '"+rg+"' 에 해당하는 그룹이 없습니다 (그룹: "+(have.length()==0?"없음 — crf_add_group 먼저":have)+")"; } }
    FieldRunningTotal v=new FieldRunningTotal(); v.setName(name); v.setSummaryField(f); v.setSummaryFunctionType(fn);
    v.setRunningTotalEvaluateType(RunningTotalEvaluateType.ForEachRecord);
    if(grp==null) v.setRunningTotalResetType(RunningTotalResetType.ResetNever); else { v.setRunningTotalResetType(RunningTotalResetType.ResetOnChangeOfGroup); v.setRunningTotalResetOnChangeGroup(grp); }
    ((RexObjectList<FieldRunningTotal>)rom.getFieldRunningTotalList()).add(v);
    String wrote=CrfMcpServer.save(rf,output,path);
    return "OK: 누적합산 필드 '"+name+"' = "+fn+"("+nm(f)+"), 레코드마다 누적, "+(grp==null?"초기화 없음(전체 누계/총계)":"그룹 '"+nm(grp.getGroupingField())+"' 이 바뀔 때 초기화(그룹 소계)")+", wrote "+wrote
      +"\n→ 셀/글상자에 crf_set_cell field="+name+" (또는 crf_set_label field=) 로 바인딩. 그룹 바닥글에 두면 그 그룹의 소계, 본문에 두면 줄마다 누계가 찍힌다."
      +"\n   SQL 에서 이미 계산해 온 소계 필드를 다시 누적하지 말 것(이중 합산). 비율·서로 다른 단위는 합산 대상이 아니다.";
  }
}
