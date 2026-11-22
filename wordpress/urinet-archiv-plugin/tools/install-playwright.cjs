const { spawnSync } = require('node:child_process');

const argumente = ['install'];
if (process.platform === 'linux') {
  argumente.push('--with-deps');
}
argumente.push('chromium');

const ergebnis = spawnSync('./node_modules/.bin/playwright', argumente, { stdio: 'inherit' });
if (ergebnis.error) {
  throw ergebnis.error;
}
process.exitCode = ergebnis.status ?? 1;
