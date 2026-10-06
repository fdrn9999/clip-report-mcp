import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 문서 양식 파서 — 리포트로 옮길 양식을 PDF 뿐 아니라 HWP / HWPX / DOCX / XLSX / XLS / PPTX / 이미지로 받았을 때 읽는다.
 *  doc_text : 글자 + 배치(용지·여백, 문단 정렬/글꼴/크기, 표의 셀 주소·병합·크기, 도형 위치)를 mm 로. 리포트 좌표(0.1mm)는 mm×10.
 *  doc_image: 이미지 파일 / PDF 쪽 / HWP·HWPX·OOXML 에 들어 있는 미리보기 그림을 MCP image 로 돌려줘 모델이 직접 본다(OCR 엔진은 없음).
 *  의존성: OOXML/HWPX 는 zip + JDK XML, HWP 는 CLIP 엔진 jar 폴더의 poi(POIFS) + 레코드 직접 해석, XLS 는 poi HSSF, PDF 는 pdfbox. */
public class CrfDocs {
  static final long MAX_ENTRY=64L*1024*1024, MAX_TOTAL=256L*1024*1024, MAX_PIXELS=120_000_000L;
  /** 한 번의 도구 호출이 zip 에서 풀어 읽은 총 바이트(항목별 한도와 별개의 전체 예산) */
  static final ThreadLocal<long[]> USED=ThreadLocal.withInitial(()->new long[1]);
  static final double PT=25.4/72, HU=25.4/7200, TW=25.4/1440, EMU=1.0/36000;
  static String mm(double v){ String s=String.format(Locale.ROOT,"%.1f",v); return s.endsWith(".0")?s.substring(0,s.length()-2):s; }
  static double num(String v){ try{ return v==null||v.isEmpty()?0:Double.parseDouble(v.trim()); }catch(Exception e){ return 0; } }
  static String s(JSONObject a,String k){ Object v=a.get(k); return v==null?null:v.toString(); }
  static String quote(String t){ return "\""+t.replace("\r","").replace("\n","⏎").replace("\t","→")+"\""; }

  // ===================== 형식 판별 =====================
  static byte[] head(File f,int n) throws IOException { try(InputStream in=new FileInputStream(f)){ byte[] b=new byte[n]; int r=in.readNBytes(b,0,n); return Arrays.copyOf(b,Math.max(r,0)); } }
  static boolean starts(byte[] h,int... sig){ if(h.length<sig.length) return false; for(int i=0;i<sig.length;i++) if((h[i]&0xFF)!=sig[i]) return false; return true; }
  /** pdf | hwp | hwpx | docx | xlsx | pptx | xls | image | hwp3 | doc | ppt | zip | ole | unknown */
  static String kind(File f) throws IOException {
    byte[] h=head(f,32); String n=f.getName().toLowerCase(Locale.ROOT);
    if(starts(h,'%','P','D','F')) return "pdf";
    if(starts(h,0x89,'P','N','G')||starts(h,0xFF,0xD8)||starts(h,'G','I','F','8')||starts(h,'B','M')) return "image";
    if(new String(h,StandardCharsets.ISO_8859_1).startsWith("HWP Document File V")) return "hwp3";
    if(starts(h,'P','K')){ try(ZipFile z=new ZipFile(f)){
        if(z.getEntry("Contents/header.xml")!=null||z.getEntry("Contents/section0.xml")!=null) return "hwpx";
        if(z.getEntry("word/document.xml")!=null) return "docx"; if(z.getEntry("xl/workbook.xml")!=null) return "xlsx"; if(z.getEntry("ppt/presentation.xml")!=null) return "pptx"; }
      return "zip"; }
    if(starts(h,0xD0,0xCF,0x11,0xE0)){ if(n.endsWith(".hwp")) return "hwp"; if(n.endsWith(".xls")) return "xls"; if(n.endsWith(".doc")) return "doc"; if(n.endsWith(".ppt")) return "ppt";
      try(InputStream in=new FileInputStream(f)){ org.apache.poi.poifs.filesystem.POIFSFileSystem fs=new org.apache.poi.poifs.filesystem.POIFSFileSystem(in); org.apache.poi.poifs.filesystem.DirectoryEntry r=fs.getRoot();
        if(r.hasEntry("FileHeader")&&r.hasEntry("BodyText")) return "hwp"; if(r.hasEntry("Workbook")||r.hasEntry("Book")) return "xls"; if(r.hasEntry("WordDocument")) return "doc"; if(r.hasEntry("PowerPoint Document")) return "ppt"; }
      return "ole"; }
    String t=new String(head(f,2048),StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
    if(n.endsWith(".html")||n.endsWith(".htm")||n.endsWith(".xhtml")||t.contains("<!doctype html")||t.contains("<html")) return "html";
    return "unknown";
  }
  static String unsupported(String kind,File f){
    switch(kind){
      case "hwp3": return "ERROR: HWP 3.x(구버전) 형식은 지원하지 않습니다 — 한글에서 HWPX 또는 PDF 로 다시 저장해 주세요";
      case "doc": return "ERROR: 구버전 Word(.doc) 는 지원하지 않습니다 — .docx 또는 PDF 로 다시 저장해 주세요";
      case "ppt": return "ERROR: 구버전 PowerPoint(.ppt) 는 지원하지 않습니다 — .pptx 또는 PDF 로 다시 저장해 주세요";
      default: return "ERROR: 지원하지 않는 문서 형식입니다("+f.getName()+") — 지원: PDF, HWP(5.x), HWPX, DOCX, XLSX/XLSM, XLS, PPTX, HTML, 이미지(PNG/JPG/GIF/BMP)";
    }
  }

  // ===================== doc_text =====================
  static String text(JSONObject args) throws Exception {
    String path=s(args,"path"); if(path==null||path.trim().isEmpty()) return "ERROR: path 인자가 비어 있습니다(문서 파일 절대경로)";
    File f=new File(path.trim()); if(!f.isFile()) return "ERROR: 파일 없음: "+path;
    USED.get()[0]=0; boolean detail="true".equalsIgnoreCase(s(args,"detail")); int max=10; try{ max=Math.max(1000,Integer.parseInt(String.valueOf(args.get("max_chars")).trim())); }catch(Exception e){ max=30000; }
    String kind=kind(f), out;
    switch(kind){
      case "pdf": out=pdf(f,detail,s(args,"pages")); break;
      case "hwpx": try(ZipFile z=new ZipFile(f)){ out=hwpx(z,detail); } break;
      case "hwp": try{ out=hwp(f,detail); }catch(IndexOutOfBoundsException|NegativeArraySizeException|ClassCastException e){ return "ERROR: HWP 문서 구조를 해석하지 못했습니다(손상되었거나 지원하지 않는 변형) — HWPX/PDF 로 다시 저장해 주세요 ["+e+"]"; } break;
      case "docx": try(ZipFile z=new ZipFile(f)){ out=docx(z,detail); } break;
      case "xlsx": try(ZipFile z=new ZipFile(f)){ out=xlsx(z,s(args,"sheet"),detail); } break;
      case "xls": out=xls(f,s(args,"sheet"),detail); break;
      case "pptx": try(ZipFile z=new ZipFile(f)){ out=pptx(z,detail); } break;
      case "image": out=imageInfo(f); break;
      case "html": out=html(f,detail); break;
      default: return unsupported(kind,f);
    }
    if(out.startsWith("ERROR")) return out;
    String headLine=f.getName()+"  ["+kind.toUpperCase(Locale.ROOT)+", "+(f.length()/1024)+" KB]  단위 mm (리포트 좌표 0.1mm = mm×10)\n";
    if(out.length()>max) out=out.substring(0,max)+"\n...("+max+"자에서 잘림 — max_chars 를 늘리거나 "+(kind.equals("pdf")?"pages":kind.startsWith("xls")?"sheet":"detail=false")+" 로 범위를 줄이세요)";
    return headLine+out+(kind.equals("image")?"":"\n→ 다음: doc_image(path) 로 이 문서를 그림으로 받아 위 판독 결과와 대조(빠진 요소·병합·정렬·테두리)한 뒤 작업하세요.");
  }

  // ===================== XML / zip 도우미 =====================
  static byte[] bytes(ZipFile z,String name) throws IOException { ZipEntry e=z.getEntry(name); if(e==null) return null; try(InputStream in=z.getInputStream(e)){ ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] buf=new byte[65536]; int n; long tot=0; long[] used=USED.get();
      while((n=in.read(buf))>0){ tot+=n; used[0]+=n; if(tot>MAX_ENTRY) throw new IOException("압축 항목이 너무 큼: "+name); if(used[0]>MAX_TOTAL) throw new IOException("문서를 푼 크기가 한도("+MAX_TOTAL/1024/1024+"MB)를 넘음 — 압축 폭탄일 수 있어 중단"); o.write(buf,0,n); } return o.toByteArray(); } }
  static Document xml(byte[] data) throws Exception { if(data==null) return null; javax.xml.parsers.DocumentBuilderFactory fac=javax.xml.parsers.DocumentBuilderFactory.newDefaultInstance(); fac.setNamespaceAware(true);   // JDK 내장 파서(CLIP jar 폴더의 구버전 xerces 는 DOM 클래스가 안 맞아 못 씀)
    for(String[] ft: new String[][]{{"http://apache.org/xml/features/disallow-doctype-decl","true"},{"http://xml.org/sax/features/external-general-entities","false"},{"http://xml.org/sax/features/external-parameter-entities","false"},{"http://apache.org/xml/features/nonvalidating/load-external-dtd","false"}}) try{ fac.setFeature(ft[0],Boolean.parseBoolean(ft[1])); }catch(Throwable t){}
    try{ fac.setXIncludeAware(false); }catch(Throwable t){} fac.setExpandEntityReferences(false);
    javax.xml.parsers.DocumentBuilder db=fac.newDocumentBuilder(); db.setEntityResolver((p,sid)->new org.xml.sax.InputSource(new StringReader("")));
    return db.parse(new ByteArrayInputStream(data)); }
  static Document xml(ZipFile z,String name) throws Exception { return xml(bytes(z,name)); }
  static String ln(Node n){ String l=n.getLocalName(); if(l!=null) return l; String q=n.getNodeName(); int i=q.indexOf(':'); return i>=0?q.substring(i+1):q; }
  /** 속성(접두어 무시) */
  static String at(Element e,String name){ if(e==null) return null; NamedNodeMap m=e.getAttributes(); for(int i=0;i<m.getLength();i++){ Node a=m.item(i); if(ln(a).equals(name)) return a.getNodeValue(); } return null; }
  /** 관계 id(r:id) — 같은 요소에 접두어 없는 id 속성이 따로 있을 수 있어(p:sldId) 구분 */
  static String rid(Element e){ NamedNodeMap m=e.getAttributes(); for(int i=0;i<m.getLength();i++){ Node a=m.item(i); if(a.getNodeName().endsWith(":id")) return a.getNodeValue(); } return at(e,"id"); }
  static List<Element> kids(Node p,String... names){ List<Element> o=new ArrayList<>(); if(p==null) return o; for(Node c=p.getFirstChild();c!=null;c=c.getNextSibling()) if(c instanceof Element){ if(names.length==0) o.add((Element)c); else for(String n: names) if(ln(c).equals(n)){ o.add((Element)c); break; } } return o; }
  static Element kid(Node p,String name){ List<Element> k=kids(p,name); return k.isEmpty()?null:k.get(0); }
  static Element path(Node p,String... names){ Node c=p; for(String n: names){ c=kid(c,n); if(c==null) return null; } return (Element)c; }
  /** 후손(자기 제외) 중 이름이 맞는 것 — stop 이름의 요소 안으로는 내려가지 않음 */
  static void desc(Node p,String name,Set<String> stop,List<Element> out){ for(Node c=p.getFirstChild();c!=null;c=c.getNextSibling()) if(c instanceof Element){ String l=ln(c); if(l.equals(name)) out.add((Element)c); if(stop==null||!stop.contains(l)) desc(c,name,stop,out); } }
  static List<Element> desc(Node p,String name){ List<Element> o=new ArrayList<>(); desc(p,name,null,o); return o; }
  static Element first(Node p,String name){ for(Node c=p.getFirstChild();c!=null;c=c.getNextSibling()) if(c instanceof Element){ if(ln(c).equals(name)) return (Element)c; Element r=first(c,name); if(r!=null) return r; } return null; }
  static String pad(int depth){ StringBuilder b=new StringBuilder(); for(int i=0;i<depth;i++) b.append("  "); return b.toString(); }

