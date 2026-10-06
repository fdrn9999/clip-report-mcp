import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.RexObjectList;
import com.clipsoft.clipreport.base.controls.Control;
import com.clipsoft.clipreport.base.controls.ControlSubreport;
import com.clipsoft.clipreport.base.datas.DataSet;
import com.clipsoft.clipreport.base.datas.DataSetItemNormal;
import com.clipsoft.clipreport.base.datas.conections.Connection;
import com.clipsoft.clipreport.base.datas.fields.FieldData;
import com.clipsoft.clipreport.base.enums.DataAccessMethod;
import com.clipsoft.clipreport.base.functions.WebLinkInfo;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.sections.Section;
import com.clipsoft.clipreport.base.sections.SubSection;
import com.clipsoft.clipreport.base.sections.SubSectionSubreport;
import com.clipsoft.clipreport.oof.OOFDocument;
import com.clipsoft.clipreport.oof.OOFFile;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * crf_render — 리포트를 **실제 CLIP 서버 엔진**으로 PDF 로 출력한다(WAS 없이).
 *
 * 디자이너 jar(Viewer.jar)에는 출력 엔진(ClipReportExport)이 없으므로, 리포트 서버 웹앱의 WEB-INF/lib 를 classpath 로 한
 * **별도 java 프로세스**에서 이 클래스의 main 을 돌린다(같은 패키지의 디자이너 jar 와 섞이지 않게).
 *  - mode=sample : 리포트 사본의 모든 데이터셋을 CSV 메모 데이터로 바꿔 가상 데이터로 출력(DB 접속 없음, 쿼리 실행 안 함)
 *  - mode=db     : 서버가 하는 그대로(DataConnection.properties 의 DB 로 쿼리 실행) 원본 리포트를 출력
 * 서블릿 요청은 동적 프록시로 흉내 낸다. 엔진 설정 폴더(WEB-INF/clipreport5)는 임시 폴더에 복사해 쓰므로
 * 엔진이 시작할 때 하는 temp/log 정리가 웹앱 소스 트리를 건드리지 않는다.
 */
public class CrfRender {
  static String env(String k){ String v=System.getenv(k); if(v==null||v.isEmpty()){ try{ v=CrfMcpServer.loadDotEnv().get(k); }catch(Throwable t){} } return v==null?"":v.trim(); }
  static String s(JSONObject a,String k){ Object v=a.get(k); return v==null?null:v.toString(); }

