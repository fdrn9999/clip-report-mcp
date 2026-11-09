import com.clipsoft.clipreport.base.RexObjectList;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.reports.Report;
import com.clipsoft.clipreport.base.reports.ReportData;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.controls.Control;
import com.clipsoft.clipreport.base.controls.ControlSubreport;
import com.clipsoft.clipreport.base.datas.DataSet;
import com.clipsoft.clipreport.base.datas.DataSetItemNormal;
import com.clipsoft.clipreport.base.datas.DataAccessMethodSQL;
import com.clipsoft.clipreport.base.datas.fields.*;
import com.clipsoft.clipreport.base.enums.ScriptType;
import com.clipsoft.clipreport.base.functions.FieldLink;
import com.clipsoft.clipreport.base.groups.Group;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/** v0.14.0: 밴드(서브섹션) 이동, 서브리포트 매개변수 2단 링크, 쿼리 부분 치환, 여러 도구·여러 파일 일괄 실행. */
public class CrfStruct {
  static String s(JSONObject a,String k){ return CrfMcpServer.s(a,k); }
  static boolean yes(JSONObject a,String k){ return "true".equalsIgnoreCase(s(a,k)); }
  static RexObjectList<Section> secs(TheReportFile rf){ return rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList(); }
  static String kind(Section s){ return s.getClass().getSimpleName().replace("Section",""); }

  // ===== 섹션 지정: 번호([i] — crf_describe_layout), 영문/한글 밴드 이름, GroupHeader:필드 / GroupFooter:필드 =====
  /** 그룹 머리글/바닥글 쌍: 머리글은 본문 위에 바깥→안 순서, 바닥글은 본문 아래에 안→바깥 순서. 반환 [머리글 index, 바닥글 index] (없으면 -1). */
  static int[] groupBands(RexObjectList<Section> secs,Group g){
    int di=CrfMcpServer.detailIndex(secs); List<Integer> h=new ArrayList<>(), f=new ArrayList<>();
    for(int i=0;i<secs.size();i++){ if(i<di && secs.get(i) instanceof SectionGroupHeader) h.add(i); if(i>di && secs.get(i) instanceof SectionGroupFooter) f.add(i); }
    for(int L=0;L<h.size();L++) if(((SectionGroupHeader)secs.get(h.get(L))).getGroup()==g) return new int[]{h.get(L), f.size()==h.size()? f.get(f.size()-1-L) : -1};
    return new int[]{-1,-1};
  }
  static String sectionList(RexObjectList<Section> secs){ StringBuilder b=new StringBuilder(); for(int i=0;i<secs.size();i++) b.append(i==0?"":", ").append("[").append(i).append("]").append(label(secs,i)); return b.toString(); }
  static String label(RexObjectList<Section> secs,int i){ Section s=secs.get(i); String k=kind(s);
    if(s instanceof SectionGroupHeader){ String gf=CrfMcpServer.groupFieldOf(s); return k+":"+gf; }
    if(s instanceof SectionGroupFooter){ for(int j=0;j<secs.size();j++) if(secs.get(j) instanceof SectionGroupHeader){ int[] p=groupBands(secs,((SectionGroupHeader)secs.get(j)).getGroup()); if(p[1]==i) return k+":"+CrfMcpServer.groupFieldOf(secs.get(j)); } }
    return k; }
  static int sectionIndex(TheReportFile rf,String key){
    RexObjectList<Section> secs=secs(rf); if(key==null||key.trim().isEmpty()) throw new RuntimeException("섹션 인자가 비어 있습니다 — 이 리포트의 밴드: "+sectionList(secs));
    String k=key.trim(); if(k.startsWith("[")&&k.endsWith("]")) k=k.substring(1,k.length()-1).trim();
    if(k.matches("\\d+")){ int i=Integer.parseInt(k); if(i<secs.size()) return i; throw new RuntimeException("섹션 번호 "+i+" 범위 밖 — 이 리포트의 밴드: "+sectionList(secs)); }
    String field=null; int c=k.indexOf(':'); if(c<0) c=k.indexOf('('); if(c>0){ field=k.substring(c+1).replaceAll("[()→>\\-\\s]","").trim(); k=k.substring(0,c); }
    String eng=CrfMcpServer.engSection(k); boolean gh=eng.equalsIgnoreCase("GroupHeader"), gf=eng.equalsIgnoreCase("GroupFooter");
    if(gh||gf){ RexObjectList<Group> gl=rf.getGlobe().getMainReport().getReportObjectManager().getGroupList();
      Group g; if(field==null||field.isEmpty()){ if(gl.size()!=1) throw new RuntimeException("그룹이 "+gl.size()+"개라 어느 그룹인지 지정해야 합니다 — 예 "+eng+":EMPNO 또는 번호. 이 리포트의 밴드: "+sectionList(secs)); g=gl.get(0); } else g=CrfMcpServer.groupOf(rf,field);
      int[] p=groupBands(secs,g); int i=gh?p[0]:p[1]; if(i<0) throw new RuntimeException("그룹 "+CrfMcpServer.nameOf(g.getGroupingField())+" 의 "+(gh?"머리글":"바닥글")+" 밴드를 찾지 못함 — 번호로 지정하세요: "+sectionList(secs)); return i; }
    for(int i=0;i<secs.size();i++) if(secs.get(i).getClass().getSimpleName().equalsIgnoreCase("Section"+eng)) return i;
    throw new RuntimeException("섹션 '"+key+"' 없음 — 이 리포트의 밴드: "+sectionList(secs)+" (번호 [i], 영문 PageHeader/DataHeader/Detail/…, 한글 페이지머리글/데이터머리글/…, 그룹은 GroupHeader:필드)"); }
  static List<Integer> indexList(String sel,int n){
    List<Integer> r=new ArrayList<>(); String v=sel==null?"all":sel.trim(); if(v.isEmpty()||v.equalsIgnoreCase("all")||v.equals("*")){ for(int i=0;i<n;i++) r.add(i); return r; }
    for(String p: v.split(",")){ p=p.trim(); if(p.isEmpty()) continue; if(p.matches("\\d+-\\d+")){ int a=Integer.parseInt(p.split("-")[0]), b=Integer.parseInt(p.split("-")[1]); for(int i=Math.min(a,b);i<=Math.max(a,b);i++) if(!r.contains(i)) r.add(i); }
      else { int i=Integer.parseInt(p); if(!r.contains(i)) r.add(i); } }
    for(int i: r) if(i<0||i>=n) throw new RuntimeException("서브섹션 번호 "+i+" 범위 밖 (이 밴드의 서브섹션 "+n+"개: 0~"+(n-1)+")");
    Collections.sort(r); return r; }
  static boolean removable(Section s){ return !(s instanceof SectionDetail) && !(s instanceof SectionGroupHeader) && !(s instanceof SectionGroupFooter); }

