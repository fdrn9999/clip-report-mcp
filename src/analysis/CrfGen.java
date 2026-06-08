import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.datas.DataSet;
import com.clipsoft.clipreport.base.datas.DataSetItemNormal;
import com.clipsoft.clipreport.base.datas.DataAccessMethodSQL;
import com.clipsoft.clipreport.base.datas.SQLParameter;
import com.clipsoft.clipreport.base.datas.fields.FieldData;
import com.clipsoft.clipreport.base.enums.DataType;
import com.clipsoft.clipreport.base.enums.ScriptType;
import com.clipsoft.clipreport.base.RexObjectList;
import java.nio.file.*;
import java.util.*;

/** End-to-end skeleton: SQL -> template.crf -> fields+query injected -> output.crf
 *  usage: CrfGen <template.crf> <query.sql> <output.crf>
 */
public class CrfGen {

  // ---- strip a -- line comment, respecting single-quoted strings ----
  static String removeLineComment(String line){
    boolean q=false;
    for(int i=0;i<line.length();i++){
      char c=line.charAt(i);
      if(c=='\'') q=!q;
      if(!q && c=='-' && i+1<line.length() && line.charAt(i+1)=='-') return line.substring(0,i);
    }
    return line;
  }
  static String stripComments(String s){
    s=s.replaceAll("(?s)/\\*.*?\\*/","");
    StringBuilder o=new StringBuilder();
    for(String ln: s.split("\n")) o.append(removeLineComment(ln)).append("\n");
    return o.toString();
  }
  static String stripMyBatisTags(String s){
    s=s.replace("<![CDATA[","").replace("]]>","");
    return s.replaceAll("(?s)<[^>]+>"," ");   // drop <if>,<where>,<select>,<foreach>...
  }