  // ───────────────────────── 부모(MCP 서버) 쪽 ─────────────────────────
  @SuppressWarnings("unchecked")
  static JSONObject render(JSONObject args) throws Exception {
    String path=s(args,"path"); if(path==null||path.trim().isEmpty()) return CrfMcpServer.textContent("ERROR: path 인자가 비어 있습니다 (.crf 절대경로 필요)");
    File crf=new File(path.trim()); if(!crf.isFile()) return CrfMcpServer.textContent("ERROR: 파일 없음: "+path);
    String mode=s(args,"mode"); mode=mode==null||mode.trim().isEmpty()?"sample":mode.trim().toLowerCase();
    if(!mode.equals("sample")&&!mode.equals("db")) return CrfMcpServer.textContent("ERROR: mode 는 sample(가상 데이터, 기본) 또는 db(실제 쿼리 실행)");
    String webapp=env("CLIP_RENDER_WEBAPP"), lib=env("CLIP_RENDER_LIB"), props=env("CLIP_RENDER_PROPS");
    if(lib.isEmpty()&&!webapp.isEmpty()) lib=new File(webapp,"WEB-INF/lib").getPath();
    if(props.isEmpty()&&!webapp.isEmpty()) props=new File(webapp,"WEB-INF/clipreport5/clipreport5.properties").getPath();
    String setup="crf_render 는 CLIP report **서버** 엔진이 필요합니다(디자이너 설치본에는 출력 엔진이 없음). .env 또는 환경변수에\n"
      +"  CLIP_RENDER_WEBAPP=<리포트 서버 웹앱 폴더>   (그 안의 WEB-INF/lib/ClipReport5.*.jar 와 WEB-INF/clipreport5/clipreport5.properties·License.properties 를 씀)\n"
      +"를 넣으세요. 폴더 구조가 다르면 CLIP_RENDER_LIB(jar 폴더)·CLIP_RENDER_PROPS(clipreport5.properties)를 따로, JDBC 드라이버 등 추가 jar 는 CLIP_RENDER_CP 로 지정합니다.";
    if(lib.isEmpty()||props.isEmpty()) return CrfMcpServer.textContent("ERROR: 서버 엔진 위치 미설정 — "+setup);
    File libDir=new File(lib), propsF=new File(props);
    if(!libDir.isDirectory()||!propsF.isFile()) return CrfMcpServer.textContent("ERROR: 서버 엔진을 찾지 못했습니다 (jar 폴더 "+lib+(libDir.isDirectory()?"":" 없음")+", 설정 "+props+(propsF.isFile()?"":" 없음")+") — "+setup);
    File[] eng=libDir.listFiles((d,n)->n.matches("(?i)ClipReport\\d+(\\.\\d+)+\\.jar"));
    if(eng==null||eng.length==0) return CrfMcpServer.textContent("ERROR: "+lib+" 에 ClipReport5.x.jar(서버 엔진)가 없습니다 — "+setup);

    String pdfArg=s(args,"pdf"); File pdfOut=null;
    if(pdfArg!=null&&!pdfArg.trim().isEmpty()){ pdfOut=new File(pdfArg.trim());
      if(!pdfOut.getName().toLowerCase().endsWith(".pdf")) return CrfMcpServer.textContent("ERROR: pdf 는 .pdf 로 끝나는 경로여야 합니다 (받은 값 "+pdfArg+")");
      if(pdfOut.getCanonicalPath().equalsIgnoreCase(crf.getCanonicalPath())) return CrfMcpServer.textContent("ERROR: pdf 경로가 리포트 파일과 같습니다"); }
    int page=intArg(args,"page",1), maxPx=Math.max(400,Math.min(4000,intArg(args,"max_px",1600))), rows=Math.max(0,Math.min(5000,intArg(args,"rows",3)));
    File base=new File(System.getProperty("java.io.tmpdir"),"clip-report-mcp-render"); base.mkdirs(); purge(base);
    File work=Files.createTempDirectory(base.toPath(),"r").toFile();
    File pdf=new File(work,"out.pdf"), log=new File(work,"engine.log"), res=new File(work,"result.json");
    JSONObject job=new JSONObject(); job.put("crf",crf.getAbsolutePath()); job.put("props",propsF.getAbsolutePath()); job.put("work",work.getAbsolutePath());
    job.put("pdf",pdf.getAbsolutePath()); job.put("result",res.getAbsolutePath()); job.put("mode",mode); job.put("rows",(long)rows);
    String db=s(args,"db"); if(db==null||db.trim().isEmpty()) db=env("CLIP_RENDER_DBNAME"); job.put("db",db);
    try{ job.put("params",jsonObj(args.get("params"))); job.put("data",jsonObj(args.get("data"))); }
    catch(Exception e){ return CrfMcpServer.textContent("ERROR: params/data 는 JSON 객체여야 합니다 — "+e.getMessage()); }
    String df=s(args,"data_file"); if(df!=null&&!df.trim().isEmpty()){ File f=new File(df.trim()); if(!f.isFile()) return CrfMcpServer.textContent("ERROR: data_file 없음: "+df); if(f.length()>20L*1024*1024) return CrfMcpServer.textContent("ERROR: data_file 이 너무 큽니다("+f.length()/1024/1024+"MB, 20MB 까지)");
      ((JSONObject)job.get("data")).put("*",new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8).replace("\uFEFF","")); }
    Files.write(new File(work,"job.json").toPath(),job.toJSONString().getBytes(StandardCharsets.UTF_8));

