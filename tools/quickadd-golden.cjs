// Generates app/src/test/resources/quickadd-golden.json: quick-add readings computed by the Windows
// app's own parseWhen() (src/core/natural-dates.js, read only, from the sibling Remember-Me folder), so
// QuickAddParityTest can check that Android reads every phrase the same way.
// Usage: node tools/quickadd-golden.cjs [path-to-Remember-Me]
//
// English and chrono-node. The Windows English reader uses chrono-node, which is only present once
// `pnpm install` has run in the Remember-Me folder. This script works either way:
// - Arabic cases always come from the Windows parser itself. chrono-node is replaced by a stub that
//   throws, so a case that would reach it (an Arabic text with no Arabic date but some Latin letters)
//   is left out instead of recorded wrongly.
// - English cases: when chrono-node resolves from the Remember-Me folder, they are computed live by
//   parseWhen(). When it does not (the usual case on a machine without node_modules there), they are
//   the answers the Windows tests assert (tests/natural-dates.test.js, run green on Windows CI with
//   chrono-node 2.10.3), copied below, and only the fields those tests check are compared
//   ("checked"). The tests state local wall-clock times, and parseWhen() works in local time, so the
//   same answer holds in every zone used here (none of them changes clocks between 9 and 20 November
//   2026); each English case is recorded in four zones.
'use strict';
const path = require('node:path');
const fs = require('node:fs');
const Module = require('node:module');
const root = process.argv[2] || path.join(__dirname, '..', '..', 'Remember-Me');

let chrono = null;
try { chrono = require(require.resolve('chrono-node', { paths: [root] })); } catch { chrono = null; }
const CHRONO_STUB = { parse() { throw new Error('CHRONO_UNAVAILABLE'); } };
const load = Module._load;
Module._load = function (request, ...rest) { return request === 'chrono-node' ? (chrono || CHRONO_STUB) : load.call(this, request, ...rest); };
const { parseWhen } = require(path.join(root, 'src', 'core', 'natural-dates.js'));

