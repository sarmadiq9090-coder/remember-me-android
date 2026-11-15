// Answers next-occurrence cases with the Windows app's own src/core/repeat.js (read only, never copied), for
// RepeatParityTortureTest. Usage: node repeat-parity.cjs <path-to-Remember-Me> <cases.json> <answers.json>
// cases: [{zone, repeat, current, now, repeatFrom, repeatDays?, repeatEvery?}], answers: [{next, offset, nextOffset} | {error}]
// (offsets in minutes east of UTC, as this Node's time-zone data sees them, to tell a tzdata difference from a rule one).
'use strict';
const path = require('node:path');
const fs = require('node:fs');
const [root, input, output] = process.argv.slice(2);
if (!root || !input || !output) { console.error('usage: node repeat-parity.cjs <Remember-Me> <cases.json> <answers.json>'); process.exit(2); }
const { nextOccurrence } = require(path.join(root, 'src', 'core', 'repeat.js'));
const cases = JSON.parse(fs.readFileSync(input, 'utf8'));
// Grouped by zone: process.env.TZ is switched once per zone (Node re-reads it on assignment).
const order = cases.map((_, i) => i).sort((a, b) => (cases[a].zone < cases[b].zone ? -1 : cases[a].zone > cases[b].zone ? 1 : a - b));
const out = new Array(cases.length);
for (const i of order) {
  const c = cases[i];
  if (process.env.TZ !== c.zone) process.env.TZ = c.zone;
  const rule = { repeatFrom: c.repeatFrom };
  if (c.repeatDays) rule.repeatDays = c.repeatDays;
  if (c.repeatEvery) rule.repeatEvery = c.repeatEvery;
  try {
    const next = nextOccurrence(c.current, c.repeat, new Date(c.now), rule);
    out[i] = { next, offset: -new Date(c.current).getTimezoneOffset(), nextOffset: -new Date(next).getTimezoneOffset() };
  } catch (e) {
    out[i] = { error: String(e && e.message || e) };
  }
}
fs.writeFileSync(output, JSON.stringify(out));
console.log(`${cases.length} cases answered by ${path.join(root, 'src', 'core', 'repeat.js')} (Node ${process.version}, ICU tz ${process.versions.tz || '?'})`);
