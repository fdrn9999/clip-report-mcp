import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.controls.*;
import com.clipsoft.clipreport.base.controls.Tables.*;
import com.clipsoft.clipreport.base.RexObjectList;
import com.clipsoft.clipreport.common.enums.LineStyle;
import org.json.simple.JSONObject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/** crf_preview: 리포트의 **배치**를 그림으로 그려 돌려준다(비전 대조용).
 *  용지 한 장에 밴드를 위에서 아래로 한 번씩 쌓고, 글상자·표 셀·선·그림 자리를 좌표(0.1mm)대로 그린다. 정적 텍스트는 검정, 필드 바인딩은 파란 «필드명».
 *  디자이너 화면에 가까운 그림이지 실제 출력이 아니다 — 데이터 반복·확장·페이지 나눔·서브리포트 내용·조건 스타일은 반영하지 않는다(최종 확인은 실서버 렌더). */
public class CrfPreview {
  static int iv(Object o,String getter,int def){ try{ String v=CrfMcpServer.g(o,getter); return v==null?def:(int)Math.round(Double.parseDouble(v)); }catch(Exception e){ return def; } }
  static Color bgr(String v,Color def){ try{ int x=Integer.parseInt(v.trim()); return new Color(x&0xFF,(x>>8)&0xFF,(x>>16)&0xFF); }catch(Exception e){ return def; } }
  static final Color FIELD=new Color(0x1A,0x4F,0xA0), GUIDE=new Color(0xB8,0xC4,0xD6), BAND=new Color(0x8A,0x94,0xA6);

  static class Band { String label; int h; List<Control> cs=new ArrayList<>(); }

