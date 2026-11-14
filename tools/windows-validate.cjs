// Restores the backups that DataTest exported (app/build/windows-check/*) with the Windows app's own
// readBackup() and validateState() (read only, from the sibling Remember-Me folder).
// Usage: node tools/windows-validate.cjs [path-to-Remember-Me]
'use strict';
const path = require('node:path');
const fs = require('node:fs');
const root = process.argv[2] || path.join(__dirname, '..', '..', 'Remember-Me');
const { readBackup } = require(path.join(root, 'src', 'data', 'backups.js'));
const dir = path.join(__dirname, '..', 'app', 'build', 'windows-check');
(async () => {
  const folders = fs.readdirSync(dir).map(f => path.join(dir, f));
  if (!folders.length) throw new Error('Run the unit tests first.');
  for (const folder of folders) {
    const b = await readBackup(folder);
    console.log(`OK ${path.basename(folder)}: verified=${b.verified}, ${b.state.tasks.length} tasks, ${b.state.notes.length} notes, repeats=${b.state.tasks.map(t => t.repeat).join(',')}`);
  }
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