  /** 서브섹션 객체들을 src 에서 빼서 dst 의 at 위치에 넣는다(같은 섹션이면 순서 변경). 빈 섹션 처리는 호출부. */
  static void moveSubs(Section src,List<SubSection> pick,Section dst,int at){
    RexObjectList<SubSection> sl=src.getSubSectionList(), dl=dst.getSubSectionList();
    for(SubSection x: pick) for(int i=0;i<sl.size();i++) if(sl.get(i)==x){ sl.remove(i); break; }
    int pos=at<0||at>dl.size()?dl.size():at; for(SubSection x: pick) dl.add(pos++,x);
  }
  static String subNames(Section s){ StringBuilder b=new StringBuilder(); RexObjectList<SubSection> l=s.getSubSectionList(); for(int i=0;i<l.size();i++) b.append(i==0?"":", ").append(i).append(":\"").append(l.get(i).getName()).append("\"").append(l.get(i) instanceof SubSectionSubreport?"[서브리포트]":""); return b.toString(); }

  /** crf_move_subsection */
  static String moveSubsection(JSONObject a) throws Exception {
    String path=s(a,"path"), output=s(a,"output"); TheReportFile rf=CrfMcpServer.open(path); RexObjectList<Section> secs=secs(rf);
    int fi=sectionIndex(rf,s(a,"from")), ti=sectionIndex(rf,s(a,"to")); Section src=secs.get(fi), dst=secs.get(ti);
    String fromLabel=label(secs,fi), toLabel=label(secs,ti);
    List<Integer> idx=indexList(s(a,"index"),src.getSubSectionList().size()); if(idx.isEmpty()) return "ERROR: 옮길 서브섹션이 없습니다 (index)";
    List<SubSection> pick=new ArrayList<>(); for(int i: idx) pick.add(src.getSubSectionList().get(i));
    int at=-1; String atS=s(a,"at"); if(atS!=null&&!atS.trim().isEmpty()&&!atS.trim().equalsIgnoreCase("end")){ if(atS.trim().equalsIgnoreCase("start")) at=0; else at=Integer.parseInt(atS.trim()); }
    if(src==dst && at>=0){ int before=0; for(int i: idx) if(i<at) before++; at-=before; }
    boolean empties=src!=dst && pick.size()==src.getSubSectionList().size(); boolean removeEmpty=!"false".equalsIgnoreCase(s(a,"remove_empty"));
    List<String> notes=new ArrayList<>();
    // 받는 쪽에 컨트롤 없는 빈 기본 서브섹션만 있으면(crf_add_group 이 만든 자리) drop_empty_target=true 로 지울 수 있다
    if(yes(a,"drop_empty_target") && src!=dst){ RexObjectList<SubSection> dl=dst.getSubSectionList(); for(int i=dl.size()-1;i>=0;i--){ SubSection x=dl.get(i); if(x instanceof SubSectionDefault && controlsIn(x)==0){ dl.remove(i); notes.add("받는 밴드의 빈 서브섹션 \""+x.getName()+"\" 제거"); if(at>i) at--; } } }
    moveSubs(src,pick,dst,at);
    if(empties){
      if(removable(src) && removeEmpty){ for(int i=0;i<secs.size();i++) if(secs.get(i)==src){ secs.remove(i); break; } notes.add(fromLabel+" 밴드가 비어 삭제(remove_empty=false 로 유지 가능 — 이때는 높이 0 빈 서브섹션을 남김)"); }
      else { src.getSubSectionList().add(CrfMcpServer.band(src instanceof SectionDetail?"본문1":"빈 서브섹션",0)); notes.add(fromLabel+" 밴드에 높이 0 빈 서브섹션을 남김("+(removable(src)?"remove_empty=false":"본문·그룹 밴드는 지울 수 없음 — 그룹은 crf_remove_group")+")"); } }
    String wrote=CrfMcpServer.save(rf,output,path);
    TheReportFile v=CrfMcpServer.open(output==null||output.trim().isEmpty()?path:output); RexObjectList<Section> vs=secs(v);
    StringBuilder b=new StringBuilder("OK: 서브섹션 "+pick.size()+"개 이동 "+fromLabel+" → "+toLabel+" (");
    for(int i=0;i<pick.size();i++) b.append(i==0?"":", ").append("\"").append(pick.get(i).getName()).append("\"").append(pick.get(i) instanceof SubSectionSubreport?"[서브리포트]":"");
    b.append("), wrote ").append(wrote);
    for(String n: notes) b.append("\n- ").append(n);
    b.append("\n밴드: ").append(sectionList(vs));
    for(int i=0;i<vs.size();i++){ String lb=label(vs,i); if(lb.equals(toLabel)||lb.equals(fromLabel)) b.append("\n  [").append(i).append("]").append(lb).append(": ").append(subNames(vs.get(i))); }
    if(dst instanceof SectionGroupHeader||dst instanceof SectionGroupFooter) b.append("\n※ 그룹 밴드는 그룹 값이 바뀔 때마다 반복 출력됩니다. 그룹마다 새 쪽은 crf_set_subsection(section=GroupHeader:필드, index=0, new_page=Before). crf_render rows=6 등으로 실제 출력 확인 권장");
    return b.toString();
  }
  static int controlsIn(SubSection sb){ if(!(sb instanceof SubSectionDefault)) return 1; List<Control> cs=new ArrayList<>(); CrfMcpServer.collectControls(sb,cs,Collections.newSetFromMap(new IdentityHashMap<>()),0); return cs.size(); }

