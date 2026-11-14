// Renders the Remember Me 1.0.0 (v2) HTML mockups to PNG: every design/v2/mockups/*.html in light and dark at
// 412x915 dp (device scale 2 -> 824x1830 px), the 200 % text variants, the tablet layout, and before/after pairs
// against the app's QC screenshots (screenshots/qc/ar-light-*.png, read only).
// Usage (PowerShell, from the repo root):
//   $env:IC_PLAYWRIGHT_DIR = "E:\fast kiwi pro neq\tools\pw"; node design\v2\tools\render.js [screen ...]
const path = require('path');
const fs = require('fs');
const pwDir = process.env.IC_PLAYWRIGHT_DIR || 'E:\\fast kiwi pro neq\\tools\\pw';
const { chromium } = require(path.join(pwDir, 'node_modules', 'playwright'));

const V2 = path.resolve(__dirname, '..');
const MOCK = path.join(V2, 'mockups');
const SHOTS = path.join(V2, 'shots');
const FONT = path.join(V2, 'kit', 'fonts', 'NotoSansArabic-VF.ttf');
const QC = path.resolve(V2, '..', '..', 'screenshots', 'qc');
// Extra renders: 200 % text (Android 14 non-linear table), tablet (1280x800 dp landscape).
const EXTRA = {
  home: [{ out: 'home-ar-200', query: '?fs=2', theme: 'light' }],
  alert: [{ out: 'alert-ar-200', query: '?fs=2', theme: 'light' }],
  'home-tablet': { size: [1280, 800] },
};
// After mockup <- before screenshot (QC, v0.1.x).
const COMPARE = {
  home: 'ar-light-01-home.png',
  quickadd: 'ar-light-02-new.png',
  edit: 'ar-light-03-edit.png',
  alert: 'ar-light-04-alert.png',
  settings: 'ar-light-05-settings.png',
  health: 'ar-light-06-background.png',
};

(async () => {
  const only = process.argv.slice(2);
  fs.mkdirSync(SHOTS, { recursive: true });
  const browser = await chromium.launch(fs.existsSync(path.join(process.env.LOCALAPPDATA || '', 'ms-playwright')) ? {} : { channel: 'msedge' });
  const ctx = await browser.newContext({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.error('page error:', e.message));
  const made = [];
  const url = (file, q = '') => 'file:///' + file.replace(/\\/g, '/') + q;
  async function shoot(file, theme, out, query, size = [412, 915]) {
    await page.setViewportSize({ width: size[0], height: size[1] });
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    await page.goto(url(file, query || ''));
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(150);
    const target = path.join(SHOTS, out + '.png');
    await page.screenshot({ path: target, clip: { x: 0, y: 0, width: size[0], height: size[1] } });
    made.push(target);
  }
  const names = fs.readdirSync(MOCK).filter(f => f.endsWith('.html')).map(f => f.slice(0, -5)).sort();
  for (const name of names) {
    if (only.length && !only.includes(name)) continue;
    const file = path.join(MOCK, name + '.html');
    const ex = EXTRA[name];
    const size = ex && ex.size;
    for (const theme of ['light', 'dark']) await shoot(file, theme, `${name}-${theme}`, '', size);
    if (Array.isArray(ex)) for (const x of ex) await shoot(file, x.theme, x.out, x.query);
    const before = COMPARE[name] && path.join(QC, COMPARE[name]);
    if (before && fs.existsSync(before)) {
      const after = path.join(SHOTS, `${name}-light.png`);
      const html = `<!doctype html><html dir="rtl"><head><meta charset="utf-8"><style>
        @font-face{font-family:NSA;src:url("${url(FONT)}");font-weight:100 900}
        body{margin:0;background:#E7ECE7;font-family:NSA,sans-serif;width:900px}
        .wrap{display:flex;gap:28px;padding:20px 24px 24px;justify-content:center}
        figure{margin:0;display:flex;flex-direction:column;align-items:center;gap:10px}
        figcaption{font-size:22px;font-weight:700;color:#181D1A}
        figcaption.after{color:#246A4B}
        img{width:412px;height:915px;object-fit:cover;object-position:top;border-radius:18px;box-shadow:0 2px 10px rgba(0,0,0,.25);background:#fff}
        </style></head><body><div class="wrap">
        <figure><figcaption>قبل (0.1)</figcaption><img src="${url(before)}"></figure>
        <figure><figcaption class="after">بعد (1.0.0)</figcaption><img src="${url(after)}"></figure>
        </div></body></html>`;
      const tmp = path.join(__dirname, '_compare.html');
      fs.writeFileSync(tmp, html);
      await page.setViewportSize({ width: 900, height: 1000 });
      await page.emulateMedia({ colorScheme: 'light' });
      await page.goto(url(tmp));
      await page.evaluate(() => document.fonts.ready);
      await page.waitForTimeout(200);
      const out = path.join(SHOTS, `compare-${name}.png`);
      await page.screenshot({ path: out, fullPage: true });
      made.push(out);
      fs.unlinkSync(tmp);
    }
  }
  await browser.close();
  console.log(made.length + ' PNGs\n' + made.map(f => path.basename(f)).join('\n'));
})().catch(e => { console.error(e); process.exit(1); });
