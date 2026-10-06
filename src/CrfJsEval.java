import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import java.util.*;
import java.util.regex.*;

/** JS 동적 쿼리(scriptType=JavaScript)를 **실제로 실행**해 SQL 문자열을 얻는다.
 *  JS 엔진은 CLIP 엔진 jar 폴더의 batik.jar 에 들어 있는 Rhino(org.mozilla.javascript) — 별도 의존성 없음.
 *  .crf 안의 스크립트는 신뢰할 수 없는 입력이므로 Java 접근(Packages/java/getClass…)을 막고 실행 시간을 제한한다.
 *  Rhino 가 classpath 에 없으면 available()=false → 호출부가 jsToPlainSql(추정 복원)로 대체. */
public class CrfJsEval {
  static final long TIME_LIMIT_MS=3000;
  static final int MAX_SQL=2_000_000;
  static class Result { String sql; String error; List<String> unset=new ArrayList<>(); boolean ok(){ return error==null; } }

  static Boolean avail;
  static boolean available(){ if(avail==null){ try{ Class.forName("org.mozilla.javascript.ContextFactory"); avail=Boolean.TRUE; }catch(Throwable t){ avail=Boolean.FALSE; } } return avail; }

  static String lookup(Map<String,String> params,String name){ if(params==null) return null; String v=params.get(name); if(v!=null) return v; for(Map.Entry<String,String> e: params.entrySet()) if(e.getKey().equalsIgnoreCase(name)) return e.getValue(); return null; }
  static final int MAX_SCRIPT=1_000_000;
  /** 값을 JS 문자열 리터럴 안에 넣을 수 있게 + SQL 리터럴 안에서도 안전하게(' → '') */
  static String escJs(String v){ return v.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\u2028","\\u2028").replace("\u2029","\\u2029").replace("'","\\'\\'"); }
  static final Pattern QTOKEN=Pattern.compile("'\\{(parameter|dataset)\\.([A-Za-z0-9_]+)\\}'");
  static final Pattern TOKEN=Pattern.compile("\\{(parameter|dataset)\\.([A-Za-z0-9_]+)\\}");
  /** 토큰 대입: '{parameter.X}' → '값'(없으면 '') ; 따옴표 없는 {parameter.X} 는 값이 있을 때만 대입하고 없으면 그대로 둔다(unset 에 기록 → 호출부가 NULL 등으로 처리).
   *  값 안의 ' 는 어느 쪽이든 SQL 규칙대로 '' 로 넣는다 — 토큰이 `'%{parameter.Q}%'` 처럼 더 큰 SQL 리터럴 안에 있어도 리터럴이 깨지지 않게(이 SQL 은 mode=db 에서 실제로 DB 에 보낸다). */
  static String bind(String js,Map<String,String> params,List<String> unset){
    Matcher m=QTOKEN.matcher(js); StringBuffer b=new StringBuffer();
    while(m.find()){ String v=lookup(params,m.group(2)); m.appendReplacement(b, Matcher.quoteReplacement("'"+(v==null?"":escJs(v))+"'")); } m.appendTail(b);
    m=TOKEN.matcher(b.toString()); StringBuffer c=new StringBuffer();
    while(m.find()){ String v=lookup(params,m.group(2)); if(v==null){ if(unset!=null&&!unset.contains(m.group(2))) unset.add(m.group(2)); m.appendReplacement(c, Matcher.quoteReplacement(m.group())); } else m.appendReplacement(c, Matcher.quoteReplacement(escJs(v))); } m.appendTail(c);
    return c.toString();
  }

  static class Limit extends Error { Limit(String m){ super(m); } }
  /** Rhino 클래스를 건드리는 정적 초기화는 여기로 미룬다(Rhino 가 없을 때 available() 만으로 클래스 로딩이 깨지지 않게). */
  static class Holder {
    static final ContextFactory FACTORY=new ContextFactory(){
      protected Context makeContext(){ Context cx=super.makeContext(); cx.setOptimizationLevel(-1); cx.setInstructionObserverThreshold(20000);
        cx.setClassShutter(new ClassShutter(){ public boolean visibleToScripts(String n){ return false; } }); return cx; }
      protected void observeInstructionCount(Context cx,int n){ Object t0=cx.getThreadLocal("t0"); if(t0 instanceof Long && System.currentTimeMillis()-(Long)t0>TIME_LIMIT_MS) throw new Limit("실행 시간 제한("+TIME_LIMIT_MS+"ms) 초과 — 무한 루프?"); }
    };
  }
  static final String[] JAVA_GLOBALS={"Packages","java","javax","org","com","edu","net","getClass","JavaAdapter","JavaImporter","Continuation"};

  /** 문법만 검사(실행 안 함). 오류가 없거나 엔진이 없으면 null. */
  static String syntaxError(String js){
    if(!available()) return null;
    if(js.length()>MAX_SCRIPT) return "스크립트가 너무 큼("+js.length()+"자) — 검사 생략";
    final String src="(function(){\n"+bind(js,null,null)+"\n})";
    try{ Holder.FACTORY.call(new ContextAction(){ public Object run(Context cx){ return cx.compileString(src,"query",0,null); } }); return null; }
    catch(Throwable t){ return message(t); }
  }
  /** 매개변수 값을 대입해 실행한 결과 SQL. 실패하면 error 에 사유. */
  static Result eval(String js,Map<String,String> params){
    final Result r=new Result();
    if(!available()){ r.error="JS 엔진(Rhino, CLIP bin\\jar 의 batik.jar)이 classpath 에 없음"; return r; }
    if(js.length()>MAX_SCRIPT){ r.error="스크립트가 너무 큼("+js.length()+"자) — 실행하지 않음"; return r; }
    final String src="(function(){\n"+bind(js,params,r.unset)+"\n})()";
    try{
      Object out=Holder.FACTORY.call(new ContextAction(){ public Object run(Context cx){
        cx.putThreadLocal("t0",Long.valueOf(System.currentTimeMillis()));
        ScriptableObject scope=cx.initStandardObjects(null,false);
        for(String g: JAVA_GLOBALS) scope.delete(g);
        Script sc=cx.compileString(src,"query",0,null);
        Object v=sc.exec(cx,scope);
        return (v==null||v instanceof Undefined)?null:Context.toString(v); } });
      if(out==null) r.error="스크립트가 값을 반환하지 않음(return sql; 누락?)";
      else { r.sql=String.valueOf(out); if(r.sql.length()>MAX_SQL) { r.sql=null; r.error="결과가 너무 큼"; } }
    }catch(OutOfMemoryError oom){ r.sql=null; r.error="메모리 부족 — 스크립트가 지나치게 큰 문자열/배열을 만듦"; }
    catch(Throwable t){ r.error=message(t); }
    return r;
  }
  /** Rhino 오류 메시지에서 래퍼 2줄('(function(){' 한 줄)만큼 줄 번호를 보정 */
  static String message(Throwable t){
    String m=String.valueOf(t.getMessage()==null?t.toString():t.getMessage()).trim();
    Matcher lm=Pattern.compile("\\(query#(\\d+)\\)").matcher(m);
    if(lm.find()){ int ln=Integer.parseInt(lm.group(1))-1; m=m.substring(0,lm.start()).trim()+" (쿼리 스크립트 "+Math.max(ln,1)+"번째 줄)"+m.substring(lm.end()); }
    return m.replaceAll("\\s+"," ");
  }
}