  /** crf_add_group absorb=true: 데이터 머리글/바닥글의 서브섹션을 새 그룹 머리글/바닥글로 옮기고 빈 데이터 밴드는 삭제. */
  static String absorbDataBands(TheReportFile rf,Section gh,Section gfoot){
    RexObjectList<Section> secs=secs(rf); StringBuilder b=new StringBuilder();
    for(Object[] p: new Object[][]{{"SectionDataHeader",gh},{"SectionDataFooter",gfoot}}){
      Section src=null; for(int i=0;i<secs.size();i++) if(secs.get(i).getClass().getSimpleName().equals(p[0])) src=secs.get(i);
      if(src==null) continue; Section dst=(Section)p[1];
      List<SubSection> pick=new ArrayList<>(); for(int i=0;i<src.getSubSectionList().size();i++) pick.add(src.getSubSectionList().get(i));
      // 새 그룹 밴드의 기본 빈 서브섹션은 컨트롤이 없으면 버린다(라벨·소계를 넣었으면 남겨서 맨 뒤에)
      RexObjectList<SubSection> dl=dst.getSubSectionList(); for(int i=dl.size()-1;i>=0;i--) if(controlsIn(dl.get(i))==0) dl.remove(i);
      moveSubs(src,pick,dst,0);
      for(int i=0;i<secs.size();i++) if(secs.get(i)==src){ secs.remove(i); break; }
      b.append(" +").append(kind(src)).append(" 서브섹션 ").append(pick.size()).append("개 흡수");
    }
    if(b.length()==0) b.append(" (absorb: 데이터 머리글/바닥글 없음)");
    return b.toString();
  }

  // ===== 서브리포트 매개변수 2단 링크 =====
  /** 서브리포트(서브섹션 SubSectionSubreport 또는 컨트롤 ControlSubreport) 하나: [이름, 객체, 부모 링크 목록, 임베디드 Report] */
  static List<Object[]> subreports(TheReportFile rf){
    List<Object[]> r=new ArrayList<>(); RexObjectList<Section> secs=secs(rf);
    for(int i=0;i<secs.size();i++){ RexObjectList<SubSection> ss=secs.get(i).getSubSectionList(); for(int j=0;j<ss.size();j++) if(ss.get(j) instanceof SubSectionSubreport){ SubSectionSubreport x=(SubSectionSubreport)ss.get(j); r.add(new Object[]{x.getName(),x,x.getFieldLinkListForSubReportParameter(),x.getSubreport(),"["+i+"]"+label(secs,i)+" sub["+j+"]"}); } }
    for(Object[] e: CrfMcpServer.allControls(rf)) if(e[3] instanceof ControlSubreport){ ControlSubreport c=(ControlSubreport)e[3]; r.add(new Object[]{c.getName(),c,c.getFieldLinkListForSubReportParameter(),c.getSubreport(),kind((Section)e[0])+" 컨트롤"}); }
    return r; }
  static Object[] pickSubreport(TheReportFile rf,String sel){
    List<Object[]> all=subreports(rf); List<Object[]> emb=new ArrayList<>(); for(Object[] x: all) if(x[3]!=null && hasDesign((Report)x[3])) emb.add(x);
    if(sel==null||sel.trim().isEmpty()){ if(emb.size()==1) return emb.get(0); throw new RuntimeException("임베디드 서브리포트가 "+emb.size()+"개 — subreport= 로 이름을 지정하세요: "+names(all)); }
    for(Object[] x: all) if(sel.trim().equals(x[0])) return x;
    throw new RuntimeException("서브리포트 '"+sel+"' 없음 — 있는 것: "+names(all)); }
  static boolean hasDesign(Report r){ try{ return r.getReportDesign()!=null && r.getReportDesign().getMainPage()!=null && r.getReportDesign().getMainPage().getSectionList().size()>0; }catch(Exception e){ return false; } }
  static String names(List<Object[]> l){ StringBuilder b=new StringBuilder(); for(Object[] x: l) b.append(b.length()==0?"":", ").append("\"").append(x[0]).append("\"(").append(x[4]).append(x[3]!=null&&hasDesign((Report)x[3])?", 임베디드":", 링크").append(")"); return b.length()==0?"(없음)":b.toString(); }
  static DataSet datasetByName(TheReportFile rf,String n){ RexObjectList<DataSet> d=rf.getGlobe().getGlobalObjectManager().getDataSetList(); for(int i=0;i<d.size();i++) if(d.get(i).getName().equalsIgnoreCase(n.trim())) return d.get(i); return null; }
  static String queryOf(DataSet ds){ DataSetItemNormal n=ds.getDataSetItemNormal(); DataAccessMethodSQL q=n==null?null:n.getDataAccessMethodSQL(); return q==null?null:q.getQueryString(); }
  static String nm(Object f){ return CrfMcpServer.nameOf(f); }

