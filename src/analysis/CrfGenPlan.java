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
import com.clipsoft.clipreport.base.controls.Control;
import com.clipsoft.clipreport.base.functions.WebLinkInfo;
import com.clipsoft.clipreport.base.enums.*;
import com.clipsoft.clipreport.base.RexObjectList;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import java.io.*;
import java.util.*;

/** Consumes an AI-produced mapping plan (JSON) and emits a .crf via the official engine. */
public class CrfGenPlan {
  static PrintStream ps;
  static DataType dt(String s){ try { return DataType.valueOf(s); } catch(Exception e){ return DataType.String; } }
  static SubSectionDefault band(String name,int h){
    SubSectionDefault sd=new SubSectionDefault(); sd.setName(name); sd.setHeight(h); sd.setVisible(true);
    sd.getControlListForEachSeparatedPageList().add(new ControlListForEachSeparatedPage());
    return sd;
  }
  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    ps=new PrintStream(System.out,true,"UTF-8");
    String template=a[0], planPath=a[1], out=a[2];
    JSONObject plan=(JSONObject)new JSONParser().parse(new InputStreamReader(new FileInputStream(planPath),"UTF-8"));

    String queryString=(String)plan.get("queryString");
    String scriptType=(String)plan.get("scriptType");
    JSONArray cols=(JSONArray)plan.get("columns");
    JSONArray params=(JSONArray)plan.get("params");
    JSONArray groups=(JSONArray)plan.get("groups");
    String footerLogo=(String)plan.get("pageFooterLogo");
    JSONArray maps=(JSONArray)plan.get("fieldMappings");

    ps.println("== PLAN ==  dataset="+plan.get("datasetName")+"  scriptType="+scriptType);
    ps.println("columns="+cols.size()+"  params="+params+"  groups="+groups);
    ps.println("fieldMappings="+ (maps==null?0:maps.size()) +"  footerLogo="+footerLogo);

    TheReportFile rf=Rexpert4.read(template);
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    Report rep=rf.getGlobe().getMainReport();
    DataSet ds=gom.getDataSetList().get(0);

    // 1) fields from plan columns
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    fl.removeAll();
    Map<String,FieldData> byName=new HashMap<>();
    for(int i=0;i<cols.size();i++){ JSONObject c=(JSONObject)cols.get(i);
      FieldData f=new FieldData(); f.setName((String)c.get("name")); f.setDataType(dt((String)c.get("type"))); f.setIndex(i);
      fl.add(f); byName.put(((String)c.get("name")).toUpperCase(),f); }

    // 2) query
    DataAccessMethodSQL sql=ds.getDataSetItemNormal().getDataAccessMethodSQL();
    sql.setScriptType(ScriptType.valueOf(scriptType)); sql.setQueryString(queryString);

    // 3) global params
    RexObjectList<FieldGlobalParameter> gp=(RexObjectList<FieldGlobalParameter>)(RexObjectList<?>) gom.getFieldGlobalParameterList();
    Set<String> have=new HashSet<>(); for(int i=0;i<gp.size();i++) have.add(gp.get(i).getName());
    for(Object p: params){ String pn=(String)p; if(have.contains(pn))continue;
      FieldGlobalParameter fp=new FieldGlobalParameter(); fp.setName(pn); fp.setDataType(DataType.String); fp.setDefaultValue(""); fp.setValueIsNull(Boolean.FALSE); fp.setPrompt(pn); fp.setTag(""); gp.add(fp); }

    // 4) groups + group header/footer bands
    MainPage mp=rep.getReportDesign().getMainPage();
    RexObjectList<Section> secs=mp.getSectionList();
    List<SectionGroupHeader> heads=new ArrayList<>(); List<SectionGroupFooter> foots=new ArrayList<>();
    for(Object g: groups){ String gc=(String)g; FieldData gfld=byName.get(gc.toUpperCase());
      Group grp=new Group(2800); if(gfld!=null) grp.setGroupingField(gfld);
      grp.setSortMethod(SortMethod.Ascending); grp.setDataTypeCasting(SortDataTypeCasting.String);
      grp.setTotalVisible(true); grp.setLabelVisible(true);
      rep.getReportObjectManager().getGroupList().add(grp);
      SectionGroupHeader gh=new SectionGroupHeader(); gh.setGroup(grp); gh.getSubSectionList().add(band("그룹 머리글["+gc+"]",60)); heads.add(gh);
      SectionGroupFooter gf=new SectionGroupFooter(); gf.getSubSectionList().add(band("그룹 바닥글["+gc+"]",50)); foots.add(gf);
    }
    int di=-1; for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionDetail) di=i;
    if(di>=0 && !groups.isEmpty()){
      List<Section> all=new ArrayList<>(); for(int i=0;i<secs.size();i++) all.add(secs.get(i));
      List<Section> nb=new ArrayList<>();
      for(int i=0;i<di;i++) nb.add(all.get(i));
      nb.addAll(heads); nb.add(all.get(di));
      List<SectionGroupFooter> rev=new ArrayList<>(foots); Collections.reverse(rev); nb.addAll(rev);
      for(int i=di+1;i<all.size();i++) nb.add(all.get(i));
      secs.removeAll(); for(Section s: nb) secs.add(s);
    }

    // 5) page footer common logo subreport
    if(footerLogo!=null && !footerLogo.isEmpty()){
      SectionPageFooter pf=null; for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionPageFooter) pf=(SectionPageFooter)secs.get(i);
      if(pf==null){ pf=new SectionPageFooter(); secs.add(pf); }
      SubSectionDefault sd=band("페이지 바닥글[공통]",170);
      ControlSubreport sr=new ControlSubreport(); sr.setName("공통하단로고");
      WebLinkInfo wi=sr.getLinkedSubreportPath(); if(wi!=null) wi.setUrlText(footerLogo);
      ((RexObjectList<Control>) sd.getControlListForEachSeparatedPageList().get(0).getControlList()).add(sr);
      pf.getSubSectionList().add(sd);
    }

    Rexpert4.write(rf,out);
    ps.println("\nwritten "+out+"  size="+new File(out).length());

    // verify
    TheReportFile v=Rexpert4.read(out);
    Report vr=v.getGlobe().getMainReport();
    DataSet vds=v.getGlobe().getGlobalObjectManager().getDataSetList().get(0);
    RexObjectList<FieldData> vf=(RexObjectList<FieldData>) vds.getFieldDataList();
    ps.println("\n===== VERIFY =====");
    ps.print("fields("+vf.size()+"): "); for(int i=0;i<vf.size();i++) ps.print(vf.get(i).getName()+":"+vf.get(i).getDataType()+" "); ps.println();
    ps.println("groups="+vr.getReportObjectManager().getGroupList().size());
    RexObjectList<Section> vs=vr.getReportDesign().getMainPage().getSectionList();
    for(int i=0;i<vs.size();i++){ Section s=vs.get(i); String x="";
      if(s instanceof SectionGroupHeader){ Group g=((SectionGroupHeader)s).getGroup(); x=" -> "+(g==null?"?":g.getGroupingField()==null?"?":g.getGroupingField().getName()); }
      ps.println(String.format("  [%2d] %s%s",i,s.getClass().getSimpleName(),x)); }
  }
}
