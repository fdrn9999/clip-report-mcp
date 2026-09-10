import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.reports.Report;
import com.clipsoft.clipreport.base.sections.*;
import com.clipsoft.clipreport.base.page.MainPage;
import com.clipsoft.clipreport.base.controls.*;
import com.clipsoft.clipreport.base.enums.*;
import com.clipsoft.clipreport.base.datas.*;
import com.clipsoft.clipreport.base.datas.fields.Field;
import com.clipsoft.clipreport.base.datas.fields.FieldData;
import com.clipsoft.clipreport.base.datas.fields.FieldFormula;
import com.clipsoft.clipreport.base.datas.fields.FieldGlobalParameter;
import com.clipsoft.clipreport.common.enums.BackStyleType;
import java.sql.*;
import com.clipsoft.clipreport.base.groups.Group;
import com.clipsoft.clipreport.base.RexObjectList;
import java.util.*;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import java.io.*;
import java.nio.file.*;

/** Minimal stdio MCP server (hand-rolled JSON-RPC 2.0) exposing CLIP-report tools to Claude.
 *  Tools: crf_summary, crf_generate (v2 → CrfDerive; legacy=true → CrfGen3), crf_set_query. No API key needed (server side). */
public class CrfMcpServer {
  static PrintStream mcp;   // protocol channel (real stdout)
  static JSONParser P = new JSONParser();

  // Server version = the VERSION file, embedded into the jar as resource /VERSION by build.ps1 (single source of truth; no constant to drift).
  static final String VERSION = readVersion();
  static String readVersion(){ try(InputStream in=CrfMcpServer.class.getResourceAsStream("/VERSION")){ if(in!=null){ String v=new String(in.readAllBytes(),"UTF-8").trim(); if(!v.isEmpty()) return v; } }catch(Exception e){} return "0.0.0-dev"; }

  // CLIP report concept primer surfaced to the LLM via MCP `instructions` so it explains/suggests in proper terms.
  static final String INSTRUCTIONS =
    "이 서버는 Clipsoft CLIP report(.crf) 리포트를 읽고 생성·수정하며, 사용자에게 설명/개선제안하는 도구를 제공합니다. 아래 개념·용어로 답하세요.\n"+
    "[섹션(밴드)] 보고서 머리글/바닥글(첫·끝 페이지 1회), 페이지 머리글/바닥글(매 페이지 상·하단), 데이터 머리글/바닥글(본문 상·하단 1회), 그룹 머리글/바닥글(그룹핑), 본문(레코드 수만큼 반복).\n"+
    "[필드] 데이터(DB컬럼), 매개변수(사용자입력→SQL조건), 시스템(PageNofM/PageNumber/RecordNumber/PrintDateTime 등), 공식(JS/VBScript 계산: sum/avg/count/min/max/field/prev/next/format 등), 누적합산(누적요약, 그룹마다 초기화 가능), 그룹이름/그룹인덱스.\n"+
    "[★공식필드(JavaScript·식 끝에 return 필수)] 필드참조=rexpert.field(\"ns.NAME\") (ns=data/system/parameter/formula/runningtotal/parent/dataset, 작은·큰따옴표 모두 가능). 요약=rexpert.sum|avg|count|min|max(범위Int, \"data.COL\", 옵션Int, \"그룹기준|빈\", \"조건식|빈\")—5번째 인자에 조건식 주면 조건부 집계(예 \"data.ITEM_CD01=1\", and(data.A=..,data.B=..)). 이웃행=rexpert.prev|next(\"data.COL\"), N번째=rexpert.fieldat(\"data.COL\",n), 정수화=rexpert.fieldbyint(\"data.COL\"). 출력형식=rexpert.format(값, \"#,##0\"|\"yyyy.mm.dd\"|\"=[1-4].[5-6].[7-8]\")—'=[시작-끝]…'은 문자열을 자리수로 잘라 조립(예 날짜 2026.06.08). 동적문장=var s=\"\"; s+=\"…\"+rexpert.field(\"data.X\")+\"…\"; return s; 패턴. 공식식 안에서 :col/#{}/${}/? 금지(그건 쿼리 파라미터용).\n"+
    "[컨트롤] 글상자(라벨), 표(셀=행×열), 선, 이미지, 서브리포트, 차트, 바코드. 위치=왼쪽/위쪽/너비/높이.\n"+
    "[셀/라벨 값] 데이터·공식·시스템 필드 바인딩 또는 정적 텍스트. 출력양식=일반/숫자/백분율/통화/날짜/시간/사용자정의. 확장가능(텍스트 넘치면 아래로), 글꼴크기조정, 셀 자동합치기.\n"+
    "[그룹] 그룹 머리글=타이틀, 그룹 바닥글=요약함수(합계 등)로 소계. [조건스타일] 조건 충족 시 배경색 등 변경. [서브리포트] 다른 데이터셋. [다단] 레코드를 단으로 연속 출력.\n"+
    "[★쿼리 파라미터] {parameter.X} 는 항상 작은따옴표 문자열로 치환되므로 날짜 포맷은 TO_CHAR(TO_DATE('{parameter.DT}','YYYYMMDD'),…) 처럼 TO_DATE 먼저(문자열에 곧장 TO_CHAR 하면 Tibero JDBC-5075 로 쿼리 전체가 0건). 쿼리 수정은 디자이너에서 데이터셋을 통째로 다시 만들지 말고(셀 바인딩이 끊김) crf_set_query 로 문자열만 교체. CLIP 리포트 쿼리에서 파라미터는 반드시 '{parameter.COLNM}' 형식(대문자, 언더바는 유지: empNm→EMPNM, emp_nm→EMP_NM)으로 작성하세요. 문자열 조건은 작은따옴표로 감싸 \"= '{parameter.X}'\". 절대 :colNm, #{colNm}, ${colNm}, ? 같은 일반 SQL/MyBatis 바인드 표기를 쓰지 마세요. {dataset.X}는 다른 데이터셋 값 참조용입니다.\n"+
    "[★입력은 상황마다 다름] 화면(.xfdl/.vue)·문서양식(PDF)·쿼리(SQL/MyBatis)·백엔드·DB연결이 항상 다 주어지지는 않습니다(화면만, 쿼리 없이, 글 설명만일 수도). 프롬프트에 실제로 있는 자료만 사용하고, 적용 안 되는 단계는 건너뛰며, 도구는 '있는 입력+의도'에 맞춰 선택합니다(고정 순서 아님). 도구로 직접 확인 가능한 건 먼저 확보(파일 읽기·db_* 도구·백엔드 추적)하되, [★모르면 질문] 그래도 부족하거나 불명확한 정보(대상 파일·테이블·파라미터·조건 등)는 임의 추정·기본값으로 진행하지 말고 반드시 유저에게 질문해 확보하세요(질문은 한 번에 모아 간결히). 유저가 '추정해서 진행'을 명시한 경우에만 가정을 밝히고 진행합니다.\n"+
    "[★쿼리 읽기/찾기] 리포트의 SQL 본문은 crf_get_query(JS 동적쿼리는 평문 복원본 포함; XML/JSON 데이터셋은 루트 XPath·필드 경로), 공식 스크립트는 crf_get_formula, '어떤 리포트가 테이블 X/매개변수 Y/문구 Z 를 쓰나'는 crf_search(dir, text, scope) 로 확인하세요. crf_summary 는 개요만 줍니다.\n"+
    "[★엑셀 출력 격자] 목록형 리포트는 대부분 화면에서 엑셀로도 내려받으므로, 엑셀로 내보내는 밴드(페이지 머리글·데이터 머리글/바닥글·그룹·본문; **페이지 바닥글은 엑셀에서 빠지므로 제외**)의 **모든 세로선 — 표 열 경계, 글상자/이미지의 왼쪽·오른쪽, 선의 x — 을 본문 표의 열 경계(±1mm)에 맞추세요**. 어긋난 세로선 하나마다 엑셀 열이 하나 더 쪼개집니다(예: 머리글 글상자 오른쪽이 620 인데 본문 경계가 460/840 이면 열 분할). 머리글 제목·조건 글상자·집계 표를 만들거나 옮길 때 left/width 를 본문 경계 값으로 주고, 끝에 crf_validate excel=true 로 '엑셀 격자' 경고가 0 인지 확인(요소별 어긋난 변과 가까운 경계를 알려줌 → crf_set_label left/width, crf_table_cols resize 로 맞춤). 사용자가 일부러 안 맞춘 것(바닥글 페이지번호 등)은 그대로 둡니다.\n"+
    "[★필드는 위치 매핑] 데이터셋 필드는 이름이 아니라 **목록 순서(위치)** 로 SELECT 컬럼에 대응합니다(디자이너 파일은 index 가 전부 0). 필드 순서 ≠ SELECT 순서면 에러 없이 엉뚱한 값(사번 칸에 부서명)이 찍힙니다. crf_set_query/crf_sync_fields 는 기본(reorder=true)으로 SELECT 순서에 맞춰 재정렬하고, 별칭 없는 식 컬럼은 COL_n 자리 필드를 만들어 위치를 지킵니다(AS 별칭 권장). 순서만 고치려면 crf_reorder_fields. SELECT 에 없는 필드는 끝으로 밀려 값이 비므로 sync_fields=replace 로 정리하세요.\n"+
    "[★JS 동적쿼리는 return 필수] scriptType=JavaScript 쿼리는 마지막에 `return sql;` 이 없으면 에러 없이 0건입니다(저장소 JS 쿼리 1,491개 전부 return 있음). crf_set_query 가 경고하고 crf_validate 가 ✖ 로 잡습니다. MyBatis 변환은 return 을 자동으로 붙이며 isValid/isEmpty/isNotEmpty/\"Y\".equals(x)/&lt; 엔티티/본문 <= 연산자를 처리합니다(<include>/<foreach> 는 수동).\n"+
    "[★목록형 리포트는 crf_generate 한 방] 비슷한 목록형 리포트(템플릿)에서 새 리포트를 파생할 때는 도구를 50번 부르지 말고 **crf_generate(template, sql, output, columns=[{field,title,width,align,format,total}], title, cond, cond_right, groups=none|auto|A,B)** 한 번으로: 템플릿 그룹·묵은 필드/바인딩 정리 → 쿼리(매개변수 선언·필드 SELECT 순서; SELECT * 는 fields_from_db=true) → 본문/제목/합계 표 재구성 → 제목·조건 글상자 → 그룹(소계) → 로고 유지 → 엑셀 격자 맞춤 → crf_validate excel=true 까지 자동. 열만 바꾸려면 crf_set_columns, 기존 JS 쿼리에 조건 하나 보태려면 crf_append_query_condition(param, sql 조각; ORDER BY 앞에 if 블록). 생성 후 실서버 렌더로 확인.\n"+
    "[★데이터셋 수정] 쿼리 교체는 crf_set_query(매개변수 자동 선언 + SELECT 컬럼을 필드로 추가·SELECT 순서로 재정렬). SELECT * 등 파싱 불가면 crf_sync_fields(mode=db)로 DB 에서 컬럼을 확정. 데이터셋 추가/삭제=crf_add_dataset/crf_remove_dataset, 매개변수=crf_set_param/crf_remove_param, 필드 이름변경/삭제=crf_rename_field/crf_remove_field(참조 검사; 참조 확인만은 crf_field_refs).\n"+
    "[★레이아웃 수정/검증] 셀=crf_set_cell(값·공식·출력양식·정렬·폰트·병합값; 여러 셀은 cells=[{row,col,…}] 한 번에; 출력양식 제거는 format='' 또는 clear_format=true), 글상자=crf_set_label, 밴드 행=crf_set_subsection(높이/숨김/페이지바꿈), 표 생성=crf_add_table(columns JSON), 표 구조=crf_table_info(열 너비·행 높이·병합 격자 확인) → crf_table_rows/crf_table_cols(action=insert|copy|delete|move|resize|equalize: 행/열 중간 삽입·복제·삭제·이동·너비/높이·균등 분배; 병합 셀은 자동으로 늘리고/승격) · crf_set_table(위치·전체 너비/높이 비례 조정·외곽선·셀 테두리 일괄·unmerge_all) · 셀 테두리=crf_set_cell border=left,top… , 그룹=crf_add_group(level/label/subtotal)·crf_set_group·crf_remove_group, 삭제=crf_remove_control/crf_remove_section(영문 DataFooter/한글 데이터바닥글 모두 가능; 없으면 오류에 현재 밴드 목록)/crf_remove_field. 용지 방향 전환은 crf_set_paper orientation=Landscape fit=true(가로/세로 크기 교환 + 모든 밴드 요소를 새 본문 너비에 비례 조정). 넓은 표는 crf_describe_layout detail=true(행 공통 스타일 «공통» 압축)·one_per_line=true 로 읽기. 수정 후에는 crf_validate 로 끊어진 바인딩·공식·매개변수를 점검하고, 원본과 비교는 crf_diff.\n"+
    "[★체크박스] 체크 표시는 ■/□·●/○ 글자로 흉내내지 말고 crf_set_cell_checkbox(셀 내용=체크박스 + 참/거짓 조건: field/true_value/false_value)로 만드세요. 조건이 비어 있으면 값이 뭐든 항상 빈 상자입니다. 기본은 색칠(check_type=Rectangle), V 체크/원은 옵션.\n"+
    "[★참조 양식은 요소 전부를 좌표까지 판독] HWPX·PDF·DOCX·HTML·이미지 등 어떤 형식이든 참조 양식을 받으면 눈에 띄는 것만 옮기지 말고 **모든 요소**(제목·부제·문단·표와 셀·선·상자·도형·체크칸·서명란·직인 자리·머리글/바닥글·페이지번호·로고·여백)를 **배치·좌표(x,y,너비,높이 mm)·정렬·글꼴/크기/굵게/색·테두리/배경·데이터 자리 여부**까지 빠짐없이 표로 인벤토리한 뒤 배치를 시작하세요. 형식별: HWPX=samples/document/parse_hwpx.py(문단 vertpos, 표 셀 크기, 도형 절대좌표, 앵커 문단 정렬) · PDF=pdf_text 만으로는 위치가 사라지므로 PyMuPDF 로 텍스트 블록 bbox + 페이지 이미지를 함께 · DOCX=word/document.xml(문단·표·정렬·섹션 여백, EMU→mm ÷36000) · HTML=렌더 스크린샷 + 요소 박스(getBoundingClientRect) · 이미지=Read 로 보고 비율로 좌표를 잡되 실제 크기(용지)는 사용자에게 확인. 인벤토리에 없는 요소가 결과물에 없거나 위치가 다르면 미완성이며, 마지막에 렌더 결과를 원본과 요소별로 나란히 대조(정렬 체크리스트)해 차이를 보고합니다.\n"+
    "[★디자인 결정은 사용자에게 묻기] 리포트를 새로 만들거나 크게 고칠 때는 아래 갈림길을 만나면 임의로 정하지 말고 **작업 전에 결정 목록을 한 번에(3~6개) 제시**하고, 항목마다 권장안(기본값)을 붙여 답을 받은 뒤 진행하세요. 중간에 새 갈림길이 나오면 그 시점에 다시 묻습니다(사용자가 이미 지정했거나 '알아서/추정해서 진행' 이라 한 항목만 생략). 갈림길: ① 요소 구성 — 연속 줄을 1열 표 하나 / 줄바꿈 글상자 하나 / 글상자 여러 개 중 무엇으로(권장: 표 하나) ② 체크박스 — 셀 체크박스 vs 독립 컨트롤, 체크 모양 색칠(Rectangle)/V/원, 상자 모양·크기, 여러 항목이면 항목별 칸 ③ 레이아웃(양식 없이 새로 만들 때) — 목록형(표+머리글) vs 문서형(양식), 용지·방향·여백, 제목/부제/출력일시/페이지번호/로고 위치, 열 순서·너비·정렬·출력양식, 그룹·소계·총계 여부, 서명란·비고란 ④ 글꼴 — 이 리포트/모듈 관례(돋움체·바탕체·나눔고딕)와 크기, 라벨·데이터 통일 ⑤ 쿼리 — 매개변수 이름·정렬·조건, 코드→명칭 변환 ⑥ 저장 — output 파일명·위치, 기존 파일 덮어쓸지. 질문은 '항목: 선택지 (권장 X — 이유 한 줄)' 형식으로 짧게, 답을 받으면 결정 요약을 한 줄로 되짚고 진행합니다.\n"+
    "[★요소 구성 원칙] 세로로 이어지는 문단·번호 목록·※주석처럼 스타일(x·너비·글꼴·크기)이 같은 줄들은 글상자를 줄마다 따로 만들지 말고 요소 하나로 만드세요: 1열 표(crf_add_table rows=[\"줄1\",\"줄2\",…], 줄마다 행) 또는 줄바꿈 글상자 하나(crf_add_label wrap=true, 줄바꿈 \\n). 별개 요소로 나누는 경우는 정렬·글꼴·바인딩·위치가 서로 다를 때뿐입니다. 이미 쪼개진 글상자는 crf_merge_labels(names, into=table|label) 로 합치고, 반대로 한 덩어리를 줄/행별로 나눠야 하면 crf_split_label. crf_validate 가 세로 연속 글상자를 경고합니다.\n"+
    "[★글꼴 관례] 새 글상자/셀의 글꼴은 리포트의 기존 글꼴(라벨/데이터 각각 집계)을 자동 상속하고, 리포트에 없으면 같은 폴더 이웃 리포트 관례를 따릅니다(SDK 기본 'System' 글꼴 금지 — crf_validate 가 경고). 이 저장소 관례: 목록형=돋움체, 문서형(통지서·서약서)=바탕체, 일부 모듈=나눔고딕; 라벨(글자)과 데이터(숫자) 글꼴은 같은 리포트 안에서 통일. 일괄 정리는 crf_set_font(font/data_font/size, only_system=true 면 System 만 교체).\n"+
    "[★화면→인쇄물 옮길 때] 화면의 빨간 강조·버튼 문구('제출하기' 등)·안내 배너는 인쇄물에 넣지 않습니다. 체크 모양(색칠 Rectangle / V)은 문서마다 사용자 지시를 따르고 지시가 없으면 물어보세요. 엑셀 저장 격자: 머리글 표의 열 경계는 본문 표 경계의 부분집합(오차 10 이내)이어야 열이 쪼개지지 않습니다(crf_validate 가 어긋난 경계를 알림). 그룹 머리글이 매 페이지 반복되면 crf_set_subsection repeat=None.\n"+
    "[★문서형(양식) 리포트] 통지서·서약서·신고서처럼 레코드 1건짜리 양식은 본문 밴드 하나에 글상자/표를 좌표로 배치합니다. 좌표 단위 0.1mm(A4=2100×2970), 색은 #RRGGBB, 줄바꿈 텍스트는 linespace(pt, 10pt 글꼴이면 5.5≈HWP 160%), 셀 병합=crf_merge_cells, 글상자 테두리=border, 글자색/밑줄=color/underline. 양식(HWPX/PDF)의 정렬(가운데/좌/우, 표 앵커 문단 정렬 포함)을 요소별로 대조하세요. 자세한 절차는 docs/document-report-recipe.md.\n"+
    "쓰기 도구는 output 경로를 따로 받아 원본을 보존합니다(output=원본이면 거부). 한 파일에 여러 단계를 이어서 고칠 때는 a/b 파일을 번갈아 쓰지 말고 **in_place=true**(output 생략 가능; 첫 쓰기 때 <path>.bak 에 원본 백업, 이후 유지)로 같은 파일에 계속 쓰세요 — a/b 교대는 한 단계가 ERROR 로 실패하면 다음 단계가 한 단계 전 파일을 읽어 변경이 조용히 유실됩니다. 배치에서는 한 호출이 ERROR(isError) 면 뒤 호출을 보내지 말고 멈추세요(tools/mcpcall.py 는 기본 stop-on-error). 없을 수도 있는 매개변수/밴드 삭제는 ignore_missing=true. 도구 실패는 'ERROR: …' 메시지(isError)로 옵니다 — 그대로 유저에게 설명하고 임의로 재시도하지 마세요. crf_describe_layout 의 표 셀 중 ‹병합› 은 병합되어 숨은 자리라 편집 불가(기준 셀에 설정), {…} 는 출력양식입니다.\n"+
    "[★열린 파일 주의] .crf가 CLIP report 앱에서 열려 있는 동안 쓰기 도구로 수정하면 파일 잠금/상태 충돌(앱에서 저장 시 편집이 덮어써짐, 또는 편집이 앱에 반영 안 됨)이 납니다. 이미 만든 _edited.crf에 추가 수정이 필요할 때 그 파일이 열려 있을 수 있으면, 먼저 유저에게 '저장 후 잠깐 닫기'를 요청하고 → 수정 → '다시 열기'를 안내하세요(저장→닫기→수정→재오픈).";

  // ---- reflection helpers for rich, defensive property reads ----
  static String g(Object o,String m){ if(o==null)return null; try{ Object v=o.getClass().getMethod(m).invoke(o); return v==null?null:v.toString(); }catch(Exception e){ return null; } }
  static Object go(Object o,String m){ if(o==null)return null; try{ return o.getClass().getMethod(m).invoke(o); }catch(Exception e){ return null; } }
  static String nameOf(Object o){ if(o==null) return "-"; String n=g(o,"getName"); return n==null?o.getClass().getSimpleName():n; }
  static String fieldKindKo(Object f){ if(f==null)return null; switch(f.getClass().getSimpleName()){
    case "FieldData": return "데이터"; case "FieldFormula": return "공식"; case "FieldGlobalSpecial": return "시스템";
    case "FieldGlobalParameter": case "FieldParameter": case "FieldReportParameter": return "매개변수";
    case "FieldRunningTotal": return "누적합산"; case "FieldGroupName": return "그룹이름"; case "FieldGroupIndex": return "그룹인덱스";
    default: return f.getClass().getSimpleName(); } }
  static void inv(StringBuilder b,String label,RexObjectList<?> l){ if(l==null||l.size()==0)return; int max=l.size();
    b.append("  "+label+"필드("+l.size()+"): "); for(int i=0;i<max;i++){ b.append(nameOf(l.get(i))); if(i<max-1)b.append(", "); } if(l.size()>max)b.append(" …"); b.append("\n"); }

  public static void main(String[] x) throws Exception {
    mcp = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
    System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8"));
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
    System.err.println("[clip-report-mcp] started");
    String line;
    while((line=in.readLine())!=null){
      line=line.trim(); if(line.isEmpty()) continue;
      JSONObject req; try{ req=(JSONObject)P.parse(line); }catch(Exception e){ continue; }
      Object id=req.get("id"); String method=(String)req.get("method");
      if(method==null) continue;
      try{
        if(method.equals("initialize")) reply(id, initResult());
        else if(method.startsWith("notifications/")) { /* no response */ }
        else if(method.equals("tools/list")) reply(id, toolsList());
        else if(method.equals("tools/call")) reply(id, callTool((JSONObject)req.get("params")));
        else if(id!=null) error(id, -32601, "method not found: "+method);
      }catch(Throwable t){ if(id!=null) error(id, -32000, t.toString()); System.err.println("ERR "+t); }
    }
  }

