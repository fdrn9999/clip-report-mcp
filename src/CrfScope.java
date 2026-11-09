import com.clipsoft.clipreport.base.globe.Globe;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.reports.Report;
import org.json.simple.JSONObject;
import java.lang.reflect.Field;
import java.util.*;

/**
 * 내장(임베디드) 서브리포트 안을 편집하는 범위(scope) 전환.
 *
 * 도구들은 모두 rf.getGlobe().getMainReport() 에서 섹션·컨트롤·공식·그룹을 찾는다. subreport="이름"(중첩은 "A>B")이
 * 주어지면 open() 직후 Globe.mainReport 를 그 서브리포트의 Report 로 바꿔 끼워, 기존 도구가 그대로 서브리포트 안을
 * 보고 고치게 한다. 데이터셋·매개변수는 GlobalObjectManager(파일 전체 공유)라 바꿀 필요가 없다.
 * 저장(CrfMcpServer.save) 직전에 원래 메인 리포트로 되돌려 쓰고, 쓴 뒤 다시 서브리포트로 바꿔 둔다(저장 뒤 검증·출력용).
 * Globe 에 setMainReport 가 없어 protected 필드 mainReport 를 리플렉션으로 바꾼다.
 */
public class CrfScope {
  /** 범위를 바꾼 파일 → {원래 메인 리포트, 지금 범위 리포트, 경로 문자열} */
  static final Map<TheReportFile,Object[]> SCOPED=Collections.synchronizedMap(new WeakHashMap<>());
  /** subreport= 를 "어느 서브리포트를 대상으로"라는 다른 뜻으로 쓰는 도구 — 범위 전환 안 함 */
  static final Set<String> OWN_MEANING=new HashSet<>(Arrays.asList("crf_link_subreport_param","crf_batch","crf_render","crf_generate","crf_list_reports","crf_search"));
  static final ThreadLocal<String> CUR_TOOL=new ThreadLocal<>();
  static final Set<String> NAME_SELECTOR=new HashSet<>(Arrays.asList("crf_set_label","crf_remove_control","crf_split_label"));
  static final Set<String> CREATE=new HashSet<>(Arrays.asList("crf_add_table","crf_add_label","crf_add_crosstab"));

  /** 파일 안의 모든 리포트: {경로("" = 메인), Report} — 메인 먼저, 내장 서브리포트는 재귀(4단) */
  static List<Object[]> allReports(TheReportFile rf){ List<Object[]> out=new ArrayList<>(); Report root=root(rf); out.add(new Object[]{"",root});
    try{ collectReports(rf,root,"",0,out); }catch(Exception e){} return out; }
  static void collectReports(TheReportFile rf,Report rep,String pre,int d,List<Object[]> out) throws Exception {
    if(d>=4) return; with(rf,rep,()->{ for(Object[] x: CrfStruct.subreports(rf)){ Report s=(Report)x[3]; if(s==null||!CrfStruct.hasDesign(s)) continue; String p=pre+x[0]; out.add(new Object[]{p,s}); collectReports(rf,s,p+">",d+1,out); } return null; }); }

  static Field mainField;
  static Field mf() throws Exception { if(mainField==null){ Field f=Globe.class.getDeclaredField("mainReport"); f.setAccessible(true); mainField=f; } return mainField; }
  static void setMain(TheReportFile rf,Report r){ try{ mf().set(rf.getGlobe(),r); }catch(Exception e){ throw new RuntimeException("서브리포트 범위 전환 실패(엔진 버전 차이?): "+e); } }

  static String requested(){ JSONObject a=CrfMcpServer.CUR_ARGS.get(); if(a==null) return null; if(OWN_MEANING.contains(CUR_TOOL.get())) return null;
    Object v=a.get("subreport"); String s=v==null?null:String.valueOf(v).trim(); return s==null||s.isEmpty()?null:s; }

  /** open() 에서 호출: subreport= 가 있으면 범위를 그 서브리포트로 */
  static void apply(TheReportFile rf){ String sel=requested(); if(sel!=null) enter(rf,sel); }

