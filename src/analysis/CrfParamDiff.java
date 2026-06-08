import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.datas.fields.FieldParameter;
import com.clipsoft.clipreport.base.enums.DataType;
import com.clipsoft.clipreport.base.RexObjectList;
import java.lang.reflect.*;
import java.util.*;

public class CrfParamDiff {
  static Map<String,String> dump(Object o) throws Exception {
    Map<String,String> m=new LinkedHashMap<>();
    for(Class<?> c=o.getClass(); c!=null && c.getName().startsWith("com.clipsoft"); c=c.getSuperclass()){
      for(Field f: c.getDeclaredFields()){
        if(Modifier.isStatic(f.getModifiers())) continue;
        f.setAccessible(true);
        Object v; try{ v=f.get(o);}catch(Exception e){ v="<err>"; }
        String cls=c.getSimpleName();
        m.put(cls+"."+f.getName(), v==null?"null":(v.getClass().getSimpleName()+"="+String.valueOf(v).replaceAll("\\s+"," ")));
      }
    }
    return m;
  }
  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    TheReportFile rf=Rexpert4.read(a[0]);
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    RexObjectList<FieldParameter> gp=(RexObjectList<FieldParameter>) gom.getFieldGlobalParameterList();
    System.out.println("template global params: "+gp.size());
    FieldParameter ex=gp.get(0);
    FieldParameter nw=new FieldParameter(); nw.setName("NEWP"); nw.setDataType(DataType.String); nw.setDefaultValue(""); nw.setPrompt("NEWP"); nw.setValueIsNull(Boolean.FALSE);
    Map<String,String> A=dump(ex), B=dump(nw);
    TreeSet<String> keys=new TreeSet<>(); keys.addAll(A.keySet()); keys.addAll(B.keySet());
    System.out.printf("%-40s | %-30s | %-30s%n","FIELD","EXISTING("+ex.getName()+")","NEW");
    for(String k: keys){ String x=A.getOrDefault(k,"<absent>"), y=B.getOrDefault(k,"<absent>");
      String mark = x.equals(y)?"   ":">>>";
      System.out.printf("%s %-36s | %-30s | %-30s%n",mark,k,x,y); }
  }
}
