import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.datas.*;
import com.clipsoft.clipreport.base.RexObjectList;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class CrfScanParams {
  public static void main(String[] a) throws Exception {
    String root=a[0]; int maxHits=Integer.parseInt(a[1]); int scanCap=Integer.parseInt(a[2]);
    List<Path> files;
    try(Stream<Path> s=Files.walk(Paths.get(root))){
      files=s.filter(p->p.toString().toLowerCase().endsWith(".crf")).limit(scanCap).collect(Collectors.toList());
    }
    int hits=0, scanned=0;
    for(Path p: files){
      if(hits>=maxHits) break;
      scanned++;
      try{
        TheReportFile rf=Rexpert4.read(p.toString());
        RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
        for(int i=0;i<dss.size();i++){
          DataSetItemNormal n=dss.get(i).getDataSetItemNormal();
          if(n==null) continue;
          DataAccessMethodSQL sql=n.getDataAccessMethodSQL();
          if(sql==null) continue;
          RexObjectList<SQLParameter> sps=sql.getSQLParameterList();
          String q=sql.getQueryString(); if(q==null) q="";
          boolean hasTok=q.toLowerCase().contains("{parameter.");
          boolean hasParam=(sps!=null && sps.size()>0);
          if(hasTok || hasParam){
            hits++;
            System.out.println("\n############ "+p.getFileName()+"  ds="+dss.get(i).getName()+"  scriptType="+sql.getScriptType());
            if(hasParam){ System.out.print("  SQLParameters: ");
              for(int k=0;k<sps.size();k++) System.out.print(sps.get(k).getParameterName()+"("+sps.get(k).getDataType()+")  ");
              System.out.println(); }
            RexObjectList<ScriptParameter> scps=sql.getScriptParameterList();
            if(scps!=null && scps.size()>0){ System.out.print("  ScriptParameters: ");
              for(int k=0;k<scps.size();k++) System.out.print(scps.get(k).getParameterName()+"  ");
              System.out.println(); }
            // print lines containing {parameter.
            String[] lines=q.split("\\r?\\n");
            int shown=0;
            for(String ln: lines){ if(ln.toLowerCase().contains("{parameter.") && shown<8){ System.out.println("   | "+ln.trim()); shown++; } }
            if(shown==0){ // show first 6 lines for context
              for(int li=0; li<Math.min(6,lines.length); li++) System.out.println("   . "+lines[li]); }
            if(hits>=maxHits) break;
          }
        }
      }catch(Throwable t){ /* skip unreadable */ }
    }
    System.out.println("\n==== scanned "+scanned+" files, hits "+hits+" ====");
  }
}
