import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.datas.DataSet;
import com.clipsoft.clipreport.base.datas.DataSetItemNormal;
import com.clipsoft.clipreport.base.datas.DataAccessMethodSQL;
import com.clipsoft.clipreport.base.datas.ScriptParameter;
import com.clipsoft.clipreport.base.datas.SQLParameter;
import com.clipsoft.clipreport.base.RexObjectList;

public class CrfDump {
  static String nameOf(Object o){
    if(o==null) return "(null)";
    try { Object r=o.getClass().getMethod("getName").invoke(o); return r==null?"(null)":r.toString(); }
    catch(Exception e){ return "?"; }
  }
  public static void main(String[] args) throws Exception {
    String path = args[0];
    System.out.println("### FILE: " + path);
    TheReportFile rf = Rexpert4.read(path);
    System.out.println("### title=" + rf.getTitle() + "  version=" + rf.getVersion());
    GlobalObjectManager gom = rf.getGlobe().getGlobalObjectManager();
    RexObjectList<DataSet> dss = gom.getDataSetList();
    System.out.println("### DataSet count = " + dss.size());
    for(int i=0;i<dss.size();i++){
      DataSet ds = dss.get(i);
      System.out.println("\n========== DataSet["+i+"] name=" + ds.getName() + "  type=" + ds.getDataSetType());
      RexObjectList<?> fields = ds.getFieldDataList();
      System.out.print("  fields("+fields.size()+"): ");
      StringBuilder fb=new StringBuilder();
      for(int j=0;j<fields.size();j++){ Object f=fields.get(j); fb.append(nameOf(f));
        try{ Object dt=f.getClass().getMethod("getDataType").invoke(f); fb.append(":").append(dt);}catch(Exception e){}
        fb.append("  "); }
      System.out.println(fb.toString());
      DataSetItemNormal n = ds.getDataSetItemNormal();
      if(n==null){ System.out.println("  (no DataSetItemNormal)"); continue; }
      System.out.println("  dataAccessMethod = " + n.getDataAccessMethod());
      DataAccessMethodSQL sql = n.getDataAccessMethodSQL();
      if(sql==null){ System.out.println("  (no SQL access method)"); continue; }
      System.out.println("  scriptType = " + sql.getScriptType());
      RexObjectList<SQLParameter> sps = sql.getSQLParameterList();
      if(sps!=null && sps.size()>0){ System.out.println("  SQLParameters("+sps.size()+"):");
        for(int k=0;k<sps.size();k++){ SQLParameter p=sps.get(k); System.out.println("     - "+p.getParameterName()+" type="+p.getDataType()+" default="+p.getDefaultValue()); } }
      RexObjectList<ScriptParameter> scs = sql.getScriptParameterList();
      if(scs!=null && scs.size()>0){ System.out.println("  ScriptParameters("+scs.size()+"):");
        for(int k=0;k<scs.size();k++){ ScriptParameter p=scs.get(k); System.out.println("     - "+p.getParameterName()+" = "+p.getPaarameterValue()); } }
      String pre=sql.getPreSQL(), post=sql.getPostSQL();
      if(pre!=null && pre.trim().length()>0) System.out.println("  preSQL: "+pre);
      if(post!=null && post.trim().length()>0) System.out.println("  postSQL: "+post);
      System.out.println("  --- queryString ---");
      System.out.println(sql.getQueryString());
      System.out.println("  --- end query ---");
    }
  }
}