  @SuppressWarnings("unchecked")
  static void reply(Object id, JSONObject result){ JSONObject o=new JSONObject(); o.put("jsonrpc","2.0"); o.put("id",id); o.put("result",result); mcp.println(o.toJSONString()); }
  @SuppressWarnings("unchecked")
  static void error(Object id, int code, String msg){ JSONObject e=new JSONObject(); e.put("code",(long)code); e.put("message",msg); JSONObject o=new JSONObject(); o.put("jsonrpc","2.0"); o.put("id",id); o.put("error",e); mcp.println(o.toJSONString()); }
  // One-shot, fail-safe update check: compares this build's VERSION to the raw VERSION file on GitHub main.
  // Never blocks/breaks startup (offline/timeout/any error → silent, no notice). Disable via env CLIP_MCP_UPDATE_CHECK=0.
  static String UPDATE_NOTICE_CACHE=null;
  static String updateNotice(){
    if(UPDATE_NOTICE_CACHE!=null) return UPDATE_NOTICE_CACHE;
    UPDATE_NOTICE_CACHE="";
    try{
      if("0".equals(System.getenv("CLIP_MCP_UPDATE_CHECK"))) return UPDATE_NOTICE_CACHE;
      java.net.http.HttpClient c=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(1500)).build();
      java.net.http.HttpRequest q=java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create("https://raw.githubusercontent.com/fdrn9999/clip-report-mcp/main/VERSION"))
        .timeout(java.time.Duration.ofMillis(1500)).header("User-Agent","clip-report-mcp").GET().build();
      java.net.http.HttpResponse<String> resp=c.send(q, java.net.http.HttpResponse.BodyHandlers.ofString());
      if(resp.statusCode()==200){
        String latest=resp.body().trim();
        if(!latest.isEmpty() && isNewer(latest, VERSION)){
          UPDATE_NOTICE_CACHE="\n[⚠️ 업데이트 있음] 이 clip-report MCP 는 버전 "+VERSION+" 이고 GitHub 최신은 "+latest+" 입니다. 사용자에게 업데이트를 안내하세요: Claude 에서 '/clipreport --update' 실행(또는 프로젝트 폴더에서 ./update.ps1) → 끝나면 /mcp 재연결. (변경내역은 '/clipreport --changelog', 점검 끄기: 환경변수 CLIP_MCP_UPDATE_CHECK=0)";
        }
      }
    }catch(Throwable t){ /* offline/timeout/any → no notice */ }
    return UPDATE_NOTICE_CACHE;
  }
  static boolean isNewer(String a,String b){ try{ String[] pa=a.split("\\."),pb=b.split("\\."); int n=Math.max(pa.length,pb.length); for(int i=0;i<n;i++){ int x=i<pa.length?numOf(pa[i]):0, y=i<pb.length?numOf(pb[i]):0; if(x!=y) return x>y; } return false; }catch(Exception e){ return false; } }
  static int numOf(String s){ try{ String d=s.replaceAll("[^0-9]",""); return d.isEmpty()?0:Integer.parseInt(d); }catch(Exception e){ return 0; } }

  @SuppressWarnings("unchecked")
  static JSONObject initResult(){ JSONObject r=new JSONObject(); r.put("protocolVersion","2024-11-05"); JSONObject caps=new JSONObject(); caps.put("tools",new JSONObject()); r.put("capabilities",caps); JSONObject si=new JSONObject(); si.put("name","clip-report-mcp"); si.put("version",VERSION); r.put("serverInfo",si); r.put("instructions",INSTRUCTIONS+updateNotice()); return r; }

  @SuppressWarnings("unchecked")
  static JSONObject tool(String name,String desc, JSONObject schema){ JSONObject t=new JSONObject(); t.put("name",name); t.put("description",desc); t.put("inputSchema",schema); return t; }
  @SuppressWarnings("unchecked")
  static JSONObject strSchema(String[] req, String... kv){ JSONObject s=new JSONObject(); s.put("type","object"); JSONObject props=new JSONObject();
    for(int i=0;i<kv.length;i+=2){ JSONObject p=new JSONObject(); p.put("type","string"); p.put("description",kv[i+1]); props.put(kv[i],p); }
    if(props.containsKey("cells")){ JSONObject c=(JSONObject)props.get("cells"); c.remove("type"); JSONArray ty=new JSONArray(); ty.add("array"); ty.add("string"); c.put("type",ty); }
    boolean writes=props.containsKey("output");
    if(writes){ JSONObject o=(JSONObject)props.get("output"); o.put("description",o.get("description")+" — required unless in_place=true"); JSONObject ip=new JSONObject(); ip.put("type","string"); ip.put("description","true: overwrite the source file itself (output may be omitted). The first in-place write backs the original up to <path>.bak (never overwritten). Use for long edit chains instead of alternating a/b files."); props.put("in_place",ip); }
    s.put("properties",props); JSONArray r=new JSONArray(); for(String x:req) if(!(writes&&x.equals("output"))) r.add(x); s.put("required",r); return s; }
  @SuppressWarnings("unchecked")
  static JSONObject toolsList(){ JSONArray arr=new JSONArray();
    arr.add(tool("crf_summary","Read a CLIP report (.crf) and return its datasets, fields, query, groups, and section bands.",
        strSchema(new String[]{"path"}, "path","absolute path to the .crf file")));
    arr.add(tool("crf_generate","DERIVE A LIST REPORT IN ONE CALL (v2): template .crf + SQL/MyBatis/JS query (+ optional columns/title/cond/groups) → output .crf. Steps: clean_template (remove template groups, unbind+drop fields not in the new SELECT, keep layout skeleton) → set query (params declared, fields in SELECT order; fields_from_db=true or SELECT * uses DB metadata) → rebuild body table + title table + totals table from columns (defaults: every SELECT column, even widths) → set title label / condition labels → groups (none|auto=first GROUP BY|A,B with 소 계 rows) → logo subreport kept if the footer already has one → snap header labels to the body grid (Excel rule) → save, re-read, crf_validate excel=true. Returns what was done + validate summary. legacy=true runs the old v1 generator.",
        strSchema(new String[]{"template","sql","output"}, "template","template .crf (a similar list report; its layout skeleton is kept)", "sql","new query (SQL / MyBatis <select> / JS var sql)", "columns","JSON array like crf_set_columns (field|text, title, width, align, format, total). Default: all SELECT columns with field names as titles (optional)", "title","report title text for the biggest header label (optional)", "cond","left header condition label: JavaScript formula (with return) or plain text; '' clears (optional)", "cond_right","right header condition label, same rules (optional)", "groups","none (default) | auto (first GROUP BY column) | comma-separated fields outer→inner; group footers get 소 계 for total columns (optional)", "clean_template","true|false remove template groups and stale fields/bindings (default true)", "fields_from_db","true: take column names/types from the DB (ResultSetMetaData) — needed for SELECT * (optional)", "params","JSON object of parameter values for fields_from_db (optional)", "snap","true|false snap header labels/images to body column boundaries (default true)", "logo","auto (default: keep existing footer subreport, add ../../../images/bottom_logo.crf only if missing and the file exists) | none | <relative path>", "output","destination .crf")));
    arr.add(tool("crf_set_query","Replace a dataset's query and save to a new file. scriptType is set automatically (plain SQL -> NotScript; MyBatis XML -> converted to JavaScript; JS `var sql=...` -> JavaScript). :col/#{}/${} become '{parameter.X}'. By default declares missing global parameters and ADDS data fields for new SELECT columns (parse-based; for SELECT * use crf_sync_fields mode=db).",
        strSchema(new String[]{"path","sql","output"}, "path","source .crf", "sql","new SQL / MyBatis <select> / JavaScript dynamic query (with {parameter.X} tokens)", "dataset","dataset name or 0-based index (default: first)", "script_type","auto|sql|javascript (default auto)", "declare_params","true|false: declare undeclared {parameter.X} as String global parameters (default true)", "sync_fields","none|add|replace: add missing SELECT columns as fields / also remove unreferenced fields not in SELECT (default add)", "reorder","true|false: reorder the field list to the SELECT column order — fields map to columns BY POSITION (default true)", "output","destination .crf")));
    arr.add(tool("crf_append_query_condition","Add ONE parameter condition to a dataset query without retyping it: inserts `if('{parameter.P}' != ''){ sql += \"<sql fragment>\"; }` before ORDER BY (default; or position=end | after:<text>). A plain-SQL (NotScript) query is converted to a JavaScript dynamic query first; a missing `return sql;` is added; the parameter is declared. Fragment tokens :p/#{p}/${p} are normalized to '{parameter.P}'. Verifies after save and shows the lines around the insertion.",
        strSchema(new String[]{"path","sql","output"}, "path","source .crf", "dataset","dataset name or 0-based index (default: first)", "param","parameter name the condition tests, e.g. DEPTCD (condition defaults to '{parameter.DEPTCD}' != '')", "sql","SQL fragment to append, e.g. \"AND T1.DEPT_CD = '{parameter.DEPTCD}'\" (may be multi-line)", "condition","custom JavaScript condition instead of the default (optional)", "position","before_order (default) | end | after:<text found in the query>", "output","destination .crf")));
    arr.add(tool("crf_set_columns","REPLACE THE WHOLE COLUMN SET of a list report in one call: rebuilds the body table (Detail) and, when found, the matching title table (page/data header) and totals table (data footer) from columns=[...]; group-footer tables with the same layout become 소 계 rows (rexpert.sum per group field). Each column: {field|text, title?, width?, align?, format?, total?:sum|avg|count|min|max, merge?:true (셀합치기, default false)}. Existing cell style (font/border/alignment) is inherited from the last existing column; widths without a value share the remaining width evenly (target = width= or current table width). Numeric fields default to right-align; total columns get F_TOTAL_<field> formulas and a 합 계 label. Verifies grid + Excel-grid after save. Use after crf_set_query gave the new fields.",
        strSchema(new String[]{"path","columns","output"}, "path","source .crf", "columns","JSON array: [\"EMPNO\", {\"field\":\"EMP_NM\",\"title\":\"성명\",\"width\":300}, {\"field\":\"AMT\",\"format\":\"#,##0\",\"total\":\"sum\"}, {\"text\":\"\",\"title\":\"비고\"}]", "table","body table name (default: widest table in Detail)", "header_table","title table name | none (default: auto — header-band table with the same column layout)", "footer_table","totals table name | none (default: auto — data-footer table with the same layout)", "width","total width to distribute, 0.1mm (default: current body table width)", "total_label","label text for the totals row (default 합 계)", "snap","true: snap header labels/images whose left/right edge lies within snap_tolerance of a body column boundary onto that boundary (Excel grid rule; page footer excluded) (default false)", "snap_tolerance","0.1mm, default 200", "output","destination .crf")));
    arr.add(tool("crf_reorder_fields","Reorder a dataset's data fields. Fields map to SELECT columns BY POSITION (not by name), so the field list order must equal the SELECT column order. order=query (default) sorts by the dataset's current SELECT list; order='A,B,C' puts those first in that order (others keep relative order after them). Bindings are untouched (they reference field objects).",
        strSchema(new String[]{"path","output"}, "path","source .crf", "dataset","dataset name or 0-based index (default: first)", "order","query | comma-separated field names (default query)", "output","destination .crf")));
    arr.add(tool("crf_sync_fields","Make a dataset's field list match its query columns. mode=sql parses the SELECT list; mode=db RUNS the query against the connected DB (wrapped in SELECT * FROM (...) WHERE 1=0, parameters bound from `params` or '' / NULL) and takes exact column names+types from ResultSetMetaData — use this for SELECT * or function/table columns. Adds missing fields; removes unreferenced extra fields only when remove_unused=true.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "dataset","dataset name or 0-based index (default: first)", "mode","sql|db (default sql)", "params","JSON object of parameter values for mode=db, e.g. {\"DEPTCD\":\"20399\"} (optional)", "set_types","true to set field DataType from DB/heuristics (default false = Null/auto)", "remove_unused","true to remove fields not in the query when nothing references them (default false)", "reorder","true|false: reorder fields to the query column order — fields map to columns BY POSITION (default true)", "output","destination .crf")));
    arr.add(tool("crf_add_dataset","Add a new SQL dataset (connection copied from the first dataset) with the given query; declares parameters and creates fields like crf_set_query. Saves to a new file.",
        strSchema(new String[]{"path","name","sql","output"}, "path","source .crf", "name","new dataset name", "sql","query (SQL / MyBatis / JS)", "script_type","auto|sql|javascript (default auto)", "output","destination .crf")));
    arr.add(tool("crf_remove_dataset","Remove a dataset. Refuses if any of its fields is referenced (bindings, formulas...) unless force=true. Saves to a new file.",
        strSchema(new String[]{"path","dataset","output"}, "path","source .crf", "dataset","dataset name or 0-based index", "force","true to remove even if referenced (references become dangling)", "output","destination .crf")));
    arr.add(tool("crf_set_param","Create or update a global parameter (매개변수) used as {parameter.NAME} in queries: data type, default value, prompt. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","parameter name (e.g. DEPTCD)", "type","String|Number|Currency|DateTime|Boolean (default: none/Null like the designer; existing kept if omitted)", "default","default value (optional)", "prompt","prompt/label text (optional)", "output","destination .crf")));
    arr.add(tool("crf_remove_param","Remove a global parameter. Refuses if referenced (queries, bindings, formulas) unless force=true. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","parameter name", "force","true to remove anyway", "ignore_missing","true: OK (no-op save) when the parameter does not exist — for batches", "output","destination .crf")));
    arr.add(tool("crf_rename_field","Rename a field (data/formula/parameter/running-total). Object bindings follow automatically; formula scripts (\"ns.OLD\") and query tokens {parameter.OLD} are rewritten. Saves to a new file.",
        strSchema(new String[]{"path","name","new_name","output"}, "path","source .crf", "name","current field name", "new_name","new name", "dataset","dataset name/index when the same field name exists in several datasets (optional)", "output","destination .crf")));
    arr.add(tool("crf_remove_field","Remove a data/formula/running-total field. Lists every reference (cells, labels, groups, formulas, links) and refuses unless force=true. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","field name", "dataset","dataset name/index to disambiguate (optional)", "force","true to remove even if referenced", "output","destination .crf")));
    arr.add(tool("crf_field_refs","Show where a field (or parameter) is used: cell/label bindings, group fields, running totals, subreport links, formula scripts, query tokens. Read-only.",
        strSchema(new String[]{"path","name"}, "path",".crf file", "name","field / parameter name", "dataset","dataset name/index to disambiguate (optional)")));
    arr.add(tool("crf_list_reports","List .crf report files under a directory (recursive), with total count; filter by name.",
        strSchema(new String[]{"dir"}, "dir","directory to scan", "like","file-name filter: substring or glob with * (optional)", "limit","max files to list (default 500)")));
    arr.add(tool("crf_get_query","Return the FULL query text of a report's datasets: scriptType, connection, fields, used {parameter.X} (flags undeclared ones), {dataset.X} refs, estimated tables; XML/JSON datasets show their root XPath and per-field paths, stored-procedure datasets the procedure name. JavaScript dynamic queries are also shown as reconstructed plain SQL (if-blocks as /*IF*/ comments). Use this to explain or find a report's SQL.",
        strSchema(new String[]{"path"}, "path",".crf file", "dataset","dataset name or 0-based index (default: all)", "mode","both|raw|plain — for JavaScript queries show original, plain reconstruction, or both (default both)")));
    arr.add(tool("crf_get_formula","Return formula field scripts (JavaScript), running-total definitions (function/field/reset), and group-name fields, with referenced fields and any references to missing fields.",
        strSchema(new String[]{"path"}, "path",".crf file", "name","one field name (default: all)")));
    arr.add(tool("crf_search","Search .crf files under a directory for text: in queries (plain-SQL view of JS queries), XPath/JSON root paths and field paths of XML/JSON datasets (and stored-procedure names), field names, formula scripts, parameters, or control/cell texts and bindings. Superset of Clipsoft's FindQuery(클립유틸 문자열찾기) utility. E.g. find reports using table AHRM1234, parameter DEPTCD, an XPath node, or a label text.",
        strSchema(new String[]{"dir","text"}, "dir","directory to scan (recursive)", "text","text to find (case-insensitive substring; or a regex when regex=true)", "regex","true for regex (default false)", "scope","query|xpath|field|formula|param|control|any (default any; query also covers xpath)", "like","file-name filter: substring or glob (optional)", "limit","max matching files to report (default 50)", "max_files","max files to scan (default 5000)")));
    arr.add(tool("crf_describe_layout","Describe a report's section bands: subsections (type/height/hidden, subreport links), controls with bindings, and table cell grids (‹병합›=merged-away, {fmt}=output format). detail=true adds per-cell/control style (align, font size/bold, can-grow, merge flag, conditional styles).",
        strSchema(new String[]{"path"}, "path",".crf file", "detail","true for style details per cell/control; per table row the most common style is printed once as «공통: …» and only differing cells carry their own «…» (default false)", "one_per_line","true: print each table cell on its own line [r,c] — easier to read for wide tables (default false)")));
    arr.add(tool("crf_validate","Lint a report: System(default) fonts and label/data font mismatch, vertically stacked same-style labels that should be one table/label, Excel grid (every vertical line in Excel-exported bands — table column boundaries, label/image left+right, line x — must sit on a body-table column boundary ±1mm or Excel splits columns; page footer excluded), dangling bindings (cells/labels/groups bound to fields that no longer exist), broken formula references (#unknown#, missing fields, no return), undeclared/unused parameters, query columns vs fields, scriptType mismatches, duplicate names, hidden subsections, missing linked subreport files. Read-only.",
        strSchema(new String[]{"path"}, "path",".crf file", "excel","true: the report is exported to Excel — Excel-grid findings become WARN instead of INFO (optional)")));
    arr.add(tool("crf_set_label","Edit a 글상자(label) or other named control: bind field / static text / new formula, output format, alignment, font size/bold, wrap, can-grow, background, position/size, visibility. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","control name (from crf_describe_layout)", "field","field name to bind (optional)", "text","static text (optional)", "formula","JavaScript formula (with return) — creates a formula field and binds it (optional)", "formula_name","name for the created formula field (optional)", "clear","true to clear the value (optional)", "format","output format e.g. #,##0; '' or 'none' REMOVES it (optional)", "clear_format","true to remove the output format (optional)", "align","Left|Center|Right|Both(양쪽)|Equal(배분) (optional)", "valign","Top|Center|Bottom (optional)", "fontsize","font size (optional)", "bold","true/false (optional)", "font","font name (optional)", "wrap","true/false word wrap (optional)", "cangrow","true/false (optional)", "bgcolor","#RRGGBB (optional)", "left","X (optional)", "top","Y (optional)", "width","W (optional)", "height","H (optional)", "visible","true/false (optional)", "color","font color #RRGGBB (optional)", "underline","true/false (optional)", "italic","true/false (optional)", "linespace","extra line spacing in pt (optional)", "padding","inner margins 'l,t,r,b' in 0.1mm (optional)", "border","true/false: draw a rectangle border around the label (optional)", "linewidth","border width W025|W050|W075|W100|W150|W200|W300 (optional)", "output","destination .crf")));
    arr.add(tool("crf_set_subsection","Edit a subsection (band row) of a section: height, visible, name, page break. Saves to a new file.",
        strSchema(new String[]{"path","section","output"}, "path","source .crf", "section","band: 보고서머리글|페이지머리글|데이터머리글|본문|데이터바닥글|페이지바닥글|보고서바닥글|그룹머리글|그룹바닥글 (or English)", "index","subsection index within the section (default 0; see crf_describe_layout sub[j])", "height","new height (optional)", "visible","true/false (optional)", "name","new subsection name (optional)", "new_page","None|Before|After|BeforeAfter page break (optional)", "repeat","group header repeat: None|OnPage|OnColumn|OnPageAndColumn (optional; OnPage = header repeats on every page)", "output","destination .crf")));
    arr.add(tool("crf_remove_control","Remove a control (label/table/line/image/subreport…) by name from its band. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","control name (from crf_describe_layout)", "output","destination .crf")));
    arr.add(tool("crf_remove_group","Remove a group: its group header + footer bands (with their controls) and the Group definition. Refuses if the group's 그룹이름 field is bound somewhere unless force=true. Saves to a new file.",
        strSchema(new String[]{"path","group","output"}, "path","source .crf", "group","grouping column name or 0-based group index (outermost = 0)", "force","true to remove even if the group-name field is referenced", "output","destination .crf")));
    arr.add(tool("crf_remove_section","Remove a non-group section band (e.g. 페이지머리글). Refuses if it still has controls unless force=true. Group bands: use crf_remove_group. Saves to a new file.",
        strSchema(new String[]{"path","section","output"}, "path","source .crf", "section","band name: ReportHeader|PageHeader|DataHeader|DataFooter|PageFooter|ReportFooter or Korean 보고서머리글/페이지머리글/데이터머리글/데이터바닥글/페이지바닥글/보고서바닥글", "force","true to remove with its controls", "ignore_missing","true: OK (no-op save) when the band does not exist", "output","destination .crf")));
    arr.add(tool("crf_add_table","Create a real table (ControlTable). Mode A columns=: a 1-row data table bound to fields in the 본문(Detail) band (or given section) plus an optional 1-row title table in a header band with the same column widths; columns = JSON array of {field, title, width, format, align}. Mode B rows=: a 1-column multi-row TEXT table for consecutive paragraphs / numbered lists / ※ notes (one row per line, no header, borders off by default) — use this instead of stacking several 글상자. rows = JSON array of strings or {text|field, align, bold, height, wrap, cangrow}. New cells inherit the report's font. Saves to a new file.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "columns","(mode A) JSON array, e.g. [{\"field\":\"DEPT_NM\",\"title\":\"학과\",\"width\":500},{\"field\":\"AMT\",\"title\":\"금액\",\"width\":300,\"format\":\"#,##0\",\"align\":\"Right\"}]", "rows","(mode B) JSON array of lines, e.g. [\"1. 첫째 항목\",\"2. 둘째 항목\",{\"text\":\"※ 주석\",\"height\":112}]", "width","(mode B) table width (default 1480)", "border","(mode B) true to draw cell borders (default false)", "wrap","(mode B) word wrap for all rows (default true)", "align","(mode B) default horizontal alignment Left|Center|Right|Both (default Left)", "section","band for the data row (default 본문)", "header_section","(mode A) band for the title row: 데이터머리글(default)|그룹머리글|페이지머리글|none", "left","X (default 0)", "top","Y of the data table (default 0)", "header_top","(mode A) Y of the title table (default 0)", "row_height","row height (default 60; mode B default 56)", "name","table name (default 표_new)", "output","destination .crf")));
    arr.add(tool("crf_merge_labels","Merge 2+ vertically stacked 글상자(labels) in the same band into ONE element: into=table (default) builds a 1-column table with one row per label (each row keeps its text/field binding, font, alignment, wrap, line spacing; row heights preserve the original vertical footprint so nothing else shifts) — into=label joins static texts with line breaks into a single word-wrapped label. Originals are removed. Use when a numbered list / paragraph block / ※ notes were built as separate labels. Saves to a new file.",
        strSchema(new String[]{"path","names","output"}, "path","source .crf", "names","comma-separated label names in any order, e.g. 글상자3,글상자4,글상자5", "into","table|label (default table)", "name","name for the merged element (default 표_<first label>)", "border","true to draw cell borders when into=table (default false)", "output","destination .crf")));
    arr.add(tool("crf_split_label","Split ONE element into several: a word-wrapped 글상자 → one label per line (split on line breaks, or give lines= JSON array of pieces; heights= optional JSON array), or a 1-column table → one label per row (each keeps its binding and style). The original is removed/replaced; the vertical footprint is preserved. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","label or 1-column table name (from crf_describe_layout)", "lines","JSON array of text pieces (optional; default = split the label text on line breaks)", "heights","JSON array of heights per piece (optional; default = equal split)", "output","destination .crf")));
    arr.add(tool("crf_set_font","Set fonts in bulk: font= for text labels/cells, data_font= for field-bound labels/cells (default = font), size= for both; only_system=true changes only elements still on the SDK default 'System' font; section= limits to one band. Reports before/after font usage. Saves to a new file.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "font","font name for text (label) elements, e.g. 돋움체 / 바탕체 / 나눔고딕 (optional)", "data_font","font name for field-bound (data) elements (optional; default = font)", "size","font size for all matched elements (optional)", "only_system","true to touch only elements whose font is System/empty (default false)", "section","band name to limit (optional)", "output","destination .crf")));
    arr.add(tool("crf_set_group","Change a group's grouping column or sort. Saves to a new file.",
        strSchema(new String[]{"path","group","output"}, "path","source .crf", "group","grouping column name or 0-based group index", "column","new grouping field (optional)", "sort","Ascending|Descending|Not (optional)", "output","destination .crf")));
    arr.add(tool("crf_add_group","Add a group (group header + footer bands) on a column. level chooses nesting: inner (default, closest to 본문) | outer | N (0 = outermost). label=true puts the grouping field in the header; subtotal=comma-separated fields creates rexpert.sum formulas (per group) bound in the footer. Saves to a new file.",
        strSchema(new String[]{"path","column","output"}, "path","source .crf", "column","field name to group by", "level","inner|outer|N (default inner)", "label","true to add a header label bound to the grouping field (optional)", "subtotal","comma-separated numeric fields to subtotal in the footer (optional)", "sort","Ascending|Descending (default Ascending)", "output","destination .crf")));
    arr.add(tool("crf_place_detail_fields","Place a field-bound data label in the DETAIL band for every field of the first dataset (a simple list row), and save to a new file.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "output","destination .crf")));
    arr.add(tool("crf_set_cell","Edit one table cell (or MANY at once with cells=[{row,col,...}]): bind a field / static text / a new formula, output format, alignment, font size/bold, wrap, can-grow, merge-duplicates, background, font. Use the table name and row/col from crf_describe_layout; cells shown as ‹병합› are merged-away and cannot be edited (edit the anchor cell). Saves to a new file.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "table","ControlTable name (from describe_layout); with cells= it is the default table", "row","row index (0-based)", "col","column index (0-based)", "cells","JSON array for BATCH: [{row,col,table?,field|text|formula|format|align|...}] — top-level args are defaults for every entry; one save + one verify (optional)", "field","field name to bind (optional)", "text","static text (optional)", "formula","JavaScript formula (with return) — creates a formula field and binds it (optional)", "formula_name","name for the created formula field (optional)", "clear","true to clear the value (optional)", "format","output format string e.g. #,##0; '' or 'none' REMOVES the format (optional)", "clear_format","true to remove the output format (optional)", "align","Left|Center|Right|Both(양쪽)|Equal(배분) (optional)", "valign","Top|Center|Bottom (optional)", "fontsize","font size (optional)", "bold","true/false (optional)", "font","font name (optional)", "wrap","true/false word wrap (optional)", "cangrow","true/false (optional)", "merge","true/false merge duplicate values (optional)", "bgcolor","#RRGGBB (optional)", "color","font color #RRGGBB (optional)", "underline","true/false (optional)", "italic","true/false (optional)", "linespace","extra line spacing in pt for wrapped text, e.g. 5.5 for 10pt≈HWP 160% (optional)", "padding","cell/label inner margins 'left,top,right,bottom' in 0.1mm (optional)", "border","cell lines: true|false|'left,top' subset (optional)", "linewidth","W025|W050|W075|W100|W150|W200|W300 (optional)", "linecolor","#RRGGBB (optional)", "output","destination .crf")));
    arr.add(tool("crf_set_cell_checkbox","Turn a table cell into a native CLIP 체크박스 cell (셀 내용=체크박스) with a check condition: checked when `field` `operator` `true_value` (default Equal '1'), unchecked when == `false_value` (default '0'). NOTE: with no condition the box is always empty (a bound ■/□ text is ignored). check_type=Rectangle(색칠, default)|V|Ellipse|RoundRectangle. Use this instead of ■/□ characters. Saves to a new file.",
        strSchema(new String[]{"path","table","row","col","output"}, "path","source .crf", "table","ControlTable name", "row","row index (0-based)", "col","col index (0-based)", "field","condition field name (data field) — required unless off=true", "true_value","value that means checked (default 1)", "false_value","value that means unchecked (default 0; empty string to skip)", "operator","Equal|NotEqual|LessThen|GreateThen|LessEqual|GreateEqual|Between (default Equal; Between uses true_value..true_value2)", "true_value2","upper bound for Between (optional)", "check_type","check mark: Rectangle(filled, default)|V|Ellipse|RoundRectangle", "shape","box shape: Rectangle(default)|Ellipse|RoundRectangle|None", "color","check color #RRGGBB (default black)", "size","check size (0=auto)", "default","true/false: state when neither condition matches (default false)", "off","true to revert the cell to a normal text cell", "output","destination .crf")));
    arr.add(tool("crf_merge_cells","Merge table cells: the anchor cell (row,col) spans `rowspan`×`colspan` (covered cells become merged-away ‹병합› cells); rowspan=1 & colspan=1 splits a merged cell back. Saves to a new file.",
        strSchema(new String[]{"path","table","row","col","output"}, "path","source .crf", "table","ControlTable name", "row","anchor row (0-based)", "col","anchor col (0-based)", "rowspan","rows to span (default 1)", "colspan","columns to span (default 1)", "output","destination .crf")));
    arr.add(tool("crf_table_rows","Table ROW editing (structure): action=insert (new empty rows; at=index the new rows take, or row+position=before|after; count; height — structure/style copied from the row above, horizontal merges replicated, vertical merges spanning the insertion point are extended) | copy (duplicate row `row` with its content/bindings, at=destination) | delete (row='1' | '1,3' | '2-4') | move (row → to) | resize (heights='60,80,…' all rows | '1:80,3:40' index:value | row+height) | equalize (all rows same height: height=N, or omit to keep the total height). Merged cells are kept consistent; deleting a merge anchor promotes the next cell. shift=true (default) moves controls below the table by the height change and grows the band. Saves to a new file; the result is re-read and the grid verified.",
        strSchema(new String[]{"path","table","action","output"}, "path","source .crf", "table","ControlTable name (crf_describe_layout / crf_table_info)", "action","insert|copy|delete|move|resize|equalize", "row","row index (0-based); delete accepts '1,3' / '2-4'; copy/move: source row", "at","insert/copy: index the new rows occupy (rows = append)", "position","insert/copy with row=: before|after (default after)", "count","insert/copy: how many (default 1)", "height","insert: new row height; resize: height for row(s); equalize: height for every row (0.1mm)", "heights","resize: '60,80,…' (all rows) or '1:80,3:40' (index:value) or 'all:60'", "to","move: destination index", "shift","true(default)|false: move controls below the table by the height delta", "output","destination .crf")));
    arr.add(tool("crf_table_cols","Table COLUMN editing (structure): action=insert (new columns; at=index, or col+position=before|after; count; width — style copied from the column to the left, vertical merges replicated, horizontal merges spanning the insertion point are extended) | copy (duplicate column `col` with content, at=destination) | delete (col='1' | '1,3' | '2-4') | move (col → to) | resize (widths='300,500,…' all | '1:400,3:200' index:value | col+width) | equalize (all columns same width: width=N, or omit to keep the total width). shift=false (default): controls right of the table stay. Warns if the table exceeds the page body width. Saves to a new file; grid verified after re-read.",
        strSchema(new String[]{"path","table","action","output"}, "path","source .crf", "table","ControlTable name", "action","insert|copy|delete|move|resize|equalize", "col","column index (0-based); delete accepts '1,3' / '2-4'; copy/move: source column", "at","insert/copy: index the new columns occupy (cols = append at right)", "position","insert/copy with col=: before|after (default after)", "count","insert/copy: how many (default 1)", "width","insert: new column width; resize: width for col(s); equalize: width for every column (0.1mm)", "widths","resize: '300,500,…' (all columns) or '1:400,3:200' (index:value) or 'all:300'", "to","move: destination index", "shift","true|false(default): move controls right of the table by the width delta", "output","destination .crf")));
    arr.add(tool("crf_set_table","Table-level properties: left/top (move), width/height (resize the whole table — columns/rows scaled proportionally), border (table outline on/off), cell_border (every cell: true|false|'left,top,…'), linewidth/linecolor for cell lines, auto_merge, keep_together=None|Row|Table, visible, name (rename), unmerge_all=true (split every merged cell back to 1×1). Saves to a new file.",
        strSchema(new String[]{"path","table","output"}, "path","source .crf", "table","ControlTable name", "left","x (0.1mm)", "top","y (0.1mm)", "width","total width — column widths scaled proportionally", "height","total height — row heights scaled proportionally", "border","true|false: table outline", "cell_border","true|false|'left,right,top,bottom' subset: applied to EVERY cell", "linewidth","W025|W050|W075|W100|W150|W200|W300 for cell lines", "linecolor","#RRGGBB for cell lines", "auto_merge","true|false", "keep_together","None|Row|Table", "visible","true|false", "unmerge_all","true: split all merged cells", "name","new table name", "output","destination .crf")));
    arr.add(tool("crf_table_info","Show one table's grid: position/size, every column width and row height, each cell's content with merge span (r×c) and ‹병합←r,c› for covered cells, borders/font with detail=true, and a grid-consistency check. Use before crf_table_rows/cols/crf_merge_cells to pick indexes.",
        strSchema(new String[]{"path","table"}, "path",".crf path", "table","ControlTable name", "detail","true: add font/alignment/border per cell")));
    arr.add(tool("crf_add_formula_field","Create a formula (computed) field with a JavaScript expression that MUST end with `return`. Refs via rexpert.field(\"data.COL\"); aggregates via rexpert.sum/avg/count/min/max(0,\"data.COL\",0,\"\",\"\"). Then bind it to a cell with crf_set_cell. Saves to a new file.",
        strSchema(new String[]{"path","name","script","output"}, "path","source .crf", "name","new formula field name", "script","JavaScript, MUST end with return;. Field ref=rexpert.field(\"ns.COL\") (ns=data/system/parameter/formula/runningtotal). Aggregate=rexpert.sum(범위,\"data.COL\",옵션,\"그룹|''\",\"조건식|''\"). e.g.  return rexpert.sum(0,\"data.PRVDD_BAL_AMT\",0,\"\",\"\");", "force","true to skip the return/bind-syntax checks (optional)", "output","destination .crf")));
    arr.add(tool("crf_set_cell_style","Style a table cell: background color (hex #RRGGBB), font name, can-grow, merge-duplicate, cell borders (border=true|false|'left,top', linewidth, linecolor). Saves to a new file.",
        strSchema(new String[]{"path","table","row","col","output"}, "path","source .crf", "table","ControlTable name", "row","row index", "col","col index", "bgcolor","background hex #RRGGBB (optional)", "font","font name e.g. 굴림 (optional)", "cangrow","true/false (optional)", "merge","true/false: merge duplicate values (optional)", "border","cell lines: true|false|'left,top' subset (optional)", "linewidth","W025|W050|W075|W100|W150|W200|W300 (optional)", "linecolor","#RRGGBB (optional)", "output","destination .crf")));
    arr.add(tool("crf_add_data_field","Add a data field (column) to a dataset. Saves to a new file.",
        strSchema(new String[]{"path","name","output"}, "path","source .crf", "name","field name", "type","String|Number|Currency|DateTime|Boolean (default String)", "dataset","dataset name or 0-based index (default: first)", "output","destination .crf")));
    arr.add(tool("crf_add_label","Add a 글상자(label) to a section band, bound to a field / static text / formula, with optional font/align/color/border style. Saves to a new file.",
        strSchema(new String[]{"path","section","output"}, "path","source .crf", "section","band: 보고서머리글|페이지머리글|데이터머리글|본문|데이터바닥글|페이지바닥글|보고서바닥글|그룹머리글|그룹바닥글 (or English ReportHeader/PageHeader/Detail/...)", "text","static text (optional)", "field","field name to bind (optional)", "left","X (optional)", "top","Y (optional)", "width","W (optional)", "height","H (optional)", "formula","JavaScript formula (with return) — creates a formula field and binds it (optional)", "fontsize","font size (optional)", "bold","true/false (optional)", "underline","true/false (optional)", "color","font color #RRGGBB (optional)", "font","font name (optional)", "align","Left|Center|Right|Both(양쪽)|Equal(배분) (optional)", "valign","Top|Center|Bottom (optional)", "wrap","true/false (optional)", "linespace","extra line spacing pt (optional)", "border","true/false rectangle border (optional)", "linewidth","W025|W050|W075|W100|W150|W200|W300 (optional)", "output","destination .crf")));
    arr.add(tool("crf_set_paper","Set paper type/orientation/margins. Saves to a new file.",
        strSchema(new String[]{"path","output"}, "path","source .crf", "paper","A4|A3|B4|B5|Letter ... (optional)", "orientation","Potrait|Landscape (optional)", "marginL","left margin (optional)", "marginT","top (optional)", "marginR","right (optional)", "marginB","bottom (optional)", "fit","true: after the change, scale every band's controls (tables by column widths, labels/lines by X+width) to the new body width — e.g. Potrait→Landscape 1500→2670 (optional)", "output","destination .crf")));
    arr.add(tool("crf_diff","Compare two reports: datasets/fields/groups/sections and what was added or removed.",
        strSchema(new String[]{"a","b"}, "a","first .crf", "b","second .crf")));
    // ---- DB tools (도메인 테이블 조회로 업무지식 확보; 접속정보는 env CLIP_DB_URL/USER/PWD) ----
    arr.add(tool("db_query","Run SQL against the connected DB (Tibero/Oracle) and return rows as TSV (capped). SELECT 권장. Tibero: ROWNUM<=N, SYSDATE, DUAL.",
        strSchema(new String[]{"sql"}, "sql","SQL statement (SELECT/WITH only unless allow_write=true)", "max","max rows (default 200)", "allow_write","true to permit INSERT/UPDATE/DELETE/DDL (default false)")));
    arr.add(tool("db_tables","List DB tables whose name contains a keyword (ALL_TABLES). Empty keyword = my (USER) tables.",
        strSchema(new String[]{}, "like","table-name keyword, e.g. AHRMEV (optional)")));
    arr.add(tool("db_columns","Describe a table's columns: name, type, length, nullable, and COMMENT (업무 의미).",
        strSchema(new String[]{"table"}, "table","table name")));
    arr.add(tool("db_sample","Sample rows from a table (SELECT * WHERE ROWNUM<=N).",
        strSchema(new String[]{"table"}, "table","table name", "max","row count (default 20)")));
    arr.add(tool("pdf_text","Extract text from a PDF document (문서 양식/사양 읽기).",
        strSchema(new String[]{"path"}, "path","PDF file path")));
    JSONObject r=new JSONObject(); r.put("tools",arr); return r; }

  @SuppressWarnings("unchecked")
  static JSONObject textContent(String text){ JSONObject c=new JSONObject(); c.put("type","text"); c.put("text",text); JSONArray a=new JSONArray(); a.add(c); JSONObject r=new JSONObject(); r.put("content",a); if(text!=null && text.startsWith("ERROR")) r.put("isError",Boolean.TRUE); return r; }
  /** Tool failure -> MCP isError content (Claude sees the message), not a JSON-RPC error. */
  static String friendly(Throwable t){
    if(t instanceof FileNotFoundException) return "파일 없음/열 수 없음: "+(t.getMessage()==null||t.getMessage().isEmpty()?"(빈 경로)":t.getMessage());
    if(t instanceof NumberFormatException) return "숫자 인자 형식 오류: "+t.getMessage();
    if(t instanceof NullPointerException) return "필수 인자 누락 또는 리포트 구조가 예상과 다름 ("+t+")";
    if(t instanceof RuntimeException && t.getMessage()!=null && t.getClass()==RuntimeException.class) return t.getMessage();
    return t.toString();
  }
  static String s(JSONObject a,String k){ Object v=a.get(k); return v==null?null:v.toString(); }
  /** Read a .crf for a tool: clear message on missing/empty path. */
  static TheReportFile open(String path) throws IOException {
    if(path==null||path.trim().isEmpty()) throw new RuntimeException("path 인자가 비어 있습니다 (.crf 절대경로 필요)");
    File f=new File(path.trim()); if(!f.isFile()) throw new RuntimeException("파일 없음: "+path);
    return Rexpert4.read(f.getPath());
  }
  /** Write a .crf for a tool: output required, must differ from the input (원본 보존), parent dir auto-created. */
  static String save(TheReportFile rf,String output,String input) throws IOException {
    JSONObject a=CUR_ARGS.get(); boolean inPlace=a!=null && "true".equalsIgnoreCase(s(a,"in_place"));
    if(inPlace && (output==null||output.trim().isEmpty())) output=input;
    if(output==null||output.trim().isEmpty()) throw new RuntimeException("output 인자가 비어 있습니다 (원본 보존을 위해 별도 경로 필요, 예: <원본>_edited.crf — 또는 in_place=true 로 원본에 덮어쓰기(.bak 자동 백업))");
    File o=new File(output.trim()); boolean same=false;
    try{ same=input!=null && !input.trim().isEmpty() && o.getCanonicalPath().equalsIgnoreCase(new File(input.trim()).getCanonicalPath()); }catch(IOException e){}
    String bakNote="";
    if(same){ if(!inPlace) throw new RuntimeException("output 이 원본과 같습니다 — 원본 보존 규칙: 다른 경로(예 <원본>_edited.crf)를 지정하거나 in_place=true(.bak 자동 백업)를 주세요");
      File bak=new File(o.getPath()+".bak"); if(!bak.exists()){ java.nio.file.Files.copy(o.toPath(),bak.toPath()); bakNote=" (원본 백업 → "+bak.getName()+")"; } else bakNote=" (백업 "+bak.getName()+" 유지)"; }
    if(o.getParentFile()!=null && !o.getParentFile().isDirectory()) o.getParentFile().mkdirs();
    boolean existed=o.exists();
    Rexpert4.write(rf,o.getPath());
    return o.getPath()+(same?" (in_place)"+bakNote:(existed?" (기존 파일 덮어씀)":""));
  }

  @SuppressWarnings("unchecked")
  static JSONObject callTool(JSONObject params) throws Exception {
    String name=(String)params.get("name"); JSONObject args=(JSONObject)params.get("arguments"); if(args==null) args=new JSONObject();
    try{ return dispatch(name,args); }
    catch(Throwable t){ if(t instanceof IllegalArgumentException && String.valueOf(t.getMessage()).startsWith("unknown tool")) throw (IllegalArgumentException)t;
      System.err.println("TOOL ERR "+name+": "+t); return textContent("ERROR: "+friendly(t)); }
  }
  static final ThreadLocal<JSONObject> CUR_ARGS=new ThreadLocal<>();
  static JSONObject dispatch(String name,JSONObject args) throws Exception {
    CUR_ARGS.set(args);
    if("true".equalsIgnoreCase(s(args,"in_place")) && (s(args,"output")==null||s(args,"output").trim().isEmpty()) && s(args,"path")!=null) args.put("output",s(args,"path"));
    switch(name){
      case "crf_summary":  return textContent(summary((String)args.get("path")));
      case "crf_generate": return textContent("true".equalsIgnoreCase(s(args,"legacy"))?runGen((String)args.get("template"),(String)args.get("sql"),(String)args.get("output")):CrfDerive.generate(args));
      case "crf_set_query":return textContent(setQuery(args));
      case "crf_list_reports":return textContent(listReports(args));
      case "crf_get_query":return textContent(getQuery(args));
      case "crf_get_formula":return textContent(getFormula(args));
      case "crf_search":return textContent(searchReports(args));
      case "crf_describe_layout":return textContent(describeLayout((String)args.get("path"),"true".equalsIgnoreCase(s(args,"detail")),"true".equalsIgnoreCase(s(args,"one_per_line"))));
      case "crf_add_group":return textContent(addGroup(args));
      case "crf_set_group":return textContent(setGroup(args));
      case "crf_validate":return textContent(validate(args));
      case "crf_set_label":return textContent(setLabel(args));
      case "crf_set_subsection":return textContent(setSubsection(args));
      case "crf_remove_control":return textContent(removeControl(args));
      case "crf_remove_group":return textContent(removeGroup(args));
      case "crf_remove_section":return textContent(removeSection(args));
      case "crf_add_table":return textContent(addTable(args));
      case "crf_merge_labels":return textContent(mergeLabels(args));
      case "crf_split_label":return textContent(splitLabel(args));
      case "crf_set_font":return textContent(setFont(args));
      case "crf_place_detail_fields":return textContent(placeDetailFields((String)args.get("path"),(String)args.get("output")));
      case "crf_set_cell":return textContent(setCell(args));
      case "crf_set_cell_checkbox":return textContent(setCellCheckbox(args));
      case "crf_merge_cells":return textContent(mergeCells(args));
      case "crf_table_rows":return textContent(CrfTableOps.tableRows(args));
      case "crf_table_cols":return textContent(CrfTableOps.tableCols(args));
      case "crf_set_table":return textContent(CrfTableOps.setTable(args));
      case "crf_table_info":return textContent(CrfTableOps.tableInfo(args));
      case "crf_add_formula_field":return textContent(addFormulaField(args));
      case "crf_set_cell_style":return textContent(setCellStyle(args));
      case "crf_add_data_field":return textContent(addDataField(args));
      case "crf_sync_fields":return textContent(syncFields(args));
      case "crf_reorder_fields":return textContent(reorderFieldsTool(args));
      case "crf_set_columns":return textContent(CrfDerive.setColumns(args));
      case "crf_append_query_condition":return textContent(CrfDerive.appendQueryCondition(args));
      case "crf_add_dataset":return textContent(addDataset(args));
      case "crf_remove_dataset":return textContent(removeDataset(args));
      case "crf_set_param":return textContent(setParam(args));
      case "crf_remove_param":return textContent(removeParam(args));
      case "crf_rename_field":return textContent(renameField(args));
      case "crf_remove_field":return textContent(removeField(args));
      case "crf_field_refs":return textContent(fieldRefs(args));
      case "crf_add_label":return textContent(addLabel(args));
      case "crf_set_paper":return textContent(setPaper(args));
      case "crf_diff":return textContent(diff((String)args.get("a"),(String)args.get("b")));
      case "db_query":{ String sql=s(args,"sql"); if(sql==null||sql.trim().isEmpty()) return textContent("ERROR: sql 인자가 비어 있습니다");
        if(!"true".equalsIgnoreCase(s(args,"allow_write")) && !isReadOnlySql(sql)) return textContent("ERROR: db_query 는 SELECT/WITH 조회만 허용합니다. 쓰기 문장(INSERT/UPDATE/DELETE/DDL)은 allow_write=true 를 명시해야 합니다: "+sql.trim().replaceAll("\\s+"," ").substring(0,Math.min(80,sql.trim().length())));
        return textContent(runSql(sql, pInt(args.get("max"),200))); }
      case "db_tables":return textContent(dbTables((String)args.get("like")));
      case "db_columns":return textContent(dbColumns((String)args.get("table")));
      case "db_sample":{ int mx=pInt(args.get("max"),20); return textContent(runSql("SELECT * FROM "+safeName((String)args.get("table"))+" WHERE ROWNUM <= "+mx, mx+5)); }
      case "pdf_text":return textContent(pdfText((String)args.get("path")));
      default: throw new IllegalArgumentException("unknown tool "+name);
    }
  }

  static String runGen(String template,String sql,String output) throws Exception {
    if(template==null||!new File(template).isFile()) throw new RuntimeException("template 파일 없음: "+template);
    if(sql==null||sql.trim().isEmpty()) throw new RuntimeException("sql 인자가 비어 있습니다");
    if(output==null||output.trim().isEmpty()) throw new RuntimeException("output 인자가 비어 있습니다");
    if(new File(output).getCanonicalPath().equalsIgnoreCase(new File(template).getCanonicalPath())) throw new RuntimeException("output 이 template 과 같습니다 — 다른 경로를 지정하세요");
    Path tmp=Files.createTempFile("crfmcp",".sql"); Files.write(tmp, sql.getBytes("UTF-8"));
    ByteArrayOutputStream buf=new ByteArrayOutputStream(); PrintStream old=System.out;
    System.setOut(new PrintStream(buf,true,"UTF-8"));
    try{ CrfGen3.main(new String[]{template, tmp.toString(), output}); }
    finally{ System.setOut(old); Files.deleteIfExists(tmp); }
    return buf.toString("UTF-8");
  }

  @SuppressWarnings("unchecked")
  static String summary(String path) throws Exception {
    TheReportFile rf=open(path);
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    Report rep=rf.getGlobe().getMainReport();
    StringBuilder b=new StringBuilder();
    b.append("title=").append(rf.getTitle()).append("  version=").append(rf.getVersion()).append("\n");
    RexObjectList<DataSet> dss=gom.getDataSetList();
    b.append("datasets=").append(dss.size()).append("\n");
    for(int i=0;i<dss.size();i++){ DataSet ds=dss.get(i);
      RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
      b.append("  DS[").append(i).append("] ").append(ds.getName()).append("  fields(").append(fl.size()).append("): ");
      for(int j=0;j<fl.size();j++){ Object dt=fl.get(j).getDataType(); b.append(fl.get(j).getName()); if(dt!=null && !"Null".equals(dt.toString())) b.append(":").append(dt); b.append(j<fl.size()-1?", ":""); }
      b.append("\n");
      DataSetItemNormal n=ds.getDataSetItemNormal();
      if(n!=null && n.getDataAccessMethodSQL()!=null){ DataAccessMethodSQL q=n.getDataAccessMethodSQL();
        b.append("     scriptType=").append(q.getScriptType()).append("  queryLen=").append(q.getQueryString()==null?0:q.getQueryString().length()).append("\n"); }
    }
    // 용지(paper)
    MainPage mp0=rep.getReportDesign().getMainPage();
    b.append("용지: ").append(mp0.getPaperType()).append("  방향=").append(mp0.getPaperOrientationType())
     .append("  크기=").append(mp0.getPaperWidth()).append("x").append(mp0.getPaperHeight())
     .append("  여백 L").append(mp0.getLeftMargin()).append("/T").append(mp0.getTopMargin()).append("/R").append(mp0.getRightMargin()).append("/B").append(mp0.getBottomMargin()).append("\n");
    // 필드 인벤토리(분류별)
    var rom=rep.getReportObjectManager();
    { RexObjectList<?> gp=gom.getFieldGlobalParameterList(); if(gp!=null&&gp.size()>0){ b.append("  매개변수필드("+gp.size()+"): "); for(int i=0;i<gp.size();i++){ Object p=gp.get(i); String dt=g(p,"getDataType"), dv=g(p,"getDefaultValue"); b.append(i>0?", ":"").append(nameOf(p)); if(dt!=null&&!"Null".equals(dt)) b.append(":").append(dt); if(dv!=null&&!dv.isEmpty()) b.append("=\"").append(dv).append("\""); } b.append("\n"); } }
    inv(b,"시스템", gom.getFieldGlobalSpecialList());
    inv(b,"공식", rom.getFieldFormulaList());
    inv(b,"누적합산", rom.getFieldRunningTotalList());
    inv(b,"그룹이름", rom.getFieldGroupNameList());

    RexObjectList<Group> groups=rep.getReportObjectManager().getGroupList();
    b.append("groups=").append(groups.size());
    for(int i=0;i<groups.size();i++){ Field gf=groups.get(i).getGroupingField(); b.append(i==0?" [":",").append(gf==null?"-":gf.getName()); }
    if(groups.size()>0) b.append("]");
    b.append("\n");
    RexObjectList<Section> secs=rep.getReportDesign().getMainPage().getSectionList();
    b.append("sections(").append(secs.size()).append("):\n");
    for(int i=0;i<secs.size();i++){ Section sec=secs.get(i); String gf=groupFieldOf(sec); b.append("  [").append(i).append("] ").append(sec.getClass().getSimpleName().replace("Section","")).append(gf==null?"":"(→"+gf+")");
      RexObjectList<SubSection> ss=sec.getSubSectionList(); b.append(": "); for(int j=0;j<ss.size();j++) b.append(j>0?" | ":"").append(subSectionInfo(ss.get(j))); b.append("\n"); }
    return b.toString();
  }

  /** Reflective setter; a failure is an error (never a silent OK). */
  static void call(Object o,String m,Class<?> pt,Object arg){ try{ o.getClass().getMethod(m,pt).invoke(o,arg); }
    catch(NoSuchMethodException e){ throw new RuntimeException("속성 '"+m+"' 을(를) "+o.getClass().getSimpleName()+" 에 설정할 수 없습니다 (지원 안 함)"); }
    catch(Exception e){ Throwable c=e instanceof java.lang.reflect.InvocationTargetException&&e.getCause()!=null?e.getCause():e; throw new RuntimeException("속성 '"+m+"' 설정 실패: "+c); } }
  static Object tableCell(Control tbl,int r,int c){ try{ return tbl.getClass().getMethod("getTableCell",int.class,int.class).invoke(tbl,r,c); }catch(Exception e){ return null; } }
  static boolean isNormalCell(Object cell){ return cell!=null && cell.getClass().getSimpleName().equals("TableCellNormal"); }
  /** Table cell for editing: range-checked; a merged-away (TableCellDumy) cell is an error with a hint to the anchor cell. */
  static Object cellOf(Control tbl,int row,int col){
    Object rc=go(tbl,"getRowCount"), cc=go(tbl,"getColumnCount"); int rows=rc instanceof Integer?(Integer)rc:0, cols=cc instanceof Integer?(Integer)cc:0;
    if(row<0||col<0||row>=rows||col>=cols) throw new RuntimeException("셀 ["+row+","+col+"] 범위 밖 — 표 '"+tbl.getName()+"' 은 "+rows+"행×"+cols+"열 (0-based)");
    Object cell=tableCell(tbl,row,col); if(cell==null) throw new RuntimeException("셀 ["+row+","+col+"] 없음");
    if(!isNormalCell(cell)){ String hint="";
      for(int c=col-1;c>=0&&hint.isEmpty();c--) if(isNormalCell(tableCell(tbl,row,c))) hint=" 기준 셀 후보: ["+row+","+c+"]";
      for(int r=row-1;r>=0&&hint.isEmpty();r--) if(isNormalCell(tableCell(tbl,r,col))) hint=" 기준 셀 후보: ["+r+","+col+"]";
      throw new RuntimeException("셀 ["+row+","+col+"] 은 병합된(숨은) 셀이라 설정할 수 없습니다."+hint+" (crf_describe_layout 의 ‹병합› 표시 참고)"); }
    return cell;
  }

  @SuppressWarnings("unchecked")
  static Field findField(TheReportFile rf,String name){
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager();
    var rom=rf.getGlobe().getMainReport().getReportObjectManager();
    java.util.List<RexObjectList<?>> lists=new java.util.ArrayList<>();
    RexObjectList<DataSet> dss=gom.getDataSetList();
    for(int i=0;i<dss.size();i++) lists.add((RexObjectList<?>) dss.get(i).getFieldDataList());
    lists.add((RexObjectList<?>) rom.getFieldDataList()); lists.add((RexObjectList<?>) rom.getFieldFormulaList());
    lists.add((RexObjectList<?>) rom.getFieldRunningTotalList()); lists.add((RexObjectList<?>) rom.getFieldGroupNameList());
    lists.add((RexObjectList<?>) gom.getFieldGlobalParameterList()); lists.add((RexObjectList<?>) gom.getFieldGlobalSpecialList());
    for(RexObjectList<?> l: lists){ if(l==null)continue; for(int i=0;i<l.size();i++){ Object o=l.get(i); if(name.equalsIgnoreCase(nameOf(o))) return (Field)o; } }
    return null;
  }
  /** 같은 이름이 여러 종류(데이터/매개변수/공식…)에 있을 수 있으므로 종류(nsOf)까지 맞는 필드만 찾는다. */
  static Field findFieldOfKind(TheReportFile rf,String name,String ns){
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); var rom=rf.getGlobe().getMainReport().getReportObjectManager();
    java.util.List<RexObjectList<?>> lists=new java.util.ArrayList<>(); RexObjectList<DataSet> dss=gom.getDataSetList();
    for(int i=0;i<dss.size();i++) lists.add((RexObjectList<?>) dss.get(i).getFieldDataList());
    lists.add((RexObjectList<?>) rom.getFieldDataList()); lists.add((RexObjectList<?>) rom.getFieldFormulaList()); lists.add((RexObjectList<?>) rom.getFieldRunningTotalList()); lists.add((RexObjectList<?>) rom.getFieldGroupNameList());
    lists.add((RexObjectList<?>) gom.getFieldGlobalParameterList()); lists.add((RexObjectList<?>) gom.getFieldGlobalSpecialList());
    for(RexObjectList<?> l: lists){ if(l==null)continue; for(int i=0;i<l.size();i++){ Object o=l.get(i); if(o instanceof Field && name.equalsIgnoreCase(nameOf(o)) && nsOf((Field)o).equals(ns)) return (Field)o; } }
    return null;
  }
  static Control findTable(TheReportFile rf,String name){
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    java.util.List<Control> all=new java.util.ArrayList<>();
    for(int i=0;i<secs.size();i++) collectControls(secs.get(i),all,Collections.newSetFromMap(new IdentityHashMap<>()),0);
    for(Control c: all) if("ControlTable".equals(c.getClass().getSimpleName()) && name.equals(c.getName())) return c;
    return null;
  }
  /** Apply value/style properties from args to a cell or label. Returns a description of what was set (empty if nothing). */
  @SuppressWarnings("unchecked")
  static String applyProps(TheReportFile rf,Object target,JSONObject args,String autoFormulaName) {
    StringBuilder did=new StringBuilder();
    String field=s(args,"field"), text=s(args,"text"), formula=s(args,"formula"), format=s(args,"format");
    if(field!=null && !field.isEmpty()){ Field f=findField(rf,field); if(f==null) throw new RuntimeException("field '"+field+"' not found (crf_summary 로 이름 확인)");
      call(target,"setApplyValueType",ApplyValueType.class,ApplyValueType.Field); call(target,"setApplyValueField",Field.class,f); did.append(" field="+field+"("+fieldKindKo(f)+")"); }
    else if(formula!=null && !formula.trim().isEmpty()){ if(!java.util.regex.Pattern.compile("\\breturn\\b").matcher(formula).find()) throw new RuntimeException("formula 에 return 이 없습니다 (예: return rexpert.field(\"data.COL\");)");
      String fname=s(args,"formula_name"); if(fname==null||fname.trim().isEmpty()) fname=autoFormulaName; fname=fname.trim(); if(findField(rf,fname)!=null){ int i=2; while(findField(rf,fname+"_"+i)!=null) i++; fname=fname+"_"+i; }
      FieldFormula ff=new FieldFormula(); ff.setName(fname); ff.setScript(formula); ff.setScriptType(ScriptType.JavaScript); ((RexObjectList<FieldFormula>) rf.getGlobe().getMainReport().getReportObjectManager().getFieldFormulaList()).add(ff);
      call(target,"setApplyValueType",ApplyValueType.class,ApplyValueType.Field); call(target,"setApplyValueField",Field.class,ff); did.append(" formula="+fname+"{"+oneLine(formula,60)+"}"); }
    else if(text!=null){ call(target,"setApplyValueType",ApplyValueType.class,ApplyValueType.Text); call(target,"setApplyValueText",String.class,text); Object prevF=go(target,"getApplyValueField"); if(prevF!=null){ try{ call(target,"setApplyValueField",Field.class,null); did.append(" (바인딩 "+nameOf(prevF)+" 해제)"); }catch(Throwable t){} } did.append(" text=\""+text+"\""); }
    else if("true".equalsIgnoreCase(s(args,"clear"))){ call(target,"setApplyValueType",ApplyValueType.class,ApplyValueType.Text); call(target,"setApplyValueText",String.class,""); if(go(target,"getApplyValueField")!=null){ try{ call(target,"setApplyValueField",Field.class,null); }catch(Throwable t){} } did.append(" cleared"); }
    boolean clearFmt="true".equalsIgnoreCase(s(args,"clear_format")) || (format!=null && (format.isEmpty()||format.equalsIgnoreCase("none")||format.equalsIgnoreCase("general")));
    if(clearFmt){ String was=q(g(target,"getOutputFormat")); call(target,"setOutputFormat",String.class,""); did.append(was.isEmpty()?" format=(이미 없음)":" format 제거(was "+was+")"); }
    else if(format!=null && !format.isEmpty()){ call(target,"setOutputFormat",String.class,format); did.append(" format="+format); }
    String align=s(args,"align"), valign=s(args,"valign"), fontsize=s(args,"fontsize"), bold=s(args,"bold"), font=s(args,"font"), wrap=s(args,"wrap");
    if(align!=null||valign!=null||fontsize!=null||bold!=null||(font!=null&&!font.isEmpty())||wrap!=null){ Object ti=go(target,"getTextInfo"); if(ti==null) throw new RuntimeException("이 컨트롤은 텍스트 속성(TextInfo)이 없습니다");
      if(align!=null&&!align.isEmpty()){ String a=align.trim().toLowerCase(); com.clipsoft.clipreport.common.enums.HorizontalAlignmentMethod h= a.startsWith("l")?com.clipsoft.clipreport.common.enums.HorizontalAlignmentMethod.Left : a.startsWith("r")?com.clipsoft.clipreport.common.enums.HorizontalAlignmentMethod.Right : (a.startsWith("c")||a.startsWith("m"))?com.clipsoft.clipreport.common.enums.HorizontalAlignmentMethod.Middle : (a.startsWith("b")||a.startsWith("j"))?com.clipsoft.clipreport.common.enums.HorizontalAlignmentMethod.Both : (a.startsWith("e")||a.startsWith("d"))?com.clipsoft.clipreport.common.enums.HorizontalAlignmentMethod.Equal : null; if(h==null) throw new RuntimeException("align 은 Left|Center|Right|Both(양쪽)|Equal(배분)"); call(ti,"setHorizontalAlignment",com.clipsoft.clipreport.common.enums.HorizontalAlignmentMethod.class,h); did.append(" align="+h); }
      if(valign!=null&&!valign.isEmpty()){ String a=valign.trim().toLowerCase(); com.clipsoft.clipreport.common.enums.VerticalAlignmentMethod v= a.startsWith("t")?com.clipsoft.clipreport.common.enums.VerticalAlignmentMethod.Top : a.startsWith("b")?com.clipsoft.clipreport.common.enums.VerticalAlignmentMethod.Bottom : (a.startsWith("c")||a.startsWith("m"))?com.clipsoft.clipreport.common.enums.VerticalAlignmentMethod.Center : null; if(v==null) throw new RuntimeException("valign 은 Top|Center|Bottom"); call(ti,"setVerticalAlignment",com.clipsoft.clipreport.common.enums.VerticalAlignmentMethod.class,v); did.append(" valign="+v); }
      if(fontsize!=null&&!fontsize.isEmpty()){ call(ti,"setFontSize",short.class,(short)Integer.parseInt(fontsize.trim())); did.append(" 크기="+fontsize.trim()); }
      if(bold!=null&&!bold.isEmpty()){ call(ti,"setFontBold",boolean.class,Boolean.parseBoolean(bold)); did.append(" 굵게="+bold); }
      if(font!=null&&!font.isEmpty()){ call(ti,"setFontName",String.class,font); did.append(" 폰트="+font); }
      if(wrap!=null&&!wrap.isEmpty()){ call(ti,"setWordWrap",boolean.class,Boolean.parseBoolean(wrap)); did.append(" 줄바꿈="+wrap); } }
    String fcol=s(args,"color"), ul=s(args,"underline"), it=s(args,"italic"), lsp=s(args,"linespace"), pad=s(args,"padding");
    if((fcol!=null&&!fcol.isEmpty())||(ul!=null&&!ul.isEmpty())||(it!=null&&!it.isEmpty())||(lsp!=null&&!lsp.isEmpty())||(pad!=null&&!pad.isEmpty())){ Object ti=go(target,"getTextInfo"); if(ti==null) throw new RuntimeException("이 컨트롤은 텍스트 속성(TextInfo)이 없습니다");
      if(fcol!=null&&!fcol.isEmpty()){ call(ti,"setForeColor",int.class,parseColor(fcol)); did.append(" 글자색="+fcol); }
      if(ul!=null&&!ul.isEmpty()){ call(ti,"setFontUnderline",boolean.class,Boolean.parseBoolean(ul)); did.append(" 밑줄="+ul); }
      if(it!=null&&!it.isEmpty()){ call(ti,"setFontItalic",boolean.class,Boolean.parseBoolean(it)); did.append(" 기울임="+it); }
      if(lsp!=null&&!lsp.isEmpty()){ call(ti,"setLineSpace",float.class,Float.parseFloat(lsp.trim())); did.append(" 줄간격="+lsp.trim()+"pt"); }
      if(pad!=null&&!pad.isEmpty()){ String[] pp=pad.split(","); if(pp.length!=4) throw new RuntimeException("padding 은 'left,top,right,bottom' 4개 (0.1mm)"); call(ti,"setLeftMargin",int.class,Integer.parseInt(pp[0].trim())); call(ti,"setTopMargin",int.class,Integer.parseInt(pp[1].trim())); call(ti,"setRightMargin",int.class,Integer.parseInt(pp[2].trim())); call(ti,"setBottomMargin",int.class,Integer.parseInt(pp[3].trim())); did.append(" 여백="+pad); } }
    String cg=s(args,"cangrow"), mg=s(args,"merge"), bg=s(args,"bgcolor");
    if(cg!=null&&!cg.isEmpty()){ call(target,"setCanGrow",boolean.class,Boolean.parseBoolean(cg)); did.append(" 확장가능="+cg); }
    if(mg!=null&&!mg.isEmpty()){ call(target,"setCellMergeRowDataDuplication",boolean.class,Boolean.parseBoolean(mg)); did.append(" 셀합치기="+mg); }
    if(bg!=null&&!bg.isEmpty()){ call(target,"setBackStyle",BackStyleType.class,BackStyleType.Normal); call(target,"setBackColor",int.class,parseColor(bg)); did.append(" 배경="+bg); }
    return did.toString();
  }
  /** cells=[{row,col,table?,...props}] 일괄: 최상위 인자(table/font/align 등)는 기본값, 항목 인자가 우선. 한 번 저장·되읽기. */
  @SuppressWarnings("unchecked")
  static String setCellBatch(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"); Object co=args.get("cells"); JSONArray cells;
    try{ cells=co instanceof JSONArray?(JSONArray)co:(JSONArray)P.parse(String.valueOf(co)); }catch(Exception e){ return "ERROR: cells 는 JSON 배열이어야 합니다: "+e.getMessage(); }
    if(cells==null||cells.isEmpty()) return "ERROR: cells 가 비어 있습니다";
    TheReportFile rf=open(path); java.util.List<Object[]> plan=new java.util.ArrayList<>(); StringBuilder out=new StringBuilder();
    for(int i=0;i<cells.size();i++){ Object eo=cells.get(i); if(!(eo instanceof JSONObject)) return "ERROR: cells["+i+"] 는 객체여야 합니다";
      JSONObject e=(JSONObject)eo, a=new JSONObject(); for(Object k: args.keySet()) if(!k.equals("cells")&&!k.equals("path")&&!k.equals("output")) a.put(k,args.get(k)); for(Object k: e.keySet()) a.put(k,e.get(k));
      String table=s(a,"table"); if(table==null||table.isEmpty()) return "ERROR: cells["+i+"] table 이 없습니다(항목 또는 최상위에 지정)";
      if(s(a,"row")==null||s(a,"col")==null) return "ERROR: cells["+i+"] row/col 이 없습니다";
      int row=Integer.parseInt(q(s(a,"row")).trim()), col=Integer.parseInt(q(s(a,"col")).trim());
      Control tbl=findTable(rf,table); if(tbl==null) return "ERROR: cells["+i+"] table '"+table+"' not found";
      Object cell=cellOf(tbl,row,col); String did=applyProps(rf,cell,a,"F_"+table+"_"+row+"_"+col)+CrfTableOps.cellBorderArgs(cell,a);
      if(did.isEmpty()) return "ERROR: cells["+i+"] "+table+"["+row+","+col+"] nothing to set";
      String key=table+"["+row+","+col+"]"; for(Object[] pl: plan) if(key.equals(pl[5])) return "ERROR: cells["+i+"] "+key+" 가 같은 배치에 두 번 있습니다 — 한 셀은 한 항목으로";
      plan.add(new Object[]{table,row,col,s(a,"field"),expectedFormat(a),key}); out.append("\n  "+key+did); }
    String wrote=save(rf,output,path); TheReportFile v=open(output); java.util.List<String> bad=new java.util.ArrayList<>();
    for(Object[] pl: plan){ Control vt=findTable(v,(String)pl[0]); Object vc=vt==null?null:cellOf(vt,(Integer)pl[1],(Integer)pl[2]); Object vf=go(vc,"getApplyValueField"); String vfmt=g(vc,"getOutputFormat"), field=(String)pl[3], format=(String)pl[4];
      if(field!=null&&!field.isEmpty()&&(vf==null||!field.equalsIgnoreCase(nameOf(vf)))) bad.add(pl[0]+"["+pl[1]+","+pl[2]+"] field");
      if(format!=null&&!format.equals(q(vfmt))) bad.add(pl[0]+"["+pl[1]+","+pl[2]+"] format(기대 '"+format+"' 실제 '"+q(vfmt)+"')"); }
    if(!bad.isEmpty()) return "ERROR: 저장 후 되읽기 검증 실패 — "+bad;
    return "OK: "+plan.size()+"개 셀 설정, verified, wrote "+wrote+out;
  }
  /** 인자로부터 저장 후 기대되는 출력양식: clear_format/빈값/none/general 이면 "", format 이 있으면 그 값, 둘 다 없으면 null(검사 안 함). applyProps 의 우선순위와 동일. */
  static String expectedFormat(JSONObject a){ String f=s(a,"format"); boolean clear="true".equalsIgnoreCase(s(a,"clear_format")) || (f!=null && (f.isEmpty()||f.equalsIgnoreCase("none")||f.equalsIgnoreCase("general"))); if(clear) return ""; return f; }
  static String setCell(JSONObject args) throws Exception {
    if(args.get("cells")!=null) return setCellBatch(args);
    String path=s(args,"path"), table=s(args,"table"), output=s(args,"output");
    if(s(args,"row")==null||s(args,"col")==null) return "ERROR: row/col 이 없습니다 (여러 셀은 cells=[{row,col,...}] 로)";
    int row=Integer.parseInt(q(s(args,"row")).trim()), col=Integer.parseInt(q(s(args,"col")).trim());
    TheReportFile rf=open(path);
    Control tbl=findTable(rf,table); if(tbl==null) return "ERROR: table '"+table+"' not found (use crf_describe_layout for names)";
    Object cell=cellOf(tbl,row,col);
    String did=applyProps(rf,cell,args,"F_"+table+"_"+row+"_"+col)+CrfTableOps.cellBorderArgs(cell,args);
    if(did.isEmpty()) return "ERROR: nothing to set (field/text/formula/clear/format/align/fontsize/bold/wrap/cangrow/merge/bgcolor/border/linewidth/linecolor)";
    String wrote=save(rf,output,path);
    TheReportFile v=open(output); Control vt=findTable(v,table); Object vc=vt==null?null:cellOf(vt,row,col);
    Object vf=go(vc,"getApplyValueField"); String vtxt=g(vc,"getApplyValueText"), vfmt=g(vc,"getOutputFormat");
    String ver=" verified["+(vf!=null?"field="+nameOf(vf):"text=\""+vtxt+"\"")+(vfmt!=null&&!vfmt.isEmpty()?" format="+vfmt:"")+"]";
    String field=s(args,"field"), format=s(args,"format");
    if(field!=null && !field.isEmpty() && (vf==null || !field.equalsIgnoreCase(nameOf(vf)))) return "ERROR: 저장 후 되읽기 검증 실패 — 셀 바인딩이 반영되지 않음"+ver;
    String expF=expectedFormat(args); if(expF!=null && !expF.equals(q(vfmt))) return "ERROR: 저장 후 되읽기 검증 실패 — 출력양식 기대 '"+expF+"' 실제 '"+q(vfmt)+"'"+ver;
    return "OK: "+table+"["+row+","+col+"] set"+did+ver+", wrote "+wrote;
  }
  /** All controls with their location: {section, subsection, controlList, control}. */
  @SuppressWarnings("unchecked")
  static java.util.List<Object[]> allControls(TheReportFile rf){ java.util.List<Object[]> out=new java.util.ArrayList<>(); RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    for(int i=0;i<secs.size();i++){ RexObjectList<SubSection> ss=secs.get(i).getSubSectionList(); for(int j=0;j<ss.size();j++){ if(!(ss.get(j) instanceof SubSectionDefault)) continue; RexObjectList<ControlListForEachSeparatedPage> cls=((SubSectionDefault)ss.get(j)).getControlListForEachSeparatedPageList();
      for(int k=0;k<cls.size();k++){ RexObjectList<Control> cl=(RexObjectList<Control>)(RexObjectList<?>)cls.get(k).getControlList(); for(int x=0;x<cl.size();x++) out.add(new Object[]{secs.get(i),ss.get(j),cl,cl.get(x)}); } } }
    return out; }
  static Object[] findControlLoc(TheReportFile rf,String name){ if(name==null) return null; for(Object[] e: allControls(rf)) if(name.equals(((Control)e[3]).getName())) return e; return null; }
  static String setLabel(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), output=s(args,"output");
    TheReportFile rf=open(path); Object[] loc=findControlLoc(rf,name); if(loc==null) return "ERROR: control '"+name+"' not found (crf_describe_layout 로 이름 확인)";
    Control c=(Control)loc[3]; StringBuilder did=new StringBuilder(applyProps(rf,c,args,"F_"+name));
    if(args.get("left")!=null){ c.setX1(pInt(args.get("left"),c.getX1())); did.append(" left="+c.getX1()); } if(args.get("top")!=null){ c.setY1(pInt(args.get("top"),c.getY1())); did.append(" top="+c.getY1()); }
    if(args.get("width")!=null){ call(c,"setWidth",int.class,pInt(args.get("width"),0)); did.append(" width="+s(args,"width")); } if(args.get("height")!=null){ call(c,"setHeight",int.class,pInt(args.get("height"),0)); did.append(" height="+s(args,"height")); }
    String vis=s(args,"visible"); if(vis!=null&&!vis.isEmpty()){ c.setVisible(Boolean.parseBoolean(vis)); did.append(" visible="+vis); }
    did.append(applyBorder(c,args));
    if(did.length()==0) return "ERROR: nothing to set";
    String wrote=save(rf,output,path);
    Object[] v=findControlLoc(open(output),name); if(v==null) return "ERROR: 저장 후 되읽기 검증 실패";
    Object vf=go(v[3],"getApplyValueField"); String vtxt=g(v[3],"getApplyValueText");
    return "OK: "+c.getClass().getSimpleName()+" \""+name+"\" ("+((Section)loc[0]).getClass().getSimpleName().replace("Section","")+") set"+did+" verified["+(vf!=null?"field="+nameOf(vf):"text=\""+q(vtxt)+"\"")+"], wrote "+wrote;
  }
  @SuppressWarnings("unchecked")
  static String addFormulaField(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), script=s(args,"script"), output=s(args,"output"); boolean force="true".equalsIgnoreCase(s(args,"force"));
    if(name==null||name.trim().isEmpty()) return "ERROR: name 인자가 비어 있습니다";
    if(script==null||script.trim().isEmpty()) return "ERROR: script 인자가 비어 있습니다";
    name=name.trim();
    TheReportFile rf=open(path);
    Field dup=findField(rf,name); if(dup!=null) return "ERROR: 이름 중복 — '"+name+"' 은 이미 "+fieldKindKo(dup)+" 필드로 존재합니다. 다른 이름을 쓰거나 기존 필드를 사용하세요";
    if(!java.util.regex.Pattern.compile("\\breturn\\b").matcher(script).find() && !force) return "ERROR: 공식 스크립트에 return 이 없습니다 (CLIP 공식은 JavaScript 식 끝에 return 필수, 예: return rexpert.field(\"data.COL\");). 의도된 것이면 force=true";
    if(java.util.regex.Pattern.compile("(?<![\\w'\"])(:[A-Za-z_]\\w*|#\\{|\\$\\{)").matcher(script).find() && !force) return "ERROR: 공식 안에 :col/#{}/${} 바인드 표기가 있습니다 — 공식에서는 rexpert.field(\"data.COL\") 로 참조하세요 (그 표기는 쿼리 파라미터 전용). 의도된 것이면 force=true";
    var rom=rf.getGlobe().getMainReport().getReportObjectManager();
    FieldFormula ff=new FieldFormula(); ff.setName(name); ff.setScript(script); ff.setScriptType(ScriptType.JavaScript);
    ((RexObjectList<FieldFormula>) rom.getFieldFormulaList()).add(ff);
    String wrote=save(rf,output,path);
    TheReportFile v=open(output);
    int n=v.getGlobe().getMainReport().getReportObjectManager().getFieldFormulaList().size();
    return "OK: added formula field '"+name+"' = "+script+" (formula fields now "+n+"), wrote "+wrote;
  }

  static int parseColor(String s){ s=s.trim().replace("#",""); if(s.matches("[0-9a-fA-F]{6}")){ int r=Integer.parseInt(s.substring(0,2),16),g=Integer.parseInt(s.substring(2,4),16),b=Integer.parseInt(s.substring(4,6),16); return (b<<16)|(g<<8)|r; } return Integer.parseInt(s); }
  static int pInt(Object o,int def){ if(o instanceof Number) return ((Number)o).intValue(); try{ return Integer.parseInt(String.valueOf(o).trim()); }catch(Exception e){ return def; } }

  static String setCellStyle(JSONObject args) throws Exception {
    String path=s(args,"path"), table=s(args,"table"), output=s(args,"output"); int row=pInt(args.get("row"),0), col=pInt(args.get("col"),0);
    TheReportFile rf=open(path); Control tbl=findTable(rf,table); if(tbl==null) return "ERROR: table '"+table+"' not found";
    Object cell=cellOf(tbl,row,col); JSONObject a=new JSONObject(); for(Object k: args.keySet()) if(!k.equals("field")&&!k.equals("text")&&!k.equals("formula")&&!k.equals("format")&&!k.equals("clear")) a.put(k,args.get(k));
    String did=applyProps(rf,cell,a,"F_"+table)+CrfTableOps.cellBorderArgs(cell,args); if(did.isEmpty()) return "ERROR: nothing to set (bgcolor/font/cangrow/merge/align/fontsize/bold/wrap/border/linewidth/linecolor)";
    String wrote=save(rf,output,path); return "OK: "+table+"["+row+","+col+"] style"+did+", wrote "+wrote;
  }

  @SuppressWarnings("unchecked")
  static String addDataField(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), type=s(args,"type"), output=s(args,"output");
    if(name==null||name.trim().isEmpty()) return "ERROR: name 인자가 비어 있습니다"; name=name.trim();
    TheReportFile rf=open(path);
    DataSet ds=datasetOf(rf,s(args,"dataset"));
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    for(int i=0;i<fl.size();i++) if(name.equalsIgnoreCase(fl.get(i).getName())) return "ERROR: 이름 중복 — 데이터셋 "+ds.getName()+" 에 '"+fl.get(i).getName()+"' 필드가 이미 있습니다";
    Field other=findField(rf,name); String warn=other!=null?" ⚠ 같은 이름의 "+fieldKindKo(other)+" 필드가 다른 목록에 있음(공식/매개변수에서 혼동 주의)":"";
    FieldData f=new FieldData(); f.setName(name); try{ f.setDataType(DataType.valueOf(type==null?"String":type)); }catch(Exception e){ f.setDataType(DataType.String); } f.setIndex(fl.size()); fl.add(f);
    String wrote=save(rf,output,path);
    return "OK: added data field '"+name+"' ("+f.getDataType()+") to "+ds.getName()+", wrote "+wrote+warn;
  }

  static Section findSection(TheReportFile rf,String key){
    String k=key==null?"":key.replaceAll("\\s","").trim(); String eng=k;
    switch(k){ case "보고서머리글": eng="ReportHeader"; break; case "보고서바닥글": eng="ReportFooter"; break;
      case "페이지머리글": eng="PageHeader"; break; case "페이지바닥글": eng="PageFooter"; break;
      case "데이터머리글": eng="DataHeader"; break; case "데이터바닥글": eng="DataFooter"; break;
      case "본문": eng="Detail"; break; case "그룹머리글": eng="GroupHeader"; break; case "그룹바닥글": eng="GroupFooter"; break; }
    String want=("Section"+eng).toLowerCase();
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    for(int i=0;i<secs.size();i++) if(secs.get(i).getClass().getSimpleName().toLowerCase().equals(want)) return secs.get(i);
    for(int i=0;i<secs.size();i++) if(secs.get(i).getClass().getSimpleName().toLowerCase().contains(eng.toLowerCase())) return secs.get(i);
    return null;
  }
  static String engSection(String key){ String k=key==null?"":key.replaceAll("\\s","").trim(); switch(k){
    case "보고서머리글": return "ReportHeader"; case "보고서바닥글": return "ReportFooter"; case "페이지머리글": return "PageHeader";
    case "페이지바닥글": return "PageFooter"; case "데이터머리글": return "DataHeader"; case "데이터바닥글": return "DataFooter";
    case "본문": return "Detail"; case "그룹머리글": return "GroupHeader"; case "그룹바닥글": return "GroupFooter"; default: return k; } }
  static int rank(String simple){ switch(simple){ case "SectionReportHeader": return 0; case "SectionPageHeader": return 1; case "SectionDataHeader": return 2;
    case "SectionGroupHeader": return 3; case "SectionDetail": return 4; case "SectionGroupFooter": return 5; case "SectionDataFooter": return 6;
    case "SectionPageFooter": return 7; case "SectionReportFooter": return 8; default: return 4; } }
  @SuppressWarnings("unchecked")
  static Section findOrCreateSection(TheReportFile rf,String key){
    Section s=findSection(rf,key); if(s!=null) return s;
    String eng=engSection(key); if(eng.toLowerCase().contains("group")) return null;   // group bands need a Group (use crf_add_group)
    Section ns; try{ ns=(Section)Class.forName("com.clipsoft.clipreport.base.sections.Section"+eng).getDeclaredConstructor().newInstance(); }catch(Exception e){ return null; }
    ns.getSubSectionList().add(band("새 "+key,80));
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    int myr=rank("Section"+eng), idx=secs.size();
    for(int i=0;i<secs.size();i++) if(rank(secs.get(i).getClass().getSimpleName())>myr){ idx=i; break; }
    secs.add(idx,ns); return ns;
  }
  @SuppressWarnings("unchecked")
  static String addLabel(JSONObject args) throws Exception {
    String path=(String)args.get("path"), section=(String)args.get("section"), output=(String)args.get("output");
    String text=(String)args.get("text"), field=(String)args.get("field");
    TheReportFile rf=open(path);
    Section sec=findOrCreateSection(rf,section); if(sec==null) return "ERROR: section '"+section+"' not found (그룹 밴드는 crf_add_group 사용)";
    SubSectionDefault sd=firstSub(sec); if(sd==null) return "ERROR: section has no subsection";
    RexObjectList<Control> cl=(RexObjectList<Control>) clpOf(sd).getControlList();
    ControlLabel c=new ControlLabel(); c.setName(uniqueControlName(rf,"label_"+(field!=null?field:"text"))); c.setVisible(true);
    c.setX1(pInt(args.get("left"),0)); c.setY1(pInt(args.get("top"),0)); c.setWidth(pInt(args.get("width"),500)); c.setHeight(pInt(args.get("height"),80));
    String did;
    if(field!=null && !field.isEmpty()){ Field f=findField(rf,field); if(f==null) return "ERROR: field '"+field+"' not found"; c.setApplyValueType(ApplyValueType.Field); c.setApplyValueField(f); did="field="+field; }
    else { c.setApplyValueType(ApplyValueType.Text); c.setApplyValueText(text==null?"":text); did="text=\""+(text==null?"":text)+"\""; }
    // 글상자 기본: 테두리 없음·투명 배경(디자이너 기본과 동일). 스타일/공식/테두리 옵션은 applyProps/applyBorder 로.
    c.setShapeType(com.clipsoft.clipreport.common.enums.ShapeType.Rectangle); c.setLineStyle(com.clipsoft.clipreport.common.enums.LineStyle.None); c.setBackStyle(BackStyleType.Transparent);
    if(c.getLineInfo()!=null) c.getLineInfo().setLineStyle(com.clipsoft.clipreport.common.enums.LineStyle.None);
    JSONObject style=new JSONObject(); for(Object k: args.keySet()){ String key=String.valueOf(k); if(!key.equals("field")&&!key.equals("text")&&!key.equals("clear")) style.put(key,args.get(k)); }
    String did2=applyProps(rf,c,style,"F_"+c.getName())+applyBorder(c,args);
    did2+=inheritFont(c,defaultFont(rf,path,go(c,"getApplyValueField")!=null),s(args,"fontsize")==null);
    cl.add(c); save(rf,output,path);
    return "OK: added label \""+c.getName()+"\"("+did+did2+") to "+sec.getClass().getSimpleName().replace("Section","")+", wrote "+output;
  }

  static String setSubsection(JSONObject args) throws Exception {
    String path=s(args,"path"), section=s(args,"section"), output=s(args,"output"); int idx=pInt(args.get("index"),0);
    TheReportFile rf=open(path); Section sec=findSection(rf,section); if(sec==null) return "ERROR: section '"+section+"' not found";
    RexObjectList<SubSection> ss=sec.getSubSectionList(); if(idx<0||idx>=ss.size()) return "ERROR: index "+idx+" 범위 밖 (서브섹션 "+ss.size()+"개)";
    SubSection sb=ss.get(idx); StringBuilder did=new StringBuilder();
    if(args.get("height")!=null){ sb.setHeight(pInt(args.get("height"),sb.getHeight())); did.append(" height="+sb.getHeight()); }
    String vis=s(args,"visible"); if(vis!=null&&!vis.isEmpty()){ sb.setVisible(Boolean.parseBoolean(vis)); did.append(" visible="+vis); }
    String nm=s(args,"name"); if(nm!=null&&!nm.isEmpty()){ sb.setName(nm); did.append(" name=\""+nm+"\""); }
    String np=s(args,"new_page"); if(np!=null&&!np.isEmpty()){ NewPageType t; try{ t=NewPageType.valueOf(np.trim()); }catch(Exception e){ return "ERROR: new_page 는 None|Before|After|BeforeAfter"; } call(sb,"setNewPage",NewPageType.class,t); did.append(" new_page="+t); }
    String rp=s(args,"repeat"); if(rp!=null&&!rp.isEmpty()){ com.clipsoft.clipreport.base.enums.RepeatGroupType t; try{ t=com.clipsoft.clipreport.base.enums.RepeatGroupType.valueOf(rp.trim()); }catch(Exception e){ return "ERROR: repeat 는 None|OnPage|OnColumn|OnPageAndColumn"; } if(!(sb instanceof SubSectionDefault)) return "ERROR: repeat 는 기본 서브섹션에만"; ((SubSectionDefault)sb).setRepeatGroup(t); did.append(" repeat="+t); }
    if(did.length()==0) return "ERROR: nothing to set (height/visible/name/new_page/repeat)";
    String wrote=save(rf,output,path);
    return "OK: "+sec.getClass().getSimpleName().replace("Section","")+" sub["+idx+"] "+subSectionInfo(sb)+" set"+did+", wrote "+wrote;
  }
  static String removeControl(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), output=s(args,"output");
    TheReportFile rf=open(path); Object[] loc=findControlLoc(rf,name); if(loc==null) return "ERROR: control '"+name+"' not found";
    RexObjectList<?> cl=(RexObjectList<?>)loc[2]; for(int i=0;i<cl.size();i++) if(cl.get(i)==loc[3]){ cl.remove(i); break; }
    String wrote=save(rf,output,path); if(findControlLoc(open(output),name)!=null) return "ERROR: 저장 후 되읽기 검증 실패 — 컨트롤이 남아 있음";
    return "OK: "+((Control)loc[3]).getClass().getSimpleName()+" \""+name+"\" 삭제 ("+((Section)loc[0]).getClass().getSimpleName().replace("Section","")+"/"+nameOf(loc[1])+"), wrote "+wrote;
  }
  static String removeSection(JSONObject args) throws Exception {
    String path=s(args,"path"), key=s(args,"section"), output=s(args,"output"); boolean force="true".equalsIgnoreCase(s(args,"force"));
    if(engSection(key).toLowerCase().contains("group")) return "ERROR: 그룹 밴드는 crf_remove_group 으로 삭제하세요";
    TheReportFile rf=open(path); Section sec=findSection(rf,key);
    if(sec==null){ if("true".equalsIgnoreCase(s(args,"ignore_missing"))){ String wrote=save(rf,output,path); return "OK: 밴드 '"+key+"' 없음 — 변경 없이 저장(ignore_missing), wrote "+wrote; } return "ERROR: section '"+key+"' not found — 이 리포트의 밴드: "+sectionsOf(rf)+" (영문 ReportHeader/PageHeader/DataHeader/DataFooter/PageFooter/ReportFooter 또는 한글 보고서머리글/…/데이터바닥글 모두 가능)"; }
    if(sec instanceof SectionDetail) return "ERROR: 본문(Detail) 밴드는 삭제할 수 없습니다";
    int n=0; for(Object[] e: allControls(rf)) if(e[0]==sec) n++;
    if(n>0&&!force) return "ERROR: 이 밴드에 컨트롤 "+n+"개가 있습니다 — force=true 로 함께 삭제";
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList(); for(int i=0;i<secs.size();i++) if(secs.get(i)==sec){ secs.remove(i); break; }
    String wrote=save(rf,output,path); return "OK: "+sec.getClass().getSimpleName().replace("Section","")+" 밴드 삭제(컨트롤 "+n+"개 포함), wrote "+wrote;
  }
  static String setPaper(JSONObject args) throws Exception {
    String path=(String)args.get("path"), output=(String)args.get("output");
    TheReportFile rf=open(path); MainPage mp=rf.getGlobe().getMainReport().getReportDesign().getMainPage();
    StringBuilder did=new StringBuilder();
    String pp=(String)args.get("paper"); if(pp!=null && !pp.isEmpty()){ try{ mp.setPaperType(com.clipsoft.clipreport.common.enums.PaperType.valueOf(pp)); did.append(" 용지="+pp); }catch(Exception e){ return "ERROR: unknown paper '"+pp+"'"; } }
    int bodyBefore=mp.getPaperWidth()-mp.getLeftMargin()-mp.getRightMargin();
    String or=(String)args.get("orientation"); if(or!=null && !or.isEmpty()){ try{ mp.setPaperOrientationType(com.clipsoft.clipreport.common.enums.PaperOrientation.valueOf(or)); mp.setPaperOrientationUse(true); did.append(" 방향="+or);
        boolean land=or.equalsIgnoreCase("Landscape"); int w=mp.getPaperWidth(), h=mp.getPaperHeight();
        if(w>0&&h>0&&((land&&w<h)||(!land&&w>h))){ mp.setPaperWidth(h); mp.setPaperHeight(w); did.append(" 크기 "+w+"x"+h+"→"+h+"x"+w+"(디자이너와 같이 가로/세로 교환)"); }
      }catch(IllegalArgumentException e){ return "ERROR: unknown orientation '"+or+"' (Potrait|Landscape)"; } }
    if(args.get("marginL")!=null){ mp.setLeftMargin(pInt(args.get("marginL"),mp.getLeftMargin())); did.append(" L"+mp.getLeftMargin()); }
    if(args.get("marginT")!=null){ mp.setTopMargin(pInt(args.get("marginT"),mp.getTopMargin())); did.append(" T"+mp.getTopMargin()); }
    if(args.get("marginR")!=null){ mp.setRightMargin(pInt(args.get("marginR"),mp.getRightMargin())); did.append(" R"+mp.getRightMargin()); }
    if(args.get("marginB")!=null){ mp.setBottomMargin(pInt(args.get("marginB"),mp.getBottomMargin())); did.append(" B"+mp.getBottomMargin()); }
    if(did.length()==0) return "ERROR: nothing to set (paper/orientation/marginL/T/R/B/fit)";
    int bodyAfter=mp.getPaperWidth()-mp.getLeftMargin()-mp.getRightMargin();
    String fitNote=""; boolean fit="true".equalsIgnoreCase(s(args,"fit"))||"true".equalsIgnoreCase(s(args,"fit_tables"));
    if(fit && bodyBefore>0 && bodyAfter>0 && bodyBefore!=bodyAfter) fitNote=fitControlsToWidth(rf,bodyBefore,bodyAfter);
    else if(fit) fitNote="\nℹ fit: 본문 너비가 그대로("+bodyAfter+") 라 조정할 것이 없음";
    else if(bodyBefore!=bodyAfter){ int maxRight=0; for(Object[] e: allControls(rf)){ Control c=(Control)e[3]; maxRight=Math.max(maxRight,c.getX1()+ix(c,"getWidth")); } fitNote="\nℹ 본문 너비 "+bodyBefore+"→"+bodyAfter+(maxRight>bodyAfter?" — 요소 오른쪽 끝("+maxRight+") 이 본문을 넘습니다":"")+" — fit=true 를 주면 모든 밴드의 표(열 비례)·글상자·선의 X/너비를 새 본문 너비에 맞춰 비례 조정"; }
    String wrote=save(rf,output,path); return "OK: paper"+did+", 본문 너비="+bodyAfter+", wrote "+wrote+fitNote;
  }
  /** 모든 밴드의 컨트롤 X/너비를 oldW→newW 비율로 조정. 표는 열 너비를 비례 배분(합=목표) 후 reindex, 글상자/선/이미지는 X1·Width 를 반올림. 폭 0 인 요소는 X 만 이동. */
  static String fitControlsToWidth(TheReportFile rf,int oldW,int newW){
    double r=(double)newW/oldW; int tables=0, others=0;
    for(Object[] e: allControls(rf)){ Control c=(Control)e[3]; int x=c.getX1(), w=ix(c,"getWidth"); int nx=(int)Math.round(x*r);
      if(c instanceof ControlTable){ ControlTable t=(ControlTable)c; int n=t.getColumnCount(); if(n>0){ int[] cur=new int[n]; for(int k=0;k<n;k++) cur[k]=t.getTableColumn(k).getWidth(); int[] v=CrfTableOps.scaleTo(cur,(int)Math.round(w*r)); for(int k=0;k<n;k++) t.getTableColumn(k).setWidth(v[k]); CrfTableOps.reindex(t); } t.setX1(nx); tables++; }
      else { c.setX1(nx); if(w>0) call(c,"setWidth",int.class,(int)Math.round(w*r)); others++; } }
    return "\n↔ fit: 본문 너비 "+oldW+"→"+newW+" (×"+String.format("%.3f",r)+") — 표 "+tables+"개(열 비례)·기타 요소 "+others+"개 X/너비 조정";
  }

  @SuppressWarnings("unchecked")
  static java.util.Set<String> dataFieldNames(TheReportFile rf){ java.util.Set<String> s=new java.util.TreeSet<>();
    RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    for(int i=0;i<dss.size();i++){ RexObjectList<FieldData> fl=(RexObjectList<FieldData>) dss.get(i).getFieldDataList(); for(int j=0;j<fl.size();j++) s.add(fl.get(j).getName()); } return s; }
  static String sectionsOf(TheReportFile rf){ RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList(); StringBuilder b=new StringBuilder(); for(int i=0;i<secs.size();i++) b.append(secs.get(i).getClass().getSimpleName().replace("Section","")).append(i<secs.size()-1?",":""); return b.toString(); }
  static String cellGridLine(TheReportFile rf,String table){ Control t=findTable(rf,table); if(t==null) return ""; java.util.Map<String,String> m=cellGrid(t); StringBuilder b=new StringBuilder("  "+table+": "); int r0=-1; for(java.util.Map.Entry<String,String> e: m.entrySet()){ int r=Integer.parseInt(e.getKey().substring(1,e.getKey().indexOf(','))); if(r!=r0){ if(r0>=0) b.append("\n  "+table+"["+r+"]: "); r0=r; } else b.append(" | "); b.append(e.getValue()); } return b.toString(); }
  static java.util.Map<String,String> cellGrid(Control t){ java.util.Map<String,String> m=new java.util.LinkedHashMap<>(); Object rc=go(t,"getRowCount"), cc=go(t,"getColumnCount"); if(!(rc instanceof Integer)||!(cc instanceof Integer)) return m;
    for(int r=0;r<(Integer)rc;r++) for(int c=0;c<(Integer)cc;c++){ Object cell=tableCell(t,r,c); String v; if(!isNormalCell(cell)) v="‹병합›"; else { Object cf=go(cell,"getApplyValueField"); String ct=g(cell,"getApplyValueText"), fmt=g(cell,"getOutputFormat"); v=cf!=null?fieldKindKo(cf)+":"+nameOf(cf):(ct!=null&&!ct.isEmpty()?"\""+ct+"\"":"·"); if("Checkbox".equals(String.valueOf(go(cell,"getCellContent")))) v=checkboxText(cell); if(fmt!=null&&!fmt.isEmpty()) v+="{"+fmt+"}"; } m.put("["+r+","+c+"]",v); } return m; }
  /** 체크박스 셀 표기: ☐체크박스[필드 연산 값] (조건 없으면 ⚠ 항상 빈 상자) */
  static String checkboxText(Object cell){
    Object tc=go(cell,"getCheckValueTrueCondition"); Object f=go(tc,"getConditionField");
    String cond = f==null ? "⚠조건없음(항상 빈 상자)" : nameOf(f)+" "+go(tc,"getCompareOperator")+" '"+q(g(tc,"getCompareValue1Text"))+"'";
    return "☐체크박스("+go(cell,"getCheckType")+")["+cond+"]";
  }
  static java.util.Map<String,String> formulaMap(TheReportFile rf){ java.util.Map<String,String> m=new java.util.TreeMap<>(); RexObjectList<?> fl=rf.getGlobe().getMainReport().getReportObjectManager().getFieldFormulaList(); for(int i=0;i<fl.size();i++) m.put(nameOf(fl.get(i)),q(g(fl.get(i),"getScript"))); return m; }
  static java.util.Map<String,String> paramMap(TheReportFile rf){ java.util.Map<String,String> m=new java.util.TreeMap<>(); RexObjectList<?> gp=rf.getGlobe().getGlobalObjectManager().getFieldGlobalParameterList(); for(int i=0;i<gp.size();i++) m.put(nameOf(gp.get(i)),q(g(gp.get(i),"getDataType"))+"="+q(g(gp.get(i),"getDefaultValue"))); return m; }
  static java.util.Map<String,String> controlMap(TheReportFile rf){ java.util.Map<String,String> m=new java.util.TreeMap<>(); for(Object[] e: allControls(rf)){ Control c=(Control)e[3]; Object f=go(c,"getApplyValueField"); String t=g(c,"getApplyValueText"); m.put(c.getName(), ((Section)e[0]).getClass().getSimpleName().replace("Section","")+" "+c.getClass().getSimpleName().replace("Control","")+(f!=null?" ["+nameOf(f)+"]":(t!=null&&!t.isEmpty()?" \""+oneLine(t,30)+"\"":""))+" @"+c.getX1()+","+c.getY1()); } return m; }
  static void diffMaps(StringBuilder s,String title,java.util.Map<String,String> A,java.util.Map<String,String> B,int max){ java.util.List<String> add=new java.util.ArrayList<>(), rem=new java.util.ArrayList<>(), chg=new java.util.ArrayList<>();
    for(String k: B.keySet()) if(!A.containsKey(k)) add.add(k); for(String k: A.keySet()) if(!B.containsKey(k)) rem.add(k); for(String k: A.keySet()) if(B.containsKey(k)&&!q(A.get(k)).equals(q(B.get(k)))) chg.add(k);
    if(add.isEmpty()&&rem.isEmpty()&&chg.isEmpty()) return; s.append(title).append(": ");
    if(!add.isEmpty()) s.append("+").append(add.size()).append(" ").append(add.subList(0,Math.min(max,add.size()))).append(add.size()>max?"… ":" "); if(!rem.isEmpty()) s.append("-").append(rem.size()).append(" ").append(rem.subList(0,Math.min(max,rem.size()))).append(rem.size()>max?"… ":" ");
    if(!chg.isEmpty()){ s.append("~").append(chg.size()); for(int i=0;i<chg.size()&&i<max;i++){ String k=chg.get(i); s.append("\n     ").append(k).append(": ").append(oneLine(A.get(k),60)).append(" → ").append(oneLine(B.get(k),60)); } if(chg.size()>max) s.append("\n     …"); }
    s.append("\n"); }
  static String diff(String a,String b) throws Exception {
    TheReportFile A=open(a), B=open(b); StringBuilder s=new StringBuilder();
    s.append("A: ").append(new File(a).getName()).append("\nB: ").append(new File(b).getName()).append("\n");
    // datasets
    java.util.Map<String,DataSet> da=new java.util.LinkedHashMap<>(), db=new java.util.LinkedHashMap<>(); RexObjectList<DataSet> la=A.getGlobe().getGlobalObjectManager().getDataSetList(), lb=B.getGlobe().getGlobalObjectManager().getDataSetList();
    for(int i=0;i<la.size();i++) da.put(la.get(i).getName(),la.get(i)); for(int i=0;i<lb.size();i++) db.put(lb.get(i).getName(),lb.get(i));
    java.util.Map<String,String> dsa=new java.util.TreeMap<>(), dsb=new java.util.TreeMap<>(); for(String k: da.keySet()) dsa.put(k,"ds"); for(String k: db.keySet()) dsb.put(k,"ds"); diffMaps(s,"데이터셋",dsa,dsb,10);
    for(String k: da.keySet()){ if(!db.containsKey(k)) continue; DataSet x=da.get(k), y=db.get(k);
      java.util.Map<String,String> fa=new java.util.TreeMap<>(), fb=new java.util.TreeMap<>(); RexObjectList<?> xf=x.getFieldDataList(), yf=y.getFieldDataList(); for(int i=0;i<xf.size();i++) fa.put(nameOf(xf.get(i)),q(g(xf.get(i),"getDataType"))); for(int i=0;i<yf.size();i++) fb.put(nameOf(yf.get(i)),q(g(yf.get(i),"getDataType"))); diffMaps(s,"  "+k+" 필드",fa,fb,15);
      DataAccessMethodSQL qx=x.getDataSetItemNormal()==null?null:x.getDataSetItemNormal().getDataAccessMethodSQL(), qy=y.getDataSetItemNormal()==null?null:y.getDataSetItemNormal().getDataAccessMethodSQL();
      if(qx!=null&&qy!=null){ String sx=q(qx.getQueryString()), sy=q(qy.getQueryString()); if(qx.getScriptType()!=qy.getScriptType()) s.append("  ").append(k).append(" scriptType: ").append(qx.getScriptType()).append(" → ").append(qy.getScriptType()).append("\n");
        if(!sx.equals(sy)){ java.util.List<String> lx=java.util.Arrays.asList(sx.split("\\r?\\n")), ly=java.util.Arrays.asList(sy.split("\\r?\\n")); java.util.Set<String> setx=new java.util.HashSet<>(), sety=new java.util.HashSet<>(); for(String l: lx) setx.add(l.trim()); for(String l: ly) sety.add(l.trim());
          java.util.List<String> plus=new java.util.ArrayList<>(), minus=new java.util.ArrayList<>(); for(String l: ly) if(!l.trim().isEmpty()&&!setx.contains(l.trim())) plus.add(l.trim()); for(String l: lx) if(!l.trim().isEmpty()&&!sety.contains(l.trim())) minus.add(l.trim());
          s.append("  ").append(k).append(" 쿼리 변경: ").append(sx.length()).append("→").append(sy.length()).append(" chars, +").append(plus.size()).append(" 줄 / -").append(minus.size()).append(" 줄\n"); for(int i=0;i<plus.size()&&i<6;i++) s.append("     + ").append(oneLine(plus.get(i),110)).append("\n"); for(int i=0;i<minus.size()&&i<6;i++) s.append("     - ").append(oneLine(minus.get(i),110)).append("\n"); } } }
    diffMaps(s,"매개변수(타입=기본값)",paramMap(A),paramMap(B),10);
    diffMaps(s,"공식",formulaMap(A),formulaMap(B),10);
    java.util.Map<String,String> ga=new java.util.TreeMap<>(), gb=new java.util.TreeMap<>(); RexObjectList<Group> gla=A.getGlobe().getMainReport().getReportObjectManager().getGroupList(), glb=B.getGlobe().getMainReport().getReportObjectManager().getGroupList(); for(int i=0;i<gla.size();i++) ga.put(nameOf(gla.get(i).getGroupingField()),""+gla.get(i).getSortMethod()); for(int i=0;i<glb.size();i++) gb.put(nameOf(glb.get(i).getGroupingField()),""+glb.get(i).getSortMethod()); diffMaps(s,"그룹",ga,gb,10);
    String sa=sectionsOf(A), sb=sectionsOf(B); if(!sa.equals(sb)) s.append("섹션: ").append(sa).append(" → ").append(sb).append("\n");
    diffMaps(s,"컨트롤",controlMap(A),controlMap(B),15);
    java.util.Map<String,Control> ta=new java.util.TreeMap<>(), tb=new java.util.TreeMap<>(); for(Object[] e: allControls(A)) if("ControlTable".equals(e[3].getClass().getSimpleName())) ta.put(((Control)e[3]).getName(),(Control)e[3]); for(Object[] e: allControls(B)) if("ControlTable".equals(e[3].getClass().getSimpleName())) tb.put(((Control)e[3]).getName(),(Control)e[3]);
    for(String k: ta.keySet()) if(tb.containsKey(k)) diffMaps(s,"  표 "+k+" 셀",cellGrid(ta.get(k)),cellGrid(tb.get(k)),20);
    if(s.toString().split("\n").length<=2) s.append("차이 없음 (데이터셋/필드/쿼리/매개변수/공식/그룹/섹션/컨트롤/셀 기준)\n");
    return s.toString();
  }

  // ---- DB (Tibero/Oracle via JDBC; 접속정보는 env, 비밀번호 코드 미포함) ----
  static String safeName(String s){ return s==null?"":s.replaceAll("[^A-Za-z0-9_$.]","").toUpperCase(); }
  // .env 자동 로드(내장): 환경변수 우선, 없으면 ~/clip-report-mcp/.env → ~/.dbtools/.env → ./.env 순서.
  static java.util.Map<String,String> loadDotEnv(){
    java.util.Map<String,String> m=new java.util.LinkedHashMap<>();
    String home=System.getProperty("user.home");
    String[] cands={ System.getenv("CLIP_ENV_FILE"), home+"/clip-report-mcp/.env", home+"/.dbtools/.env", ".env" };
    for(String c: cands){ if(c==null) continue; File f=new File(c); if(!f.isFile()) continue;
      try{ for(String ln: Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)){
        String t=ln.trim(); if(t.isEmpty()||t.startsWith("#")||!t.contains("=")) continue;
        int i=t.indexOf('='); String k=t.substring(0,i).trim(); String v=t.substring(i+1).trim();
        if(v.length()>=2 && ((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'")))) v=v.substring(1,v.length()-1);
        m.put(k,v);
      } m.put("__source", f.getPath()); break; }catch(Exception ex){} }
    return m;
  }
  static Connection db() throws Exception {
    String url=System.getenv("CLIP_DB_URL"), user=System.getenv("CLIP_DB_USER"), pwd=System.getenv("CLIP_DB_PWD");
    if(url==null||user==null||pwd==null){ java.util.Map<String,String> e=loadDotEnv();
      if(url==null) url=e.get("CLIP_DB_URL"); if(user==null) user=e.get("CLIP_DB_USER"); if(pwd==null) pwd=e.get("CLIP_DB_PWD"); }
    if(url==null||url.isEmpty()||user==null) throw new RuntimeException("DB 미설정: ~/.dbtools/.env (또는 ~/clip-report-mcp/.env) 에 CLIP_DB_URL / CLIP_DB_USER / CLIP_DB_PWD 를 넣으세요. (동명 환경변수도 가능)");
    for(String drv: new String[]{"com.tmax.tibero.jdbc.TbDriver","oracle.jdbc.OracleDriver"}){ try{ Class.forName(drv); }catch(Throwable t){} }
    return DriverManager.getConnection(url, user, pwd==null?"":pwd);
  }
  /** true if the statement is a read (SELECT/WITH/EXPLAIN...) after stripping leading comments. */
  static boolean isReadOnlySql(String sql){ String t=sql.replaceAll("(?s)/\\*.*?\\*/"," ").replaceAll("(?m)--.*$"," ").trim().toUpperCase();
    return t.startsWith("SELECT")||t.startsWith("WITH")||t.startsWith("EXPLAIN")||t.startsWith("DESC")||t.startsWith("SHOW"); }
  static int DB_TIMEOUT_SEC=Integer.parseInt(System.getenv().getOrDefault("CLIP_DB_TIMEOUT","60"));
  static String runSql(String sql,int maxRows) throws Exception {
    StringBuilder b=new StringBuilder();
    try(Connection c=db(); Statement st=c.createStatement()){
      try{ st.setQueryTimeout(DB_TIMEOUT_SEC); }catch(Throwable t){}
      boolean hasRs=st.execute(sql);
      if(!hasRs) return "(updateCount="+st.getUpdateCount()+")";
      try(ResultSet rs=st.getResultSet()){
        ResultSetMetaData m=rs.getMetaData(); int n=m.getColumnCount(); int rows=0;
        for(int i=1;i<=n;i++){ if(i>1)b.append("\t"); b.append(m.getColumnLabel(i)); } b.append("\n");
        while(rs.next()){ for(int i=1;i<=n;i++){ if(i>1)b.append("\t"); String v=rs.getString(i); b.append(v==null?"":v.replace("\t"," ").replace("\n"," ").replace("\r"," ")); } b.append("\n");
          if(++rows>=maxRows){ b.append("... ("+maxRows+"행에서 잘림)\n"); break; } }
        b.append("("+rows+" rows)");
      }
    }
    return b.toString();
  }
  static String dbTables(String like) throws Exception {
    String k=like==null?"":like.toUpperCase().replaceAll("[^A-Za-z0-9_%]","");
    String sql = k.isEmpty() ? "SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME"
      : "SELECT OWNER, TABLE_NAME FROM ALL_TABLES WHERE TABLE_NAME LIKE '%"+k+"%' ORDER BY OWNER, TABLE_NAME";
    return runSql(sql, 500);
  }
  static String dbColumns(String table) throws Exception {
    String t=safeName(table);
    String sql="SELECT c.COLUMN_NAME, c.DATA_TYPE, c.DATA_LENGTH, c.NULLABLE, cc.COMMENTS "+
      "FROM ALL_TAB_COLUMNS c LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER=c.OWNER AND cc.TABLE_NAME=c.TABLE_NAME AND cc.COLUMN_NAME=c.COLUMN_NAME "+
      "WHERE c.TABLE_NAME='"+t+"' ORDER BY c.COLUMN_ID";
    return runSql(sql, 500);
  }
  // ---- PDF 텍스트 추출 (번들 pdfbox, 리플렉션으로 버전 비의존) ----
  static String pdfText(String path) throws Exception {
    Class<?> docC=Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
    Object d=docC.getMethod("load", File.class).invoke(null, new File(path));
    try{
      Class<?> stripC=Class.forName("org.apache.pdfbox.text.PDFTextStripper");
      Object s=stripC.getDeclaredConstructor().newInstance();
      String txt=(String) stripC.getMethod("getText", docC).invoke(s, d);
      if(txt==null) txt="";
      return txt.length()>20000 ? txt.substring(0,20000)+"\n...(20000자에서 잘림)" : txt;
    } finally { try{ docC.getMethod("close").invoke(d); }catch(Exception e){} }
  }

  /** Dataset by name (case-insensitive) or 0-based index; null/empty -> first. */
  static DataSet datasetOf(TheReportFile rf,String sel){
    RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    if(dss.size()==0) throw new RuntimeException("리포트에 데이터셋이 없습니다");
    if(sel==null||sel.trim().isEmpty()) return dss.get(0);
    String k=sel.trim(); for(int i=0;i<dss.size();i++) if(k.equalsIgnoreCase(dss.get(i).getName())) return dss.get(i);
    try{ int i=Integer.parseInt(k); if(i>=0&&i<dss.size()) return dss.get(i); }catch(NumberFormatException e){}
    StringBuilder names=new StringBuilder(); for(int i=0;i<dss.size();i++) names.append(i==0?"":", ").append(i).append(":").append(dss.get(i).getName());
    throw new RuntimeException("데이터셋 '"+sel+"' 없음 — 사용 가능: "+names);
  }
  static boolean looksLikeJsQuery(String q){ return q!=null && java.util.regex.Pattern.compile("(?s)\\bvar\\s+\\w+\\s*=\\s*\"|\\w+\\s*\\+=\\s*\"|\\n\\s*\\+\\s*\"").matcher(q).find(); }
  static boolean looksLikeMyBatis(String q){ return q!=null && java.util.regex.Pattern.compile("(?is)<(if|where|foreach|choose|trim|set|select)\\b").matcher(q).find(); }
  /** Result of applying a query to a dataset (shared by crf_set_query / crf_add_dataset). */
  static class QueryApply { String conv; ScriptType before, after; java.util.List<String> warns=new java.util.ArrayList<>(), declared=new java.util.ArrayList<>(), added=new java.util.ArrayList<>(), removed=new java.util.ArrayList<>(), keptRef=new java.util.ArrayList<>(), skipped=new java.util.ArrayList<>(), placeholders=new java.util.ArrayList<>(), orphan=new java.util.ArrayList<>(); java.util.Set<String> undeclared=new java.util.TreeSet<>(); boolean noColumns, reordered, noReturn; }
  static DataType dataTypeOrNull(String t){ try{ return DataType.valueOf(t); }catch(Exception e){ return DataType.String; } }
  static DataType nullType(){ try{ return DataType.valueOf("Null"); }catch(Exception e){ return DataType.String; } }
  @SuppressWarnings("unchecked")
  static FieldGlobalParameter addGlobalParam(TheReportFile rf,String name,DataType type,String def,String prompt){
    FieldGlobalParameter fp=new FieldGlobalParameter(); fp.setName(name); fp.setDataType(type==null?DataType.String:type); fp.setDefaultValue(def==null?"":def); fp.setValueIsNull(Boolean.FALSE); fp.setPrompt(prompt==null?name:prompt); fp.setTag("");
    ((RexObjectList<FieldGlobalParameter>)(RexObjectList<?>) rf.getGlobe().getGlobalObjectManager().getFieldGlobalParameterList()).add(fp); return fp; }
  static FieldData findDataField(DataSet ds,String name){ RexObjectList<?> fl=ds.getFieldDataList(); for(int i=0;i<fl.size();i++) if(name.equalsIgnoreCase(nameOf(fl.get(i)))) return (FieldData)fl.get(i); return null; }
  @SuppressWarnings("unchecked")
  static FieldData addDataFieldTo(DataSet ds,String name,DataType type){ RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList(); FieldData f=new FieldData(); f.setName(name); f.setDataType(type==null?nullType():type); f.setIndex(fl.size()); fl.add(f); return f; }
  static boolean validIdent(String n){ return n!=null && n.matches("[A-Za-z_가-힣][A-Za-z0-9_가-힣$#]*"); }
  /** Convert + set the query on a dataset; optionally declare missing parameters and sync fields from the SELECT list. */
  @SuppressWarnings("unchecked")
  static QueryApply applyQuery(TheReportFile rf,DataSet ds,String sql,String mode,boolean declare,String sync){ return applyQuery(rf,ds,sql,mode,declare,sync,true); }
  @SuppressWarnings("unchecked")
  static QueryApply applyQuery(TheReportFile rf,DataSet ds,String sql,String mode,boolean declare,String sync,boolean reorder){
    QueryApply r=new QueryApply();
    DataAccessMethodSQL q=ds.getDataSetItemNormal()==null?null:ds.getDataSetItemNormal().getDataAccessMethodSQL();
    if(q==null) throw new RuntimeException("데이터셋 "+ds.getName()+" 은 SQL 데이터셋이 아닙니다");
    r.before=q.getScriptType(); String m=mode==null||mode.trim().isEmpty()?"auto":mode.trim().toLowerCase(); String conv; ScriptType after;
    if(m.equals("auto")){ if(looksLikeMyBatis(sql)){ conv=CrfGen2.mybatisToJs(sql,r.warns); after=ScriptType.JavaScript; } else if(looksLikeJsQuery(sql)){ conv=CrfGen2.subParamsQuoted(sql); after=ScriptType.JavaScript; } else { conv=CrfGen2.subParamsQuoted(CrfGen2.stripComments(sql)).trim(); after=ScriptType.NotScript; } }
    else if(m.equals("javascript")||m.equals("js")){ conv=looksLikeMyBatis(sql)?CrfGen2.mybatisToJs(sql,r.warns):CrfGen2.subParamsQuoted(sql); after=ScriptType.JavaScript; }
    else if(m.equals("sql")){ conv=CrfGen2.subParamsQuoted(sql); after=ScriptType.NotScript; }
    else throw new RuntimeException("script_type 은 auto|sql|javascript 중 하나");
    conv=CrfGen2.normParamTokens(conv); q.setQueryString(conv); q.setScriptType(after); r.conv=conv; r.after=after;
    java.util.Set<String> declaredNames=declaredParams(rf);
    for(String u: usedParams(conv)) if(!declaredNames.contains(u.toUpperCase())){ if(declare){ addGlobalParam(rf,u,nullType(),"",u); declaredNames.add(u.toUpperCase()); r.declared.add(u); } else r.undeclared.add(u); }
    String sy=sync==null||sync.trim().isEmpty()?"add":sync.trim().toLowerCase();
    if(!sy.equals("none")){ String plain=after==ScriptType.JavaScript?jsToPlainSql(conv):conv; java.util.List<String> cols=CrfGen2.parseColumns(CrfGen2.stripComments(plain));
      if(cols.isEmpty()) r.noColumns=true;
      else { java.util.Set<String> want=new java.util.HashSet<>();
        for(String c: cols){ if(!validIdent(c)){ r.skipped.add(c); continue; } want.add(c.toUpperCase()); if(findDataField(ds,c)==null){ addDataFieldTo(ds,c,nullType()); if(c.matches("COL_\\d+")) r.placeholders.add(c); else r.added.add(c); } }
        if(sy.equals("replace")){ RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList(); for(int i=fl.size()-1;i>=0;i--){ FieldData f=fl.get(i); if(want.contains(f.getName().toUpperCase())) continue; java.util.List<String> refs=refsOf(rf,f); if(refs.isEmpty()){ fl.remove(i); r.removed.add(f.getName()); } else r.keptRef.add(f.getName()+"("+refs.size()+"곳 참조)"); } }
        if(reorder) r.reordered=reorderFields(ds,cols,r.orphan); } }
    if(after==ScriptType.JavaScript && !hasReturn(conv)) r.noReturn=true;
    return r;
  }
  /** 데이터셋 필드는 이름이 아니라 **목록 위치**로 SELECT 컬럼에 매핑된다(디자이너 파일은 FieldData.index 가 전부 0). SELECT 순서대로 필드 목록을 재배열하고, SELECT 에 없는 필드는 뒤로 보낸다(orphan 에 기록). 순서가 바뀌었으면 true. */
  @SuppressWarnings("unchecked")
  static boolean reorderFields(DataSet ds,java.util.List<String> cols,java.util.List<String> orphan){
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList(); java.util.List<FieldData> cur=new java.util.ArrayList<>(); for(int i=0;i<fl.size();i++) cur.add(fl.get(i));
    java.util.List<FieldData> ordered=new java.util.ArrayList<>(); java.util.Set<FieldData> used=Collections.newSetFromMap(new IdentityHashMap<>());
    for(String c: cols){ for(FieldData f: cur) if(!used.contains(f) && c.equalsIgnoreCase(f.getName())){ ordered.add(f); used.add(f); break; } }
    for(FieldData f: cur) if(!used.contains(f)){ ordered.add(f); if(orphan!=null) orphan.add(f.getName()); }
    boolean changed=false; for(int i=0;i<cur.size();i++) if(cur.get(i)!=ordered.get(i)){ changed=true; break; }
    if(changed){ fl.removeAll(); for(FieldData f: ordered) fl.add(f); }
    for(int i=0;i<fl.size();i++) fl.get(i).setIndex(i);
    return changed;
  }
  /** JS 동적 쿼리에 return 문이 있는지(주석 제외). 없으면 엔진이 빈 문자열을 실행해 에러 없이 0건. */
  static boolean hasReturn(String js){ if(js==null) return false; String t=js.replaceAll("(?s)/\\*.*?\\*/"," ").replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'"," \"\" ").replaceAll("(?m)//[^\\r\\n]*",""); return java.util.regex.Pattern.compile("(?<![\\w$.])return(?![\\w$])").matcher(t).find(); }
  static String applySummary(QueryApply r){ StringBuilder b=new StringBuilder();
    if(!r.declared.isEmpty()) b.append("\n+ 매개변수 선언: ").append(r.declared).append(" (디자이너 기본과 같은 타입 없음(Null)·기본값 ''; 타입/기본값은 crf_set_param 으로)");
    if(!r.undeclared.isEmpty()) b.append("\n⚠ 쿼리가 쓰는데 선언되지 않은 매개변수: ").append(r.undeclared).append(" (declare_params=true 또는 crf_set_param)");
    if(!r.added.isEmpty()) b.append("\n+ 필드 추가: ").append(r.added);
    if(!r.removed.isEmpty()) b.append("\n- 필드 제거(미참조): ").append(r.removed);
    if(!r.keptRef.isEmpty()) b.append("\n⚠ SELECT 에 없지만 참조 중이라 유지: ").append(r.keptRef);
    if(!r.placeholders.isEmpty()) b.append("\n⚠ 별칭 없는 식 컬럼은 자리 필드 ").append(r.placeholders).append(" 로 생성(위치 매핑 유지용) — SELECT 에 AS 별칭을 주고 다시 set_query 하면 그 이름으로 바뀝니다");
    if(!r.skipped.isEmpty()) b.append("\n⚠ 필드로 만들 수 없는 컬럼명(건너뜀 — 뒤 컬럼 위치가 밀립니다, AS 별칭 필요): ").append(r.skipped);
    if(r.reordered) b.append("\n↕ 필드 순서를 SELECT 순서로 재정렬(필드는 이름이 아니라 위치로 컬럼에 매핑됨; reorder=false 로 끌 수 있음)");
    if(!r.orphan.isEmpty()) b.append("\n⚠ SELECT 에 없는 필드 ").append(r.orphan).append(" 는 목록 끝으로 — 대응 컬럼이 없어 값이 비게 됩니다(sync_fields=replace 로 제거하거나 SELECT 에 추가)");
    if(r.noReturn) b.append("\n⚠ JavaScript 쿼리에 return 문이 없습니다 — 마지막 줄에 `return sql;` 이 없으면 에러 없이 0건이 됩니다");
    if(r.noColumns) b.append("\n⚠ SELECT 목록을 파싱하지 못함(SELECT * / 함수테이블) — crf_sync_fields mode=db 로 DB 에서 컬럼을 확정하세요");
    if(!r.warns.isEmpty()) b.append("\n⚠ MyBatis 변환 경고: ").append(r.warns);
    return b.toString(); }
  @SuppressWarnings("unchecked")
  static String setQuery(JSONObject args) throws Exception {
    String path=s(args,"path"), sql=s(args,"sql"), output=s(args,"output");
    if(sql==null||sql.trim().isEmpty()) return "ERROR: sql 인자가 비어 있습니다";
    TheReportFile rf=open(path); DataSet ds=datasetOf(rf,s(args,"dataset"));
    boolean declare=!"false".equalsIgnoreCase(s(args,"declare_params"));
    QueryApply r=applyQuery(rf,ds,sql,s(args,"script_type"),declare,s(args,"sync_fields"),!"false".equalsIgnoreCase(s(args,"reorder")));
    String wrote=save(rf,output,path);
    StringBuilder b=new StringBuilder("OK: set query on "+ds.getName()+" ("+r.conv.length()+" chars, scriptType "+r.before+" → "+r.after+")"+(r.conv.equals(sql)?"":" — 파라미터를 {parameter.X} 형식으로 정규화함")+", wrote "+wrote);
    b.append(applySummary(r)); b.append("\n필드("+ds.getFieldDataList().size()+"): "+fieldList(ds));
    b.append("\n----- query (first 600 chars) -----\n").append(r.conv.length()>600?r.conv.substring(0,600)+"\n…":r.conv);
    return b.toString();
  }

  // ===== v0.5.1: dataset / parameter / field editing =====
  static String nsOf(Field f){ switch(f.getClass().getSimpleName()){ case "FieldData": return "data"; case "FieldFormula": return "formula"; case "FieldGlobalParameter": case "FieldParameter": case "FieldReportParameter": return "parameter"; case "FieldRunningTotal": return "runningtotal"; case "FieldGroupName": return "groupname"; case "FieldGroupIndex": return "groupindex"; case "FieldGlobalSpecial": return "system"; default: return f.getClass().getSimpleName().toLowerCase(); } }
  static java.util.regex.Pattern scriptRefPattern(String ns,String name){ return java.util.regex.Pattern.compile("([\"'])"+ns+"\\."+java.util.regex.Pattern.quote(name)+"\\1", java.util.regex.Pattern.CASE_INSENSITIVE); }
  static java.util.regex.Pattern paramTokenPattern(String name){ return java.util.regex.Pattern.compile("\\{parameter\\."+java.util.regex.Pattern.quote(name)+"\\}", java.util.regex.Pattern.CASE_INSENSITIVE); }
  /** Everywhere a field object is used: bindings (labels/cells), group fields, running totals, conditions, subreport links (by identity) + formula scripts and query tokens (by name). */
  @SuppressWarnings("unchecked")
  static java.util.List<String> refsOf(TheReportFile rf,Field target){
    java.util.List<String> out=new java.util.ArrayList<>();
    walkRefs(rf.getGlobe(),target,out,Collections.newSetFromMap(new IdentityHashMap<>()),"",0);
    var rom=rf.getGlobe().getMainReport().getReportObjectManager(); java.util.regex.Pattern sp=scriptRefPattern(nsOf(target),target.getName());
    RexObjectList<FieldFormula> fl=(RexObjectList<FieldFormula>) rom.getFieldFormulaList(); for(int i=0;i<fl.size();i++) if(fl.get(i)!=target && sp.matcher(q(fl.get(i).getScript())).find()) out.add("공식 "+fl.get(i).getName()+" 스크립트");
    if(nsOf(target).equals("parameter")){ java.util.regex.Pattern pp=paramTokenPattern(target.getName()); RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
      for(int i=0;i<dss.size();i++){ DataSetItemNormal n=dss.get(i).getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL(); if(qm!=null && pp.matcher(q(qm.getQueryString())).find()) out.add("데이터셋 "+dss.get(i).getName()+" 쿼리 {parameter."+target.getName()+"}"); } }
    return out;
  }
  static boolean structural(Object v){ if(v==null) return false; String sn=v.getClass().getSimpleName(); return v instanceof Section||v instanceof SubSection||v instanceof Control||sn.startsWith("TableCell")||sn.equals("FieldLink"); }
  @SuppressWarnings("unchecked")
  static void walkRefs(Object o,Field target,java.util.List<String> out,java.util.Set<Object> seen,String ctx,int d){
    if(o==null||d>30) return;
    if(o instanceof RexObjectList){ RexObjectList<?> l=(RexObjectList<?>)o; for(int i=0;i<l.size();i++){ Object e=l.get(i); if(structural(e)) continue; walkRefs(e,target,out,seen,ctx,d); } return; }
    if(!o.getClass().getName().startsWith("com.clipsoft.clipreport")) return; if(o==target) return; if(!seen.add(o)) return;
    String sn=o.getClass().getSimpleName(); String c=ctx;
    if(o instanceof Section){ String gf=groupFieldOf((Section)o); c=sn.replace("Section","")+(gf==null?"":"(→"+gf+")"); } else if(o instanceof SubSection) c=ctx+"/"+q(nameOf(o)); else if(o instanceof Control) c=ctx+"/"+sn.replace("Control","")+"\""+q(nameOf(o))+"\""; else if(o instanceof Field) c="필드 "+nameOf(o); else if(o instanceof Group) c="그룹"; else if(sn.equals("Condition")) c=ctx+"/조건"; else if(sn.equals("ConditionalStyle")) c=ctx+"/조건스타일";
    // --- structural children first (explicit, with precise context) ---
    if(o instanceof MainPage){ RexObjectList<Section> secs=((MainPage)o).getSectionList(); for(int i=0;i<secs.size();i++) walkRefs(secs.get(i),target,out,seen,c,d+1); }
    if(o instanceof Section){ RexObjectList<SubSection> ss=((Section)o).getSubSectionList(); for(int i=0;i<ss.size();i++) walkRefs(ss.get(i),target,out,seen,c,d+1); }
    if(o instanceof SubSectionDefault){ RexObjectList<ControlListForEachSeparatedPage> cls=((SubSectionDefault)o).getControlListForEachSeparatedPageList(); for(int k=0;k<cls.size();k++){ RexObjectList<Control> cl=(RexObjectList<Control>)(RexObjectList<?>)cls.get(k).getControlList(); for(int x=0;x<cl.size();x++) walkRefs(cl.get(x),target,out,seen,c,d+1); } }
    if(o instanceof Control && "ControlTable".equals(sn)){ Object rc=go(o,"getRowCount"), cc=go(o,"getColumnCount"); if(rc instanceof Integer && cc instanceof Integer) for(int r=0;r<(Integer)rc;r++) for(int k=0;k<(Integer)cc;k++){ Object cell=tableCell((Control)o,r,k); if(cell!=null) walkRefs(cell,target,out,seen,c+"["+r+","+k+"]",d+1); } }
    Object links=go(o,"getFieldLinkListForSubReportParameter"); if(links instanceof RexObjectList){ RexObjectList<?> ll=(RexObjectList<?>)links; for(int i=0;i<ll.size();i++) walkRefs(ll.get(i),target,out,seen,c+"/매개변수링크",d+1); }
    // --- generic getters (never descend into structural objects here) ---
    for(java.lang.reflect.Method m:o.getClass().getMethods()){ if(m.getParameterCount()!=0||!m.getName().startsWith("get")||m.getName().equals("getClass")) continue; String nm=m.getName(); if(nm.equals("getParentObj")||nm.equals("getRefInfomationStorage")||nm.equals("getRefStorage")||nm.equals("getSubreport")) continue;
      Class<?> rt=m.getReturnType(); if(rt.isPrimitive()||rt==String.class||rt.isEnum()||rt==Class.class||rt.isArray()) continue;
      Object v; try{ v=m.invoke(o); }catch(Throwable t){ continue; } if(v==null) continue;
      if(v==target){ out.add(c+"."+nm.substring(3)); continue; }
      if(v instanceof Field||structural(v)) continue;
      walkRefs(v,target,out,seen,c,d+1); }
  }
  /** All field references in the layout: {ctx.getter, Field}. */
  static void collectAllFieldRefs(Object o,java.util.List<Object[]> all,java.util.Set<Object> seen,String ctx,int d){
    if(o==null||d>30) return;
    if(o instanceof RexObjectList){ RexObjectList<?> l=(RexObjectList<?>)o; for(int i=0;i<l.size();i++){ Object e=l.get(i); if(structural(e)) continue; collectAllFieldRefs(e,all,seen,ctx,d); } return; }
    if(!o.getClass().getName().startsWith("com.clipsoft.clipreport")) return; if(o instanceof Field) return; if(!seen.add(o)) return;
    String sn=o.getClass().getSimpleName(); String c=ctx;
    if(o instanceof Section){ c=sn.replace("Section",""); } else if(o instanceof SubSection) c=ctx+"/"+q(nameOf(o)); else if(o instanceof Control) c=ctx+"/"+sn.replace("Control","")+"\""+q(nameOf(o))+"\""; else if(o instanceof Group) c="그룹"; else if(sn.equals("Condition")) c=ctx+"/조건"; else if(sn.equals("ConditionalStyle")) c=ctx+"/조건스타일";
    if(o instanceof MainPage){ RexObjectList<Section> secs=((MainPage)o).getSectionList(); for(int i=0;i<secs.size();i++) collectAllFieldRefs(secs.get(i),all,seen,c,d+1); }
    if(o instanceof Section){ RexObjectList<SubSection> ss=((Section)o).getSubSectionList(); for(int i=0;i<ss.size();i++) collectAllFieldRefs(ss.get(i),all,seen,c,d+1); }
    if(o instanceof SubSectionDefault){ RexObjectList<ControlListForEachSeparatedPage> cls=((SubSectionDefault)o).getControlListForEachSeparatedPageList(); for(int k=0;k<cls.size();k++){ RexObjectList<?> cl=cls.get(k).getControlList(); for(int x=0;x<cl.size();x++) collectAllFieldRefs(cl.get(x),all,seen,c,d+1); } }
    if(o instanceof Control && "ControlTable".equals(sn)){ Object rc=go(o,"getRowCount"), cc=go(o,"getColumnCount"); if(rc instanceof Integer && cc instanceof Integer) for(int r=0;r<(Integer)rc;r++) for(int k=0;k<(Integer)cc;k++){ Object cell=tableCell((Control)o,r,k); if(cell!=null) collectAllFieldRefs(cell,all,seen,c+"["+r+","+k+"]",d+1); } }
    Object links=go(o,"getFieldLinkListForSubReportParameter"); if(links instanceof RexObjectList){ RexObjectList<?> ll=(RexObjectList<?>)links; for(int i=0;i<ll.size();i++) collectAllFieldRefs(ll.get(i),all,seen,c+"/매개변수링크",d+1); }
    for(java.lang.reflect.Method m:o.getClass().getMethods()){ if(m.getParameterCount()!=0||!m.getName().startsWith("get")||m.getName().equals("getClass")) continue; String nm=m.getName(); if(nm.equals("getParentObj")||nm.equals("getRefInfomationStorage")||nm.equals("getRefStorage")||nm.equals("getSubreport")) continue;
      Class<?> rt=m.getReturnType(); if(rt.isPrimitive()||rt==String.class||rt.isEnum()||rt==Class.class||rt.isArray()) continue;
      Object v; try{ v=m.invoke(o); }catch(Throwable t){ continue; }
      if(Field.class.isAssignableFrom(rt)){ String at=g(o,"getApplyValueType"); if(nm.equals("getApplyValueField") && !"Field".equals(at)) continue; if(v!=null) all.add(new Object[]{c+"."+nm.substring(3),v}); else if(nm.equals("getApplyValueField")||nm.equals("getGroupingField")||nm.equals("getLinkedField1")||nm.equals("getLinkedField2")) all.add(new Object[]{c+"."+nm.substring(3),null}); continue; }
      if(v==null||structural(v)) continue; collectAllFieldRefs(v,all,seen,c,d+1); }
  }
  static java.util.Set<Object> knownFields(TheReportFile rf){ java.util.Set<Object> k=Collections.newSetFromMap(new IdentityHashMap<>()); GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); var rom=rf.getGlobe().getMainReport().getReportObjectManager(); RexObjectList<DataSet> dss=gom.getDataSetList();
    for(int i=0;i<dss.size();i++){ RexObjectList<?> fl=dss.get(i).getFieldDataList(); for(int j=0;j<fl.size();j++) k.add(fl.get(j)); RexObjectList<?> dp=dss.get(i).getFieldDataSetParameterList(); if(dp!=null) for(int j=0;j<dp.size();j++) k.add(dp.get(j)); }
    for(RexObjectList<?> l: new RexObjectList<?>[]{rom.getFieldDataList(),rom.getFieldFormulaList(),rom.getFieldRunningTotalList(),rom.getFieldGroupNameList(),rom.getFieldGroupIndexList(),rom.getFieldReportParameterList(),rom.getFieldDataSetParameterList(),gom.getFieldGlobalParameterList(),gom.getFieldGlobalSpecialList(),gom.getFieldConditionList()}) if(l!=null) for(int j=0;j<l.size();j++) k.add(l.get(j));
    // embedded subreports (리포트 서브섹션 / 서브리포트 컨트롤) own their dataset-parameter fields (LinkedField2 targets)
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList(); java.util.List<Object> subs=new java.util.ArrayList<>();
    for(int i=0;i<secs.size();i++){ RexObjectList<SubSection> ss=secs.get(i).getSubSectionList(); for(int j=0;j<ss.size();j++){ Object r=go(ss.get(j),"getSubreport"); if(r!=null) subs.add(r); } }
    for(Object[] e: allControls(rf)){ Object r=go(e[3],"getSubreport"); if(r!=null) subs.add(r); }
    for(Object r: subs){ Object srom=go(r,"getReportObjectManager"); if(srom==null) continue; for(String gname: new String[]{"getFieldDataSetParameterList","getFieldDataList","getFieldFormulaList","getFieldRunningTotalList","getFieldGroupNameList","getFieldReportParameterList"}){ Object l=go(srom,gname); if(l instanceof RexObjectList) for(int j=0;j<((RexObjectList<?>)l).size();j++) k.add(((RexObjectList<?>)l).get(j)); } }
    return k; }
  @SuppressWarnings("unchecked")
  static String validate(JSONObject args) throws Exception {
    String path=s(args,"path"); TheReportFile rf=open(path); GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); var rom=rf.getGlobe().getMainReport().getReportObjectManager(); RexObjectList<DataSet> dss=gom.getDataSetList();
    java.util.List<String> err=new java.util.ArrayList<>(), warn=new java.util.ArrayList<>(), info=new java.util.ArrayList<>();
    // 1) dangling bindings
    java.util.Set<Object> known=knownFields(rf); java.util.List<Object[]> all=new java.util.ArrayList<>(); collectAllFieldRefs(rf.getGlobe(),all,Collections.newSetFromMap(new IdentityHashMap<>()),"",0);
    int nRefs=0; for(Object[] r: all){ nRefs++; String at=String.valueOf(r[0]); if(r[1]==null) err.add("바인딩 비어 있음: "+at); else if(!known.contains(r[1])){ if(at.endsWith(".LinkedField2")) info.add("서브리포트 매개변수 링크 대상 확인 불가: "+at+" → '"+nameOf(r[1])+"'"); else err.add("존재하지 않는 필드에 바인딩: "+at+" → '"+nameOf(r[1])+"' ("+fieldKindKo(r[1])+", 필드 목록에 없음)"); } }
    // 2) formulas
    RexObjectList<FieldFormula> fl=(RexObjectList<FieldFormula>) rom.getFieldFormulaList();
    for(int i=0;i<fl.size();i++){ FieldFormula f=fl.get(i); String sc=q(f.getScript());
      if(sc.contains("#unknown#")) err.add("공식 "+f.getName()+": 끊어진 참조 #unknown#");
      if(!java.util.regex.Pattern.compile("\\breturn\\b").matcher(sc).find()) warn.add("공식 "+f.getName()+": return 없음");
      java.util.regex.Matcher m=java.util.regex.Pattern.compile("[\"']([a-zA-Z]+)\\.([A-Za-z0-9_가-힣]+)[\"']").matcher(sc); java.util.Set<String> miss=new java.util.TreeSet<>();
      while(m.find()){ String ns=m.group(1).toLowerCase(), fn=m.group(2); if((ns.equals("data")||ns.equals("formula")||ns.equals("parameter")||ns.equals("runningtotal")) && findField(rf,fn)==null) miss.add(ns+"."+fn); }
      if(!miss.isEmpty()) err.add("공식 "+f.getName()+": 없는 필드 참조 "+miss); }
    // 3) groups
    RexObjectList<Group> gl=rom.getGroupList(); for(int i=0;i<gl.size();i++){ Field gf=gl.get(i).getGroupingField(); if(gf==null) err.add("그룹["+i+"]: 그룹 필드 없음(null)"); else if(!known.contains(gf)) err.add("그룹["+i+"]: 존재하지 않는 필드 '"+gf.getName()+"'"); }
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList(); int gh=0,gfo=0; for(int i=0;i<secs.size();i++){ if(secs.get(i) instanceof SectionGroupHeader){ gh++; if(((SectionGroupHeader)secs.get(i)).getGroup()==null) err.add("그룹 머리글["+i+"]: 연결된 그룹 없음"); } if(secs.get(i) instanceof SectionGroupFooter) gfo++; }
    if(gh!=gfo) warn.add("그룹 머리글("+gh+")/바닥글("+gfo+") 수 불일치"); if(gh!=gl.size()) warn.add("그룹 정의("+gl.size()+")와 그룹 머리글("+gh+") 수 불일치");
    // 4) parameters
    java.util.Set<String> declared=declaredParams(rf); java.util.Set<String> usedAll=new java.util.TreeSet<>();
    for(int i=0;i<dss.size();i++){ DataSetItemNormal n=dss.get(i).getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL(); if(qm==null) continue; String raw=q(qm.getQueryString());
      if(raw.trim().isEmpty()) warn.add("데이터셋 "+dss.get(i).getName()+": 쿼리 비어 있음");
      for(String u: usedParams(raw)){ usedAll.add(u.toUpperCase()); if(!declared.contains(u.toUpperCase())) err.add("데이터셋 "+dss.get(i).getName()+": 미선언 매개변수 {parameter."+u+"}"); }
      boolean js=qm.getScriptType()==ScriptType.JavaScript; if(js && !looksLikeJsQuery(raw) && !raw.trim().isEmpty()) warn.add("데이터셋 "+dss.get(i).getName()+": scriptType=JavaScript 인데 평문 SQL 로 보임"); if(!js && looksLikeJsQuery(raw)) warn.add("데이터셋 "+dss.get(i).getName()+": scriptType=NotScript 인데 JavaScript 로 보임");
      if(js && !raw.trim().isEmpty() && !hasReturn(raw)) err.add("데이터셋 "+dss.get(i).getName()+": JavaScript 쿼리에 return 문이 없음 — 마지막 줄에 `return sql;` 이 없으면 에러 없이 0건 (crf_set_query 로 교체)");
      String plain=js?jsToPlainSql(raw):raw; java.util.List<String> cols=CrfGen2.parseColumns(CrfGen2.stripComments(plain)); if(!cols.isEmpty()){ java.util.Set<String> cu=new java.util.HashSet<>(); for(String c: cols) cu.add(c.toUpperCase()); java.util.Set<String> fu=new java.util.HashSet<>(); RexObjectList<?> fds=dss.get(i).getFieldDataList(); java.util.List<String> notInQuery=new java.util.ArrayList<>(); for(int j=0;j<fds.size();j++){ String fn=nameOf(fds.get(j)); fu.add(fn.toUpperCase()); if(!cu.contains(fn.toUpperCase())) notInQuery.add(fn); }
        java.util.List<String> notInFields=new java.util.ArrayList<>(); for(String c: cols) if(!c.matches("COL_\\d+")&&!fu.contains(c.toUpperCase())) notInFields.add(c);
        if(!notInQuery.isEmpty()) info.add("데이터셋 "+dss.get(i).getName()+": 쿼리 SELECT 에 없는 필드 "+notInQuery); if(!notInFields.isEmpty()) warn.add("데이터셋 "+dss.get(i).getName()+": 필드로 없는 SELECT 컬럼 "+notInFields+" (crf_sync_fields)"); } }
    RexObjectList<?> gp=gom.getFieldGlobalParameterList(); for(int i=0;i<gp.size();i++){ Field pf=(Field)gp.get(i); if(!usedAll.contains(pf.getName().toUpperCase()) && refsOf(rf,pf).isEmpty()) info.add("매개변수 "+pf.getName()+": 어디에도 사용되지 않음"); }
    // 5) duplicate names
    java.util.Map<String,java.util.List<String>> names=new java.util.TreeMap<>(); for(int i=0;i<dss.size();i++){ RexObjectList<?> fds=dss.get(i).getFieldDataList(); for(int j=0;j<fds.size();j++) names.computeIfAbsent(nameOf(fds.get(j)).toUpperCase(),k->new java.util.ArrayList<>()).add("데이터("+dss.get(i).getName()+")"); }
    for(RexObjectList<?> l: new RexObjectList<?>[]{rom.getFieldFormulaList(),rom.getFieldRunningTotalList(),gom.getFieldGlobalParameterList()}) if(l!=null) for(int j=0;j<l.size();j++) names.computeIfAbsent(nameOf(l.get(j)).toUpperCase(),k->new java.util.ArrayList<>()).add(fieldKindKo(l.get(j)));
    for(java.util.Map.Entry<String,java.util.List<String>> e: names.entrySet()){ java.util.List<String> v=e.getValue(); boolean sameDs=v.size()>1 && new java.util.HashSet<>(v).size()<v.size(); boolean crossKind=v.size()>1 && !v.stream().allMatch(x->x.startsWith("데이터(")); if(sameDs) warn.add("중복 이름 "+e.getKey()+": "+v); else if(crossKind) info.add("같은 이름이 여러 종류에 있음 "+e.getKey()+": "+v+" (공식/매개변수 참조 시 혼동 주의)"); }
    // 6) hidden / subreport links
    for(int i=0;i<secs.size();i++){ RexObjectList<SubSection> ss=secs.get(i).getSubSectionList(); for(int j=0;j<ss.size();j++){ SubSection sb=ss.get(j); String band=secs.get(i).getClass().getSimpleName().replace("Section",""); if(!sb.getVisible()) info.add(band+" sub["+j+"] \""+sb.getName()+"\" 숨김");
      Object wl=go(sb,"getLinkedSubreportPath"); String url=g(wl,"getUrlText"); if(url!=null&&!url.isEmpty()&&!url.matches("(?i)https?://.*")){ File f=new File(new File(path).getAbsoluteFile().getParentFile(),url); if(!f.isFile()) warn.add(band+" sub["+j+"]: 링크 서브리포트 파일 없음 "+url+" (기준 "+new File(path).getAbsoluteFile().getParent()+")"); } } }
    for(Object[] e: allControls(rf)){ Control c=(Control)e[3]; if(!c.getVisible()) info.add(((Section)e[0]).getClass().getSimpleName().replace("Section","")+": 컨트롤 \""+c.getName()+"\" 숨김"); if(c instanceof ControlSubreport){ String url=g(((ControlSubreport)c).getLinkedSubreportPath(),"getUrlText"); if(url!=null&&!url.isEmpty()&&!url.matches("(?i)https?://.*")){ File f=new File(new File(path).getAbsoluteFile().getParentFile(),url); if(!f.isFile()) warn.add("서브리포트 \""+c.getName()+"\": 링크 파일 없음 "+url); } } }
    // 7) 글꼴: System(SDK 기본) 글꼴, 라벨/데이터 글꼴 불일치, 글꼴 종류
    Object[] fp=fontProfile(rf); @SuppressWarnings("unchecked") java.util.Map<String,Integer> lf=(java.util.Map<String,Integer>)fp[0], df=(java.util.Map<String,Integer>)fp[1]; @SuppressWarnings("unchecked") java.util.List<String> sysL=(java.util.List<String>)fp[3];
    if(!sysL.isEmpty()) warn.add("System(SDK 기본) 글꼴 "+sysL.size()+"곳 — 다른 리포트처럼 글꼴을 맞추세요: crf_set_font(font=…, only_system=true)"+refsText(sysL,6));
    String lTop=topKey(lf), dTop=topKey(df); if(lTop!=null&&dTop!=null&&!lTop.equals(dTop)) info.add("라벨(글자) 글꼴 "+lTop+" 과 데이터(숫자) 글꼴 "+dTop+" 이 다름 — 의도한 게 아니면 crf_set_font 로 통일");
    java.util.Set<String> allF=new java.util.TreeSet<>(lf.keySet()); allF.addAll(df.keySet());
    if(allF.size()>1){ @SuppressWarnings("unchecked") java.util.Map<String,java.util.List<String>> fw=(java.util.Map<String,java.util.List<String>>)fp[4]; java.util.Map<String,Integer> tot=new java.util.LinkedHashMap<>(); for(java.util.Map.Entry<String,Integer> e: lf.entrySet()) tot.merge(e.getKey(),e.getValue(),Integer::sum); for(java.util.Map.Entry<String,Integer> e: df.entrySet()) tot.merge(e.getKey(),e.getValue(),Integer::sum);
      String main=topKey(tot); StringBuilder mb=new StringBuilder(); for(String f: allF){ if(f.equals(main)) continue; java.util.List<String> at=fw.getOrDefault(f,java.util.Collections.emptyList()); mb.append("\n   - ").append(f).append(" ").append(at.size()).append("곳: ").append(String.join(", ",at.subList(0,Math.min(4,at.size())))).append(at.size()>4?" …":""); }
      info.add("글꼴 "+allF.size()+"종 섞여 있음 — 주 글꼴 "+main+"("+tot.get(main)+"곳), 그 외:"+mb+"\n   → 의도한 게 아니면 crf_set_font(font="+main+") 로 통일"); }
    // 8) 같은 스타일로 세로 연속인 글상자 → 요소 하나로 (정적 텍스트만이면 WARN, 필드 바인딩 섞이면 INFO)
    for(String x: stackedLabelRuns(rf)){ if(x.startsWith("!")) warn.add(x.substring(1)); else info.add(x); }
    // 9) 엑셀 격자: 머리글 밴드 표의 열 경계가 본문 표 경계의 부분집합인지
    for(String x: excelGridIssues(rf)){ if("true".equalsIgnoreCase(s(args,"excel"))) warn.add(x); else info.add(x); }
    StringBuilder b=new StringBuilder(new File(path).getName()+" — ERROR "+err.size()+" / WARN "+warn.size()+" / INFO "+info.size()+"  (바인딩 "+nRefs+"곳, 공식 "+fl.size()+", 그룹 "+gl.size()+", 데이터셋 "+dss.size()+")\n");
    for(String x: err) b.append("  ✖ ").append(x).append("\n"); for(String x: warn) b.append("  ⚠ ").append(x).append("\n"); for(String x: info) b.append("  ℹ ").append(x).append("\n");
    if(err.isEmpty()&&warn.isEmpty()) b.append("  ✔ 문제 없음\n");
    return b.toString();
  }
  static String refsText(java.util.List<String> refs,int max){ StringBuilder b=new StringBuilder(); for(int i=0;i<refs.size()&&i<max;i++) b.append("\n   - ").append(refs.get(i)); if(refs.size()>max) b.append("\n   … +").append(refs.size()-max); return b.toString(); }
  /** Locate a field by name across datasets / formulas / running totals / parameters; dataset selector disambiguates. */
  static Field locateField(TheReportFile rf,String name,String dsSel,StringBuilder err){
    if(name==null||name.trim().isEmpty()){ err.append("name 인자가 비어 있습니다"); return null; } name=name.trim();
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); var rom=rf.getGlobe().getMainReport().getReportObjectManager(); RexObjectList<DataSet> dss=gom.getDataSetList();
    if(dsSel!=null&&!dsSel.trim().isEmpty()){ DataSet ds=datasetOf(rf,dsSel); FieldData f=findDataField(ds,name); if(f==null) err.append("데이터셋 "+ds.getName()+" 에 필드 '"+name+"' 없음"); return f; }
    java.util.List<Field> found=new java.util.ArrayList<>(); java.util.List<String> where=new java.util.ArrayList<>();
    for(int i=0;i<dss.size();i++){ FieldData f=findDataField(dss.get(i),name); if(f!=null){ found.add(f); where.add("데이터셋 "+dss.get(i).getName()); } }
    for(RexObjectList<?> l: new RexObjectList<?>[]{rom.getFieldFormulaList(),rom.getFieldRunningTotalList(),rom.getFieldGroupNameList(),gom.getFieldGlobalParameterList()}) if(l!=null) for(int i=0;i<l.size();i++) if(name.equalsIgnoreCase(nameOf(l.get(i)))){ found.add((Field)l.get(i)); where.add(fieldKindKo(l.get(i))+" 필드"); }
    if(found.isEmpty()){ err.append("필드 '"+name+"' 없음 (crf_summary 로 이름 확인)"); return null; }
    if(found.size()>1){ err.append("'"+name+"' 이 여러 곳에 있습니다: "+where+" — dataset 인자로 지정하세요"); return null; }
    return found.get(0);
  }
  /** Remove a field object from whichever list holds it. */
  static boolean removeFromLists(TheReportFile rf,Field f){
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); var rom=rf.getGlobe().getMainReport().getReportObjectManager(); RexObjectList<DataSet> dss=gom.getDataSetList();
    java.util.List<RexObjectList<?>> lists=new java.util.ArrayList<>(); for(int i=0;i<dss.size();i++) lists.add(dss.get(i).getFieldDataList());
    lists.add(rom.getFieldFormulaList()); lists.add(rom.getFieldRunningTotalList()); lists.add(rom.getFieldGroupNameList()); lists.add(rom.getFieldGroupIndexList()); lists.add(gom.getFieldGlobalParameterList()); lists.add(rom.getFieldReportParameterList());
    for(RexObjectList<?> l: lists){ if(l==null) continue; for(int i=0;i<l.size();i++) if(l.get(i)==f){ l.remove(i); return true; } }
    return false;
  }
  static String fieldRefs(JSONObject args) throws Exception {
    TheReportFile rf=open(s(args,"path")); StringBuilder err=new StringBuilder(); Field f=locateField(rf,s(args,"name"),s(args,"dataset"),err); if(f==null) return "ERROR: "+err;
    java.util.List<String> refs=refsOf(rf,f);
    return fieldKindKo(f)+" 필드 '"+f.getName()+"' 참조 "+refs.size()+"곳"+(refs.isEmpty()?" (없음 — 삭제해도 안전)":":"+refsText(refs,60));
  }
  static String renameField(JSONObject args) throws Exception {
    String path=s(args,"path"), nn=s(args,"new_name"), output=s(args,"output");
    if(nn==null||nn.trim().isEmpty()) return "ERROR: new_name 인자가 비어 있습니다"; nn=nn.trim();
    TheReportFile rf=open(path); StringBuilder err=new StringBuilder(); Field f=locateField(rf,s(args,"name"),s(args,"dataset"),err); if(f==null) return "ERROR: "+err;
    if(nn.equalsIgnoreCase(f.getName())) return "ERROR: 이름이 같습니다";
    Field dup=findField(rf,nn); if(dup!=null) return "ERROR: 이름 중복 — '"+nn+"' 은 이미 "+fieldKindKo(dup)+" 필드로 존재합니다";
    String old=f.getName(), ns=nsOf(f); java.util.List<String> refs=refsOf(rf,f); f.setName(nn);
    int scripts=0, queries=0; var rom=rf.getGlobe().getMainReport().getReportObjectManager();
    java.util.regex.Pattern sp=scriptRefPattern(ns,old); RexObjectList<?> fl=rom.getFieldFormulaList();
    for(int i=0;i<fl.size();i++){ FieldFormula ff=(FieldFormula)fl.get(i); String sc=q(ff.getScript()); java.util.regex.Matcher m=sp.matcher(sc); if(m.find()){ ff.setScript(m.replaceAll("$1"+ns+"."+java.util.regex.Matcher.quoteReplacement(nn)+"$1")); scripts++; } }
    if(ns.equals("parameter")){ java.util.regex.Pattern pp=paramTokenPattern(old); RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
      for(int i=0;i<dss.size();i++){ DataSetItemNormal n=dss.get(i).getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL(); if(qm==null) continue; String qs=q(qm.getQueryString()); java.util.regex.Matcher m=pp.matcher(qs); if(m.find()){ qm.setQueryString(m.replaceAll(java.util.regex.Matcher.quoteReplacement("{parameter."+nn+"}"))); queries++; } } }
    RexObjectList<?> gn=rom.getFieldGroupNameList(); if(gn!=null) for(int i=0;i<gn.size();i++){ Field g=(Field)gn.get(i); if(q(g.getName()).contains("["+old+"]")) g.setName(g.getName().replace("["+old+"]","["+nn+"]")); }
    String wrote=save(rf,output,path);
    TheReportFile v=open(output); if(findField(v,nn)==null) return "ERROR: 저장 후 되읽기 검증 실패 — 새 이름이 반영되지 않음";
    return "OK: "+fieldKindKo(f)+" 필드 '"+old+"' → '"+nn+"' (객체 바인딩 "+refs.size()+"곳은 자동 추종, 공식 스크립트 "+scripts+"개"+(ns.equals("parameter")?", 쿼리 "+queries+"개":"")+" 재작성), wrote "+wrote+(refs.isEmpty()?"":refsText(refs,20));
  }
  static String removeField(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"); boolean force="true".equalsIgnoreCase(s(args,"force"));
    TheReportFile rf=open(path); StringBuilder err=new StringBuilder(); Field f=locateField(rf,s(args,"name"),s(args,"dataset"),err); if(f==null) return "ERROR: "+err;
    if(nsOf(f).equals("parameter")) return "ERROR: 매개변수는 crf_remove_param 으로 삭제하세요";
    if(nsOf(f).equals("groupname")||nsOf(f).equals("system")) return "ERROR: "+fieldKindKo(f)+" 필드는 삭제 대상이 아닙니다";
    java.util.List<String> refs=refsOf(rf,f);
    if(!refs.isEmpty()&&!force) return "ERROR: '"+f.getName()+"' 은 "+refs.size()+"곳에서 참조됩니다 — 먼저 바인딩을 바꾸거나 force=true:"+refsText(refs,40);
    int ownerDs=-1; { RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList(); for(int i=0;i<dss.size()&&ownerDs<0;i++){ RexObjectList<?> fl=dss.get(i).getFieldDataList(); for(int j=0;j<fl.size();j++) if(fl.get(j)==f){ ownerDs=i; break; } } }
    if(!removeFromLists(rf,f)) return "ERROR: 필드 목록에서 찾지 못함";
    String wrote=save(rf,output,path);
    TheReportFile v=open(output); Field left; if(f instanceof FieldData && ownerDs>=0){ RexObjectList<DataSet> vd=v.getGlobe().getGlobalObjectManager().getDataSetList(); left=ownerDs<vd.size()?findDataField(vd.get(ownerDs),f.getName()):null; } else left=findFieldOfKind(v,f.getName(),nsOf(f));
    if(left!=null) return "ERROR: 저장 후 되읽기 검증 실패 — "+fieldKindKo(f)+" 필드 '"+f.getName()+"' 이 남아 있음";
    Field same=findField(v,f.getName()); String note=same!=null?"\nℹ 같은 이름의 "+fieldKindKo(same)+" 필드는 그대로 있습니다":"";
    return "OK: "+fieldKindKo(f)+" 필드 '"+f.getName()+"' 삭제, wrote "+wrote+note+(refs.isEmpty()?"":"\n⚠ force 삭제 — 다음 참조가 끊어졌습니다(디자이너에서 정리 필요):"+refsText(refs,40));
  }
  static String setParam(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), output=s(args,"output"), type=s(args,"type"), def=s(args,"default"), prompt=s(args,"prompt");
    if(name==null||name.trim().isEmpty()) return "ERROR: name 인자가 비어 있습니다"; name=name.trim();
    TheReportFile rf=open(path); RexObjectList<?> gp=rf.getGlobe().getGlobalObjectManager().getFieldGlobalParameterList(); Object ex=null; for(int i=0;i<gp.size();i++) if(name.equalsIgnoreCase(nameOf(gp.get(i)))) ex=gp.get(i);
    DataType dt=null; if(type!=null&&!type.trim().isEmpty()){ try{ dt=DataType.valueOf(type.trim()); }catch(Exception e){ return "ERROR: type 은 String|Number|Currency|DateTime|Boolean"; } }
    String did;
    if(ex!=null){ Field other=findField(rf,name); if(other!=null && other!=ex) return "ERROR: 이름 중복 — '"+name+"' 은 "+fieldKindKo(other)+" 필드로도 존재";
      if(dt!=null) call(ex,"setDataType",DataType.class,dt); if(def!=null) call(ex,"setDefaultValue",String.class,def); if(prompt!=null) call(ex,"setPrompt",String.class,prompt);
      did="updated '"+nameOf(ex)+"'"; }
    else { Field other=findField(rf,name); if(other!=null) return "ERROR: 이름 중복 — '"+name+"' 은 이미 "+fieldKindKo(other)+" 필드로 존재";
      addGlobalParam(rf,name,dt==null?nullType():dt,def,prompt); did="created '"+name+"'"; }
    String wrote=save(rf,output,path);
    Object v=null; RexObjectList<?> vp=open(output).getGlobe().getGlobalObjectManager().getFieldGlobalParameterList(); for(int i=0;i<vp.size();i++) if(name.equalsIgnoreCase(nameOf(vp.get(i)))) v=vp.get(i);
    if(v==null) return "ERROR: 저장 후 되읽기 검증 실패";
    return "OK: 매개변수 "+did+" type="+g(v,"getDataType")+" default=\""+q(g(v,"getDefaultValue"))+"\" prompt=\""+q(g(v,"getPrompt"))+"\", wrote "+wrote;
  }
  static String paramNames(TheReportFile rf){ RexObjectList<?> gp=rf.getGlobe().getGlobalObjectManager().getFieldGlobalParameterList(); StringBuilder b=new StringBuilder(); for(int i=0;i<gp.size();i++) b.append(i>0?", ":"").append(nameOf(gp.get(i))); return b.toString(); }
  static String removeParam(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), output=s(args,"output"); boolean force="true".equalsIgnoreCase(s(args,"force"));
    if(name==null||name.trim().isEmpty()) return "ERROR: name 인자가 비어 있습니다"; name=name.trim();
    TheReportFile rf=open(path); RexObjectList<?> gp=rf.getGlobe().getGlobalObjectManager().getFieldGlobalParameterList(); Field f=null; for(int i=0;i<gp.size();i++) if(name.equalsIgnoreCase(nameOf(gp.get(i)))) f=(Field)gp.get(i);
    if(f==null){ if("true".equalsIgnoreCase(s(args,"ignore_missing"))){ String wrote=save(rf,output,path); return "OK: 매개변수 '"+name+"' 없음 — 변경 없이 저장(ignore_missing), wrote "+wrote; } return "ERROR: 매개변수 '"+name+"' 없음 (있는 매개변수: "+paramNames(rf)+"; 배치에서 없어도 넘어가려면 ignore_missing=true)"; }
    java.util.List<String> refs=refsOf(rf,f);
    if(!refs.isEmpty()&&!force) return "ERROR: 매개변수 '"+f.getName()+"' 은 "+refs.size()+"곳에서 참조됩니다 — force=true 로 강행 가능:"+refsText(refs,40);
    removeFromLists(rf,f); String wrote=save(rf,output,path);
    return "OK: 매개변수 '"+f.getName()+"' 삭제, wrote "+wrote+(refs.isEmpty()?"":"\n⚠ force 삭제 — 끊어진 참조:"+refsText(refs,40));
  }
  @SuppressWarnings("unchecked")
  static String addDataset(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"), sql=s(args,"sql"), output=s(args,"output");
    if(name==null||name.trim().isEmpty()) return "ERROR: name 인자가 비어 있습니다"; name=name.trim();
    if(sql==null||sql.trim().isEmpty()) return "ERROR: sql 인자가 비어 있습니다";
    TheReportFile rf=open(path); RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    for(int i=0;i<dss.size();i++) if(name.equalsIgnoreCase(dss.get(i).getName())) return "ERROR: 데이터셋 이름 중복: "+dss.get(i).getName();
    if(dss.size()==0) return "ERROR: 복제할 기존 데이터셋(연결 정보)이 없습니다";
    DataSet first=dss.get(0); DataSet ds=new DataSet(); ds.setName(name); ds.setDataSetType(first.getDataSetType());
    DataSetItemNormal in=ds.getDataSetItemNormal(); if(in==null) return "ERROR: 새 데이터셋 구조 생성 실패(SDK)";
    DataSetItemNormal fn=first.getDataSetItemNormal(); if(fn!=null){ in.setLinkedConnection(fn.getLinkedConnection()); in.setDataAccessMethod(fn.getDataAccessMethod()); }
    if(in.getDataAccessMethodSQL()==null) return "ERROR: 새 데이터셋에 SQL 접근방식이 없습니다(SDK)";
    QueryApply r=applyQuery(rf,ds,sql,s(args,"script_type"),true,"add");
    dss.add(ds); String wrote=save(rf,output,path);
    TheReportFile v=open(output); RexObjectList<DataSet> vd=v.getGlobe().getGlobalObjectManager().getDataSetList(); DataSet vds=null; for(int i=0;i<vd.size();i++) if(name.equalsIgnoreCase(vd.get(i).getName())) vds=vd.get(i);
    if(vds==null) return "ERROR: 저장 후 되읽기 검증 실패 — 데이터셋이 없음";
    return "OK: 데이터셋 '"+name+"' 추가 (DS["+vd.indexOf(vds)+"], scriptType "+r.after+", 연결="+nameOf(fn==null?null:fn.getLinkedConnection())+", 필드 "+vds.getFieldDataList().size()+"개: "+fieldList(vds)+"), wrote "+wrote+applySummary(r)+"\n(서브리포트/표에 바인딩하려면 crf_set_cell / crf_add_label 로 이 데이터셋 필드를 지정)";
  }
  static String removeDataset(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"); boolean force="true".equalsIgnoreCase(s(args,"force"));
    if(s(args,"dataset")==null||s(args,"dataset").trim().isEmpty()) return "ERROR: dataset 인자가 비어 있습니다";
    TheReportFile rf=open(path); RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList(); DataSet ds=datasetOf(rf,s(args,"dataset"));
    if(dss.size()<=1) return "ERROR: 마지막 데이터셋은 삭제할 수 없습니다";
    java.util.List<String> refs=new java.util.ArrayList<>(); RexObjectList<?> fl=ds.getFieldDataList(); for(int i=0;i<fl.size();i++){ for(String r: refsOf(rf,(Field)fl.get(i))) refs.add(nameOf(fl.get(i))+" ← "+r); }
    if(!refs.isEmpty()&&!force) return "ERROR: 데이터셋 "+ds.getName()+" 의 필드가 "+refs.size()+"곳에서 참조됩니다 — force=true 로 강행 가능:"+refsText(refs,40);
    dss.remove(dss.indexOf(ds)); String wrote=save(rf,output,path);
    TheReportFile v=open(output); return "OK: 데이터셋 '"+ds.getName()+"' 삭제 (남은 데이터셋 "+v.getGlobe().getGlobalObjectManager().getDataSetList().size()+"), wrote "+wrote+(refs.isEmpty()?"":"\n⚠ force 삭제 — 끊어진 참조:"+refsText(refs,40));
  }
  /** Bind {parameter.X}/{dataset.X} for a metadata-only execution: quoted tokens -> 'value' or '', bare tokens -> value or NULL. */
  static String bindForExec(String sql,java.util.Map<String,String> params){
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("'\\{(parameter|dataset)\\.([A-Za-z0-9_]+)\\}'").matcher(sql); StringBuffer b=new StringBuffer();
    while(m.find()){ String v=params==null?null:params.get(m.group(2)); if(v==null&&params!=null) v=params.get(m.group(2).toUpperCase()); m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement("'"+(v==null?"":v.replace("'","''"))+"'")); } m.appendTail(b);
    m=java.util.regex.Pattern.compile("\\{(parameter|dataset)\\.([A-Za-z0-9_]+)\\}").matcher(b.toString()); StringBuffer c=new StringBuffer();
    while(m.find()){ String v=params==null?null:params.get(m.group(2)); if(v==null&&params!=null) v=params.get(m.group(2).toUpperCase()); m.appendReplacement(c, java.util.regex.Matcher.quoteReplacement(v==null?"NULL":v)); } m.appendTail(c);
    return c.toString();
  }
  static DataType jdbcToDataType(String typeName,int scale,String col){ String t=q(typeName).toUpperCase();
    if(t.contains("DATE")||t.contains("TIME")) return DataType.DateTime;
    if(t.contains("NUM")||t.contains("DEC")||t.contains("INT")||t.contains("FLOAT")||t.contains("DOUBLE")||t.contains("REAL")) return CrfGen2.guessType(col)==DataType.Currency?DataType.Currency:DataType.Number;
    return DataType.String; }
  @SuppressWarnings("unchecked")
  static String reorderFieldsTool(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), order=q(s(args,"order")).trim();
    TheReportFile rf=open(path); DataSet ds=datasetOf(rf,s(args,"dataset")); String before=fieldList(ds);
    java.util.List<String> cols=new java.util.ArrayList<>(); String how;
    if(order.isEmpty()||order.equalsIgnoreCase("query")){ DataAccessMethodSQL qm=ds.getDataSetItemNormal()==null?null:ds.getDataSetItemNormal().getDataAccessMethodSQL(); if(qm==null) return "ERROR: 데이터셋 "+ds.getName()+" 은 SQL 데이터셋이 아닙니다 — order 에 필드명 목록을 주세요";
      String raw=q(qm.getQueryString()); String plain=qm.getScriptType()==ScriptType.JavaScript?jsToPlainSql(raw):raw; cols=CrfGen2.parseColumns(CrfGen2.stripComments(plain));
      if(cols.isEmpty()) return "ERROR: SELECT 목록을 파싱하지 못함(SELECT * 등) — order 에 필드명 목록을 직접 주세요"; how="쿼리 SELECT 순서"; }
    else { for(String n: order.split(",")){ n=n.trim(); if(n.isEmpty()) continue; if(findDataField(ds,n)==null) return "ERROR: 필드 없음: "+n+" (있는 필드: "+before+")"; cols.add(n); } how="지정 순서"; }
    if(how.startsWith("쿼리")){ java.util.List<String> missing0=new java.util.ArrayList<>(); for(String c: cols) if(findDataField(ds,c)==null) missing0.add(c); if(!missing0.isEmpty()) return "ERROR: 쿼리 컬럼 "+missing0+" 에 해당하는 필드가 없어 순서만 바꾸면 뒤 컬럼 위치가 밀립니다 — crf_sync_fields(필드 추가+재정렬) 를 쓰세요"; }
    java.util.List<String> orphan=new java.util.ArrayList<>(); boolean changed=reorderFields(ds,cols,orphan);
    String wrote=save(rf,output,path); DataSet v=datasetOf(open(output),String.valueOf(indexOfDataset(rf,ds))); String after=fieldList(v);
    StringBuilder b=new StringBuilder((changed?"OK: 필드 순서 재정렬(":"OK: 이미 원하는 순서(")+how+"), wrote "+wrote);
    b.append("\n전: ").append(before).append("\n후: ").append(after);
    java.util.List<String> missing=new java.util.ArrayList<>(); for(String c: cols) if(findDataField(v,c)==null) missing.add(c);
    if(!missing.isEmpty()) b.append("\n⚠ 쿼리 컬럼인데 필드가 없음(위치가 밀림): ").append(missing).append(" — crf_sync_fields 로 추가");
    if(!orphan.isEmpty() && how.startsWith("쿼리")) b.append("\n⚠ SELECT 에 없는 필드는 끝으로(대응 컬럼이 없어 값이 빔): ").append(orphan);
    return b.toString();
  }
  static int indexOfDataset(TheReportFile rf,DataSet ds){ RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList(); for(int i=0;i<dss.size();i++) if(dss.get(i)==ds) return i; return 0; }
  /** 쿼리를 SELECT * FROM (…) WHERE 1=0 로 실행해 ResultSetMetaData 에서 컬럼명→DataType 을 얻는다(순서 유지). 필드명으로 못 쓰는 컬럼은 skipped 에. */
  static java.util.Map<String,DataType> dbMeta(String plain,java.util.Map<String,String> params,java.util.List<String> skipped) throws Exception {
    java.util.Map<String,DataType> types=new java.util.LinkedHashMap<>();
    String body=bindForExec(CrfGen2.stripComments(plain),params).trim().replaceAll(";\\s*$","");
    String exec="SELECT * FROM (\n"+body+"\n) WHERE 1=0";
    try(Connection c=db(); Statement st=c.createStatement()){ try{ st.setQueryTimeout(DB_TIMEOUT_SEC); st.setMaxRows(1); }catch(Throwable t){}
      try(ResultSet rs=st.executeQuery(exec)){ ResultSetMetaData md=rs.getMetaData(); java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=1;i<=md.getColumnCount();i++){ String raw=md.getColumnLabel(i), c2=raw;
          if(!validIdent(c2)||seen.contains(c2.toUpperCase())){ String alt="COL_"+i; int k=2; while(seen.contains(alt.toUpperCase())) alt="COL_"+i+"_"+(k++); if(skipped!=null) skipped.add(raw+(validIdent(raw)?"(중복":"(사용불가")+" → "+alt+")"); c2=alt; }   // 위치 보존: 못 쓰는/중복 이름도 자리 필드로
          seen.add(c2.toUpperCase()); types.put(c2,jdbcToDataType(md.getColumnTypeName(i),md.getScale(i),c2)); } }
    }catch(SQLException e){ throw new RuntimeException("DB 실행 실패 — "+e.getMessage().trim()+"\n(params 로 매개변수 값을 주거나 쿼리를 확인하세요; 실행한 SQL 앞부분)\n"+(exec.length()>500?exec.substring(0,500)+"…":exec)); }
    return types;
  }
  static String syncFields(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), mode=q(s(args,"mode")).trim().toLowerCase(); if(mode.isEmpty()) mode="sql";
    boolean setTypes="true".equalsIgnoreCase(s(args,"set_types")), removeUnused="true".equalsIgnoreCase(s(args,"remove_unused"));
    TheReportFile rf=open(path); DataSet ds=datasetOf(rf,s(args,"dataset"));
    DataAccessMethodSQL qm=ds.getDataSetItemNormal()==null?null:ds.getDataSetItemNormal().getDataAccessMethodSQL(); if(qm==null) return "ERROR: 데이터셋 "+ds.getName()+" 은 SQL 데이터셋이 아닙니다";
    String raw=q(qm.getQueryString()); String plain=qm.getScriptType()==ScriptType.JavaScript?jsToPlainSql(raw):raw;
    java.util.List<String> cols=new java.util.ArrayList<>(); java.util.Map<String,DataType> types=new java.util.LinkedHashMap<>(); java.util.List<String> skipped=new java.util.ArrayList<>(); String source;
    if(mode.equals("sql")){ for(String c: CrfGen2.parseColumns(CrfGen2.stripComments(plain))){ if(!validIdent(c)) skipped.add(c); else { cols.add(c); types.put(c,c.matches("COL_\\d+")?nullType():CrfGen2.guessType(c)); } }
      if(cols.isEmpty()&&skipped.isEmpty()) return "ERROR: SELECT 목록을 파싱하지 못함(SELECT * / 함수테이블 등) — mode=db 를 사용하세요"; source="SQL 파싱"; }
    else if(mode.equals("db")){ java.util.Map<String,String> params=new java.util.HashMap<>(); String pj=s(args,"params"); if(pj!=null&&!pj.trim().isEmpty()){ try{ JSONObject po=(JSONObject)P.parse(pj); for(Object k: po.keySet()) params.put(String.valueOf(k),String.valueOf(po.get(k))); }catch(Exception e){ return "ERROR: params 는 JSON 객체여야 합니다: "+e; } }
      try{ java.util.Map<String,DataType> meta=dbMeta(plain,params,skipped); cols.addAll(meta.keySet()); types.putAll(meta); }catch(RuntimeException e){ return "ERROR: "+e.getMessage(); }
      source="DB ResultSetMetaData"+(params.isEmpty()?"":" params="+params); }
    else return "ERROR: mode 는 sql|db";
    java.util.List<String> added=new java.util.ArrayList<>(), typed=new java.util.ArrayList<>(), removed=new java.util.ArrayList<>(), kept=new java.util.ArrayList<>(); java.util.Set<String> want=new java.util.HashSet<>();
    for(String c: cols){ want.add(c.toUpperCase()); FieldData f=findDataField(ds,c); if(f==null){ addDataFieldTo(ds,c,setTypes?types.get(c):nullType()); added.add(c+(setTypes?":"+types.get(c):"")); } else if(setTypes && f.getDataType()!=types.get(c)){ f.setDataType(types.get(c)); typed.add(c+":"+types.get(c)); } }
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    for(int i=fl.size()-1;i>=0;i--){ FieldData f=fl.get(i); if(want.contains(f.getName().toUpperCase())) continue; if(!removeUnused){ kept.add(f.getName()); continue; } java.util.List<String> refs=refsOf(rf,f); if(refs.isEmpty()){ fl.remove(i); removed.add(f.getName()); } else kept.add(f.getName()+"("+refs.size()+"곳 참조)"); }
    boolean reordered=!"false".equalsIgnoreCase(s(args,"reorder")) && reorderFields(ds,cols,null);
    String wrote=save(rf,output,path);
    StringBuilder b=new StringBuilder("OK: "+ds.getName()+" 필드 동기화 ("+source+") — 쿼리 컬럼 "+cols.size()+"개, wrote "+wrote);
    if(reordered) b.append("\n↕ 필드 순서를 쿼리 컬럼 순서로 재정렬(필드는 위치로 컬럼에 매핑됨)");
    b.append("\n쿼리 컬럼: "); for(String c: cols) b.append(c).append(":").append(types.get(c)).append(" ");
    if(!added.isEmpty()) b.append("\n+ 추가: ").append(added); if(!typed.isEmpty()) b.append("\n~ 타입 변경: ").append(typed);
    if(!removed.isEmpty()) b.append("\n- 제거(미참조): ").append(removed);
    if(!kept.isEmpty()) b.append("\n⚠ 쿼리에 없는 필드 유지(목록 끝, 대응 컬럼이 없어 값이 빔): ").append(kept).append(removeUnused?" (참조 중)":" (remove_unused=true 면 미참조 필드 제거)");
    if(!skipped.isEmpty()) b.append("\n⚠ 필드명으로 못 쓰는 컬럼(별칭 필요): ").append(skipped);
    b.append("\n필드("+ds.getFieldDataList().size()+"): "+fieldList(ds));
    return b.toString();
  }

  static java.util.regex.Pattern likePattern(String like){ if(like==null||like.trim().isEmpty()) return null; String l=like.trim();
    return l.contains("*")||l.contains("?") ? java.util.regex.Pattern.compile("(?i)^"+l.replace(".","\\.").replace("*",".*").replace("?",".")+"$") : java.util.regex.Pattern.compile("(?i).*"+java.util.regex.Pattern.quote(l)+".*"); }
  static java.util.List<Path> crfFiles(String dir,String like,int max) throws IOException { java.util.regex.Pattern lp=likePattern(like);
    try(java.util.stream.Stream<Path> st=Files.walk(Paths.get(dir))){ return st.filter(p->p.toString().toLowerCase().endsWith(".crf")).filter(p->lp==null||lp.matcher(p.getFileName().toString()).matches()).sorted().limit(max).collect(java.util.stream.Collectors.toList()); } }
  static String listReports(JSONObject args) throws Exception {
    String dir=s(args,"dir"), like=s(args,"like"); int limit=pInt(args.get("limit"),500);
    if(dir==null||!new File(dir).isDirectory()) return "ERROR: dir 폴더 없음: "+dir;
    java.util.List<Path> all=crfFiles(dir,like,Integer.MAX_VALUE);
    StringBuilder b=new StringBuilder("reports under "+dir+(like==null||like.isEmpty()?"":" like="+like)+": total "+all.size()+(all.size()>limit?" (showing first "+limit+", use like/limit)":"")+"\n");
    int n=0; for(Path p: all){ if(n++>=limit) break; b.append("  ").append(p).append("  (").append(Files.size(p)/1024).append(" KB)\n"); }
    return b.toString();
  }

  // ===== v0.5.0: query reading / formula reading / folder search =====
  static String q(String x){ return x==null?"":x; }
  /** JavaScript dynamic query -> plain SQL: concatenates the string literals in order; if/else blocks become /*IF cond*&#47; … /*END IF*&#47; comments. */
  static String jsToPlainSql(String js){
    StringBuilder out=new StringBuilder(); int n=js.length(), i=0; java.util.ArrayDeque<Boolean> braces=new java.util.ArrayDeque<>(); boolean pendingIf=false;
    while(i<n){ char c=js.charAt(i);
      if(c=='"'||c=='\''){ char qc=c; i++; while(i<n && js.charAt(i)!=qc){ char d=js.charAt(i); if(d=='\\' && i+1<n){ char e=js.charAt(i+1); if(e=='n') out.append('\n'); else if(e=='r'){} else if(e=='t') out.append('\t'); else out.append(e); i+=2; } else { out.append(d); i++; } } i++; continue; }
      if(c=='/' && i+1<n && js.charAt(i+1)=='/'){ int e=js.indexOf('\n',i); i=e<0?n:e; continue; }
      if(c=='/' && i+1<n && js.charAt(i+1)=='*'){ int e=js.indexOf("*/",i+2); i=e<0?n:e+2; continue; }
      if(Character.isLetter(c)||c=='_'){ int s0=i; while(i<n && (Character.isLetterOrDigit(js.charAt(i))||js.charAt(i)=='_')) i++; String w=js.substring(s0,i);
        if(w.equals("if")){ int p0=js.indexOf('(',i); if(p0>=0){ int d=0,e=p0; for(;e<n;e++){ char x=js.charAt(e); if(x=='(')d++; else if(x==')'){ d--; if(d==0)break; } } String cond=js.substring(p0+1,Math.min(e,n)).trim().replace("*/","* /"); out.append("\n/*IF ").append(cond).append(" */\n"); i=e+1; pendingIf=true; } }
        else if(w.equals("else")){ out.append("\n/*ELSE*/\n"); pendingIf=true; }
        else if(w.equals("var")){ int k=i; while(k<n&&Character.isWhitespace(js.charAt(k))) k++; if(js.startsWith("__fe",k)){ int e=js.indexOf(';',i); i=e<0?n:e+1; } continue; }   // var __feN = '{parameter.X}'.split(',') 보조 변수만 생략(var sql = "…" 는 문자열을 그대로 살림)
        else if(w.equals("for")){ int p0=js.indexOf('(',i); if(p0>=0){ int d=0,e=p0; for(;e<n;e++){ char x=js.charAt(e); if(x=='(')d++; else if(x==')'){ d--; if(d==0)break; } } out.append("\n/*FOREACH ").append(js.substring(p0+1,Math.min(e,n)).trim().replace("*/","* /")).append(" */\n"); i=e+1; pendingIf=true; } }
        continue; }
      if(c=='{'){ braces.push(pendingIf); pendingIf=false; i++; continue; }
      if(c=='}'){ boolean wasIf=!braces.isEmpty() && braces.pop(); if(wasIf){ int k=i+1; while(k<n && Character.isWhitespace(js.charAt(k))) k++; if(!js.startsWith("else",k)) out.append("\n/*END IF*/\n"); } i++; continue; }
      i++; }
    return out.toString().replaceAll("__fe\\d+\\[__i\\d+\\]","<원소>").replaceAll("[ \\t]+\n","\n").replaceAll("\n{3,}","\n\n").trim();
  }
  /** Table names referenced by FROM/JOIN/INTO/UPDATE (estimate; schema-qualified kept, DUAL dropped). */
  static java.util.Set<String> tablesOf(String sql){
    java.util.Set<String> t=new java.util.TreeSet<>(); if(sql==null) return t;
    String x=CrfGen2.stripComments(sql).replaceAll("'[^']*'","''");
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)\\b(FROM|JOIN|INTO|UPDATE)\\s+([A-Za-z_][\\w$#]*(?:\\.[A-Za-z_][\\w$#]*)?)").matcher(x);
    while(m.find()){ String nm=m.group(2).toUpperCase(); if(nm.equals("DUAL")||nm.equals("TABLE")||nm.equals("SELECT")) continue; t.add(nm); }
    java.util.regex.Matcher f=java.util.regex.Pattern.compile("(?is)\\bFROM\\s+([^;()]*?)(?=\\bWHERE\\b|\\bGROUP\\b|\\bORDER\\b|\\bHAVING\\b|\\bUNION\\b|\\bJOIN\\b|\\bCONNECT\\b|\\bSTART\\b|$)").matcher(x);
    while(f.find()){ for(String item: f.group(1).split(",")){ java.util.regex.Matcher im=java.util.regex.Pattern.compile("^([A-Za-z_][\\w$#]*(?:\\.[A-Za-z_][\\w$#]*)?)").matcher(item.trim()); if(im.find()){ String nm=im.group(1).toUpperCase(); if(!nm.equals("DUAL")&&!nm.equals("SELECT")) t.add(nm); } } }
    return t;
  }
  static java.util.Set<String> usedParams(String txt){ java.util.Set<String> u=new java.util.TreeSet<>(); java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\{parameter\\.([A-Za-z0-9_]+)\\}").matcher(q(txt)); while(m.find()) u.add(m.group(1)); return u; }
  static java.util.Set<String> declaredParams(TheReportFile rf){ java.util.Set<String> d=new java.util.TreeSet<>();
    RexObjectList<?> gp=rf.getGlobe().getGlobalObjectManager().getFieldGlobalParameterList(); for(int i=0;i<gp.size();i++) d.add(nameOf(gp.get(i)).toUpperCase());
    RexObjectList<?> rp=rf.getGlobe().getMainReport().getReportObjectManager().getFieldReportParameterList(); if(rp!=null) for(int i=0;i<rp.size();i++) d.add(nameOf(rp.get(i)).toUpperCase());
    return d; }
  static String fieldList(DataSet ds){ StringBuilder b=new StringBuilder(); RexObjectList<?> fl=ds.getFieldDataList(); for(int j=0;j<fl.size();j++){ Object f=fl.get(j); Object dt=go(f,"getDataType"); b.append(nameOf(f)); if(dt!=null&&!"Null".equals(dt.toString())) b.append(":").append(dt); b.append(j<fl.size()-1?", ":""); } return b.toString(); }

  @SuppressWarnings("unchecked")
  static String getQuery(JSONObject args) throws Exception {
    String path=s(args,"path"), sel=s(args,"dataset"), mode=q(s(args,"mode")).trim().toLowerCase(); if(mode.isEmpty()) mode="both";
    TheReportFile rf=open(path);
    RexObjectList<DataSet> dss=rf.getGlobe().getGlobalObjectManager().getDataSetList();
    java.util.List<DataSet> targets=new java.util.ArrayList<>();
    if(sel==null||sel.trim().isEmpty()||sel.trim().equals("*")) for(int i=0;i<dss.size();i++) targets.add(dss.get(i)); else targets.add(datasetOf(rf,sel));
    java.util.Set<String> declared=declaredParams(rf);
    StringBuilder b=new StringBuilder(new File(path).getName()+"  datasets="+dss.size()+(targets.size()==dss.size()?"":"  (showing "+targets.size()+")")+"  선언된 매개변수: "+declared+"\n");
    for(DataSet ds: targets){ int idx=dss.indexOf(ds);
      DataSetItemNormal n=ds.getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL();
      b.append("\n=== DS[").append(idx).append("] ").append(ds.getName()).append("  접근=").append(n==null?"?":String.valueOf(n.getDataAccessMethod())).append("  연결=").append(n==null?"-":nameOf(n.getLinkedConnection()));
      b.append("\n  필드(").append(ds.getFieldDataList().size()).append("): ").append(fieldList(ds)).append("\n");
      if(qm==null||!"SQL".equals(String.valueOf(n.getDataAccessMethod()))){ java.util.List<String[]> xp=xpathsOf(ds); if(xp.isEmpty()) b.append("  (SQL 데이터셋 아님 — 쿼리/경로 없음)\n"); else { b.append("  ----- ").append(String.valueOf(n.getDataAccessMethod())).append(" 경로(XPath) -----\n"); for(String[] e: xp) b.append("  ").append(e[0].equals("root")?"루트":e[0].equals("procedure")?"프로시저":"필드 "+e[0]).append(" = ").append(e[1]).append("\n"); } continue; }
      String raw=q(qm.getQueryString()); boolean js=qm.getScriptType()==ScriptType.JavaScript; String plain=js?jsToPlainSql(raw):raw;
      b.append("  scriptType=").append(qm.getScriptType()).append("  길이=").append(raw.length());
      RexObjectList<SQLParameter> sp=qm.getSQLParameterList(); if(sp!=null&&sp.size()>0){ b.append("  SQL매개변수: "); for(int k=0;k<sp.size();k++) b.append(k>0?", ":"").append(sp.get(k).getParameterName()).append("(").append(sp.get(k).getDataType()).append(")"); }
      RexObjectList<ScriptParameter> scp=qm.getScriptParameterList(); if(scp!=null&&scp.size()>0){ b.append("  스크립트매개변수: "); for(int k=0;k<scp.size();k++) b.append(k>0?", ":"").append(scp.get(k).getParameterName()); }
      java.util.Set<String> used=usedParams(raw); java.util.Set<String> und=new java.util.TreeSet<>(); for(String u:used) if(!declared.contains(u.toUpperCase())) und.add(u);
      b.append("\n  매개변수 사용: ").append(used.isEmpty()?"-":used.toString()).append(und.isEmpty()?"":"  ⚠미선언: "+und);
      java.util.Set<String> dsr=new java.util.TreeSet<>(); java.util.regex.Matcher dm=java.util.regex.Pattern.compile("\\{dataset\\.([A-Za-z0-9_]+)\\}").matcher(raw); while(dm.find()) dsr.add(dm.group(1)); if(!dsr.isEmpty()) b.append("  데이터셋참조: ").append(dsr);
      b.append("\n  테이블(추정): ").append(tablesOf(plain)).append("\n");
      if(!js) b.append("  ----- query -----\n").append(raw).append("\n");
      else { if(!mode.equals("plain")) b.append("  ----- query (JavaScript 원문) -----\n").append(raw).append("\n");
             if(!mode.equals("raw")) b.append("  ----- 평문 복원 (문자열 연결을 풀고 if 블록은 /*IF*/ 주석; 실제 실행 SQL 은 조건에 따라 달라짐) -----\n").append(plain).append("\n"); }
    }
    return b.toString();
  }

  @SuppressWarnings("unchecked")
  static String getFormula(JSONObject args) throws Exception {
    String path=s(args,"path"), name=s(args,"name"); String want=name==null?"":name.trim();
    TheReportFile rf=open(path); var rom=rf.getGlobe().getMainReport().getReportObjectManager();
    StringBuilder b=new StringBuilder(); int shown=0;
    RexObjectList<FieldFormula> fl=(RexObjectList<FieldFormula>) rom.getFieldFormulaList();
    for(int i=0;i<fl.size();i++){ FieldFormula f=fl.get(i); if(!want.isEmpty()&&!want.equalsIgnoreCase(f.getName())) continue; shown++;
      String sc=q(f.getScript()); b.append("== 공식 ").append(f.getName()).append(" (").append(f.getScriptType()).append(")\n").append(sc).append("\n");
      java.util.Set<String> refs=new java.util.TreeSet<>(), missing=new java.util.TreeSet<>();
      java.util.regex.Matcher m=java.util.regex.Pattern.compile("[\"']([a-zA-Z]+)\\.([A-Za-z0-9_가-힣#]+)[\"']").matcher(sc);
      while(m.find()){ String ns=m.group(1).toLowerCase(), fn=m.group(2); if(!(ns.equals("data")||ns.equals("formula")||ns.equals("parameter")||ns.equals("runningtotal")||ns.equals("system")||ns.equals("parent")||ns.equals("dataset")||ns.equals("groupname"))) continue; refs.add(ns+"."+fn);
        if(ns.equals("data")||ns.equals("formula")||ns.equals("parameter")||ns.equals("runningtotal")){ if(findField(rf,fn)==null) missing.add(ns+"."+fn); } }
      if(sc.contains("#unknown#")) missing.add("#unknown# (끊어진 참조)");
      if(!refs.isEmpty()||!missing.isEmpty()) b.append("   참조: ").append(refs).append(missing.isEmpty()?"":"  ⚠없는 필드: "+missing).append("\n");
    }
    RexObjectList<?> rt=rom.getFieldRunningTotalList(); if(rt!=null) for(int i=0;i<rt.size();i++){ Object r=rt.get(i); if(!want.isEmpty()&&!want.equalsIgnoreCase(nameOf(r))) continue; shown++;
      b.append("== 누적합산 ").append(nameOf(r)).append(": ").append(g(r,"getSummaryFunctionType")).append("(").append(nameOf(go(r,"getSummaryField"))).append(")  평가=").append(g(r,"getRunningTotalEvaluateType"));
      Object ef=go(r,"getRunningTotalEvaluateOnChangeField"); if(ef!=null) b.append(" 변경필드=").append(nameOf(ef)); Object eg=go(r,"getRunningTotalEvaluateOnChangeGroup"); if(eg!=null) b.append(" 변경그룹=").append(nameOf(go(eg,"getGroupingField")));
      b.append("  리셋=").append(g(r,"getRunningTotalResetType")); Object rfd=go(r,"getRunningTotalResetOnChangeField"); if(rfd!=null) b.append(" 리셋필드=").append(nameOf(rfd)); Object rg=go(r,"getRunningTotalResetOnChangeGroup"); if(rg!=null) b.append(" 리셋그룹=").append(nameOf(go(rg,"getGroupingField"))); b.append("\n"); }
    RexObjectList<?> gn=rom.getFieldGroupNameList(); if(gn!=null) for(int i=0;i<gn.size();i++){ Object gf=gn.get(i); if(!want.isEmpty()&&!want.equalsIgnoreCase(nameOf(gf))) continue; shown++; Object grp=go(gf,"getLinkedGroup"); b.append("== 그룹이름 ").append(nameOf(gf)).append(" → 그룹필드 ").append(grp==null?"-":nameOf(go(grp,"getGroupingField"))).append("\n"); }
    if(shown==0) return !want.isEmpty()? "ERROR: 공식/누적합산/그룹이름 필드 '"+want+"' 없음 (crf_summary 로 이름 확인)" : "(공식·누적합산·그룹이름 필드 없음)";
    return b.toString();
  }

  static String searchReports(JSONObject args) throws Exception {
    String dir=s(args,"dir"), text=s(args,"text"), scope=q(s(args,"scope")).trim().toLowerCase(), like=s(args,"like"); boolean regex="true".equalsIgnoreCase(s(args,"regex"));
    int limit=pInt(args.get("limit"),50), maxFiles=pInt(args.get("max_files"),5000);
    if(dir==null||!new File(dir).isDirectory()) return "ERROR: dir 폴더 없음: "+dir;
    if(text==null||text.trim().isEmpty()) return "ERROR: text 인자가 비어 있습니다";
    if(scope.isEmpty()) scope="any"; if(!scope.matches("query|xpath|field|formula|param|control|any|all")) return "ERROR: scope 는 query|xpath|field|formula|param|control|any";
    java.util.regex.Pattern pat; try{ pat=java.util.regex.Pattern.compile(regex?text:java.util.regex.Pattern.quote(text), java.util.regex.Pattern.CASE_INSENSITIVE); }catch(Exception e){ return "ERROR: 정규식 오류: "+e.getMessage(); }
    java.util.List<Path> files=crfFiles(dir,like,maxFiles); Path root=Paths.get(dir);
    long t0=System.currentTimeMillis(); int scanned=0, hitFiles=0, unreadable=0; StringBuilder b=new StringBuilder();
    for(Path p: files){ if(hitFiles>=limit) break; scanned++;
      TheReportFile rf; try{ rf=Rexpert4.read(p.toString()); }catch(Throwable t){ unreadable++; continue; }
      java.util.List<String> hits=new java.util.ArrayList<>(); try{ collectHits(rf,scope,pat,hits); }catch(Throwable t){ hits.add("(탐색 오류 "+t+")"); }
      if(hits.isEmpty()) continue; hitFiles++;
      b.append("● ").append(p).append("\n"); int shown=0; for(String h: hits){ if(shown++>=8){ b.append("    … +").append(hits.size()-8).append(" more\n"); break; } b.append("    ").append(h).append("\n"); } }
    String head="search '"+text+"' scope="+scope+(regex?" (regex)":"")+" under "+dir+(like==null||like.isEmpty()?"":" like="+like)+": "+hitFiles+" file(s) hit, scanned "+scanned+"/"+files.size()+(unreadable>0?" (unreadable "+unreadable+")":"")+", "+(System.currentTimeMillis()-t0)+" ms"+(hitFiles>=limit?"  ⚠ limit "+limit+" 도달 — like/limit 으로 조정":"")+"\n";
    return head+(b.length()==0?"(일치 없음)":b.toString());
  }
  /** SQL 이 아닌 데이터셋의 접근 경로: XML/JSON 루트 XPath + 필드별 경로, 저장 프로시저명. 없으면 null */
  @SuppressWarnings("unchecked")
  static java.util.List<String[]> xpathsOf(DataSet ds){
    java.util.List<String[]> out=new java.util.ArrayList<>(); DataSetItemNormal n=ds.getDataSetItemNormal(); if(n==null) return out;
    Object am=n.getDataAccessMethod(); String kind=am==null?"":String.valueOf(am);
    Object x=n.getDataAccessMethodXML(), j=n.getDataAccessMethodJSON(), sp=n.getDataAccessMethodStoredProcedure();
    if(kind.equals("XML")&&x!=null){ String rp=g(x,"getRootPath"); if(rp!=null&&!rp.isEmpty()) out.add(new String[]{"root",rp}); }
    else if(kind.equals("JSON")&&j!=null){ String rp=g(j,"getRootPath"); if(rp!=null&&!rp.isEmpty()) out.add(new String[]{"root",rp}); }
    else if(kind.equals("StoredProcedure")&&sp!=null){ String fn=g(sp,"getFunctionName"); if(fn!=null&&!fn.isEmpty()) out.add(new String[]{"procedure",fn}); }
    if(kind.equals("XML")||kind.equals("JSON")){ RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList(); for(int i=0;i<fl.size();i++){ String xp=fl.get(i).getXMLPath(); if(xp!=null&&!xp.isEmpty()) out.add(new String[]{fl.get(i).getName(),xp}); } }
    return out;
  }
  static String oneLine(String x,int max){ String y=q(x).replaceAll("\\s+"," ").trim(); return y.length()>max?y.substring(0,max)+"…":y; }
  @SuppressWarnings("unchecked")
  static void collectHits(TheReportFile rf,String scope,java.util.regex.Pattern pat,java.util.List<String> hits){
    boolean any=scope.equals("any")||scope.equals("all");
    GlobalObjectManager gom=rf.getGlobe().getGlobalObjectManager(); var rom=rf.getGlobe().getMainReport().getReportObjectManager(); RexObjectList<DataSet> dss=gom.getDataSetList();
    if(any||scope.equals("query")) for(int i=0;i<dss.size();i++){ DataSet ds=dss.get(i); DataSetItemNormal n=ds.getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL(); if(qm==null) continue;
      String raw=q(qm.getQueryString()); String txt=qm.getScriptType()==ScriptType.JavaScript?jsToPlainSql(raw):raw; int c=0;
      for(String ln: txt.split("\\r?\\n")){ if(pat.matcher(ln).find()){ if(c++<3) hits.add("[query "+ds.getName()+"] "+oneLine(ln,160)); } } if(c>3) hits.add("[query "+ds.getName()+"] … +"+(c-3)+" lines"); }
    if(any||scope.equals("query")||scope.equals("xpath")) for(int i=0;i<dss.size();i++){ DataSet ds=dss.get(i); for(String[] e: xpathsOf(ds)) if(pat.matcher(e[1]).find()||(!e[0].equals("root")&&!e[0].equals("procedure")&&pat.matcher(e[0]).find())) hits.add("[xpath "+ds.getName()+(e[0].equals("root")?"":e[0].equals("procedure")?" 프로시저":" 필드 "+e[0])+"] "+oneLine(e[1],160)); }
    if(any||scope.equals("field")){ for(int i=0;i<dss.size();i++){ RexObjectList<?> fl=dss.get(i).getFieldDataList(); for(int j=0;j<fl.size();j++) if(pat.matcher(nameOf(fl.get(j))).find()) hits.add("[field "+dss.get(i).getName()+"] "+nameOf(fl.get(j))); }
      for(RexObjectList<?> l: new RexObjectList<?>[]{rom.getFieldFormulaList(),rom.getFieldRunningTotalList(),rom.getFieldGroupNameList()}) if(l!=null) for(int j=0;j<l.size();j++) if(pat.matcher(nameOf(l.get(j))).find()) hits.add("["+fieldKindKo(l.get(j))+"필드] "+nameOf(l.get(j))); }
    if(any||scope.equals("formula")){ RexObjectList<FieldFormula> fl=(RexObjectList<FieldFormula>) rom.getFieldFormulaList(); for(int j=0;j<fl.size();j++){ String sc=q(fl.get(j).getScript()); if(pat.matcher(sc).find()) hits.add("[formula "+fl.get(j).getName()+"] "+oneLine(sc,140)); } }
    if(any||scope.equals("param")){ RexObjectList<?> gp=gom.getFieldGlobalParameterList(); for(int j=0;j<gp.size();j++) if(pat.matcher(nameOf(gp.get(j))).find()) hits.add("[param] "+nameOf(gp.get(j)));
      for(int i=0;i<dss.size();i++){ DataSetItemNormal n=dss.get(i).getDataSetItemNormal(); DataAccessMethodSQL qm=n==null?null:n.getDataAccessMethodSQL(); if(qm==null) continue; for(String u: usedParams(qm.getQueryString())) if(pat.matcher(u).find()) hits.add("[param used in "+dss.get(i).getName()+"] {parameter."+u+"}"); } }
    if(any||scope.equals("control")){ RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
      for(int i=0;i<secs.size();i++){ java.util.List<Control> cs=new java.util.ArrayList<>(); collectControls(secs.get(i),cs,Collections.newSetFromMap(new IdentityHashMap<>()),0); String band=secs.get(i).getClass().getSimpleName().replace("Section","");
        for(Control c: cs){ String nm=q(c.getName()), txt=q(g(c,"getApplyValueText")); Object f=go(c,"getApplyValueField");
          if(pat.matcher(nm).find()||pat.matcher(txt).find()||(f!=null&&pat.matcher(nameOf(f)).find())) hits.add("[control "+band+"] "+c.getClass().getSimpleName().replace("Control","")+" \""+nm+"\""+(txt.isEmpty()?"":" 텍스트=\""+oneLine(txt,80)+"\"")+(f!=null?" 필드="+nameOf(f):""));
          if("ControlTable".equals(c.getClass().getSimpleName())){ Object rc=go(c,"getRowCount"), cc=go(c,"getColumnCount"); if(rc instanceof Integer&&cc instanceof Integer) for(int r=0;r<(Integer)rc;r++) for(int k=0;k<(Integer)cc;k++){ Object cell=tableCell(c,r,k); if(!isNormalCell(cell)) continue; String ct=q(g(cell,"getApplyValueText")); Object cf=go(cell,"getApplyValueField");
            if(pat.matcher(ct).find()||(cf!=null&&pat.matcher(nameOf(cf)).find())) hits.add("[cell "+band+" "+nm+"["+r+","+k+"]] "+(cf!=null?"필드="+nameOf(cf):"\""+oneLine(ct,80)+"\"")); } } } } }
  }

  /** One-line description of a subsection: name, type, height, hidden flag; subreport subsections show link/embedded + parameter links. */
  static String subSectionInfo(SubSection sb){
    String type=sb.getClass().getSimpleName().replace("SubSection",""); if(type.equals("Default")) type="";
    StringBuilder b=new StringBuilder("\""+q(sb.getName())+"\""+(type.isEmpty()?"":" ["+type+"]")+" h="+sb.getHeight()+(sb.getVisible()?"":" [숨김]"));
    if(sb instanceof SubSectionSubreport){ SubSectionSubreport ss=(SubSectionSubreport)sb; b.append(subreportInfo(ss.getLinkedSubreportPath(), ss.getSubreport(), ss.getFieldLinkListForSubReportParameter())); }
    return b.toString();
  }
  static String subreportInfo(Object webLink,Object embedded,RexObjectList<?> links){
    StringBuilder b=new StringBuilder(); String url=g(webLink,"getUrlText");
    if(url!=null&&!url.isEmpty()) b.append(" 링크=").append(url);
    if(embedded!=null){ Object rom=go(embedded,"getReportObjectManager"); Object rd=go(embedded,"getReportDesign"); int secs=-1; try{ secs=((RexObjectList<?>)go(go(rd,"getMainPage"),"getSectionList")).size(); }catch(Exception e){} if(secs>0) b.append(" 임베디드 서브리포트(섹션 ").append(secs).append(")"); }
    if(links!=null&&links.size()>0){ b.append(" 매개변수링크: "); for(int i=0;i<links.size();i++){ Object l=links.get(i); b.append(i>0?", ":"").append(nameOf(go(l,"getLinkedField1"))).append("→").append(nameOf(go(l,"getLinkedField2"))); } }
    return b.toString();
  }
  static String groupFieldOf(Section sec){ if(sec instanceof SectionGroupHeader){ Group gp=((SectionGroupHeader)sec).getGroup(); return gp==null?"-":nameOf(gp.getGroupingField()); } return null; }

  static SubSectionDefault band(String name,int h){ SubSectionDefault sd=new SubSectionDefault(); sd.setName(name); sd.setHeight(h); sd.setVisible(true); sd.getControlListForEachSeparatedPageList().add(new ControlListForEachSeparatedPage()); return sd; }
  /** First (existing) control list of a subsection — never add a second ControlListForEachSeparatedPage (controls there may not render). */
  @SuppressWarnings("unchecked")
  static ControlListForEachSeparatedPage clpOf(SubSectionDefault sd){ RexObjectList<ControlListForEachSeparatedPage> l=sd.getControlListForEachSeparatedPageList(); if(l.size()>0) return l.get(0); ControlListForEachSeparatedPage c=new ControlListForEachSeparatedPage(); l.add(c); return c; }
  static java.util.Set<String> controlNames(TheReportFile rf){ java.util.Set<String> s=new java.util.HashSet<>(); RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    for(int i=0;i<secs.size();i++){ java.util.List<Control> cs=new java.util.ArrayList<>(); collectControls(secs.get(i),cs,Collections.newSetFromMap(new IdentityHashMap<>()),0); for(Control c:cs) if(c.getName()!=null) s.add(c.getName()); } return s; }
  static String uniqueControlName(TheReportFile rf,String base){ java.util.Set<String> used=controlNames(rf); if(!used.contains(base)) return base; for(int i=2;;i++) if(!used.contains(base+"_"+i)) return base+"_"+i; }
  static SubSectionDefault firstSub(Section sec){ RexObjectList<SubSection> ss=sec.getSubSectionList(); if(ss==null||ss.size()==0)return null; SubSection s=ss.get(0); return (s instanceof SubSectionDefault)?(SubSectionDefault)s:null; }

  @SuppressWarnings("unchecked")
  static String describeLayout(String path,boolean detail) throws Exception { return describeLayout(path,detail,false); }
  static String describeLayout(String path,boolean detail,boolean onePerLine) throws Exception {
    TheReportFile rf=open(path);
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    StringBuilder b=new StringBuilder();
    for(int i=0;i<secs.size();i++){ Section sec=secs.get(i); String gf=groupFieldOf(sec); b.append("["+i+"] "+sec.getClass().getSimpleName().replace("Section","")+(gf==null?"":"(→"+gf+")")+"\n");
      RexObjectList<SubSection> ss=sec.getSubSectionList();
      for(int j=0;j<ss.size();j++){ SubSection sb=ss.get(j); b.append("   sub["+j+"] "+subSectionInfo(sb)+"\n");
      List<Control> cs=new ArrayList<>();
      if(sb instanceof SubSectionDefault){ RexObjectList<ControlListForEachSeparatedPage> cls=((SubSectionDefault)sb).getControlListForEachSeparatedPageList(); for(int k=0;k<cls.size();k++){ RexObjectList<Control> cl=(RexObjectList<Control>)(RexObjectList<?>)cls.get(k).getControlList(); for(int x=0;x<cl.size();x++) cs.add(cl.get(x)); if(k>0&&cl.size()>0) b.append("      (컨트롤 리스트 #"+k+" — 별도 페이지 리스트)\n"); } }
      else if(!(sb instanceof SubSectionSubreport)) collectControls(sb, cs, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
      for(Control c: cs){
        Object f=go(c,"getApplyValueField"); String txt=g(c,"getApplyValueText");
        String bind="";
        if(f!=null) bind=" ["+fieldKindKo(f)+":"+nameOf(f)+"]";
        else if(txt!=null && !txt.isEmpty()) bind=" [텍스트:\""+txt+"\"]";
        String w=g(c,"getWidth"), ht=g(c,"getHeight");
        String pos="위치(왼"+g(c,"getX1")+",위"+g(c,"getY1")+",너비"+(w==null?"?":w)+",높이"+(ht==null?"?":ht)+")";
        StringBuilder ex=new StringBuilder();
        String fmt=g(c,"getOutputFormat"); if(fmt!=null && !fmt.isEmpty()) ex.append(" 출력양식="+fmt);
        if("true".equals(g(c,"getCanGrow"))) ex.append(" 확장가능");
        String font=g(go(c,"getTextInfo"),"getFontName"); if(!detail && font!=null && !font.isEmpty()) ex.append(" 폰트="+font);
        if(c instanceof ControlSubreport){ ControlSubreport sr=(ControlSubreport)c; ex.append(subreportInfo(sr.getLinkedSubreportPath(), sr.getSubreport(), sr.getFieldLinkListForSubReportParameter())); }
        if(!c.getVisible()) ex.append(" [숨김]");
        if(detail) ex.append(styleInfo(c));
        b.append("      - "+c.getClass().getSimpleName()+" \""+c.getName()+"\""+bind+"  "+pos+ex+"\n");
        if("ControlTable".equals(c.getClass().getSimpleName())){   // 표: 셀별 바인딩 그리드
          Object rc=go(c,"getRowCount"), cc=go(c,"getColumnCount");
          if(rc instanceof Integer && cc instanceof Integer){ int rows=(Integer)rc, colsN=(Integer)cc; int maxC=Math.min(colsN,60);
            for(int rr=0;rr<rows && rr<60;rr++){
              String[] vals=new String[maxC], sts=new String[maxC];
              for(int cn=0;cn<maxC;cn++){ Object cell=null; try{ cell=c.getClass().getMethod("getTableCell",int.class,int.class).invoke(c,rr,cn); }catch(Exception e){}
                Object cf=go(cell,"getApplyValueField"); String ct=g(cell,"getApplyValueText");
                String cb = !isNormalCell(cell)? "‹병합›" : cf!=null? fieldKindKo(cf)+":"+nameOf(cf) : (ct!=null && !ct.isEmpty()? "\""+ct+"\"" : "·");
                if(isNormalCell(cell) && "Checkbox".equals(String.valueOf(go(cell,"getCellContent")))) cb=checkboxText(cell);
                String cfmt=g(cell,"getOutputFormat"); if(isNormalCell(cell) && cfmt!=null && !cfmt.isEmpty()) cb+="{"+cfmt+"}";
                vals[cn]=cb; sts[cn]=detail&&isNormalCell(cell)?styleInfo(cell).trim().replace(" ", ", "):null; }
              // detail: 행에서 가장 흔한 스타일을 «공통» 으로 한 번만 쓰고, 다른 셀만 «…» 을 붙인다
              String common=null; if(detail){ java.util.Map<String,Integer> cnt=new java.util.HashMap<>(); for(String st: sts) if(st!=null&&!st.isEmpty()) cnt.merge(st,1,Integer::sum); int best=0; for(java.util.Map.Entry<String,Integer> e: cnt.entrySet()) if(e.getValue()>best){ best=e.getValue(); common=e.getKey(); } if(best<2) common=null; }
              if(onePerLine){ b.append("          ["+rr+"]"+(common!=null?" «공통: "+common+"»":"")+"\n");
                for(int cn=0;cn<maxC;cn++){ String st=sts[cn]; b.append("            ["+rr+","+cn+"] "+vals[cn]+(st!=null&&!st.isEmpty()&&!st.equals(common)?" «"+st+"»":"")+"\n"); } }
              else { StringBuilder row=new StringBuilder("          ["+rr+"]"+(common!=null?" «공통: "+common+"»":"")+" ");
                for(int cn=0;cn<maxC;cn++){ String st=sts[cn]; row.append(vals[cn]); if(st!=null&&!st.isEmpty()&&!st.equals(common)) row.append(" «").append(st).append("»"); row.append(cn<maxC-1?" | ":""); }
                b.append(row).append(colsN>maxC?" …(+"+(colsN-maxC)+"열)":"").append("\n"); } }
            if(rows>60) b.append("          …(+"+(rows-60)+"행)\n"); }
        }
      } } }
    return b.toString();
  }
  /** Style summary for a control or cell (detail mode). */
  static String styleInfo(Object o){ StringBuilder x=new StringBuilder(); Object ti=go(o,"getTextInfo");
    if(ti!=null){ String ha=g(ti,"getHorizontalAlignment"), va=g(ti,"getVerticalAlignment"), fs=g(ti,"getFontSize"), fb=g(ti,"getFontBold"), fn=g(ti,"getFontName"), ww=g(ti,"getWordWrap");
      if(ha!=null) x.append(" 정렬=").append(ha).append(va!=null?"/"+va:""); if(fn!=null&&!fn.isEmpty()) x.append(" 폰트=").append(fn); if(fs!=null) x.append(" 크기=").append(fs); if("true".equals(fb)) x.append(" 굵게"); if("true".equals(ww)) x.append(" 줄바꿈"); }
    if("true".equals(g(o,"getCanGrow"))) x.append(" 확장가능"); if("true".equals(g(o,"getCellMergeRowDataDuplication"))) x.append(" 셀합치기");
    Object cs=go(o,"getConditionalStyleList"); if(cs instanceof RexObjectList && ((RexObjectList<?>)cs).size()>0) x.append(" 조건스타일=").append(((RexObjectList<?>)cs).size());
    String bs=g(o,"getBackStyle"); if(bs!=null && !bs.equals("Transparent") && !bs.equals("None")){ String bc=g(o,"getBackColor"); if(bc!=null) x.append(" 배경=").append(colorHex(bc)); }
    return x.toString(); }
  static String colorHex(String bgr){ try{ int v=Integer.parseInt(bgr); int r=v&0xFF, gg=(v>>8)&0xFF, b=(v>>16)&0xFF; return String.format("#%02X%02X%02X",r,gg,b); }catch(Exception e){ return bgr; } }
  static void collectControls(Object o,List<Control> out,Set<Object> seen,int d){
    if(o==null||d>14) return;
    if(o instanceof RexObjectList){ RexObjectList<?> l=(RexObjectList<?>)o; for(int i=0;i<l.size();i++) collectControls(l.get(i),out,seen,d); return; }
    if(!o.getClass().getName().startsWith("com.clipsoft.clipreport")) return; if(!seen.add(o)) return;
    if(d>0 && o instanceof Section) return;   // don't cross into sibling/linked sections
    if(o instanceof Control){ out.add((Control)o); if(o instanceof ControlSubreport) return; }  // don't descend into a subreport's own report
    for(java.lang.reflect.Method m:o.getClass().getMethods()){ if(m.getParameterCount()!=0||!m.getName().startsWith("get")||m.getName().equals("getClass"))continue;
      Class<?> rt=m.getReturnType(); if(rt.isPrimitive()||rt==String.class||rt.isEnum()||rt==Class.class)continue;
      String nm=m.getName(); if(nm.equals("getApplyValueField")||nm.equals("getParentObj")||nm.equals("getSubreport"))continue;
      try{ collectControls(m.invoke(o),out,seen,d+1);}catch(Throwable t){} }
  }

  static int detailIndex(RexObjectList<Section> secs){ for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionDetail) return i; return -1; }
  /** Group by 0-based index (outermost=0, order of groupList) or grouping column name. */
  static Group groupOf(TheReportFile rf,String sel){ RexObjectList<Group> gl=rf.getGlobe().getMainReport().getReportObjectManager().getGroupList(); if(gl.size()==0) throw new RuntimeException("그룹이 없습니다");
    if(sel==null||sel.trim().isEmpty()) throw new RuntimeException("group 인자가 비어 있습니다"); String k=sel.trim();
    for(int i=0;i<gl.size();i++){ Field f=gl.get(i).getGroupingField(); if(f!=null&&k.equalsIgnoreCase(f.getName())) return gl.get(i); }
    try{ int i=Integer.parseInt(k); if(i>=0&&i<gl.size()) return gl.get(i); }catch(NumberFormatException e){}
    StringBuilder names=new StringBuilder(); for(int i=0;i<gl.size();i++) names.append(i==0?"":", ").append(i).append(":").append(nameOf(gl.get(i).getGroupingField()));
    throw new RuntimeException("그룹 '"+sel+"' 없음 — 사용 가능: "+names); }
  @SuppressWarnings("unchecked")
  static String addGroup(JSONObject args) throws Exception {
    String path=s(args,"path"), column=s(args,"column"), output=s(args,"output"), level=q(s(args,"level")).trim().toLowerCase(), sort=q(s(args,"sort")).trim(); boolean label="true".equalsIgnoreCase(s(args,"label")); String subtotal=s(args,"subtotal");
    if(column==null||column.trim().isEmpty()) return "ERROR: column 인자가 비어 있습니다";
    TheReportFile rf=open(path); Report rep=rf.getGlobe().getMainReport();
    Field gf=findField(rf,column.trim()); if(gf==null||!(gf instanceof FieldData)) return "ERROR: column '"+column+"' 데이터 필드 없음 (crf_summary 로 확인)";
    RexObjectList<Group> gl=rep.getReportObjectManager().getGroupList(); for(int i=0;i<gl.size();i++) if(gl.get(i).getGroupingField()==gf) return "ERROR: '"+gf.getName()+"' 로 이미 그룹이 있습니다";
    MainPage mp=rep.getReportDesign().getMainPage(); RexObjectList<Section> secs=mp.getSectionList(); int di=detailIndex(secs); if(di<0) return "ERROR: 본문(Detail) 밴드가 없습니다";
    java.util.List<Integer> hIdx=new java.util.ArrayList<>(), fIdx=new java.util.ArrayList<>(); for(int i=0;i<secs.size();i++){ if(i<di&&secs.get(i) instanceof SectionGroupHeader) hIdx.add(i); if(i>di&&secs.get(i) instanceof SectionGroupFooter) fIdx.add(i); }
    int L=hIdx.size(); int N; if(level.isEmpty()||level.equals("inner")) N=L; else if(level.equals("outer")) N=0; else { try{ N=Integer.parseInt(level); }catch(NumberFormatException e){ return "ERROR: level 은 inner|outer|N"; } if(N<0) N=0; if(N>L) N=L; }
    Group g=new Group(2800); g.setGroupingField(gf); g.setSortMethod(sort.equalsIgnoreCase("Descending")?SortMethod.Descending:SortMethod.Ascending); g.setDataTypeCasting(SortDataTypeCasting.String); g.setTotalVisible(true); g.setLabelVisible(true);
    if(N>=gl.size()) gl.add(g); else gl.add(N,g);
    SectionGroupHeader gh=new SectionGroupHeader(); gh.setGroup(g); SubSectionDefault hsub=band("그룹 머리글["+gf.getName()+"]",60); gh.getSubSectionList().add(hsub);
    SectionGroupFooter gfoot=new SectionGroupFooter(); SubSectionDefault fsub=band("그룹 바닥글["+gf.getName()+"]",50); gfoot.getSubSectionList().add(fsub);
    int hpos = N<L ? hIdx.get(N) : di; secs.add(hpos,gh);
    int fpos = N<L ? fIdx.get(L-1-N)+1+1 : di+2; secs.add(fpos,gfoot);
    StringBuilder extra=new StringBuilder();
    if(label){ ControlLabel c=new ControlLabel(); c.setName(uniqueControlName(rf,"grp_"+gf.getName())); c.setVisible(true); c.setX1(0); c.setY1(0); c.setWidth(600); c.setHeight(55); c.setApplyValueType(ApplyValueType.Field); c.setApplyValueField(gf); inheritFont(c,defaultFont(rf,path,true),true); ((RexObjectList<Control>)(RexObjectList<?>)clpOf(hsub).getControlList()).add(c); extra.append(" +머리글 라벨("+gf.getName()+")"); }
    if(subtotal!=null&&!subtotal.trim().isEmpty()){ int x=0; for(String fn: subtotal.split(",")){ fn=fn.trim(); if(fn.isEmpty()) continue; Field sf=findField(rf,fn); if(sf==null) return "ERROR: subtotal 필드 '"+fn+"' 없음";
        String fname="SUM_"+fn+"_BY_"+gf.getName(); if(findField(rf,fname)!=null){ int i=2; while(findField(rf,fname+"_"+i)!=null) i++; fname=fname+"_"+i; }
        FieldFormula ff=new FieldFormula(); ff.setName(fname); ff.setScript("return rexpert.sum(0,\"data."+fn+"\",0,\"data."+gf.getName()+"\",\"\");"); ff.setScriptType(ScriptType.JavaScript); ((RexObjectList<FieldFormula>) rep.getReportObjectManager().getFieldFormulaList()).add(ff);
        ControlLabel c=new ControlLabel(); c.setName(uniqueControlName(rf,"sub_"+fn)); c.setVisible(true); c.setX1(x*400); c.setY1(0); c.setWidth(400); c.setHeight(50); c.setApplyValueType(ApplyValueType.Field); c.setApplyValueField(ff); c.setOutputFormat("#,##0"); inheritFont(c,defaultFont(rf,path,true),true); ((RexObjectList<Control>)(RexObjectList<?>)clpOf(fsub).getControlList()).add(c); x++; extra.append(" +소계 "+fname); } }
    String wrote=save(rf,output,path);
    TheReportFile v=open(output); int vg=v.getGlobe().getMainReport().getReportObjectManager().getGroupList().size();
    return "OK: added group on "+gf.getName()+" (level "+N+" of "+(L+1)+", 정렬 "+g.getSortMethod()+"; header+footer bands)"+extra+" — groups now "+vg+", sections: "+sectionsOf(v)+", wrote "+wrote+(subtotal==null||subtotal.trim().isEmpty()?"":"\n소계 공식은 rexpert.sum(0,\"data.F\",0,\"data."+gf.getName()+"\",\"\") 형태(그룹 기준 필드) — 결과가 다르면 crf_add_formula_field/crf_set_cell 로 조정하세요");
  }
  static String setGroup(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), column=s(args,"column"), sort=s(args,"sort");
    TheReportFile rf=open(path); Group g=groupOf(rf,s(args,"group")); StringBuilder did=new StringBuilder();
    if(column!=null&&!column.trim().isEmpty()){ Field f=findField(rf,column.trim()); if(f==null) return "ERROR: column '"+column+"' 없음"; g.setGroupingField(f); did.append(" column="+f.getName());
      RexObjectList<?> gn=rf.getGlobe().getMainReport().getReportObjectManager().getFieldGroupNameList(); if(gn!=null) for(int i=0;i<gn.size();i++){ Object x=gn.get(i); if(go(x,"getLinkedGroup")==g){ String nm=nameOf(x); if(nm.contains("[")) ((Field)x).setName(nm.substring(0,nm.indexOf('['))+"["+f.getName()+"]"); } } }
    if(sort!=null&&!sort.trim().isEmpty()){ SortMethod sm; try{ sm=SortMethod.valueOf(sort.trim()); }catch(Exception e){ return "ERROR: sort 는 Ascending|Descending|Not"; } g.setSortMethod(sm); did.append(" sort="+sm); }
    if(did.length()==0) return "ERROR: nothing to set (column/sort)";
    String wrote=save(rf,output,path); return "OK: group("+nameOf(g.getGroupingField())+") set"+did+", wrote "+wrote;
  }
  static String removeGroup(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"); boolean force="true".equalsIgnoreCase(s(args,"force"));
    TheReportFile rf=open(path); Report rep=rf.getGlobe().getMainReport(); Group g=groupOf(rf,s(args,"group")); RexObjectList<Group> gl=rep.getReportObjectManager().getGroupList();
    RexObjectList<Section> secs=rep.getReportDesign().getMainPage().getSectionList(); int di=detailIndex(secs);
    java.util.List<Integer> hIdx=new java.util.ArrayList<>(), fIdx=new java.util.ArrayList<>(); for(int i=0;i<secs.size();i++){ if(i<di&&secs.get(i) instanceof SectionGroupHeader) hIdx.add(i); if(i>di&&secs.get(i) instanceof SectionGroupFooter) fIdx.add(i); }
    int level=-1; for(int i=0;i<hIdx.size();i++) if(((SectionGroupHeader)secs.get(hIdx.get(i))).getGroup()==g) level=i;
    // group-name fields linked to this group
    java.util.List<String> refs=new java.util.ArrayList<>(); java.util.List<Field> gnf=new java.util.ArrayList<>(); RexObjectList<?> gn=rep.getReportObjectManager().getFieldGroupNameList(); if(gn!=null) for(int i=0;i<gn.size();i++) if(go(gn.get(i),"getLinkedGroup")==g){ gnf.add((Field)gn.get(i)); for(String r: refsOf(rf,(Field)gn.get(i))) refs.add(nameOf(gn.get(i))+" ← "+r); }
    RexObjectList<?> rt=rep.getReportObjectManager().getFieldRunningTotalList(); if(rt!=null) for(int i=0;i<rt.size();i++){ Object r=rt.get(i); if(go(r,"getRunningTotalResetOnChangeGroup")==g||go(r,"getRunningTotalEvaluateOnChangeGroup")==g) refs.add("누적합산 "+nameOf(r)+" (그룹 기준)"); }
    if(!refs.isEmpty()&&!force) return "ERROR: 그룹("+nameOf(g.getGroupingField())+") 관련 필드가 "+refs.size()+"곳에서 참조됩니다 — force=true 로 강행:"+refsText(refs,30);
    int nCtl=0; int hp=-1, fp=-1; if(level>=0){ hp=hIdx.get(level); fp=fIdx.size()>level? fIdx.get(hIdx.size()-1-level) : -1; for(Object[] e: allControls(rf)) if(e[0]==secs.get(hp)||(fp>=0&&e[0]==secs.get(fp))) nCtl++; }
    if(nCtl>0&&!force) return "ERROR: 그룹("+nameOf(g.getGroupingField())+") 머리글/바닥글 밴드에 컨트롤 "+nCtl+"개가 있습니다 — force=true 로 밴드와 함께 삭제 (개별 컨트롤은 crf_describe_layout 로 확인)";
    if(level>=0){ if(fp>hp){ secs.remove(fp); secs.remove(hp); } else { secs.remove(hp); if(fp>=0) secs.remove(fp); } }
    for(int i=0;i<gl.size();i++) if(gl.get(i)==g){ gl.remove(i); break; }
    for(Field f: gnf) removeFromLists(rf,f);
    String wrote=save(rf,output,path); TheReportFile v=open(output);
    return "OK: 그룹("+nameOf(g.getGroupingField())+") 삭제 — 머리글/바닥글 밴드"+(level>=0?"(컨트롤 "+nCtl+"개 포함)":" 없음")+", 그룹이름 필드 "+gnf.size()+"개; groups now "+v.getGlobe().getMainReport().getReportObjectManager().getGroupList().size()+", sections: "+sectionsOf(v)+", wrote "+wrote+(refs.isEmpty()?"":"\n⚠ force — 끊어진 참조:"+refsText(refs,30));
  }

  /** Build a 1-row ControlTable with the given column widths; cells are TableCellNormal wired to rows/columns. */
  @SuppressWarnings("unchecked")
  static Control buildTable(String name,int[] widths,int rowH){ return buildTableGrid(name,widths,new int[]{rowH}); }
  /** rows×cols 표 골격(TableCellNormal, 대각선 None). 행 높이는 rowHs[] 로. */
  static Control buildTableGrid(String name,int[] widths,int[] rowHs){
    com.clipsoft.clipreport.base.controls.ControlTable t=new com.clipsoft.clipreport.base.controls.ControlTable(); t.setName(name); t.setVisible(true);
    int th=0; for(int r=0;r<rowHs.length;r++){ com.clipsoft.clipreport.base.controls.Tables.TableRow row=new com.clipsoft.clipreport.base.controls.Tables.TableRow(); row.setHeight(rowHs[r]); t.getTableRowList().add(row); th+=rowHs[r]; }
    int tw=0; for(int c=0;c<widths.length;c++){ com.clipsoft.clipreport.base.controls.Tables.TableColumn col=new com.clipsoft.clipreport.base.controls.Tables.TableColumn(); col.setWidth(widths[c]); t.getTableColumnList().add(col); tw+=widths[c]; }
    for(int r=0;r<rowHs.length;r++){ com.clipsoft.clipreport.base.controls.Tables.TableRow row=t.getTableRowList().get(r);
      for(int c=0;c<widths.length;c++){ com.clipsoft.clipreport.base.controls.Tables.TableColumn col=t.getTableColumnList().get(c);
        com.clipsoft.clipreport.base.controls.Tables.TableCellNormal cell=new com.clipsoft.clipreport.base.controls.Tables.TableCellNormal(); cell.setName(rowHs.length==1?name+"_c"+c:name+"_r"+r+"c"+c); cell.setTableRow(row); cell.setTableColumn(col); cell.setRowSpan(1); cell.setColSpan(1); row.getTableCellList().add(cell); col.getTableCellList().add(cell);
        noDiagonal(cell); } }
    try{ t.linkBaseCell(); t.setBaseCellRowColIndex(); }catch(Throwable e){}
    try{ t.getLineInfoSplit().setLineStyle(com.clipsoft.clipreport.common.enums.LineStyle.None); t.getLineInfoFDiagona().setLineStyle(com.clipsoft.clipreport.common.enums.LineStyle.None); t.getLineInfoBDiagona().setLineStyle(com.clipsoft.clipreport.common.enums.LineStyle.None); }catch(Throwable e){}
    t.setWidth(tw); t.setHeight(th); return t; }
  /** SDK 로 새로 만든 셀은 대각선(FDiagona/BDiagona) 기본이 Solid 라 X 표시가 그려진다 → None 으로. */
  static void noDiagonal(com.clipsoft.clipreport.base.controls.Tables.TableCellNormal cell){
    try{ cell.getLineInfoFDiagona().setLineStyle(com.clipsoft.clipreport.common.enums.LineStyle.None); cell.getLineInfoBDiagona().setLineStyle(com.clipsoft.clipreport.common.enums.LineStyle.None); }catch(Throwable e){}
  }
  /** 글상자 테두리: border=true/false (+linewidth). ShapeType Rectangle + LineStyle + LineInfo 를 함께 맞춘다. */
  static String applyBorder(Control c,JSONObject args){
    String border=s(args,"border"), lw=s(args,"linewidth"); if((border==null||border.isEmpty())&&(lw==null||lw.isEmpty())) return "";
    StringBuilder did=new StringBuilder(); Object li=go(c,"getLineInfo");
    if(border!=null&&!border.isEmpty()){ boolean on=Boolean.parseBoolean(border); com.clipsoft.clipreport.common.enums.LineStyle ls=on?com.clipsoft.clipreport.common.enums.LineStyle.Solid:com.clipsoft.clipreport.common.enums.LineStyle.None;
      call(c,"setShapeType",com.clipsoft.clipreport.common.enums.ShapeType.class,com.clipsoft.clipreport.common.enums.ShapeType.Rectangle); call(c,"setLineStyle",com.clipsoft.clipreport.common.enums.LineStyle.class,ls); if(li!=null) call(li,"setLineStyle",com.clipsoft.clipreport.common.enums.LineStyle.class,ls);
      if(on){ call(c,"setLineColor",int.class,0); if(li!=null) call(li,"setLineColor",int.class,0); } did.append(" 테두리="+on); }
    if(lw!=null&&!lw.isEmpty()){ com.clipsoft.clipreport.common.enums.LineWidth w; try{ w=com.clipsoft.clipreport.common.enums.LineWidth.valueOf(lw.trim()); }catch(Exception e){ throw new RuntimeException("linewidth 는 W025|W050|W075|W100|W150|W200|W300"); } call(c,"setLineWidth",com.clipsoft.clipreport.common.enums.LineWidth.class,w); if(li!=null) call(li,"setLineWidth",com.clipsoft.clipreport.common.enums.LineWidth.class,w); did.append(" 선굵기="+w); }
    return did.toString();
  }
  /** crf_set_cell_checkbox: 셀 내용=체크박스 + 참/거짓 조건. 조건이 없으면 항상 빈 상자(값 바인딩은 무시됨). */
  static String setCellCheckbox(JSONObject args) throws Exception {
    String path=s(args,"path"), table=s(args,"table"), output=s(args,"output");
    int row=Integer.parseInt(q(s(args,"row")).trim()), col=Integer.parseInt(q(s(args,"col")).trim());
    TheReportFile rf=open(path); Control tbl=findTable(rf,table); if(tbl==null) return "ERROR: table '"+table+"' not found (use crf_describe_layout for names)";
    com.clipsoft.clipreport.base.controls.Tables.TableCellNormal n=(com.clipsoft.clipreport.base.controls.Tables.TableCellNormal) cellOf(tbl,row,col);
    StringBuilder did=new StringBuilder();
    if("true".equalsIgnoreCase(s(args,"off"))){ n.setCellContent(com.clipsoft.clipreport.common.enums.CellContentType.Text); String wrote=save(rf,output,path); return "OK: 표 '"+table+"' ["+row+","+col+"] 셀 내용을 텍스트로 되돌림, wrote "+wrote; }
    String field=s(args,"field"); if(field==null||field.trim().isEmpty()) return "ERROR: field(조건 필드) 가 필요합니다 — 조건 없는 체크박스는 항상 빈 상자로 나옵니다";
    Field f=findField(rf,field.trim()); if(f==null) return "ERROR: field '"+field+"' not found (crf_summary 로 이름 확인)";
    String tv=s(args,"true_value"), fv=s(args,"false_value"), tv2=s(args,"true_value2"), op=s(args,"operator"), ct=s(args,"check_type"), shape=s(args,"shape"), color=s(args,"color"), size=s(args,"size"), def=s(args,"default");
    if(tv==null||tv.isEmpty()) tv="1"; if(fv==null) fv="0";
    CompareOperator o=CompareOperator.Equal; if(op!=null&&!op.trim().isEmpty()){ try{ o=CompareOperator.valueOf(op.trim()); }catch(Exception e){ return "ERROR: operator 는 Equal|NotEqual|LessThen|GreateThen|LessEqual|GreateEqual|Between"; } }
    n.setCellContent(com.clipsoft.clipreport.common.enums.CellContentType.Checkbox);
    com.clipsoft.clipreport.common.enums.CheckType cty=com.clipsoft.clipreport.common.enums.CheckType.Rectangle; if(ct!=null&&!ct.trim().isEmpty()){ try{ cty=com.clipsoft.clipreport.common.enums.CheckType.valueOf(ct.trim()); }catch(Exception e){ return "ERROR: check_type 은 Rectangle|V|Ellipse|RoundRectangle"; } }
    n.setCheckType(cty); did.append(" 체크모양="+cty);
    if(shape!=null&&!shape.trim().isEmpty()){ try{ n.setCheckShapeType(com.clipsoft.clipreport.common.enums.ShapeType.valueOf(shape.trim())); }catch(Exception e){ return "ERROR: shape 는 Rectangle|Ellipse|RoundRectangle|None"; } did.append(" 상자="+shape.trim()); } else n.setCheckShapeType(com.clipsoft.clipreport.common.enums.ShapeType.Rectangle);
    if(color!=null&&!color.isEmpty()){ n.setCheckColor(parseColor(color)); did.append(" 색="+color); } else n.setCheckColor(0);
    if(size!=null&&!size.isEmpty()){ n.setCheckSize(pInt(size,0)); did.append(" 크기="+size); }
    if(def!=null&&!def.isEmpty()){ n.setCheckValueDefault(Boolean.parseBoolean(def)); did.append(" 기본="+def); } else n.setCheckValueDefault(false);
    com.clipsoft.clipreport.base.functions.Condition tc=n.getCheckValueTrueCondition(); tc.setConditionField(f); tc.setCompareOperator(o); tc.setCompareValue1Type(ApplyValueType.Text); tc.setCompareValue1Text(tv);
    if(o==CompareOperator.Between){ if(tv2==null||tv2.isEmpty()) return "ERROR: Between 은 true_value2 가 필요합니다"; tc.setCompareValue2Type(ApplyValueType.Text); tc.setCompareValue2Text(tv2); }
    did.append(" 참조건="+f.getName()+" "+o+" '"+tv+"'"+(o==CompareOperator.Between?"~'"+tv2+"'":""));
    com.clipsoft.clipreport.base.functions.Condition fc=n.getCheckValueFalseCondition();
    if(!fv.isEmpty()){ fc.setConditionField(f); fc.setCompareOperator(CompareOperator.Equal); fc.setCompareValue1Type(ApplyValueType.Text); fc.setCompareValue1Text(fv); did.append(" 거짓조건="+f.getName()+" Equal '"+fv+"'"); }
    // 체크박스 셀은 텍스트/필드 값을 그리지 않으므로 바인딩을 비운다
    n.setApplyValueType(ApplyValueType.Text); n.setApplyValueText(""); try{ n.setApplyValueField(null); }catch(Throwable e){}
    String wrote=save(rf,output,path);
    TheReportFile v=open(output); Control vt=findTable(v,table); com.clipsoft.clipreport.base.controls.Tables.TableCellNormal vn=(com.clipsoft.clipreport.base.controls.Tables.TableCellNormal) cellOf(vt,row,col);
    if(vn.getCellContent()!=com.clipsoft.clipreport.common.enums.CellContentType.Checkbox || vn.getCheckValueTrueCondition().getConditionField()==null) return "ERROR: 저장 후 되읽기 검증 실패 — 체크박스/조건이 반영되지 않음";
    return "OK: 표 '"+table+"' ["+row+","+col+"] → 체크박스"+did+", verified[content="+vn.getCellContent()+" cond="+nameOf(vn.getCheckValueTrueCondition().getConditionField())+"], wrote "+wrote;
  }
  /** crf_merge_cells: 기준 셀 (row,col) 을 rowspan×colspan 으로 병합(덮이는 셀은 TableCellDumy). 1×1 이면 병합 해제. */
  static String mergeCells(JSONObject args) throws Exception {
    String path=s(args,"path"), table=s(args,"table"), output=s(args,"output");
    int row=Integer.parseInt(q(s(args,"row")).trim()), col=Integer.parseInt(q(s(args,"col")).trim()), rs=pInt(args.get("rowspan"),1), cs=pInt(args.get("colspan"),1);
    if(rs<1||cs<1) return "ERROR: rowspan/colspan 은 1 이상";
    TheReportFile rf=open(path); Control c0=findTable(rf,table); if(c0==null) return "ERROR: table '"+table+"' not found (use crf_describe_layout for names)";
    com.clipsoft.clipreport.base.controls.ControlTable t=(com.clipsoft.clipreport.base.controls.ControlTable) c0;
    int rows=t.getRowCount(), cols=t.getColumnCount();
    if(row<0||col<0||row>=rows||col>=cols) return "ERROR: 기준 셀 ["+row+","+col+"] 범위 밖 — 표는 "+rows+"행×"+cols+"열";
    if(row+rs>rows||col+cs>cols) return "ERROR: 병합 범위가 표를 벗어남 — "+rows+"행×"+cols+"열, 요청 ["+row+".."+(row+rs-1)+","+col+".."+(col+cs-1)+"]";
    com.clipsoft.clipreport.base.controls.Tables.TableCell a=t.getTableCell(row,col);
    if(!(a instanceof com.clipsoft.clipreport.base.controls.Tables.TableCellNormal)) return "ERROR: 기준 셀 ["+row+","+col+"] 은 다른 셀에 병합된 자리입니다 — 그 기준 셀에서 해제하세요";
    com.clipsoft.clipreport.base.controls.Tables.TableCellNormal base=(com.clipsoft.clipreport.base.controls.Tables.TableCellNormal) a;
    int oldRs=Math.max(1,base.getRowSpan()), oldCs=Math.max(1,base.getColSpan());
    // 1) 기존 병합 해제: base 에 속한 dummy 를 새 normal 셀로 복구
    int restored=0;
    for(int r=row;r<row+oldRs&&r<rows;r++) for(int c=col;c<col+oldCs&&c<cols;c++){ if(r==row&&c==col) continue; com.clipsoft.clipreport.base.controls.Tables.TableCell old=t.getTableCell(r,c);
      if(old instanceof com.clipsoft.clipreport.base.controls.Tables.TableCellDumy){ com.clipsoft.clipreport.base.controls.Tables.TableCellNormal n=new com.clipsoft.clipreport.base.controls.Tables.TableCellNormal(); n.setName(t.getName()+"_"+r+"_"+c); n.setTableRow(t.getTableRow(r)); n.setTableColumn(t.getTableColumn(c)); n.setRowSpan(1); n.setColSpan(1); n.setApplyValueType(ApplyValueType.Text); n.setApplyValueText(""); noDiagonal(n); copyCellStyle(base,n); replaceCell(t,r,c,old,n); restored++; } }
    // 2) 새 병합: 덮이는 셀이 다른 병합에 속해 있으면 거부
    for(int r=row;r<row+rs;r++) for(int c=col;c<col+cs;c++){ if(r==row&&c==col) continue; com.clipsoft.clipreport.base.controls.Tables.TableCell old=t.getTableCell(r,c);
      if(old instanceof com.clipsoft.clipreport.base.controls.Tables.TableCellDumy) return "ERROR: 셀 ["+r+","+c+"] 은 이미 다른 셀에 병합되어 있습니다 — 먼저 그 기준 셀을 1×1 로 해제하세요";
      if(old instanceof com.clipsoft.clipreport.base.controls.Tables.TableCellNormal){ com.clipsoft.clipreport.base.controls.Tables.TableCellNormal on=(com.clipsoft.clipreport.base.controls.Tables.TableCellNormal) old; if(on.getRowSpan()>1||on.getColSpan()>1) return "ERROR: 셀 ["+r+","+c+"] 은 자체 병합("+on.getRowSpan()+"×"+on.getColSpan()+") 기준 셀입니다 — 먼저 해제하세요"; } }
    int merged=0;
    for(int r=row;r<row+rs;r++) for(int c=col;c<col+cs;c++){ if(r==row&&c==col) continue; com.clipsoft.clipreport.base.controls.Tables.TableCell old=t.getTableCell(r,c);
      com.clipsoft.clipreport.base.controls.Tables.TableCellDumy d=new com.clipsoft.clipreport.base.controls.Tables.TableCellDumy(); d.setTableRow(t.getTableRow(r)); d.setTableColumn(t.getTableColumn(c)); d.setBaseCell(base); d.setBaseCellRowIndex(row); d.setBaseCellColIndex(col); replaceCell(t,r,c,old,d); merged++; }
    base.setRowSpan(rs); base.setColSpan(cs);
    try{ t.linkBaseCell(); t.setBaseCellRowColIndex(); }catch(Throwable e){}
    String wrote=save(rf,output,path);
    com.clipsoft.clipreport.base.controls.ControlTable vt=(com.clipsoft.clipreport.base.controls.ControlTable) findTable(open(output),table);
    com.clipsoft.clipreport.base.controls.Tables.TableCell vb=vt.getTableCell(row,col); int vrs=go(vb,"getRowSpan") instanceof Integer?(Integer)go(vb,"getRowSpan"):-1, vcs=go(vb,"getColSpan") instanceof Integer?(Integer)go(vb,"getColSpan"):-1;
    if(vrs!=rs||vcs!=cs) return "ERROR: 저장 후 되읽기 검증 실패 — span="+vrs+"×"+vcs;
    return "OK: 표 '"+table+"' ["+row+","+col+"] "+(rs==1&&cs==1?"병합 해제(복구 "+restored+"셀)":"→ "+rs+"×"+cs+" 병합(덮인 셀 "+merged+(restored>0?", 이전 병합 복구 "+restored:"")+")")+", verified[span="+vrs+"×"+vcs+"], wrote "+wrote;
  }
  static void replaceCell(com.clipsoft.clipreport.base.controls.ControlTable t,int r,int c,com.clipsoft.clipreport.base.controls.Tables.TableCell old,com.clipsoft.clipreport.base.controls.Tables.TableCell nw){
    RexObjectList<com.clipsoft.clipreport.base.controls.Tables.TableCell> rl=t.getTableRow(r).getTableCellList(); for(int i=0;i<rl.size();i++) if(rl.get(i)==old){ rl.remove(i); rl.add(i,nw); break; }
    RexObjectList<com.clipsoft.clipreport.base.controls.Tables.TableCell> cl2=t.getTableColumn(c).getTableCellList(); for(int i=0;i<cl2.size();i++) if(cl2.get(i)==old){ cl2.remove(i); cl2.add(i,nw); break; }
  }
  /** 병합 해제로 복구되는 셀에 기준 셀의 글꼴/테두리를 복사(디자이너 기본과 비슷하게). */
  static void copyCellStyle(com.clipsoft.clipreport.base.controls.Tables.TableCellNormal from,com.clipsoft.clipreport.base.controls.Tables.TableCellNormal to){
    try{ com.clipsoft.clipreport.base.functions.TextInfo a=from.getTextInfo(), b=to.getTextInfo(); b.setFontName(a.getFontName()); b.setFontSize(a.getFontSize()); b.setFontBold(a.getFontBold()); b.setHorizontalAlignment(a.getHorizontalAlignment()); b.setVerticalAlignment(a.getVerticalAlignment()); b.setWordWrap(a.getWordWrap());
      com.clipsoft.clipreport.base.functions.LineInfo[] src={from.getLineInfoLeft(),from.getLineInfoRight(),from.getLineInfoTop(),from.getLineInfoBottom()}, dst={to.getLineInfoLeft(),to.getLineInfoRight(),to.getLineInfoTop(),to.getLineInfoBottom()};
      for(int i=0;i<4;i++){ dst[i].setLineStyle(src[i].getLineStyle()); dst[i].setLineWidth(src[i].getLineWidth()); dst[i].setLineColor(src[i].getLineColor()); }
      to.setVisibleLeftLine(from.getVisibleLeftLine()); to.setVisibleRightLine(from.getVisibleRightLine()); to.setVisibleTopLine(from.getVisibleTopLine()); to.setVisibleBottomLine(from.getVisibleBottomLine()); }catch(Throwable e){}
  }
  @SuppressWarnings("unchecked")
  static String addTable(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), colsJson=s(args,"columns"), sectionKey=q(s(args,"section")).trim(), headKey=q(s(args,"header_section")).trim(), name=q(s(args,"name")).trim();
    String rowsJson=s(args,"rows"); if(rowsJson!=null&&!rowsJson.trim().isEmpty()) return addTextTable(args,rowsJson);
    if(colsJson==null||colsJson.trim().isEmpty()) return "ERROR: columns(필드 표) 또는 rows(문단 목록 표) 인자가 필요합니다";
    JSONArray cols; try{ cols=(JSONArray)P.parse(colsJson); }catch(Exception e){ return "ERROR: columns 는 JSON 배열이어야 합니다: "+e; }
    if(cols.isEmpty()) return "ERROR: columns 가 비어 있습니다";
    if(sectionKey.isEmpty()) sectionKey="본문"; if(headKey.isEmpty()) headKey="데이터머리글"; if(name.isEmpty()) name="표_new";
    int left=pInt(args.get("left"),0), top=pInt(args.get("top"),0), rowH=pInt(args.get("row_height"),60);
    TheReportFile rf=open(path);
    Section dataSec=findOrCreateSection(rf,sectionKey); if(dataSec==null) return "ERROR: section '"+sectionKey+"' not found";
    SubSectionDefault dsub=firstSub(dataSec); if(dsub==null) return "ERROR: section '"+sectionKey+"' 에 기본 서브섹션이 없습니다(서브리포트 밴드?)";
    int n=cols.size(); int[] widths=new int[n]; Field[] fields=new Field[n]; String[] titles=new String[n], formats=new String[n], aligns=new String[n];
    for(int i=0;i<n;i++){ JSONObject c=(JSONObject)cols.get(i); String fn=s(c,"field"); if(fn==null||fn.trim().isEmpty()) return "ERROR: columns["+i+"].field 누락"; Field f=findField(rf,fn.trim()); if(f==null) return "ERROR: 필드 '"+fn+"' 없음"; fields[i]=f;
      widths[i]=pInt(c.get("width"),300); titles[i]=s(c,"title")==null?f.getName():s(c,"title"); formats[i]=s(c,"format"); aligns[i]=s(c,"align"); }
    String[] fdD=defaultFont(rf,path,true), fdL=defaultFont(rf,path,false);
    String dname=uniqueControlName(rf,name); Control dt=buildTable(dname,widths,rowH); dt.setX1(left); dt.setY1(top);
    for(int i=0;i<n;i++){ Object cell=tableCell(dt,0,i); call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Field); call(cell,"setApplyValueField",Field.class,fields[i]); if(formats[i]!=null&&!formats[i].isEmpty()) call(cell,"setOutputFormat",String.class,formats[i]);
      if(aligns[i]!=null&&!aligns[i].isEmpty()){ JSONObject a=new JSONObject(); a.put("align",aligns[i]); applyProps(rf,cell,a,"F"); } inheritFont(cell,fdD,true); }
    ((RexObjectList<Control>)(RexObjectList<?>)clpOf(dsub).getControlList()).add(dt); if(dsub.getHeight()<top+rowH) dsub.setHeight(top+rowH);
    String hname=null; if(!headKey.equalsIgnoreCase("none")){ Section hs=findOrCreateSection(rf,headKey); if(hs==null) return "ERROR: header_section '"+headKey+"' not found (그룹 밴드는 먼저 crf_add_group)"; SubSectionDefault hsub=firstSub(hs); if(hsub==null) return "ERROR: header_section 에 기본 서브섹션이 없습니다";
      int htop=pInt(args.get("header_top"),0); hname=uniqueControlName(rf,dname+"_title"); Control ht=buildTable(hname,widths,rowH); ht.setX1(left); ht.setY1(htop);
      for(int i=0;i<n;i++){ Object cell=tableCell(ht,0,i); call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Text); call(cell,"setApplyValueText",String.class,titles[i]); JSONObject a=new JSONObject(); a.put("align","Center"); a.put("bold","true"); applyProps(rf,cell,a,"F"); inheritFont(cell,fdL,true); }
      ((RexObjectList<Control>)(RexObjectList<?>)clpOf(hsub).getControlList()).add(ht); if(hsub.getHeight()<htop+rowH) hsub.setHeight(htop+rowH); }
    String wrote=save(rf,output,path);
    TheReportFile v=open(output); Control vt=findTable(v,dname); if(vt==null) return "ERROR: 저장 후 되읽기 검증 실패 — 표가 없음"; Object vc=tableCell(vt,0,0);
    int tw=0; for(int w: widths) tw+=w;
    return "OK: 표 '"+dname+"' ("+n+"열, 너비 "+tw+") 를 "+dataSec.getClass().getSimpleName().replace("Section","")+" 에 생성"+(hname!=null?", 제목 표 '"+hname+"' 를 "+headKey+" 에 생성":"")+" — verified[cell(0,0) field="+nameOf(go(vc,"getApplyValueField"))+"], wrote "+wrote+"\n⚠ SDK 로 만든 표는 디자이너에서 한 번 열어 테두리/여백을 확인하세요 (crf_set_cell 로 셀별 조정 가능)";
  }
  @SuppressWarnings("unchecked")
  static String placeDetailFields(String path,String output) throws Exception {
    TheReportFile rf=open(path);
    DataSet ds=rf.getGlobe().getGlobalObjectManager().getDataSetList().get(0);
    RexObjectList<FieldData> fl=(RexObjectList<FieldData>) ds.getFieldDataList();
    if(fl.size()==0) return "ERROR: dataset "+ds.getName()+" has no fields";
    RexObjectList<Section> secs=rf.getGlobe().getMainReport().getReportDesign().getMainPage().getSectionList();
    SectionDetail det=null; for(int i=0;i<secs.size();i++) if(secs.get(i) instanceof SectionDetail) det=(SectionDetail)secs.get(i);
    if(det==null) return "ERROR: no Detail band";
    SubSectionDefault sd=firstSub(det); if(sd==null) return "ERROR: no detail subsection";
    RexObjectList<Control> cl=(RexObjectList<Control>) clpOf(sd).getControlList();
    int n=fl.size(), colW=Math.max(150, Math.min(400, 2600/Math.max(1,n))); String[] fd=defaultFont(rf,path,true);
    for(int i=0;i<n;i++){ FieldData f=fl.get(i); ControlLabel c=new ControlLabel(); c.setName(uniqueControlName(rf,"dat_"+f.getName())); c.setVisible(true);
      c.setX1(i*colW); c.setY1(0); c.setWidth(colW); c.setHeight(55);
      c.setApplyValueType(ApplyValueType.Field); c.setApplyValueField(f); inheritFont(c,fd,true); cl.add(c); }
    save(rf,output,path);
    return "OK: placed "+n+" field-bound data labels in Detail band, wrote "+output;
  }

  // ===================== v0.7.1: 글꼴 상속 / 글상자↔표 붙이기·나누기 / lint =====================
  static boolean isSystemFont(String fn){ return fn==null||fn.trim().isEmpty()||fn.trim().equalsIgnoreCase("System"); }
  /** 리포트의 텍스트 요소(글상자·셀) 글꼴 통계: [0]=라벨(정적 텍스트) 글꼴→개수, [1]=데이터(필드 바인딩) 글꼴→개수, [2]=크기→개수, [3]=System 글꼴 위치 목록 */
  static Object[] fontProfile(TheReportFile rf){
    java.util.Map<String,Integer> lab=new java.util.LinkedHashMap<>(), dat=new java.util.LinkedHashMap<>(), size=new java.util.LinkedHashMap<>(); java.util.List<String> sys=new java.util.ArrayList<>(); java.util.Map<String,java.util.List<String>> where=new java.util.LinkedHashMap<>();
    for(Object[] e: allControls(rf)){ Control c=(Control)e[3]; String band=((Section)e[0]).getClass().getSimpleName().replace("Section","");
      if("ControlTable".equals(c.getClass().getSimpleName())){ Object rc=go(c,"getRowCount"), cc=go(c,"getColumnCount"); if(!(rc instanceof Integer)||!(cc instanceof Integer)) continue;
        for(int r=0;r<(Integer)rc;r++) for(int k=0;k<(Integer)cc;k++){ Object cell=tableCell(c,r,k); if(!isNormalCell(cell)) continue; fontTally(cell,band+" 표 "+c.getName()+"["+r+","+k+"]",lab,dat,size,sys,where); } }
      else if(go(c,"getTextInfo")!=null) fontTally(c,band+" "+c.getClass().getSimpleName().replace("Control","")+" \""+c.getName()+"\"",lab,dat,size,sys,where); }
    return new Object[]{lab,dat,size,sys,where};
  }
  static void fontTally(Object o,String at,java.util.Map<String,Integer> lab,java.util.Map<String,Integer> dat,java.util.Map<String,Integer> size,java.util.List<String> sys,java.util.Map<String,java.util.List<String>> where){
    Object ti=go(o,"getTextInfo"); if(ti==null) return; String fn=g(ti,"getFontName"), fs=g(ti,"getFontSize");
    if(isSystemFont(fn)){ sys.add(at); return; }
    boolean bound=go(o,"getApplyValueField")!=null; (bound?dat:lab).merge(fn.trim(),1,Integer::sum); if(fs!=null) size.merge(fs,1,Integer::sum); where.computeIfAbsent(fn.trim(),k->new java.util.ArrayList<>()).add(at);
  }
  static String topKey(java.util.Map<String,Integer> m){ String best=null; int bn=0; for(java.util.Map.Entry<String,Integer> e: m.entrySet()) if(e.getValue()>bn){ best=e.getKey(); bn=e.getValue(); } return best; }
  /** 새 요소에 줄 기본 글꼴 [이름, 크기(없으면 null), 근거]: 리포트의 라벨/데이터 지배 글꼴 → 반대쪽 → 같은 폴더 이웃 리포트(최근 8개) → 돋움체 */
  @SuppressWarnings("unchecked")
  static String[] defaultFont(TheReportFile rf,String path,boolean data){
    Object[] p=fontProfile(rf); java.util.Map<String,Integer> pri=(java.util.Map<String,Integer>)p[data?1:0], sec=(java.util.Map<String,Integer>)p[data?0:1]; String sz=topKey((java.util.Map<String,Integer>)p[2]);
    String f=topKey(pri); if(f!=null) return new String[]{f,sz,"리포트 "+(data?"데이터":"라벨")+" 글꼴"};
    f=topKey(sec); if(f!=null) return new String[]{f,sz,"리포트 "+(data?"라벨":"데이터")+" 글꼴"};
    try{ File me=new File(q(path)).getAbsoluteFile(); File dir=me.getParentFile(); File[] fs=dir==null?null:dir.listFiles((d,n)->n.toLowerCase().endsWith(".crf")&&!n.equalsIgnoreCase(me.getName()));
      if(fs!=null&&fs.length>0){ java.util.Arrays.sort(fs,(a,b)->Long.compare(b.lastModified(),a.lastModified())); java.util.Map<String,Integer> agg=new java.util.LinkedHashMap<>(), aggS=new java.util.LinkedHashMap<>(); int n=0;
        for(File x: fs){ if(n++>=8) break; try{ Object[] qp=fontProfile(open(x.getPath())); for(int i=0;i<2;i++) for(java.util.Map.Entry<String,Integer> e: ((java.util.Map<String,Integer>)qp[i]).entrySet()) agg.merge(e.getKey(),e.getValue(),Integer::sum); for(java.util.Map.Entry<String,Integer> e: ((java.util.Map<String,Integer>)qp[2]).entrySet()) aggS.merge(e.getKey(),e.getValue(),Integer::sum); }catch(Throwable t){} }
        f=topKey(agg); if(f!=null) return new String[]{f,topKey(aggS),"같은 폴더 이웃 리포트 관례"}; } }catch(Throwable t){}
    return new String[]{"돋움체",null,"저장소 기본(돋움체)"};
  }
  /** 글꼴이 아직 SDK 기본(System)인 요소에만 기본 글꼴(+크기)을 준다. 반환: 적용 설명 또는 "" */
  static String inheritFont(Object target,String[] def,boolean setSize){
    Object ti=go(target,"getTextInfo"); if(ti==null||def==null) return ""; if(!isSystemFont(g(ti,"getFontName"))) return "";
    call(ti,"setFontName",String.class,def[0]); String did=" 폰트="+def[0];
    if(setSize&&def[1]!=null){ try{ call(ti,"setFontSize",short.class,(short)Integer.parseInt(def[1].trim())); did+=" 크기="+def[1]; }catch(Exception e){} }
    return did+"(상속:"+def[2]+")";
  }
  /** TextInfo 속성 복사(글꼴·크기·굵게·기울임·밑줄·글자색·정렬·줄바꿈·줄간격·여백) */
  static void copyTextInfo(Object from,Object to){
    Object a=go(from,"getTextInfo"), b=go(to,"getTextInfo"); if(a==null||b==null) return;
    for(String p: new String[]{"FontName","FontSize","FontBold","FontItalic","FontUnderline","FontStrike","ForeColor","HorizontalAlignment","VerticalAlignment","WordWrap","LineSpace","WordSpace","LeftMargin","TopMargin","RightMargin","BottomMargin","AutoFontSize"}){
      try{ java.lang.reflect.Method gm=a.getClass().getMethod("get"+p); Object v=gm.invoke(a); java.lang.reflect.Method sm=b.getClass().getMethod("set"+p,gm.getReturnType()); sm.invoke(b,v); }catch(Throwable t){} }
  }
  /** 표 셀 네 변 테두리 on/off (Solid W050 검정 / None) */
  static void cellBorder(Object cell,boolean on){
    try{ com.clipsoft.clipreport.base.controls.Tables.TableCellNormal n=(com.clipsoft.clipreport.base.controls.Tables.TableCellNormal)cell; n.setVisibleLeftLine(on); n.setVisibleRightLine(on); n.setVisibleTopLine(on); n.setVisibleBottomLine(on);
      for(com.clipsoft.clipreport.base.functions.LineInfo li: new com.clipsoft.clipreport.base.functions.LineInfo[]{n.getLineInfoLeft(),n.getLineInfoRight(),n.getLineInfoTop(),n.getLineInfoBottom()}){ li.setLineStyle(on?com.clipsoft.clipreport.common.enums.LineStyle.Solid:com.clipsoft.clipreport.common.enums.LineStyle.None); if(on){ li.setLineWidth(com.clipsoft.clipreport.common.enums.LineWidth.W050); li.setLineColor(0); } } }catch(Throwable t){}
  }
  /** 표 자체의 외곽선(ControlTable LineStyle/LineInfo) on/off — 셀 선과 별개로 그려진다 */
  static void tableBorder(Control t,boolean on){
    com.clipsoft.clipreport.common.enums.LineStyle ls=on?com.clipsoft.clipreport.common.enums.LineStyle.Solid:com.clipsoft.clipreport.common.enums.LineStyle.None;
    try{ call(t,"setLineStyle",com.clipsoft.clipreport.common.enums.LineStyle.class,ls); Object li=go(t,"getLineInfo"); if(li!=null){ call(li,"setLineStyle",com.clipsoft.clipreport.common.enums.LineStyle.class,ls); if(on){ call(li,"setLineWidth",com.clipsoft.clipreport.common.enums.LineWidth.class,com.clipsoft.clipreport.common.enums.LineWidth.W050); call(li,"setLineColor",int.class,0); } } if(on) call(t,"setLineColor",int.class,0); }catch(Throwable e){}
  }
  static int ix(Object o,String m){ Object v=go(o,m); return v instanceof Number?((Number)v).intValue():0; }
  /** 값 바인딩 복사(필드/텍스트/출력양식) */
  static void copyBinding(Object from,Object to){
    Object f=go(from,"getApplyValueField"); String txt=g(from,"getApplyValueText"), fmt=g(from,"getOutputFormat");
    if(f!=null){ call(to,"setApplyValueType",ApplyValueType.class,ApplyValueType.Field); call(to,"setApplyValueField",Field.class,(Field)f); }
    else { call(to,"setApplyValueType",ApplyValueType.class,ApplyValueType.Text); call(to,"setApplyValueText",String.class,q(txt)); }
    if(fmt!=null&&!fmt.isEmpty()) call(to,"setOutputFormat",String.class,fmt);
  }
  /** crf_add_table rows=: 1열 N행 문단 목록 표 */
  @SuppressWarnings("unchecked")
  static String addTextTable(JSONObject args,String rowsJson) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), sectionKey=q(s(args,"section")).trim(), name=q(s(args,"name")).trim(), dAlign=q(s(args,"align")).trim();
    JSONArray rows; try{ rows=(JSONArray)P.parse(rowsJson); }catch(Exception e){ return "ERROR: rows 는 JSON 배열이어야 합니다: "+e; }
    if(rows.isEmpty()) return "ERROR: rows 가 비어 있습니다";
    if(sectionKey.isEmpty()) sectionKey="본문"; if(name.isEmpty()) name="표_text"; if(dAlign.isEmpty()) dAlign="Left";
    int left=pInt(args.get("left"),0), top=pInt(args.get("top"),0), rowH=pInt(args.get("row_height"),56), width=pInt(args.get("width"),1480);
    boolean border="true".equalsIgnoreCase(s(args,"border")), wrap=!"false".equalsIgnoreCase(s(args,"wrap"));
    TheReportFile rf=open(path); Section sec=findOrCreateSection(rf,sectionKey); if(sec==null) return "ERROR: section '"+sectionKey+"' not found";
    SubSectionDefault sub=firstSub(sec); if(sub==null) return "ERROR: section '"+sectionKey+"' 에 기본 서브섹션이 없습니다";
    int n=rows.size(); int[] rh=new int[n]; JSONObject[] spec=new JSONObject[n];
    for(int i=0;i<n;i++){ Object o=rows.get(i); JSONObject j; if(o instanceof JSONObject) j=(JSONObject)o; else { j=new JSONObject(); j.put("text",String.valueOf(o)); } spec[i]=j; rh[i]=pInt(j.get("height"),rowH); if(rh[i]<=0) return "ERROR: rows["+i+"].height 는 양수"; }
    String[] fdD=defaultFont(rf,path,true), fdL=defaultFont(rf,path,false);
    String tname=uniqueControlName(rf,name); Control t=buildTableGrid(tname,new int[]{width},rh); t.setX1(left); t.setY1(top); tableBorder(t,border);
    StringBuilder did=new StringBuilder();
    for(int i=0;i<n;i++){ Object cell=tableCell(t,i,0); JSONObject j=spec[i]; String fld=s(j,"field"), txt=s(j,"text");
      if(fld!=null&&!fld.trim().isEmpty()){ Field f=findField(rf,fld.trim()); if(f==null) return "ERROR: rows["+i+"].field '"+fld+"' 없음"; call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Field); call(cell,"setApplyValueField",Field.class,f); }
      else { call(cell,"setApplyValueType",ApplyValueType.class,ApplyValueType.Text); call(cell,"setApplyValueText",String.class,q(txt)); }
      JSONObject a=new JSONObject(); a.put("align",s(j,"align")==null?dAlign:s(j,"align")); a.put("valign","Center"); a.put("wrap",String.valueOf(!"false".equalsIgnoreCase(s(j,"wrap"))&&wrap)); if(s(j,"bold")!=null) a.put("bold",s(j,"bold")); if(s(j,"cangrow")!=null) a.put("cangrow",s(j,"cangrow")); if(s(j,"fontsize")!=null) a.put("fontsize",s(j,"fontsize")); if(s(j,"color")!=null) a.put("color",s(j,"color")); if(s(j,"linespace")!=null) a.put("linespace",s(j,"linespace")); if(s(j,"padding")!=null) a.put("padding",s(j,"padding")); if(s(j,"font")!=null) a.put("font",s(j,"font"));
      applyProps(rf,cell,a,"F"); inheritFont(cell,fld!=null&&!fld.trim().isEmpty()?fdD:fdL,s(j,"fontsize")==null); cellBorder(cell,border); }
    ((RexObjectList<Control>)(RexObjectList<?>)clpOf(sub).getControlList()).add(t); int th=0; for(int h: rh) th+=h; if(sub.getHeight()<top+th) sub.setHeight(top+th);
    String wrote=save(rf,output,path);
    Control vt=findTable(open(output),tname); if(vt==null) return "ERROR: 저장 후 되읽기 검증 실패 — 표가 없음"; int vr=ix(vt,"getRowCount");
    String vf=g(go(tableCell(vt,0,0),"getTextInfo"),"getFontName");
    return "OK: 문단 목록 표 '"+tname+"' ("+n+"행×1열, 너비 "+width+", 높이 "+th+", 테두리 "+(border?"있음":"없음")+", 폰트 "+vf+") 를 "+sec.getClass().getSimpleName().replace("Section","")+" 에 생성 — verified[rows="+vr+"], wrote "+wrote;
  }
  /** crf_merge_labels: 세로로 놓인 글상자들을 1열 표 하나(into=table) 또는 줄바꿈 글상자 하나(into=label) 로 합친다. */
  @SuppressWarnings("unchecked")
  static String mergeLabels(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), namesS=q(s(args,"names")).trim(), into=q(s(args,"into")).trim().toLowerCase(), name=q(s(args,"name")).trim(); boolean border="true".equalsIgnoreCase(s(args,"border"));
    if(namesS.isEmpty()) return "ERROR: names (쉼표로 구분한 글상자 이름) 가 필요합니다"; if(into.isEmpty()) into="table";
    TheReportFile rf=open(path); java.util.List<Object[]> locs=new java.util.ArrayList<>();
    for(String nm: namesS.split(",")){ nm=nm.trim(); if(nm.isEmpty()) continue; Object[] l=findControlLoc(rf,nm); if(l==null) return "ERROR: control '"+nm+"' not found (crf_describe_layout 로 이름 확인)"; if(!(l[3] instanceof ControlLabel)) return "ERROR: '"+nm+"' 은 글상자가 아닙니다 ("+l[3].getClass().getSimpleName()+")"; locs.add(l); }
    if(locs.size()<2) return "ERROR: 글상자가 2개 이상이어야 합니다";
    Object sub=locs.get(0)[1]; for(Object[] l: locs) if(l[1]!=sub) return "ERROR: 모든 글상자가 같은 밴드(서브섹션)에 있어야 합니다";
    locs.sort((a,b)->{ int d=ix(a[3],"getY1")-ix(b[3],"getY1"); return d!=0?d:ix(a[3],"getX1")-ix(b[3],"getX1"); });
    ControlLabel first=(ControlLabel)locs.get(0)[3]; RexObjectList<Control> cl=(RexObjectList<Control>)locs.get(0)[2];
    int x=Integer.MAX_VALUE, right=0, y=Integer.MAX_VALUE, bottom=0; StringBuilder names=new StringBuilder();
    for(Object[] l: locs){ Control c=(Control)l[3]; x=Math.min(x,ix(c,"getX1")); right=Math.max(right,ix(c,"getX1")+ix(c,"getWidth")); y=Math.min(y,ix(c,"getY1")); bottom=Math.max(bottom,ix(c,"getY1")+ix(c,"getHeight")); names.append(names.length()>0?",":"").append(c.getName()); }
    String band=((Section)locs.get(0)[0]).getClass().getSimpleName().replace("Section",""); String wrote;
    if(into.startsWith("l")){
      StringBuilder txt=new StringBuilder(); java.util.List<String> noWrap=new java.util.ArrayList<>(); for(Object[] l: locs){ Control c=(Control)l[3]; if(go(c,"getApplyValueField")!=null) return "ERROR: '"+c.getName()+"' 은 필드 바인딩 글상자라 into=label 로 합칠 수 없습니다 — into=table 을 쓰세요"; if(!"true".equals(g(go(c,"getTextInfo"),"getWordWrap"))) noWrap.add(c.getName()); if(txt.length()>0) txt.append("\n"); txt.append(q(g(c,"getApplyValueText"))); }
      first.setApplyValueType(ApplyValueType.Text); first.setApplyValueText(txt.toString()); first.setX1(x); first.setY1(y); first.setWidth(right-x); first.setHeight(bottom-y); try{ first.getTextInfo().setWordWrap(true); }catch(Throwable t){}
      if(!name.isEmpty()) first.setName(uniqueControlName(rf,name));
      for(int i=1;i<locs.size();i++) for(int k=0;k<cl.size();k++) if(cl.get(k)==locs.get(i)[3]){ cl.remove(k); break; }
      wrote=save(rf,output,path); TheReportFile v=open(output); Object[] vl=findControlLoc(v,first.getName()); if(vl==null) return "ERROR: 저장 후 되읽기 검증 실패";
      return "OK: "+band+" 글상자 "+locs.size()+"개("+names+") → 줄바꿈 글상자 하나 \""+first.getName()+"\" (위치 "+x+","+y+" 크기 "+(right-x)+"×"+(bottom-y)+", "+locs.size()+"줄), verified, wrote "+wrote+(noWrap.isEmpty()?"":"\n⚠ 원래 줄바꿈 없던 글상자 "+noWrap+" 의 긴 줄은 이제 너비에서 접힐 수 있습니다(끝 글자가 다음 줄로 내려가면 into=table 로 합치거나 너비를 늘리세요)");
    }
    int n=locs.size(); int[] rh=new int[n];
    for(int i=0;i<n;i++){ Control c=(Control)locs.get(i)[3]; int cy=ix(c,"getY1"), ch=ix(c,"getHeight"); rh[i]= i<n-1 ? ix(locs.get(i+1)[3],"getY1")-cy : (bottom-cy); if(rh[i]<=0) return "ERROR: '"+c.getName()+"' 와 다음 글상자가 겹쳐 있습니다 — 세로로 나란한 글상자만 표로 합칠 수 있습니다"; if(rh[i]<ch) rh[i]=ch; }
    String tname=uniqueControlName(rf,name.isEmpty()?"표_"+first.getName():name); Control t=buildTableGrid(tname,new int[]{right-x},rh); t.setX1(x); t.setY1(y); tableBorder(t,border);
    for(int i=0;i<n;i++){ Control c=(Control)locs.get(i)[3]; Object cell=tableCell(t,i,0); copyBinding(c,cell); copyTextInfo(c,cell); try{ call(cell,"setCanGrow",boolean.class,"true".equals(g(c,"getCanGrow"))); }catch(Throwable e){}
      String bs=g(c,"getBackStyle"); if(bs!=null&&!bs.equals("Transparent")&&!bs.equals("None")){ try{ call(cell,"setBackStyle",BackStyleType.class,BackStyleType.valueOf(bs)); call(cell,"setBackColor",int.class,ix(c,"getBackColor")); }catch(Throwable e){} }
      cellBorder(cell,border); }
    int idx=cl.size(); for(int k=0;k<cl.size();k++) if(cl.get(k)==first){ idx=k; break; }
    for(Object[] l: locs) for(int k=0;k<cl.size();k++) if(cl.get(k)==l[3]){ cl.remove(k); break; }
    cl.add(Math.min(idx,cl.size()),t);
    wrote=save(rf,output,path);
    TheReportFile v=open(output); Control vt=findTable(v,tname); if(vt==null) return "ERROR: 저장 후 되읽기 검증 실패 — 표가 없음"; for(Object[] l: locs) if(findControlLoc(v,((Control)l[3]).getName())!=null) return "ERROR: 저장 후 되읽기 검증 실패 — 원본 글상자가 남아 있음";
    int th=0; for(int h: rh) th+=h; String vt0=g(tableCell(vt,0,0),"getApplyValueText"); Object vf0=go(tableCell(vt,0,0),"getApplyValueField");
    return "OK: "+band+" 글상자 "+n+"개("+names+") → 1열 표 \""+tname+"\" ("+n+"행, 위치 "+x+","+y+" 크기 "+(right-x)+"×"+th+", 테두리 "+(border?"있음":"없음")+") — 각 행이 원래 글상자의 값/글꼴/정렬/줄간격을 유지, 세로 자리는 그대로. verified[rows="+ix(vt,"getRowCount")+", (0,0)="+(vf0!=null?"필드 "+nameOf(vf0):"\""+oneLine(vt0,30)+"\"")+"], wrote "+wrote;
  }
  /** crf_split_label: 줄바꿈 글상자 → 줄별 글상자 / 1열 표 → 행별 글상자 */
  @SuppressWarnings("unchecked")
  static String splitLabel(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), name=q(s(args,"name")).trim(), linesJson=s(args,"lines"), heightsJson=s(args,"heights");
    TheReportFile rf=open(path); Object[] loc=findControlLoc(rf,name); if(loc==null) return "ERROR: control '"+name+"' not found";
    Control c=(Control)loc[3]; RexObjectList<Control> cl=(RexObjectList<Control>)loc[2]; String band=((Section)loc[0]).getClass().getSimpleName().replace("Section","");
    int idx=cl.size(); for(int k=0;k<cl.size();k++) if(cl.get(k)==c){ idx=k; break; }
    java.util.List<String> made=new java.util.ArrayList<>();
    if("ControlTable".equals(c.getClass().getSimpleName())){
      int rows=ix(c,"getRowCount"), cols=ix(c,"getColumnCount"); if(cols!=1) return "ERROR: "+cols+"열 표는 행별 글상자로 나눌 수 없습니다 — 1열 표만 가능 (셀 편집은 crf_set_cell)";
      int x=ix(c,"getX1"), y=ix(c,"getY1"), w=ix(c,"getWidth");
      for(int r=0;r<rows;r++){ Object row=null; try{ row=c.getClass().getMethod("getTableRow",int.class).invoke(c,r); }catch(Exception e){} int h=ix(row,"getHeight"); Object cell=tableCell(c,r,0);
        ControlLabel l=new ControlLabel(); l.setName(uniqueControlName(rf,name+"_"+(r+1))); l.setVisible(true); l.setX1(x); l.setY1(y); l.setWidth(w); l.setHeight(h);
        l.setShapeType(com.clipsoft.clipreport.common.enums.ShapeType.Rectangle); l.setLineStyle(com.clipsoft.clipreport.common.enums.LineStyle.None); l.setBackStyle(BackStyleType.Transparent); if(l.getLineInfo()!=null) l.getLineInfo().setLineStyle(com.clipsoft.clipreport.common.enums.LineStyle.None);
        if(isNormalCell(cell)){ copyBinding(cell,l); copyTextInfo(cell,l); try{ l.setCanGrow("true".equals(g(cell,"getCanGrow"))); }catch(Throwable e){} }
        cl.add(Math.min(idx+r,cl.size()),l); made.add(l.getName()+"("+h+")"); y+=h; }
      for(int k=0;k<cl.size();k++) if(cl.get(k)==c){ cl.remove(k); break; }
      String wrote=save(rf,output,path); TheReportFile v=open(output); if(findControlLoc(v,name)!=null) return "ERROR: 저장 후 되읽기 검증 실패 — 표가 남아 있음"; for(String m: made) if(findControlLoc(v,m.substring(0,m.indexOf('(')))==null) return "ERROR: 저장 후 되읽기 검증 실패 — "+m+" 없음";
      return "OK: "+band+" 1열 표 \""+name+"\" ("+rows+"행) → 글상자 "+rows+"개 "+made+" (각 행의 값/글꼴/정렬 유지, 세로 자리 그대로), verified, wrote "+wrote;
    }
    if(!(c instanceof ControlLabel)) return "ERROR: '"+name+"' 은 글상자/표가 아닙니다 ("+c.getClass().getSimpleName()+")";
    if(go(c,"getApplyValueField")!=null&&(linesJson==null||linesJson.trim().isEmpty())) return "ERROR: 필드 바인딩 글상자는 줄 단위로 나눌 수 없습니다 — lines 로 텍스트 조각을 지정하거나 표로 바꾸세요";
    java.util.List<String> parts=new java.util.ArrayList<>();
    if(linesJson!=null&&!linesJson.trim().isEmpty()){ JSONArray a; try{ a=(JSONArray)P.parse(linesJson); }catch(Exception e){ return "ERROR: lines 는 JSON 배열: "+e; } for(Object o: a) parts.add(String.valueOf(o)); }
    else for(String p: q(g(c,"getApplyValueText")).split("\r?\n")) parts.add(p);
    if(parts.size()<2) return "ERROR: 나눌 줄이 없습니다(줄바꿈 없는 텍스트) — lines JSON 배열로 조각을 지정하세요";
    int n=parts.size(); int x=ix(c,"getX1"), y=ix(c,"getY1"), w=ix(c,"getWidth"), H=ix(c,"getHeight"); int[] hs=new int[n];
    if(heightsJson!=null&&!heightsJson.trim().isEmpty()){ JSONArray a; try{ a=(JSONArray)P.parse(heightsJson); }catch(Exception e){ return "ERROR: heights 는 JSON 배열: "+e; } if(a.size()!=n) return "ERROR: heights 개수("+a.size()+") ≠ 조각 수("+n+")"; for(int i=0;i<n;i++) hs[i]=pInt(a.get(i),0); }
    else { int base=H/n, rem=H-base*n; for(int i=0;i<n;i++) hs[i]=base+(i==n-1?rem:0); }
    ControlLabel src0=(ControlLabel)c; int cy=y;
    for(int i=0;i<n;i++){ ControlLabel l; if(i==0){ l=src0; } else { l=new ControlLabel(); l.setName(uniqueControlName(rf,name+"_"+(i+1))); l.setVisible(src0.getVisible()); l.setShapeType(src0.getShapeType()); l.setLineStyle(src0.getLineStyle()); l.setBackStyle(src0.getBackStyle()); try{ l.setBackColor(src0.getBackColor()); }catch(Throwable e){} try{ l.getLineInfo().setLineStyle(src0.getLineInfo().getLineStyle()); l.getLineInfo().setLineWidth(src0.getLineInfo().getLineWidth()); }catch(Throwable e){} copyTextInfo(src0,l); l.setCanGrow(src0.getCanGrow()); }
      l.setApplyValueType(ApplyValueType.Text); l.setApplyValueText(parts.get(i)); l.setX1(x); l.setY1(cy); l.setWidth(w); l.setHeight(hs[i]); cy+=hs[i];
      if(i>0) cl.add(Math.min(idx+i,cl.size()),l); made.add(l.getName()+"("+hs[i]+")"); }
    String wrote=save(rf,output,path); TheReportFile v=open(output); for(String m: made) if(findControlLoc(v,m.substring(0,m.indexOf('(')))==null) return "ERROR: 저장 후 되읽기 검증 실패 — "+m+" 없음";
    return "OK: "+band+" 글상자 \""+name+"\" → "+n+"개 "+made+" (같은 스타일, 세로 자리 그대로), verified, wrote "+wrote;
  }
  /** crf_set_font: 글꼴 일괄 적용 */
  static String setFont(JSONObject args) throws Exception {
    String path=s(args,"path"), output=s(args,"output"), font=q(s(args,"font")).trim(), dfont=q(s(args,"data_font")).trim(), sz=q(s(args,"size")).trim(), section=q(s(args,"section")).trim(); boolean onlySys="true".equalsIgnoreCase(s(args,"only_system"));
    if(font.isEmpty()&&dfont.isEmpty()&&sz.isEmpty()) return "ERROR: font / data_font / size 중 하나는 필요합니다";
    if(dfont.isEmpty()) dfont=font; if(font.isEmpty()&&!sz.isEmpty()&&dfont.isEmpty()) { /* size only */ }
    short szv=0; if(!sz.isEmpty()){ try{ szv=(short)Integer.parseInt(sz); }catch(Exception e){ return "ERROR: size 는 정수"; } }
    TheReportFile rf=open(path); Section secF=null; if(!section.isEmpty()){ secF=findSection(rf,section); if(secF==null) return "ERROR: section '"+section+"' not found"; }
    Object[] before=fontProfile(rf); int[] cnt=new int[3];
    for(Object[] e: allControls(rf)){ if(secF!=null&&e[0]!=secF) continue; Control c=(Control)e[3];
      if("ControlTable".equals(c.getClass().getSimpleName())){ int rows=ix(c,"getRowCount"), cols=ix(c,"getColumnCount"); for(int r=0;r<rows;r++) for(int k=0;k<cols;k++){ Object cell=tableCell(c,r,k); if(isNormalCell(cell)) applyFontTo(cell,font,dfont,szv,onlySys,cnt); } }
      else if(go(c,"getTextInfo")!=null) applyFontTo(c,font,dfont,szv,onlySys,cnt); }
    if(cnt[0]+cnt[1]==0) return "ERROR: 바꿀 요소가 없습니다 (라벨 0, 데이터 0, 건너뜀 "+cnt[2]+")"+(onlySys?" — only_system=true 인데 System 글꼴 요소가 없음":"");
    String wrote=save(rf,output,path); Object[] after=fontProfile(open(output));
    return "OK: 글꼴 적용 — 라벨 "+cnt[0]+"곳"+(font.isEmpty()?"":"→"+font)+", 데이터 "+cnt[1]+"곳"+(dfont.isEmpty()?"":"→"+dfont)+(szv>0?", 크기 "+szv:"")+(cnt[2]>0?", 건너뜀 "+cnt[2]:"")+(secF!=null?" ("+section+" 만)":"")+"\n  전: 라벨 "+before[0]+" 데이터 "+before[1]+" System "+((java.util.List<?>)before[3]).size()+"곳\n  후: 라벨 "+after[0]+" 데이터 "+after[1]+" System "+((java.util.List<?>)after[3]).size()+"곳, wrote "+wrote;
  }
  static void applyFontTo(Object o,String font,String dfont,short sz,boolean onlySys,int[] cnt){
    Object ti=go(o,"getTextInfo"); if(ti==null) return; String fn=g(ti,"getFontName"); if(onlySys&&!isSystemFont(fn)){ cnt[2]++; return; }
    boolean bound=go(o,"getApplyValueField")!=null; String target=bound?dfont:font; boolean did=false;
    if(!target.isEmpty()&&!target.equals(fn)){ call(ti,"setFontName",String.class,target); did=true; }
    if(sz>0){ call(ti,"setFontSize",short.class,sz); did=true; }
    if(did) cnt[bound?1:0]++; else cnt[2]++;
  }
  /** lint: 같은 스타일(x·너비·글꼴·크기·굵게·테두리)로 세로 연속인 글상자 묶음 */
  static java.util.List<String> stackedLabelRuns(TheReportFile rf){
    java.util.List<String> out=new java.util.ArrayList<>(); java.util.Map<Object,java.util.List<ControlLabel>> bySub=new java.util.LinkedHashMap<>(); java.util.Map<Object,String> bandOf=new java.util.HashMap<>();
    for(Object[] e: allControls(rf)){ if(!(e[3] instanceof ControlLabel)) continue; ControlLabel l=(ControlLabel)e[3]; if(!l.getVisible()) continue; bySub.computeIfAbsent(e[1],k->new java.util.ArrayList<>()).add(l); bandOf.put(e[1],((Section)e[0]).getClass().getSimpleName().replace("Section","")); }
    for(java.util.Map.Entry<Object,java.util.List<ControlLabel>> en: bySub.entrySet()){ java.util.List<ControlLabel> ls=en.getValue(); ls.sort((a,b)->{ int d=ix(a,"getY1")-ix(b,"getY1"); return d!=0?d:ix(a,"getX1")-ix(b,"getX1"); });
      java.util.List<ControlLabel> run=new java.util.ArrayList<>();
      for(ControlLabel l: ls){ if(!run.isEmpty()&&!stackable(run.get(run.size()-1),l)){ flushRun(run,bandOf.get(en.getKey()),out); run.clear(); } run.add(l); }
      flushRun(run,bandOf.get(en.getKey()),out); }
    return out;
  }
  static void flushRun(java.util.List<ControlLabel> run,String band,java.util.List<String> out){
    if(run.size()<2) return; StringBuilder nm=new StringBuilder(); for(ControlLabel l: run) nm.append(nm.length()>0?",":"").append(l.getName());
    boolean allText=true; for(ControlLabel l: run) if(go(l,"getApplyValueField")!=null) allText=false;
    out.add((allText?"!":"")+band+": 글상자 "+run.size()+"개("+nm+") 가 같은 스타일(x·너비·글꼴)로 세로 연속 — 요소 하나로 합치세요: crf_merge_labels(names=\""+nm+"\", into="+(allText?"table|label":"table")+")");
  }
  static boolean stackable(ControlLabel a,ControlLabel b){
    if(Math.abs(ix(a,"getX1")-ix(b,"getX1"))>5||Math.abs(ix(a,"getWidth")-ix(b,"getWidth"))>5) return false;
    int gap=ix(b,"getY1")-(ix(a,"getY1")+ix(a,"getHeight")); if(gap<-5||gap>Math.max(30,Math.min(ix(a,"getHeight"),ix(b,"getHeight"))/2)) return false;
    Object ta=go(a,"getTextInfo"), tb=go(b,"getTextInfo");
    if(!java.util.Objects.equals(g(ta,"getFontName"),g(tb,"getFontName"))||!java.util.Objects.equals(g(ta,"getFontSize"),g(tb,"getFontSize"))||!java.util.Objects.equals(g(ta,"getFontBold"),g(tb,"getFontBold"))) return false;
    if(!java.util.Objects.equals(g(a,"getLineStyle"),g(b,"getLineStyle"))||!java.util.Objects.equals(g(a,"getBackStyle"),g(b,"getBackStyle"))) return false;
    return true;
  }
  /** lint: 엑셀 저장 격자 — 엑셀로 내보내는 밴드(페이지 바닥글 제외)의 모든 세로선(표 열 경계, 글상자/이미지/서브리포트의 좌·우, 선의 x)이 본문 표 열 경계(±10 = 1mm)에 있어야 엑셀에서 열이 추가로 쪼개지지 않는다.
   *  본문에 표가 없으면(문서형) 검사하지 않는다. 결과 첫 줄은 요약(본문 경계 N개 + 어긋난 세로선 M개 → 엑셀 열 최대 N+M), 이후 요소별로 어긋난 변과 가까운 경계를 제시. */
  static java.util.List<String> excelGridIssues(TheReportFile rf){
    java.util.List<String> out=new java.util.ArrayList<>(); java.util.TreeSet<Integer> body=new java.util.TreeSet<>(); java.util.List<Object[]> others=new java.util.ArrayList<>();
    for(Object[] e: allControls(rf)){ Control c=(Control)e[3]; if(!c.getVisible()) continue; String band=((Section)e[0]).getClass().getSimpleName().replace("Section",""); boolean isTable="ControlTable".equals(c.getClass().getSimpleName());
      if(e[0] instanceof SectionDetail && isTable){ body.addAll(tableBounds(c)); continue; }
      if(band.equals("PageFooter")) continue;   // 엑셀 내보내기에서 빠지는 밴드(페이지번호·로그 등) — 맞출 필요 없음
      others.add(new Object[]{band,c,isTable}); }
    if(body.size()<2) return out;
    java.util.TreeSet<Integer> extra=new java.util.TreeSet<>(); java.util.List<String> lines=new java.util.ArrayList<>();
    for(Object[] o: others){ Control c=(Control)o[1]; java.util.List<Object[]> edges=new java.util.ArrayList<>();  // {이름, x}
      if((Boolean)o[2]){ java.util.List<Integer> b=tableBounds(c); for(int i=0;i<b.size();i++) edges.add(new Object[]{i==0?"왼쪽":i==b.size()-1?"오른쪽":"열"+(i-1)+"|"+i+" 경계",b.get(i)}); }
      else { int x=ix(c,"getX1"), w=ix(c,"getWidth"); if(w<=0){ edges.add(new Object[]{"x",x}); } else { edges.add(new Object[]{"왼쪽",x}); edges.add(new Object[]{"오른쪽",x+w}); } }
      java.util.List<String> bad=new java.util.ArrayList<>();
      for(Object[] ed: edges){ int x=(Integer)ed[1]; Integer lo=body.floor(x), hi=body.ceiling(x); boolean ok=(lo!=null&&x-lo<=10)||(hi!=null&&hi-x<=10); if(ok) continue; extra.add(x);
        bad.add(ed[0]+" "+x+"(가까운 경계 "+(lo==null?"-":lo)+"/"+(hi==null?"-":hi)+")"); }
      if(!bad.isEmpty()) lines.add("   - "+o[0]+" "+c.getClass().getSimpleName().replace("Control","")+" \""+c.getName()+"\": "+String.join(", ",bad)); }
    if(lines.isEmpty()) return out;
    out.add("엑셀 격자: 본문 표 경계 "+body.size()+"개 외에 어긋난 세로선 "+extra.size()+"개("+extra+") — 엑셀 저장 시 열이 최대 "+(body.size()-1+extra.size())+"개로 쪼개짐(페이지 바닥글 제외). 표 열 경계·글상자 좌우를 본문 경계 "+body+" 에 맞추세요(crf_set_label left/width, crf_table_cols resize):\n"+String.join("\n",lines));
    return out;
  }
  static java.util.List<Integer> tableBounds(Control t){ java.util.List<Integer> b=new java.util.ArrayList<>(); int x=ix(t,"getX1"); b.add(x); int cols=ix(t,"getColumnCount"); for(int c=0;c<cols;c++){ Object col=null; try{ col=t.getClass().getMethod("getTableColumn",int.class).invoke(t,c); }catch(Exception e){} x+=ix(col,"getWidth"); b.add(x); } return b; }
}
