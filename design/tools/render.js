// Renders the Remember Me HTML mockups to PNG at 412x915 dp (device scale 2 -> 824x1830 px), light and dark,
// plus the 200% font-size and English variants, then builds before/after comparison images from the app's
// current screenshots (screenshots/*.png, read only).
// Usage (PowerShell, from the repo root):
//   $env:IC_PLAYWRIGHT_DIR = "E:\fast kiwi pro neq\tools\pw"; node design\tools\render.js [screen ...]
const path = require('path');
const fs = require('fs');
const pwDir = process.env.IC_PLAYWRIGHT_DIR || 'E:\\fast kiwi pro neq\\tools\\pw';
const { chromium } = require(path.join(pwDir, 'node_modules', 'playwright'));

const DESIGN = path.resolve(__dirname, '..');
const MOCK = path.join(DESIGN, 'mockups');
const SHOTS = path.join(DESIGN, 'shots');
const CURRENT = path.resolve(DESIGN, '..', 'screenshots'); // the app's own screenshots ("before"), read only
// mockup file -> { before: current screenshot, themes, extra: [{ out, query, theme }] }
const SCREENS = {
  home: { before: '01-overdue-ar.png', extra: [{ out: 'home-ar-200', query: '?fs=2', theme: 'light' }] },
  'home-en': { before: '08-today-en.png', themes: [], extra: [{ out: 'home-en-light', theme: 'light' }], compareFrom: 'home-en-light', compareCaption: ['Before (0.1.0)', 'After (proposed)'] },
  'home-empty': {},
  edit: { before: '04-new-reminder-ar.png' },
  'edit-repeat': {},
  alert: { before: '05-alert-ar.png', extra: [{ out: 'alert-ar-200', query: '?fs=2', theme: 'light' }] },
  background: { before: '07-background-ar.png' },
  settings: { before: '06-settings-ar.png' },
  'settings-dialog': {},
  trash: {},
  about: {},
  notification: {},
  widget: {},
};

(async () => {
  const only = process.argv.slice(2);
  fs.mkdirSync(SHOTS, { recursive: true });
  const browser = await chromium.launch(fs.existsSync(path.join(process.env.LOCALAPPDATA || '', 'ms-playwright')) ? {} : { channel: 'msedge' });
  const ctx = await browser.newContext({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  const made = [];
  const url = (file, q = '') => 'file:///' + file.replace(/\\/g, '/') + q;
  async function shoot(file, theme, out, query) {
    await page.setViewportSize({ width: 412, height: 915 });
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    await page.goto(url(file, query || ''));
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(200);
    const target = path.join(SHOTS, out + '.png');
    await page.screenshot({ path: target, clip: { x: 0, y: 0, width: 412, height: 915 } });
    made.push(target);
  }
  for (const [name, cfg] of Object.entries(SCREENS)) {
    if (only.length && !only.includes(name)) continue;
    const file = path.join(MOCK, name + '.html');
    if (!fs.existsSync(file)) { console.log('skip (no mockup)', name); continue; }
    for (const theme of cfg.themes || ['light', 'dark']) await shoot(file, theme, `${name}-${theme}`);
    for (const x of cfg.extra || []) await shoot(file, x.theme, x.out, x.query);
    if (cfg.before) {
      const before = path.join(CURRENT, cfg.before);
      const after = path.join(SHOTS, (cfg.compareFrom || `${name}-light`) + '.png');
      const [cb, ca] = cfg.compareCaption || ['قبل (0.1.0)', 'بعد (المقترح)'];
      const html = `<!doctype html><html dir="rtl"><head><meta charset="utf-8"><style>
        @font-face{font-family:NSA;src:url("${url(path.join(MOCK, 'fonts', 'NotoSansArabic-VF.ttf'))}");font-weight:100 900}
        body{margin:0;background:#E7ECE7;font-family:NSA,sans-serif;width:900px}
        .wrap{display:flex;gap:28px;padding:20px 24px 24px;justify-content:center}
        figure{margin:0;display:flex;flex-direction:column;align-items:center;gap:10px}
        figcaption{font-size:22px;font-weight:700;color:#181D1A}
        figcaption.after{color:#246A4B}
        img{width:412px;height:886px;object-fit:cover;object-position:top;border-radius:18px;box-shadow:0 2px 10px rgba(0,0,0,.25);background:#fff}
        </style></head><body><div class="wrap">
        <figure><figcaption>${cb}</figcaption><img src="${url(before)}"></figure>
        <figure><figcaption class="after">${ca}</figcaption><img src="${url(after)}"></figure>
        </div></body></html>`;
      const tmp = path.join(__dirname, '_compare.html');
      fs.writeFileSync(tmp, html);
      await page.setViewportSize({ width: 900, height: 980 });
      await page.emulateMedia({ colorScheme: 'light' });
      await page.goto(url(tmp));
      await page.evaluate(() => document.fonts.ready);
      await page.waitForTimeout(250);
      const out = path.join(SHOTS, `compare-${name}.png`);
      await page.screenshot({ path: out, fullPage: true });
      made.push(out);
      fs.unlinkSync(tmp);
    }
  }
  await browser.close();
  console.log(made.map(f => path.basename(f)).join('\n'));
})().catch(e => { console.error(e); process.exit(1); });
