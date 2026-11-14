// F-091, Windows side of the round trip (read only use of the Windows app's code).
// Reads build/interchange/android (written by InterchangeTest.phase1), restores it with Windows'
// readBackup(), edits it with Windows' saveTask / snoozeTask / completeTask, and saves a Windows backup
// to build/interchange/windows for InterchangeTest.phase2.
// Usage: node tools/windows-roundtrip.cjs [path-to-Remember-Me]
'use strict';
const path = require('node:path');
const fs = require('node:fs');
const root = process.argv[2] || path.join(__dirname, '..', '..', 'Remember-Me');
const { readBackup } = require(path.join(root, 'src', 'data', 'backups.js'));
const { exportBackup } = require(path.join(root, 'src', 'data', 'attachments.js'));
const { saveTask, snoozeTask, completeTask } = require(path.join(root, 'src', 'core', 'tasks.js'));
const base = path.join(__dirname, '..', 'app', 'build', 'interchange');

(async () => {
  const b = await readBackup(path.join(base, 'android'));
  const state = b.state;
  const med = state.tasks.find(t => t.title === 'دواء الضغط');
  const notes = state.tasks.find(t => t.title === 'Meeting notes');
  if (!med || !notes) throw new Error('sample tasks missing');
  if (!med.android || !med.tags || !med.checklist) throw new Error('Windows dropped android/tags/checklist on read');
  // An edit in the Windows editor (it sends the fields it shows, plus tags and checklist as they are).
  saveTask(state, { id: med.id, title: med.title, body: 'edited on Windows', priority: med.priority, category: med.category,
    dueAt: med.dueAt, repeat: med.repeat, remindBefore: med.remindBefore, tags: med.tags, checklist: med.checklist }, new Date('2026-11-15T09:05:00Z'));
  snoozeTask(state, med.id, '2026-11-15T10:30:00.000Z', new Date('2026-11-15T09:06:00Z'));
  completeTask(state, notes.id, new Date('2026-11-15T09:07:00Z'));
  const out = path.join(base, 'windows');
  fs.rmSync(out, { recursive: true, force: true });
  fs.mkdirSync(out, { recursive: true });
  await exportBackup(state, path.join(base, 'android'), out);
  const after = state.tasks.find(t => t.id === med.id);
  console.log(`OK android=${JSON.stringify(after.android)} tags=${after.tags} steps=${after.checklist.length}`);
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
