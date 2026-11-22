const { execFileSync } = require('node:child_process');
const { copyFileSync, existsSync, mkdirSync, mkdtempSync, renameSync, rmdirSync, unlinkSync } = require('node:fs');
const { dirname, join, resolve } = require('node:path');

const dateien = [
  'urinet-archiv.php',
  'includes/class-client.php',
  'includes/class-einstellungen.php',
];
mkdirSync('dist', { recursive: true });
const temporaer = mkdtempSync(join('dist', '.paket-'));
const paket = resolve(temporaer, 'urinet-archiv.zip');
const ordner = join(temporaer, 'urinet-archiv-plugin');
try {
  for (const datei of dateien) {
    const ziel = join(ordner, datei);
    mkdirSync(dirname(ziel), { recursive: true });
    copyFileSync(datei, ziel);
  }
  const paketDateien = dateien.map((datei) => `urinet-archiv-plugin/${datei}`);
  execFileSync('zip', ['-q', paket, ...paketDateien], { cwd: temporaer });
  const inhalt = execFileSync('unzip', ['-Z1', paket], { encoding: 'utf8' }).trim().split('\n').sort();
  if (JSON.stringify(inhalt) !== JSON.stringify(paketDateien.sort())) {
    throw new Error('Das Plugin-Paket enthält unerwartete Dateien.');
  }
  renameSync(paket, join('dist', 'urinet-archiv.zip'));
} finally {
  if (existsSync(paket)) unlinkSync(paket);
  for (const datei of dateien) {
    const ziel = join(ordner, datei);
    if (existsSync(ziel)) unlinkSync(ziel);
  }
  if (existsSync(join(ordner, 'includes'))) rmdirSync(join(ordner, 'includes'));
  if (existsSync(ordner)) rmdirSync(ordner);
  rmdirSync(temporaer);
}
