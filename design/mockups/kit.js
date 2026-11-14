// Mockup helpers for Remember Me. Icons are hand-drawn stand-ins in the Material Symbols Rounded style
// (24 dp grid, 2 dp stroke); the app uses Icons.Rounded.* from material-icons-extended (Apache-2.0), listed in DESIGN-SPEC.md.
// ?fs=2 renders with Android 14+ non-linear font scaling at 200% (AOSP FontScaleConverter table for 2.0).
(function () {
  const S = (d, extra = '') => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${d}${extra}</svg>`;
  const F = d => `<svg viewBox="0 0 24 24" fill="currentColor">${d}</svg>`;
  const P = d => `<path d="${d}"/>`;
  function gear() {
    let d = '';
    const n = 8, ro = 10, ri = 7.6;
    for (let k = 0; k < n; k++) {
      const a0 = (k / n) * 2 * Math.PI, w = Math.PI / n * 0.55;
      const pts = [[ri, a0 - w * 1.4], [ro, a0 - w * 0.75], [ro, a0 + w * 0.75], [ri, a0 + w * 1.4]];
      pts.forEach(([r, a], j) => { d += `${k === 0 && j === 0 ? 'M' : 'L'}${(12 + r * Math.cos(a)).toFixed(2)} ${(12 + r * Math.sin(a)).toFixed(2)} `; });
    }
    return S(P(d + 'Z') + '<circle cx="12" cy="12" r="3.2"/>');
  }
  const bellPath = 'M12 3.5c-3.3 0-5.5 2.6-5.5 6.2v3.6L4.8 16.5h14.4l-1.7-3.2V9.7c0-3.6-2.2-6.2-5.5-6.2z M9.8 19.2a2.3 2.3 0 0 0 4.4 0';
  const I = {
    search: S('<circle cx="10.5" cy="10.5" r="6.5"/>' + P('M15.5 15.5 20.5 20.5')),
    settings: gear(),
    more: F('<circle cx="12" cy="5.5" r="1.9"/><circle cx="12" cy="12" r="1.9"/><circle cx="12" cy="18.5" r="1.9"/>'),
    back: S(P('M19.5 12H5 M11 6l-6 6 6 6')),
    close: S(P('M6 6l12 12M18 6 6 18')),
    check: S(P('M5 12.5l4.5 4.5L19 7.5')),
    add: S(P('M12 5v14M5 12h14')),
    bell: S(P(bellPath)),
    bellring: S(P(bellPath) + P('M3 8.5c.4-2 1.4-3.6 2.8-4.8 M21 8.5c-.4-2-1.4-3.6-2.8-4.8')),
    bellfill: F('<path d="M12 3c-3.6 0-6 2.8-6 6.7v3.4L4.2 16.6c-.3.6.1 1.4.8 1.4h14c.7 0 1.1-.8.8-1.4L18 13.1V9.7C18 5.8 15.6 3 12 3z"/><path d="M9.5 19.3h5a2.5 2.5 0 0 1-5 0z"/>'),
    repeat: S(P('M17 3.5l3 3-3 3 M4 11.5v-1.5a3.5 3.5 0 0 1 3.5-3.5H20 M7 20.5l-3-3 3-3 M20 12.5V14a3.5 3.5 0 0 1-3.5 3.5H4')),
    flag: S(P('M5.5 21V4.5 M5.5 4.5h11l-2 4 2 4h-11')),
    flagfill: F('<path d="M5 3.5h12.6c.7 0 1.1.8.8 1.4L16.6 8.5l1.8 3.6c.3.6-.1 1.4-.8 1.4H6.8V20.5a.9.9 0 0 1-1.8 0z"/>'),
    clock: S('<circle cx="12" cy="12" r="8.5"/>' + P('M12 7.5V12l3 2')),
    event: S('<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/>' + P('M3.5 10h17 M8 3v4 M16 3v4') + '<rect x="13.5" y="13.5" width="3.5" height="3.5" rx=".6" fill="currentColor" stroke="none"/>'),
    notes: S(P('M4.5 6.5h15 M4.5 12h15 M4.5 17.5h9')),
    edit: S(P('M14.5 5.5l4 4L9 19H5v-4z M12.5 7.5l4 4')),
    warning: S(P('M12 3.8 2.8 19.8h18.4z M12 10v4.3 M12 17v.1')),
    warnfill: F('<path d="M10.3 3.9a2 2 0 0 1 3.4 0l8.2 14.2a2 2 0 0 1-1.7 3H3.8a2 2 0 0 1-1.7-3z"/><path d="M12 9v5 M12 17.2v.1" style="stroke:var(--icon-cut,#fff)" stroke-width="2.2" stroke-linecap="round"/>'),
    errorfill: F('<circle cx="12" cy="12" r="9.5"/><path d="M12 7v6 M12 16.3v.1" style="stroke:var(--icon-cut,#fff)" stroke-width="2.2" stroke-linecap="round"/>'),
    okfill: F('<circle cx="12" cy="12" r="9.5"/><path d="M7.5 12.3l3 3 6-6.3" fill="none" style="stroke:var(--icon-cut,#fff)" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>'),
    infofill: F('<circle cx="12" cy="12" r="9.5"/><path d="M12 11v6 M12 7.6v.1" style="stroke:var(--icon-cut,#fff)" stroke-width="2.2" stroke-linecap="round"/>'),
    lightbulb: S(P('M9 18h6 M10 21h4 M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2V16h5v-.1c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z')),
    info: S('<circle cx="12" cy="12" r="9"/>' + P('M12 11v6 M12 7.6v.1')),
    done: F('<circle cx="12" cy="12" r="10"/><path d="M7.3 12.4l3.1 3.1 6.3-6.6" fill="none" style="stroke:var(--icon-cut,#fff)" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>'),
    notifications: S(P(bellPath)),
    alarm: S('<circle cx="12" cy="13" r="7.5"/>' + P('M12 9.5V13l2.5 1.5 M4 5.5l3-2.5 M20 5.5l-3-2.5')),
    fullscreen: S('<rect x="6" y="2.5" width="12" height="19" rx="2.5"/>' + P('M10 18.5h4')),
    battery: S('<rect x="7" y="4" width="10" height="17.5" rx="2"/>' + P('M10 2.5h4 M12 9v4.5 M10 11.5l2 2 2-2')),
    hourglass: S(P('M6.5 3.5h11 M6.5 20.5h11 M7.5 3.5c0 4 4.5 5.5 4.5 8.5s-4.5 4.5-4.5 8.5 M16.5 3.5c0 4-4.5 5.5-4.5 8.5s4.5 4.5 4.5 8.5')),
    dnd: S('<circle cx="12" cy="12" r="9"/>' + P('M7.5 12h9')),
    phone: S('<rect x="6.5" y="2.5" width="11" height="19" rx="2.5"/>' + P('M10.5 5.5h3 M11.5 18.5h1')),
    chevron: S(P('M9.5 5.5l6.5 6.5-6.5 6.5')),
    expand: S(P('M6 9.5l6 6 6-6')),
    collapse: S(P('M6 14.5l6-6 6 6')),
    restore: S(P('M4 12a8 8 0 1 0 2.4-5.7 M4 4v4.5h4.5') + P('M12 8v4.5l3 1.8')),
    trash: S(P('M4 6.5h16 M9 6.5V4.5h6v2 M6.3 6.5l.9 12.7a2 2 0 0 0 2 1.8h5.6a2 2 0 0 0 2-1.8l.9-12.7 M10 11v6 M14 11v6')),
    save: S(P('M12 3.5v11 M7 10l5 5 5-5 M4.5 20h15')),
    upload: S(P('M12 15V4 M7 8.5l5-5 5 5 M4.5 20h15')),
    globe: S('<circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="3.8" ry="9"/>' + P('M3 12h18')),
    contrast: S('<circle cx="12" cy="12" r="9"/>', '<path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor" stroke="none"/>'),
    calendar: S('<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/>' + P('M3.5 10h17 M8 3v4 M16 3v4 M7.5 14h2 M11 14h2 M14.5 14h2 M7.5 17h2 M11 17h2')),
    month: S('<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/>' + P('M3.5 10h17 M8 3v4 M16 3v4 M9 13.5h6 M9 17h4')),
    volume: S(P('M4 9.5h3.5L12 5.5v13l-4.5-4H4z M15.5 9a4 4 0 0 1 0 6 M18 6.5a7.5 7.5 0 0 1 0 11')),
    again: S(P(bellPath) + P('M19.5 3.5v3.5H16')),
    lock: S('<rect x="5" y="10.5" width="14" height="10.5" rx="2.5"/>' + P('M8.3 10.5V7.8a3.7 3.7 0 0 1 7.4 0v2.7') + '<circle cx="12" cy="15.7" r="1" fill="currentColor"/>'),
    privacy: S(P('M12 2.8 4.5 5.8v5.7c0 4.6 3.2 8.6 7.5 9.7 4.3-1.1 7.5-5.1 7.5-9.7V5.8z M8.8 12l2.2 2.2 4.3-4.4')),
    history: S(P('M4 12a8 8 0 1 0 2.4-5.7 M4 4v4.5h4.5') + P('M12 8v4.5l3 1.8')),
    label: S(P('M3.5 12.6V5a1.5 1.5 0 0 1 1.5-1.5h7.6l8 8a1.5 1.5 0 0 1 0 2.1l-7.1 7.1a1.5 1.5 0 0 1-2.1 0z') + '<circle cx="8" cy="8" r="1.4" fill="currentColor"/>'),
    snooze: S('<circle cx="12" cy="13" r="7.5"/>' + P('M9.5 10.5h5l-5 5h5 M4 5.5l3-2.5 M20 5.5l-3-2.5')),
    open: S(P('M14 3.5h6.5V10 M20.5 3.5 11 13 M18 14v4.5a2 2 0 0 1-2 2H5.5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2H10')),
    doc: S(P('M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z M14 3v5h5 M8.5 13h7 M8.5 16.5h5')),
    minus: S(P('M5 12h14')),
    wifi: F('<path d="M12 20.5 1.5 8.7A15 15 0 0 1 22.5 8.7z"/>'),
    batt: F('<rect x="7" y="3" width="10" height="19" rx="2"/><rect x="9.5" y="1.5" width="5" height="2" rx="1"/>'),
    signal: F('<path d="M21.5 2.5v19h-19z"/>'),
  };
  // Directional icons that flip in RTL (Material bidirectionality guidance). Clock hands, bells, checks: never.
  const MIRRORED = new Set(['back', 'chevron', 'open', 'notes', 'label', 'volume']);
  // The brand mark: the Windows icon (deep-green tile, lavender bell, lime corner). Fixed colours in both themes.
  const brand = `<svg viewBox="0 0 256 256"><rect width="256" height="256" rx="56" fill="#2F6249"/><rect x="1" y="1" width="254" height="254" rx="55" fill="#123B2A"/>
    <path fill="#C5B3FF" d="M124 66C148.64 66 168 90.36 168 124C168 151 186 158 188 160H60C62 158 80 151 80 124C80 90.36 99.36 66 124 66ZM66 153H182A10 10 0 0 1 192 163V163A10 10 0 0 1 182 173H66A10 10 0 0 1 56 163V163A10 10 0 0 1 66 153ZM113 61A11 11 0 1 0 135 61A11 11 0 1 0 113 61ZM107 181A17 17 0 0 0 141 181Z"/>
    <path fill="#B9F34A" d="M228.8 197.69L223.85 223.85L197.69 228.8L202.64 202.64Z"/></svg>`;
  window.KIT = { icons: I, brand };

  // Android 14+ non-linear font scaling, 200% (frameworks/base FontScaleConverterFactory, scale 2.0).
  const FROM = [8, 10, 12, 14, 18, 20, 24, 30, 100], TO = [16, 20, 24, 26, 30, 34, 36, 38, 100];
  function conv(sp) {
    if (sp <= FROM[0]) return sp * TO[0] / FROM[0];
    for (let k = 1; k < FROM.length; k++) if (sp <= FROM[k]) {
      const t = (sp - FROM[k - 1]) / (FROM[k] - FROM[k - 1]); return TO[k - 1] + t * (TO[k] - TO[k - 1]);
    }
    return sp;
  }
  function scaleFonts() {
    const els = [...document.querySelectorAll('.phone, .phone *')].filter(e => !e.closest('svg') && !e.closest('.statusbar') && !e.matches('i[data-i]'));
    const sizes = els.map(e => { const cs = getComputedStyle(e); return [parseFloat(cs.fontSize), parseFloat(cs.lineHeight)]; });
    els.forEach((e, k) => {
      const [fs, lh] = sizes[k]; const nf = conv(fs);
      e.style.fontSize = nf.toFixed(2) + 'px';
      // Compose keeps the sp line height proportional to the scaled size only if lineHeight is in sp; keep the ratio.
      if (!isNaN(lh)) e.style.lineHeight = (lh * nf / fs).toFixed(2) + 'px';
    });
  }
  function render() {
    document.querySelectorAll('i[data-i]').forEach(el => {
      const n = el.dataset.i;
      el.innerHTML = I[n] || '';
      if (MIRRORED.has(n) && getComputedStyle(el).direction === 'rtl') el.classList.add('mirror');
    });
    document.querySelectorAll('[data-brand]').forEach(el => { el.innerHTML = brand; });
    document.querySelectorAll('.statusbar').forEach(el => {
      if (!el.innerHTML.trim()) el.innerHTML = '<span>4:20</span><span class="sys"><i data-i="signal"></i><i data-i="wifi"></i><i data-i="batt"></i></span>';
      el.querySelectorAll('i[data-i]').forEach(x => { x.innerHTML = I[x.dataset.i]; });
    });
    if (new URLSearchParams(location.search).get('fs') === '2') { document.documentElement.classList.add('fs2'); scaleFonts(); }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', render); else render();
})();
