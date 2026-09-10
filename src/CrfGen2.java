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
  static String stripTags(String s){ s=s.replace("<![CDATA[","").replace("]]>",""); return unescapeXml(MB_TAG.matcher(s).replaceAll(" ")); }
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
    int i=0; java.util.Set<String> seen=new java.util.HashSet<>(); for(String it:items){i++; String n=colName(it); if(n==null||seen.contains(n.toUpperCase())) n="COL_"+i; seen.add(n.toUpperCase()); c.add(n);} return c; }  // 중복 이름은 COL_n 자리(위치 보존)
  static List<String> parseParams(String s){ List<String> o=new ArrayList<>(); Matcher m=Pattern.compile("[#$]\\{\\s*([A-Za-z_][A-Za-z0-9_]*)").matcher(s); while(m.find()){ if(!o.contains(m.group(1)))o.add(m.group(1)); } return o; }
  static DataType guessType(String n){ String u=n.toUpperCase(); if(u.matches(".*(AMT|AMOUNT|PRICE|SUM|TOT|PAY|SAL).*"))return DataType.Currency; if(u.matches(".*(CNT|COUNT|QTY|NUM|SEQ)$"))return DataType.Number; if(u.matches(".*(YMD|YM|DATE|DT)$"))return DataType.DateTime; return DataType.String; }

  // ---------- token substitution: #{x}->'{parameter.x}'  ${x}->{parameter.x} ----------
  /** Quote-aware: outside string literals  #{x}->'{parameter.x}'  ${x}->{parameter.x}  :x->'{parameter.x}' ;
   *  inside '...' literals  #{x}/${x}->{parameter.x} (the literal's own quotes are kept, so '#{x}' -> '{parameter.x}') and :x is left alone. */
  static String subParamsQuoted(String s){
    StringBuilder out=new StringBuilder(); int i=0, n=s.length();
    while(i<n){
      int q=s.indexOf('\'',i); if(q<0){ out.append(subOutside(s.substring(i))); break; }
      out.append(subOutside(s.substring(i,q)));
      int e=q+1; while(e<n){ int nq=s.indexOf('\'',e); if(nq<0){ e=n; break; } if(nq+1<n && s.charAt(nq+1)=='\''){ e=nq+2; continue; } e=nq+1; break; }
      out.append(subInside(s.substring(q,e))); i=e;
    }
    return out.toString();
  }
  static String subOutside(String s){
    s=s.replaceAll("#\\{\\s*([A-Za-z_][A-Za-z0-9_]*)[^}]*\\}","'{parameter.$1}'");
    s=s.replaceAll("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)[^}]*\\}","{parameter.$1}");
    s=s.replaceAll("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)","'{parameter.$1}'");   // :colNm (Oracle/JDBC bind) -> CLIP token
    return s;
  }
  static String subInside(String lit){
    lit=lit.replaceAll("#\\{\\s*([A-Za-z_][A-Za-z0-9_]*)[^}]*\\}","{parameter.$1}");
    lit=lit.replaceAll("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)[^}]*\\}","{parameter.$1}");
    return lit;
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
    t=unescapeXml(t).trim();
    // 함수형 검사: isValid(x) / isNotEmpty(x) / isNotBlank(x) → 값 있음, isEmpty(x)/isBlank(x)/!isValid(x) → 값 없음 (@pkg.Cls@isEmpty(x) 형태 포함)
    t=t.replaceAll("!\\s*(?:@[A-Za-z0-9_.$]+@)?(?:isValid|isNotEmpty|isNotBlank|isNotNull)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)","'{parameter.$1}' == ''");
    t=t.replaceAll("!\\s*(?:@[A-Za-z0-9_.$]+@)?(?:isEmpty|isBlank|isNull)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)","'{parameter.$1}' != ''");
    t=t.replaceAll("(?:@[A-Za-z0-9_.$]+@)?(?:isValid|isNotEmpty|isNotBlank|isNotNull)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)","'{parameter.$1}' != ''");
    t=t.replaceAll("(?:@[A-Za-z0-9_.$]+@)?(?:isEmpty|isBlank|isNull)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)","'{parameter.$1}' == ''");
    // "A".equals(p) / p.equals("A") / p == "A" / p == 'A'
    t=t.replaceAll("[\"']([^\"']*)[\"']\\.equals\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)","'{parameter.$2}' == '$1'");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\.equals\\(\\s*[\"']([^\"']*)[\"']\\s*\\)","'{parameter.$1}' == '$2'");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=)\\s*\"([^\"]*)\"","'{parameter.$1}' $2 '$3'");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=)\\s*'([^']+)'","'{parameter.$1}' $2 '$3'");
    // 숫자 비교: cnt > 0, cnt >= 10 → '{parameter.CNT}' > 0 (JS 가 문자열을 숫자로 강제 변환; '' 는 0)
    t=t.replaceAll("(?<![\\w'.{])([A-Za-z_][A-Za-z0-9_]*)\\s*(>=|<=|>|<|==|!=)\\s*(-?\\d+(?:\\.\\d+)?)(?![\\w'])","'{parameter.$1}' $2 $3");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*!=\\s*null","'{parameter.$1}' != ''");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*null","'{parameter.$1}' == ''");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*!=\\s*''","'{parameter.$1}' != ''");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*''","'{parameter.$1}' == ''");
    t=t.replaceAll("\\band\\b"," && ").replaceAll("\\bor\\b"," || ").replaceAll("\\s+"," ").trim();
    return t;
  }
  static String testAttr(String tag){ Matcher t=Pattern.compile("test\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(tag); if(!t.find()) return "true"; String v=t.group(1)!=null?t.group(1):t.group(2); return convertCond(v); }
  static String unescapeXml(String s){ return s==null?null:s.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replace("&amp;","&"); }
  /** MyBatis 동적 태그만 태그로 인식 — SQL 본문의 비교연산자(<, <=, <>)는 태그가 아니므로 보존. */
  static final Pattern MB_TAG=Pattern.compile("(?is)</?(?:if|where|foreach|choose|when|otherwise|trim|set|select|include|bind|sql|insert|update|delete)\\b(?:\"[^\"]*\"|'[^']*'|[^>\"'])*>|<!--.*?-->");  // 속성값 안의 > 는 태그 끝이 아님
  static String escLine(String s){ return s.replace("\\","\\\\").replace("\"","\\\""); }
  // ---------- MyBatis -> JavaScript builder ----------
  static String mybatisToJs(String mb, List<String> warns){
    String body=mb;
    Matcher sel=Pattern.compile("(?is)<select[^>]*>(.*)</select>").matcher(mb);
    if(sel.find()) body=sel.group(1);
    body=body.replace("<![CDATA[","").replace("]]>","");
    StringBuilder js=new StringBuilder("var sql = \"\";\r\n");
    Matcher m=MB_TAG.matcher(body);
    int pos=0; java.util.ArrayDeque<int[]> choose=new java.util.ArrayDeque<>();  // choose 중첩: [분기 수]
    java.util.ArrayDeque<String[]> fe=new java.util.ArrayDeque<>(); int feSeq=0;   // foreach 중첩: [item, 변수명, 인덱스명, close]
    java.util.ArrayDeque<String> trimClose=new java.util.ArrayDeque<>();
    while(m.find()){
      String text=body.substring(pos,m.start());
      if(fe.isEmpty()) emitText(js,text); else emitForeachBody(js,text,fe.peek());
      String tag=m.group().trim();
      String low=tag.toLowerCase();
      if(low.startsWith("<!--")) {}
      else if(low.startsWith("<include")) warns.add("<include> NOT expanded (refid 조각을 직접 붙여 넣으세요): "+tag);
      else if(low.startsWith("<bind")) warns.add("<bind> NOT converted: "+tag);
      else if(low.startsWith("<where")) js.append("sql += \" WHERE 1=1 \\r\\n\";\r\n");
      else if(low.startsWith("</where")||low.startsWith("</set")||low.startsWith("</select")) {}
      else if(low.startsWith("<if")){ String c=testAttr(tag); js.append("if(").append(c).append("){\r\n"); }
      else if(low.startsWith("</if")) js.append("}\r\n");
      else if(low.startsWith("<choose")) choose.push(new int[]{0});
      else if(low.startsWith("<when")){ String c=testAttr(tag); int[] st=choose.peek(); boolean first=st==null||st[0]==0; if(st!=null) st[0]++; js.append(first?"if(":"else if(").append(c).append("){\r\n"); }
      else if(low.startsWith("</when")) js.append("}\r\n");
      else if(low.startsWith("<otherwise")){ int[] st=choose.peek(); boolean first=st==null||st[0]==0; if(st!=null) st[0]++; js.append(first?"if(true){\r\n":"else {\r\n"); }
      else if(low.startsWith("</otherwise")) js.append("}\r\n");
      else if(low.startsWith("</choose")){ if(!choose.isEmpty()) choose.pop(); }
      else if(low.startsWith("<foreach")){ feSeq++; String coll=attr(tag,"collection"), item=attr(tag,"item"), open=attr(tag,"open"), sep=attr(tag,"separator"), close=attr(tag,"close");
        if(coll==null||coll.isEmpty()){ warns.add("<foreach> collection 없음 — 건너뜀: "+tag); fe.push(new String[]{item==null?"item":item,"__fe"+feSeq,"__i"+feSeq,""}); continue; }
        String pn=rp(coll.replaceAll("[^A-Za-z0-9_].*$","")); String v="__fe"+feSeq, ix="__i"+feSeq;
        js.append("var ").append(v).append(" = ('{parameter.").append(pn).append("}' == '') ? [] : '{parameter.").append(pn).append("}'.split(',');\r\n");
        if(open!=null&&!open.isEmpty()) js.append("sql += \"").append(escLine(open)).append("\";\r\n");
        js.append("for(var ").append(ix).append("=0; ").append(ix).append("<").append(v).append(".length; ").append(ix).append("++){\r\n");
        if(sep!=null&&!sep.isEmpty()) js.append("if(").append(ix).append(">0) sql += \"").append(escLine(sep)).append("\";\r\n");
        fe.push(new String[]{item==null?"item":item,v,ix,close==null?"":close});
        warns.add("<foreach collection="+coll+"> → 매개변수 "+pn+" 를 쉼표로 이어 붙인 문자열(예 A,B,C)로 넘겨야 합니다(JS split 루프로 변환; 각 원소는 '…' 로 감쌈)"); }
      else if(low.startsWith("</foreach")){ if(!fe.isEmpty()){ String[] st=fe.pop(); js.append("}\r\n"); if(!st[3].isEmpty()) js.append("sql += \"").append(escLine(st[3])).append("\";\r\n"); } }
      else if(low.startsWith("<trim")){ String prefix=attr(tag,"prefix"), suffix=attr(tag,"suffix"), po=attr(tag,"prefixOverrides"), so=attr(tag,"suffixOverrides");
        if(prefix!=null&&prefix.trim().equalsIgnoreCase("WHERE")){ js.append("sql += \" WHERE 1=1 \\r\\n\";\r\n"); if(po!=null&&po.toUpperCase().contains("OR")) warns.add("<trim prefix=WHERE prefixOverrides=\""+po+"\"> 를 WHERE 1=1 로 바꿈 — 첫 조각이 OR 로 시작하면 의미가 달라지니 확인"); }
        else if(prefix!=null&&!prefix.isEmpty()){ js.append("sql += \" ").append(escLine(prefix)).append(" \";\r\n"); if(po!=null&&!po.isEmpty()) warns.add("<trim prefixOverrides=\""+po+"\"> 는 적용 못 함(첫 조각의 "+po+" 를 직접 제거하세요)"); }
        if(so!=null&&!so.isEmpty()) warns.add("<trim suffixOverrides=\""+so+"\"> 는 적용 못 함(마지막 조각의 "+so+" 를 직접 제거하세요)");
        trimClose.push(suffix==null?"":suffix); }
      else if(low.startsWith("</trim")){ if(!trimClose.isEmpty()){ String c=trimClose.pop(); if(!c.isEmpty()) js.append("sql += \" ").append(escLine(c)).append(" \";\r\n"); } }
      else if(low.startsWith("<set")){ js.append("sql += \" SET \";\r\n"); warns.add("<set> → ' SET ' 로만 변환(마지막 쉼표 제거는 수동)"); }
      else if(low.startsWith("<select")) {}
      else warns.add("unhandled tag: "+tag);
      pos=m.end();
    }
    if(fe.isEmpty()) emitText(js, body.substring(pos)); else emitForeachBody(js,body.substring(pos),fe.peek());
    js.append("return sql;\r\n");
    return js.toString();
  }
  static String attr(String tag,String name){ Matcher a=Pattern.compile("(?i)\\b"+Pattern.quote(name)+"\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(tag); if(!a.find()) return null; return unescapeXml(a.group(1)!=null?a.group(1):a.group(2)); }
  /** foreach 본문: #{item}/#{item.x} → '"+__feN[__iN]+"' (따옴표 감쌈), ${item} → "+__feN[__iN]+" ; 나머지 텍스트는 일반 변환 */
  static void emitForeachBody(StringBuilder js,String text,String[] st){
    if(text==null) return; String item=Pattern.quote(st[0]), v=st[1]+"["+st[2]+"]";
    // '#{item}' 처럼 이미 SQL 따옴표로 감싸져 있으면 따옴표를 더 붙이지 않는다
    String t=text.replaceAll("'\\s*#\\{\\s*"+item+"(?:\\.[A-Za-z0-9_]+)?\\s*\\}\\s*'","\uE001").replaceAll("#\\{\\s*"+item+"(?:\\.[A-Za-z0-9_]+)?\\s*\\}","\uE001").replaceAll("\\$\\{\\s*"+item+"(?:\\.[A-Za-z0-9_]+)?\\s*\\}","\uE002");
    t=subParamsQuoted(unescapeXml(t));
    for(String ln: t.replace("\r\n","\n").replace("\r","\n").split("\n",-1)){ if(ln.trim().isEmpty()) continue; String e=escLine(ln).replace("\uE001","'\" + "+v+" + \"'").replace("\uE002","\" + "+v+" + \""); js.append("sql += \"").append(e).append("\\r\\n\";\r\n"); }
  }
  static void emitText(StringBuilder js,String text){ emitText(js,text,true); }
  /** xml=false 면 평문 SQL(엔티티 복원 안 함 — '&lt;' 같은 리터럴 보존) */
  static void emitText(StringBuilder js,String text,boolean xml){
    if(text==null) return;
    String t=subParamsQuoted(xml?unescapeXml(text):text);
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
