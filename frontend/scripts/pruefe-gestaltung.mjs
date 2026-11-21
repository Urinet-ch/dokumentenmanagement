// Prüft die Gestaltungsregeln der Oberfläche (docs/oberflaeche/README.md, Entscheid Fabian 21.11.2026):
// keine festen Farbwerte und keine früheren Farbnamen in den Komponenten, und jede Seite mit <h1> hat einen
// Seitenkopf (.seitenkopf), und Komponenten bauen die globalen Bausteine aus styles.scss (.pille, .hinweis, .tabelle,
// .kachel, .segment) nicht lokal nach. Ausnahme nur mit «gestaltung-ausnahme: <Grund>» auf derselben oder der
// vorherigen Zeile.
// Aufruf: node scripts/pruefe-gestaltung.mjs [Verzeichnis]   (Standard: src/app)
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const AUSGENOMMEN = [/\.spec\.ts$/, /\/gemeinsam\/api\//, /\/gemeinsam\/branding\.service\.ts$/];
const AUSNAHME = /gestaltung-ausnahme:\s*\p{L}{3}/u;
const ALTE_NAMEN = /var\(--urinet-(dunkel|hell|karte|gedaempft|linie|fehler|erfolg)\b/;
const FUNKTION = /\b(rgba?|hsla?|hwb|lab|lch|oklab|oklch)\(/;
// In SCSS jede Hex-Farbe; in TS und HTML nur als CSS-Wert (nach «:» oder «,»), damit «(#358)» in Kommentaren nicht zählt
const HEX_SCSS = /(^|[^\w&-])#[0-9a-fA-F]{3,8}\b/;
const HEX_WERT = /[:,]\s*#[0-9a-fA-F]{3,8}\b/;
// Globale Bausteine (styles.scss) und frühere lokale Namen dafür; gemeldet wird ein Selektor, der mit der Klasse
// beginnt (Definition). Platzierung im Zusammenhang, z. B. `.kopf .pille { margin-left: … }`, bleibt erlaubt.
const BAUSTEIN = /^&?\.(pill|pille|hint|hinweis|tabelle|kachel|segment)(?![\w-])/;
// Ordner, deren Bausteine noch nicht umgestellt sind
const BAUSTEIN_ORDNER_AUSNAHME = [
  /\/chat\//, // bis Umstellung nach #399 (Chat-Oberfläche, offener PR)
];

// Selektoren eines Stylesheets mit Zeilennummer (Kommentare entfernt, Zeilen bleiben erhalten)
function selektoren(css, ersteZeile) {
  const ohneKommentare = css
    .replace(/\/\*[\s\S]*?\*\//g, (k) => k.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/.*$/gm, '$1');
  const treffer = [];
  for (const m of ohneKommentare.matchAll(/([^{};]+)\{/g)) {
    const vorspann = m[1];
    const start = m.index + vorspann.length - vorspann.trimStart().length;
    const zeile = ersteZeile + ohneKommentare.slice(0, start).split('\n').length - 1;
    if (!vorspann.trim().startsWith('@')) treffer.push([zeile, vorspann.trim()]);
  }
  return treffer;
}

function lokaleBausteine(pfad, text, zeilen) {
  if (BAUSTEIN_ORDNER_AUSNAHME.some((m) => m.test(pfad))) return [];
  const bloecke = pfad.endsWith('.scss')
    ? [[text, 1]]
    : [...text.matchAll(/styles:\s*\[?\s*`([^`]*)`/g)].map((m) => [
        m[1],
        text.slice(0, m.index + m[0].indexOf('`') + 1).split('\n').length,
      ]);
  return bloecke.flatMap(([css, ersteZeile]) =>
    selektoren(css, ersteZeile)
      .filter(([, sel]) => sel.split(',').some((teil) => BAUSTEIN.test(teil.trim())))
      .filter(([zeile]) => ![zeile - 1, zeile - 2].some((i) => AUSNAHME.test(zeilen[i] ?? '')))
      .map(([zeile, sel]) => [
        zeile,
        `lokaler Nachbau des Bausteins «${sel.match(/\.[\w-]+/)[0]}» (globale Klasse aus styles.scss verwenden)`,
      ]),
  );
}

export function pruefeDatei(pfad, text) {
  const befunde = [];
  const zeilen = text.split('\n');
  const scss = pfad.endsWith('.scss');
  zeilen.forEach((zeile, i) => {
    const ausnahme = AUSNAHME.test(zeile) || AUSNAHME.test(zeilen[i - 1] ?? '');
    if (ausnahme) return;
    const code = scss ? zeile.replace(/\/\/.*$/, '') : zeile;
    if ((scss ? HEX_SCSS : HEX_WERT).test(code))
      befunde.push([i + 1, 'fester Farbwert (Token aus styles.scss verwenden)']);
    if ((scss || /styles?:|style=/.test(text)) && FUNKTION.test(code) && !/color-mix\(/.test(code))
      befunde.push([
        i + 1,
        'Farbfunktion mit festem Wert (Token oder color-mix mit Token verwenden)',
      ]);
    if (ALTE_NAMEN.test(code))
      befunde.push([i + 1, 'früherer Farbname --urinet-* (neue Tokens verwenden)']);
  });
  if (/\.(scss|ts)$/.test(pfad)) befunde.push(...lokaleBausteine(pfad, text, zeilen));
  if (/<h1[\s>]/.test(text) && !/seitenkopf/.test(text))
    befunde.push([1, 'Seite mit <h1> ohne <div class="seitenkopf">']);
  return befunde;
}

function dateien(verzeichnis) {
  return readdirSync(verzeichnis).flatMap((name) => {
    const pfad = join(verzeichnis, name);
    if (statSync(pfad).isDirectory()) return dateien(pfad);
    return /\.(scss|ts|html)$/.test(name) && !AUSGENOMMEN.some((m) => m.test(pfad)) ? [pfad] : [];
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const wurzel = process.argv[2] ?? 'src/app';
  const alle = dateien(wurzel).flatMap((pfad) =>
    pruefeDatei(pfad, readFileSync(pfad, 'utf8')).map(
      ([zeile, grund]) => `${relative('.', pfad)}:${zeile}: ${grund}`,
    ),
  );
  if (alle.length) {
    console.error(`Gestaltungsregeln verletzt (docs/oberflaeche/README.md):\n${alle.join('\n')}`);
    process.exit(1);
  }
  console.log('Gestaltungsregeln eingehalten.');
}
