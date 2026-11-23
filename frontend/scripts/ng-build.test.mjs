import assert from 'node:assert/strict';
import { test } from 'node:test';
import { commit, defines, version } from './ng-build.mjs';

const POM =
  '<project><parent><version>9</version></parent><artifactId>archiv-backend</artifactId><version>0.1.0-SNAPSHOT</version></project>';

test('Version steht hinter der artifactId des Backends', () => {
  assert.equal(version(POM), '0.1.0-SNAPSHOT');
  assert.equal(version('<project/>'), 'unbekannt');
});

test('Build ist der gekürzte Commit, sonst «lokal»', () => {
  assert.equal(
    commit({ GITHUB_SHA: '3873777b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f' }, () => ''),
    '3873777',
  );
  assert.equal(
    commit({}, () => 'abcdef0123456789\n'),
    'abcdef0',
  );
  assert.equal(
    commit({}, () => ''),
    'lokal',
  );
  assert.equal(
    commit({ GITHUB_SHA: '<x>' }, () => ''),
    'lokal',
  );
});

test('Konstanten werden als Zeichenketten übergeben', () => {
  const d = defines({ GITHUB_SHA: 'abcdef0123456' }, POM, new Date('2026-11-23T10:00:00Z'));
  assert.deepEqual(d, [
    '--define=ARCHIV_VERSION="0.1.0-SNAPSHOT"',
    '--define=ARCHIV_BUILD="abcdef0"',
    '--define=ARCHIV_GEBAUT="2026-11-23T10:00:00.000Z"',
  ]);
});
