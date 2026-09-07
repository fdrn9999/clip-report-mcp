import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.RexObjectList;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.reports.Report;
import com.clipsoft.clipreport.base.page.MainPage;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.datas.*;
import com.clipsoft.clipreport.base.datas.fields.*;
import com.clipsoft.clipreport.base.controls.*;
import com.clipsoft.clipreport.base.controls.Tables.*;
import com.clipsoft.clipreport.base.functions.TextInfo;
import com.clipsoft.clipreport.base.functions.LineInfo;
import com.clipsoft.clipreport.base.enums.*;
import com.clipsoft.clipreport.common.enums.*;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;

/** 문서형 리포트 SDK 생성 예제(인하공전 ssrmet0220 회피 자진 신고서). usage: java -cp "<CLIP jar>*;clip-report-mcp.jar;." SampleDocumentReport <template.crf> <out.crf>
 *  좌표 단위 0.1mm, 색 BGR(rgb() 헬퍼), 새 셀 대각선 None 필수, 체크박스는 checkbox() 헬퍼(조건 기반). 좌표 단위 0.1mm (PDF 7p 양식 이미지 6.61px/mm 기준 환산). */
public class SampleDocumentReport {
  static PrintStream ps;
  static final String FONT = "바탕체";
  static final int BLACK = 0x000000, GRAY_BG = rgb(0xD9D9D9), BLUE = rgb(0x2E75B6), YELLOW = rgb(0xFFC000), GREEN = rgb(0x70AD47), RED = rgb(0xFF0000);
  static int rgb(int rrggbb){ int r=(rrggbb>>16)&255, g=(rrggbb>>8)&255, b=rrggbb&255; return (b<<16)|(g<<8)|r; }
  static final int W = 1500;
  static float LS = 5.5f;

  static TheReportFile rf; static GlobalObjectManager gom; static Report rep; static RexObjectList<Control> cl; static int seq=0;
  static Map<String,Field> fields=new HashMap<>();

  static String SQL =
    "SELECT A.SYY\n" +
    "     , A.SMT_CD\n" +
    "     , A.EMPNO\n" +
    "     , C.EMP_NM\n" +
    "     , COM.FN_CSYS_DEPT_NM(A.DEPT_CD) AS DEPT_NM\n" +
    "     , COM.FN_CSYS_CODE_NM('AHRM0070', A.JPOS_CD) AS JPOS_NM\n" +
    "     , COM.FN_CSYS_CODE_NM('JGRD_CD', A.JGRD_CD) AS JGRD_NM\n" +
    "     , COM.FN_TELNO_FORMAT(A.MBPNO) AS MBPNO\n" +
    "     , A.STDNT_NM\n" +
    "     , A.ORGIN_HSCH_NM\n" +
    "     , A.ENTEX_EXMT_NO\n" +
    "     , A.APLNT_RELTN_NM\n" +
    "     , A.DEPRT_NM\n" +
    "     , A.ENTNS_RCRIT_DIV_CD\n" +
    "     , A.ENTNS_SCRNN_CD\n" +
    "     , A.PRCTN_INTRV_DIV_CD\n" +
    "     , A.INDIN_PROVD_AGREE_YN\n" +
    "     , A.AVOD_STTMN_DIV_CD\n" +
    "     , TO_CHAR(A.STTMN_DT, 'YYYY') AS STTMN_YY\n" +
    "     , TO_CHAR(A.STTMN_DT, 'MM') AS STTMN_MM\n" +
    "     , TO_CHAR(A.STTMN_DT, 'DD') AS STTMN_DD\n" +
    "  FROM SCH.SSRM010 A\n" +
    "     , ADM.AHRM100 B\n" +
    "     , ADM.AHRM150 C\n" +
    " WHERE A.EMPNO = B.EMPNO\n" +
    "   AND B.HR_PERSL_NO = C.HR_PERSL_NO\n" +
    "   AND A.SYY = '{parameter.SYY}'\n" +
    "   AND A.SMT_CD = '{parameter.SMTCD}'\n" +
    "   AND A.EMPNO = '{parameter.EMPNO}'";
  static String[] COLS = {"SYY","SMT_CD","EMPNO","EMP_NM","DEPT_NM","JPOS_NM","JGRD_NM","MBPNO","STDNT_NM","ORGIN_HSCH_NM","ENTEX_EXMT_NO","APLNT_RELTN_NM","DEPRT_NM","ENTNS_RCRIT_DIV_CD","ENTNS_SCRNN_CD","PRCTN_INTRV_DIV_CD","INDIN_PROVD_AGREE_YN","AVOD_STTMN_DIV_CD","STTMN_YY","STTMN_MM","STTMN_DD"};

