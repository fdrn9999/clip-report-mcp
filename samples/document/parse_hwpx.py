# HWPX 양식 파서: unzip 한 폴더를 주면 문단(정렬/글꼴/색/크기)·표(셀 크기/테두리)·도형(위치) 을 덤프한다.
# usage: PYTHONIOENCODING=utf-8 python parse_hwpx.py <unzipped_hwpx_dir> > dump.txt
# 세로 위치는 hp:lineseg vertpos (HWPUNIT×25.4/7200 = mm) 를 보라 — 문서형 리포트 좌표(0.1mm)로 그대로 환산 가능.
# ★ 표/도형이 들어 있는 문단의 align 도 함께 찍는다 (treatAsChar=1 표는 이 정렬을 따라 좌/우/가운데 배치됨).
import sys, re, xml.etree.ElementTree as ET
ns = {'hp':'http://www.hancom.co.kr/hwpml/2011/paragraph','hs':'http://www.hancom.co.kr/hwpml/2011/section','hh':'http://www.hancom.co.kr/hwpml/2011/head','hc':'http://www.hancom.co.kr/hwpml/2011/core'}
d = sys.argv[1]
head = ET.parse(f'{d}/Contents/header.xml').getroot()
# charPr map
charpr = {}
for cp in head.iter('{%s}charPr' % ns['hh']):
    cid = cp.get('id')
    charpr[cid] = dict(height=cp.get('height'), color=cp.get('textColor'), bold=cp.find('hh:bold', ns) is not None, underline=(cp.find('hh:underline',ns).get('type') if cp.find('hh:underline',ns) is not None else None))
    fr = cp.find('hh:fontRef', ns)
    charpr[cid]['font'] = fr.get('hangul') if fr is not None else None
fonts = {}
for ff in head.iter('{%s}fontface' % ns['hh']):
    for f in ff.findall('hh:font', ns):
        fonts[f.get('id')] = f.get('face')
parapr = {}
for pp in head.iter('{%s}paraPr' % ns['hh']):
    al = pp.find('hh:align', ns)
    ls = pp.find('hh:lineSpacing', ns)
    mg = pp.find('hh:margin', ns)
    parapr[pp.get('id')] = dict(align=al.get('horizontal') if al is not None else None, ls=(ls.get('value') if ls is not None else None), indent=(mg.find('hc:intent',ns).get('value') if mg is not None and mg.find('hc:intent',ns) is not None else None), left=(mg.find('hc:left',ns).get('value') if mg is not None and mg.find('hc:left',ns) is not None else None))
sec = ET.parse(f'{d}/Contents/section0.xml').getroot()
# page setup
for pp in sec.iter('{%s}pagePr' % ns['hp']):
    print('PAGE', pp.attrib, {m.tag.split('}')[1]:m.attrib for m in pp})
    mg = pp.find('hp:margin', ns)
    print('MARGIN', mg.attrib if mg is not None else None)
def dump_para(p, depth=0, ctx=''):
    ppid = p.get('paraPrIDRef')
    pa = parapr.get(ppid, {})
    runs = []
    for run in p.findall('hp:run', ns):
        cid = run.get('charPrIDRef')
        cp = charpr.get(cid, {})
        for child in run:
            tag = child.tag.split('}')[1]
            if tag == 't':
                txt = ''.join(child.itertext())
                if txt.strip():
                    runs.append((txt, cid, cp.get('height'), cp.get('color'), cp.get('bold'), fonts.get(cp.get('font')), cp.get('underline')))
            elif tag == 'tbl':
                print('  '*depth + f'[TABLE rows={child.get("rowCnt")} cols={child.get("colCnt")} anchorParaAlign={pa.get("align")}]')
                sz = child.find('hp:sz', ns); pos = child.find('hp:pos', ns)
                print('  '*depth + f'  sz={sz.attrib if sz is not None else None} pos={pos.attrib if pos is not None else None}')
                for tr in child.findall('hp:tr', ns):
                    for tc in tr.findall('hp:tc', ns):
                        ca = tc.find('hp:cellAddr', ns); cs = tc.find('hp:cellSpan', ns); csz = tc.find('hp:cellSz', ns)
                        print('  '*depth + f'  <cell r{ca.get("rowAddr")}c{ca.get("colAddr")} span={cs.get("rowSpan")}x{cs.get("colSpan")} sz={csz.get("width")}x{csz.get("height")} border={tc.get("borderFillIDRef")}>')
                        for sp in tc.findall('hp:subList/hp:p', ns):
                            dump_para(sp, depth+2)
            elif tag in ('rect','line','pic','container','ellipse','textart'):
                sz = child.find('hp:sz', ns); pos = child.find('hp:pos', ns); off = child.find('hp:offset', ns)
                print('  '*depth + f'[{tag.upper()} anchorParaAlign={pa.get("align")} sz={sz.attrib if sz is not None else None} pos={pos.attrib if pos is not None else None} off={off.attrib if off is not None else None}]')
                for sp in child.findall('.//hp:subList/hp:p', ns):
                    dump_para(sp, depth+2)
    if runs:
        print('  '*depth + f'P(align={pa.get("align")},ls={pa.get("ls")},ind={pa.get("indent")},left={pa.get("left")}):')
        for r in runs:
            print('  '*depth + f'   "{r[0]}" [cp{r[1]} h={r[2]} col={r[3]} b={r[4]} f={r[5]} u={r[6]}]')
    else:
        # empty paragraph
        has_tbl = p.find('.//hp:tbl', ns) is not None
        if not has_tbl:
            print('  '*depth + f'P(empty align={pa.get("align")},ls={pa.get("ls")})')
for p in sec.findall('hp:p', ns):
    dump_para(p)
