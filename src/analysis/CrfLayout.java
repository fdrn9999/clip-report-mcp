import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.page.MainPage;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.RexObjectList;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public class CrfLayout {
  static PrintStream ps;
  static String name(Object o){ try{ Object v=o.getClass().getMethod("getName").invoke(o); return v==null?"":(""+v); }catch(Exception e){ return ""; } }
  static String webInfo(Object o){ if(o==null) return "null"; StringBuilder b=new StringBuilder(); for(Method m:o.getClass().getMethods()){ if(m.getParameterCount()==0&&m.getName().startsWith("get")&&m.getReturnType()==String.class&&!m.getName().equals("getClass")){ try{Object v=m.invoke(o); if(v!=null&&!((String)v).isEmpty()) b.append(m.getName().substring(3)).append("=").append(v).append(" ");}catch(Exception e){} } } return b.toString(); }
  static void controls(Object o,String ind,Set<Object> seen,int d){
    if(o==null||d>16) return;
    if(o instanceof RexObjectList){ RexObjectList<?> l=(RexObjectList<?>)o; for(int i=0;i<l.size();i++) controls(l.get(i),ind,seen,d); return; }
    if(!o.getClass().getName().startsWith("com.clipsoft.clipreport")) return;
    if(!seen.add(o)) return;
    String s=o.getClass().getSimpleName();
    boolean isCtl=s.startsWith("Control");
    String nind=ind;
    if(isCtl){ ps.println(ind+"- "+s+"  name=\""+name(o)+"\""); nind=ind+"   "; }
    if(s.equals("ControlSubreport")){ try{ Object wp=o.getClass().getMethod("getLinkedSubreportPath").invoke(o); ps.println(nind+"(linkedSubreport: "+webInfo(wp)+")"); }catch(Exception e){}
       try{ Object sub=o.getClass().getMethod("getSubreport").invoke(o); ps.println(nind+"(embeddedSubreport="+(sub!=null)+")"); }catch(Exception e){} return; }
    for(Method m: o.getClass().getMethods()){
      if(m.getParameterCount()!=0||!m.getName().startsWith("get")||m.getName().equals("getClass")) continue;
      Class<?> rt=m.getReturnType(); if(rt.isPrimitive()||rt==String.class||rt.isEnum()||rt==Class.class) continue;
      String mn=m.getName(); if(mn.equals("getSubreport")||mn.equals("getParentObj")||mn.equals("getRefInfomationStorage")) continue;
      try{ controls(m.invoke(o),nind,seen,d+1);}catch(Throwable t){}
    }
  }
  public static void main(String[] a) throws Exception {
    ps=new PrintStream(System.out,true,"UTF-8");
    for(String path: a){
      TheReportFile rf=Rexpert4.read(path);
      MainPage mp=rf.getGlobe().getMainReport().getReportDesign().getMainPage();
      RexObjectList<Section> secs=mp.getSectionList();
      ps.println("\n################ "+new File(path).getName()+"  sections="+secs.size());
      for(int i=0;i<secs.size();i++){
        Section sec=secs.get(i);
        String st=sec.getClass().getSimpleName();
        StringBuilder subs=new StringBuilder();
        RexObjectList<SubSection> ss=sec.getSubSectionList();
        for(int j=0;j<ss.size();j++){ subs.append("\""+name(ss.get(j))+"\""); try{subs.append("(h="+ss.get(j).getClass().getMethod("getHeight").invoke(ss.get(j))+")");}catch(Exception e){} subs.append(" "); }
        String grp=""; if(sec instanceof SectionGroupHeader){ try{Object g=((SectionGroupHeader)sec).getGroup(); grp=" group#"+(g==null?"null":System.identityHashCode(g));}catch(Exception e){} }
        ps.println(String.format("[%2d] %-20s subs=%s%s",i,st,subs,grp));
        if(st.contains("PageFooter")||st.contains("Group")){ controls(sec,"        ",Collections.newSetFromMap(new IdentityHashMap<>()),0); }
      }
    }
  }
}