  // ---- extract the top-level SELECT...FROM column list (quote+paren aware) ----
  static String selectList(String sql){
    String up=sql.toUpperCase();
    int sel=indexOfKeyword(up,"SELECT",0,0);
    if(sel<0) return null;
    int start=sel+6;
    int depth=0; boolean q=false;
    for(int i=start;i<sql.length();i++){
      char c=sql.charAt(i);
      if(c=='\'') q=!q;
      if(q) continue;
      if(c=='(') depth++;
      else if(c==')') depth--;
      else if(depth==0 && matchWord(up,i,"FROM")) return sql.substring(start,i);
    }
    return sql.substring(start);
  }
  static boolean matchWord(String up,int i,String kw){
    if(!up.startsWith(kw,i)) return false;
    boolean lb = i==0 || !Character.isLetterOrDigit(up.charAt(i-1)) && up.charAt(i-1)!='_';
    int e=i+kw.length();
    boolean rb = e>=up.length() || !Character.isLetterOrDigit(up.charAt(e)) && up.charAt(e)!='_';
    return lb && rb;
  }
  static int indexOfKeyword(String up,String kw,int from,int wantDepth){
    int depth=0; boolean q=false;
    for(int i=from;i<up.length();i++){
      char c=up.charAt(i);
      if(c=='\'') q=!q;
      if(q) continue;
      if(c=='(') depth++; else if(c==')') depth--;
      else if(depth==wantDepth && matchWord(up,i,kw)) return i;
    }
    return -1;
  }
  static List<String> splitTopLevel(String list){
    List<String> out=new ArrayList<>(); int depth=0; boolean q=false; int last=0;
    for(int i=0;i<list.length();i++){ char c=list.charAt(i);
      if(c=='\'') q=!q;
      if(q) continue;
      if(c=='(') depth++; else if(c==')') depth--;
      else if(c==',' && depth==0){ out.add(list.substring(last,i)); last=i+1; } }
    out.add(list.substring(last));
    return out;
  }
  static String colName(String item){
    String t=item.trim().replaceAll("\\s+"," ");
    if(t.isEmpty()) return null;
    String up=t.toUpperCase();
    int as=lastTopLevel(up," AS ");
    String name;
    if(as>=0) name=t.substring(as+4).trim();
    else {
      // no AS: take last token; if function/expr -> null
      String tail=t.substring(t.replaceAll("[A-Za-z0-9_\\.\\[\\]\"`]+$","").length());
      name = tail;
    }
    name=name.replaceAll("[\"`\\[\\]]","").trim();
    int dot=name.lastIndexOf('.'); if(dot>=0) name=name.substring(dot+1);
    if(!name.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
    return name;
  }
  static int lastTopLevel(String up,String needle){
    int depth=0; boolean q=false; int found=-1;
    for(int i=0;i+needle.length()<=up.length();i++){ char c=up.charAt(i);
      if(c=='\'') q=!q; if(q) continue;
      if(c=='(') depth++; else if(c==')') depth--;
      else if(depth==0 && up.startsWith(needle,i)) found=i; }
    return found;
  }
  static List<String> parseColumns(String sql){
    String list=selectList(sql); List<String> cols=new ArrayList<>();
    if(list==null) return cols;
    int i=0;
    for(String item: splitTopLevel(list)){ i++;
      String n=colName(item);
      if(n==null) n="COL_"+i;
      if(!cols.contains(n)) cols.add(n);
    }
    return cols;
  }
  static List<String> parseGroupBy(String sql){
    String up=sql.toUpperCase(); int g=indexOfKeyword(up,"GROUP",0,0);
    List<String> out=new ArrayList<>();
    if(g<0) return out;
    int by=indexOfKeyword(up,"BY",g,0); if(by<0) return out;
    int end=sql.length();
    for(String kw: new String[]{"ORDER","HAVING","LIMIT"}){ int k=indexOfKeyword(up,kw,by,0); if(k>=0&&k<end) end=k; }
    for(String item: splitTopLevel(sql.substring(by+2,end))){
      String n=item.trim(); int dot=n.lastIndexOf('.'); if(dot>=0) n=n.substring(dot+1);
      n=n.replaceAll("[^A-Za-z0-9_]","");
      if(n.matches("[A-Za-z_][A-Za-z0-9_]*")) out.add(n);
    }
    return out;
  }
  static List<String> parseParams(String sql){
    List<String> out=new ArrayList<>();
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("[#$]\\{\\s*([A-Za-z_][A-Za-z0-9_\\.]*)").matcher(sql);
    while(m.find()){ String p=m.group(1); if(!out.contains(p)) out.add(p); }
    return out;
  }
  static DataType guessType(String name){
    String u=name.toUpperCase();
    if(u.matches(".*(AMT|AMOUNT|PRICE|SUM|TOT|PAY|SAL).*")) return DataType.Currency;
    if(u.matches(".*(CNT|COUNT|QTY|NUM|SEQ|NO)$")||u.matches(".*(CNT|COUNT|QTY)" )) return DataType.Number;
    if(u.matches(".*(YMD|YM|DATE|DT|YYYY).*")) return DataType.DateTime;
    return DataType.String;
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    String template=a[0], sqlFile=a[1], out=a[2];
    String raw=new String(Files.readAllBytes(Paths.get(sqlFile)), java.nio.charset.StandardCharsets.UTF_8);

    String forCols=stripComments(stripMyBatisTags(raw));
    List<String> cols=parseColumns(forCols);
    List<String> groups=parseGroupBy(forCols);
    List<String> params=parseParams(raw);
    boolean mybatis = raw.matches("(?s).*<(if|where|foreach|choose|trim|select)\\b.*");
    System.out.println("PARSED columns("+cols.size()+"): "+cols);
    System.out.println("PARSED group by    : "+groups);
    System.out.println("PARSED params #{}  : "+params);
    System.out.println("MyBatis detected   : "+mybatis);

    // queryString + scriptType (PoC: plain SQL -> NotScript; mybatis -> very basic JS note)
    String queryString = stripComments(raw).trim();
    ScriptType stype = ScriptType.NotScript;

    TheReportFile rf = Rexpert4.read(template);
    GlobalObjectManager gom = rf.getGlobe().getGlobalObjectManager();
    DataSet ds = gom.getDataSetList().get(0);
    System.out.println("\nTemplate dataset: "+ds.getName());

    // rebuild field list to match query columns
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    fl.removeAll();
    for(int i=0;i<cols.size();i++){ FieldData f=new FieldData(); f.setName(cols.get(i)); f.setDataType(guessType(cols.get(i))); f.setIndex(i); fl.add(f); }

    // inject SQL + params
    DataSetItemNormal n=ds.getDataSetItemNormal();
    DataAccessMethodSQL sql=n.getDataAccessMethodSQL();
    sql.setScriptType(stype);
    sql.setQueryString(queryString);
    RexObjectList<SQLParameter> sps=sql.getSQLParameterList();
    if(sps!=null){ sps.removeAll(); for(String p:params){ SQLParameter sp=new SQLParameter(); sp.setParameterName(p); sp.setDataType(DataType.String); sps.add(sp);} }

    Rexpert4.write(rf, out);

    // verify
    TheReportFile rf2=Rexpert4.read(out);
    DataSet ds2=rf2.getGlobe().getGlobalObjectManager().getDataSetList().get(0);
    RexObjectList<FieldData> fl2=(RexObjectList<FieldData>) ds2.getFieldDataList();
    DataAccessMethodSQL sql2=ds2.getDataSetItemNormal().getDataAccessMethodSQL();
    System.out.println("\n===== VERIFY (reloaded "+out+") =====");
    System.out.println("scriptType="+sql2.getScriptType()+"  queryLen="+sql2.getQueryString().length()+"  sqlParams="+(sql2.getSQLParameterList()==null?0:sql2.getSQLParameterList().size()));
    System.out.println("fields("+fl2.size()+"):");
    for(int i=0;i<fl2.size();i++) System.out.printf("   %-14s %s%n", fl2.get(i).getName(), fl2.get(i).getDataType());
  }
}