  /** crf_link_subreport_param: 부모 필드 → 서브리포트 매개변수(FieldReportParameter) → 서브리포트 데이터셋 매개변수(FieldDataSetParameter, 쿼리의 '{dataset.X}'). */
  @SuppressWarnings("unchecked")
  static String linkSubreportParam(JSONObject a) throws Exception {
    String path=s(a,"path"), output=s(a,"output"), pfName=s(a,"parent_field");
    if(pfName==null||pfName.trim().isEmpty()) return "ERROR: parent_field 인자가 비어 있습니다 (부모 리포트의 데이터 필드, 예 EMPNO)";
    TheReportFile rf=CrfMcpServer.open(path); Object[] sr=pickSubreport(rf,s(a,"subreport")); Report sub=(Report)sr[3];
    if(sub==null||!hasDesign(sub)) return "ERROR: 서브리포트 \""+sr[0]+"\" 는 파일 링크(외부 .crf) 서브리포트입니다 — 이 도구는 임베디드 서브리포트만 연결합니다(링크 서브리포트는 그 파일을 열어 매개변수를 맞추세요)";
    Field pf=null; try{ DataSet mds=rf.getGlobe().getMainReport().getReportData().getActiveDataSet(); if(mds!=null) pf=CrfMcpServer.findDataField(mds,pfName.trim()); }catch(Exception e){}   // 부모(메인) 리포트가 쓰는 데이터셋의 필드 우선 — 같은 이름이 다른 데이터셋에도 있을 수 있다
    if(pf==null) pf=CrfMcpServer.findFieldOfKind(rf,pfName.trim(),"data"); if(pf==null) pf=CrfMcpServer.findField(rf,pfName.trim());
    if(pf==null) return "ERROR: 부모 필드 '"+pfName+"' 없음 (crf_summary 로 필드 확인)";
    String param=s(a,"param"); if(param==null||param.trim().isEmpty()) param="D_"+pf.getName(); param=param.trim();
    ReportData rd=sub.getReportData(); DataSet ds;
    String dsSel=s(a,"dataset"); if(dsSel!=null&&!dsSel.trim().isEmpty()){ ds=datasetByName(rf,dsSel); if(ds==null) ds=CrfMcpServer.datasetOf(rf,dsSel); }
    else { ds=rd.getActiveDataSet(); if(ds==null) return "ERROR: 서브리포트 \""+sr[0]+"\" 에 연결된 데이터셋이 없습니다 — dataset= 로 지정하세요"; }
    String dsParam=s(a,"dataset_param"); if(dsParam==null||dsParam.trim().isEmpty()) dsParam=param; dsParam=dsParam.trim();
    if(!CrfMcpServer.validIdent(param)||!CrfMcpServer.validIdent(dsParam)) return "ERROR: 매개변수 이름은 영문/숫자/_ 만 (param="+param+", dataset_param="+dsParam+")";
    List<String> did=new ArrayList<>();
    // (a) 서브리포트 매개변수
    RexObjectList<FieldParameter> rpl=sub.getReportObjectManager().getFieldReportParameterList(); FieldReportParameter rp=null;
    for(int i=0;i<rpl.size();i++) if(param.equalsIgnoreCase(rpl.get(i).getName()) && rpl.get(i) instanceof FieldReportParameter) rp=(FieldReportParameter)rpl.get(i);
    if(rp==null){ rp=new FieldReportParameter(); rp.setName(param); rp.setDefaultValue(""); rp.setPrompt(""); rp.setValueIsNull(false); ((RexObjectList<FieldParameter>)(RexObjectList<?>)rpl).add(rp); did.add("서브리포트 매개변수 "+param+" 생성"); }
    RexObjectList<FieldLink> l1=(RexObjectList<FieldLink>)(RexObjectList<?>)sr[2]; boolean has1=false;
    for(int i=0;i<l1.size();i++) if(l1.get(i).getLinkedField2()==rp){ if(l1.get(i).getLinkedField1()==pf) has1=true; else { did.add("기존 링크 "+nm(l1.get(i).getLinkedField1())+"→"+param+" 를 "+pf.getName()+"→"+param+" 로 교체"); l1.get(i).setLinkedField1(pf); has1=true; } }
    if(!has1){ FieldLink fl=new FieldLink(); fl.setLinkedField1(pf); fl.setLinkedField2(rp); l1.add(fl); did.add("① 부모 "+pf.getName()+" → 서브리포트 매개변수 "+param); } else did.add("① 부모 "+pf.getName()+" → "+param+" (이미 있음)");
    // (b) 데이터셋 매개변수 + 링크
    RexObjectList<FieldDataSetParameter> dpl=ds.getFieldDataSetParameterList(); FieldDataSetParameter dp=null;
    for(int i=0;i<dpl.size();i++) if(dsParam.equalsIgnoreCase(dpl.get(i).getName())) dp=dpl.get(i);
    if(dp==null){ dp=new FieldDataSetParameter(); dp.setName(dsParam); dp.setPreparedParameter(true); dp.setReplacementFormat(""); dp.setDefaultValue(""); dp.setPrompt(""); dp.setValueIsNull(false); dpl.add(dp); did.add("데이터셋 "+ds.getName()+" 매개변수 "+dsParam+" 생성"); }
    RexObjectList<FieldLink> l2=rd.getFieldLinkListForDataSetParameter(); boolean has2=false;
    for(int i=0;i<l2.size();i++) if(l2.get(i).getLinkedField2()==dp){ if(l2.get(i).getLinkedField1()==rp) has2=true; else { did.add("기존 링크 "+nm(l2.get(i).getLinkedField1())+"→"+ds.getName()+"."+dsParam+" 를 교체"); l2.get(i).setLinkedField1(rp); has2=true; } }
    if(!has2){ FieldLink fl=new FieldLink(); fl.setLinkedField1(rp); fl.setLinkedField2(dp); l2.add(fl); did.add("② 서브리포트 매개변수 "+param+" → 데이터셋 "+ds.getName()+"."+dsParam); } else did.add("② "+param+" → "+ds.getName()+"."+dsParam+" (이미 있음)");
    if(rd.getActiveDataSet()!=ds) did.add("⚠ 서브리포트에 연결된 데이터셋은 "+(rd.getActiveDataSet()==null?"없음":rd.getActiveDataSet().getName())+" — "+ds.getName()+" 이 아님(그 데이터셋 쿼리에서만 값이 쓰임)");
    String wrote=CrfMcpServer.save(rf,output,path);
    String q=queryOf(ds); String tok="'{dataset."+dsParam+"}'";
    StringBuilder b=new StringBuilder("OK: 서브리포트 \""+sr[0]+"\" 매개변수 링크, wrote "+wrote);
    for(String d: did) b.append("\n- ").append(d);
    if(q!=null && !q.toUpperCase().contains("{DATASET."+dsParam.toUpperCase()+"}")) b.append("\n⚠ 데이터셋 ").append(ds.getName()).append(" 쿼리에 {dataset.").append(dsParam).append("} 가 아직 없습니다 — 조건에 ").append(tok).append(" 를 넣으세요(예 AND B.EMPNO = ").append(tok).append("; crf_query_replace 나 crf_append_query_condition 사용)");
    else if(q!=null) b.append("\n✔ 쿼리가 {dataset.").append(dsParam).append("} 를 사용 중");
    TheReportFile v=CrfMcpServer.open(output==null||output.trim().isEmpty()?path:output); b.append("\n검증(되읽기): ");
    for(Object[] x: subreports(v)) if(String.valueOf(x[0]).equals(String.valueOf(sr[0]))) b.append(linkInfo(x));
    b.append("\n최종 확인: crf_render mode=db params={…} 로 사람마다 서브리포트 행이 바뀌는지");
    return b.toString();
  }
  /** describe_layout 용: 부모→서브리포트 매개변수, 서브리포트 매개변수→데이터셋 매개변수 두 단계를 한 줄로. */
  static String linkInfo(Object[] x){
    StringBuilder b=new StringBuilder(); RexObjectList<?> l1=(RexObjectList<?>)x[2];
    if(l1!=null&&l1.size()>0){ b.append(" 매개변수링크: "); for(int i=0;i<l1.size();i++){ Object l=l1.get(i); b.append(i>0?", ":"").append(nm(CrfMcpServer.go(l,"getLinkedField1"))).append("→").append(nm(CrfMcpServer.go(l,"getLinkedField2"))); } }
    Report sub=(Report)x[3]; if(sub!=null && hasDesign(sub)){ ReportData rd=sub.getReportData(); DataSet ads=rd==null?null:rd.getActiveDataSet();
      if(ads!=null) b.append(" 데이터셋=").append(ads.getName());
      RexObjectList<FieldLink> l2=rd==null?null:rd.getFieldLinkListForDataSetParameter();
      if(l2!=null&&l2.size()>0){ b.append(" 데이터셋링크: "); for(int i=0;i<l2.size();i++){ FieldLink l=l2.get(i); b.append(i>0?", ":"").append(nm(l.getLinkedField1())).append("→").append(ads==null?"":ads.getName()+".").append(nm(l.getLinkedField2())); } } }
    return b.toString(); }

