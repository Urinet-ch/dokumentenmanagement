import { routes } from '../app.routes';
import { AKTIONEN, Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { MENUE } from '../gemeinsam/navigation';
import { istAppPfad, istAppPfadPraefix } from './app-pfade';
import { HILFE_KATALOG, bedingungErfuellt } from './hilfe-katalog';

// Dateien lesen ohne @types/node: Vitest läuft in Node, `ng test` startet in frontend/ (Spec 07, Req 7.2)
interface Dateisystem {
  existsSync(pfad: string): boolean;
  readdirSync(pfad: string): string[];
}
const fs = (globalThis as unknown as { process: { getBuiltinModule(name: string): Dateisystem } }).process.getBuiltinModule(
  'node:fs',
);
const ORDNER = 'public/anleitung';
const ROLLEN: readonly Rolle[] = ['inhaber', 'geschaeftsstelle', 'mitarbeiter', 'treuhaender'];

describe('Hilfe-Katalog (Spec 07, Req 7.2)', () => {
  it('jede Datei des Katalogs besteht und heisst wie ihr Anker', () => {
    for (const eintrag of HILFE_KATALOG) {
      expect(eintrag.datei).toBe(`/anleitung/${eintrag.anker}.md`);
      expect(fs.existsSync(`public${eintrag.datei}`), eintrag.datei).toBe(true);
    }
  });

  it('jede Datei im Ordner steht im Katalog', () => {
    const dateien = fs.readdirSync(ORDNER).sort();
    expect(dateien).toEqual(HILFE_KATALOG.map((eintrag) => `${eintrag.anker}.md`).sort());
  });

  it('Anker sind eindeutig und in Kleinbuchstaben mit Bindestrich', () => {
    const anker = HILFE_KATALOG.map((eintrag) => eintrag.anker);
    expect(new Set(anker).size).toBe(anker.length);
    for (const a of anker) expect(a).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
  });

  it('jeder Eintrag hat einen Titel aus texte.de-CH.ts', () => {
    for (const eintrag of HILFE_KATALOG) expect(eintrag.titel?.trim(), eintrag.anker).toBeTruthy();
  });

  it('ein Unterabschnitt gehört zu einem Abschnitt, der vor ihm steht', () => {
    HILFE_KATALOG.forEach((eintrag, index) => {
      if (!eintrag.eltern) return;
      const eltern = HILFE_KATALOG.findIndex((e) => e.anker === eintrag.eltern);
      expect(eltern, eintrag.anker).toBeGreaterThanOrEqual(0);
      expect(eltern).toBeLessThan(index);
      expect(HILFE_KATALOG[eltern].eltern).toBeUndefined();
    });
  });

  it('Bedingungen nennen nur bekannte Aktionen und nie eine leere Liste', () => {
    for (const { anker, bedingung } of HILFE_KATALOG) {
      if (!bedingung) continue;
      expect(bedingung.eine.length, anker).toBeGreaterThan(0);
      for (const aktion of [...bedingung.eine, ...(bedingung.ohne ?? [])]) expect(AKTIONEN, anker).toContain(aktion);
    }
  });

  it('zugeordnete Pfade sind Routen der App', () => {
    for (const { anker, pfade } of HILFE_KATALOG) {
      for (const pfad of pfade ?? []) expect(istAppPfadPraefix(pfad, routes), `${anker}: ${pfad}`).toBe(true);
    }
  });

  it('zeigt für jeden Pfad, den auch das Menü kennt, dasselbe wie das Menü', () => {
    for (const eintrag of HILFE_KATALOG) {
      for (const punkt of MENUE.filter((p) => eintrag.pfade?.includes(p.pfad))) {
        for (const rolle of ROLLEN) {
          expect(bedingungErfuellt(eintrag.bedingung, rolle), `${eintrag.anker}, ${rolle}`).toBe(darf(rolle, punkt.aktion));
        }
      }
    }
  });

  it('enthält die allgemeinen Abschnitte «erste-schritte» und «probleme», auch abgemeldet', () => {
    for (const anker of ['erste-schritte', 'probleme']) {
      const eintrag = HILFE_KATALOG.find((e) => e.anker === anker);
      expect(eintrag?.bedingung).toBeUndefined();
      expect(eintrag?.abgemeldet).toBe(true);
    }
    expect(HILFE_KATALOG[0].anker).toBe('erste-schritte');
    expect(HILFE_KATALOG.at(-1)?.anker).toBe('probleme');
  });
});

describe('bedingungErfuellt', () => {
  it('ohne Bedingung für alle, auch ohne Rolle', () => {
    expect(bedingungErfuellt(undefined, null)).toBe(true);
    expect(bedingungErfuellt(undefined, 'treuhaender')).toBe(true);
  });

  it('mindestens eine Aktion aus «eine» und keine aus «ohne»', () => {
    const bedingung = { eine: ['A5'], ohne: ['A7'] } as const;
    expect(bedingungErfuellt(bedingung, 'mitarbeiter')).toBe(true);
    expect(bedingungErfuellt(bedingung, 'inhaber')).toBe(false);
    expect(bedingungErfuellt(bedingung, 'treuhaender')).toBe(false);
    expect(bedingungErfuellt({ eine: ['A11', 'A13', 'EXPORT'] }, 'treuhaender')).toBe(true);
    expect(bedingungErfuellt({ eine: ['A1'] }, null)).toBe(false);
  });
});

describe('Pfade der App', () => {
  it('erkennt Routen, auch mit Parametern, und Präfixe', () => {
    expect(istAppPfad('/', routes)).toBe(true);
    expect(istAppPfad('/erfassen', routes)).toBe(true);
    expect(istAppPfad('/dokument/abc-123', routes)).toBe(true);
    expect(istAppPfad('/dokument', routes)).toBe(false);
    expect(istAppPfadPraefix('/dokument', routes)).toBe(true);
    expect(istAppPfadPraefix('/wissen/entwuerfe', routes)).toBe(true);
  });

  it('lehnt unbekannte und fremde Pfade ab, auch über die Umleitung «**»', () => {
    for (const pfad of ['/gibt-es-nicht', '//example.com', '/erfassen?x=1', '/erfassen#a', '/Erfassen', 'erfassen', '/erfassen/']) {
      expect(istAppPfad(pfad, routes), pfad).toBe(false);
    }
  });
});
