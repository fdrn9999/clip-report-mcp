import com.clipsoft.clipreport.base.enums.ObjectID;
import com.clipsoft.clipreport.base.enums.PropertyID;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.ios.RexpertFileWriter;
import com.clipsoft.clipreport.base.writer.globe.TheReportFileWriter;
import java.io.*;

/**
 * .crf 저장 — 엔진 기본 writer(Rexpert4.write)의 세 가지 문제를 보정한다.
 *  1) DataAccessMethodSQLWriter 가 쿼리 본문(속성 900)을 평문 문자열로 쓴다(pre/post SQL 910/911 만 암호화).
 *     디자이너가 저장한 리포트는 900 도 암호화(type 11)이므로, 그대로 두면 저장할 때마다 쿼리가 평문이 된다.
 *     → SQL 객체의 900 을 엔진의 propertyWriteEncryptedString 으로 쓴다(리더는 9/11 둘 다 읽음).
 *  2) CrosstabCellNormalWriter 가 헤더에 속성 23개를 선언하고 24개를 쓴다 → 크로스탭이 든 리포트를 저장하면
 *     되읽기에서 깨진다. → 그 객체·그 개수일 때만 24 로 고친다.
 *  3) WebLinkInfoWriter 가 팝업 너비를 팝업 높이의 속성 ID(48116)로 쓴다 → 되읽으면 높이가 너비 값으로 바뀌고
 *     너비는 사라진다. → 한 객체 안에서 두 번째로 나오는 48116 을 48117(PID_PopupWidth)로 쓴다.
 * 저장소 실측(2,549개): 엔진 기본 writer 로는 크로스탭이 든 48개가 되읽기 실패(EOFException), 웹 링크가 든 8개는 값이 바뀜.
 * 암호 알고리즘·키는 엔진 것을 그대로 쓴다(자체 구현 없음).
 */
public class CrfIO {
  /** 쿼리 본문을 평문으로 남기려면 CLIP_SQL_PLAIN=1 (디버깅용). */
  static final boolean PLAIN="1".equals(System.getenv("CLIP_SQL_PLAIN"));

  static class Writer extends RexpertFileWriter {
    int cur; boolean popupH;
    Writer(OutputStream out){ super(out); }
    @Override public void writeObjectHeader(int id,int props,int childs) throws IOException {
      cur=id; popupH=false;
      if(id==ObjectID.OID_CrosstabCellNormal && props==23) props=24;
      super.writeObjectHeader(id,props,childs);
    }
    @Override public void propertyWriteString(int id,String v) throws IOException {
      // 객체는 자기 속성을 자식 객체보다 먼저 쓰므로 cur 가 SQL 객체인 동안의 900 은 그 객체의 쿼리 본문이다.
      if(!PLAIN && cur==ObjectID.OID_DataAccessMethodSQL && id==900) super.propertyWriteEncryptedString(id,v==null?"":v);
      else super.propertyWriteString(id,v);
    }
    @Override public void propertyWriteInt(int id,int v) throws IOException {
      if(id==PropertyID.PID_PopupHeight){ if(popupH) id=PropertyID.PID_PopupWidth; popupH=true; }
      super.propertyWriteInt(id,v);
    }
  }

  public static void write(TheReportFile rf,OutputStream out) throws IOException {
    Writer w=new Writer(out);
    try{ w.setEncrypted(rf.getGlobe().getEncrypted()); TheReportFileWriter.write(w,rf); }
    finally{ w.close(); }
  }

  /** 임시 파일에 쓰고 되읽어 본 뒤 제자리로 옮긴다 — 쓰다 실패해도 기존 파일이 깨지지 않는다. */
  public static void write(TheReportFile rf,String path) throws IOException {
    File o=new File(path), dir=o.getAbsoluteFile().getParentFile();
    File tmp=File.createTempFile(".crfio",".tmp",dir);
    try{
      try(OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))){ write(rf,out); }
      try{ com.clipsoft.clipreport.base.Rexpert4.read(tmp.getPath()); }
      catch(Throwable t){ throw new IOException("저장한 파일을 되읽지 못했습니다(저장 취소, 기존 파일 유지): "+t,t); }
      try{
        try{ java.nio.file.Files.move(tmp.toPath(),o.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        catch(java.nio.file.AtomicMoveNotSupportedException e){ java.nio.file.Files.move(tmp.toPath(),o.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
      }catch(IOException e){   // 교체 실패(대상이 열려 있음 등): 검증까지 끝난 임시 파일을 지우지 않고 알려 준다
        File keep=new File(dir,o.getName()+".unsaved"); try{ java.nio.file.Files.move(tmp.toPath(),keep.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); }catch(IOException e2){ keep=tmp; }
        tmp=null; throw new IOException("저장 파일을 "+o.getPath()+" 로 옮기지 못했습니다("+e+") — 대상이 디자이너 등에서 열려 있는지 확인하세요. 저장하려던 내용은 "+keep.getPath()+" 에 있습니다",e); }
    } finally { if(tmp!=null && tmp.exists() && !tmp.delete()) tmp.deleteOnExit(); }
  }

  public static byte[] bytes(TheReportFile rf) throws IOException {
    ByteArrayOutputStream b=new ByteArrayOutputStream(); write(rf,b); return b.toByteArray();
  }

  /** CLI: java -cp ... CrfIO in.crf out.crf — 그대로 다시 저장(평문 SQL 로 저장된 리포트를 암호화 저장으로 되돌릴 때). */
  public static void main(String[] a) throws IOException {
    if(a.length<2 || new File(a[0]).getCanonicalPath().equalsIgnoreCase(new File(a[1]).getCanonicalPath())){ System.err.println("usage: CrfIO <in.crf> <out.crf>  (out 은 in 과 달라야 함)"); System.exit(2); }
    write(com.clipsoft.clipreport.base.Rexpert4.read(a[0]),a[1]); System.err.println("saved "+a[1]);
  }
}