  /** crf_validate 용: '{dataset.X}' 가 실제로 값을 받는지(2단 링크) 검사. 반환 [err, warn, info] 리스트. */
  static void datasetLinkIssues(TheReportFile rf,List<String> err,List<String> warn,List<String> info){
    RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    // 어느 리포트(메인/서브)가 어떤 데이터셋을 쓰는지
    Map<DataSet,List<Object[]>> users=new IdentityHashMap<>();
    Report main=rf.getGlobe().getMainReport(); if(main.getReportData()!=null && main.getReportData().getActiveDataSet()!=null) users.computeIfAbsent(main.getReportData().getActiveDataSet(),k->new ArrayList<>()).add(new Object[]{"메인 리포트",main,null});
    for(Object[] x: subreports(rf)){ Report sub=(Report)x[3]; if(sub==null||!hasDesign(sub)) continue; ReportData rd=sub.getReportData(); if(rd!=null && rd.getActiveDataSet()!=null) users.computeIfAbsent(rd.getActiveDataSet(),k->new ArrayList<>()).add(new Object[]{"서브리포트 \""+x[0]+"\"",sub,x}); }
    java.util.regex.Pattern tokP=java.util.regex.Pattern.compile("\\{dataset\\.([A-Za-z0-9_가-힣]+)\\}",java.util.regex.Pattern.CASE_INSENSITIVE);
    for(int i=0;i<dss.size();i++){ DataSet ds=dss.get(i); String q=queryOf(ds); if(q==null) continue;
      Set<String> toks=new LinkedHashSet<>(); java.util.regex.Matcher m=tokP.matcher(q); while(m.find()) toks.add(m.group(1));
      if(toks.isEmpty()) continue;
      List<Object[]> us=users.get(ds);
      if(us==null){ info.add("데이터셋 "+ds.getName()+": {dataset."+String.join("},{dataset.",toks)+"} 를 쓰지만 이 데이터셋을 쓰는 리포트/서브리포트가 없음(사용 안 하는 데이터셋)"); continue; }
      for(String t: toks){ FieldDataSetParameter dp=null; RexObjectList<FieldDataSetParameter> dpl=ds.getFieldDataSetParameterList(); for(int k=0;k<dpl.size();k++) if(t.equalsIgnoreCase(dpl.get(k).getName())) dp=dpl.get(k);
        if(dp==null){ err.add("데이터셋 "+ds.getName()+": 쿼리의 {dataset."+t+"} 에 해당하는 데이터셋 매개변수가 없음 — 값이 비어 0건이 됩니다 (crf_link_subreport_param parent_field=… dataset_param="+t+")"); continue; }
        for(Object[] u: us){ Report r=(Report)u[1]; RexObjectList<FieldLink> l2=r.getReportData().getFieldLinkListForDataSetParameter(); Field src=null;
          boolean dangling=false; for(int k=0;k<l2.size();k++) if(l2.get(k).getLinkedField2()==dp){ src=l2.get(k).getLinkedField1(); if(src==null) dangling=true; }
          if(dangling && src==null){ warn.add(u[0]+" → 데이터셋 "+ds.getName()+": {dataset."+t+"} 링크(②)의 출발 매개변수가 비어 있음(끊어진 링크) — 값이 비어 0건일 수 있습니다. 의도한 게 아니면 crf_link_subreport_param 으로 다시 연결"); continue; }
          if(src==null){ err.add(u[0]+" → 데이터셋 "+ds.getName()+": {dataset."+t+"} 로 값을 넘기는 링크(② 서브리포트 매개변수 → 데이터셋 매개변수)가 없음 — 에러 없이 0건이 됩니다. crf_link_subreport_param 으로 연결"); continue; }
          if(u[2]!=null && src instanceof FieldReportParameter){ RexObjectList<?> l1=(RexObjectList<?>)((Object[])u[2])[2]; boolean fed=false; if(l1!=null) for(int k=0;k<l1.size();k++) if(CrfMcpServer.go(l1.get(k),"getLinkedField2")==src && CrfMcpServer.go(l1.get(k),"getLinkedField1")!=null) fed=true;
            if(!fed) warn.add(u[0]+": 매개변수 "+nm(src)+" 에 부모 필드 링크(①)가 없음 — {dataset."+t+"} 가 빈 값이 됩니다(crf_link_subreport_param)"); } } } }
  }

