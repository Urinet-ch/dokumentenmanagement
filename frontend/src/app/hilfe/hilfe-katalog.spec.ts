import { routes } from '../app.routes';
import { AKTIONEN, Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { MENUE, aktiverPfad } from '../gemeinsam/navigation';
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

  it('führt die Abschnitte in der Reihenfolge von Req 6.1 und Unterabschnitte nur unter ihrem Abschnitt', () => {
    // Req 6.1; die Abschnitte kommen mit 22.5 bis 22.7, darum genügt hier die Reihenfolge der vorhandenen.
    const reihenfolge = ['erste-schritte', 'erfassen', 'eingangskorb', 'suche', 'chat', 'dokument', 'kontakte', 'wissen',
      'importe', 'jahresexport', 'verwaltung', 'probleme'];
    const abschnitte = HILFE_KATALOG.filter((e) => !e.eltern).map((e) => e.anker);
    expect(abschnitte.every((a) => reihenfolge.includes(a)), abschnitte.join(', ')).toBe(true);
    expect(abschnitte).toEqual(reihenfolge.filter((a) => abschnitte.includes(a)));
    HILFE_KATALOG.forEach((eintrag, index) => {
      if (!eintrag.eltern) return;
      const vorher = HILFE_KATALOG.slice(0, index).filter((e) => !e.eltern).at(-1);
      expect(vorher?.anker, eintrag.anker).toBe(eintrag.eltern);
      expect(eintrag.anker.startsWith(`${eintrag.eltern}-`), eintrag.anker).toBe(true);
    });
  });

  it('Bestand und Verwaltung nach design.md «Hilfe-Katalog» (Aufgabe 22.7)', () => {
    const erwartet: Record<string, { eine: string[]; eltern?: string; pfade?: string[] }> = {
      importe: { eine: ['A12'], pfade: ['/importe'] },
      jahresexport: { eine: ['EXPORT'], pfade: ['/export'] },
      verwaltung: { eine: ['A11', 'A13', 'A14'], pfade: ['/verwaltung/benutzer'] },
      'verwaltung-dokumentarten': { eine: ['A11'], eltern: 'verwaltung', pfade: ['/verwaltung/dokumentarten'] },
      'verwaltung-protokoll': { eine: ['A13'], eltern: 'verwaltung', pfade: ['/verwaltung/protokoll'] },
      'verwaltung-api-tokens': { eine: ['A14'], eltern: 'verwaltung', pfade: ['/verwaltung/api-tokens'] },
    };
    for (const [anker, soll] of Object.entries(erwartet)) {
      const eintrag = HILFE_KATALOG.find((e) => e.anker === anker);
      expect(eintrag?.bedingung, anker).toEqual({ eine: soll.eine });
      expect(eintrag?.eltern, anker).toBe(soll.eltern);
      expect(eintrag?.pfade, anker).toEqual(soll.pfade);
      expect(eintrag?.abgemeldet, anker).toBeUndefined();
    }
    // Nur der Inhaber hat heute A11 bis A14 und A12; der Jahresexport gehört Inhaber und Treuhänder (rechte.ts).
    const verwaltung = HILFE_KATALOG.find((e) => e.anker === 'verwaltung');
    expect(ROLLEN.filter((rolle) => bedingungErfuellt(verwaltung?.bedingung, rolle))).toEqual(['inhaber']);
    const jahresexport = HILFE_KATALOG.find((e) => e.anker === 'jahresexport');
    expect(ROLLEN.filter((rolle) => bedingungErfuellt(jahresexport?.bedingung, rolle))).toEqual(['inhaber', 'treuhaender']);
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

  it('Suche, Chat, Kontakte und Wissen mit Bedingung, Eltern und Reihenfolge nach Req 6.1 und design.md (Aufgabe 22.6)', () => {
    const erwartet = [
      { anker: 'suche', eine: ['A2'], eltern: undefined },
      { anker: 'chat', eine: ['A2'], eltern: undefined },
      { anker: 'kontakte', eine: ['A6'], eltern: undefined },
      { anker: 'wissen', eine: ['A15'], eltern: undefined },
      { anker: 'wissen-entwuerfe', eine: ['A17'], eltern: 'wissen' },
      { anker: 'wissen-quellen', eine: ['A16'], eltern: 'wissen' },
    ];
    const positionen = erwartet.map(({ anker }) => HILFE_KATALOG.findIndex((e) => e.anker === anker));
    expect(positionen.every((p) => p >= 0), erwartet.map((e) => e.anker).join(', ')).toBe(true);
    expect([...positionen].sort((a, b) => a - b)).toEqual(positionen);
    erwartet.forEach(({ anker, eine, eltern }, i) => {
      const eintrag = HILFE_KATALOG[positionen[i]];
      expect(eintrag.bedingung, anker).toEqual({ eine });
      expect(eintrag.eltern, anker).toBe(eltern);
      expect(eintrag.abgemeldet, anker).toBeUndefined();
    });
  });

  it('zeigt die Abschnitte aus Aufgabe 22.6 genau den Rollen nach Requirement 2.2 und Spec 04, 12.1', () => {
    const sichtbar = (rolle: Rolle) =>
      ['suche', 'chat', 'kontakte', 'wissen', 'wissen-entwuerfe', 'wissen-quellen'].filter((anker) =>
        bedingungErfuellt(HILFE_KATALOG.find((e) => e.anker === anker)?.bedingung, rolle),
      );
    expect(sichtbar('inhaber')).toEqual([
      'suche', 'chat', 'kontakte', 'wissen', 'wissen-entwuerfe', 'wissen-quellen',
    ]);
    expect(sichtbar('geschaeftsstelle')).toEqual([
      'suche', 'chat', 'kontakte', 'wissen', 'wissen-entwuerfe', 'wissen-quellen',
    ]);
    expect(sichtbar('mitarbeiter')).toEqual(['suche', 'chat', 'wissen']);
    expect(sichtbar('treuhaender')).toEqual(['suche', 'chat', 'wissen']);
  });

  it('ordnet die Seiten der Wissensbasis wie das Menü dem längsten Pfad zu (Aufgabe 22.6)', () => {
    const pfade = HILFE_KATALOG.flatMap((e) => e.pfade ?? []);
    const anker = (url: string) => HILFE_KATALOG.find((e) => e.pfade?.includes(aktiverPfad(url, pfade) ?? ''))?.anker;
    expect(anker('/wissen')).toBe('wissen');
    expect(anker('/wissen/konzepte/beispiel')).toBe('wissen');
    expect(anker('/wissen/entwuerfe')).toBe('wissen-entwuerfe');
    expect(anker('/wissen/entwuerfe/import')).toBe('wissen-entwuerfe');
    expect(anker('/wissen/entwuerfe/meine/abc')).toBe('wissen');
    expect(anker('/wissensbasis/einstellungen')).toBe('wissen');
    expect(anker('/suche?q=x')).toBe('suche');
    expect(anker('/kontakte/abc')).toBe('kontakte');
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
