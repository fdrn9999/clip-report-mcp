import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.reports.Report;
import com.clipsoft.clipreport.base.page.MainPage;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.groups.Group;
import com.clipsoft.clipreport.base.datas.*;
import com.clipsoft.clipreport.base.datas.fields.FieldData;
import com.clipsoft.clipreport.base.datas.fields.FieldGlobalParameter;
import com.clipsoft.clipreport.base.controls.ControlListForEachSeparatedPage;
import com.clipsoft.clipreport.base.controls.ControlSubreport;
import com.clipsoft.clipreport.base.functions.WebLinkInfo;
import com.clipsoft.clipreport.base.enums.*;
import com.clipsoft.clipreport.base.RexObjectList;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Unified draft generator: SQL/MyBatis -> dataset+fields+query+params + group bands + page-footer logo. */
public class CrfGen3 {
  static PrintStream ps;
  static String nm(Object o){ if(o==null)return "null"; try{return ""+o.getClass().getMethod("getName").invoke(o);}catch(Exception e){return "?";} }

  static List<String> parseGroupBy(String sql){
    String up=sql.toUpperCase(); int g=CrfGen2.indexOfKeyword(up,"GROUP",0); List<String> out=new ArrayList<>();
    if(g<0) return out; int by=CrfGen2.indexOfKeyword(up,"BY",g); if(by<0) return out;
    int end=sql.length(); for(String kw:new String[]{"ORDER","HAVING","LIMIT"}){ int k=CrfGen2.indexOfKeyword(up,kw,by); if(k>=0&&k<end)end=k; }
    for(String item: CrfGen2.splitTop(sql.substring(by+2,end))){ String n=item.trim(); int d=n.lastIndexOf('.'); if(d>=0)n=n.substring(d+1); n=n.replaceAll("[^A-Za-z0-9_]",""); if(n.matches("[A-Za-z_][A-Za-z0-9_]*"))out.add(n); }
    return out;
  }
  static SubSectionDefault band(String name,int h){
    SubSectionDefault sd=new SubSectionDefault(); sd.setName(name); sd.setHeight(h); sd.setVisible(true);
    sd.getControlListForEachSeparatedPageList().add(new ControlListForEachSeparatedPage());
    return sd;
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    ps=new PrintStream(System.out,true,"UTF-8");
    String template=a[0], inFile=a[1], out=a[2];
    String raw=new String(Files.readAllBytes(Paths.get(inFile)), java.nio.charset.StandardCharsets.UTF_8);
    boolean mybatis=Pattern.compile("(?is)<(if|where|foreach|choose|trim|set|select)\\b").matcher(raw).find();

    List<String> cols=CrfGen2.parseColumns(CrfGen2.stripComments(CrfGen2.stripTags(raw)));
    List<String> groupCols=parseGroupBy(CrfGen2.stripComments(CrfGen2.stripTags(raw)));
    List<String> params=CrfGen2.parseParams(raw);
    List<String> warns=new ArrayList<>();
    String queryString; ScriptType stype;
    if(mybatis){ queryString=CrfGen2.mybatisToJs(raw,warns); stype=ScriptType.JavaScript; }
    else { queryString=CrfGen2.subParamsQuoted(CrfGen2.stripComments(raw)).trim(); stype=ScriptType.NotScript; }
    queryString=CrfGen2.normParamTokens(queryString);   // empNm -> EMPNM

    ps.println("== INPUT ==  mybatis="+mybatis+"  scriptType="+stype);
    ps.println("columns   : "+cols);
    ps.println("group by  : "+groupCols);
    ps.println("params    : "+params);

    TheReportFile rf=Rexpert4.read(template);
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    Report rep=rf.getGlobe().getMainReport();
    DataSet ds=gom.getDataSetList().get(0);

    // 1) fields
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    fl.removeAll();
    Map<String,FieldData> byName=new HashMap<>();
    for(int i=0;i<cols.size();i++){ FieldData f=new FieldData(); f.setName(cols.get(i)); f.setDataType(CrfGen2.guessType(cols.get(i))); f.setIndex(i); fl.add(f); byName.put(cols.get(i).toUpperCase(),f); }

    // 2) query
    DataAccessMethodSQL sql=ds.getDataSetItemNormal().getDataAccessMethodSQL();
    sql.setScriptType(stype); sql.setQueryString(queryString);

    // 3) global params
    RexObjectList<FieldGlobalParameter> gp=(RexObjectList<FieldGlobalParameter>)(RexObjectList<?>) gom.getFieldGlobalParameterList();
    Set<String> have=new HashSet<>(); for(int i=0;i<gp.size();i++) have.add(gp.get(i).getName());
    for(String p:params){ String pn=CrfGen2.rp(p); if(have.contains(pn))continue; have.add(pn); FieldGlobalParameter fp=new FieldGlobalParameter(); fp.setName(pn); fp.setDataType(DataType.String); fp.setDefaultValue(""); fp.setValueIsNull(Boolean.FALSE); fp.setPrompt(p); fp.setTag(""); gp.add(fp); }

    // 4) groups (one per group-by column)
    MainPage mp=rep.getReportDesign().getMainPage();
    RexObjectList<Section> secs=mp.getSectionList();
    List<SectionGroupHeader> heads=new ArrayList<>(); List<SectionGroupFooter> foots=new ArrayList<>();
    for(String gc: groupCols){
      FieldData gfld=byName.get(gc.toUpperCase());
      Group grp=new Group(2800); if(gfld!=null) grp.setGroupingField(gfld);
      grp.setSortMethod(SortMethod.Ascending); grp.setDataTypeCasting(SortDataTypeCasting.String);
      grp.setTotalVisible(true); grp.setLabelVisible(true);
      rep.getReportObjectManager().getGroupList().add(grp);
      SectionGroupHeader gh=new SectionGroupHeader(); gh.setGroup(grp); gh.getSubSectionList().add(band("그룹 머리글["+gc+"]",60)); heads.add(gh);
      SectionGroupFooter gf=new SectionGroupFooter(); gf.getSubSectionList().add(band("그룹 바닥글["+gc+"]",50)); foots.add(gf);
    }
    // rebuild ordered section list:  before-detail + heads + detail + foots(reverse) + after-detail
    int di=-1; for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionDetail) di=i;
    List<Section> all=new ArrayList<>(); for(int i=0;i<secs.size();i++) all.add(secs.get(i));
    if(di>=0 && !groupCols.isEmpty()){
      List<Section> rebuilt=new ArrayList<>();
      for(int i=0;i<di;i++) rebuilt.add(all.get(i));
      rebuilt.addAll(heads);
      rebuilt.add(all.get(di));
      List<SectionGroupFooter> rf2=new ArrayList<>(foots); Collections.reverse(rf2); rebuilt.addAll(rf2);
      for(int i=di+1;i<all.size();i++) rebuilt.add(all.get(i));
      secs.removeAll(); for(Section s: rebuilt) secs.add(s);
    }

