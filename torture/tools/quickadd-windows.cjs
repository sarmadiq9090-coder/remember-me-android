// Reads quick-add texts with the Windows app's own parseWhen() (src/core/natural-dates.js, read only, never copied),
// to tell an Android-only quick-add finding from one both apps share. chrono-node (English) is stubbed when it is not
// installed in the Remember-Me folder; a case that reaches it answers {error: "CHRONO_UNAVAILABLE"}.
// Usage: node quickadd-windows.cjs <path-to-Remember-Me> <cases.json> <answers.json>
//   cases: [{text, now (ISO), zone}]   answers: [{reading} | {error}]
'use strict';
const path = require('node:path');
const fs = require('node:fs');
const Module = require('node:module');
const [rootArg, input, output] = process.argv.slice(2);
const root = path.resolve(rootArg);
let chrono = null;
try { chrono = require(require.resolve('chrono-node', { paths: [root] })); } catch { chrono = null; }
const load = Module._load;
Module._load = function (request, ...rest) { return request === 'chrono-node' ? (chrono || { parse() { throw new Error('CHRONO_UNAVAILABLE'); } }) : load.call(this, request, ...rest); };
const { parseWhen } = require(path.join(root, 'src', 'core', 'natural-dates.js'));
const cases = JSON.parse(fs.readFileSync(input, 'utf8'));
const order = cases.map((_, i) => i).sort((a, b) => (cases[a].zone < cases[b].zone ? -1 : cases[a].zone > cases[b].zone ? 1 : a - b));
const out = new Array(cases.length);
for (const i of order) {
  const c = cases[i];
  if (process.env.TZ !== c.zone) process.env.TZ = c.zone;
  try { out[i] = { reading: parseWhen(c.text, new Date(c.now)) ?? null }; } catch (e) { out[i] = { error: String(e && e.message || e) }; }
}
fs.writeFileSync(output, JSON.stringify(out));
console.log(`${cases.length} texts read by ${path.join(root, 'src', 'core', 'natural-dates.js')} (chrono-node ${chrono ? 'present' : 'stubbed'})`);
