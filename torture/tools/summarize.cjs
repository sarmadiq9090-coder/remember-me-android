// Summarises the JUnit XML of the torture classes (app/build/test-results/testDebugUnitTest/TEST-*torture*.xml).
// Usage: node summarize.cjs <results-dir> [out.txt]   Prints per class: tests, failures, skipped, time; then each
// failure with the first lines of its message. No dependencies.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const [dir, outFile] = process.argv.slice(2);
const unxml = s => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#10;/g, '\n').replace(/&#13;/g, '').replace(/&amp;/g, '&');
const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter(f => /^TEST-com\.fastkiwi\.rememberme\.torture\..*\.xml$/.test(f)).sort() : [];
const lines = [];
let T = 0, F = 0, S = 0;
const failures = [];
for (const f of files) {
  const xml = fs.readFileSync(path.join(dir, f), 'utf8');
  const head = /<testsuite [^>]*>/.exec(xml)[0];
  const attr = k => (new RegExp(`${k}="([^"]*)"`).exec(head) || [])[1];
  const tests = +attr('tests'), fail = +attr('failures') + +attr('errors'), skip = +attr('skipped');
  T += tests; F += fail; S += skip;
  lines.push(`${attr('name').replace('com.fastkiwi.rememberme.torture.', '').padEnd(42)} tests ${String(tests).padStart(3)}  failed ${String(fail).padStart(3)}  skipped ${String(skip).padStart(3)}  ${attr('time')} s`);
  const re = /<testcase name="([^"]*)" classname="([^"]*)"[^>]*?(?:\/>|>([\s\S]*?)<\/testcase>)/g;
  let m;
  while ((m = re.exec(xml))) {
    const body = m[3] || '';
    const fm = /<(failure|error) message="([^"]*)"/.exec(body);
    if (fm) failures.push(`${m[2].replace('com.fastkiwi.rememberme.torture.', '')}.${m[1]}: ${unxml(fm[2]).split('\n').slice(0, 4).join(' | ').slice(0, 600)}`);
  }
}
lines.push(`TOTAL tests ${T}, failed ${F}, skipped ${S}`);
if (failures.length) { lines.push('', 'FAILURES'); for (const x of failures) lines.push('- ' + x); }
const text = lines.join('\n') + '\n';
process.stdout.write(text);
if (outFile) fs.writeFileSync(outFile, text);
