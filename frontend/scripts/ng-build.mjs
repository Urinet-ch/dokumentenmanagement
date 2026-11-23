// Baut die App mit Version und Build als Konstanten (src/app/gemeinsam/build-info.ts, Anzeige unten rechts).
// Version: <version> des Backends aus backend/pom.xml; Build: Commit (GITHUB_SHA in CI und Deploy, sonst git) und Zeitpunkt.
// Aufruf wie `ng build`: node scripts/ng-build.mjs [Argumente von ng build]
import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

export function version(pom) {
  return (
    /<artifactId>archiv-backend<\/artifactId>\s*<version>([^<]+)<\/version>/.exec(pom)?.[1] ??
    'unbekannt'
  );
}

export function commit(env, git) {
  const sha = (env.GITHUB_SHA || git() || '').trim();
  return /^[0-9a-f]{7,40}$/.test(sha) ? sha.slice(0, 7) : 'lokal';
}

function git() {
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    });
  } catch {
    return '';
  }
}

export function defines(env, pom, jetzt = new Date()) {
  const wert = { version: version(pom), build: commit(env, git), gebaut: jetzt.toISOString() };
  return Object.entries({
    ARCHIV_VERSION: wert.version,
    ARCHIV_BUILD: wert.build,
    ARCHIV_GEBAUT: wert.gebaut,
  }).map(([name, text]) => `--define=${name}=${JSON.stringify(text)}`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const args = [
    'ng',
    'build',
    ...defines(process.env, readFileSync('../backend/pom.xml', 'utf8')),
    ...process.argv.slice(2),
  ];
  process.exit(spawnSync('npx', args, { stdio: 'inherit' }).status ?? 1);
}