  // ===================== HWPX =====================
  static class HwpxCtx { Map<String,String[]> charPr=new HashMap<>(); Map<String,String[]> paraPr=new HashMap<>(); Map<String,String> fonts=new HashMap<>(); boolean detail; StringBuilder b=new StringBuilder(); }
  static final Set<String> HWPX_SHAPES=new HashSet<>(Arrays.asList("rect","line","pic","container","ellipse","textart","polygon","curve","arc","connectLine","equation","ole"));
  static String hwpx(ZipFile z,boolean detail) throws Exception {
    HwpxCtx x=new HwpxCtx(); x.detail=detail;
    Document head=xml(z,"Contents/header.xml");
    if(head!=null){
      for(Element ff: desc(head,"fontface")) if("HANGUL".equalsIgnoreCase(at(ff,"lang"))) for(Element fo: kids(ff,"font")) x.fonts.put(at(fo,"id"),at(fo,"face"));
      for(Element cp: desc(head,"charPr")){ Element fr=kid(cp,"fontRef"), ul=kid(cp,"underline"); boolean under=ul!=null&&at(ul,"type")!=null&&!"NONE".equalsIgnoreCase(at(ul,"type"));
        x.charPr.put(at(cp,"id"),new String[]{at(cp,"height"),at(cp,"textColor"),kid(cp,"bold")!=null?"B":"",fr==null?null:x.fonts.get(at(fr,"hangul")),under?"U":"",kid(cp,"italic")!=null?"I":""}); }
      for(Element pp: desc(head,"paraPr")){ Element al=kid(pp,"align"), ls=first(pp,"lineSpacing"); x.paraPr.put(at(pp,"id"),new String[]{al==null?null:at(al,"horizontal"),ls==null?null:at(ls,"value")}); }
    }
    List<String> secs=new ArrayList<>(); for(Enumeration<? extends ZipEntry> en=z.entries(); en.hasMoreElements();){ String n=en.nextElement().getName(); if(n.matches("Contents/section\\d+\\.xml")) secs.add(n); }
    if(secs.size()>300) return "ERROR: HWPX 구역(section)이 "+secs.size()+"개 — 비정상적으로 많아 중단";
    secs.sort(Comparator.comparingInt(n->Integer.parseInt(n.replaceAll("\\D",""))));
    if(secs.isEmpty()) return "ERROR: HWPX 안에 Contents/section*.xml 이 없습니다";
    for(String sn: secs){ Document sec=xml(z,sn); x.b.append("== ").append(sn.substring(9)).append("\n");
      for(Element pp: desc(sec,"pagePr")){ Element mg=kid(pp,"margin"); x.b.append("용지 ").append(mm(num(at(pp,"width"))*HU)).append("×").append(mm(num(at(pp,"height"))*HU)).append("mm");
        if(mg!=null) x.b.append("  여백 왼").append(mm(num(at(mg,"left"))*HU)).append(" 오른").append(mm(num(at(mg,"right"))*HU)).append(" 위").append(mm(num(at(mg,"top"))*HU)).append(" 아래").append(mm(num(at(mg,"bottom"))*HU)).append(" 머리말").append(mm(num(at(mg,"header"))*HU)).append(" 꼬리말").append(mm(num(at(mg,"footer"))*HU));
        x.b.append("  (본문 너비 ").append(mm((num(at(pp,"width"))-(mg==null?0:num(at(mg,"left"))+num(at(mg,"right"))))*HU)).append("mm; 문단 y 는 본문 영역 위에서부터)\n"); }
      for(Element p: kids(sec.getDocumentElement(),"p")) hwpxPara(x,p,0); }
    return x.b.toString();
  }
  static String hwpxText(Element t){ StringBuilder sb=new StringBuilder(); for(Node c=t.getFirstChild();c!=null;c=c.getNextSibling()){ if(c.getNodeType()==Node.TEXT_NODE||c.getNodeType()==Node.CDATA_SECTION_NODE) sb.append(c.getNodeValue()); else if(c instanceof Element){ String l=ln(c); if(l.equals("lineBreak")) sb.append('\n'); else if(l.equals("tab")) sb.append('\t'); else if(l.equals("nbSpace")||l.equals("fwSpace")) sb.append(' '); else sb.append(hwpxText((Element)c)); } } return sb.toString(); }
  static String hwpxStyle(HwpxCtx x,String cid){ String[] cp=x.charPr.get(cid); if(cp==null) return ""; StringBuilder s=new StringBuilder(); if(cp[0]!=null) s.append(" ").append(mm(num(cp[0])/100)).append("pt"); String fl=cp[2]+cp[5]+cp[4]; if(!fl.isEmpty()) s.append(" ").append(fl); if(cp[3]!=null) s.append(" ").append(cp[3]); if(cp[1]!=null&&!cp[1].equalsIgnoreCase("#000000")) s.append(" ").append(cp[1]); return s.toString(); }
  static void hwpxPara(HwpxCtx x,Element p,int depth){
    String[] pa=x.paraPr.get(at(p,"paraPrIDRef")); String align=pa==null||pa[0]==null?"?":pa[0]; String ind=pad(depth);
    Element seg=path(p,"linesegarray","lineseg"); String y=seg!=null?" y="+mm(num(at(seg,"vertpos"))*HU):"";
    List<String[]> runs=new ArrayList<>(); List<Runnable> after=new ArrayList<>();
    for(Element run: kids(p,"run")){ String cid=at(run,"charPrIDRef");
      for(Element c: kids(run)){ String tag=ln(c);
        if(tag.equals("t")){ String t=hwpxText(c); if(!t.isEmpty()) runs.add(new String[]{t,cid}); }
        else if(tag.equals("tbl")){ final Element tbl=c; after.add(()->hwpxTable(x,tbl,depth,align)); }
        else if(HWPX_SHAPES.contains(tag)){ final Element sh=c; after.add(()->hwpxShape(x,sh,depth,align)); } } }
    StringBuilder txt=new StringBuilder(); for(String[] r: runs) txt.append(r[0]);
    if(txt.toString().trim().isEmpty()){ if(after.isEmpty()&&x.detail) x.b.append(ind).append("P").append(y).append(" (빈 줄)\n"); }
    else { x.b.append(ind).append("P").append(y).append(" ").append(align).append(hwpxStyle(x,runs.get(0)[1])).append(pa!=null&&pa[1]!=null&&x.detail?" 줄간격"+pa[1]:"").append(": ").append(quote(txt.toString())).append("\n");
      if(x.detail){ Set<String> ids=new LinkedHashSet<>(); for(String[] r: runs) ids.add(r[1]); if(ids.size()>1) for(String[] r: runs) x.b.append(ind).append("   · ").append(quote(r[0])).append(hwpxStyle(x,r[1])).append("\n"); } }
    for(Runnable r: after) r.run();
  }
  static String hwpxPos(Element e){ Element sz=kid(e,"sz"), pos=kid(e,"pos"); StringBuilder s=new StringBuilder(); if(sz!=null) s.append(" ").append(mm(num(at(sz,"width"))*HU)).append("×").append(mm(num(at(sz,"height"))*HU)).append("mm");
    if(pos!=null){ if("1".equals(at(pos,"treatAsChar"))) s.append(" 글자처럼"); else s.append(" 위치 가로 ").append(at(pos,"horzRelTo")).append("+").append(mm(num(at(pos,"horzOffset"))*HU)).append(" 세로 ").append(at(pos,"vertRelTo")).append("+").append(mm(num(at(pos,"vertOffset"))*HU)); } return s.toString(); }
  static void hwpxTable(HwpxCtx x,Element tbl,int depth,String align){ String ind=pad(depth);
    x.b.append(ind).append("[표 ").append(at(tbl,"rowCnt")).append("행×").append(at(tbl,"colCnt")).append("열").append(hwpxPos(tbl)).append(" 문단정렬=").append(align).append("]\n");
    for(Element tr: kids(tbl,"tr")) for(Element tc: kids(tr,"tc")){ Element ca=kid(tc,"cellAddr"), cs=kid(tc,"cellSpan"), sz=kid(tc,"cellSz");
      x.b.append(ind).append("  [").append(ca==null?"?":at(ca,"rowAddr")).append(",").append(ca==null?"?":at(ca,"colAddr")).append("]");
      if(cs!=null&&!("1".equals(at(cs,"rowSpan"))&&"1".equals(at(cs,"colSpan")))) x.b.append(" 병합 ").append(at(cs,"rowSpan")).append("×").append(at(cs,"colSpan"));
      if(sz!=null) x.b.append(" ").append(mm(num(at(sz,"width"))*HU)).append("×").append(mm(num(at(sz,"height"))*HU)).append("mm");
      Element sl=kid(tc,"subList"); final List<Element> ps=sl==null?new ArrayList<>():kids(sl,"p");
      if(ps.size()==1&&first(ps.get(0),"tbl")==null&&!HWPX_SHAPES.stream().anyMatch(n->first(ps.get(0),n)!=null)){ int before=x.b.length(); hwpxPara(x,ps.get(0),0); String line=x.b.substring(before); x.b.setLength(before); x.b.append(line.isEmpty()?"\n":" "+line.replaceFirst("^P( y=[\\d.-]+)? ","")); }
      else { x.b.append("\n"); for(Element sp: ps) hwpxPara(x,sp,depth+2); } }
  }
  static void hwpxShape(HwpxCtx x,Element sh,int depth,String align){ String tag=ln(sh); String name=tag.equals("rect")?"사각형":tag.equals("line")||tag.equals("connectLine")?"선":tag.equals("pic")?"그림":tag.equals("ellipse")?"타원":tag.equals("container")?"묶음":tag;
    x.b.append(pad(depth)).append("[").append(name).append(hwpxPos(sh)).append(" 문단정렬=").append(align).append("]\n");
    List<Element> subs=new ArrayList<>(); desc(sh,"subList",new HashSet<>(Arrays.asList("subList")),subs); for(Element sl: subs) for(Element sp: kids(sl,"p")) hwpxPara(x,sp,depth+2);
    if(tag.equals("container")) for(Element c: kids(sh)) if(HWPX_SHAPES.contains(ln(c))) hwpxShape(x,c,depth+1,align);
  }

