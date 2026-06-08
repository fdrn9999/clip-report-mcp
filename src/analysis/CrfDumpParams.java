import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.reports.ReportObjectManager;
import com.clipsoft.clipreport.base.RexObjectList;
import java.lang.reflect.*;

public class CrfDumpParams {
  static String nm(Object o){ try{ return ""+o.getClass().getMethod("getName").invoke(o);}catch(Exception e){return "?";} }
  static void list(String label, RexObjectList<?> l){
    System.out.print(label+" ("+(l==null?0:l.size())+"): ");
    if(l!=null) for(int i=0;i<l.size();i++) System.out.print(nm(l.get(i))+"  ");
    System.out.println();
  }
  public static void main(String[] a) throws Exception {
    TheReportFile rf=Rexpert4.read(a[0]);
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    ReportObjectManager rom=rf.getGlobe().getMainReport().getReportObjectManager();
    System.out.println("FILE "+a[0]);
    list("GLOBAL FieldGlobalParameterList", gom.getFieldGlobalParameterList());
    list("REPORT FieldReportParameterList", rom.getFieldReportParameterList());
    // dump one FieldParameter's full members to learn structure
    RexObjectList<?> rp=rom.getFieldReportParameterList();
    if(rp!=null && rp.size()>0){
      Object p=rp.get(0);
      System.out.println("\n-- FieldReportParameter["+nm(p)+"] members --");
      for(Method m: p.getClass().getMethods()){
        if(m.getParameterCount()==0 && m.getName().startsWith("get") && !m.getName().equals("getClass")){
          Class<?> rt=m.getReturnType();
          if(rt.isPrimitive()||rt==String.class||rt.isEnum()){ try{ System.out.println("   "+m.getName()+" = "+m.invoke(p)); }catch(Exception e){} }
        }
      }
    }
  }
}
