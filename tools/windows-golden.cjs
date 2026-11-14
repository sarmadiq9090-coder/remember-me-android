// Generates app/src/test/resources/windows-golden.json: next-occurrence answers computed by the Windows
// app's own src/core/repeat.js (read only, from the sibling Remember-Me folder), so RepeatParityTest can
// check that Android computes the same moment for every case.
// Usage: node tools/windows-golden.cjs [path-to-Remember-Me]
'use strict';
const path = require('node:path');
const fs = require('node:fs');
const root = process.argv[2] || path.join(__dirname, '..', '..', 'Remember-Me');
const { nextOccurrence } = require(path.join(root, 'src', 'core', 'repeat.js'));

// A small deterministic PRNG, so the file only changes when the Windows rules change.
let seed = 20261114;
const rand = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
const pick = list => list[Math.floor(rand() * list.length)];

const zones = ['Asia/Baghdad', 'Asia/Riyadh', 'Africa/Cairo', 'Europe/Berlin', 'America/New_York', 'Asia/Beirut', 'Australia/Sydney', 'UTC'];
const repeats = ['daily', 'weekly', 'days', 'interval', 'monthly', 'yearly'];
const cases = [];
for (let i = 0; i < 600; i++) {
  const zone = pick(zones);
  process.env.TZ = zone;
  const repeat = pick(repeats);
  // Starts between 2024 and 2028, at awkward times too (00:30, 02:30 for DST gaps, the 29th-31st).
  const start = new Date(2024 + Math.floor(rand() * 5), Math.floor(rand() * 12), pick([1, 5, 15, 28, 29, 30, 31]), pick([0, 2, 9, 23]), pick([0, 30, 59]));
  const current = new Date(start.getTime() + Math.floor(rand() * 3) * 86400000 * pick([1, 7, 31]));
  const now = new Date(current.getTime() + pick([-3600000, 0, 60000, 86400000, 86400000 * 9, 86400000 * 400]) + Math.floor(rand() * 3600000));
  const rule = { repeatFrom: start.toISOString() };
  if (repeat === 'days') rule.repeatDays = [...new Set([pick([0, 1, 2, 3, 4, 5, 6]), pick([0, 1, 2, 3, 4, 5, 6])])].sort();
  if (repeat === 'interval') rule.repeatEvery = pick([2, 3, 10, 30, 365]);
  const next = nextOccurrence(current.toISOString(), repeat, now, rule);
  cases.push({ zone, repeat, current: current.toISOString(), now: now.toISOString(), ...rule, next });
}
const out = path.join(__dirname, '..', 'app', 'src', 'test', 'resources', 'windows-golden.json');
fs.writeFileSync(out, JSON.stringify({ source: 'Remember-Me/src/core/repeat.js', count: cases.length, cases }, null, 1) + '\n');
console.log(`${cases.length} cases written to ${path.relative(process.cwd(), out)}`);