  // ===================== HWP 5.x (바이너리) =====================
  static int u16(byte[] d,int o){ return o+2<=d.length?((d[o]&0xFF)|((d[o+1]&0xFF)<<8)):0; }
  static long u32(byte[] d,int o){ return o+4<=d.length?((d[o]&0xFFL)|((d[o+1]&0xFFL)<<8)|((d[o+2]&0xFFL)<<16)|((d[o+3]&0xFFL)<<24)):0; }
  static int i32(byte[] d,int o){ return (int)u32(d,o); }
  static byte[] stream(org.apache.poi.poifs.filesystem.DirectoryEntry dir,String name) throws IOException { if(!dir.hasEntry(name)) return null; org.apache.poi.poifs.filesystem.Entry e=dir.getEntry(name); if(!(e instanceof org.apache.poi.poifs.filesystem.DocumentEntry)) return null;
    if(((org.apache.poi.poifs.filesystem.DocumentEntry)e).getSize()>MAX_ENTRY) throw new IOException("HWP 스트림이 너무 큼: "+name);
    try(InputStream in=new org.apache.poi.poifs.filesystem.DocumentInputStream((org.apache.poi.poifs.filesystem.DocumentEntry)e)){ return in.readAllBytes(); } }
  static byte[] inflate(byte[] data) throws IOException { java.util.zip.Inflater inf=new java.util.zip.Inflater(true); inf.setInput(data); ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] buf=new byte[65536];
    try{ while(!inf.finished()){ int n=inf.inflate(buf); if(n==0&&(inf.needsInput()||inf.needsDictionary())) break; o.write(buf,0,n); if(o.size()>MAX_ENTRY) throw new IOException("압축 해제 결과가 너무 큼"); } }catch(java.util.zip.DataFormatException e){ throw new IOException("HWP 스트림 압축 해제 실패: "+e.getMessage()); } finally{ inf.end(); }
    return o.toByteArray(); }
  /** 레코드 순회: [tag, level, offset, size] */
  static List<int[]> records(byte[] d){ List<int[]> o=new ArrayList<>(); int p=0; while(p+4<=d.length){ long h=u32(d,p); int tag=(int)(h&0x3FF), level=(int)((h>>10)&0x3FF), size=(int)((h>>20)&0xFFF); p+=4; if(size==0xFFF){ if(p+4>d.length) break; size=(int)u32(d,p); p+=4; } if(size<0||size>d.length-p) break; o.add(new int[]{tag,level,p,size}); p+=size; } return o; }
  static String hwpParaText(byte[] d,int off,int size){ StringBuilder sb=new StringBuilder(); int n=size/2;
    for(int i=0;i<n;i++){ int c=u16(d,off+i*2);
      if(c>=32){ sb.append((char)c); continue; }
      switch(c){ case 10: sb.append('\n'); break; case 13: break; case 0: break; case 24: sb.append('-'); break; case 30: case 31: sb.append(' '); break; case 25: case 26: case 27: case 28: case 29: break;
        case 9: sb.append('\t'); i+=7; break;
        default: i+=7; }   // 확장/인라인 컨트롤(표·그림·필드 등): 8 WCHAR 차지
    }
    return sb.toString(); }
  static final String[] HWP_ALIGN={"JUSTIFY","LEFT","RIGHT","CENTER","DISTRIBUTE","DISTRIBUTE_SPACE"};
  static String hwp(File f,boolean detail) throws Exception {
    try(InputStream in=new FileInputStream(f)){
      org.apache.poi.poifs.filesystem.POIFSFileSystem fs=new org.apache.poi.poifs.filesystem.POIFSFileSystem(in); org.apache.poi.poifs.filesystem.DirectoryEntry root=fs.getRoot();
      byte[] fh=stream(root,"FileHeader"); if(fh==null||!new String(fh,0,Math.min(17,fh.length),StandardCharsets.ISO_8859_1).startsWith("HWP Document File")) return "ERROR: HWP 5.x 문서가 아닙니다(FileHeader 없음)";
      if(fh.length<40) return "ERROR: HWP FileHeader 가 손상되었습니다(길이 "+fh.length+")";
      long flags=u32(fh,36); boolean comp=(flags&1)!=0; String ver=(fh[35]&0xFF)+"."+(fh[34]&0xFF)+"."+(fh[33]&0xFF)+"."+(fh[32]&0xFF);
      if((flags&2)!=0) return "ERROR: 암호가 걸린 HWP 문서입니다 — 암호를 풀어 다시 저장하거나 PDF 로 주세요";
      if((flags&4)!=0||!root.hasEntry("BodyText")){ byte[] pv=stream(root,"PrvText"); return "ERROR: 배포용(읽기 전용) HWP 문서라 본문을 읽을 수 없습니다 — 일반 문서/HWPX/PDF 로 다시 저장해 주세요."+(pv==null?"":"\n미리보기 텍스트(앞부분만):\n"+new String(pv,StandardCharsets.UTF_16LE)); }
      // DocInfo: 글꼴 / 글자 모양 / 문단 모양
      List<String> faces=new ArrayList<>(); List<String> charShape=new ArrayList<>(); List<String> paraAlign=new ArrayList<>();
      byte[] di=stream(root,"DocInfo"); if(di!=null){ if(comp) di=inflate(di);
        for(int[] r: records(di)){ int o=r[2];
          if(r[0]==19){ int len=u16(di,o+1); faces.add(o+3+len*2<=o+r[3]?new String(di,o+3,len*2,StandardCharsets.UTF_16LE):"?"); }
          else if(r[0]==21&&r[3]>=56){ int face=u16(di,o); long attr=u32(di,o+46); long col=u32(di,o+52); String c=String.format("#%02X%02X%02X",col&0xFF,(col>>8)&0xFF,(col>>16)&0xFF);
            String fl=((attr&2)!=0?"B":"")+((attr&1)!=0?"I":"")+(((attr>>2)&3)!=0?"U":"");
            charShape.add(" "+mm(i32(di,o+42)/100.0)+"pt"+(fl.isEmpty()?"":" "+fl)+" {F"+face+"}"+(c.equals("#000000")?"":" "+c)); }
          else if(r[0]==25&&r[3]>=4){ int a=(int)((u32(di,o)>>2)&7); paraAlign.add(a<HWP_ALIGN.length?HWP_ALIGN[a]:"?"); } }
        for(int i=0;i<charShape.size();i++){ String cs=charShape.get(i); java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\{F(\\d+)\\}").matcher(cs); if(m.find()){ int fi=Integer.parseInt(m.group(1)); charShape.set(i,cs.replace(m.group(),fi<faces.size()?faces.get(fi):"")); } } }
      StringBuilder b=new StringBuilder("HWP "+ver+(comp?"":" (비압축)")+"\n");
      org.apache.poi.poifs.filesystem.DirectoryEntry body=(org.apache.poi.poifs.filesystem.DirectoryEntry)root.getEntry("BodyText");
      List<String> names=new ArrayList<>(); for(Iterator<org.apache.poi.poifs.filesystem.Entry> it=body.getEntries(); it.hasNext();){ String n=it.next().getName(); if(n.matches("Section\\d+")) names.add(n); }
      names.sort(Comparator.comparingInt(n->Integer.parseInt(n.substring(7))));
      for(String sn: names){ byte[] d=stream(body,sn); if(d==null) continue; if(comp) d=inflate(d); b.append("== ").append(sn).append("\n"); hwpSection(b,d,charShape,paraAlign,detail); }
      return b.toString();
    }
  }
  /** 표 문맥: ctrlLevel(표 컨트롤 레코드 level), 들여쓰기 깊이 */
  static void hwpSection(StringBuilder b,byte[] d,List<String> charShape,List<String> paraAlign,boolean detail){
    List<int[]> recs=records(d); Deque<int[]> tables=new ArrayDeque<>();   // [ctrlLevel]
    String pText=null, pStyle="", pAlign="?", pY=""; boolean pOpen=false, cellLine=false; String pendingCtrl=null; int secStart=b.length(), pLv=0;
    for(int ri=0;ri<=recs.size();ri++){ int[] r=ri<recs.size()?recs.get(ri):null; int tag=r==null?-1:r[0], lv=r==null?0:r[1], o=r==null?0:r[2], sz=r==null?0:r[3];
      boolean flush=r==null||tag==66||tag==71||tag==72;
      if(flush&&pOpen){ String ind=tables.isEmpty()?pad(pLv>0?2:0):pad(tables.size()*2); boolean empty=pText==null||pText.trim().isEmpty();   // 표 밖인데 level>0 이면 글상자/머리말 안의 문단(y 는 그 안에서의 위치)
        if(cellLine){ if(empty) b.append("\n"); else b.append(" ").append(pAlign).append(pStyle).append(": ").append(quote(pText)).append("\n"); cellLine=false; }
        else if(!empty) b.append(ind).append("P").append(pY).append(" ").append(pAlign).append(pStyle).append(": ").append(quote(pText)).append("\n");
        else if(detail&&pendingCtrl==null) b.append(ind).append("P").append(pY).append(" (빈 줄)\n");
        pOpen=false; pText=null; }
      if(r==null) break;
      while(!tables.isEmpty()&&lv<=tables.peek()[0]){ tables.pop(); if(cellLine){ b.append("\n"); cellLine=false; } }
      if(tag==66){ pOpen=true; pLv=lv; pText=null; pStyle=""; pY=""; int ps=u16(d,o+8); pAlign=ps<paraAlign.size()?paraAlign.get(ps):"?"; pendingCtrl=null; }
      else if(tag==67) pText=hwpParaText(d,o,sz);
      else if(tag==68&&sz>=8){ int id=(int)u32(d,o+4); pStyle=id<charShape.size()?charShape.get(id):""; }
      else if(tag==69&&sz>=8) pY=" y="+mm(i32(d,o+4)*HU);
      else if(tag==71&&sz>=4){ String id=new String(new byte[]{d[o+3],d[o+2],d[o+1],d[o]},StandardCharsets.ISO_8859_1); pendingCtrl=id;
        if(id.equals("tbl ")||id.equals("gso ")){ long attr=u32(d,o+4); boolean asChar=(attr&1)!=0; String pos=sz>=24?" "+mm(u32(d,o+16)*HU)+"×"+mm(u32(d,o+20)*HU)+"mm"+(asChar?" 글자처럼":" 위치 가로+"+mm(i32(d,o+12)*HU)+" 세로+"+mm(i32(d,o+8)*HU)):"";
          if(id.equals("tbl ")){ b.append(pad(tables.size()*2)).append("[표").append(pos).append(" 문단정렬=").append(pAlign); tables.push(new int[]{lv,0}); }
          else b.append(pad(tables.size()*2)).append("[개체(그림/도형)").append(pos).append(" 문단정렬=").append(pAlign).append("]\n"); }
        else if(id.equals("head")||id.equals("foot")) b.append(pad(tables.size()*2)).append(id.equals("head")?"[머리말]\n":"[꼬리말]\n"); }
      else if(tag==77&&!tables.isEmpty()&&tables.peek()[1]==0&&sz>=8){ b.append(" ").append(u16(d,o+4)).append("행×").append(u16(d,o+6)).append("열]\n"); tables.peek()[1]=1; }
      else if(tag==72&&!tables.isEmpty()&&lv==tables.peek()[0]+1&&sz>=24){ if(tables.peek()[1]==0){ b.append("]\n"); tables.peek()[1]=1; }
        int col=u16(d,o+8), row=u16(d,o+10), cspan=u16(d,o+12), rspan=u16(d,o+14); int nPara=u16(d,o)&0x7FFF;
        b.append(pad(tables.size()*2-1)).append("[").append(row).append(",").append(col).append("]"); if(rspan!=1||cspan!=1) b.append(" 병합 ").append(rspan).append("×").append(cspan);
        b.append(" ").append(mm(u32(d,o+16)*HU)).append("×").append(mm(u32(d,o+20)*HU)).append("mm");
        if(nPara==1) cellLine=true; else b.append("\n"); }
      else if(tag==73&&sz>=40){ double w=u32(d,o)*HU, h=u32(d,o+4)*HU; boolean land=(u32(d,o+36)&1)!=0; double l=u32(d,o+8)*HU, rt=u32(d,o+12)*HU;
        String pg="용지 "+mm(land?h:w)+"×"+mm(land?w:h)+"mm"+(land?" (가로)":"")+"  여백 왼"+mm(l)+" 오른"+mm(rt)+" 위"+mm(u32(d,o+16)*HU)+" 아래"+mm(u32(d,o+20)*HU)+" 머리말"+mm(u32(d,o+24)*HU)+" 꼬리말"+mm(u32(d,o+28)*HU)
          +"  (본문 너비 "+mm((land?h:w)-l-rt)+"mm; 문단 y 는 본문 영역 위에서부터)\n";
        b.insert(secStart,pg); secStart+=pg.length(); }   // 구역 정의는 첫 문단 안에 있어 문단보다 늦게 나오므로 구역 머리로 올림
    }
  }

  // ===================== DOCX =====================
  static final Set<String> TXBX=new HashSet<>(Arrays.asList("txbxContent"));
  static String docx(ZipFile z,boolean detail) throws Exception {
    Document doc=xml(z,"word/document.xml"); if(doc==null) return "ERROR: word/document.xml 이 없습니다"; StringBuilder b=new StringBuilder();
    Element body=first(doc,"body"); if(body==null) return "ERROR: 본문(w:body)이 없습니다";
    List<Element> sects=desc(body,"sectPr"); if(!sects.isEmpty()){ Element sp=sects.get(sects.size()-1), sz=kid(sp,"pgSz"), mg=kid(sp,"pgMar");
      if(sz!=null){ b.append("용지 ").append(mm(num(at(sz,"w"))*TW)).append("×").append(mm(num(at(sz,"h"))*TW)).append("mm").append("landscape".equals(at(sz,"orient"))?" (가로)":"");
        if(mg!=null) b.append("  여백 왼").append(mm(num(at(mg,"left"))*TW)).append(" 오른").append(mm(num(at(mg,"right"))*TW)).append(" 위").append(mm(num(at(mg,"top"))*TW)).append(" 아래").append(mm(num(at(mg,"bottom"))*TW)).append("  (본문 너비 ").append(mm((num(at(sz,"w"))-num(at(mg,"left"))-num(at(mg,"right")))*TW)).append("mm)");
        b.append("\n"); } }
    for(Enumeration<? extends ZipEntry> en=z.entries(); en.hasMoreElements();){ String n=en.nextElement().getName(); if(n.matches("word/(header|footer)\\d*\\.xml")){ Document hd=xml(z,n); StringBuilder t=new StringBuilder(); for(Element p: desc(hd,"p")){ String s=docxText(p); if(!s.trim().isEmpty()) t.append(t.length()>0?" / ":"").append(s.trim()); } if(t.length()>0) b.append(n.contains("header")?"[머리글] ":"[바닥글] ").append(quote(t.toString())).append("\n"); } }
    docxBlocks(b,body,0,detail);
    return b.toString();
  }
  static String docxText(Element p){ StringBuilder sb=new StringBuilder(); docxText(p,sb); return sb.toString(); }
  static void docxText(Node p,StringBuilder sb){ for(Node c=p.getFirstChild();c!=null;c=c.getNextSibling()) if(c instanceof Element){ String l=ln(c); if(l.equals("t")) sb.append(c.getTextContent()); else if(l.equals("tab")) sb.append('\t'); else if(l.equals("br")||l.equals("cr")) sb.append('\n'); else if(l.equals("txbxContent")||l.equals("pPr")||l.equals("rPr")||l.equals("instrText")||l.equals("delText")||l.equals("Fallback")) continue; else docxText(c,sb); } }
  static String docxStyle(Element p){ StringBuilder s=new StringBuilder(); Element ppr=kid(p,"pPr"); String jc=ppr==null||kid(ppr,"jc")==null?"left":at(kid(ppr,"jc"),"val"); s.append(jc==null?"left":jc.equals("both")?"justify":jc);
    Element r=null; for(Element rr: desc(p,"r")){ if(!docxText(rr).trim().isEmpty()){ r=rr; break; } } Element rpr=r==null?null:kid(r,"rPr"); if(rpr==null&&ppr!=null) rpr=kid(ppr,"rPr");
    if(rpr!=null){ Element sz=kid(rpr,"sz"), bo=kid(rpr,"b"), fo=kid(rpr,"rFonts"), co=kid(rpr,"color"), ul=kid(rpr,"u"); if(sz!=null) s.append(" ").append(mm(num(at(sz,"val"))/2)).append("pt");
      if(bo!=null&&!"0".equals(at(bo,"val"))&&!"false".equals(at(bo,"val"))) s.append(" B"); if(ul!=null&&!"none".equals(at(ul,"val"))) s.append(" U"); if(fo!=null){ String fn=at(fo,"eastAsia"); if(fn==null) fn=at(fo,"ascii"); if(fn!=null) s.append(" ").append(fn); }
      if(co!=null&&at(co,"val")!=null&&!at(co,"val").equalsIgnoreCase("auto")&&!at(co,"val").equals("000000")) s.append(" #").append(at(co,"val")); }
    if(ppr!=null&&kid(ppr,"pStyle")!=null) s.append(" style=").append(at(kid(ppr,"pStyle"),"val"));
    return s.toString(); }
  static void docxBlocks(StringBuilder b,Node parent,int depth,boolean detail){
    for(Element e: kids(parent)){ String l=ln(e);
      if(l.equals("p")){ String t=docxText(e); if(!t.trim().isEmpty()) b.append(pad(depth)).append("P ").append(docxStyle(e)).append(": ").append(quote(t)).append("\n"); else if(detail) b.append(pad(depth)).append("P (빈 줄)\n");
        List<Element> boxes=new ArrayList<>(); desc(e,"txbxContent",TXBX,boxes); Set<String> seen=new HashSet<>(); for(Element bx: boxes){ StringBuilder tb=new StringBuilder(); docxBlocks(tb,bx,depth+2,detail); if(tb.length()>0&&seen.add(tb.toString())){ b.append(pad(depth)).append("[글상자]\n").append(tb); } }
        if(first(e,"drawing")!=null||first(e,"pict")!=null){ Element ext=first(e,"extent"); if(ext!=null&&boxes.isEmpty()) b.append(pad(depth)).append("[그림/도형 ").append(mm(num(at(ext,"cx"))*EMU)).append("×").append(mm(num(at(ext,"cy"))*EMU)).append("mm]\n"); } }
      else if(l.equals("tbl")) docxTable(b,e,depth,detail);
      else if(l.equals("sdt")){ Element c=kid(e,"sdtContent"); if(c!=null) docxBlocks(b,c,depth,detail); }
      else if(l.equals("ins")||l.equals("smartTag")||l.equals("customXml")) docxBlocks(b,e,depth,detail); }
  }
  static void docxTable(StringBuilder b,Element tbl,int depth,boolean detail){
    List<Double> grid=new ArrayList<>(); Element tg=kid(tbl,"tblGrid"); if(tg!=null) for(Element gc: kids(tg,"gridCol")) grid.add(num(at(gc,"w"))*TW);
    List<Element> rows=kids(tbl,"tr"); double tw=0; for(double g: grid) tw+=g; StringBuilder gw=new StringBuilder(); for(double g: grid) gw.append(gw.length()>0?", ":"").append(mm(g));
    Element tpr=kid(tbl,"tblPr"); String jc=tpr!=null&&kid(tpr,"jc")!=null?at(kid(tpr,"jc"),"val"):"left";
    b.append(pad(depth)).append("[표 ").append(rows.size()).append("행×").append(grid.size()).append("열 너비 ").append(mm(tw)).append("mm 열너비[").append(gw).append("] 정렬=").append(jc).append("]\n");
    int ri=0; for(Element tr: rows){ Element trp=kid(tr,"trPr"); Element th=trp==null?null:kid(trp,"trHeight"), gb=trp==null?null:kid(trp,"gridBefore"); int ci=gb==null?0:Math.max(0,(int)num(at(gb,"val")));   /* 행 앞의 빈 격자 칸(gridBefore)만큼 열 번호를 밀어 시작 */
      for(Element tc: kids(tr,"tc")){ Element pr=kid(tc,"tcPr"); int span=1; String vm=null; if(pr!=null){ Element gs=kid(pr,"gridSpan"); if(gs!=null) span=Math.max(1,(int)num(at(gs,"val"))); Element v=kid(pr,"vMerge"); if(v!=null) vm=at(v,"val")==null?"continue":at(v,"val"); }
        double w=0; for(int k=ci;k<ci+span&&k<grid.size();k++) w+=grid.get(k);
        b.append(pad(depth+1)).append("[").append(ri).append(",").append(ci).append("]"); if(span>1) b.append(" 가로병합 ").append(span); if("restart".equals(vm)) b.append(" 세로병합 시작"); b.append(" ").append(mm(w)).append(th!=null?"×"+mm(num(at(th,"val"))*TW):"").append("mm");
        if("continue".equals(vm)){ b.append(" ‹세로병합 — 위 셀에 이어짐›\n"); }
        else { List<Element> blocks=kids(tc,"p","tbl"); if(blocks.size()==1&&ln(blocks.get(0)).equals("p")&&first(blocks.get(0),"txbxContent")==null){ String t=docxText(blocks.get(0)); b.append(t.trim().isEmpty()?"\n":" "+docxStyle(blocks.get(0))+": "+quote(t)+"\n"); } else { b.append("\n"); docxBlocks(b,tc,depth+3,detail); } }
        ci+=span; }
      ri++; }
  }

  // ===================== XLSX =====================
  static int colIndex(String ref){ int c=0; for(int i=0;i<ref.length();i++){ char ch=ref.charAt(i); if(ch>='A'&&ch<='Z') c=c*26+(ch-'A'+1); else if(ch>='a'&&ch<='z') c=c*26+(ch-'a'+1); else break; } return c; }
  static String siText(Element si){ StringBuilder sb=new StringBuilder(); for(Element c: kids(si)){ String l=ln(c); if(l.equals("t")) sb.append(c.getTextContent()); else if(l.equals("r")){ Element t=kid(c,"t"); if(t!=null) sb.append(t.getTextContent()); } } return sb.toString(); }
  static boolean isDateFmt(int id,String code){ if((id>=14&&id<=22)||(id>=27&&id<=36)||(id>=45&&id<=47)||(id>=50&&id<=58)) return true; if(code==null) return false; String c=code.replaceAll("\"[^\"]*\"","").replaceAll("\\[[^\\]]*\\]","").replaceAll("\\\\.","").toLowerCase(Locale.ROOT); return c.matches(".*[ymdhs].*")&&!c.contains("general"); }
  /** 엑셀 날짜 일련번호 → 날짜. 1900 체계는 존재하지 않는 1900-02-29(일련번호 60) 때문에 60 미만은 하루 뒤로 센다. date1904 통합 문서는 1904-01-01 이 0. */
  static String excelDate(double v,boolean d1904){ java.time.LocalDateTime base=d1904?java.time.LocalDateTime.of(1904,1,1,0,0):java.time.LocalDateTime.of(1899,12,v<60?31:30,0,0); java.time.LocalDateTime t=base.plusSeconds(Math.round(v*86400)); boolean hasTime=Math.abs(v-Math.floor(v))>1e-9, hasDate=d1904?!(v<1&&hasTime):v>=1;
    return hasDate&&hasTime?t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")):hasDate?t.toLocalDate().toString():t.toLocalTime().toString(); }
  static String numText(String v){ try{ double d=Double.parseDouble(v); if(d==Math.rint(d)&&Math.abs(d)<1e15) return String.valueOf((long)d); return new java.math.BigDecimal(v).round(new java.math.MathContext(15)).stripTrailingZeros().toPlainString(); }catch(Exception e){ return v; } }
  static String xlsx(ZipFile z,String sheetSel,boolean detail) throws Exception {
    Document wb=xml(z,"xl/workbook.xml"); Document rels=xml(z,"xl/_rels/workbook.xml.rels"); if(wb==null) return "ERROR: xl/workbook.xml 이 없습니다";
    Map<String,String> rel=new HashMap<>(); if(rels!=null) for(Element r: desc(rels,"Relationship")) rel.put(at(r,"Id"),at(r,"Target"));
    List<String> shared=new ArrayList<>(); Document ss=xml(z,"xl/sharedStrings.xml"); if(ss!=null) for(Element si: kids(ss.getDocumentElement(),"si")) shared.add(siText(si));
    List<Boolean> dateXf=new ArrayList<>(); Document st=xml(z,"xl/styles.xml"); if(st!=null){ Map<Integer,String> fmts=new HashMap<>(); for(Element nf: desc(st,"numFmt")) fmts.put((int)num(at(nf,"numFmtId")),at(nf,"formatCode")); Element xfs=first(st,"cellXfs"); if(xfs!=null) for(Element xf: kids(xfs,"xf")){ int id=(int)num(at(xf,"numFmtId")); dateXf.add(isDateFmt(id,fmts.get(id))); } }
    Element wpr=first(wb,"workbookPr"); final boolean d1904=wpr!=null&&("1".equals(at(wpr,"date1904"))||"true".equalsIgnoreCase(at(wpr,"date1904")));
    StringBuilder b=new StringBuilder(); List<Element> sheets=desc(wb,"sheet"); StringBuilder names=new StringBuilder(); for(Element sh: sheets) names.append(names.length()>0?", ":"").append(at(sh,"name")).append("hidden".equals(at(sh,"state"))?"(숨김)":"");
    b.append("시트 ").append(sheets.size()).append("개: ").append(names).append("\n"); int shown=0, idx=0;
    for(Element sh: sheets){ String name=at(sh,"name"); idx++; if(sheetSel!=null&&!sheetSel.trim().isEmpty()&&!(sheetSel.trim().equalsIgnoreCase(name)||sheetSel.trim().equals(String.valueOf(idx)))) continue; shown++;
      String target=rel.get(rid(sh)); if(target==null) continue; String pathIn=target.startsWith("/")?target.substring(1):"xl/"+target; Document sd=xml(z,pathIn); if(sd==null) continue; Element root=sd.getDocumentElement();
      Element dim=kid(root,"dimension"), ps=kid(root,"pageSetup"), fp=kid(root,"sheetFormatPr");
      b.append("== 시트 ").append(idx).append(" \"").append(name).append("\"").append(dim==null?"":" 범위 "+at(dim,"ref")).append(ps!=null&&"landscape".equals(at(ps,"orientation"))?" 인쇄=가로":"").append(fp!=null&&at(fp,"defaultRowHeight")!=null?" 기본 행높이 "+mm(num(at(fp,"defaultRowHeight"))*PT)+"mm":"").append("\n");
      Element cols=kid(root,"cols"); if(cols!=null){ StringBuilder cw=new StringBuilder(); for(Element c: kids(cols,"col")){ int mn=(int)num(at(c,"min")), mx=(int)num(at(c,"max")); if(mx-mn>50) continue; String rng=mn==mx?colName(mn):colName(mn)+"-"+colName(mx); cw.append(cw.length()>0?", ":"").append(rng).append("=").append(mm((num(at(c,"width"))*7+5)*25.4/96)).append("1".equals(at(c,"hidden"))?"(숨김)":""); } if(cw.length()>0) b.append("  열 너비(mm, 근사): ").append(cw).append("\n"); }
      Element mc=kid(root,"mergeCells"); if(mc!=null){ StringBuilder m=new StringBuilder(); int n=0; for(Element e: kids(mc,"mergeCell")){ if(n++<200) m.append(m.length()>0?", ":"").append(at(e,"ref")); } b.append("  병합(").append(n).append("): ").append(m).append(n>200?" …":"").append("\n"); }
      Element data=kid(root,"sheetData"); int rows=0; if(data!=null) for(Element row: kids(data,"row")){ StringBuilder line=new StringBuilder();
        for(Element c: kids(row,"c")){ String t=at(c,"t"); Element v=kid(c,"v"), fo=kid(c,"f"), is=kid(c,"is"); String val=v==null?null:v.getTextContent();
          if("s".equals(t)&&val!=null){ int si=(int)num(val); val=si<shared.size()?shared.get(si):""; } else if("inlineStr".equals(t)&&is!=null) val=siText(is); else if("b".equals(t)&&val!=null) val="1".equals(val)?"TRUE":"FALSE";
          else if(val!=null&&(t==null||"n".equals(t))){ int sx=(int)num(at(c,"s")); if(at(c,"s")!=null&&sx<dateXf.size()&&dateXf.get(sx)){ try{ val=excelDate(Double.parseDouble(val),d1904); }catch(Exception e){} } else val=numText(val); }
          if((val==null||val.isEmpty())&&fo==null) continue;
          line.append(line.length()>0?" | ":"").append(at(c,"r")).append("=").append(val==null?"":("s".equals(t)||"str".equals(t)||"inlineStr".equals(t)?quote(val):val)); if(fo!=null&&detail) line.append(" {=").append(fo.getTextContent()).append("}"); }
        if(line.length()==0) continue; rows++; b.append("  ").append(line); if(detail&&at(row,"ht")!=null) b.append("   (행높이 ").append(mm(num(at(row,"ht"))*PT)).append("mm)"); b.append("\n"); }
      if(rows==0) b.append("  (값 있는 셀 없음)\n"); }
    if(shown==0) return "ERROR: 시트 '"+sheetSel+"' 없음 — 있는 시트: "+names;
    return b.toString();
  }
  static String colName(int n){ StringBuilder s=new StringBuilder(); while(n>0){ int r=(n-1)%26; s.insert(0,(char)('A'+r)); n=(n-1)/26; } return s.toString(); }

  // ===================== XLS (poi HSSF) =====================
  static String xls(File f,String sheetSel,boolean detail) throws Exception {
    try(InputStream in=new FileInputStream(f)){ org.apache.poi.hssf.usermodel.HSSFWorkbook wb=new org.apache.poi.hssf.usermodel.HSSFWorkbook(in); StringBuilder b=new StringBuilder(); int n=wb.getNumberOfSheets(); StringBuilder names=new StringBuilder(); for(int i=0;i<n;i++) names.append(i>0?", ":"").append(wb.getSheetName(i));
      b.append("시트 ").append(n).append("개: ").append(names).append("\n"); int shown=0; org.apache.poi.ss.usermodel.DataFormatter fmt=new org.apache.poi.ss.usermodel.DataFormatter();
      for(int i=0;i<n;i++){ String name=wb.getSheetName(i); if(sheetSel!=null&&!sheetSel.trim().isEmpty()&&!(sheetSel.trim().equalsIgnoreCase(name)||sheetSel.trim().equals(String.valueOf(i+1)))) continue; shown++;
        org.apache.poi.hssf.usermodel.HSSFSheet sh=wb.getSheetAt(i); b.append("== 시트 ").append(i+1).append(" \"").append(name).append("\" 행 ").append(sh.getFirstRowNum()+1).append("~").append(sh.getLastRowNum()+1).append("\n");
        int mr=sh.getNumMergedRegions(); if(mr>0){ StringBuilder m=new StringBuilder(); for(int k=0;k<mr&&k<200;k++) m.append(k>0?", ":"").append(sh.getMergedRegion(k).formatAsString()); b.append("  병합(").append(mr).append("): ").append(m).append(mr>200?" …":"").append("\n"); }
        int rows=0; for(int r=sh.getFirstRowNum(); r<=sh.getLastRowNum(); r++){ org.apache.poi.hssf.usermodel.HSSFRow row=sh.getRow(r); if(row==null) continue; StringBuilder line=new StringBuilder();
          for(int c=Math.max(0,row.getFirstCellNum()); c<row.getLastCellNum(); c++){ org.apache.poi.hssf.usermodel.HSSFCell cell=row.getCell(c); if(cell==null) continue; String val; int ct=cell.getCellType(); boolean str=ct==org.apache.poi.ss.usermodel.Cell.CELL_TYPE_STRING;
            try{ if(ct==org.apache.poi.ss.usermodel.Cell.CELL_TYPE_FORMULA){ int rt=cell.getCachedFormulaResultType(); val=rt==org.apache.poi.ss.usermodel.Cell.CELL_TYPE_NUMERIC?numText(String.valueOf(cell.getNumericCellValue())):rt==org.apache.poi.ss.usermodel.Cell.CELL_TYPE_STRING?cell.getStringCellValue():String.valueOf(cell); if(detail) val+=" {="+cell.getCellFormula()+"}"; } else val=fmt.formatCellValue(cell); }catch(Throwable t){ val=String.valueOf(cell); }
            if(val==null||val.isEmpty()) continue; line.append(line.length()>0?" | ":"").append(colName(c+1)).append(r+1).append("=").append(str?quote(val):val); }
          if(line.length()==0) continue; rows++; b.append("  ").append(line); if(detail) b.append("   (행높이 ").append(mm(row.getHeightInPoints()*PT)).append("mm)"); b.append("\n"); }
        if(rows==0) b.append("  (값 있는 셀 없음)\n"); }
      if(shown==0) return "ERROR: 시트 '"+sheetSel+"' 없음 — 있는 시트: "+names;
      return b.toString(); }
  }

  // ===================== PPTX =====================
  static String pptx(ZipFile z,boolean detail) throws Exception {
    Document pres=xml(z,"ppt/presentation.xml"), rels=xml(z,"ppt/_rels/presentation.xml.rels"); if(pres==null) return "ERROR: ppt/presentation.xml 이 없습니다";
    Map<String,String> rel=new HashMap<>(); if(rels!=null) for(Element r: desc(rels,"Relationship")) rel.put(at(r,"Id"),at(r,"Target"));
    StringBuilder b=new StringBuilder(); Element sz=first(pres,"sldSz"); List<Element> ids=desc(pres,"sldId");
    b.append("슬라이드 ").append(ids.size()).append("장").append(sz==null?"":"  크기 "+mm(num(at(sz,"cx"))*EMU)+"×"+mm(num(at(sz,"cy"))*EMU)+"mm").append("  (도형 위치 x,y = 슬라이드 왼쪽 위 기준)\n");
    int n=0; for(Element id: ids){ n++; String target=rel.get(rid(id)); if(target==null) continue; String p=target.startsWith("/")?target.substring(1):"ppt/"+target; Document sd=xml(z,p); if(sd==null) continue;
      b.append("== 슬라이드 ").append(n).append("\n"); Element tree=first(sd,"spTree"); if(tree!=null) pptxShapes(b,tree,1,detail); }
    return b.toString();
  }
  static String pptxXfrm(Element sp){ Element x=first(sp,"xfrm"); if(x==null) return " (위치=레이아웃 상속)"; Element off=kid(x,"off"), ext=kid(x,"ext"); if(off==null||ext==null) return "";
    return " x="+mm(num(at(off,"x"))*EMU)+" y="+mm(num(at(off,"y"))*EMU)+" "+mm(num(at(ext,"cx"))*EMU)+"×"+mm(num(at(ext,"cy"))*EMU)+"mm"; }
  static String pptxParaText(Element p){ StringBuilder sb=new StringBuilder(); for(Element c: kids(p)){ String l=ln(c); if(l.equals("r")||l.equals("fld")){ Element t=kid(c,"t"); if(t!=null) sb.append(t.getTextContent()); } else if(l.equals("br")) sb.append('\n'); } return sb.toString(); }
  static void pptxBody(StringBuilder b,Element txBody,int depth){ if(txBody==null) return; for(Element p: kids(txBody,"p")){ String t=pptxParaText(p); if(t.trim().isEmpty()) continue; Element ppr=kid(p,"pPr"); String al=ppr==null||at(ppr,"algn")==null?"":" "+at(ppr,"algn"); Element r=kid(p,"r"); Element rpr=r==null?null:kid(r,"rPr"); String szs=rpr!=null&&at(rpr,"sz")!=null?" "+mm(num(at(rpr,"sz"))/100)+"pt":""; String bo=rpr!=null&&"1".equals(at(rpr,"b"))?" B":"";
      b.append(pad(depth)).append("·").append(al).append(szs).append(bo).append(al.isEmpty()&&szs.isEmpty()&&bo.isEmpty()?" ":": ").append(quote(t)).append("\n"); } }
  static void pptxShapes(StringBuilder b,Element tree,int depth,boolean detail){
    for(Element e: kids(tree)){ String l=ln(e); Element nv=first(e,"cNvPr"); String name=nv==null?"":" \""+at(nv,"name")+"\"";
      if(l.equals("sp")){ Element tx=kid(e,"txBody"); boolean has=false; if(tx!=null) for(Element p: kids(tx,"p")) if(!pptxParaText(p).trim().isEmpty()) has=true; if(!has&&!detail) continue; b.append(pad(depth)).append("[도형").append(name).append(pptxXfrm(kid(e,"spPr")==null?e:kid(e,"spPr"))).append("]\n"); pptxBody(b,tx,depth+1); }
      else if(l.equals("pic")) b.append(pad(depth)).append("[그림").append(name).append(pptxXfrm(kid(e,"spPr")==null?e:kid(e,"spPr"))).append("]\n");
      else if(l.equals("cxnSp")){ if(detail) b.append(pad(depth)).append("[선").append(name).append(pptxXfrm(kid(e,"spPr")==null?e:kid(e,"spPr"))).append("]\n"); }
      else if(l.equals("grpSp")){ b.append(pad(depth)).append("[그룹").append(name).append(pptxXfrm(kid(e,"grpSpPr")==null?e:kid(e,"grpSpPr"))).append(" — 안쪽 도형 좌표는 그룹 내부 좌표계]\n"); pptxShapes(b,e,depth+1,detail); }
      else if(l.equals("graphicFrame")){ Element tbl=first(e,"tbl"); String pos=kid(e,"xfrm")==null?"":pptxXfrm(e);
        if(tbl==null){ if(detail) b.append(pad(depth)).append("[개체(차트/OLE 등)").append(name).append(pos).append("]\n"); continue; }
        List<Element> gc=tbl.getFirstChild()==null?new ArrayList<>():kids(kid(tbl,"tblGrid"),"gridCol"); StringBuilder gw=new StringBuilder(); for(Element g: gc) gw.append(gw.length()>0?", ":"").append(mm(num(at(g,"w"))*EMU)); List<Element> trs=kids(tbl,"tr");
        b.append(pad(depth)).append("[표").append(name).append(pos).append(" ").append(trs.size()).append("행×").append(gc.size()).append("열 열너비[").append(gw).append("]]\n"); int ri=0;
        for(Element tr: trs){ int ci=0; for(Element tc: kids(tr,"tc")){ boolean cover="1".equals(at(tc,"hMerge"))||"1".equals(at(tc,"vMerge")); if(!cover){ StringBuilder t=new StringBuilder(); Element tx=kid(tc,"txBody"); if(tx!=null) for(Element p: kids(tx,"p")){ String s=pptxParaText(p); if(!s.isEmpty()) t.append(t.length()>0?"\n":"").append(s); }
              int gs=(int)num(at(tc,"gridSpan")), rs=(int)num(at(tc,"rowSpan")); if(!t.toString().trim().isEmpty()||detail) b.append(pad(depth+1)).append("[").append(ri).append(",").append(ci).append("]").append(gs>1||rs>1?" 병합 "+Math.max(rs,1)+"×"+Math.max(gs,1):"").append(detail?" 행높이 "+mm(num(at(tr,"h"))*EMU)+"mm":"").append(" ").append(quote(t.toString())).append("\n"); }
            ci++; } ri++; } } }
  }

  // ===================== PDF =====================
  static String pdf(File f,boolean detail,String pages) throws Exception {
    int from=1, to=Integer.MAX_VALUE; if(pages!=null&&!pages.trim().isEmpty()){ java.util.regex.Matcher m=java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*(?:-\\s*(\\d+)?)?\\s*$").matcher(pages); if(!m.find()) return "ERROR: pages 는 '3' 또는 '1-5' 형식"; from=Integer.parseInt(m.group(1)); to=pages.contains("-")?(m.group(2)==null?Integer.MAX_VALUE:Integer.parseInt(m.group(2))):from; }
    try(org.apache.pdfbox.pdmodel.PDDocument d=org.apache.pdfbox.pdmodel.PDDocument.load(f)){ int n=d.getNumberOfPages(); if(from>n) return "ERROR: pages 시작 쪽 "+from+" 이 전체 "+n+"쪽을 넘습니다";
      StringBuilder b=new StringBuilder("쪽 "+n+"개"+(from>1||to<n?" (표시 "+from+"~"+Math.min(to,n)+")":"")+"\n"); org.apache.pdfbox.text.PDFTextStripper st=detail?new PosStripper(b):new org.apache.pdfbox.text.PDFTextStripper(); st.setSortByPosition(true); st.setStartPage(from); st.setEndPage(Math.min(to,n));
      String txt=st.getText(d); if(!detail){ org.apache.pdfbox.pdmodel.common.PDRectangle r=d.getPage(from-1).getMediaBox(); b.append("용지 ").append(mm(r.getWidth()*PT)).append("×").append(mm(r.getHeight()*PT)).append("mm\n").append(txt==null?"":txt); }
      if((detail?b.length()<60:txt==null||txt.trim().isEmpty())) b.append("(글자가 추출되지 않음 — 스캔한 그림 PDF 일 수 있습니다. doc_image 로 쪽을 그림으로 받아 직접 보세요)\n");
      return b.toString(); }
  }
  /** 줄(글자 묶음)마다 위치·크기: x,y 는 쪽 왼쪽 위 기준 mm (y 는 글자 기준선) */
  static class PosStripper extends org.apache.pdfbox.text.PDFTextStripper { final StringBuilder out; PosStripper(StringBuilder out) throws IOException { super(); this.out=out; }
    @Override protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException { org.apache.pdfbox.pdmodel.common.PDRectangle r=page.getMediaBox(); out.append("== 쪽 ").append(getCurrentPageNo()).append("  용지 ").append(mm(r.getWidth()*PT)).append("×").append(mm(r.getHeight()*PT)).append("mm\n"); }
    @Override protected void writeString(String text,List<org.apache.pdfbox.text.TextPosition> tp) throws IOException { if(text==null||text.trim().isEmpty()||tp==null||tp.isEmpty()) return; org.apache.pdfbox.text.TextPosition a=tp.get(0), z=tp.get(tp.size()-1);
      out.append("  x=").append(mm(a.getXDirAdj()*PT)).append(" y=").append(mm(a.getYDirAdj()*PT)).append(" w=").append(mm((z.getXDirAdj()+z.getWidthDirAdj()-a.getXDirAdj())*PT)).append(" ").append(mm(a.getFontSizeInPt())).append("pt: ").append(quote(text)).append("\n"); }
  }

  // ===================== 이미지 =====================
  /** 그림을 읽기 전에 머리글의 크기부터 확인한다(작게 압축된 초대형 그림이 통째로 풀려 메모리를 다 쓰는 것 방지). 읽을 수 없는 형식이면 null. */
  static java.awt.image.BufferedImage readImage(Object src) throws IOException {
    try(javax.imageio.stream.ImageInputStream in=javax.imageio.ImageIO.createImageInputStream(src instanceof byte[]?new ByteArrayInputStream((byte[])src):src)){ if(in==null) return null;
      Iterator<javax.imageio.ImageReader> it=javax.imageio.ImageIO.getImageReaders(in); if(!it.hasNext()) return null; javax.imageio.ImageReader rd=it.next();
      try{ rd.setInput(in,true,true); long w=rd.getWidth(0), h=rd.getHeight(0); if(w<=0||h<=0||w*h>MAX_PIXELS) throw new IOException("그림이 너무 큼("+w+"×"+h+"px) — "+MAX_PIXELS/1_000_000+"백만 화소까지"); return rd.read(0); } finally { rd.dispose(); } } }
  static String imageInfo(File f) throws Exception { System.setProperty("java.awt.headless","true"); java.awt.image.BufferedImage img=readImage(f); if(img==null) return "ERROR: 이미지를 읽을 수 없습니다(지원: PNG/JPG/GIF/BMP)";
    int w=img.getWidth(), h=img.getHeight(); boolean land=w>h; double a4w=land?297:210;
    return "이미지 "+w+"×"+h+"px ("+(land?"가로":"세로")+", 가로:세로 = 1:"+String.format(Locale.ROOT,"%.3f",(double)h/w)+")\n"
      +"A4 "+(land?"가로":"세로")+" 한 장을 꽉 채운 양식이라면 1px ≈ "+String.format(Locale.ROOT,"%.4f",a4w/w)+"mm (요소 위치 = px × 이 값)\n"
      +"글자 인식(OCR) 엔진은 내장돼 있지 않습니다 — doc_image(path) 로 이 이미지를 받아 직접 보고 글자·표·위치를 판독하세요.\n"; }
  static byte[] encode(java.awt.image.BufferedImage img,String fmt) throws IOException { ByteArrayOutputStream o=new ByteArrayOutputStream(); java.awt.image.BufferedImage src=img;
    if(fmt.equals("jpg")&&img.getType()!=java.awt.image.BufferedImage.TYPE_INT_RGB){ src=new java.awt.image.BufferedImage(img.getWidth(),img.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB); java.awt.Graphics2D g=src.createGraphics(); g.setColor(java.awt.Color.WHITE); g.fillRect(0,0,src.getWidth(),src.getHeight()); g.drawImage(img,0,0,null); g.dispose(); }
    if(!javax.imageio.ImageIO.write(src,fmt,o)) throw new IOException("이미지 인코딩 실패: "+fmt); return o.toByteArray(); }
  static java.awt.image.BufferedImage scale(java.awt.image.BufferedImage img,int maxPx){ int w=img.getWidth(), h=img.getHeight(); if(Math.max(w,h)<=maxPx) return img; double k=(double)maxPx/Math.max(w,h); int nw=Math.max(1,(int)Math.round(w*k)), nh=Math.max(1,(int)Math.round(h*k));
    java.awt.image.BufferedImage o=new java.awt.image.BufferedImage(nw,nh,java.awt.image.BufferedImage.TYPE_INT_RGB); java.awt.Graphics2D g=o.createGraphics(); g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC); g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,java.awt.RenderingHints.VALUE_RENDER_QUALITY); g.setColor(java.awt.Color.WHITE); g.fillRect(0,0,nw,nh);
    g.drawImage(img.getScaledInstance(nw,nh,java.awt.Image.SCALE_SMOOTH),0,0,null); g.dispose(); return o; }
  @SuppressWarnings("unchecked")
  static JSONObject imageContent(String text,java.awt.image.BufferedImage out,boolean photo) throws IOException {
    byte[] data=encode(out,photo?"jpg":"png"); String mime=photo?"image/jpeg":"image/png"; if(data.length>3_000_000){ data=encode(out,"jpg"); mime="image/jpeg"; }
    if(data.length>4_500_000) return CrfMcpServer.textContent("ERROR: 그림이 너무 큽니다("+data.length/1024+" KB) — max_px 를 줄여 다시 호출하세요");
    JSONObject t=new JSONObject(); t.put("type","text"); t.put("text",text);
    JSONObject im=new JSONObject(); im.put("type","image"); im.put("data",Base64.getEncoder().encodeToString(data)); im.put("mimeType",mime);
    JSONArray a=new JSONArray(); a.add(t); a.add(im); JSONObject r=new JSONObject(); r.put("content",a); return r; }

  // ===================== 문서 → PDF 변환 (PC 에 설치된 프로그램 사용, 결과는 임시 폴더에 캐시) =====================
  static final boolean WIN=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");
  static File cacheDir(){ File d=new File(System.getProperty("java.io.tmpdir"),"clip-report-mcp-doc"); d.mkdirs(); return d; }
  /** 변환 캐시 파일: 이름 = 문서 **내용**의 SHA-256(경로·시각과 무관 — 다른 문서가 같은 캐시를 받거나 바뀐 문서가 옛 결과를 받지 않게) */
  static File cached(File src,String ext) throws Exception { java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256"); try(InputStream in=new FileInputStream(src)){ byte[] buf=new byte[65536]; int n; while((n=in.read(buf))>0) md.update(buf,0,n); }
    StringBuilder h=new StringBuilder(); for(byte x: md.digest()) h.append(String.format("%02x",x)); return new File(cacheDir(),h.substring(0,40)+ext); }
  /** 외부 프로그램 실행(출력 수집, 시간 제한). 반환 [종료코드, 출력] — 시간 초과면 종료코드 -999 */
  static Object[] run(List<String> cmd,int sec,File outFile) throws Exception {
    ProcessBuilder pb=new ProcessBuilder(cmd); pb.redirectErrorStream(true); if(outFile!=null) pb.redirectOutput(outFile); Process p=pb.start(); p.getOutputStream().close();
    final ByteArrayOutputStream o=new ByteArrayOutputStream(); Thread rd=null;
    if(outFile==null){ rd=new Thread(()->{ try(InputStream in=p.getInputStream()){ byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>0){ if(o.size()<4_000_000) o.write(buf,0,n); } }catch(IOException e){} }); rd.setDaemon(true); rd.start(); }
    if(!p.waitFor(sec,java.util.concurrent.TimeUnit.SECONDS)){ p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); return new Object[]{-999,"시간 초과("+sec+"초)"}; }
    if(rd!=null) rd.join(3000);
    return new Object[]{p.exitValue(),new String(o.toByteArray(),StandardCharsets.UTF_8)}; }
  static String firstExisting(String... cands){ for(String c: cands){ if(c!=null&&!c.isEmpty()&&new File(c).isFile()) return c; } return null; }
  static String env(String k){ String v=System.getenv(k); return v==null?"":v; }
  /** Edge/Chrome 실행 파일(헤드리스 렌더용). CLIP_BROWSER 로 지정 가능 */
  static String browser(){ return firstExisting(env("CLIP_BROWSER"), env("ProgramFiles(x86)")+"\\Microsoft\\Edge\\Application\\msedge.exe", env("ProgramFiles")+"\\Microsoft\\Edge\\Application\\msedge.exe", env("ProgramFiles")+"\\Google\\Chrome\\Application\\chrome.exe", env("ProgramFiles(x86)")+"\\Google\\Chrome\\Application\\chrome.exe", env("LOCALAPPDATA")+"\\Google\\Chrome\\Application\\chrome.exe",
      "/usr/bin/google-chrome","/usr/bin/chromium","/usr/bin/chromium-browser","/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"); }
  static String soffice(){ return firstExisting(env("CLIP_SOFFICE"), env("ProgramFiles")+"\\LibreOffice\\program\\soffice.exe", env("ProgramFiles(x86)")+"\\LibreOffice\\program\\soffice.exe","/usr/bin/soffice","/usr/bin/libreoffice","/Applications/LibreOffice.app/Contents/MacOS/soffice"); }
  /** MS Office / 한글 COM 자동화로 PDF 저장(매크로 실행 금지, 읽기 전용으로 열기). */
  static final String COM_PS1=
    "$ErrorActionPreference='Stop'; try{ [Console]::OutputEncoding=[Text.Encoding]::UTF8 }catch{}; $src=$args[0]; $dst=$args[1]; $kind=$args[2]\r\n"+
    "if($kind -eq 'word'){ $a=New-Object -ComObject Word.Application; try{ $a.Visible=$false; $a.DisplayAlerts=0; try{ $a.AutomationSecurity=3 }catch{}; $d=$a.Documents.Open($src,$false,$true,$false); $d.ExportAsFixedFormat($dst,17); $d.Close($false) } finally { $a.Quit() } }\r\n"+
    "elseif($kind -eq 'excel'){ $a=New-Object -ComObject Excel.Application; try{ $a.Visible=$false; $a.DisplayAlerts=$false; try{ $a.AutomationSecurity=3 }catch{}; $w=$a.Workbooks.Open($src,0,$true); $w.ExportAsFixedFormat(0,$dst); $w.Close($false) } finally { $a.Quit() } }\r\n"+
    "elseif($kind -eq 'ppt'){ $a=New-Object -ComObject PowerPoint.Application; try{ try{ $a.AutomationSecurity=3 }catch{}; $p=$a.Presentations.Open($src,-1,0,0); $p.SaveAs($dst,32); $p.Close() } finally { $a.Quit() } }\r\n"+
    "elseif($kind -eq 'hwp'){ $h=New-Object -ComObject HWPFrame.HwpObject; try{ try{ $h.RegisterModule('FilePathCheckDLL','FilePathCheckerModule') | Out-Null }catch{}; if(-not $h.Open($src,'','forceopen:true;versionwarning:false')){ throw 'open failed' }; if(-not $h.SaveAs($dst,'PDF','')){ throw 'save failed' } } finally { $h.Quit() } }\r\n"+
    "else { throw \"unknown kind $kind\" }\r\n";
  /** 문서를 PDF 로 변환해 캐시 파일을 돌려준다. 못 하면 null 이고 why 에 시도한 방법과 실패 사유. */
  static File toPdf(File f,String kind,StringBuilder why,String[] how) throws Exception {
    File pdf=cached(f,".pdf"); if(pdf.isFile()&&pdf.length()>0){ how[0]="변환 캐시"; return pdf; }
    File part=new File(cacheDir(),"part_"+Long.toHexString(System.nanoTime())+".pdf");   // 변환 중간 산출물 — 완성되면 캐시 이름으로 옮긴다(반쯤 쓰인 파일이 캐시로 읽히지 않게)
    try{ String via=convert(f,kind,part,why); if(via==null||!part.isFile()||part.length()==0) return null;
      java.nio.file.Files.move(part.toPath(),pdf.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); how[0]=via; return pdf;
    } finally { part.delete(); }
  }
  /** 실제 변환: 성공하면 쓴 방법 이름, 실패하면 null(why 에 사유) */
  static String convert(File f,String kind,File out,StringBuilder why) throws Exception {
    if(kind.equals("html")){ String br=browser(); if(br==null){ why.append("Edge/Chrome 을 찾지 못함(CLIP_BROWSER 로 지정 가능)"); return null; }
      // 인쇄 용지를 A4 로(브라우저 기본은 Letter) — 문서가 자체 @page 를 가지면 뒤에 오는 그쪽이 이긴다
      File tmp=new File(cacheDir(),"print_"+Long.toHexString(System.nanoTime())+".html");
      try{ java.nio.file.Files.write(tmp.toPath(),htmlInject(f,"<style>@page{size:A4}</style>","").getBytes(StandardCharsets.ISO_8859_1));
        if(browserPdf(br,tmp.toURI().toString(),out)) return new File(br).getName()+" 헤드리스 인쇄(A4, 네트워크 차단)"; } finally { tmp.delete(); }
      why.append("브라우저("+new File(br).getName()+") 인쇄 실패"); return null; }
    String com=kind.equals("docx")?"word":kind.equals("xlsx")||kind.equals("xls")?"excel":kind.equals("pptx")?"ppt":kind.equals("hwp")||kind.equals("hwpx")?"hwp":null;
    if(WIN&&com!=null){ File ps=new File(cacheDir(),"convert_"+Long.toHexString(System.nanoTime())+".ps1");
      try{ java.nio.file.Files.write(ps.toPath(),COM_PS1.getBytes(StandardCharsets.US_ASCII));
        Object[] r=run(Arrays.asList("powershell.exe","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",ps.getAbsolutePath(),f.getAbsolutePath(),out.getAbsolutePath(),com),90,null);
        if(out.isFile()&&out.length()>0) return (com.equals("hwp")?"한글":com.equals("word")?"MS Word":com.equals("excel")?"MS Excel":"MS PowerPoint")+" 로 PDF 저장";
        String msg=String.valueOf(r[1]).replaceAll("\\s+"," ").trim(); String prog=com.equals("hwp")?"한글(한컴오피스)":"MS Office";
        if(msg.contains("80040154")||msg.contains("REGDB_E_CLASSNOTREG")) why.append(prog).append(" 이 이 PC 에 설치돼 있지 않음; ");
        else if(Integer.valueOf(-999).equals(r[0])) why.append(prog).append(" 변환이 90초 안에 끝나지 않아 중단(프로그램 창이 남아 있으면 닫아 주세요); ");
        else why.append(prog).append(" 변환 실패: ").append(msg.length()>160?msg.substring(0,160)+"…":msg.isEmpty()?"종료코드 "+r[0]:msg).append("; ");
        out.delete();
      } finally { ps.delete(); } }
    String so=soffice(); if(so!=null){ File outDir=new File(cacheDir(),"lo_"+Long.toHexString(System.nanoTime())); outDir.mkdirs();
      try{ Object[] r=run(Arrays.asList(so,"--headless","--convert-to","pdf","--outdir",outDir.getAbsolutePath(),f.getAbsolutePath()),120,null); File[] made=outDir.listFiles((d,n)->n.toLowerCase(Locale.ROOT).endsWith(".pdf"));
        if(made!=null&&made.length>0&&made[0].length()>0){ java.nio.file.Files.move(made[0].toPath(),out.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); return "LibreOffice 로 PDF 변환"; }
        why.append("LibreOffice 변환 실패(종료코드 ").append(r[0]).append("); ");
      } finally { deleteTree(outDir); } }
    else why.append("LibreOffice 없음(CLIP_SOFFICE 로 지정 가능)");
    return null;
  }

  // ===================== HTML =====================
  /** 헤드리스 Edge/Chrome 으로 url 을 PDF 로 인쇄. 실행 파일은 곧바로 끝나고 PDF 는 뒤에서 만들어지므로(표준출력도 못 받음) 파일이 생겨 크기가 멈출 때까지 기다린다. */
  static boolean browserPdf(String br,String url,File pdf,String... extra) throws Exception {
    File prof=new File(cacheDir(),"bp_"+Long.toHexString(System.nanoTime())); prof.mkdirs(); pdf.delete();
    List<String> cmd=new ArrayList<>(Arrays.asList(br,"--headless=new","--disable-gpu","--no-first-run","--disable-extensions","--user-data-dir="+prof.getAbsolutePath(),"--no-pdf-header-footer","--virtual-time-budget=5000"));
    if(!"1".equals(env("CLIP_DOC_ALLOW_NETWORK"))) cmd.addAll(Arrays.asList(   // 원격 CSS/글꼴이 꼭 필요한 신뢰하는 문서만 CLIP_DOC_ALLOW_NETWORK=1 로 풀 수 있음
      // 양식 HTML 은 신뢰할 수 없는 입력 — 그 안의 스크립트가 밖으로 나가지 못하게: 모든 요청을 죽은 프록시로(로컬호스트 포함), DNS 는 전부 실패, 백그라운드 통신 끔. file:// 은 프록시를 타지 않아 문서 자체와 로컬 그림/CSS 는 그대로 읽힌다
      "--proxy-server=http://127.0.0.1:9","--proxy-bypass-list=<-loopback>","--host-resolver-rules=MAP * ~NOTFOUND","--disable-background-networking","--disable-sync","--disable-component-update","--no-default-browser-check")); cmd.addAll(Arrays.asList(extra)); cmd.add("--print-to-pdf="+pdf.getAbsolutePath()); cmd.add(url);
    try{ run(cmd,60,null); long end=System.currentTimeMillis()+40000, last=-1; int stable=0;
      while(System.currentTimeMillis()<end){ Thread.sleep(250); long len=pdf.isFile()?pdf.length():-1; if(len>0&&len==last){ if(++stable>=3) return true; } else stable=0; last=len; }
      return pdf.isFile()&&pdf.length()>0;
    } finally { deleteTree(prof); }
  }
  static void deleteTree(File f){ try{ File[] c=f.listFiles(); if(c!=null) for(File x: c) deleteTree(x); f.delete(); }catch(Throwable t){} }
  /** 브라우저 안에서 실행해 요소 위치를 뽑는 스크립트(ASCII 만 — 어떤 문자셋의 HTML 에도 끼워 넣을 수 있게).
   *  결과를 받을 통로가 인쇄된 PDF 뿐이라, 측정이 끝나면 문서를 결과 글자(항목 구분 ';', 줄 구분 '$', 글자는 16진수)로 바꿔 놓고 그 PDF 의 텍스트를 읽는다. */
  static final String HTML_PROBE=
    "<script>(function(){function go(){var out=[];try{var K=25.4/96,seen=[];function r(e){var b=e.getBoundingClientRect();return [((b.left+window.scrollX)*K).toFixed(1),((b.top+window.scrollY)*K).toFixed(1),(b.width*K).toFixed(1),(b.height*K).toFixed(1)].join(';');}"+
    "function hx(s){s=encodeURIComponent(s);var o='';for(var i=0;i<s.length;i++){var h=s.charCodeAt(i).toString(16);o+=(h.length<2?'0':'')+h;}return o;}"+
    "function tx(s){return hx((s||'').replace(/[ \\t\\r\\n]+/g,' ').replace(/^ | $/g,''));}"+
    "function st(e){var c=getComputedStyle(e);return [(parseFloat(c.fontSize)*0.75).toFixed(1),(parseInt(c.fontWeight)>=600?'B':'N'),tx(c.textAlign)].join(';');}"+
    "function vis(e){var c=getComputedStyle(e),b=e.getBoundingClientRect();return c.display!='none'&&c.visibility!='hidden'&&b.width>0&&b.height>0;}"+
    "out.push(['PAGE',(document.documentElement.scrollWidth*K).toFixed(1),(document.documentElement.scrollHeight*K).toFixed(1),tx(document.title)].join(';'));"+
    "var all=document.body?document.body.getElementsByTagName('*'):[];for(var i=0;i<all.length&&out.length<4000;i++){var e=all[i],t=e.tagName;if(t=='SCRIPT'||t=='STYLE'||t=='NOSCRIPT'||t=='HEAD'||!vis(e))continue;"+
    "if(t=='TABLE'){out.push(['TB',r(e),e.rows.length].join(';'));continue;}"+
    "if(t=='TD'||t=='TH'){var inner=e.getElementsByTagName('table').length>0;out.push(['C',e.parentNode.rowIndex,e.cellIndex,e.rowSpan,e.colSpan,r(e),st(e),inner?'':tx(e.innerText)].join(';'));if(!inner){var d=e.getElementsByTagName('*');for(var j=0;j<d.length;j++)if(d[j].tagName!='INPUT'&&d[j].tagName!='IMG'&&d[j].tagName!='SELECT'&&d[j].tagName!='TEXTAREA')seen.push(d[j]);}continue;}"+
    "if(seen.indexOf(e)>=0)continue;"+
    "if(t=='IMG'){out.push(['IMG',r(e),tx(e.alt)].join(';'));continue;}"+
    "if(t=='INPUT'||t=='SELECT'||t=='TEXTAREA'){out.push(['IN',r(e),tx((e.type||t)+(e.checked?' checked':'')),tx(e.value)].join(';'));continue;}"+
    "var own='';for(var n=e.firstChild;n;n=n.nextSibling)if(n.nodeType==3)own+=n.nodeValue;if(own.replace(/\\s+/g,'')!='')out.push(['T',r(e),st(e),tx(own)].join(';'));}"+
    "}catch(x){out=['ERR;'+String(x).replace(/[^A-Za-z0-9 ]/g,' ')];}"+
    "document.documentElement.innerHTML='<head><style>@page{size:A4;margin:0}html,body{margin:0;padding:0;background:#fff}pre{margin:0;padding:8mm;font:6pt Consolas,\"Courier New\",monospace;color:#000;white-space:pre-wrap;word-break:break-all;font-variant-ligatures:none}</style></head><body><pre></pre></body>';"+
    "document.getElementsByTagName('pre')[0].textContent='@@CLIPDUMP_BEGIN@@'+out.join('$')+'@@CLIPDUMP_END@@';}"+
    "if(document.readyState=='complete')go();else window.addEventListener('load',go);})();</script>";
  static String dec(String hex){ try{ if(hex.length()%2!=0||!hex.matches("[0-9a-fA-F]*")) return hex; byte[] b=new byte[hex.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16); return java.net.URLDecoder.decode(new String(b,StandardCharsets.US_ASCII).replace("+","%2B"),"UTF-8"); }catch(Exception e){ return hex; } }
  /** HTML 사본 만들기: <head> 바로 뒤에 <base>(원래 폴더 기준 상대 경로 유지)+headHtml, </body> 앞에 bodyEndHtml. 바이트를 그대로(문자셋 보존) 다루려고 ISO-8859-1 로 왕복한다 — 끼워 넣는 내용은 ASCII 여야 함. */
  static String htmlInject(File f,String headHtml,String bodyEndHtml) throws IOException {
    String src=new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.ISO_8859_1); String low=src.toLowerCase(Locale.ROOT);
    String base=(low.contains("<base ")?"":"<base href=\""+f.getAbsoluteFile().getParentFile().toURI().toASCIIString()+"\">")+headHtml; int hp=low.indexOf("<head"); int he=hp>=0?src.indexOf('>',hp):-1; src=he>=0?src.substring(0,he+1)+base+src.substring(he+1):base+src;
    if(bodyEndHtml.isEmpty()) return src; low=src.toLowerCase(Locale.ROOT); int bp=low.lastIndexOf("</body"); return bp>=0?src.substring(0,bp)+bodyEndHtml+src.substring(bp):src+bodyEndHtml; }
  static String html(File f,boolean detail) throws Exception {
    String br=browser(); StringBuilder note=new StringBuilder();
    if(br!=null){
      String src=htmlInject(f,"",HTML_PROBE);
      File tmp=new File(cacheDir(),"probe_"+Long.toHexString(System.nanoTime())+".html"), pdf=new File(cacheDir(),tmp.getName()+".pdf");
      try{ java.nio.file.Files.write(tmp.toPath(),src.getBytes(StandardCharsets.ISO_8859_1));
        if(browserPdf(br,tmp.toURI().toString(),pdf,"--window-size=794,1123")){ String txt; try(org.apache.pdfbox.pdmodel.PDDocument d=org.apache.pdfbox.pdmodel.PDDocument.load(pdf)){ txt=new org.apache.pdfbox.text.PDFTextStripper().getText(d); }
          txt=txt==null?"":txt.replaceAll("\\s+",""); int a=txt.indexOf("@@CLIPDUMP_BEGIN@@"), z=txt.indexOf("@@CLIPDUMP_END@@");
          if(a>=0&&z>a){ String out=htmlFormat(txt.substring(a+18,z),detail,new File(br).getName()); if(out!=null) return out; }
          note.append("(브라우저 판독 실패 — 위치 없이 글자만)\n"); }
        else note.append("(브라우저(").append(new File(br).getName()).append(") 실행 결과를 얻지 못함 — 위치 없이 글자만)\n");
      } finally { tmp.delete(); pdf.delete(); }
    } else note.append("(Edge/Chrome 을 찾지 못해 위치 없이 글자만 — CLIP_BROWSER 로 지정 가능)\n");
    return note+htmlPlain(f);
  }
  static String htmlFormat(String dump,boolean detail,String br){ StringBuilder b=new StringBuilder(); int n=0;
    for(String line: dump.split("\\$")){ String[] p=line.split(";",-1); if(p.length<2) continue;
      switch(p[0]){
        case "ERR": return null;
        case "PAGE": b.append("문서 크기 ").append(p[1]).append("×").append(p[2]).append("mm (브라우저 ").append(br).append(" 창 폭 794px = A4 210mm 로 배치; x,y = 문서 왼쪽 위 기준)").append(p.length>3&&!p[3].isEmpty()?"  제목 "+quote(dec(p[3])):"").append("\n"); break;
        case "TB": if(p.length>=6) b.append("[표 x=").append(p[1]).append(" y=").append(p[2]).append(" ").append(p[3]).append("×").append(p[4]).append("mm ").append(p[5]).append("행 — 셀 [행,그 행 안에서의 순번]; 실제 열 위치는 x 로 판단]\n"); break;
        case "C": if(p.length>=13){ String t=dec(p[12]); if(t.isEmpty()&&!detail) break; b.append("  [").append(p[1]).append(",").append(p[2]).append("]").append(!(p[3].equals("1")&&p[4].equals("1"))?" 병합 "+p[3]+"×"+p[4]:"").append(" x=").append(p[5]).append(" y=").append(p[6]).append(" ").append(p[7]).append("×").append(p[8]).append("mm ").append(dec(p[11])).append(" ").append(mm(num(p[9]))).append("pt").append(p[10].equals("B")?" B":"").append(": ").append(quote(t)).append("\n"); n++; } break;
        case "T": if(p.length>=9){ b.append("P x=").append(p[1]).append(" y=").append(p[2]).append(" ").append(p[3]).append("×").append(p[4]).append("mm ").append(dec(p[7])).append(" ").append(mm(num(p[5]))).append("pt").append(p[6].equals("B")?" B":"").append(": ").append(quote(dec(p[8]))).append("\n"); n++; } break;
        case "IMG": if(p.length>=5) b.append("[그림 x=").append(p[1]).append(" y=").append(p[2]).append(" ").append(p[3]).append("×").append(p[4]).append("mm").append(p.length>5&&!p[5].isEmpty()?" "+quote(dec(p[5])):"").append("]\n"); break;
        case "IN": if(p.length>=6) b.append("[입력 ").append(dec(p[5])).append(" x=").append(p[1]).append(" y=").append(p[2]).append(" ").append(p[3]).append("×").append(p[4]).append("mm").append(p.length>6&&!p[6].isEmpty()?" 값 "+quote(dec(p[6])):"").append("]\n"); break;
      } }
    return n==0?b.append("(보이는 글자 없음)\n").toString():b.toString(); }
  /** 브라우저가 없을 때: JDK 의 HTML 파서로 글자와 표 구조만 */
  static String htmlPlain(File f) throws Exception {
    byte[] raw=java.nio.file.Files.readAllBytes(f.toPath()); String probe=new String(raw,0,Math.min(raw.length,4096),StandardCharsets.ISO_8859_1); java.util.regex.Matcher cm=java.util.regex.Pattern.compile("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)").matcher(probe);
    java.nio.charset.Charset cs=StandardCharsets.UTF_8; if(cm.find()){ try{ cs=java.nio.charset.Charset.forName(cm.group(1)); }catch(Exception e){} }
    final StringBuilder b=new StringBuilder(); final int[] rc={0,0}; final boolean[] skip={false};
    javax.swing.text.html.HTMLEditorKit.ParserCallback cb=new javax.swing.text.html.HTMLEditorKit.ParserCallback(){
      public void handleText(char[] data,int pos){ if(skip[0]) return; String t=new String(data).replaceAll("\\s+"," ").trim(); if(!t.isEmpty()) b.append(t).append(' '); }
      public void handleStartTag(javax.swing.text.html.HTML.Tag t,javax.swing.text.MutableAttributeSet a,int pos){ if(t==javax.swing.text.html.HTML.Tag.SCRIPT||t==javax.swing.text.html.HTML.Tag.STYLE) skip[0]=true;
        else if(t==javax.swing.text.html.HTML.Tag.TABLE){ b.append("\n[표]\n"); rc[0]=0; } else if(t==javax.swing.text.html.HTML.Tag.TR){ rc[1]=0; }
        else if(t==javax.swing.text.html.HTML.Tag.TD||t==javax.swing.text.html.HTML.Tag.TH){ Object rs=a.getAttribute(javax.swing.text.html.HTML.Attribute.ROWSPAN), cs2=a.getAttribute(javax.swing.text.html.HTML.Attribute.COLSPAN); b.append("  [").append(rc[0]).append(",").append(rc[1]++).append("]").append(rs!=null||cs2!=null?" 병합 "+(rs==null?"1":rs)+"×"+(cs2==null?"1":cs2):"").append(": "); }
        else if(t.isBlock()) b.append("\n"); }
      public void handleEndTag(javax.swing.text.html.HTML.Tag t,int pos){ if(t==javax.swing.text.html.HTML.Tag.SCRIPT||t==javax.swing.text.html.HTML.Tag.STYLE) skip[0]=false; else if(t==javax.swing.text.html.HTML.Tag.TR){ rc[0]++; } else if(t==javax.swing.text.html.HTML.Tag.TD||t==javax.swing.text.html.HTML.Tag.TH||t.isBlock()) b.append("\n"); }
      public void handleSimpleTag(javax.swing.text.html.HTML.Tag t,javax.swing.text.MutableAttributeSet a,int pos){ if(t==javax.swing.text.html.HTML.Tag.BR) b.append("\n"); } };
    new javax.swing.text.html.parser.ParserDelegator().parse(new StringReader(new String(raw,cs)),cb,true);
    return b.toString().replaceAll("[ \\t]+\n","\n").replaceAll("\n{3,}","\n\n").trim()+"\n";
  }

  /** doc_image: MCP content(text + image). 실패는 ERROR 텍스트. */
  @SuppressWarnings("unchecked")
  static JSONObject image(JSONObject args) throws Exception {
    String path=s(args,"path"); if(path==null||path.trim().isEmpty()) return CrfMcpServer.textContent("ERROR: path 인자가 비어 있습니다(문서/이미지 파일 절대경로)");
    File f=new File(path.trim()); if(!f.isFile()) return CrfMcpServer.textContent("ERROR: 파일 없음: "+path);
    int maxPx=1600; try{ if(args.get("max_px")!=null) maxPx=Math.max(200,Math.min(4000,Integer.parseInt(String.valueOf(args.get("max_px")).trim()))); }catch(Exception e){ return CrfMcpServer.textContent("ERROR: max_px 는 숫자(200~4000)"); }
    int page=1; try{ if(args.get("page")!=null&&!String.valueOf(args.get("page")).trim().isEmpty()) page=Integer.parseInt(String.valueOf(args.get("page")).trim()); }catch(Exception e){ return CrfMcpServer.textContent("ERROR: page 는 숫자(1부터)"); }
    String source=s(args,"source"); source=source==null||source.trim().isEmpty()?"auto":source.trim().toLowerCase(Locale.ROOT); if(!(source.equals("auto")||source.equals("convert")||source.equals("preview"))) return CrfMcpServer.textContent("ERROR: source 는 auto|convert|preview");
    USED.get()[0]=0; System.setProperty("java.awt.headless","true"); String kind=kind(f); java.awt.image.BufferedImage img=null; String note;
    switch(kind){
      case "image": img=readImage(f); if(img==null) return CrfMcpServer.textContent("ERROR: 이미지를 읽을 수 없습니다(지원: PNG/JPG/GIF/BMP)"); note="이미지 원본 "+img.getWidth()+"×"+img.getHeight()+"px"; break;
      case "pdf": { Object[] r=renderPdf(f,page,maxPx); if(r[0]==null) return CrfMcpServer.textContent("ERROR: "+r[1]); img=(java.awt.image.BufferedImage)r[0]; note="PDF "+r[1]; break; }
      case "hwp": case "hwpx": case "docx": case "xlsx": case "xls": case "pptx": case "html": {
        StringBuilder why=new StringBuilder(); String[] how=new String[1]; File pdf=source.equals("preview")?null:toPdf(f,kind,why,how);
        if(pdf!=null){ Object[] r=renderPdf(pdf,page,maxPx); if(r[0]==null) return CrfMcpServer.textContent("ERROR: "+r[1]); img=(java.awt.image.BufferedImage)r[0]; note=kind.toUpperCase(Locale.ROOT)+" → PDF("+how[0]+") → 그림: "+r[1]; }
        else { if(source.equals("convert")) return CrfMcpServer.textContent("ERROR: 문서를 그림으로 변환하지 못했습니다 — "+why+"\n(HWP/HWPX 는 한글 또는 LibreOffice+H2Orestart, DOCX/XLSX/PPTX 는 MS Office 또는 LibreOffice, HTML 은 Edge/Chrome 이 이 PC 에 있어야 합니다. 없으면 문서를 PDF 로 저장해서 주세요)");
          byte[] pv=null;
          if(kind.equals("hwp")){ try(InputStream in=new FileInputStream(f)){ org.apache.poi.poifs.filesystem.POIFSFileSystem fs=new org.apache.poi.poifs.filesystem.POIFSFileSystem(in); pv=stream(fs.getRoot(),"PrvImage"); } }
          else if(!kind.equals("xls")&&!kind.equals("html")){ try(ZipFile z=new ZipFile(f)){ for(String cand: new String[]{"Preview/PrvImage.png","docProps/thumbnail.jpeg","docProps/thumbnail.png","docProps/thumbnail.jpg"}){ pv=bytes(z,cand); if(pv!=null) break; } } }
          if(pv!=null){ try{ img=readImage(pv); }catch(IOException e){ img=null; } }   // 깨진 미리보기 그림은 없는 것으로
          if(img==null) return CrfMcpServer.textContent("ERROR: 이 "+kind.toUpperCase(Locale.ROOT)+" 문서를 그림으로 만들 수 없습니다 — "+(source.equals("preview")?"":"변환 실패("+why+"), ")+"파일 안에 미리보기 그림도 없습니다. 문서를 PDF 로 저장해서 doc_image 를 쓰거나, doc_text 결과만으로 진행한다고 사용자에게 알리세요");
          if(page>1) return CrfMcpServer.textContent("ERROR: 내장 미리보기 그림은 첫 쪽뿐입니다(page="+page+" 불가)"+(why.length()>0?" — 변환 실패: "+why:""));
          note=kind.toUpperCase(Locale.ROOT)+" 에 저장된 미리보기 그림(첫 쪽만, 저해상도 — 문서를 저장한 프로그램이 넣어 둔 것)"+(why.length()>0?"; 전체 쪽 변환은 실패: "+why:""); }
        break; }
      default: return CrfMcpServer.textContent(unsupported(kind,f));
    }
    int ow=img.getWidth(), oh=img.getHeight(); java.awt.image.BufferedImage out=scale(img,maxPx); boolean photo=kind.equals("image")&&starts(head(f,4),0xFF,0xD8);
    return imageContent(f.getName()+" — "+note+(out!=img?"; 전송 크기 "+out.getWidth()+"×"+out.getHeight()+"px (원본 "+ow+"×"+oh+", max_px="+maxPx+")":"")+"\n이 그림을 직접 보고 doc_text 결과(글자·표 구조·위치)와 대조해 빠진 요소·병합·정렬을 확인한 뒤 작업하세요.",out,photo);
  }
  /** PDF 한 쪽을 그림으로: [BufferedImage|null, 설명 또는 오류] */
  static Object[] renderPdf(File f,int page,int maxPx) throws Exception {
    try(org.apache.pdfbox.pdmodel.PDDocument d=org.apache.pdfbox.pdmodel.PDDocument.load(f)){ int n=d.getNumberOfPages(); if(page<1||page>n) return new Object[]{null,"page "+page+" 없음 — 전체 "+n+"쪽"};
      org.apache.pdfbox.pdmodel.common.PDRectangle r=d.getPage(page-1).getMediaBox(); float dpi=(float)Math.max(36,Math.min(300,maxPx/(Math.max(r.getWidth(),r.getHeight())/72.0)));
      java.awt.image.BufferedImage img=new org.apache.pdfbox.rendering.PDFRenderer(d).renderImageWithDPI(page-1,dpi);
      return new Object[]{img,page+"/"+n+"쪽 ("+mm(r.getWidth()*PT)+"×"+mm(r.getHeight()*PT)+"mm, "+Math.round(dpi)+"dpi → 1px ≈ "+String.format(Locale.ROOT,"%.3f",25.4/dpi)+"mm"+(n>page?"; 다음 쪽은 page="+(page+1):"")+")"}; } }
}