// Local wall-clock time in the current process.env.TZ (month 1-12).
const local = (y, m, d, h, mi) => new Date(y, m - 1, d, h, mi);
const toLocal = iso => { const d = new Date(iso); const p = n => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`; };
const fromLocal = text => { const [, y, m, d, h, mi] = text.match(/^(\d+)-(\d+)-(\d+) (\d+):(\d+)$/).map(Number); return local(y, m, d, h, mi).toISOString(); };

// Contexts: [label, zone, [y, m, d, h, mi], weekStart]. Monday 9 and Friday 13 November 2026 as in the
// Windows tests; the three week starts; Berlin's spring-forward (29 March 2026) and fall-back
// (25 October 2026) nights; a year end, a month end, and late evenings.
const CONTEXTS = [
  ['mon', 'Asia/Baghdad', [2026, 11, 9, 10, 0], 6],
  ['fri', 'Asia/Baghdad', [2026, 11, 13, 10, 0], 6],
  ['mon-sun', 'Europe/Berlin', [2026, 11, 9, 10, 0], 0],
  ['fri-mon', 'Europe/Berlin', [2026, 11, 13, 10, 0], 1],
  ['mon-late', 'Asia/Baghdad', [2026, 11, 9, 23, 50], 6],
  ['sun-mon', 'UTC', [2026, 11, 15, 10, 0], 1],
  ['dst-spring', 'Europe/Berlin', [2026, 3, 28, 22, 30], 6],
  ['dst-fall', 'Europe/Berlin', [2026, 10, 24, 21, 0], 0],
  ['year-end', 'Asia/Baghdad', [2026, 12, 31, 23, 30], 6],
  ['month-end', 'America/New_York', [2026, 1, 30, 15, 0], 0],
];

// Arabic phrases: the Windows tests' own, then more in standard Arabic and Iraqi, Gulf and Levantine
// speech, with Western, Arabic-Indic and Persian digits, and titles that hold no date.
const ARABIC = [
  // From tests/natural-dates.test.js.
  'اتصل بأمي بكرة ٥ العصر', 'اتصل بأمي بكرة 5 العصر', 'باچر الصبح', 'باجر الصبح', 'بكرا الضهر', 'باكر العصر', 'غداً ٣ الظهر', 'بُكرة ٧ مساءً',
  'بعد ساعتين', 'بعد ساعة', 'بعد نص ساعة', 'بعد ربع ساعة', 'بعد ساعة ونص', 'بعد ٣ ساعات', 'كمان ١٠ دقايق', 'تذكير بعد ٥ دقائق',
  'بعد بكرة ٩ الصبح', 'عقب باچر العصر', 'بعد غد ٨ صباحاً', 'بعد ٣ أيام', 'بعد يومين ٤ العصر', 'بعد أسبوع', 'بعد شهر', 'اليوم المغرب',
  'الليلة', 'هالليلة ١٠', 'اليوم ٥', 'الاجتماع الساعة ١٧', 'عشا مع سامي الجمعة ٧:٣٠ م', 'الساعة خمسة ونص العصر', 'الساعة الخامسة مساءً',
  'عالساعة ٤ إلا ربع العصر', 'موعد الطبيب يوم الأربعاء ١١ الصبح', 'هالجمعة ١٠ الصبح', 'الجمعة هذي ١٠ الصبح', 'الخميس من الأسبوع الجاي ٥ العصر',
  'بكرة الفجر', 'السبت ٨ بالليل', 'دوا ٩ مساءً', 'بكرة ۵ العصر', 'بُكْرَة ٥ العَصر', 'بكره ٥ العصر', 'بكرة ٥ العـــصر', 'بكرة ١٠:٣٠ الصبح', 'بكرة ١٠٫٣٠ الصبح',
  'كل يوم ٧ الصبح', 'دوا كل يوم الساعة ٢٠:٠٠', 'رياضة يومياً ٦ المسا', 'كل صباح', 'كل ليلة ١٠', 'كل خميس ٨ بالليل', 'كل يوم جمعة ١١ الصبح',
  'كل اثنين ٩ الصبح', 'كل اثنين وخميس ٦ المسا', 'كل ثلاثة أيام ٩ الصبح', 'كل يومين ٨ الصبح', 'كل شهر ١٠ الصبح', 'كل سنة ٩ الصبح',
  'بكرة ٥', 'الاجتماع الساعة ٣', 'بكرة الساعة ١٢', 'الخميس الجاي ٨ بالليل', 'الخميس الياي ٨ بالليل', 'الاثنين ٨ المسا', 'الأحد ٢ بالليل',
  'بكرة نص الليل', 'بعد بكرة', 'الخميس الجاي', 'اشتري ٥ كيلو رز بكرة', 'اليوم ٩ الصبح', 'السبت الجاي ١٠ الصبح',
  'قراءة عشر صفحات', 'غداء مع العائلة', 'اشتري ٥ كيلو رز', 'مراجعة خطة الأسبوع', 'الخميس الماضي', 'ست الحبايب', 'ساعة يد جديدة', 'اتصل بأحمد',
  'موعد الطبيب في بكرة ٥ العصر', 'بكرة ٥ العصر: اجتماع الفريق', 'اتصل بكرة ٥ العصر بسامي', 'كل يوم',
  // Iraqi.
  'باچر الساعة ٨ الصبح اجتماع', 'عقب باچر', 'الخميس الياي', 'كل يوم جمعة', 'باچر عالساعة ٩', 'روح للدكتور باچر العصريه', 'باچر الساعة ثنعش الظهر',
  'هالخميس ٤ العصر', 'الجمعة اليايه ١٠ الصبح', 'باجر بالليل', 'وباچر الصبح', 'اتصل بأبو علي عقب باچر الساعة ٦ المسا',
  // Gulf.
  'باكر الظهر', 'بكرا العصريه', 'باكر الساعة حدعش الصبح', 'الحين بعد نص ساعة', 'الأحد الجاي ٩ الصبح', 'كل أحد وأربعاء ٧ المسا', 'باكر الساعة ٧ وربع الصبح',
  // Levantine.
  'بكرا الساعة تلاتة العصر', 'بعد بكرا', 'الجمعة الجاية', 'كمان ربع ساعة', 'عالساعة ٤', 'بكرا ع الساعة ٥ المسا', 'التلاتا الجاي ١١ الصبح', 'بكرا الساعة اتناش الضهر',
  'شوف الدكتور يوم التلات ٤ العصر', 'بعد بكرا الساعة تمانية المسا',
  // Standard Arabic.
  'غداً في الساعة العاشرة صباحاً', 'يوم الأحد القادم', 'الأسبوع المقبل', 'الأسبوع الجاي', 'بعد أسبوعين', 'كل أسبوعين', 'كل ٣ أسابيع', 'شهرياً', 'سنوياً', 'كل عام',
  'أسبوعياً ٩ الصبح', 'كل أسبوع', 'الاجتماع القادم يوم الخميس القادم الساعة الثالثة عصراً', 'خلال ٣ أيام', 'بعد شهرين', 'بعد ١٠ دقائق', 'بعد ساعة إلا ربع',
  'بعد ٢ ساعة ونص', 'بعد دقيقتين', 'بعد ٩٠ دقيقة', 'كل ٧ أيام', 'كل يوم واحد', 'كل ٤٠٠ يوم', 'كل ١ يوم',
  // Times and parts of the day.
  'الساعة ١٢ الظهر', 'الساعة ١٢ بالليل', '١١ الضحى', 'الساعة ٣ إلا ثلث العصر', 'الساعة ٤ وربع', 'الساعة ٥ و١٠ دقايق', 'على ٨', 'الساعة ٢٣:٥٩',
  'الساعة ٠٧:٠٥', 'الساعة ٢٤', 'الساعة ٢٥', 'الساعة ٠', 'الساعة ٥:٥٠ ونص', 'الساعة ١٢ إلا ربع', 'العشا', 'بعد الظهر', 'الظهر', 'الفجر', 'المغرب',
  'الساعة ٣ بعد الظهر', 'الساعة ١١ بالليل', 'الساعة ١ بالليل', '١٢ نص الليل', 'الساعة ٩ العشا', 'الساعة ٢ ص', '١٠م', 'بكرة ٧ pm', 'بكرة 8 a.m.',
  'الخميس ٢ بالليل', 'الجمعة نص الليل', 'بكرة ١٢ بالليل', 'اليوم العشا', 'الليلة ١١', 'الليلة الساعة ٢', 'كل ليلة', 'كل مساء ٨', 'كل عصر', 'كل ظهر',
  // Persian and Arabic-Indic digits.
  'بكرة ۸ الصبح', 'بعد ۲ ساعات', 'الساعة ۱۷:۴۵', 'بكرة ١٢:٤٥ الظهر', 'كل ۳ أيام ۹ الصبح',
  // Past days, ambiguous and odd ones.
  'الخميس الماضي ٥ العصر', 'السبت اللي فات', 'سورة الجمعة', 'اشتري ٥ كيلو طماطم', 'الغرفة 12', 'اتصل بخميس', 'زيارة أبو جمعة', 'ادفع ١٢.٥٠ دينار',
  'خمسة ونص كيلو', 'الساعة', 'بكرة بكرة', 'اليوم وبكرة', 'كل خميس وجمعة وسبت', 'كل خميس الجاي', 'الخميس والجمعة', 'هذا الخميس', 'السبت هذا ٩ الصبح',
  'الثلاثاء من الاسبوع القادم', 'الأربعاء الفايت', 'يوم خميس', 'في يوم الجمعة ٣ العصر', 'بالخميس', 'كل يوم أحد وأربعاء',
];

// English: the Windows tests' answers (see the header). Local times, now = Monday 9 November 10:00
// unless 'fri' (Friday 13), week starting on Saturday.
const MON = '2026-11-09 10:00', FRI = '2026-11-13 10:00';
const ENGLISH = [
  // [text, now, expect]: expect null = not a date; else { dueAt?, ask, options, repeat?, repeatDays?, repeatEvery?, title? }.
  ['call mom tomorrow 5pm', MON, { due: '2026-11-10 17:00', title: 'call mom' }],
  ['Call mom tomorrow at 5 pm', MON, { due: '2026-11-10 17:00', title: 'Call mom' }],
  ['in 2 hours', MON, { due: '2026-11-09 12:00' }],
  ['stretch in 30 minutes', MON, { due: '2026-11-09 10:30', title: 'stretch' }],
  ['next Thursday 8pm', FRI, { due: '2026-11-19 20:00' }],
  ['every day 7am', MON, { due: '2026-11-10 07:00', repeat: 'daily' }],
  ['Take pills daily at 21:00', MON, { due: '2026-11-09 21:00', repeat: 'daily', title: 'Take pills' }],
  ['at 17:30', MON, { due: '2026-11-09 17:30' }],
  ['at 07:30', MON, { due: '2026-11-10 07:30' }],
  ['dinner 8 in the evening', MON, { due: '2026-11-09 20:00', title: 'dinner' }],
  ['tonight', MON, { due: '2026-11-09 21:00' }],
  ['tonight at 8', MON, { due: '2026-11-09 20:00' }],
  ['tomorrow morning', MON, { due: '2026-11-10 09:00' }],
  ['tomorrow noon', MON, { due: '2026-11-10 12:00' }],
  ['this thursday 5pm', MON, { due: '2026-11-12 17:00' }],
  ['thursday next week 5pm', MON, { due: '2026-11-19 17:00' }],
  ['in 3 days', MON, { due: '2026-11-12 10:00' }],
  ['in 2 days at 4pm', MON, { due: '2026-11-11 16:00' }],
  ['Nov 20 3pm', MON, { due: '2026-11-20 15:00' }],
  ['gym every monday and thursday 7pm', MON, { due: '2026-11-09 19:00', repeat: 'days', repeatDays: [1, 4], title: 'gym' }],
  ['standup weekdays 9:30am', MON, { due: '2026-11-10 09:30', repeat: 'days', repeatDays: [1, 2, 3, 4, 5], title: 'standup' }],
  ['every friday 6pm', MON, { due: '2026-11-13 18:00', repeat: 'weekly' }],
  ['water plants every other day 6pm', MON, { due: '2026-11-09 18:00', repeat: 'interval', repeatEvery: 2 }],
  ['every 3 days at 8am', MON, { due: '2026-11-10 08:00', repeat: 'interval', repeatEvery: 3 }],
  ['every morning', MON, { due: '2026-11-10 09:00', repeat: 'daily' }],
  ['monthly at 9am', MON, { due: '2026-11-10 09:00', repeat: 'monthly' }],
  ['tomorrow at 5', MON, { ask: 'meridiem', options: ['2026-11-10 05:00', '2026-11-10 17:00'] }],
  ['at 7:30', MON, { ask: 'meridiem', options: ['2026-11-09 19:30', '2026-11-10 07:30'] }],
  ['next Thursday 8pm', MON, { ask: 'day', options: ['2026-11-12 20:00', '2026-11-19 20:00'] }],
  ['Thursday', MON, { ask: 'time', options: ['2026-11-12 09:00', '2026-11-12 12:00', '2026-11-12 18:00'] }],
  ['pay rent next week', MON, { ask: 'time', options: ['2026-11-16 09:00', '2026-11-16 12:00', '2026-11-16 18:00'], title: 'pay rent' }],
  ['meeting on friday', MON, { ask: 'time', options: ['2026-11-13 09:00', '2026-11-13 12:00', '2026-11-13 18:00'] }],
  ['today 9am', MON, { ask: 'past', options: ['2026-11-10 09:00'] }],
  ['every day', MON, { ask: 'time', options: ['2026-11-09 12:00', '2026-11-09 18:00', '2026-11-10 09:00'] }],
  ['Call mom at tomorrow 5pm', MON, { title: 'Call mom' }],
  ['tomorrow 5pm', MON, { title: 'tomorrow 5pm' }],
  ...['I sat down', 'March numbers', 'read chapter 5', 'buy 2 apples', 'may report', 'Wed photos', 'Sun cream', 'now'].map(text => [text, MON, null]),
];
const ENGLISH_ZONES = ['Asia/Baghdad', 'Europe/Berlin', 'UTC', 'America/New_York'];

const cases = [];
let skipped = 0;
// The Windows answer, in the golden file's shape (null when parseWhen() finds nothing).
const answer = r => r && { title: r.title, dueAt: r.dueAt, repeat: r.repeat, repeatDays: r.repeatDays ?? [], repeatEvery: r.repeatEvery ?? null, ask: r.ask, options: r.options };

for (const [label, zone, ymdhm, weekStart] of CONTEXTS) {
  process.env.TZ = zone;
  const now = local(...ymdhm);
  for (const text of ARABIC) {
    let r;
    try { r = parseWhen(text, { now, weekStart }); } catch (e) { if (e.message === 'CHRONO_UNAVAILABLE') { skipped++; continue; } throw e; }
    cases.push({ lang: 'ar', context: label, zone, now: now.toISOString(), weekStart, text, expect: answer(r), checked: 'all' });
  }
}

for (const zone of ENGLISH_ZONES) {
  process.env.TZ = zone;
  for (const [text, nowText, want] of ENGLISH) {
    const now = new Date(fromLocal(nowText));
    const base = { lang: 'en', context: nowText === FRI ? 'fri' : 'mon', zone, now: now.toISOString(), weekStart: 6, text };
    if (chrono) { cases.push({ ...base, expect: answer(parseWhen(text, { now, weekStart: 6 })), checked: 'all' }); continue; }
    if (want === null) { cases.push({ ...base, expect: null, checked: 'all' }); continue; }
    const checked = [], expect = {};
    if (want.title !== undefined) { checked.push('title'); expect.title = want.title; }
    if (want.due) {
      const due = fromLocal(want.due);
      Object.assign(expect, { dueAt: due, ask: null, options: [due], repeat: want.repeat ?? 'none', repeatDays: want.repeatDays ?? [], repeatEvery: want.repeatEvery ?? null });
      checked.push('dueAt', 'ask', 'options', 'repeat', 'repeatDays', 'repeatEvery');
    } else if (want.ask) {
      Object.assign(expect, { dueAt: null, ask: want.ask, options: want.options.map(fromLocal) });
      checked.push('dueAt', 'ask', 'options');
    }
    cases.push({ ...base, expect, checked });
  }
}

// Sanity: the local-time helpers agree with the zone.
process.env.TZ = 'Europe/Berlin';
if (toLocal(local(2026, 3, 29, 2, 30).toISOString()) !== '2026-03-29 03:30') throw new Error('TZ switching does not work in this Node');

const out = path.join(__dirname, '..', 'app', 'src', 'test', 'resources', 'quickadd-golden.json');
const count = lang => cases.filter(c => c.lang === lang).length;
fs.writeFileSync(out, JSON.stringify({
  source: 'Remember-Me/src/core/natural-dates.js (parseWhen)',
  english: chrono ? 'computed live with chrono-node' : 'answers asserted by Remember-Me/tests/natural-dates.test.js (chrono-node not installed)',
  count: cases.length, arabic: count('ar'), englishCount: count('en'), skipped, cases,
}, null, 1) + '\n');
console.log(`${cases.length} cases (${count('ar')} Arabic, ${count('en')} English, ${skipped} skipped: need chrono-node) written to ${path.relative(process.cwd(), out)}`);
