import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.RexObjectList;
import java.lang.reflect.*;
import java.util.*;

public class CrfHistogram {
  static Map<String,Integer> hist = new TreeMap<>();
  static Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
  static int visited=0;
  static final int CAP=2000000;

  static void walk(Object o){
    if(o==null) return;
    if(visited>CAP) return;
    if(o instanceof RexObjectList){
      RexObjectList<?> l=(RexObjectList<?>)o;
      for(int i=0;i<l.size();i++) walk(l.get(i));
      return;
    }
    Class<?> c=o.getClass();
    if(!c.getName().startsWith("com.clipsoft.clipreport")) return;
    if(seen.contains(o)) return;
    seen.add(o); visited++;
    String k=c.getSimpleName();
    hist.merge(k,1,Integer::sum);
    for(Method m: c.getMethods()){
      if(m.getParameterCount()!=0) continue;
      String n=m.getName();
      if(!n.startsWith("get")) continue;
      if(n.equals("getClass")) continue;
      Class<?> rt=m.getReturnType();
      if(rt.isPrimitive()||rt==String.class||rt.isEnum()) continue;
      if(rt==Class.class) continue;
      try{ Object r=m.invoke(o); walk(r); }catch(Throwable t){}
    }
  }
  static Map<String,Integer> run(String path) throws Exception {
    hist=new TreeMap<>(); seen=Collections.newSetFromMap(new IdentityHashMap<>()); visited=0;
    TheReportFile rf=Rexpert4.read(path);
    walk(rf.getGlobe());
    return hist;
  }
  public static void main(String[] a) throws Exception {
    Map<String,Integer> A=run(a[0]);
    Map<String,Integer> B=run(a[1]);
    int ta=A.values().stream().mapToInt(Integer::intValue).sum();
    int tb=B.values().stream().mapToInt(Integer::intValue).sum();
    System.out.println("TOTAL objects: orig="+ta+"  copy="+tb);
    System.out.println("\n--- DIFF (only classes where counts differ) ---");
    TreeSet<String> keys=new TreeSet<>(); keys.addAll(A.keySet()); keys.addAll(B.keySet());
    for(String k: keys){
      int x=A.getOrDefault(k,0), y=B.getOrDefault(k,0);
      if(x!=y) System.out.printf("  %-32s orig=%-5d copy=%-5d  (%+d)%n",k,x,y,(y-x));
    }
    System.out.println("\n--- top classes (orig) ---");
    A.entrySet().stream().sorted((p,q)->q.getValue()-p.getValue()).limit(25)
      .forEach(e->System.out.printf("  %-32s %d%n",e.getKey(),e.getValue()));
  }
}