  /** crf_validate 용: 셀 합치기가 켜진 표 셀의 기준 필드(SuppressReferenceFieldList) — 이 필드 값이 바뀔 때마다 합치기가 끊긴다(템플릿에서 물려받은 경우가 많음). */
  static void mergeRefNotes(TheReportFile rf,List<String> info){
    for(Object[] e: CrfMcpServer.allControls(rf)){ Object c=e[3]; if(!"ControlTable".equals(c.getClass().getSimpleName())) continue;
      Object rc=CrfMcpServer.go(c,"getRowCount"), cc=CrfMcpServer.go(c,"getColumnCount"); if(!(rc instanceof Integer)||!(cc instanceof Integer)) continue;
      for(int r=0;r<(Integer)rc;r++) for(int k=0;k<(Integer)cc;k++){ Object cell=CrfMcpServer.tableCell((Control)c,r,k); if(!CrfMcpServer.isNormalCell(cell)) continue;
        boolean on="true".equals(CrfMcpServer.g(cell,"getCellMergeRowDataDuplication"))||"true".equals(CrfMcpServer.g(cell,"getCellMergeNextDataIfSpace")); if(!on) continue;
        String refs=CrfMcpServer.refNames(CrfMcpServer.go(cell,"getSuppressReferenceFieldList")); if(refs.isEmpty()) continue;
        Object bf=CrfMcpServer.go(cell,"getApplyValueField");
        info.add(kind((Section)e[0])+" 표 \""+((Control)c).getName()+"\"["+r+","+k+"]"+(bf==null?"":"("+nm(bf)+")")+": 셀 합치기 기준필드 "+refs+" — 이 값이 바뀌는 곳에서 합치기가 끊김(의도가 아니면 crf_set_cell merge_ref=\"\" 로 해제)"); } } }

  // ===== crf_query_replace =====
  static String queryReplace(JSONObject a) throws Exception {
    String path=s(a,"path"), output=s(a,"output"), find=s(a,"find"), repl=s(a,"replace"); if(find==null||find.isEmpty()) return "ERROR: find 인자가 비어 있습니다"; if(repl==null) repl="";
    boolean regex=yes(a,"regex"); boolean flex=!regex && !"false".equalsIgnoreCase(s(a,"flex_space"));
    String ec=s(a,"expect_count"); int expect=ec==null||ec.trim().isEmpty()?1:(ec.trim().equalsIgnoreCase("any")||ec.trim().equals("*")?-1:Integer.parseInt(ec.trim()));
    java.util.regex.Pattern p;
    if(regex) p=java.util.regex.Pattern.compile(find, yes(a,"ignore_case")?java.util.regex.Pattern.CASE_INSENSITIVE:0);
    else p=java.util.regex.Pattern.compile(flex?flexPattern(find):java.util.regex.Pattern.quote(find), yes(a,"ignore_case")?java.util.regex.Pattern.CASE_INSENSITIVE:0);
    TheReportFile rf=CrfMcpServer.open(path); RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    String dsSel=s(a,"dataset"); List<DataSet> targets=new ArrayList<>();
    if(dsSel==null||dsSel.trim().isEmpty()||dsSel.trim().equals("*")){ for(int i=0;i<dss.size();i++) if(queryOf(dss.get(i))!=null) targets.add(dss.get(i)); } else targets.add(CrfMcpServer.datasetOf(rf,dsSel));
    StringBuilder b=new StringBuilder(); int total=0; List<String> per=new ArrayList<>(); Map<DataSet,String> newQ=new LinkedHashMap<>();
    for(DataSet ds: targets){ String q=queryOf(ds); if(q==null) continue; java.util.regex.Matcher m=p.matcher(q); int n=0; StringBuilder show=new StringBuilder();
      while(m.find()){ n++; if(n<=5){ int ls=m.start()>0?q.lastIndexOf('\n',m.start()-1)+1:0, le=q.indexOf('\n',m.end()); if(le<0) le=q.length(); if(le<ls) le=ls; show.append("\n    ").append(q.substring(ls,le).trim()); } }
      if(n>0){ String nq=regex?p.matcher(q).replaceAll(repl):p.matcher(q).replaceAll(java.util.regex.Matcher.quoteReplacement(repl)); newQ.put(ds,nq); per.add(ds.getName()+" "+n+"곳:"+show); total+=n; } }
    if(total==0) return "ERROR: '"+find+"' 를 찾지 못함 ("+(targets.size()==1?"데이터셋 "+targets.get(0).getName():"데이터셋 "+targets.size()+"개")+(flex?", 공백 수 무시":"")+") — crf_get_query 로 원문을 확인하세요";
    if(expect>=0 && total!=expect) return "ERROR: 일치 "+total+"곳 ≠ expect_count="+expect+" — 저장하지 않음. 범위를 좁히거나(dataset=, 더 긴 find) expect_count="+total+"/any 로 다시:\n  "+String.join("\n  ",per);
    List<String> notes=new ArrayList<>(); boolean declare=!"false".equalsIgnoreCase(s(a,"declare_params"));
    for(Map.Entry<DataSet,String> e: newQ.entrySet()){ DataSet ds=e.getKey(); DataAccessMethodSQL qm=ds.getDataSetItemNormal().getDataAccessMethodSQL(); qm.setQueryString(e.getValue());
      Set<String> declared=CrfMcpServer.declaredParams(rf); for(String u: CrfMcpServer.usedParams(e.getValue())) if(!declared.contains(u.toUpperCase())){ if(declare){ CrfMcpServer.addGlobalParam(rf,u,CrfMcpServer.nullType(),"",u); notes.add("+ 매개변수 선언 "+u); } else notes.add("⚠ 미선언 매개변수 {parameter."+u+"}"); }
      if(qm.getScriptType()==ScriptType.JavaScript){ if(!CrfMcpServer.hasReturn(e.getValue())) notes.add("✖ "+ds.getName()+": JavaScript 쿼리에 return 이 없어짐 — 0건이 됩니다"); else { String pr=CrfMcpServer.jsQueryProblem(e.getValue()); if(pr!=null) notes.add("✖ "+ds.getName()+": JavaScript 쿼리 "+pr); } } }
    String wrote=CrfMcpServer.save(rf,output,path);
    b.append("OK: ").append(total).append("곳 치환 (").append(regex?"정규식":flex?"글자 그대로, 공백 수 무시":"글자 그대로").append("), wrote ").append(wrote);
    for(String x: per) b.append("\n  ").append(x);
    for(String x: notes) b.append("\n").append(x);
    b.append("\n(필드 목록은 바꾸지 않음 — SELECT 컬럼을 바꿨으면 crf_sync_fields)");
    return b.toString();
  }
  /** 공백 수 무시 패턴: 낱말 사이 공백은 \s+, 연산자(= < > ! , ( ) + - * / |) 앞뒤 공백은 있어도 없어도 된다. */
  static String flexPattern(String find){
    List<String> tok=new ArrayList<>(); List<Boolean> op=new ArrayList<>(); String ops="=<>!,()+-*/|";
    StringBuilder w=new StringBuilder(); for(char c: find.trim().toCharArray()){
      if(Character.isWhitespace(c)){ if(w.length()>0){ tok.add(w.toString()); op.add(false); w.setLength(0); } continue; }
      if(ops.indexOf(c)>=0){ if(w.length()>0){ tok.add(w.toString()); op.add(false); w.setLength(0); } tok.add(String.valueOf(c)); op.add(true); continue; }
      w.append(c); }
    if(w.length()>0){ tok.add(w.toString()); op.add(false); }
    StringBuilder r=new StringBuilder(); for(int i=0;i<tok.size();i++){ if(i>0) r.append(op.get(i)||op.get(i-1)?"\\s*":"\\s+"); r.append(java.util.regex.Pattern.quote(tok.get(i))); }
    return r.toString(); }

