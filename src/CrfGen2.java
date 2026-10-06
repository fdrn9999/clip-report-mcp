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
    // 컬렉션 검사: list.size() > 0 / list.size > 0 / !list.isEmpty() → 값 있음, list.size() == 0 / list.isEmpty() → 값 없음 (매개변수는 쉼표 문자열로 전달)
    t=t.replaceAll("!\\s*([A-Za-z_][A-Za-z0-9_]*)\\.isEmpty\\(\\s*\\)","'{parameter.$1}' != ''");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\.isEmpty\\(\\s*\\)","'{parameter.$1}' == ''");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\.(?:size\\(\\s*\\)|length\\(\\s*\\)|size|length)\\s*(?:>\\s*0|!=\\s*0|>=\\s*1)(?![\\d.])","'{parameter.$1}' != ''");
    t=t.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\.(?:size\\(\\s*\\)|length\\(\\s*\\)|size|length)\\s*(?:==\\s*0|<\\s*1|<=\\s*0)(?![\\d.])","'{parameter.$1}' == ''");
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
  static String testAttr(String tag){ return testAttr(tag,null,null); }
  /** foreach 안의 test: 루프 변수(item / item.prop)와 index 변수는 매개변수가 아니라 현재 원소(__feN[__iN]) / 인덱스(__iN) 로 바꾼다. */
  static String testAttr(String tag,java.util.Deque<Fe> fe,List<String> warns){ Matcher t=Pattern.compile("test\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(tag); if(!t.find()) return "true"; String v=t.group(1)!=null?t.group(1):t.group(2);
    if(fe==null||fe.isEmpty()) return convertCond(v);
    for(Fe f: fe){   // 안쪽 foreach 부터
      Matcher m=Pattern.compile("(?<![\\w.$'\"])"+Pattern.quote(f.item)+"((?:\\.[A-Za-z_][A-Za-z0-9_]*)*)(?![\\w(])").matcher(v); StringBuffer b=new StringBuffer();
      while(m.find()){ if(!m.group(1).isEmpty()&&warns!=null) warns.add("<foreach> test 의 "+m.group()+" → 원소 자체로 비교(매개변수가 쉼표 문자열이라 원소의 속성은 없음)"); m.appendReplacement(b,"__FEV"+f.seq+"__"); } m.appendTail(b); v=b.toString();
      if(f.index!=null) v=v.replaceAll("(?<![\\w.$'\"])"+Pattern.quote(f.index)+"(?![\\w(.])","__FEI"+f.seq+"__"); }
    String c=convertCond(v);
    for(Fe f: fe){ String el=f.v+"["+f.ix+"]"; c=c.replace("'{parameter.__FEV"+f.seq+"__}'",el).replace("__FEV"+f.seq+"__",el).replace("'{parameter.__FEI"+f.seq+"__}'",f.ix).replace("__FEI"+f.seq+"__",f.ix); }
    return c; }
  /** foreach 한 단계: item/index 변수명, JS 배열 변수(__feN)·인덱스 변수(__iN), close 문자열 */
  static class Fe { String item, index, v, ix, close, sep=""; int seq; boolean buffered; Fe(String item,String index,int seq,String close){ this.item=item==null||item.isEmpty()?"item":item; this.index=index==null||index.isEmpty()?null:index; this.seq=seq; this.v="__fe"+seq; this.ix="__i"+seq; this.close=close==null?"":close; } }
  static String unescapeXml(String s){ return s==null?null:s.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replace("&amp;","&"); }
  /** MyBatis 동적 태그만 태그로 인식 — SQL 본문의 비교연산자(<, <=, <>)는 태그가 아니므로 보존. */
  static final Pattern MB_TAG=Pattern.compile("(?is)</?(?:if|where|foreach|choose|when|otherwise|trim|set|select|include|bind|sql|insert|update|delete)\\b(?:\"[^\"]*\"|'[^']*'|[^>\"'])*>|<!--.*?-->");  // 속성값 안의 > 는 태그 끝이 아님
  static String escLine(String s){ return s.replace("\\","\\\\").replace("\"","\\\""); }
  // ---------- MyBatis -> JavaScript builder ----------
  /** <trim>/<where>/<set> 실행 시 처리용 JS 도우미(정규식 없이 기본 문자열 함수만): 본문을 trim 해 비었으면 '' ,
   *  아니면 prefixOverrides(앞)/suffixOverrides(뒤) 중 처음 맞는 것(대소문자 무시)을 떼고 prefix/suffix 를 붙인다 — MyBatis TrimSqlNode 와 같은 규칙. */
  static final String TRIM_HELPER=
    "function __trim(b, pre, suf, po, so){\r\n"+
    "  var ws = \" \\t\\r\\n\", s = 0, e = b.length, o, k, u, i;\r\n"+
    "  while(s < e && ws.indexOf(b.charAt(s)) >= 0){ s++; }\r\n"+
    "  while(e > s && ws.indexOf(b.charAt(e - 1)) >= 0){ e--; }\r\n"+
    "  b = b.substring(s, e);\r\n"+
    "  if(b == \"\"){ return \"\"; }\r\n"+
    "  u = b.toUpperCase(); o = (po == \"\") ? [] : po.toUpperCase().split(\"|\");\r\n"+
    "  for(k = 0; k < o.length; k++){ if(o[k] != \"\" && u.indexOf(o[k]) == 0){ b = b.substring(o[k].length); break; } }\r\n"+
    "  u = b.toUpperCase(); o = (so == \"\") ? [] : so.toUpperCase().split(\"|\");\r\n"+
    "  for(k = 0; k < o.length; k++){ i = u.length - o[k].length; if(o[k] != \"\" && i >= 0 && u.lastIndexOf(o[k]) == i){ b = b.substring(0, i); break; } }\r\n"+
    "  return \" \" + pre + \" \" + b + \" \" + suf + \" \";\r\n"+
    "}\r\n";
  /** MyBatis <where> 가 떼는 앞머리(WhereSqlNode 와 동일) */
  static final String WHERE_PO="AND |OR |AND\n|OR\n|AND\r|OR\r|AND\t|OR\t";
  static String jsStr(String s){ return "\""+(s==null?"":s).replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")+"\""; }
  static String[] overrideTokens(String po){ return po==null||po.isEmpty()?new String[0]:po.split("\\|"); }
  /** 텍스트 앞머리(공백 뒤)의 override 하나를 뗀다(대소문자 무시) */
  static String stripLeading(String text,String[] toks){ int s=0; while(s<text.length()&&Character.isWhitespace(text.charAt(s))) s++; String u=text.substring(s).toUpperCase();
    for(String t: toks){ if(!t.isEmpty()&&u.startsWith(t.toUpperCase())) return text.substring(0,s)+text.substring(s+t.length()); } return text; }
  static boolean startsWithToken(String text,String[] toks){ String u=text.toUpperCase(); for(String t: toks) if(!t.isEmpty()&&u.startsWith(t.toUpperCase())) return true; return false; }
  /** from 이후 블록의 첫 내용: [0]=처음 나오는 비어 있지 않은 텍스트(앞 공백 제거; 블록이 비었으면 ""), [1]="static"(동적 태그보다 텍스트가 먼저) | "dynamic" */
  static String[] firstContent(String body,int from){ Matcher m=MB_TAG.matcher(body); int p=from; boolean dyn=false;
    while(m.find(p)){ String t=body.substring(p,m.start()); if(!t.trim().isEmpty()) return new String[]{t.replaceAll("^\\s+",""),dyn?"dynamic":"static"};
      String low=m.group().toLowerCase(); if(low.startsWith("</where")||low.startsWith("</trim")||low.startsWith("</set")||low.startsWith("</select")) return new String[]{"",dyn?"dynamic":"static"};
      if(!low.startsWith("<!--")) dyn=true; p=m.end(); }
    return new String[]{body.substring(p).replaceAll("^\\s+",""),dyn?"dynamic":"static"}; }
  static final String[] AND_TOKS={"AND ","AND\n","AND\r","AND\t"};
  /** <where>/<trim prefix=WHERE> 블록에서 '맨 앞에 올 수 있는' 조각(앞의 조건이 전부 거짓일 때 첫 출력이 되는 조각)이 모두 AND 로 시작하는지.
   *  그럴 때만 WHERE 1=1 로 바꿔도 뜻이 같다 — 하나라도 OR 로 시작하거나 접속사가 없으면 실행 시점 정리(__trim)로 보낸다. 안쪽에 where/trim 이 또 있으면 보수적으로 false. */
  static boolean allLeadAnd(String body,int from){ Matcher m=MB_TAG.matcher(body); int p=from; boolean staticSeen=false; java.util.ArrayDeque<boolean[]> cond=new java.util.ArrayDeque<>();
    while(true){ boolean has=m.find(p); String t=body.substring(p,has?m.start():body.length());
      if(!t.trim().isEmpty()&&!staticSeen){ boolean leading=true; for(boolean[] c: cond) if(c[0]) leading=false;
        if(leading&&!startsWithToken(t.replaceAll("^\\s+",""),AND_TOKS)) return false;
        if(cond.isEmpty()) staticSeen=true; else for(boolean[] c: cond) c[0]=true; }
      if(!has) return true; String low=m.group().toLowerCase(); p=m.end();
      if(low.startsWith("<!--")||low.endsWith("/>")) continue;
      if(low.startsWith("<if")||low.startsWith("<when")||low.startsWith("<otherwise")) cond.push(new boolean[]{false});
      else if(low.startsWith("</if")||low.startsWith("</when")||low.startsWith("</otherwise")){ if(!cond.isEmpty()) cond.pop(); }
      else if(low.startsWith("<where")||low.startsWith("<trim")||low.startsWith("<set")) return false;
      else if(low.startsWith("</where")||low.startsWith("</trim")||low.startsWith("</set")||low.startsWith("</select")) return true; } }
  /** from(<foreach …> 바로 뒤)부터 짝이 맞는 </foreach> 사이에 조건 태그(<if>/<choose>)가 있는지 */
  static boolean foreachHasDynamic(String body,int from){ Matcher m=MB_TAG.matcher(body); int p=from, depth=0;
    while(m.find(p)){ String low=m.group().toLowerCase(); p=m.end(); if(low.startsWith("<foreach")&&!low.endsWith("/>")) depth++; else if(low.startsWith("</foreach")){ if(depth==0) return false; depth--; } else if(low.startsWith("<if")||low.startsWith("<choose")||low.startsWith("<when")) return true; }
    return false; }
  static String mybatisToJs(String mb, List<String> warns){
    String body=mb;
    Matcher sel=Pattern.compile("(?is)<select[^>]*>(.*)</select>").matcher(mb);
    if(sel.find()) body=sel.group(1);
    body=body.replace("<![CDATA[","").replace("]]>","");
    StringBuilder js=new StringBuilder();
    Matcher m=MB_TAG.matcher(body);
    int pos=0; java.util.ArrayDeque<int[]> choose=new java.util.ArrayDeque<>();  // choose 중첩: [분기 수]
    java.util.ArrayDeque<Fe> fe=new java.util.ArrayDeque<>(); int feSeq=0;        // foreach 중첩(안쪽이 앞)
    java.util.ArrayDeque<String> trims=new java.util.ArrayDeque<>(); int trimSeq=0; boolean helper=false;   // where/trim/set: 닫을 때 붙일 JS("" 면 없음)
    String[] pendingStrip=null;   // 정적 텍스트로 시작하는 <where>/<trim prefix=WHERE>: 바로 다음 텍스트 앞머리의 AND/OR 를 변환 시점에 뗀다
    while(m.find()){
      String text=body.substring(pos,m.start());
      if(pendingStrip!=null&&!text.trim().isEmpty()){ text=stripLeading(text,pendingStrip); pendingStrip=null; }
      if(fe.isEmpty()) emitText(js,text); else emitForeachBody(js,text,fe);
      String tag=m.group().trim();
      String low=tag.toLowerCase();
      if(low.startsWith("<!--")) {}
      else if(low.startsWith("<include")) warns.add("<include> NOT expanded (refid 조각을 직접 붙여 넣으세요): "+tag);
      else if(low.startsWith("<bind")) warns.add("<bind> NOT converted: "+tag);
      else if(low.startsWith("<where")||low.startsWith("<trim")||low.startsWith("<set")){
        String prefix, suffix="", po, so="";
        if(low.startsWith("<where")){ prefix="WHERE"; po=WHERE_PO; }
        else if(low.startsWith("<set")){ prefix="SET"; po=","; so=","; }
        else { prefix=attr(tag,"prefix"); suffix=attr(tag,"suffix"); po=attr(tag,"prefixOverrides"); so=attr(tag,"suffixOverrides"); if(prefix==null) prefix=""; if(suffix==null) suffix=""; if(po==null) po=""; if(so==null) so=""; }
        boolean whereLike=prefix.trim().equalsIgnoreCase("WHERE")&&suffix.isEmpty()&&so.isEmpty();
        String[] fc=firstContent(body,m.end()); String[] toks=overrideTokens(po);
        if(low.endsWith("/>")||fc[0].isEmpty()&&fc[1].equals("static")) { if(!low.endsWith("/>")) trims.push(""); }   // 빈 블록: 아무것도 붙이지 않음
        else if(whereLike&&fc[1].equals("static")){ js.append("sql += \" WHERE \\r\\n\";\r\n"); pendingStrip=toks; trims.push(""); }   // 무조건 조각으로 시작 → WHERE 는 항상, 앞머리 AND/OR 만 뗌
        else if(whereLike&&startsWithToken(fc[0],AND_TOKS)&&startsWithToken(fc[0],toks)&&allLeadAnd(body,m.end())){ js.append("sql += \" WHERE 1=1 \\r\\n\";\r\n"); trims.push(""); }   // 흔한 형태(<if> 조각이 전부 AND 로 시작) → 읽기 쉬운 WHERE 1=1
        else { trimSeq++; helper=true; js.append("var __t").append(trimSeq).append(" = sql; sql = \"\";\r\n");
          trims.push("sql = __t"+trimSeq+" + __trim(sql, "+jsStr(prefix.trim())+", "+jsStr(suffix.trim())+", "+jsStr(po)+", "+jsStr(so)+");\r\n"); }
      }
      else if(low.startsWith("</where")||low.startsWith("</trim")||low.startsWith("</set")){ if(!trims.isEmpty()) js.append(trims.pop()); }
      else if(low.startsWith("</select")) {}
      else if(low.startsWith("<if")){ String c=testAttr(tag,fe,warns); js.append("if(").append(c).append("){\r\n"); }
      else if(low.startsWith("</if")) js.append("}\r\n");
      else if(low.startsWith("<choose")) choose.push(new int[]{0});
      else if(low.startsWith("<when")){ String c=testAttr(tag,fe,warns); int[] st=choose.peek(); boolean first=st==null||st[0]==0; if(st!=null) st[0]++; js.append(first?"if(":"else if(").append(c).append("){\r\n"); }
      else if(low.startsWith("</when")) js.append("}\r\n");
      else if(low.startsWith("<otherwise")){ int[] st=choose.peek(); boolean first=st==null||st[0]==0; if(st!=null) st[0]++; js.append(first?"if(true){\r\n":"else {\r\n"); }
      else if(low.startsWith("</otherwise")) js.append("}\r\n");
      else if(low.startsWith("</choose")){ if(!choose.isEmpty()) choose.pop(); }
      else if(low.startsWith("<foreach")){ feSeq++; String coll=attr(tag,"collection"), open=attr(tag,"open"), sep=attr(tag,"separator");
        Fe f=new Fe(attr(tag,"item"),attr(tag,"index"),feSeq,attr(tag,"close"));
        Matcher cm=Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*$").matcher(coll==null?"":coll.trim());   // param.deptList → deptList (마지막 식별자)
        if(!cm.find()){ warns.add("<foreach> collection 없음/해석 불가 — 빈 목록으로 처리(본문이 출력되지 않음): "+tag); js.append("var ").append(f.v).append(" = [];\r\n"); }
        else { String pn=rp(cm.group(1));
          js.append("var ").append(f.v).append(" = ('{parameter.").append(pn).append("}' == '') ? [] : '{parameter.").append(pn).append("}'.split(',');\r\n");
          warns.add("<foreach collection="+coll+"> → 매개변수 "+pn+" 를 쉼표로 이어 붙인 문자열(예 A,B,C)로 넘겨야 합니다(JS split 루프로 변환; 각 원소는 '…' 로 감쌈)"); }
        f.sep=sep==null?"":sep; f.buffered=!f.sep.isEmpty()&&foreachHasDynamic(body,m.end());
        if(open!=null&&!open.isEmpty()) js.append("if(").append(f.v).append(".length > 0) sql += \"").append(escLine(open)).append("\";\r\n");   // 빈 목록이면 MyBatis 처럼 open/close 도 내지 않음(IN () 방지)
        if(f.buffered){ helper=true; js.append("var __n").append(f.seq).append(" = 0;\r\n"); }
        js.append("for(var ").append(f.ix).append("=0; ").append(f.ix).append("<").append(f.v).append(".length; ").append(f.ix).append("++){\r\n");
        if(f.buffered) js.append("var __s").append(f.seq).append(" = sql; sql = \"\";\r\n");   // 본문에 <if> 가 있으면 회차 결과를 따로 모아, 내용이 있을 때만 구분자를 붙인다
        else if(!f.sep.isEmpty()) js.append("if(").append(f.ix).append(">0) sql += \"").append(escLine(f.sep)).append("\";\r\n");
        fe.push(f); }
      else if(low.startsWith("</foreach")){ if(!fe.isEmpty()){ Fe f=fe.pop();
          if(f.buffered) js.append("var __b").append(f.seq).append(" = sql; sql = __s").append(f.seq).append(";\r\nif(__trim(__b").append(f.seq).append(", \"\", \"\", \"\", \"\") != \"\"){ if(__n").append(f.seq).append(" > 0) sql += \"").append(escLine(f.sep)).append("\"; sql += __b").append(f.seq).append("; __n").append(f.seq).append("++; }\r\n");
          js.append("}\r\n"); if(!f.close.isEmpty()) js.append("if(").append(f.v).append(".length > 0) sql += \"").append(escLine(f.close)).append("\";\r\n"); } }
      else if(low.startsWith("<select")) {}
      else warns.add("unhandled tag: "+tag);
      pos=m.end();
    }
    String tail=body.substring(pos); if(pendingStrip!=null) tail=stripLeading(tail,pendingStrip);
    if(fe.isEmpty()) emitText(js,tail); else emitForeachBody(js,tail,fe);
    while(!trims.isEmpty()) js.append(trims.pop());   // 닫히지 않은 where/trim
    return "var sql = \"\";\r\n"+(helper?TRIM_HELPER:"")+js+"return sql;\r\n";
  }
  static String attr(String tag,String name){ Matcher a=Pattern.compile("(?i)\\b"+Pattern.quote(name)+"\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(tag); if(!a.find()) return null; return unescapeXml(a.group(1)!=null?a.group(1):a.group(2)); }
  /** foreach 본문: #{item}/#{item.x} → '"+__feN[__iN]+"' (따옴표 감쌈), ${item} → "+__feN[__iN]+" , #{index}/${index} → "+__iN+" ; 바깥 foreach 의 변수도 같은 규칙(중첩); 나머지 텍스트는 일반 변환 */
  static void emitForeachBody(StringBuilder js,String text,java.util.Deque<Fe> fe){
    if(text==null) return; String t=text;
    for(Fe f: fe){ String item=Pattern.quote(f.item), tail="\\s*(?:[.,][^}]*)?\\}", Q="\uE001"+f.seq+"\uE000", R="\uE002"+f.seq+"\uE000", I="\uE003"+f.seq+"\uE000";
      // '#{item}' 처럼 이미 SQL 따옴표로 감싸져 있으면 따옴표를 더 붙이지 않는다
      t=t.replaceAll("'\\s*#\\{\\s*"+item+tail+"\\s*'",Q).replaceAll("#\\{\\s*"+item+tail,Q).replaceAll("\\$\\{\\s*"+item+tail,R);
      if(f.index!=null) t=t.replaceAll("[#$]\\{\\s*"+Pattern.quote(f.index)+"\\s*(?:,[^}]*)?\\}",I); }
    t=subParamsQuoted(unescapeXml(t));
    for(String ln: t.replace("\r\n","\n").replace("\r","\n").split("\n",-1)){ if(ln.trim().isEmpty()) continue; String e=escLine(ln);
      for(Fe f: fe){ String el=f.v+"["+f.ix+"]"; e=e.replace("\uE001"+f.seq+"\uE000","'\" + "+el+" + \"'").replace("\uE002"+f.seq+"\uE000","\" + "+el+" + \"").replace("\uE003"+f.seq+"\uE000","\" + "+f.ix+" + \""); }
      js.append("sql += \"").append(e).append("\\r\\n\";\r\n"); }
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

    CrfIO.write(rf,out);

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