  static void enter(TheReportFile rf,String path){
    Report root=rf.getGlobe().getMainReport(); Object[] prev=SCOPED.get(rf); if(prev!=null) root=(Report)prev[0];
    setMain(rf,root); Report cur=root; StringBuilder walked=new StringBuilder();
    for(String part: path.split(">")){ String p=part.trim(); if(p.isEmpty()) continue;
      List<Object[]> subs=CrfStruct.subreports(rf); List<Object[]> hit=new ArrayList<>();
      for(Object[] x: subs) if(p.equals(String.valueOf(x[0]))) hit.add(x);
      if(hit.isEmpty()){ setMain(rf,root); SCOPED.remove(rf); throw new RuntimeException("서브리포트 '"+p+"' 없음"+(walked.length()>0?"('"+walked+"' 안)":"")+" — 있는 것: "+CrfStruct.names(subs)+" (crf_describe_layout 로 확인)"); }
      Object[] x=hit.get(0);
      if(hit.size()>1){ setMain(rf,root); SCOPED.remove(rf); throw new RuntimeException("서브리포트 이름 '"+p+"' 이 "+hit.size()+"개 — 디자이너에서 이름을 구분하거나 crf_set_subsection 으로 이름을 바꾸세요: "+CrfStruct.names(hit)); }
      Report sub=(Report)x[3];
      if(sub==null || !CrfStruct.hasDesign(sub)){ setMain(rf,root); SCOPED.remove(rf); throw new RuntimeException("서브리포트 '"+p+"' 는 내장이 아니라 링크된 별도 파일입니다"+linkOf(x[1])+" — 그 .crf 를 path 로 직접 열어 편집하세요"); }
      cur=sub; setMain(rf,cur); walked.append(walked.length()==0?"":">").append(p); }
    SCOPED.put(rf,new Object[]{root,cur,walked.toString()});
  }
  static String linkOf(Object holder){ try{ String u=CrfMcpServer.g(CrfMcpServer.go(holder,"getLinkedSubreportPath"),"getUrlText"); return u==null||u.isEmpty()?"":"(링크="+u+")"; }catch(Exception e){ return ""; } }

  static String scopeOf(TheReportFile rf){ Object[] s=SCOPED.get(rf); return s==null?null:(String)s[2]; }
  /** save() 에서: 원래 메인으로 되돌려 쓰고 다시 범위로 */
  interface IO { void run() throws java.io.IOException; }
  static void writeRooted(TheReportFile rf,IO w) throws java.io.IOException {
    Object[] s=SCOPED.get(rf); if(s==null){ w.run(); return; }
    setMain(rf,(Report)s[0]); try{ w.run(); } finally{ setMain(rf,(Report)s[1]); } }
  /** 일시적으로 다른 리포트를 메인 자리에 두고 작업 (describe_layout 재귀용) */
  static <T> T with(TheReportFile rf,Report r,java.util.concurrent.Callable<T> c) throws Exception {
    Report old=rf.getGlobe().getMainReport(); setMain(rf,r); try{ return c.call(); } finally{ setMain(rf,old); } }
  static Report root(TheReportFile rf){ Object[] s=SCOPED.get(rf); return s==null?rf.getGlobe().getMainReport():(Report)s[0]; }

  /** 파일 안의 모든 내장 서브리포트 경로("A", "A>B" …) — 메인 기준 */
  static List<String> embeddedPaths(String file) throws Exception {
    TheReportFile rf=com.clipsoft.clipreport.base.Rexpert4.read(file); List<String> out=new ArrayList<>(); collect(rf,rf.getGlobe().getMainReport(),"",0,out); return out; }
  static void collect(TheReportFile rf,Report rep,String pre,int d,List<String> out) throws Exception {
    if(d>=4) return; with(rf,rep,()->{ for(Object[] x: CrfStruct.subreports(rf)){ Report s=(Report)x[3]; if(s==null||!CrfStruct.hasDesign(s)) continue; String p=pre+x[0]; out.add(p); collect(rf,s,p+">",d+1,out); } return null; }); }

