import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.datas.DataSet;
import com.clipsoft.clipreport.base.datas.fields.Field;
import com.clipsoft.clipreport.base.datas.fields.FieldData;
import com.clipsoft.clipreport.base.enums.DataType;
import com.clipsoft.clipreport.base.RexObjectList;

public class CrfAddField {
  @SuppressWarnings("unchecked")
  static void dumpTail(TheReportFile rf, String tag) throws Exception {
    GlobalObjectManager gom = rf.getGlobe().getGlobalObjectManager();
    DataSet ds = gom.getDataSetList().get(0);
    RexObjectList<FieldData> fl = (RexObjectList<FieldData>) ds.getFieldDataList();
    System.out.println("["+tag+"] dataset="+ds.getName()+" fieldCount="+fl.size());
    for(int i=Math.max(0,fl.size()-4); i<fl.size(); i++){
      FieldData f=fl.get(i);
      System.out.printf("     #%d name=%-16s type=%-8s letter=%s id=%d%n",
        i, f.getName(), f.getDataType(), f.getFieldLetter(), f.getObjectID());
    }
  }
  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    String in=a[0], out=a[1];
    TheReportFile rf = Rexpert4.read(in);
    dumpTail(rf, "BEFORE");

    GlobalObjectManager gom = rf.getGlobe().getGlobalObjectManager();
    DataSet ds = gom.getDataSetList().get(0);
    RexObjectList<FieldData> fl = (RexObjectList<FieldData>) ds.getFieldDataList();

    // (A) brand new fields (simulate query columns)
    String[] cols = {"TEST_NEW_COL", "DEPT_NM", "TOTAL_AMT"};
    DataType[] types = {DataType.Number, DataType.String, DataType.Currency};
    for(int i=0;i<cols.length;i++){
      FieldData nf = new FieldData();
      nf.setName(cols[i]);
      nf.setDataType(types[i]);
      nf.setIndex(fl.size());
      fl.add(nf);
    }

    System.out.println("\n-- added "+cols.length+" fields, writing --\n");
    Rexpert4.write(rf, out);
    TheReportFile rf2 = Rexpert4.read(out);
    dumpTail(rf2, "AFTER");

    // verify presence
    RexObjectList<FieldData> fl2 = (RexObjectList<FieldData>) rf2.getGlobe().getGlobalObjectManager().getDataSetList().get(0).getFieldDataList();
    int found=0;
    for(int i=0;i<fl2.size();i++){ String n=fl2.get(i).getName();
      if("TEST_NEW_COL".equals(n)||"DEPT_NM".equals(n)||"TOTAL_AMT".equals(n)) found++; }
    System.out.println("\nRESULT: newColsFound="+found+"/3  finalCount="+fl2.size()+" (expected 54)");
  }
}
