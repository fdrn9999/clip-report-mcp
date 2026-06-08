import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.datas.*;
import com.clipsoft.clipreport.base.datas.fields.FieldData;
import com.clipsoft.clipreport.base.datas.fields.FieldParameter;
import com.clipsoft.clipreport.base.datas.fields.FieldGlobalParameter;
import com.clipsoft.clipreport.base.enums.DataType;
import com.clipsoft.clipreport.base.enums.ScriptType;
import com.clipsoft.clipreport.base.RexObjectList;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** v2: SQL or MyBatis -> .crf. Adds {parameter.X} tokens + global FieldParameters,
 *  and MyBatis(<if>/<where>/#{}/${}) -> JavaScript dynamic query. */
public class CrfGen2 {
  // ---------- shared SQL parsing (column / groupby / params) ----------
  static String removeLineComment(String line){ boolean q=false; for(int i=0;i<line.length();i++){char c=line.charAt(i); if(c=='\'')q=!q; if(!q&&c=='-'&&i+1<line.length()&&line.charAt(i+1)=='-')return line.substring(0,i);} return line; }
  static String stripComments(String s){ s=s.replaceAll("(?s)/\\*.*?\\*/",""); StringBuilder o=new StringBuilder(); for(String ln:s.split("\n"))o.append(removeLineComment(ln)).append("\n"); return o.toString(); }
  static String stripTags(String s){ s=s.replace("<![CDATA[","").replace("]]>",""); return s.replaceAll("(?s)<[^>]+>"," "); }
  static boolean matchWord(String up,int i,String kw){ if(!up.startsWith(kw,i))return false; boolean lb=i==0||(!Character.isLetterOrDigit(up.charAt(i-1))&&up.charAt(i-1)!='_'); int e=i+kw.length(); boolean rb=e>=up.length()||(!Character.isLetterOrDigit(up.charAt(e))&&up.charAt(e)!='_'); return lb&&rb; }
  static int indexOfKeyword(String up,String kw,int from){ int d=0; boolean q=false; for(int i=from;i<up.length();i++){char c=up.charAt(i); if(c=='\'')q=!q; if(q)continue; if(c=='(')d++; else if(c==')')d--; else if(d==0&&matchWord(up,i,kw))return i;} return -1; }
  static String selectList(String sql){ String up=sql.toUpperCase(); int sel=indexOfKeyword(up,"SELECT",0); if(sel<0)return null; int st=sel+6,d=0; boolean q=false; for(int i=st;i<sql.length();i++){char c=sql.charAt(i); if(c=='\'')q=!q; if(q)continue; if(c=='(')d++; else if(c==')')d--; else if(d==0&&matchWord(up,i,"FROM"))return sql.substring(st,i);} return sql.substring(st); }
  static List<String> splitTop(String s){ List<String> o=new ArrayList<>(); int d=0; boolean q=false,last=false; int lp=0; for(int i=0;i<s.length();i++){char c=s.charAt(i); if(c=='\'')q=!q; if(q)continue; if(c=='(')d++; else if(c==')')d--; else if(c==','&&d==0){o.add(s.substring(lp,i)); lp=i+1;}} o.add(s.substring(lp)); return o; }
  static int lastTop(String up,String needle){ int d=0; boolean q=false,r=-1>0?true:false; int f=-1; for(int i=0;i+needle.length()<=up.length();i++){char c=up.charAt(i); if(c=='\'')q=!q; if(q)continue; if(c=='(')d++; else if(c==')')d--; else if(d==0&&up.startsWith(needle,i))f=i;} return f; }
  static String colName(String item){
    String t=item.trim().replaceAll("\\s+"," "); if(t.isEmpty())return null;
    String up=t.toUpperCase(); int as=lastTop(up," AS ");
    String name = (as>=0) ? t.substring(as+4).trim() : null;
    if(name==null){ String tail=t.substring(t.replaceAll("[A-Za-z0-9_가-힣\\.\\[\\]\"'`]+$","").length()); name=tail.trim(); }
    if(name.length()>=2){ char a0=name.charAt(0), a1=name.charAt(name.length()-1);
      if((a0=='"'&&a1=='"')||(a0=='\''&&a1=='\'')||(a0=='`'&&a1=='`')||(a0=='['&&a1==']')) name=name.substring(1,name.length()-1).trim(); }
    int dot=name.lastIndexOf('.'); if(dot>=0)name=name.substring(dot+1);
    name=name.replaceAll("[\"'`\\[\\]]","").trim();
    if(name.matches("[A-Za-z_가-힣][A-Za-z0-9_가-힣]*")) return name;  // allow Hangul / quoted aliases
    return null;
  }
  static List<String> parseColumns(String sql){ String list=selectList(sql); List<String> c=new ArrayList<>(); if(list==null)return c;
    List<String> items=splitTop(list);
    for(String it:items){ String tt=it.trim(); if(tt.equals("*")||tt.endsWith(".*")) return c; }  // SELECT * -> not derivable from SQL (need runtime metadata)
    int i=0; for(String it:items){i++; String n=colName(it); if(n==null)n="COL_"+i; if(!c.contains(n))c.add(n);} return c; }
  static List<String> parseParams(String s){ List<String> o=new ArrayList<>(); Matcher m=Pattern.compile("[#$]\\{\\s*([A-Za-z_][A-Za-z0-9_]*)").matcher(s); while(m.find()){ if(!o.contains(m.group(1)))o.add(m.group(1)); } return o; }
  static DataType guessType(String n){ String u=n.toUpperCase(); if(u.matches(".*(AMT|AMOUNT|PRICE|SUM|TOT|PAY|SAL).*"))return DataType.Currency; if(u.matches(".*(CNT|COUNT|QTY|NUM|SEQ)$"))return DataType.Number; if(u.matches(".*(YMD|YM|DATE|DT)$"))return DataType.DateTime; return DataType.String; }

  // ---------- token substitution: #{x}->'{parameter.x}'  ${x}->{parameter.x} ----------
  static String subParamsQuoted(String s){
    s=s.replaceAll("#\\{\\s*([A-Za-z_][A-Za-z0-9_]*)[^}]*\\}","'{parameter.$1}'");
    s=s.replaceAll("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)[^}]*\\}","{parameter.$1}");
    s=s.replaceAll("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)","'{parameter.$1}'");   // :colNm (Oracle/JDBC bind) -> CLIP token
    return s;
  }
  // ---------- report param name = screen name UPPERCASED, underscores preserved ----------
  // empNm -> EMPNM ; emp_nm / EMP_NM -> EMP_NM. DEFAULT: upper. Override: env CLIP_PARAM_MODE = upper | asis | uppernosep
  static String PARAM_MODE = System.getenv().getOrDefault("CLIP_PARAM_MODE","upper");
  static String rp(String n){
    if("asis".equals(PARAM_MODE)) return n;
    if("uppernosep".equals(PARAM_MODE)) return n.replaceAll("[^A-Za-z0-9]","").toUpperCase();
    return n.toUpperCase(); // upper (default): uppercase, keep underscores
  }
  static String normParamTokens(String s){
    if("asis".equals(PARAM_MODE)) return s; // preserve
    Matcher m=Pattern.compile("\\{parameter\\.([A-Za-z0-9_]+)\\}").matcher(s);
    StringBuffer b=new StringBuffer();
    while(m.find()) m.appendReplacement(b, Matcher.quoteReplacement("{parameter."+rp(m.group(1))+"}"));
    m.appendTail(b); return b.toString();
  }
  // ---------- MyBatis test -> JS condition ----------
  static String convertCond(String t){
    t=t.trim();
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*!=\\s*null","'{parameter.$1}' != ''");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*null","'{parameter.$1}' == ''");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*!=\\s*''","'{parameter.$1}' != ''");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*''","'{parameter.$1}' == ''");
    t=t.replaceAll("\\band\\b"," && ").replaceAll("\\bor\\b"," || ").replaceAll("\\s+"," ").trim();
    return t;
  }
  static String escLine(String s){ return s.replace("\\","\\\\").replace("\"","\\\""); }
  // ---------- MyBatis -> JavaScript builder ----------
  static String mybatisToJs(String mb, List<String> warns){
    String body=mb;
    Matcher sel=Pattern.compile("(?is)<select[^>]*>(.*)</select>").matcher(mb);
    if(sel.find()) body=sel.group(1);
    body=body.replace("<![CDATA[","").replace("]]>","");
    StringBuilder js=new StringBuilder("var sql = \"\";\r\n");
    Matcher m=Pattern.compile("<[^>]+>").matcher(body);
    int pos=0;
    while(m.find()){
      String text=body.substring(pos,m.start());
      emitText(js,text);
      String tag=m.group().trim();
      String low=tag.toLowerCase();
      if(low.startsWith("<where")) js.append("sql += \" WHERE 1=1 \\r\\n\";\r\n");
      else if(low.startsWith("</where")||low.startsWith("</set")||low.startsWith("</trim")||low.startsWith("</select")) {}
      else if(low.startsWith("<if")){ Matcher t=Pattern.compile("test\\s*=\\s*\"([^\"]*)\"").matcher(tag); String c=t.find()?convertCond(t.group(1)):"true"; js.append("if(").append(c).append("){\r\n"); }
      else if(low.startsWith("</if")) js.append("}\r\n");
      else if(low.startsWith("<choose")) {}
      else if(low.startsWith("<when")){ Matcher t=Pattern.compile("test\\s*=\\s*\"([^\"]*)\"").matcher(tag); String c=t.find()?convertCond(t.group(1)):"true"; js.append("if(").append(c).append("){\r\n"); warns.add("<when> mapped to if (choose/when else-if semantics approximated)"); }
      else if(low.startsWith("</when")) js.append("}\r\n");
      else if(low.startsWith("<otherwise")){ js.append("if(true){\r\n"); warns.add("<otherwise> mapped to if(true)"); }
      else if(low.startsWith("</otherwise")) js.append("}\r\n");
      else if(low.startsWith("</choose")) {}
      else if(low.startsWith("<foreach")||low.startsWith("</foreach")) warns.add("<foreach> NOT converted (manual JS loop needed): "+tag);
      else if(low.startsWith("<select")) {}
      else warns.add("unhandled tag: "+tag);
      pos=m.end();
    }
    emitText(js, body.substring(pos));
    return js.toString();
  }
  static void emitText(StringBuilder js,String text){
    if(text==null) return;
    String t=subParamsQuoted(text);
    String[] lines=t.replace("\r\n","\n").replace("\r","\n").split("\n",-1);
    for(String ln: lines){ if(ln.trim().isEmpty()) continue; js.append("sql += \"").append(escLine(ln)).append("\\r\\n\";\r\n"); }
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    String template=a[0], inFile=a[1], out=a[2];
    String raw=new String(Files.readAllBytes(Paths.get(inFile)), java.nio.charset.StandardCharsets.UTF_8);
    boolean mybatis = Pattern.compile("(?is)<(if|where|foreach|choose|trim|set|select)\\b").matcher(raw).find();

    List<String> cols=parseColumns(stripComments(stripTags(raw)));
    List<String> params=parseParams(raw);
    List<String> warns=new ArrayList<>();
    String queryString; ScriptType stype;
    if(mybatis){ queryString=mybatisToJs(raw,warns); stype=ScriptType.JavaScript; }
    else { queryString=subParamsQuoted(stripComments(raw)).trim(); stype=ScriptType.NotScript; }
    queryString=normParamTokens(queryString);   // empNm -> EMPNM in {parameter.*} tokens

    System.out.println("MyBatis="+mybatis+"  scriptType="+stype);
    System.out.println("columns("+cols.size()+"): "+cols);
    System.out.println("params ("+params.size()+"): "+params);
    if(!warns.isEmpty()) System.out.println("WARN: "+warns);
    System.out.println("\n----- generated queryString -----\n"+queryString+"\n---------------------------------");

    TheReportFile rf=Rexpert4.read(template);
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    DataSet ds=gom.getDataSetList().get(0);

    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    fl.removeAll();
    for(int i=0;i<cols.size();i++){ FieldData f=new FieldData(); f.setName(cols.get(i)); f.setDataType(guessType(cols.get(i))); f.setIndex(i); fl.add(f); }

    DataAccessMethodSQL sql=ds.getDataSetItemNormal().getDataAccessMethodSQL();
    sql.setScriptType(stype); sql.setQueryString(queryString);

    // global parameters for {parameter.X}
    boolean CREATE_PARAMS = a.length>3 && a[3].equals("params");
    RexObjectList<FieldParameter> gp=(RexObjectList<FieldParameter>) gom.getFieldGlobalParameterList();
    if(CREATE_PARAMS){
      Set<String> existing=new HashSet<>(); for(int i=0;i<gp.size();i++) existing.add(gp.get(i).getName());
      for(String p: params){ String pn=rp(p); if(existing.contains(pn)) continue; existing.add(pn); FieldGlobalParameter fp=new FieldGlobalParameter(); fp.setName(pn); fp.setDataType(DataType.String); fp.setDefaultValue(""); fp.setValueIsNull(Boolean.FALSE); fp.setPrompt(p); fp.setTag(""); gp.add(fp); }
    } else {
      System.out.println("[params NOT created in .crf; declare in Designer: "+params+"]");
    }

    Rexpert4.write(rf,out);

    // verify
    TheReportFile rf2=Rexpert4.read(out);
    GlobalObjectManager g2=rf2.getGlobe().getGlobalObjectManager();
    DataSet d2=g2.getDataSetList().get(0);
    System.out.println("\n===== VERIFY ("+out+") =====");
    System.out.println("scriptType="+d2.getDataSetItemNormal().getDataAccessMethodSQL().getScriptType());
    RexObjectList<FieldData> f2=(RexObjectList<FieldData>) d2.getFieldDataList();
    System.out.print("fields("+f2.size()+"): "); for(int i=0;i<f2.size();i++) System.out.print(f2.get(i).getName()+":"+f2.get(i).getDataType()+"  "); System.out.println();
    RexObjectList<FieldParameter> g2p=(RexObjectList<FieldParameter>) g2.getFieldGlobalParameterList();
    System.out.print("globalParams("+g2p.size()+"): "); for(int i=0;i<g2p.size();i++) System.out.print(g2p.get(i).getName()+"  "); System.out.println();
  }
}
