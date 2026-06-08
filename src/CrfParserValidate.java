import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.datas.*;
import com.clipsoft.clipreport.base.datas.fields.FieldData;
import com.clipsoft.clipreport.base.RexObjectList;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/** Validate CrfGen2.parseColumns against ground truth: a report's actual FieldData list. */
public class CrfParserValidate {
  static PrintStream ps;
  // reconstruct SQL text from a JavaScript-assembled queryString by joining its string literals
  static String reconstruct(String q, String scriptType){
    if(q==null) return "";
    if(!"JavaScript".equalsIgnoreCase(scriptType)) return q;
    Matcher m=Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(q);
    StringBuilder sb=new StringBuilder();
    while(m.find()){
      String s=m.group(1).replace("\\r","\r").replace("\\n","\n").replace("\\t","\t").replace("\\\"","\"").replace("\\\\","\\");
      sb.append(s);
    }
    return sb.length()>0 ? sb.toString() : q;
  }
  static Set<String> upper(Collection<String> c){ Set<String> s=new HashSet<>(); for(String x:c) s.add(x.toUpperCase()); return s; }

  public static void main(String[] a) throws Exception {
    ps=new PrintStream(System.out,true,"UTF-8");
    String root=a[0]; int cap=a.length>1?Integer.parseInt(a[1]):500;
    List<Path> files; try(Stream<Path> st=Files.walk(Paths.get(root))){ files=st.filter(p->p.toString().toLowerCase().endsWith(".crf")).limit(cap).collect(Collectors.toList()); }

    int reports=0, datasets=0, exact=0, subset=0, mismatch=0, skipped=0;
    int nsTot=0, nsExact=0, jsTot=0, jsExact=0, starFunc=0, expTot=0, expExact=0;
    List<String> samples=new ArrayList<>();
    for(Path p: files){
      try{
        TheReportFile rf=Rexpert4.read(p.toString());
        RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
        boolean counted=false;
        for(int i=0;i<dss.size();i++){
          DataSet ds=dss.get(i);
          DataSetItemNormal n=ds.getDataSetItemNormal(); if(n==null) continue;
          DataAccessMethodSQL sql=n.getDataAccessMethodSQL(); if(sql==null) continue;
          RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
          if(fl.size()==0) continue;
          if(!counted){ reports++; counted=true; }
          datasets++;
          List<String> actual=new ArrayList<>(); for(int j=0;j<fl.size();j++) actual.add(fl.get(j).getName());
          String recon=reconstruct(sql.getQueryString(), ""+sql.getScriptType());
          List<String> parsed=CrfGen2.parseColumns(CrfGen2.stripComments(recon));
          if(parsed.isEmpty()){ skipped++; continue; }
          Set<String> A=upper(actual), P=upper(parsed);
          boolean isJS="JavaScript".equalsIgnoreCase(""+sql.getScriptType());
          boolean star=Pattern.compile("(?is)select\\s+\\*|\\bTABLE\\s*\\(").matcher(recon).find();
          if(star) starFunc++;
          if(isJS){ jsTot++; if(A.equals(P)) jsExact++; } else { nsTot++; if(A.equals(P)) nsExact++; }
          if(!star){ expTot++; if(A.equals(P)) expExact++; }
          if(A.equals(P)) exact++;
          else if(P.containsAll(A) || A.containsAll(P)) {
            subset++;
            if(samples.size()<12){ Set<String> miss=new TreeSet<>(A); miss.removeAll(P); Set<String> extra=new TreeSet<>(P); extra.removeAll(A);
              samples.add(String.format("SUBSET %-28s actual=%d parsed=%d miss=%s extra=%s", p.getFileName(), A.size(), P.size(), miss, extra)); }
          }
          else {
            mismatch++;
            if(samples.size()<12){ Set<String> miss=new TreeSet<>(A); miss.removeAll(P); Set<String> extra=new TreeSet<>(P); extra.removeAll(A);
              samples.add(String.format("MISMATCH %-26s actual=%d parsed=%d miss=%s extra=%s", p.getFileName(), A.size(), P.size(), miss, extra)); }
          }
        }
      }catch(Throwable t){ /* unreadable */ }
    }
    int total=exact+subset+mismatch;
    ps.println("=== Parser validation ===");
    ps.println("reports(with SQL dataset)="+reports+"  datasets compared="+datasets+"  (parsed-empty skipped="+skipped+")");
    ps.println(String.format("EXACT    = %d (%.1f%%)", exact, total>0?100.0*exact/total:0));
    ps.println(String.format("SUBSET   = %d (%.1f%%)  (one side contains the other)", subset, total>0?100.0*subset/total:0));
    ps.println(String.format("MISMATCH = %d (%.1f%%)", mismatch, total>0?100.0*mismatch/total:0));
    ps.println("\n=== by query type (the honest split) ===");
    ps.println(String.format("NotScript (clean SQL)  : %d/%d exact = %.1f%%  <- true parser accuracy", nsExact, nsTot, nsTot>0?100.0*nsExact/nsTot:0));
    ps.println(String.format("JavaScript (reconstructed): %d/%d exact = %.1f%%  (limited by JS-blob reconstruction)", jsExact, jsTot, jsTot>0?100.0*jsExact/jsTot:0));
    ps.println(String.format("contains SELECT * / TABLE(func) (cols not in SQL): %d datasets", starFunc));
    ps.println(String.format("EXPLICIT column-list queries only: %d/%d exact = %.1f%%  <- parser accuracy where parsing is even possible", expExact, expTot, expTot>0?100.0*expExact/expTot:0));
    ps.println("\n--- examples ---"); for(String s: samples) ps.println(s);
  }
}
