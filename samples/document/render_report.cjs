// 실서버 렌더 검증: CDP(9222) 로 띄운 Chrome 에 붙어 /report/callReport.jsp 에 POST 하고 스크린샷을 남긴다.
// usage: NODE_PATH=<global node_modules with playwright> node render_report.cjs <filePath(확장자 없이)> '<paramsJson>' <outPng> [ratio] [width]
//   width: 스크린샷 뷰포트 폭(px, 기본 1000). 가로(Landscape) 리포트는 1400 이상을 주지 않으면 오른쪽이 잘린다. 환경변수 CLIP_RENDER_WIDTH 로도 지정.
//   예: node render_report.cjs "sch/ssrm/ssrmet/ssrmet0220_prn01" '{"syy":"2027","smtCd":"10","empno":"2003054"}' out.png 100
// 전제: Chrome --remote-debugging-port=9222 로 실행 + ERP(localhost:8080) 로그인 세션. BASE 는 환경에 맞게 수정.
const BASE = process.env.CLIP_ERP_BASE || 'http://localhost:8080';
const { chromium } = require('playwright');
(async () => {
  const [filePath, paramsJson, outPng, ratio, widthArg] = process.argv.slice(2);
  const width = parseInt(widthArg || process.env.CLIP_RENDER_WIDTH || '1000', 10);
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  const page = await ctx.newPage();
  page.on('dialog', d => d.accept().catch(() => {}));
  const params = JSON.parse(paramsJson);
  const reportParams = Buffer.from(JSON.stringify(params), 'utf8').toString('base64');
  await page.goto(BASE + '/nx/erp.html', { waitUntil: 'domcontentloaded' }).catch(() => {});
  await page.evaluate(({ filePath, reportParams, ratio }) => {
    const f = document.createElement('form');
    f.method = 'POST'; f.action = '/report/callReport.jsp';
    const add = (k, v) => { const i = document.createElement('input'); i.type = 'hidden'; i.name = k; i.value = v; f.appendChild(i); };
    add('reportParams', reportParams); add('paramType', 'query'); add('filePath', filePath);
    add('reportBtn', 'PS'); add('useGlio', 'false'); add('scrollView', '1'); add('persInfo', '0'); add('toolbar', '1'); add('ratio', ratio || '100');
    document.body.appendChild(f); f.submit();
  }, { filePath, reportParams, ratio });
  await page.waitForLoadState('load').catch(() => {});
  await page.waitForTimeout(6000);
  const info = await page.evaluate(() => ({ url: location.href, title: document.title, len: document.body.innerText.length, text: document.body.innerText.slice(0, 1500) }));
  console.log(JSON.stringify({ url: info.url, title: info.title, len: info.len }));
  console.log(info.text);
  await page.setViewportSize({ width, height: 1500 });
  await page.waitForTimeout(1000);
  await page.screenshot({ path: outPng, fullPage: true });
  console.log('screenshot', outPng);
  await page.close();
  await browser.close().catch(() => {});
})().catch(e => { console.error('ERR', e); process.exit(1); });