  @SuppressWarnings("unchecked")
  static JSONObject preview(JSONObject args) throws Exception {
    String path=CrfMcpServer.s(args,"path"); TheReportFile rf=CrfMcpServer.open(path);
    int maxPx=1600; try{ if(args.get("max_px")!=null) maxPx=Math.max(400,Math.min(4000,Integer.parseInt(String.valueOf(args.get("max_px")).trim()))); }catch(Exception e){ return CrfMcpServer.textContent("ERROR: max_px 는 숫자(400~4000)"); }
    boolean guides=!"false".equalsIgnoreCase(CrfMcpServer.s(args,"guides"));
    var mp=rf.getGlobe().getMainReport().getReportDesign().getMainPage();
    int pw=iv(mp,"getPaperWidth",2100), ph=iv(mp,"getPaperHeight",2970), ml=iv(mp,"getLeftMargin",0), mt=iv(mp,"getTopMargin",0), mr=iv(mp,"getRightMargin",0), mb=iv(mp,"getBottomMargin",0);
    List<Band> bands=new ArrayList<>(); int total=mt; RexObjectList<Section> secs=mp.getSectionList();
    for(int i=0;i<secs.size();i++){ Section sec=secs.get(i); String gf=CrfMcpServer.groupFieldOf(sec); RexObjectList<SubSection> ss=sec.getSubSectionList(); if(ss==null) continue;
      for(int j=0;j<ss.size();j++){ SubSection sb=ss.get(j); if(!sb.getVisible()) continue; Band b=new Band(); b.label=sec.getClass().getSimpleName().replace("Section","")+(gf==null?"":"("+gf+")")+(ss.size()>1?" #"+j:""); b.h=sb.getHeight();
        if(sb instanceof SubSectionDefault){ RexObjectList<ControlListForEachSeparatedPage> cls=((SubSectionDefault)sb).getControlListForEachSeparatedPageList(); for(int k=0;k<cls.size();k++){ RexObjectList<Control> cl=(RexObjectList<Control>)(RexObjectList<?>)cls.get(k).getControlList(); for(int x=0;x<cl.size();x++) b.cs.add(cl.get(x)); } }
        else if(!(sb instanceof SubSectionSubreport)) CrfMcpServer.collectControls(sb,b.cs,Collections.newSetFromMap(new IdentityHashMap<>()),0);
        else b.label+=" [서브리포트]";
        bands.add(b); total+=b.h; } }
    total+=mb; int H=Math.max(ph,total); double s=(double)maxPx/Math.max(pw,H);
    System.setProperty("java.awt.headless","true");
    BufferedImage img=new BufferedImage(Math.max(1,(int)Math.round(pw*s)),Math.max(1,(int)Math.round(H*s)),BufferedImage.TYPE_INT_RGB); Graphics2D g=img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(Color.WHITE); g.fillRect(0,0,img.getWidth(),img.getHeight());
    Stroke dash=new BasicStroke(1f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,10f,new float[]{4f,4f},0f), solid=new BasicStroke(1f);
    if(guides){ g.setColor(GUIDE); g.setStroke(dash); g.drawRect((int)(ml*s),(int)(mt*s),(int)((pw-ml-mr)*s),(int)((ph-mt-mb)*s)); }
    int y=mt, drawn=0; Font small=new Font("Malgun Gothic",Font.PLAIN,Math.max(9,(int)(26*s)));
    for(Band b: bands){
      if(guides){ g.setStroke(dash); g.setColor(GUIDE); g.drawLine(0,(int)(y*s),img.getWidth(),(int)(y*s)); 
        if(b.h>0&&ml>0){ Shape cl=g.getClip(); g.setClip(0,(int)(y*s)+1,Math.max(1,(int)(ml*s)-2),Math.max(1,(int)(b.h*s)-1)); g.setColor(BAND); g.setFont(small); g.drawString(b.label,2,(int)(y*s)+small.getSize()+1); g.setClip(cl); } }   // 밴드 이름은 왼쪽 여백 안에만(내용을 가리지 않게)
      for(Control c: b.cs){ if(!c.getVisible()) continue; try{ draw(g,c,ml,y,s); drawn++; }catch(Throwable t){ /* 한 컨트롤 실패가 전체를 막지 않게 */ } }
      y+=b.h; }
    if(guides){ g.setStroke(dash); g.setColor(GUIDE); g.drawLine(0,(int)(y*s),img.getWidth(),(int)(y*s));
      if(total>ph){ g.setColor(new Color(0xD0,0x40,0x40)); g.drawLine(0,(int)(ph*s),img.getWidth(),(int)(ph*s)); g.setFont(small); g.drawString("용지 끝("+ph+")",2,(int)(ph*s)-3); } }
    g.dispose();
    String note=new java.io.File(path).getName()+" 배치 미리보기 — 용지 "+pw+"×"+ph+" (0.1mm), 여백 L"+ml+"/T"+mt+"/R"+mr+"/B"+mb+", 밴드 "+bands.size()+"개(높이 합 "+(total-mt-mb)+"), 컨트롤 "+drawn+"개, 1px ≈ "+String.format(Locale.ROOT,"%.2f",0.1/s)+"mm\n"
      +"검정=정적 텍스트, 파랑 «…»=필드 바인딩, 점선=여백·밴드 경계(guides=false 로 끔). 밴드를 한 번씩만 쌓은 설계 화면이라 데이터 반복·확장·페이지 나눔·서브리포트 내용은 없습니다.\n"
      +"원본 양식 그림(doc_image)과 나란히 대조해 요소 누락·위치·정렬·병합·테두리를 확인하세요. 실제 출력 확인은 실서버 렌더.";
    return CrfDocs.imageContent(note,img,false);
  }

