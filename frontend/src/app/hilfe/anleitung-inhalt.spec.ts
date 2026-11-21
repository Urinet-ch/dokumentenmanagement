import { routes } from '../app.routes';
import { anleitungMarkdown } from './anleitung-markdown';
import { istAppPfad } from './app-pfade';
import { HILFE_KATALOG } from './hilfe-katalog';

// Inhaltsprüfung aller Dateien unter frontend/public/anleitung/ (Spec 07, Req 7.4, 9.3, 9.5, 9.7). Vitest läuft in Node,
// `ng test` startet in frontend/; gelesen wird ohne @types/node über process.getBuiltinModule.
interface Dateisystem {
  readdirSync(pfad: string): string[];
  readFileSync(pfad: string): Uint8Array;
}
const fs = (globalThis as unknown as { process: { getBuiltinModule(name: string): Dateisystem } }).process.getBuiltinModule(
  'node:fs',
);
const ORDNER = 'public/anleitung';
const MAX_SUMME = 200 * 1024;

const dateien = fs
  .readdirSync(ORDNER)
  .filter((name) => name.endsWith('.md'))
  .map((name) => {
    const bytes = fs.readFileSync(`${ORDNER}/${name}`);
    return { name, bytes: bytes.length, inhalt: new TextDecoder().decode(bytes) };
  });

/** Text ohne Code (`…` und ```-Blöcke), damit Beispiele in Code die Sprachregeln nicht verletzen. */
function ohneCode(inhalt: string): string {
  return inhalt
    .split('```')
    .filter((_, i) => i % 2 === 0)
    .join(' ')
    .split('`')
    .filter((_, i) => i % 2 === 0)
    .join(' ');
}

describe('Inhalt der Anleitung', () => {
  it('es gibt Dateien', () => {
    expect(dateien.length).toBeGreaterThan(0);
  });

  for (const datei of dateien) {
    describe(datei.name, () => {
      it('besteht nur aus der Markdown-Teilmenge, Links nur auf Routen und Abschnitte (Req 7.3, 7.4)', () => {
        const { befunde } = anleitungMarkdown(datei.inhalt, { istAppPfad: (pfad) => istAppPfad(pfad, routes) });
        expect(befunde).toEqual([]);
      });

      it('nennt keine fremden Ziele, auch nicht als Text (Req 7.4)', () => {
        for (const schema of ['http:', 'https:', 'mailto:', 'javascript:', 'data:', 'www.']) {
          expect(datei.inhalt.toLowerCase(), schema).not.toContain(schema);
        }
      });

      it('schreibt «ss» statt «ß» und Guillemets statt Anführungszeichen (Req 9.3)', () => {
        const text = ohneCode(datei.inhalt);
        expect(text).not.toContain('ß');
        for (const zeichen of ['"', '„', '“', '”']) expect(text, zeichen).not.toContain(zeichen);
      });

      it('enthält keine Muster von E-Mail-Adressen, Telefonnummern oder IBAN (Req 9.5)', () => {
        const text = datei.inhalt;
        expect(text).not.toMatch(/[\w.+-]+@[\w-]+\.[\w.-]+/);
        expect(text).not.toMatch(/(?:\+|00)\d{2}[\s\d]{6,}/);
        expect(text).not.toMatch(/\b0\d{2}[ /]?\d{3}[ ]?\d{2}[ ]?\d{2}\b/);
        expect(text).not.toMatch(/\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){3,}/);
      });

      it('nutzt {rolle} nur in Abschnitten, die eine Rolle voraussetzen', () => {
        const eintrag = HILFE_KATALOG.find((e) => `${e.anker}.md` === datei.name);
        if (eintrag?.abgemeldet) expect(datei.inhalt).not.toContain('{rolle}');
      });
    });
  }

  it(`alle Dateien zusammen höchstens ${MAX_SUMME} Bytes (Req 9.7)`, () => {
    expect(dateien.reduce((summe, datei) => summe + datei.bytes, 0)).toBeLessThanOrEqual(MAX_SUMME);
  });
});
