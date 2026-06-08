import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.page.MainPage;
import com.clipsoft.clipreport.base.reports.Report;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.groups.Group;
import com.clipsoft.clipreport.base.datas.fields.Field;
import com.clipsoft.clipreport.base.RexObjectList;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class CrfGroupInspect {
  static PrintStream ps;
  static String nm(Object o){ if(o==null)return "null"; try{ Object v=o.getClass().getMethod("getName").invoke(o); return v==null?"":(""+v);}catch(Exception e){return "?";} }
  public static void main(String[] a) throws Exception {
    ps=new PrintStream(System.out,true,"UTF-8");
    List<Path> files; try(Stream<Path> s=Files.walk(Paths.get(a[0]))){ files=s.filter(p->p.toString().toLowerCase().endsWith(".crf")).limit(400).collect(Collectors.toList()); }
    for(Path p: files){
      try{
        TheReportFile rf=Rexpert4.read(p.toString());
        Report rep=rf.getGlobe().getMainReport();
        RexObjectList<Group> groups=rep.getReportObjectManager().getGroupList();
        MainPage mp=rep.getReportDesign().getMainPage();
        RexObjectList<Section> secs=mp.getSectionList();
        boolean hasGH=false; for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionGroupHeader) hasGH=true;
        if(groups.size()==0 || !hasGH) continue;
        ps.println("=== FOUND grouped report: "+p.getFileName()+"  groups="+groups.size()+" sections="+secs.size());
        for(int i=0;i<groups.size();i++){ Group g=groups.get(i);
          ps.println(String.format("  Group[%d] objectID=%d refIndex=%d groupingField=%s sort=%s totalVis=%s labelVis=%s",
            i,g.getObjectID(),g.getRefIndex(),nm(g.getGroupingField()),g.getSortMethod(),g.getTotalVisible(),g.getLabelVisible())); }
        ps.println("  --- section list ---");
        for(int i=0;i<secs.size();i++){ Section sec=secs.get(i); String t=sec.getClass().getSimpleName();
          String gref=""; if(sec instanceof SectionGroupHeader){ Group g=((SectionGroupHeader)sec).getGroup(); gref=" -> Group objectID="+(g==null?"null":g.getObjectID()); }
          RexObjectList<SubSection> ss=sec.getSubSectionList();
          ps.print(String.format("   [%2d] %-20s subsecs=%d%s | ",i,t,ss.size(),gref));
          for(int j=0;j<ss.size();j++){ SubSection s=ss.get(j); ps.print(s.getClass().getSimpleName()+"(\""+nm(s)+"\",h="+s.getHeight()+") "); }
          ps.println();
          // inspect first subsection's internal control-container structure for a group section
          if((sec instanceof SectionGroupHeader||sec instanceof SectionGroupFooter) && ss.size()>0){
            SubSection s0=ss.get(0);
            if(s0 instanceof SubSectionDefault){ SubSectionDefault sd=(SubSectionDefault)s0;
              RexObjectList<?> cls=sd.getControlListForEachSeparatedPageList();
              ps.println("        SubSectionDefault.controlListForEachSeparatedPageList size="+(cls==null?0:cls.size()));
              if(cls!=null) for(int k=0;k<cls.size();k++){ Object clp=cls.get(k);
                try{ RexObjectList<?> ctrls=(RexObjectList<?>)clp.getClass().getMethod("getControlList").invoke(clp);
                  ps.print("          CLP["+k+"] controls="+ctrls.size()+": "); for(int x=0;x<ctrls.size();x++) ps.print(ctrls.get(x).getClass().getSimpleName()+" "); ps.println();
                }catch(Exception e){ ps.println("          CLP err "+e); }
              }
            }
          }
        }
        return; // first one only
      }catch(Throwable t){}
    }
    ps.println("no grouped report found");
  }
}