    // 5) page footer logo subreport (ensure)
    SectionPageFooter pf=null; for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionPageFooter) pf=(SectionPageFooter)secs.get(i);
    boolean hasLogo=false;
    if(pf!=null){ String dump=footerDump(pf); hasLogo=dump.contains("bottom_logo.crf"); }
    ps.println("\npage footer exists="+(pf!=null)+"  hasLogo(before)="+hasLogo);
    if(!hasLogo){
      if(pf==null){ pf=new SectionPageFooter(); secs.add(pf); }
      SubSectionDefault sd=band("페이지 바닥글[공통]",170);
      ControlSubreport sr=new ControlSubreport(); sr.setName("공통하단로고");
      WebLinkInfo wi=sr.getLinkedSubreportPath();
      if(wi!=null){ wi.setUrlText("../../../images/bottom_logo.crf"); ps.println("  logo subreport url set"); }
      else ps.println("  WARN getLinkedSubreportPath()==null");
      ((RexObjectList<com.clipsoft.clipreport.base.controls.Control>) sd.getControlListForEachSeparatedPageList().get(0).getControlList()).add(sr);
      pf.getSubSectionList().add(sd);
    }

    Rexpert4.write(rf,out);
    ps.println("\nwritten "+out+"  size="+new File(out).length());

    // verify
    TheReportFile v=Rexpert4.read(out);
    GlobalObjectManager vg=v.getGlobe().getGlobalObjectManager();
    Report vr=v.getGlobe().getMainReport();
    DataSet vds=vg.getDataSetList().get(0);
    ps.println("\n===== VERIFY =====");
    ps.println("scriptType="+vds.getDataSetItemNormal().getDataAccessMethodSQL().getScriptType());
    RexObjectList<FieldData> vf=(RexObjectList<FieldData>) vds.getFieldDataList();
    ps.print("fields("+vf.size()+"): "); for(int i=0;i<vf.size();i++) ps.print(vf.get(i).getName()+" "); ps.println();
    RexObjectList<FieldGlobalParameter> vp=(RexObjectList<FieldGlobalParameter>)(RexObjectList<?>) vg.getFieldGlobalParameterList();
    ps.print("globalParams("+vp.size()+"): "); for(int i=0;i<vp.size();i++) ps.print(vp.get(i).getName()+" "); ps.println();
    ps.println("groups="+vr.getReportObjectManager().getGroupList().size());
    RexObjectList<Section> vs=vr.getReportDesign().getMainPage().getSectionList();
    for(int i=0;i<vs.size();i++){ Section s=vs.get(i); String x=""; if(s instanceof SectionGroupHeader){ Group g=((SectionGroupHeader)s).getGroup(); x=" -> "+(g==null?"null":nm(g.getGroupingField())); } ps.println(String.format("  [%2d] %-20s%s",i,s.getClass().getSimpleName(),x)); }
    SectionPageFooter vpf=null; for(int i=0;i<vs.size();i++) if(vs.get(i) instanceof SectionPageFooter) vpf=(SectionPageFooter)vs.get(i);
    ps.println("pageFooter hasLogo(after)="+(vpf!=null && footerDump(vpf).contains("bottom_logo.crf")));
  }

  static String footerDump(Section pf){
    StringBuilder b=new StringBuilder(); dumpC(pf,b,Collections.newSetFromMap(new IdentityHashMap<>()),0); return b.toString();
  }
  static void dumpC(Object o,StringBuilder b,Set<Object> seen,int d){
    if(o==null||d>14) return;
    if(o instanceof RexObjectList){ RexObjectList<?> l=(RexObjectList<?>)o; for(int i=0;i<l.size();i++) dumpC(l.get(i),b,seen,d); return; }
    if(!o.getClass().getName().startsWith("com.clipsoft.clipreport")) return; if(!seen.add(o)) return;
    for(java.lang.reflect.Method m:o.getClass().getMethods()){ if(m.getParameterCount()==0&&m.getReturnType()==String.class&&m.getName().startsWith("get")&&!m.getName().equals("getClass")){ try{Object v=m.invoke(o); if(v!=null)b.append(v).append("|");}catch(Exception e){} } }
    if(o.getClass().getSimpleName().equals("ControlSubreport")) return;
    for(java.lang.reflect.Method m:o.getClass().getMethods()){ if(m.getParameterCount()!=0||!m.getName().startsWith("get")||m.getName().equals("getClass"))continue; Class<?> rt=m.getReturnType(); if(rt.isPrimitive()||rt==String.class||rt.isEnum()||rt==Class.class)continue; if(m.getName().equals("getSubreport")||m.getName().equals("getParentObj"))continue; try{dumpC(m.invoke(o),b,seen,d+1);}catch(Throwable t){} }
  }
}