  static void draw(Graphics2D g,Control c,int ox,int oy,double s){
    int x=ox+iv(c,"getX1",0), y=oy+iv(c,"getY1",0), w=iv(c,"getWidth",0), h=iv(c,"getHeight",0); String cls=c.getClass().getSimpleName();
    if(c instanceof ControlTable){ table(g,(ControlTable)c,x,y,s); return; }
    if(cls.equals("ControlLine")){ int x2=ox+iv(c,"getX2",iv(c,"getX1",0)+w), y2=oy+iv(c,"getY2",iv(c,"getY1",0)+h); g.setColor(Color.BLACK); g.setStroke(new BasicStroke(1f)); g.drawLine(px(x,s),px(y,s),px(x2,s),px(y2,s)); return; }
    Rectangle r=new Rectangle(px(x,s),px(y,s),Math.max(1,px(x+w,s)-px(x,s)),Math.max(1,px(y+h,s)-px(y,s)));
    if(c instanceof ControlLabel){ fill(g,c,r); Object li=CrfMcpServer.go(c,"getLineInfo"); String ls=li==null?null:CrfMcpServer.g(li,"getLineStyle"); if(ls!=null&&!ls.equals("None")){ g.setColor(Color.BLACK); g.setStroke(new BasicStroke(1f)); g.drawRect(r.x,r.y,r.width,r.height); }
      text(g,c,r,s); return; }
    // 그림·서브리포트·차트·바코드 등: 자리만
    g.setColor(new Color(0xF1,0xF3,0xF6)); g.fillRect(r.x,r.y,r.width,r.height); g.setColor(BAND); g.setStroke(new BasicStroke(1f)); g.drawRect(r.x,r.y,r.width,r.height);
    Font f=new Font("Malgun Gothic",Font.PLAIN,Math.max(8,Math.min(r.height-2,(int)(28*s)))); g.setFont(f); Shape clip=g.getClip(); g.setClip(r); g.drawString("["+cls.replace("Control","")+"] "+CrfMcpServer.q(c.getName()),r.x+2,r.y+f.getSize()); g.setClip(clip);
  }
  static int px(int v,double s){ return (int)Math.round(v*s); }
  static void fill(Graphics2D g,Object o,Rectangle r){ String bs=CrfMcpServer.g(o,"getBackStyle"); if(bs!=null&&!bs.equals("Transparent")&&!bs.equals("None")){ Color bc=bgr(String.valueOf(CrfMcpServer.g(o,"getBackColor")),null); if(bc!=null){ g.setColor(bc); g.fillRect(r.x,r.y,r.width,r.height); } } }
  /** 글상자/셀의 값(정적 텍스트 또는 «필드») 을 정렬·글꼴·줄바꿈대로 그린다 */
  static void text(Graphics2D g,Object o,Rectangle r,double s){
    Object f=CrfMcpServer.go(o,"getApplyValueField"); String t=CrfMcpServer.g(o,"getApplyValueText"); boolean field=f!=null; String txt=field?"«"+CrfMcpServer.nameOf(f)+"»":t; if(txt==null||txt.isEmpty()) return;
    Object ti=CrfMcpServer.go(o,"getTextInfo"); String fn=ti==null?null:CrfMcpServer.g(ti,"getFontName"), ha=ti==null?"":String.valueOf(CrfMcpServer.g(ti,"getHorizontalAlignment")).toLowerCase(Locale.ROOT), va=ti==null?"":String.valueOf(CrfMcpServer.g(ti,"getVerticalAlignment")).toLowerCase(Locale.ROOT);
    double pt=9; try{ pt=Double.parseDouble(CrfMcpServer.g(ti,"getFontSize")); }catch(Exception e){} boolean bold=ti!=null&&"true".equals(CrfMcpServer.g(ti,"getFontBold")), wrap=ti!=null&&"true".equals(CrfMcpServer.g(ti,"getWordWrap"));
    int size=Math.max(5,(int)Math.round(pt*254/72*s)); Font font=new Font(fn==null||fn.isEmpty()||fn.equalsIgnoreCase("System")?"Malgun Gothic":fn,bold?Font.BOLD:Font.PLAIN,size);
    if(font.canDisplayUpTo(txt)>=0) font=new Font("Malgun Gothic",bold?Font.BOLD:Font.PLAIN,size);
    g.setFont(font); FontMetrics fm=g.getFontMetrics(); int pad=Math.max(1,(int)(8*s)); int avail=Math.max(1,r.width-pad*2);
    List<String> lines=new ArrayList<>(); for(String ln: txt.replace("\r\n","\n").replace('\r','\n').split("\n",-1)){
      if(!wrap||fm.stringWidth(ln)<=avail){ lines.add(ln); continue; }
      StringBuilder cur=new StringBuilder(); for(int i=0;i<ln.length();i++){ char ch=ln.charAt(i); if(cur.length()>0&&fm.stringWidth(cur.toString()+ch)>avail){ lines.add(cur.toString()); cur.setLength(0); } cur.append(ch); } lines.add(cur.toString()); }
    int lh=fm.getHeight(), th=lh*lines.size(); int y0=va.contains("bottom")?r.y+r.height-th:(va.contains("middle")||va.contains("center"))?r.y+(r.height-th)/2:r.y;
    Color col=field?FIELD:Color.BLACK; if(!field&&ti!=null){ String fc=CrfMcpServer.g(ti,"getFontColor"); if(fc!=null) col=bgr(fc,Color.BLACK); }
    Shape clip=g.getClip(); g.setClip(r.intersection(clip==null?r:clip.getBounds())); g.setColor(col);
    for(int i=0;i<lines.size();i++){ String ln=lines.get(i); int lw=fm.stringWidth(ln); int tx=ha.contains("right")?r.x+r.width-pad-lw:(ha.contains("middle")||ha.contains("center"))?r.x+(r.width-lw)/2:r.x+pad; /* 가로 가운데는 SDK 값이 Middle, 세로 가운데는 Center */ g.drawString(ln,tx,y0+i*lh+fm.getAscent()); }
    g.setClip(clip);
  }
  static void table(Graphics2D g,ControlTable t,int x0,int y0,double s){
    int rows=t.getRowCount(), cols=t.getColumnCount(); int[] cx=new int[cols+1], ry=new int[rows+1]; cx[0]=x0; ry[0]=y0;
    for(int k=0;k<cols;k++) cx[k+1]=cx[k]+t.getTableColumn(k).getWidth(); for(int r=0;r<rows;r++) ry[r+1]=ry[r]+t.getTableRow(r).getHeight();
    List<Object[]> cells=new ArrayList<>();
    for(int r=0;r<rows;r++){ RexObjectList<TableCell> l=t.getTableRow(r).getTableCellList(); for(int k=0;k<cols&&k<l.size();k++){ TableCell cell=l.get(k); if(!(cell instanceof TableCellNormal)) continue; TableCellNormal n=(TableCellNormal)cell;
        int r2=Math.min(rows,r+Math.max(1,n.getRowSpan())), k2=Math.min(cols,k+Math.max(1,n.getColSpan()));
        Rectangle rc=new Rectangle(px(cx[k],s),px(ry[r],s),Math.max(1,px(cx[k2],s)-px(cx[k],s)),Math.max(1,px(ry[r2],s)-px(ry[r],s))); fill(g,n,rc); cells.add(new Object[]{n,rc}); } }
    for(Object[] e: cells){ TableCellNormal n=(TableCellNormal)e[0]; Rectangle rc=(Rectangle)e[1];
      if("Checkbox".equals(String.valueOf(n.getCellContent()))){ int d=Math.max(4,Math.min(rc.width,rc.height)*6/10); g.setColor(FIELD); g.setStroke(new BasicStroke(1f)); g.drawRect(rc.x+(rc.width-d)/2,rc.y+(rc.height-d)/2,d,d); }
      else text(g,n,rc,s); }
    g.setColor(Color.BLACK); g.setStroke(new BasicStroke(1f));
    for(Object[] e: cells){ TableCellNormal n=(TableCellNormal)e[0]; Rectangle rc=(Rectangle)e[1];
      if(n.getVisibleLeftLine()&&n.getLineInfoLeft().getLineStyle()!=LineStyle.None) g.drawLine(rc.x,rc.y,rc.x,rc.y+rc.height);
      if(n.getVisibleRightLine()&&n.getLineInfoRight().getLineStyle()!=LineStyle.None) g.drawLine(rc.x+rc.width,rc.y,rc.x+rc.width,rc.y+rc.height);
      if(n.getVisibleTopLine()&&n.getLineInfoTop().getLineStyle()!=LineStyle.None) g.drawLine(rc.x,rc.y,rc.x+rc.width,rc.y);
      if(n.getVisibleBottomLine()&&n.getLineInfoBottom().getLineStyle()!=LineStyle.None) g.drawLine(rc.x,rc.y+rc.height,rc.x+rc.width,rc.y+rc.height); }
    if(t.getLineStyle()!=null&&!String.valueOf(t.getLineStyle()).equals("None")) g.drawRect(px(x0,s),px(y0,s),px(cx[cols],s)-px(x0,s),px(ry[rows],s)-px(y0,s));
  }
}