  /** subreport= 없이 부른 crf_diff/crf_validate 뒤에 내장 서브리포트별 결과를 덧붙인다.
   *  keep: 서브리포트 결과에서 남길 줄(검사는 ✖/⚠ 만 — 데이터셋 경고처럼 메인과 같은 줄은 뺀다). */
  @SuppressWarnings("unchecked")
  static String deep(String file,String mainOut,java.util.concurrent.Callable<String> run,boolean diffMode) throws Exception { return deep(file,null,mainOut,run,diffMode); }
  /** fileB: diff 의 B — 서브리포트 경로는 A∪B 로 보고 한쪽에만 있는 것은 추가/삭제로 알린다.
   *  validate: 서브리포트에서만 나온 ✖ 개수를 머리글의 'ERROR n' 에 더한다(crf_batch 의 validate gate 가 잡도록). */
  static String deep(String file,String fileB,String mainOut,java.util.concurrent.Callable<String> run,boolean diffMode) throws Exception {
    if(requested()!=null || file==null) return mainOut; List<String> pa, pb;
    try{ pa=embeddedPaths(file); pb=fileB==null?pa:embeddedPaths(fileB); }catch(Exception e){ return mainOut; }
    LinkedHashSet<String> paths=new LinkedHashSet<>(pa); paths.addAll(pb); if(paths.isEmpty()) return mainOut;
    JSONObject a=CrfMcpServer.CUR_ARGS.get(); StringBuilder b=new StringBuilder(mainOut); Set<String> mainLines=new HashSet<>(Arrays.asList(mainOut.split("\n"))); int extraErr=0;
    try{ for(String p: paths){ String body;
        if(diffMode && !pb.contains(p)) body="  내장 서브리포트가 B 에 없음(삭제·이름 변경 또는 링크로 바뀜)";
        else if(diffMode && !pa.contains(p)) body="  내장 서브리포트가 B 에만 있음(추가·이름 변경)";
        else { a.put("subreport",p); String r; try{ r=run.call(); }catch(Exception e){ r=(diffMode?"  ":"  ✖ ")+CrfMcpServer.friendly(e); }
          if(diffMode){ int i=r.indexOf('\n',Math.max(0,r.indexOf("B:"))); body=i<0?r:r.substring(i+1).trim(); if(body.startsWith("차이 없음")) continue; body="  "+body.replace("\n","\n  "); }
          else { StringBuilder k=new StringBuilder(); for(String l: r.split("\n")){ String t=l.trim(); if((t.startsWith("✖")||t.startsWith("⚠")) && !mainLines.contains(l)){ k.append("    ").append(t).append("\n"); if(t.startsWith("✖")) extraErr++; } } body=k.toString().replaceAll("\\s+$",""); if(body.isEmpty()) continue; } }
        b.append(b.toString().endsWith("\n")?"":"\n").append("▼ 내장 서브리포트 '").append(p).append("' (subreport=\"").append(p).append("\"):\n").append(body).append("\n"); } }
    finally{ a.remove("subreport"); }
    String out=b.toString();
    if(!diffMode && extraErr>0){ java.util.regex.Matcher m=java.util.regex.Pattern.compile("— ERROR (\\d+)").matcher(out);
      if(m.find()){ int n=Integer.parseInt(m.group(1))+extraErr; out=out.substring(0,m.start())+"— ERROR "+n+" (내장 서브리포트 ✖ "+extraErr+" 포함)"+out.substring(m.end()); } }
    return out; }

  /** "서브리포트>표1" 경로 문법: subreport 가 없고 table/name 에 '>' 가 있으면 앞부분을 subreport 로 */
  @SuppressWarnings("unchecked")
  static void splitPathArgs(String tool,JSONObject a){
    if(OWN_MEANING.contains(tool)) return; Object sv=a.get("subreport"); if(sv!=null && !String.valueOf(sv).trim().isEmpty()) return;
    // 기존 객체를 고르는 인자만: name 은 컨트롤 선택자인 도구에서만(crf_set_table 의 name 은 새 이름), 새로 만드는 도구의 table/name 은 그대로
    if(CREATE.contains(tool)) return;
    for(String k: NAME_SELECTOR.contains(tool)?new String[]{"table","name"}:new String[]{"table"}){ Object v=a.get(k); if(!(v instanceof String)) continue; String s=(String)v; int i=s.lastIndexOf('>'); if(i<=0||i==s.length()-1) continue;
      a.put("subreport",s.substring(0,i).trim()); a.put(k,s.substring(i+1).trim()); return; }
  }
}