  public static void main(String[] a) throws Exception {
    ps=new PrintStream(System.out,true,"UTF-8");
    build(a[0], a[1]);
  }

  @SuppressWarnings("unchecked")
  static void build(String template, String out) throws Exception {
    rf=Rexpert4.read(template); gom=rf.getGlobe().getGlobalObjectManager(); rep=rf.getGlobe().getMainReport(); fields.clear(); seq=0;
    DataSet ds=gom.getDataSetList().get(0);
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList(); fl.removeAll();
    for(int i=0;i<COLS.length;i++){ FieldData f=new FieldData(); f.setName(COLS[i]); f.setDataType(DataType.String); f.setIndex(i); fl.add(f); fields.put(COLS[i],f); }
    DataAccessMethodSQL sql=ds.getDataSetItemNormal().getDataAccessMethodSQL(); sql.setScriptType(ScriptType.NotScript); sql.setQueryString(SQL);
    RexObjectList<FieldGlobalParameter> gp=(RexObjectList<FieldGlobalParameter>)(RexObjectList<?>) gom.getFieldGlobalParameterList();
    for(int i=gp.size()-1;i>=0;i--) if(!"G_REPORTLOG".equals(gp.get(i).getName())) gp.remove(i);
    for(String p: new String[]{"SYY","SMTCD","EMPNO"}){ FieldGlobalParameter fp=new FieldGlobalParameter(); fp.setName(p); fp.setDataType(DataType.String); fp.setDefaultValue(""); fp.setValueIsNull(Boolean.FALSE); fp.setPrompt(p); fp.setTag(""); gp.add(fp); }
    MainPage mp=rep.getReportDesign().getMainPage();
    mp.setPaperType(PaperType.A4); mp.setPaperWidth(2100); mp.setPaperHeight(2970); mp.setPaperOrientationType(PaperOrientation.Potrait);
    mp.setLeftMargin(300); mp.setTopMargin(250); mp.setRightMargin(300); mp.setBottomMargin(150);
    RexObjectList<Section> secs=mp.getSectionList(); secs.removeAll();
    SectionDetail det=new SectionDetail(); SubSectionDefault sd=new SubSectionDefault(); sd.setName("본문1"); sd.setVisible(true);
    ControlListForEachSeparatedPage clp=new ControlListForEachSeparatedPage(); sd.getControlListForEachSeparatedPageList().add(clp);
    cl=(RexObjectList<Control>)(RexObjectList<?>) clp.getControlList();
    det.getSubSectionList().add(sd); secs.add(det);
    sd.setHeight(buildForm());
    Rexpert4.write(rf,out);
    ps.println("written "+out+" ("+Files.size(Paths.get(out))+" bytes)");
  }

