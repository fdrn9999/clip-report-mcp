import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.datas.DataSet;
import com.clipsoft.clipreport.base.datas.DataSetItemNormal;
import com.clipsoft.clipreport.base.datas.DataAccessMethodSQL;
import com.clipsoft.clipreport.base.RexObjectList;
import java.io.File;

public class CrfRoundTrip {
  static String summarize(TheReportFile rf) throws Exception {
    StringBuilder sb = new StringBuilder();
    GlobalObjectManager gom = rf.getGlobe().getGlobalObjectManager();
    RexObjectList<DataSet> dss = gom.getDataSetList();
    sb.append("datasets=").append(dss.size());
    for (int i=0;i<dss.size();i++){
      DataSet ds=dss.get(i);
      sb.append(" | DS[").append(i).append("]=").append(ds.getName());
      RexObjectList<?> f=ds.getFieldDataList();
      sb.append(" fields=").append(f.size());
      DataSetItemNormal n=ds.getDataSetItemNormal();
      if(n!=null){
        DataAccessMethodSQL q=n.getDataAccessMethodSQL();
        if(q!=null){
          sb.append(" scriptType=").append(q.getScriptType());
          String s=q.getQueryString();
          sb.append(" qlen=").append(s==null?0:s.length());
          sb.append(" hasMarker=").append(s!=null && s.contains("ROUNDTRIP_MARKER_42"));
        }
      }
    }
    return sb.toString();
  }
  public static void main(String[] a) throws Exception {
    String in=a[0], out=a[1];
    TheReportFile rf = Rexpert4.read(in);
    System.out.println("[1] ORIGINAL : " + summarize(rf));

    // round-trip write (no change)
    String outCopy = out + ".copy.crf";
    Rexpert4.write(rf, outCopy);
    TheReportFile rf2 = Rexpert4.read(outCopy);
    System.out.println("[2] COPY     : " + summarize(rf2));
    System.out.println("    sizes: orig=" + new File(in).length() + "  copy=" + new File(outCopy).length());

    // modify queryString of first SQL dataset, then write+reread
    GlobalObjectManager gom = rf.getGlobe().getGlobalObjectManager();
    RexObjectList<DataSet> dss = gom.getDataSetList();
    boolean modified=false;
    for(int i=0;i<dss.size() && !modified;i++){
      DataSetItemNormal n=dss.get(i).getDataSetItemNormal();
      if(n==null) continue;
      DataAccessMethodSQL q=n.getDataAccessMethodSQL();
      if(q==null) continue;
      String s=q.getQueryString(); if(s==null) s="";
      q.setQueryString(s + "\r\n-- ROUNDTRIP_MARKER_42\r\n");
      modified=true;
    }
    System.out.println("[3] modified queryString? " + modified);
    String outMod = out + ".mod.crf";
    Rexpert4.write(rf, outMod);
    TheReportFile rf3 = Rexpert4.read(outMod);
    System.out.println("[4] MODIFIED : " + summarize(rf3));
    System.out.println("    sizes: mod=" + new File(outMod).length());
    System.out.println("DONE. outputs: " + outCopy + " , " + outMod);
  }
}
