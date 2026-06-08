import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.reports.Report;
import com.clipsoft.clipreport.base.page.MainPage;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.datas.*;
import com.clipsoft.clipreport.base.datas.fields.FieldData;
import com.clipsoft.clipreport.base.controls.Control;
import com.clipsoft.clipreport.base.controls.ControlLabel;
import com.clipsoft.clipreport.base.controls.ControlListForEachSeparatedPage;
import com.clipsoft.clipreport.base.enums.*;
import com.clipsoft.clipreport.base.RexObjectList;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import java.io.*;
import java.util.*;

/** Place field-bound data labels in the DETAIL band + static caption labels in the PAGE HEADER.
 *  Proves the field<->control binding (setApplyValueField). */
public class CrfGenDetail {
  static PrintStream ps;
  static DataType dt(String s){ try { return DataType.valueOf(s); } catch(Exception e){ return DataType.String; } }

  static ControlLabel mkLabel(String name,int x,int y,int w,int h){
    ControlLabel c=new ControlLabel(); c.setName(name); c.setVisible(true);
    c.setX1(x); c.setY1(y); c.setWidth(w); c.setHeight(h); return c;
  }
  @SuppressWarnings("unchecked")
  static RexObjectList<Control> newControlList(SubSectionDefault sd){
    ControlListForEachSeparatedPage clp=new ControlListForEachSeparatedPage();
    sd.getControlListForEachSeparatedPageList().add(clp);
    return (RexObjectList<Control>) clp.getControlList();
  }
  static SubSectionDefault firstSub(Section sec){
    RexObjectList<SubSection> ss=sec.getSubSectionList();
    if(ss==null||ss.size()==0) return null;
    SubSection s=ss.get(0); return (s instanceof SubSectionDefault)?(SubSectionDefault)s:null;
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    ps=new PrintStream(System.out,true,"UTF-8");
    String template=a[0], planPath=a[1], out=a[2];
    JSONObject plan=(JSONObject)new JSONParser().parse(new InputStreamReader(new FileInputStream(planPath),"UTF-8"));
    JSONArray cols=(JSONArray)plan.get("columns");

    TheReportFile rf=Rexpert4.read(template);
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    Report rep=rf.getGlobe().getMainReport();
    DataSet ds=gom.getDataSetList().get(0);

    // fields
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    fl.removeAll();
    List<FieldData> fields=new ArrayList<>();
    for(int i=0;i<cols.size();i++){ JSONObject c=(JSONObject)cols.get(i);
      FieldData f=new FieldData(); f.setName((String)c.get("name")); f.setDataType(dt((String)c.get("type"))); f.setIndex(i);
      fl.add(f); fields.add(f); }
    // query
    DataAccessMethodSQL sql=ds.getDataSetItemNormal().getDataAccessMethodSQL();
    sql.setScriptType(ScriptType.valueOf((String)plan.get("scriptType"))); sql.setQueryString((String)plan.get("queryString"));

    // layout
    int n=fields.size();
    int colW=Math.max(150, Math.min(400, 2600/Math.max(1,n)));
    int hH=70, dH=55;

    MainPage mp=rep.getReportDesign().getMainPage();
    RexObjectList<Section> secs=mp.getSectionList();
    SectionPageHeader ph=null; SectionDetail det=null;
    for(int i=0;i<secs.size();i++){ Section s=secs.get(i); if(s instanceof SectionPageHeader) ph=(SectionPageHeader)s; if(s instanceof SectionDetail) det=(SectionDetail)s; }

    int headerLabels=0, dataLabels=0;
    // page header: static column captions
    if(ph!=null){ SubSectionDefault sd=firstSub(ph); if(sd!=null){ RexObjectList<Control> cl=newControlList(sd);
      for(int i=0;i<n;i++){ ControlLabel c=mkLabel("hdr_"+fields.get(i).getName(), i*colW, 0, colW, hH);
        c.setApplyValueType(ApplyValueType.Text); c.setApplyValueText(fields.get(i).getName());
        cl.add(c); headerLabels++; } } }
    // detail: field-bound data labels
    if(det!=null){ SubSectionDefault sd=firstSub(det); if(sd!=null){ RexObjectList<Control> cl=newControlList(sd);
      for(int i=0;i<n;i++){ ControlLabel c=mkLabel("dat_"+fields.get(i).getName(), i*colW, 0, colW, dH);
        c.setApplyValueType(ApplyValueType.Field); c.setApplyValueField(fields.get(i));
        cl.add(c); dataLabels++; } } }
    ps.println("placed: pageHeader captions="+headerLabels+"  detail dataLabels="+dataLabels+"  colW="+colW);

    Rexpert4.write(rf,out);
    ps.println("written "+out+"  size="+new File(out).length());

    // verify: re-read, walk detail controls, confirm binding
    TheReportFile v=Rexpert4.read(out);
    RexObjectList<Section> vs=v.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    int bound=0; List<String> sample=new ArrayList<>();
    for(int i=0;i<vs.size();i++){ if(!(vs.get(i) instanceof SectionDetail)) continue;
      for(Control c: walkControls(vs.get(i))){
        if(c instanceof ControlLabel){ ControlLabel lc=(ControlLabel)c;
          if(lc.getApplyValueType()==ApplyValueType.Field && lc.getApplyValueField()!=null){ bound++;
            if(sample.size()<12) sample.add(lc.getName()+" -> field="+lc.getApplyValueField().getName()+" @("+lc.getX1()+","+lc.getY1()+" "+lc.getWidth()+"x"+lc.getHeight()+")"); } } } }
    ps.println("\n===== VERIFY (detail field-bound labels reloaded) =====");
    ps.println("field-bound data labels = "+bound);
    for(String s: sample) ps.println("  "+s);
  }

  @SuppressWarnings("unchecked")
  static List<Control> walkControls(Object o){ List<Control> out=new ArrayList<>(); collect(o,out,Collections.newSetFromMap(new IdentityHashMap<>()),0); return out; }
  static void collect(Object o,List<Control> out,Set<Object> seen,int d){
    if(o==null||d>14) return;
    if(o instanceof RexObjectList){ RexObjectList<?> l=(RexObjectList<?>)o; for(int i=0;i<l.size();i++) collect(l.get(i),out,seen,d); return; }
    if(!o.getClass().getName().startsWith("com.clipsoft.clipreport")) return; if(!seen.add(o)) return;
    if(o instanceof Control) out.add((Control)o);
    for(java.lang.reflect.Method m:o.getClass().getMethods()){ if(m.getParameterCount()!=0||!m.getName().startsWith("get")||m.getName().equals("getClass"))continue;
      Class<?> rt=m.getReturnType(); if(rt.isPrimitive()||rt==String.class||rt.isEnum()||rt==Class.class)continue;
      String nm=m.getName(); if(nm.equals("getApplyValueField")||nm.equals("getParentObj")||nm.equals("getSubreport"))continue;
      try{ collect(m.invoke(o),out,seen,d+1);}catch(Throwable t){} }
  }
}