  static int buildForm(){
    Field BANNER=formula("BANNER", "return rexpert.field(\"data.SYY\") + \"학년도 입학전형\";");
    Field JPOSJGRD=formula("JPOS_JGRD", "var jp = rexpert.field(\"data.JPOS_NM\"); var jg = rexpert.field(\"data.JGRD_NM\"); jp = (jp == null ? \"\" : \"\" + jp); jg = (jg == null ? \"\" : \"\" + jg); return jp != \"\" ? jp + \"/\" + jg : jg;");
    Field RCRIT=formula("RCRIT_CHK", "var c = \"\" + rexpert.field(\"data.ENTNS_RCRIT_DIV_CD\"); return (c == \"1\" ? \"■\" : \"□\") + \" 수시 1차      \" + (c == \"2\" ? \"■\" : \"□\") + \" 수시 2차      \" + (c == \"3\" ? \"■\" : \"□\") + \" 정시\\n\" + (c == \"4\" ? \"■\" : \"□\") + \" 편입학        \" + (c == \"5\" ? \"■\" : \"□\") + \" 학사학위 전공심화과정\";");
    Field SCRNN=formula("SCRNN_CHK", "var s = \"\" + rexpert.field(\"data.ENTNS_SCRNN_CD\"); var p = \"\" + rexpert.field(\"data.PRCTN_INTRV_DIV_CD\"); var i1 = (s == \"11\" && p == \"1\") ? \"■\" : \"□\"; var i2 = (s == \"11\" && p == \"2\") ? \"■\" : \"□\"; var o1 = (s == \"21\" && p == \"1\") ? \"■\" : \"□\"; var o2 = (s == \"21\" && p == \"2\") ? \"■\" : \"□\"; return (s == \"01\" ? \"■\" : \"□\") + \" 일반전형      \" + (s == \"11\" ? \"■\" : \"□\") + \" 정원내 특별전형 (\" + i1 + \" 실기  \" + i2 + \" 면접)\\n\" + (s == \"21\" ? \"■\" : \"□\") + \" 정원외 특별전형 (\" + o1 + \" 실기  \" + o2 + \" 면접)\";");
    Field DATE=formula("STTMN_DATE", "var y = rexpert.field(\"data.STTMN_YY\"); if (y == null || (\"\" + y) == \"\") return \"년        월        일\"; return y + \"년    \" + parseInt(\"\" + rexpert.field(\"data.STTMN_MM\"), 10) + \"월    \" + parseInt(\"\" + rexpert.field(\"data.STTMN_DD\"), 10) + \"일\";");
    Field SIGN=formula("SIGN", "return \"신 고 인:      \" + rexpert.field(\"data.EMP_NM\") + \"      (서명)\";");

    // 상단 배너: 학년도 입학전형 (위: 파랑/노랑, 아래: 초록/빨강 굵은 선)
    ControlTable bn=table("표_배너",0,0,new int[]{340,220},new int[]{90});
    for(int c=0;c<2;c++){ TableCellNormal n=cell(bn,0,c,"",null,null,BLACK,HorizontalAlignmentMethod.Left,-1,false,false,true,true);
      lineColor(n.getLineInfoTop(), c==0?BLUE:YELLOW, LineWidth.W300); lineColor(n.getLineInfoBottom(), c==0?GREEN:RED, LineWidth.W300); }
    label(15,0,540,90,null,BANNER,15,true,false,BLACK,HorizontalAlignmentMethod.Left,false);

    // 외곽 상자
    int by=120;
    ControlLabel box=label(0,by,W,2015,"",null,10,false,false,BLACK,HorizontalAlignmentMethod.Left,false);
    box.setShapeType(ShapeType.Rectangle); box.setLineStyle(LineStyle.Solid); box.setLineWidth(LineWidth.W075); box.setLineColor(BLACK);
    if(box.getLineInfo()!=null){ box.getLineInfo().setLineStyle(LineStyle.Solid); box.getLineInfo().setLineWidth(LineWidth.W075); box.getLineInfo().setLineColor(BLACK); }

    label(0,by+40,W,90,"대학입학전형 회피 자진 신고서",null,16,true,false,BLACK,HorizontalAlignmentMethod.Middle,false);

    // 신고자 인적사항
    label(20,by+185,W-40,60,"□ 신고자 인적사항",null,12,true,false,BLACK,HorizontalAlignmentMethod.Left,false);
    int[] cw={230,473,281,476};
    ControlTable t1=table("표_신고자",20,by+260,cw,new int[]{94,94});
    head(t1,0,0,"성명");  val(t1,0,1,f("EMP_NM"));  head(t1,0,2,"소속부서"); val(t1,0,3,f("DEPT_NM"));
    head(t1,1,0,"직위/직급"); val(t1,1,1,JPOSJGRD); head(t1,1,2,"연락처"); val(t1,1,3,f("MBPNO"));

    // 지원자 인적사항
    label(20,by+495,W-40,60,"□ 지원자 인적사항",null,12,true,false,BLACK,HorizontalAlignmentMethod.Left,false);
    ControlTable t2=table("표_지원자",20,by+570,cw,new int[]{98,94,110,106,106});
    head(t2,0,0,"성명");   val(t2,0,1,f("STDNT_NM"));     head(t2,0,2,"출신고교");        val(t2,0,3,f("ORGIN_HSCH_NM"));
    head(t2,1,0,"수험번호"); val(t2,1,1,f("ENTEX_EXMT_NO")); head(t2,1,2,"교직원과의\n관계"); val(t2,1,3,f("APLNT_RELTN_NM"));
    head(t2,2,0,"지원학과"); span(t2,2,1,3); val(t2,2,1,f("DEPRT_NM"));
    head(t2,3,0,"모집시기"); span(t2,3,1,3); TableCellNormal r=val(t2,3,1,RCRIT); r.getTextInfo().setLeftMargin(20); r.getTextInfo().setLineSpace(3f);
    head(t2,4,0,"전형유형"); span(t2,4,1,3); TableCellNormal s=val(t2,4,1,SCRNN); s.getTextInfo().setLeftMargin(20); s.getTextInfo().setLineSpace(3f);

    // 개인정보 수집 및 이용 동의
    label(20,by+1155,W-40,60,"□ 개인정보 수집 및 이용 동의",null,12,true,false,BLACK,HorizontalAlignmentMethod.Left,false);
    ControlTable t3=table("표_동의",20,by+1229,new int[]{318,991,151},new int[]{83,126});
    head(t3,0,0,"개인정보 수집 및\n이용 목적"); head(t3,0,1,"개인정보 제공 항목"); head(t3,0,2,"동의");
    TableCellNormal p1=cell(t3,1,0,"대학입학전형\n회피 여부확인",null,null,BLACK,HorizontalAlignmentMethod.Middle,-1,true,true,true,true); p1.getTextInfo().setLineSpace(2f);
    TableCellNormal p2=cell(t3,1,1,"신고자 인적사항(성명, 소속부서, 직위/직급, 연락처), 지원자 인적사항(성명, 출신고교, 수험번호, 교직원과의 관계) 및 지원학과, 모집시기, 전형유형  * 보유기간 1년",null,null,BLACK,HorizontalAlignmentMethod.Left,-1,true,true,true,true); p2.getTextInfo().setLineSpace(2f);
    // 동의 칸: CLIP 기본 체크박스 셀(CellContent=Checkbox) — 참/거짓 조건으로 INDIN_PROVD_AGREE_YN 1/0 판정
    TableCellNormal p3=cell(t3,1,2,"",null,null,BLACK,HorizontalAlignmentMethod.Middle,-1,true,true,true,true);
    checkbox(p3, f("INDIN_PROVD_AGREE_YN"), "1", "0");

    label(20,by+1470,W-40,100,"* 개인정보 수집 및 이용에 대한 동의를 거부할 수 있음. 단, 동의하지 않을 입학전형 업무에 제한받을 수 있음.",null,9,false,false,BLACK,HorizontalAlignmentMethod.Left,true);

    label(0,by+1603,W,60,"위와 같이 회피 자진 신고서를 제출합니다.",null,11,false,false,BLACK,HorizontalAlignmentMethod.Middle,false);
    label(0,by+1705,W,60,null,DATE,11,false,false,BLACK,HorizontalAlignmentMethod.Middle,false);
    label(0,by+1807,W-30,60,null,SIGN,11,false,false,BLACK,HorizontalAlignmentMethod.Right,false);
    label(40,by+1920,W-40,70,"인하공업전문대학 총장 귀하",null,12,false,false,BLACK,HorizontalAlignmentMethod.Left,false);
    return by+2015+25;
  }