    // classpath: 서버 lib 먼저, 그다음 이 jar, 추가 jar(CLIP_RENDER_CP), MCP 서버 classpath 중 디자이너 jar 폴더 밖의 것(JDBC 드라이버)
    List<String> cp=new ArrayList<>(); cp.add(new File(libDir,"*").getPath());
    String self=new File(CrfRender.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath(); cp.add(self);
    if(!env("CLIP_RENDER_CP").isEmpty()) cp.add(env("CLIP_RENDER_CP"));
    String designerDir=""; try{ designerDir=new File(Rexpert4.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile().getCanonicalPath(); }catch(Exception e){}
    for(String e: System.getProperty("java.class.path","").split(File.pathSeparator)){ if(e.isEmpty()) continue; File f=new File(e);
      try{ if(f.isFile() && !f.getCanonicalFile().getParent().equalsIgnoreCase(designerDir) && !f.getCanonicalPath().equalsIgnoreCase(new File(self).getCanonicalPath())) cp.add(f.getPath()); }catch(IOException ex){} }
    // mode=db: 서버 lib 에 JDBC 드라이버가 없으면(WAS 공용 lib 에 두는 경우) 디자이너 jar 폴더의 드라이버를 뒤에 붙인다
    String jdbcRe="(?i).*(tibero|ojdbc|mysql|mariadb|postgres|sqljdbc|mssql|cubrid|altibase).*\\.jar";   // jconn(Sybase)·db2jcc 는 뺀다: DriverManager 초기화 때 재귀/잡음을 일으킴
    if(mode.equals("db") && !designerDir.isEmpty()){ File[] have=libDir.listFiles((d,n)->n.matches(jdbcRe)); boolean inCp=false; for(String e: cp) if(new File(e).getName().matches(jdbcRe)) inCp=true;
      if((have==null||have.length==0) && !inCp){ File[] dj=new File(designerDir).listFiles((d,n)->n.matches(jdbcRe)); if(dj!=null) for(File x: dj) cp.add(x.getPath()); } }
    String javaExe=new File(System.getProperty("java.home"),"bin/java"+(File.separatorChar=='\\'?".exe":"")).getPath();
    ProcessBuilder pb=new ProcessBuilder(javaExe,"-Dfile.encoding=UTF-8","-Djava.awt.headless=true","-Xmx1g","-cp",String.join(File.pathSeparator,cp),"CrfRender",new File(work,"job.json").getPath());
    pb.redirectErrorStream(true).redirectOutput(log); pb.directory(work);
    int timeout=Math.max(10,intEnv("CLIP_RENDER_TIMEOUT",120));
    Process p=pb.start(); boolean done=p.waitFor(timeout,java.util.concurrent.TimeUnit.SECONDS);
    if(!done){ p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); boolean dead=p.waitFor(15,java.util.concurrent.TimeUnit.SECONDS); clean(work);
      if(!dead) return CrfMcpServer.textContent("ERROR: 렌더가 "+timeout+"초 안에 끝나지 않았고 엔진 프로세스(pid "+p.pid()+")도 종료되지 않았습니다 — 작업 관리자에서 java 프로세스를 확인하세요.\n"+tail(log,12));
      return CrfMcpServer.textContent("ERROR: 렌더가 "+timeout+"초 안에 끝나지 않아 중단했습니다(CLIP_RENDER_TIMEOUT 으로 조정). mode=db 면 쿼리가 오래 걸리거나 DB 에 닿지 못한 것일 수 있습니다.\n"+tail(log,12)); }
    JSONObject r=null; if(res.isFile()) try{ r=(JSONObject)new JSONParser().parse(new String(Files.readAllBytes(res.toPath()),StandardCharsets.UTF_8)); }catch(Exception e){}
    if(r==null||!"ok".equals(r.get("status"))||!pdf.isFile()||pdf.length()==0){ clean(work); pdf.delete();
      return CrfMcpServer.textContent("ERROR: 렌더 실패"+(r!=null&&r.get("error")!=null?" — "+r.get("error"):" (엔진 종료 코드 "+p.exitValue()+")")+"\n"+notes(r)+"--- 엔진 로그(끝) ---\n"+tail(log,15)+"\n(전체 로그: "+log.getPath()+")"); }

    clean(work);
    File outPdf=pdf;
    if(pdfOut!=null){ outPdf=pdfOut; if(outPdf.getAbsoluteFile().getParentFile()!=null) outPdf.getAbsoluteFile().getParentFile().mkdirs(); Files.copy(pdf.toPath(),outPdf.toPath(),StandardCopyOption.REPLACE_EXISTING); pdf.delete(); }
    StringBuilder b=new StringBuilder();
    b.append(crf.getName()).append(" — 실제 엔진 출력(").append(mode.equals("db")?"DB 쿼리 실행":"가상 데이터, 쿼리 실행 안 함").append("), 엔진 ").append(eng[0].getName()).append('\n');
    b.append("PDF: ").append(outPdf.getPath()).append('\n').append(notes(r));
    if(mode.equals("db")) b.append(loaded(log));
    String errs=grep(log,"(?i)\\[ERROR|Exception|JDBC-\\d|ORA-\\d",6); if(!errs.isEmpty()) b.append("⚠ 엔진 로그의 오류 줄:\n").append(errs);
    Object[] img=CrfDocs.renderPdf(outPdf,page,maxPx);
    if(img[0]==null) return CrfMcpServer.textContent("ERROR: "+img[1]+"\n"+b);
    b.append("그림: ").append(img[1]).append('\n');
    String txt=pageText(outPdf,page); if(!txt.isEmpty()) b.append("--- ").append(page).append("쪽 글자 ---\n").append(txt.length()>2500?txt.substring(0,2500)+"\n…(생략 — doc_text 로 전체)":txt).append('\n');
    b.append("이 그림은 데이터 행 반복·확장·페이지 나눔·소계·서브리포트까지 반영된 실제 출력입니다. 값이 맞는지, 글자·표가 잘리지 않는지, 다음 쪽에서 머리글이 이어지는지 확인하세요"
      +(mode.equals("sample")?" (가상 데이터라 쿼리·실제 수치는 검증되지 않습니다 — mode=db 또는 업무 화면에서 확인).":"."));
    return CrfDocs.imageContent(b.toString(),(java.awt.image.BufferedImage)img[0],false);
  }
  /** 하루 지난 작업 폴더 삭제(출력 PDF 에 실제 데이터가 들어 있을 수 있다). */
  static void purge(File base){ File[] ds=base.listFiles(); if(ds==null) return; long old=System.currentTimeMillis()-24L*3600*1000; for(File d: ds) if(d.isDirectory() && d.lastModified()<old) rmrf(d); }
  /** 리포트 사본·엔진 설정 사본(DB 접속정보·라이선스 포함)·넘긴 데이터는 결과와 상관없이 지운다. 남는 것은 PDF 와 엔진 로그뿐. */
  static void clean(File work){ rmrf(new File(work,"root")); rmrf(new File(work,"tree")); new File(work,"job.json").delete(); new File(work,"result.json").delete(); }
  static void rmrf(File f){ File[] c=f.listFiles(); if(c!=null) for(File x: c) rmrf(x); f.delete(); }
  static int intArg(JSONObject a,String k,int d){ try{ String v=s(a,k); return v==null||v.trim().isEmpty()?d:Integer.parseInt(v.trim()); }catch(Exception e){ return d; } }
  static int intEnv(String k,int d){ try{ String v=env(k); return v.isEmpty()?d:Integer.parseInt(v); }catch(Exception e){ return d; } }
  static JSONObject jsonObj(Object v) throws Exception { if(v==null) return new JSONObject(); if(v instanceof JSONObject) return (JSONObject)v; String t=v.toString().trim(); if(t.isEmpty()) return new JSONObject(); return (JSONObject)new JSONParser().parse(t); }
  static String notes(JSONObject r){ if(r==null) return ""; StringBuilder b=new StringBuilder(); Object n=r.get("notes"); if(n instanceof JSONArray) for(Object o:(JSONArray)n) b.append(o).append('\n'); return b.toString(); }
  interface LineFn { void line(String l); }
  /** 엔진 로그를 통째로 올리지 않고 줄 단위로 읽는다(아주 긴 줄은 잘라서 넘김). */
  static void eachLine(File f,LineFn fn){ try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8),1<<16)){ StringBuilder b=new StringBuilder(); int c;
      while((c=r.read())>=0){ if(c=='\n'){ fn.line(b.toString()); b.setLength(0); } else if(c!='\r' && b.length()<4000) b.append((char)c); } if(b.length()>0) fn.line(b.toString()); }catch(IOException e){} }
  static String tail(File f,int n){ ArrayDeque<String> q=new ArrayDeque<>(); eachLine(f,l->{ q.add(cut(l)); if(q.size()>n) q.poll(); }); StringBuilder b=new StringBuilder(); for(String l: q) b.append(l).append('\n'); return b.toString(); }
  static String grep(File f,String re,int max){ java.util.regex.Pattern p=java.util.regex.Pattern.compile(re); StringBuilder b=new StringBuilder(); int[] n={0};
    eachLine(f,l->{ if(!l.contains("glyphlist.txt") && p.matcher(l).find()){ if(n[0]++<max) b.append("  ").append(cut(l)).append('\n'); } }); if(n[0]>max) b.append("  … 외 ").append(n[0]-max).append("줄\n"); return b.toString(); }
  /** 엔진 로그의 "[데이터셋] Database data load rows : N" → 데이터셋별 읽은 행 수 */
  static String loaded(File f){ java.util.regex.Pattern p=java.util.regex.Pattern.compile("\\[INFO\\] - \\[(.+?)\\] .*data load rows : (\\d+)"); Map<String,String> m=new LinkedHashMap<>();
    eachLine(f,l->{ java.util.regex.Matcher x=p.matcher(l); if(x.find()) m.put(x.group(1),x.group(2)); });
    StringBuilder b=new StringBuilder(); for(Map.Entry<String,String> e: m.entrySet()) b.append("데이터셋 ").append(e.getKey()).append(": ").append(e.getValue()).append("행 (DB)\n"); return b.toString(); }
  static String cut(String l){ l=l.replaceAll("^CLIPreport Server \\w+ [\\d-]+ [\\d:.]+ (\\w{30,} )?",""); return l.length()>300?l.substring(0,300)+"…":l; }
  static String pageText(File pdf,int page){ try(org.apache.pdfbox.pdmodel.PDDocument d=org.apache.pdfbox.pdmodel.PDDocument.load(pdf)){ org.apache.pdfbox.text.PDFTextStripper st=new org.apache.pdfbox.text.PDFTextStripper(); st.setStartPage(page); st.setEndPage(page); st.setSortByPosition(true); return st.getText(d).trim(); }catch(Throwable t){ return ""; } }

  // ───────────────────────── 자식 프로세스(서버 엔진 classpath) ─────────────────────────
  static String root;
  static final Map<String,Object> attrs=new HashMap<>();
  static Object servlet(Class<?> type) {
    return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
      String n=m.getName();
      if(n.equals("getServletContext")) return servlet(Class.forName("javax.servlet.ServletContext"));
      if(n.equals("getSession")) return servlet(Class.forName("javax.servlet.http.HttpSession"));
      if(n.equals("getRealPath")) return root+String.valueOf(a[0]).replace('/',File.separatorChar);
      if(n.equals("getAttribute")) return attrs.get(a[0]);
      if(n.equals("setAttribute")){ attrs.put((String)a[0],a[1]); return null; }
      if(n.equals("getAttributeNames")||n.equals("getInitParameterNames")||n.equals("getHeaderNames")||n.equals("getParameterNames")) return Collections.emptyEnumeration();
      if(n.equals("getParameterMap")) return Collections.emptyMap();
      if(n.equals("getRemoteAddr")||n.equals("getLocalAddr")) return "127.0.0.1";
      if(n.equals("getServerName")||n.equals("getLocalName")) return "localhost";
      if(n.equals("getId")) return "CRF_RENDER";
      if(n.equals("getContextPath")) return "";
      if(n.equals("getScheme")) return "http";
      if(n.equals("getServerPort")) return 80;
      if(n.equals("getRequestURL")) return new StringBuffer("http://localhost/crf-render");
      if(n.equals("getRequestURI")) return "/crf-render";
      if(n.equals("getLocale")) return Locale.KOREA;
      if(n.equals("getLocales")) return Collections.enumeration(Collections.singletonList(Locale.KOREA));
      if(n.equals("hashCode")) return System.identityHashCode(p);
      if(n.equals("equals")) return p==a[0];
      if(n.equals("toString")) return "crf-render "+type.getSimpleName();
      Class<?> rt=m.getReturnType();
      if(rt==boolean.class) return false; if(rt==int.class) return 0; if(rt==long.class) return 0L;
      return null;
    });
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] argv) throws Exception {
    JSONObject job=(JSONObject)new JSONParser().parse(new String(Files.readAllBytes(Paths.get(argv[0])),StandardCharsets.UTF_8));
    JSONObject res=new JSONObject(); JSONArray notes=new JSONArray(); res.put("notes",notes); int code=2;
    try{
      File work=new File(s(job,"work")), crf=new File(s(job,"crf")), props=isolate(new File(s(job,"props")),work);
      boolean sample=!"db".equals(s(job,"mode"));
      OOFDocument doc=OOFDocument.newOOF(); OOFFile f;
      if(sample){
        TheReportFile rf=Rexpert4.read(crf.getPath());
        List<String[]> memo=toSample(rf,(JSONObject)job.get("data"),((Number)job.get("rows")).intValue(),notes);
        File dir=new File(work,"tree/d1/d2/d3/d4/d5/d6/d7/d8"); dir.mkdirs();   // 상대 링크(../../images/…)가 작업 폴더 안에 떨어지도록 깊게
        File copy=new File(dir,crf.getName()); CrfIO.write(rf,copy.getPath());
        copyLinks(rf,crf.getAbsoluteFile().getParentFile(),dir,work,notes);
        f=addFile(doc,copy.getPath());
        for(String[] m: memo) f.addConnectionMemo(m[0],m[1]).addContentParamCSV("*","UTF-8","\\t","\\r\\n","","0");
      } else {
        String db=s(job,"db"); if(db==null||db.isEmpty()) db=dbName(new File(s(job,"props")).getParentFile());
        if(db==null||db.isEmpty()) throw new RuntimeException("DB 이름을 알 수 없습니다 — db 인자 또는 CLIP_RENDER_DBNAME(DataConnection.properties 의 dataconnectionN.dbname)을 주세요");
        f=addFile(doc,crf.getPath()); f.addConnectionData("*",db); notes.add("DB 연결: "+db+" (서버의 DataConnection.properties)");
      }
      JSONObject params=(JSONObject)job.get("params");
      if(params!=null){ StringBuilder pl=new StringBuilder(); for(Object k: params.keySet()){ Object v=params.get(k); f.addField(k.toString(),v==null?"":v.toString()); pl.append(pl.length()>0?", ":"").append(k).append('=').append(v); } if(pl.length()>0) notes.add("매개변수: "+pl); }
      Class<?> ex=Class.forName("com.clipsoft.clipreport.server.service.ClipReportExport"), req=Class.forName("javax.servlet.http.HttpServletRequest"), opt=Class.forName("com.clipsoft.clipreport.export.option.PDFOption");
      Method m=ex.getMethod("createExportForPDF",req,OutputStream.class,String.class,OOFDocument.class,opt);
      int status; try(OutputStream out=new BufferedOutputStream(new FileOutputStream(s(job,"pdf")))){ status=(Integer)m.invoke(null,servlet(req),out,props.getPath(),doc,null); }
      if(status==0){ res.put("status","ok"); code=0; } else { res.put("status","fail"); res.put("error","엔진 상태 코드 "+status+" (0 이 정상)"); }
    }catch(Throwable t){ Throwable c=t; while(c instanceof java.lang.reflect.InvocationTargetException && c.getCause()!=null) c=c.getCause(); c.printStackTrace();
      res.put("status","fail"); res.put("error",c.getClass().getSimpleName()+": "+c.getMessage()); }
    Files.write(Paths.get(s(job,"result")),res.toJSONString().getBytes(StandardCharsets.UTF_8));
    System.exit(code);
  }

  // addFile 은 오버로드에 디자이너 jar 에 없는 클래스(CreateReport)가 있어 직접 부르면 컴파일이 안 된다.
  static OOFFile addFile(OOFDocument doc,String path) throws Exception { return (OOFFile)OOFDocument.class.getMethod("addFile",String.class,String.class).invoke(doc,"crf.root",path); }

  /** 엔진 설정 폴더를 work/root/WEB-INF/clipreport5 로 복사(파일만) — 쓰기 경로는 사본, 글꼴 등 읽기 경로는 원본을 가리킨다. */
  static File isolate(File props,File work) throws IOException {
    File cfg=props.getAbsoluteFile().getParentFile(), rootDir=new File(work,"root"), t=new File(rootDir,"WEB-INF/clipreport5"); t.mkdirs();
    root=rootDir.getPath();
    File[] fs=cfg.listFiles(); if(fs!=null) for(File x: fs) if(x.isFile() && !x.equals(props)) Files.copy(x.toPath(),new File(t,x.getName()).toPath(),StandardCopyOption.REPLACE_EXISTING);
    String orig=cfg.getPath().replace('\\','/'); StringBuilder b=new StringBuilder();
    for(String l: Files.readAllLines(props.toPath(),StandardCharsets.ISO_8859_1)){
      String k=l.contains("=")?l.substring(0,l.indexOf('=')).trim():"";
      if(k.equals("base.scheduler.active")) l="base.scheduler.active=false";
      else if(k.equals("base.log.write")) l="base.log.write=console";
      else if(!k.isEmpty() && !l.trim().startsWith("#") && !k.equals("base.path.reportfolder") && !k.equals("base.path.logfolder") && !k.equals("base.path.dbfile")) l=l.replace("%root%",orig);
      b.append(l).append("\r\n"); }
    File out=new File(t,props.getName()); Files.write(out.toPath(),b.toString().getBytes(StandardCharsets.ISO_8859_1));
    for(String d: new String[]{"temp","logs","log","tempBarcode"}) new File(t,d).mkdirs();
    return out;
  }
  static String dbName(File cfg){ try{ Properties p=new Properties(); try(InputStream in=new FileInputStream(new File(cfg,"DataConnection.properties"))){ p.load(in); } return p.getProperty("dataconnection1.dbname","").trim(); }catch(Exception e){ return ""; } }

  /** 모든 데이터셋을 CSV 메모 데이터로: 데이터셋마다 연결 하나(MEMO_i)를 만들고 그 이름으로 TSV 를 넘긴다. 반환 [연결 이름, TSV] */
  @SuppressWarnings("unchecked")
  static List<String[]> toSample(TheReportFile rf,JSONObject data,int rows,JSONArray notes){
    // 그룹 필드는 값이 행마다 다르면 행마다 그룹이 끊기므로, 바깥 그룹일수록 길게 같은 값을 준다(그룹 3개 안팎).
    Map<Object,Integer> span=new IdentityHashMap<>();
    try{ RexObjectList<?> gl=(RexObjectList<?>)rf.getGlobe().getMainReport().getReportObjectManager().getGroupList(); int n=gl.size();
      for(int i=0;i<n;i++){ Object gf=gl.get(i).getClass().getMethod("getGroupingField").invoke(gl.get(i)); if(gf!=null) span.put(gf,Math.max(1,(int)Math.ceil(rows/Math.pow(3,i+1)))); } }catch(Throwable t){}
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); RexObjectList<DataSet> dss=(RexObjectList<DataSet>)gom.getDataSetList();
    RexObjectList<Connection> conns=(RexObjectList<Connection>)gom.getConnectionList();
    List<String[]> out=new ArrayList<>(); Set<String> used=new HashSet<>(); Set<Object> keep=Collections.newSetFromMap(new IdentityHashMap<>());
    for(int i=0;i<dss.size();i++){ DataSet ds=dss.get(i); DataSetItemNormal n=ds.getDataSetItemNormal(); if(n==null){ notes.add("⚠ 데이터셋 "+ds.getName()+": 일반 데이터셋이 아니라 가상 데이터를 넣지 못했습니다 — 원래 연결 그대로라 이 데이터셋을 쓰는 부분은 비거나 오류가 날 수 있습니다"); continue; }
      RexObjectList<FieldData> fl=(RexObjectList<FieldData>)ds.getFieldDataList(); List<String> names=new ArrayList<>(); for(int j=0;j<fl.size();j++) names.add(fl.get(j).getName());
      Object given=data==null?null:data.get(ds.getName()); if(given==null && data!=null && dss.size()==1) given=data.get("*"); if(given!=null) used.add(ds.getName());
      String tsv; int cnt; String src; boolean over=false;
      if(given instanceof JSONArray){ StringBuilder b=new StringBuilder(); cnt=0; for(Object row:(JSONArray)given){ List<String> cells=new ArrayList<>();
          if(row instanceof JSONObject){ JSONObject o=(JSONObject)row; for(String nm: names){ Object v=o.get(nm); if(v==null) for(Object k: o.keySet()) if(k.toString().equalsIgnoreCase(nm)){ v=o.get(k); break; } cells.add(cell(v)); } }
          else if(row instanceof JSONArray){ for(Object v:(JSONArray)row) cells.add(cell(v)); if(cells.size()>names.size()){ cells=new ArrayList<>(cells.subList(0,names.size())); over=true; } while(cells.size()<names.size()) cells.add(""); }
          else cells.add(cell(row));
          b.append(String.join("\t",cells)).append("\r\n"); cnt++; } tsv=b.toString(); src="data"; }
      else if(given!=null){ tsv=given.toString().replace("\r\n","\n").replace('\r','\n'); while(tsv.endsWith("\n")) tsv=tsv.substring(0,tsv.length()-1); cnt=tsv.isEmpty()?0:tsv.split("\n",-1).length; tsv=tsv.isEmpty()?"":tsv.replace("\n","\r\n")+"\r\n"; src="data(TSV)"; }
      else { StringBuilder b=new StringBuilder(); for(int r=0;r<rows;r++){ List<String> cells=new ArrayList<>(); for(int j=0;j<fl.size();j++) cells.add(fake(fl.get(j),span.containsKey(fl.get(j))?r/span.get(fl.get(j)):r,j)); b.append(String.join("\t",cells)).append("\r\n"); } tsv=b.toString(); cnt=fl.size()==0?0:rows; src="자동 생성"; }
      if(names.isEmpty()){ tsv=""; cnt=0; }   // 필드가 없으면 넣을 칸이 없다
      if(over) notes.add("⚠ 데이터셋 "+ds.getName()+": data 의 행이 필드 수("+names.size()+")보다 길어 넘치는 값은 버렸습니다");
      String cn="MEMO_"+i; Connection c=new Connection(); c.setName(cn); conns.add(c); keep.add(c);
      n.setDataAccessMethod(DataAccessMethod.CSV); n.setLinkedConnection(c); n.getDataAccessMethodCSV().setRecordsetIndex(0);
      out.add(new String[]{cn,tsv});
      notes.add("데이터셋 "+ds.getName()+": "+cnt+"행 ("+src+"), 필드 "+names.size()+"개"+(names.isEmpty()?" — 필드가 없어 값이 안 나옵니다(crf_sync_fields)":""));
    }
    for(int i=0;i<dss.size();i++){ DataSetItemNormal n=dss.get(i).getDataSetItemNormal(); if(n!=null && n.getLinkedConnection()!=null) keep.add(n.getLinkedConnection()); }
    for(int i=conns.size()-1;i>=0;i--) if(!keep.contains(conns.get(i))) conns.remove(i);   // 아무 데이터셋도 안 쓰는 원래 연결(JDBC)은 사본에서 뺀다
    if(data!=null) for(Object k: data.keySet()) if(!used.contains(k.toString()) && !(k.equals("*")&&dss.size()==1)) notes.add("⚠ data 의 '"+k+"' 에 해당하는 데이터셋이 없습니다(이름 확인: crf_summary)");
    return out;
  }
  static String cell(Object v){ return v==null?"":v.toString().replace('\t',' ').replace("\r\n"," ").replace('\n',' ').replace('\r',' '); }
  /** 필드 이름·타입으로 그럴듯한 가상 값: 칸마다 다른 값이라 잘못 연결된 칸을 찾기 쉽다. */
  static String fake(FieldData f,int r,int j){
    String n=f.getName()==null?"":f.getName().toUpperCase(), t=String.valueOf(f.getDataType());
    if(t.equals("Number")||t.equals("Currency")||n.matches(".*(_AMT|_AMOUNT|_PRICE|_PAY|_SUM|_TOT|_TOTAL)\\d*$")) return String.valueOf((r+1)*1000+(j+1)*10);
    if(n.matches(".*(_CNT|_QTY|_NUM|_SEQ|_NO|_RANK|_ORD|_SCORE|_POINT|_CDT|_RATE|_PER)\\d*$")||n.equals("NO")||n.equals("RN")||n.equals("RNUM")) return String.valueOf(r+1+j);
    if(t.equals("DateTime")||n.matches(".*(_DT|_DATE|_YMD|_DAY)\\d*$")) return String.format("202601%02d",(r%28)+1);
    if(n.matches(".*(_YY|_YEAR)$")) return "2026";
    if(t.equals("Boolean")||n.matches(".*(_YN|_FG|_FLAG)$")) return r%2==0?"Y":"N";
    return f.getName()+(r+1);
  }
  /** 엔진은 링크 서브리포트(공통 로고 등)를 리포트 파일 위치 기준 상대경로로만 찾는다 → 사본 옆 같은 상대 위치에 링크 대상도 복사한다. */
  @SuppressWarnings("unchecked")
  static void copyLinks(TheReportFile rf,File base,File copyDir,File work,JSONArray notes){
    try{
      List<WebLinkInfo> links=new ArrayList<>();
      RexObjectList<Section> secs=(RexObjectList<Section>)rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
      for(int i=0;i<secs.size();i++){ RexObjectList<?> subs=(RexObjectList<?>)secs.get(i).getSubSectionList();
        for(int j=0;j<subs.size();j++) if(subs.get(j) instanceof SubSectionSubreport) links.add(((SubSectionSubreport)subs.get(j)).getLinkedSubreportPath()); }
      for(Object[] e: CrfMcpServer.allControls(rf)) if(e[3] instanceof ControlSubreport) links.add(((ControlSubreport)e[3]).getLinkedSubreportPath());
      Set<String> seen=new HashSet<>(); String w=work.getCanonicalPath()+File.separator;
      for(WebLinkInfo l: links){ if(l==null) continue; String u=l.getUrlText(); if(u==null||u.trim().isEmpty()||u.matches("(?i)https?://.*")||!seen.add(u)) continue;
        File src=new File(base,u).getCanonicalFile(), dst=new File(copyDir,u).getCanonicalFile();
        if(!src.isFile()){ notes.add("⚠ 링크 서브리포트 파일 없음: "+u); continue; }
        if(!src.getName().toLowerCase().endsWith(".crf")){ notes.add("⚠ 링크 서브리포트 "+u+": .crf 가 아니라 가상 출력에서는 뺍니다"); continue; }
        if(!dst.getPath().startsWith(w)){ notes.add("⚠ 링크 서브리포트 "+u+": 상위 폴더를 너무 많이 올라가 가상 출력에서는 빠집니다"); continue; }
        dst.getParentFile().mkdirs(); Files.copy(src.toPath(),dst.toPath(),StandardCopyOption.REPLACE_EXISTING);
        notes.add("링크 서브리포트 "+u+" 포함(자체 쿼리 데이터셋이 있으면 그 부분은 비어 나옵니다)"); }
    }catch(Throwable t){ notes.add("⚠ 링크 서브리포트 처리 실패: "+t); }
  }
}
