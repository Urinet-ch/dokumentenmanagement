import assert from 'node:assert/strict';
import { test } from 'node:test';
import { pruefeDatei } from './pruefe-gestaltung.mjs';

test('feste Farben in SCSS werden gemeldet, Tokens nicht', () => {
  assert.equal(pruefeDatei('a.scss', '.x { color: #eeb111; }').length, 1);
  assert.equal(pruefeDatei('a.scss', '.x { background: rgb(0 0 0 / 50%); }').length, 1);
  assert.equal(pruefeDatei('a.scss', '.x { color: var(--akzent-text); }').length, 0);
  assert.equal(
    pruefeDatei('a.scss', '.x { fill: color-mix(in srgb, var(--urinet-gelb) 15%, transparent); }')
      .length,
    0,
  );
});

test('Kommentare mit PR-Nummern zählen in TS nicht, CSS-Werte schon', () => {
  assert.equal(pruefeDatei('a.ts', '// Review #106 (#358)').length, 0);
  assert.equal(pruefeDatei('a.ts', 'styles: `.x { background: var(--x, #0d1017); }`').length, 1);
});

test('frühere Farbnamen werden gemeldet', () => {
  assert.equal(pruefeDatei('a.scss', '.x { color: var(--urinet-hell); }').length, 1);
  assert.equal(pruefeDatei('a.scss', '.x { color: var(--urinet-gelb); }').length, 0);
});

test('Ausnahme mit Grund auf derselben oder der vorherigen Zeile', () => {
  assert.equal(
    pruefeDatei('a.scss', '.k { background: #000; // gestaltung-ausnahme: Kamerabild }').length,
    0,
  );
  assert.equal(
    pruefeDatei('a.scss', '// gestaltung-ausnahme: Kamerabild\n.k { background: #000; }').length,
    0,
  );
  assert.equal(pruefeDatei('a.scss', '.k { background: #000; // gestaltung-ausnahme: }').length, 1);
});

test('Seite mit h1 braucht einen Seitenkopf', () => {
  assert.equal(pruefeDatei('a.html', '<h1>Titel</h1>').length, 1);
  assert.equal(pruefeDatei('a.html', '<div class="seitenkopf"><h1>Titel</h1></div>').length, 0);
});