  // ───────────── helpers ─────────────
  static Field f(String n){ Field x=fields.get(n); if(x==null) throw new RuntimeException("no field "+n); return x; }
  @SuppressWarnings("unchecked")
  static Field formula(String name,String script){
    FieldFormula ff=new FieldFormula(); ff.setName(name); ff.setScript(script); ff.setScriptType(ScriptType.JavaScript);
    ((RexObjectList<FieldFormula>) rep.getReportObjectManager().getFieldFormulaList()).add(ff); return ff;
  }
  static void text(TextInfo ti,int size,boolean bold,boolean underline,int color,HorizontalAlignmentMethod ha,boolean wrap){
    ti.setFontName(FONT); ti.setFontSize((short)size); ti.setFontBold(bold); ti.setFontUnderline(underline); ti.setForeColor(color);
    ti.setHorizontalAlignment(ha); ti.setVerticalAlignment(VerticalAlignmentMethod.Center); ti.setWordWrap(wrap); if(wrap) ti.setLineSpace(LS);
  }
  static ControlLabel label(int x,int y,int w,int h,String txt,Field fld,int size,boolean bold,boolean underline,int color,HorizontalAlignmentMethod ha,boolean wrap){
    ControlLabel c=new ControlLabel(); c.setName("글상자"+(++seq)); c.setVisible(true); c.setX1(x); c.setY1(y); c.setWidth(w); c.setHeight(h);
    c.setShapeType(ShapeType.Rectangle); c.setLineStyle(LineStyle.None); c.setBackStyle(BackStyleType.Transparent); c.setCanGrow(false);
    if(c.getLineInfo()!=null) c.getLineInfo().setLineStyle(LineStyle.None);
    if(fld!=null){ c.setApplyValueType(ApplyValueType.Field); c.setApplyValueField(fld); } else { c.setApplyValueType(ApplyValueType.Text); c.setApplyValueText(txt==null?"":txt); }
    text(c.getTextInfo(),size,bold,underline,color,ha,wrap);
    cl.add(c); return c;
  }
  static ControlTable table(String name,int x,int y,int[] colW,int[] rowH){
    ControlTable t=new ControlTable(); t.setName(name); t.setVisible(true); t.setX1(x); t.setY1(y);
    t.setLineStyle(LineStyle.None);
    line(t.getLineInfoSplit(),false); line(t.getLineInfoFDiagona(),false); line(t.getLineInfoBDiagona(),false); if(t.getLineInfo()!=null) t.getLineInfo().setLineStyle(LineStyle.None);
    int tw=0,th=0; TableRow[] rows=new TableRow[rowH.length]; TableColumn[] cols=new TableColumn[colW.length];
    for(int r=0;r<rowH.length;r++){ rows[r]=new TableRow(); rows[r].setHeight(rowH[r]); t.getTableRowList().add(rows[r]); th+=rowH[r]; }
    for(int c=0;c<colW.length;c++){ cols[c]=new TableColumn(); cols[c].setWidth(colW[c]); t.getTableColumnList().add(cols[c]); tw+=colW[c]; }
    for(int r=0;r<rowH.length;r++) for(int c=0;c<colW.length;c++){
      TableCellNormal n=new TableCellNormal(); n.setName(name+"_"+r+"_"+c); n.setTableRow(rows[r]); n.setTableColumn(cols[c]); n.setRowSpan(1); n.setColSpan(1);
      rows[r].getTableCellList().add(n); cols[c].getTableCellList().add(n);
    }
    try{ t.linkBaseCell(); t.setBaseCellRowColIndex(); }catch(Throwable e){ ps.println("WARN link "+e); }
    t.setWidth(tw); t.setHeight(th); cl.add(t); return t;
  }
  /** (r,c)부터 colspan 만큼 가로 병합: 뒤 셀들을 TableCellDumy 로 교체 */
  static void span(ControlTable t,int r,int c,int colspan){
    TableCellNormal base=(TableCellNormal) t.getTableCell(r,c); base.setColSpan(colspan);
    for(int k=1;k<colspan;k++){
      TableRow row=t.getTableRow(r); TableColumn col=t.getTableColumn(c+k);
      TableCell old=t.getTableCell(r,c+k);
      TableCellDumy d=new TableCellDumy(); d.setTableRow(row); d.setTableColumn(col); d.setBaseCell(base); d.setBaseCellRowIndex(r); d.setBaseCellColIndex(c);
      replace(row.getTableCellList(),old,d); replace(col.getTableCellList(),old,d);
    }
    try{ t.linkBaseCell(); t.setBaseCellRowColIndex(); }catch(Throwable e){ ps.println("WARN relink "+e); }
  }
  static void replace(RexObjectList<TableCell> list,TableCell old,TableCell nw){ for(int i=0;i<list.size();i++) if(list.get(i)==old){ list.remove(i); list.add(i,nw); return; } }
  static void line(LineInfo li,boolean on){ if(li==null) return; li.setLineStyle(on?LineStyle.Solid:LineStyle.None); li.setLineWidth(LineWidth.W050); li.setLineColor(BLACK); }
  static void lineColor(LineInfo li,int color,LineWidth w){ if(li==null) return; li.setLineStyle(LineStyle.Solid); li.setLineWidth(w); li.setLineColor(color); }
  static TableCellNormal cell(ControlTable t,int r,int c,String txt,Field fld,String fmt,int color,HorizontalAlignmentMethod ha,int bg,boolean L,boolean R,boolean T,boolean B){
    TableCellNormal n=(TableCellNormal) t.getTableCell(r,c);
    if(fld!=null){ n.setApplyValueType(ApplyValueType.Field); n.setApplyValueField(fld); } else { n.setApplyValueType(ApplyValueType.Text); n.setApplyValueText(txt==null?"":txt); }
    if(fmt!=null) n.setOutputFormat(fmt);
    text(n.getTextInfo(),10,false,false,color,ha,true);
    n.getTextInfo().setLeftMargin(8); n.getTextInfo().setRightMargin(8); n.getTextInfo().setLineSpace(0f);
    n.setCanGrow(false);
    if(bg>=0){ n.setBackStyle(BackStyleType.Normal); n.setBackColor(bg); } else { n.setBackStyle(BackStyleType.Transparent); }
    n.setVisibleLeftLine(L); n.setVisibleRightLine(R); n.setVisibleTopLine(T); n.setVisibleBottomLine(B);
    line(n.getLineInfoLeft(),L); line(n.getLineInfoRight(),R); line(n.getLineInfoTop(),T); line(n.getLineInfoBottom(),B); line(n.getLineInfoFDiagona(),false); line(n.getLineInfoBDiagona(),false);
    return n;
  }
  /** 셀을 기본 체크박스로: 조건필드==trueVal 이면 체크, ==falseVal 이면 해제 (디자이너 [셀 내용: 체크박스]와 동일) */
  static void checkbox(TableCellNormal n, Field condField, String trueVal, String falseVal){
    n.setCellContent(CellContentType.Checkbox); n.setCheckType(CheckType.Rectangle);   // 사용자 선호: V 체크보다 색칠(채운 사각형) n.setCheckShapeType(ShapeType.Rectangle); n.setCheckColor(BLACK); n.setCheckSize(0); n.setCheckValueDefault(false);
    com.clipsoft.clipreport.base.functions.Condition tc=n.getCheckValueTrueCondition(); tc.setConditionField(condField); tc.setCompareOperator(CompareOperator.Equal); tc.setCompareValue1Type(ApplyValueType.Text); tc.setCompareValue1Text(trueVal);
    com.clipsoft.clipreport.base.functions.Condition fc=n.getCheckValueFalseCondition(); fc.setConditionField(condField); fc.setCompareOperator(CompareOperator.Equal); fc.setCompareValue1Type(ApplyValueType.Text); fc.setCompareValue1Text(falseVal);
  }
  static TableCellNormal head(ControlTable t,int r,int c,String txt){ TableCellNormal n=cell(t,r,c,txt,null,null,BLACK,HorizontalAlignmentMethod.Middle,GRAY_BG,true,true,true,true); n.getTextInfo().setFontBold(true); n.getTextInfo().setLineSpace(2f); return n; }
  static TableCellNormal val(ControlTable t,int r,int c,Field fld){ return cell(t,r,c,null,fld,null,BLACK,HorizontalAlignmentMethod.Left,-1,true,true,true,true); }
}
