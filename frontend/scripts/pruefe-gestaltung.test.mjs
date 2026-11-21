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

test('lokale Nachbauten der Bausteine werden gemeldet, auch frühere Namen', () => {
  for (const klasse of ['pill', 'pille', 'hint', 'hinweis', 'tabelle', 'kachel', 'segment']) {
    assert.equal(pruefeDatei('a.scss', `.${klasse} {\n  padding: 5px;\n}`).length, 1, klasse);
  }
  assert.equal(pruefeDatei('a.scss', '.x,\n.pille.ok { color: var(--ok); }').length, 1);
  const befund = pruefeDatei('a.scss', '.x { color: var(--ok); }\n\n.hinweis { margin: 0; }');
  assert.deepEqual(befund.map(([zeile]) => zeile), [3]);
});

test('Platzierung im Zusammenhang und ähnliche Namen bleiben erlaubt', () => {
  assert.equal(pruefeDatei('a.scss', '.kopf .pille { margin-left: 0.25rem; }').length, 0);
  assert.equal(pruefeDatei('a.scss', '.tabelle-rahmen { margin: 0; }').length, 0);
  assert.equal(pruefeDatei('a.scss', '.pillen { display: flex; }').length, 0);
  assert.equal(pruefeDatei('a.scss', '// .pille { }\n.x { margin: 0; }').length, 0);
  assert.equal(pruefeDatei('a.scss', '@media (min-width: 60rem) {\n  .x { margin: 0; }\n}').length, 0);
});

test('Nachbauten in styles:-Blöcken von Komponenten werden gemeldet', () => {
  assert.equal(pruefeDatei('a.ts', 'styles: [`\n  .hinweis { color: var(--warnung); }\n`]').length, 1);
  assert.equal(pruefeDatei('a.ts', 'styles: `.kopf .hinweis { margin: 0; }`').length, 0);
  assert.equal(pruefeDatei('a.ts', "const klasse = '.pille { }';").length, 0);
});

test('Ausnahme für Bausteine mit Vermerk und für den Chat bis zu seiner Umstellung', () => {
  assert.equal(
    pruefeDatei('a.scss', '// gestaltung-ausnahme: eigene Form\n.segment { gap: 0; }').length,
    0,
  );
  assert.equal(pruefeDatei('src/app/chat/chat.scss', '.pill { padding: 5px; }').length, 0);
  assert.equal(pruefeDatei('src/app/kontakte/k.scss', '.pill { padding: 5px; }').length, 1);
});
