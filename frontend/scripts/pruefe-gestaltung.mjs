// Prüft die Gestaltungsregeln der Oberfläche (docs/oberflaeche/README.md, Entscheid Fabian 21.11.2026):
// keine festen Farbwerte und keine früheren Farbnamen in den Komponenten, und jede Seite mit <h1> hat einen
// Seitenkopf (.seitenkopf). Ausnahme nur mit «gestaltung-ausnahme: <Grund>» auf derselben oder der vorherigen Zeile.
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
