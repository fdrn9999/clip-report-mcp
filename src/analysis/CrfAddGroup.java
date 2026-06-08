import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.page.MainPage;
import com.clipsoft.clipreport.base.reports.Report;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.groups.Group;
import com.clipsoft.clipreport.base.datas.DataSet;
import com.clipsoft.clipreport.base.datas.fields.Field;
import com.clipsoft.clipreport.base.datas.fields.FieldData;
import com.clipsoft.clipreport.base.controls.ControlListForEachSeparatedPage;
import com.clipsoft.clipreport.base.enums.SortMethod;
import com.clipsoft.clipreport.base.enums.SortDataTypeCasting;
import com.clipsoft.clipreport.base.RexObjectList;
import java.io.*;

public class CrfAddGroup {
  static PrintStream ps;
  static String nm(Object o){ if(o==null)return "null"; try{return ""+o.getClass().getMethod("getName").invoke(o);}catch(Exception e){return "?";} }
  static SubSectionDefault band(String name,int h){
    SubSectionDefault sd=new SubSectionDefault(); sd.setName(name); sd.setHeight(h); sd.setVisible(true);
    sd.getControlListForEachSeparatedPageList().add(new ControlListForEachSeparatedPage());
    return sd;
  }
  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    ps=new PrintStream(System.out,true,"UTF-8");
    String template=a[0], out=a[1], groupCol=a[2];
    TheReportFile rf=Rexpert4.read(template);
    Report rep=rf.getGlobe().getMainReport();

    // find grouping field (search dataset fields)
    Field gf=null;
    for(DataSet ds: iter(rf.getGlobe().getGlobalObjectManager().getDataSetList())){
      RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
      for(int i=0;i<fl.size();i++) if(groupCol.equalsIgnoreCase(fl.get(i).getName())) gf=fl.get(i);
    }
    ps.println("grouping field = "+nm(gf));

    // create group
    Group g=new Group(2800);
    if(gf!=null) g.setGroupingField(gf);
    g.setSortMethod(SortMethod.Ascending);
    g.setDataTypeCasting(SortDataTypeCasting.String);
    g.setTotalVisible(true);
    g.setLabelVisible(true);
    rep.getReportObjectManager().getGroupList().add(g);

    // header + footer sections
    SectionGroupHeader gh=new SectionGroupHeader(); gh.setGroup(g);
    gh.getSubSectionList().add(band("그룹 머리글1",50));
    SectionGroupFooter gfoot=new SectionGroupFooter();
    gfoot.getSubSectionList().add(band("그룹 바닥글1",50));

    // insert around Detail
    MainPage mp=rep.getReportDesign().getMainPage();
    RexObjectList<Section> secs=mp.getSectionList();
    int di=-1; for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionDetail) di=i;
    ps.println("detail index="+di+"  sectionCount(before)="+secs.size());
    if(di<0){ secs.add(gh); secs.add(gfoot); }
    else { secs.add(di, gh); secs.add(di+2, gfoot); }

    Rexpert4.write(rf,out);
    ps.println("written: "+out+"  size="+new File(out).length());

    // verify
    TheReportFile rf2=Rexpert4.read(out);
    Report rep2=rf2.getGlobe().getMainReport();
    ps.println("\n===== VERIFY =====");
    ps.println("groupList="+rep2.getReportObjectManager().getGroupList().size());
    RexObjectList<Section> s2=rep2.getReportDesign().getMainPage().getSectionList();
    for(int i=0;i<s2.size();i++){ Section sec=s2.get(i); String gx="";
      if(sec instanceof SectionGroupHeader){ Group gg=((SectionGroupHeader)sec).getGroup(); gx=" -> group field="+(gg==null?"null":nm(gg.getGroupingField())); }
      ps.println(String.format("  [%2d] %-20s%s",i,sec.getClass().getSimpleName(),gx)); }
  }
  static Iterable<DataSet> iter(RexObjectList<DataSet> l){ java.util.List<DataSet> o=new java.util.ArrayList<>(); for(int i=0;i<l.size();i++)o.add(l.get(i)); return o; }
}
