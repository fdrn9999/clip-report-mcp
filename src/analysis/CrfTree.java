import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.RexObjectList;
import java.lang.reflect.*;
import java.util.*;

public class CrfTree {
  static Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
  static StringBuilder out=new StringBuilder();
  static final int MAX=22;
  static boolean show(String s){
    return s.matches("(Section.*|SubSection.*|Control.*|Group|GroupingSummary|MainPage|Report|ReportDesign|TableCell.*|TableRow|TableColumn|TextInfo.*|Summary)");
  }
  static String scalars(Object o){
    StringBuilder b=new StringBuilder();
    String[] want={"getName","getText","getCaption","getValue","getFieldName","getFormula","getExpression","getContent"};
    for(String g: want){ try{ Method m=o.getClass().getMethod(g); Object v=m.invoke(o); if(v instanceof String && !((String)v).isEmpty()){ String s=((String)v).replaceAll("\\s+"," "); if(s.length()>70)s=s.substring(0,70)+"~"; b.append(" ").append(g.substring(3).toLowerCase()).append("=\"").append(s).append("\""); } }catch(Exception e){} }
    for(String g: new String[]{"getHeight","getLeft","getTop","getWidth"}){ try{ Method m=o.getClass().getMethod(g); Object v=m.invoke(o); b.append(" ").append(g.substring(3).toLowerCase()).append("=").append(v); }catch(Exception e){} }
    try{ Method m=o.getClass().getMethod("getVisible"); b.append(" vis=").append(m.invoke(o)); }catch(Exception e){}
    return b.toString();
  }
  static void walk(Object o,int depth){
    if(o==null) return;
    if(o instanceof RexObjectList){ RexObjectList<?> l=(RexObjectList<?>)o; for(int i=0;i<l.size();i++) walk(l.get(i),depth); return; }
    Class<?> c=o.getClass();
    if(!c.getName().startsWith("com.clipsoft.clipreport")) return;
    if(seen.contains(o)) return; seen.add(o);
    String simple=c.getSimpleName();
    int nd=depth;
    if(show(simple)){
      for(int i=0;i<depth;i++) out.append("  ");
      out.append(simple).append(scalars(o)).append("\n");
      nd=depth+1;
    }
    if(depth>MAX) return;
    // recurse getters (skip noisy ref types)
    if(simple.startsWith("RefInfomation")||simple.equals("RefObjectLinker")) return;
    Method[] ms=c.getMethods(); Arrays.sort(ms,(x,y)->x.getName().compareTo(y.getName()));
    for(Method m: ms){
      if(m.getParameterCount()!=0) continue;
      String n=m.getName(); if(!n.startsWith("get")||n.equals("getClass")) continue;
      Class<?> rt=m.getReturnType();
      if(rt.isPrimitive()||rt==String.class||rt.isEnum()||rt==Class.class) continue;
      if(n.equals("getParentObj")||n.equals("getRefInfomationStorage")) continue;
      try{ walk(m.invoke(o),nd); }catch(Throwable t){}
    }
  }
  public static void main(String[] a) throws Exception {
    TheReportFile rf=Rexpert4.read(a[0]);
    walk(rf.getGlobe().getMainReport(),0);
    System.out.println(out.toString());
  }
}