  // ===== crf_batch =====
  static final Set<String> NO_BATCH=new HashSet<>(Arrays.asList("crf_batch"));
  @SuppressWarnings("unchecked")
  static JSONObject batch(JSONObject a) throws Exception {
    Object st=a.get("steps"); JSONArray steps;
    if(st instanceof JSONArray) steps=(JSONArray)st; else if(st!=null && !String.valueOf(st).trim().isEmpty()) steps=(JSONArray)new org.json.simple.parser.JSONParser().parse(String.valueOf(st)); else return CrfMcpServer.textContent("ERROR: steps 인자가 비어 있습니다 — [{\"tool\":\"crf_set_param\",\"args\":{…}}, …]");
    if(steps.isEmpty()) return CrfMcpServer.textContent("ERROR: steps 가 비어 있습니다");
    List<String> files=new ArrayList<>(); Object fv=a.get("files");
    if(fv instanceof JSONArray){ for(Object o: (JSONArray)fv) files.add(String.valueOf(o)); }
    else if(fv!=null && !String.valueOf(fv).trim().isEmpty()){ String t=String.valueOf(fv).trim(); if(t.startsWith("[")) for(Object o: (JSONArray)new org.json.simple.parser.JSONParser().parse(t)) files.add(String.valueOf(o)); else for(String x: t.split("[,;\\n]")) if(!x.trim().isEmpty()) files.add(x.trim()); }
    for(Object o: steps){ if(!(o instanceof JSONObject)) return CrfMcpServer.textContent("ERROR: steps 의 각 항목은 {\"tool\":…, \"args\":{…}} 객체여야 합니다"); String tn=String.valueOf(((JSONObject)o).get("tool")); if(NO_BATCH.contains(tn)) return CrfMcpServer.textContent("ERROR: crf_batch 안에서 crf_batch 는 쓸 수 없습니다"); }
    boolean keepGoing=yes(a,"continue_on_error");
    StringBuilder b=new StringBuilder();
    if(files.isEmpty() && yes(a,"dry_run")) return CrfMcpServer.textContent("ERROR: dry_run 은 files= 와 함께만 쓸 수 있습니다(files 없이 쓰면 각 단계가 자기 path/output 에 그대로 저장하므로 미리보기가 안 됨)");
    if(files.isEmpty()){ // 단계마다 자기 path/output — 순서대로 실행, 실패하면 멈춤
      int ok=0; for(int i=0;i<steps.size();i++){ JSONObject sp=(JSONObject)steps.get(i); String tn=String.valueOf(sp.get("tool")); JSONObject sa=argsOf(sp);
        String r=run(tn,sa); boolean bad=r.startsWith("ERROR"); b.append("── [").append(i+1).append("] ").append(tn).append(bad?" ✖":" ✔").append("\n").append(indent(r)).append("\n"); if(bad){ if(!keepGoing){ b.append("중단: ").append(steps.size()-i-1).append("단계 실행 안 함 (continue_on_error=true 로 계속)\n"); return result(b,true,ok,steps.size()); } } else ok++; }
      return result(b,ok<steps.size(),ok,steps.size()); }
    // 파일마다: 임시 사본에 모든 단계를 in_place 로 적용 → 전부 성공해야 결과 위치에 쓴다(실패한 파일은 원본/결과 그대로)
    String outDir=s(a,"out_dir"); boolean inPlace=yes(a,"in_place"); String suffix=s(a,"suffix");
    if((outDir==null||outDir.trim().isEmpty()) && !inPlace && (suffix==null||suffix.isEmpty())) return CrfMcpServer.textContent("ERROR: files= 를 줄 때는 결과 위치가 필요합니다 — out_dir=<폴더>(원본 보존), suffix=_edited(같은 폴더에 <이름>_edited.crf), 또는 in_place=true(원본 덮어쓰기, .bak 백업)");
    boolean dry=yes(a,"dry_run"); int okFiles=0; boolean fail0=false;
    Path tmpDir=Files.createTempDirectory("crfbatch");
    try{
      for(String f: files){ File src=new File(f.trim()); b.append("══ ").append(src.getName()).append("\n");
        if(!src.isFile()){ b.append("  ✖ 파일 없음: ").append(f).append("\n"); if(!keepGoing) break; continue; }
        File dest0= inPlace? src : (outDir!=null&&!outDir.trim().isEmpty()? new File(outDir.trim(),src.getName()) : new File(src.getParentFile(),src.getName().replaceFirst("(?i)\\.crf$","")+suffix+".crf"));
        if(!inPlace && dest0.getCanonicalPath().equalsIgnoreCase(src.getCanonicalPath())){ b.append("  ✖ 결과 위치가 원본과 같음("+dest0.getPath()+") — 원본을 덮어쓰려면 in_place=true(.bak 백업), 아니면 다른 out_dir/suffix\n"); fail0=true; if(!keepGoing) break; continue; }
        File work=tmpDir.resolve(src.getName()).toFile(); Files.copy(src.toPath(),work.toPath(),StandardCopyOption.REPLACE_EXISTING);
        boolean fail=false;
        for(int i=0;i<steps.size();i++){ JSONObject sp=(JSONObject)steps.get(i); String tn=String.valueOf(sp.get("tool")); JSONObject sa=argsOf(sp);
          for(Object k: new ArrayList<>(sa.keySet())){ Object v=sa.get(k); if(v instanceof String) sa.put(k,((String)v).replace("{file}",src.getPath()).replace("{name}",src.getName().replaceFirst("\\.crf$",""))); }
          sa.put("path",work.getPath()); if(writes(tn)){ sa.remove("output"); sa.put("in_place","true"); sa.put("_nobak","true"); }
          String r=run(tn,sa); boolean bad=r.startsWith("ERROR"); b.append("  [").append(i+1).append("] ").append(tn).append(bad?" ✖ ":" ✔ ").append(firstLines(r,bad?6:2).replace(work.getPath(),"(작업 사본)")).append("\n");
          if(bad){ fail=true; break; } }
        if(fail){ b.append("  → 이 파일은 저장하지 않음(원본·결과 위치 그대로)\n"); if(!keepGoing){ b.append("중단 (continue_on_error=true 면 다음 파일 계속)\n"); break; } continue; }
        File dest=dest0;
        if(dry){ b.append("  → dry_run: ").append(dest.getPath()).append(" 에 쓰지 않음\n"); okFiles++; continue; }
        if(dest.getParentFile()!=null) dest.getParentFile().mkdirs();
        if(inPlace){ File bak=new File(src.getPath()+".bak"); if(!bak.exists()) Files.copy(src.toPath(),bak.toPath()); }
        Files.copy(work.toPath(),dest.toPath(),StandardCopyOption.REPLACE_EXISTING); okFiles++;
        b.append("  → ").append(dest.getPath()).append(inPlace?" (in_place, .bak 백업)":"").append("\n"); }
    } finally { try{ for(File x: tmpDir.toFile().listFiles()) x.delete(); tmpDir.toFile().delete(); }catch(Exception e){} }
    b.insert(0,(okFiles==files.size()?"OK":"ERROR")+": 파일 "+okFiles+"/"+files.size()+"개 성공, 단계 "+steps.size()+"개"+(dry?" (dry_run)":"")+"\n");
    return CrfMcpServer.textContent(b.toString());
  }
  static JSONObject result(StringBuilder b,boolean bad,int ok,int n){ b.insert(0,(bad?"ERROR":"OK")+": 단계 "+ok+"/"+n+" 성공\n"); return CrfMcpServer.textContent(b.toString()); }
  @SuppressWarnings("unchecked")
  static JSONObject argsOf(JSONObject sp) throws Exception { Object v=sp.get("args"); if(v==null) v=sp.get("arguments"); JSONObject r=new JSONObject();
    if(v instanceof JSONObject) r.putAll((JSONObject)v); else if(v!=null && !String.valueOf(v).trim().isEmpty()) r.putAll((JSONObject)new org.json.simple.parser.JSONParser().parse(String.valueOf(v)));
    return r; }
  static boolean writes(String tool){ try{ JSONObject t=null; for(Object o: (JSONArray)CrfMcpServer.toolsList().get("tools")) if(tool.equals(((JSONObject)o).get("name"))) t=(JSONObject)o; if(t==null) return false; JSONObject props=(JSONObject)((JSONObject)t.get("inputSchema")).get("properties"); return props.containsKey("output") && !tool.equals("crf_render"); }catch(Exception e){ return false; } }
  static String run(String tool,JSONObject args){
    JSONObject saved=CrfMcpServer.CUR_ARGS.get(); String savedTool=CrfScope.CUR_TOOL.get();
    try{ JSONObject r=CrfMcpServer.callTool(wrap(tool,args)); StringBuilder t=new StringBuilder(); JSONArray c=(JSONArray)r.get("content"); int img=0;
      if(c!=null) for(Object o: c){ JSONObject x=(JSONObject)o; if("text".equals(x.get("type"))) t.append(t.length()==0?"":"\n").append(x.get("text")); else img++; }
      if(img>0) t.append("\n[그림 ").append(img).append("개 생략 — 그림이 필요하면 따로 호출]");
      String s=t.toString(); if(Boolean.TRUE.equals(r.get("isError")) && !s.startsWith("ERROR")) s="ERROR: "+s;
      // crf_validate 는 검사 결과를 돌려줄 뿐 isError 가 아니다 — 일괄 실행에서는 ✖(ERROR n>0)가 있으면 실패로 본다(gate=false 로 끔)
      if(tool.equals("crf_validate") && !"false".equalsIgnoreCase(String.valueOf(args.get("gate"))) && java.util.regex.Pattern.compile("— ERROR [1-9]").matcher(s).find()) s="ERROR: crf_validate 에 ✖ 가 있어 실패로 처리(gate=false 면 통과) — "+s;
      return s; }
    catch(Throwable e){ return "ERROR: "+CrfMcpServer.friendly(e); }
    finally{ CrfMcpServer.CUR_ARGS.set(saved); CrfScope.CUR_TOOL.set(savedTool); } }
  @SuppressWarnings("unchecked")
  static JSONObject wrap(String tool,JSONObject args){ JSONObject p=new JSONObject(); p.put("name",tool); p.put("arguments",args); return p; }
  static String indent(String s){ return "   "+s.replace("\n","\n   "); }
  static String firstLines(String s,int n){ String[] l=s.split("\n"); StringBuilder b=new StringBuilder(); for(int i=0;i<Math.min(n,l.length);i++) b.append(i==0?"":"\n      ").append(l[i]); if(l.length>n) b.append(" …"); return b.toString(); }
}
