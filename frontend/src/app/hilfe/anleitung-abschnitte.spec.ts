import { routes } from '../app.routes';
import { Aktion, Rolle } from '../gemeinsam/anmeldung/rechte';
import { Anleitungsteil, anleitungMarkdown } from './anleitung-markdown';
import { istAppPfad } from './app-pfade';
import { HILFE_KATALOG, HilfeEintrag, bedingungErfuellt, hilfeEintrag } from './hilfe-katalog';

// Abschnitte der Anleitung gegen Spec 07, Req 6.1 und design.md «Hilfe-Katalog» (Aufgabe 22.5): Reihenfolge, Titel,
// Bedingungen, Verweise zwischen Abschnitten und die Namen aus texte.de-CH.ts. Gelesen wird wie in
// anleitung-inhalt.spec.ts ohne @types/node, Arbeitsverzeichnis frontend/.
interface Dateisystem {
  readFileSync(pfad: string): Uint8Array;
}
const fs = (globalThis as unknown as { process: { getBuiltinModule(name: string): Dateisystem } }).process.getBuiltinModule(
  'node:fs',
);
const lesen = (pfad: string) => new TextDecoder().decode(fs.readFileSync(pfad));

interface Soll {
  anker: string;
  titel: string;
  eltern?: string;
  eine?: Aktion[];
  ohne?: Aktion[];
  pfade?: string[];
  abgemeldet?: boolean;
}

/** Die Tabelle aus design.md «Hilfe-Katalog» in Lesereihenfolge (Abschnitte nach Req 6.1), mit den dort nachgetragenen Abweichungen. */
const SOLL: readonly Soll[] = [
  { anker: 'erste-schritte', titel: 'Erste Schritte', pfade: ['/'], abgemeldet: true },
  { anker: 'erfassen', titel: 'Erfassen', eine: ['A1'], pfade: ['/erfassen'] },
  { anker: 'erfassen-handy', titel: 'Mit dem Handy fotografieren', eltern: 'erfassen', eine: ['A1'] },
  { anker: 'erfassen-dateien', titel: 'Dateien hochladen', eltern: 'erfassen', eine: ['A1'] },
  { anker: 'erfassen-offline', titel: 'Ohne Netz erfassen', eltern: 'erfassen', eine: ['A1'], abgemeldet: true },
  { anker: 'eingangskorb', titel: 'Eingangskorb', eine: ['A5'], pfade: ['/eingangskorb'] },
  { anker: 'eingangskorb-bestaetigen', titel: 'Prüfen, korrigieren und bestätigen', eltern: 'eingangskorb', eine: ['A7'] },
  { anker: 'eingangskorb-eigene', titel: 'Ihre erfassten Dokumente verfolgen', eltern: 'eingangskorb', eine: ['A5'], ohne: ['A7'] },
  { anker: 'suche', titel: 'Suche', eine: ['A2'], pfade: ['/suche'] },
  { anker: 'chat', titel: 'Chat', eine: ['A2'], pfade: ['/chat'] },
  { anker: 'chat-bewerten', titel: 'Antworten bewerten', eltern: 'chat', eine: ['A2'] },
  { anker: 'chat-auswertung', titel: 'Chat-Auswertung', eltern: 'chat', eine: ['A24'], pfade: ['/chat/auswertung'] },
  { anker: 'dokument', titel: 'Dokumentansicht', eine: ['A2'], pfade: ['/dokument'] },
  { anker: 'dokument-aendern', titel: 'Angaben ändern', eltern: 'dokument', eine: ['A6'] },
  { anker: 'kontakte', titel: 'Kontakte', eine: ['A6'], pfade: ['/kontakte'] },
  // Pfade abweichend von der Tabelle, design.md «Umsetzung (Aufgabe 22.6)»
  { anker: 'wissen', titel: 'Wissen', eine: ['A15'], pfade: ['/wissen', '/wissen/entwuerfe/meine', '/wissensbasis'] },
  { anker: 'wissen-entwuerfe', titel: 'Wissensentwürfe', eltern: 'wissen', eine: ['A17'], pfade: ['/wissen/entwuerfe'] },
  { anker: 'wissen-quellen', titel: 'Wissensquellen markieren und verwalten', eltern: 'wissen', eine: ['A16'] },
  { anker: 'importe', titel: 'Importe', eine: ['A12'], pfade: ['/importe'] },
  { anker: 'jahresexport', titel: 'Jahresexport', eine: ['EXPORT'], pfade: ['/export'] },
  { anker: 'verwaltung', titel: 'Verwaltung', eine: ['A11', 'A13', 'A14'], pfade: ['/verwaltung/benutzer'] },
  { anker: 'verwaltung-dokumentarten', titel: 'Dokumentarten', eltern: 'verwaltung', eine: ['A11'], pfade: ['/verwaltung/dokumentarten'] },
  { anker: 'verwaltung-protokoll', titel: 'Zugriffsprotokoll', eltern: 'verwaltung', eine: ['A13'], pfade: ['/verwaltung/protokoll'] },
  { anker: 'verwaltung-api-tokens', titel: 'API-Tokens', eltern: 'verwaltung', eine: ['A14'], pfade: ['/verwaltung/api-tokens'] },
  { anker: 'probleme', titel: 'Wenn etwas nicht klappt', pfade: ['/nicht-gefunden'], abgemeldet: true },
];

/** Die Dateien der Aufgabe 22.5. */
const AUFGABE_22_5 = [
  'erste-schritte',
  'erfassen',
  'erfassen-handy',
  'erfassen-dateien',
  'erfassen-offline',
  'eingangskorb',
  'eingangskorb-bestaetigen',
  'eingangskorb-eigene',
  'dokument',
  'dokument-aendern',
];

/** Namen in Guillemets, die nicht aus der App stammen: Menüs der Browser (erfassen-handy.md). */
const FREMDE_NAMEN = ['Zum Home-Bildschirm', 'App installieren', 'Zum Startbildschirm hinzufügen'];

const SICHTEN: readonly (Rolle | null)[] = ['inhaber', 'geschaeftsstelle', 'mitarbeiter', 'treuhaender', null];

/** Sichtbarkeit nach Req 6.2 und 6.3: mit Rolle nach Bedingung und nur mit dem Abschnitt; ohne Rolle nur «abgemeldet». */
function sichtbar(eintrag: HilfeEintrag, rolle: Rolle | null): boolean {
  if (rolle === null) return eintrag.abgemeldet === true;
  if (!bedingungErfuellt(eintrag.bedingung, rolle)) return false;
  const eltern = eintrag.eltern ? hilfeEintrag(eintrag.eltern) : undefined;
  return !eltern || sichtbar(eltern, rolle);
}

function verweise(teile: readonly Anleitungsteil[]): string[] {
  return teile.flatMap((teil) => [...(teil.art === 'abschnitt' && teil.anker ? [teil.anker] : []), ...verweise(teil.kinder ?? [])]);
}

describe('Abschnitte der Anleitung (Spec 07, Req 6.1)', () => {
  it('der Katalog folgt der Reihenfolge aus Req 6.1 und design.md', () => {
    const reihenfolge = SOLL.map((soll) => soll.anker);
    const positionen = HILFE_KATALOG.map((eintrag) => reihenfolge.indexOf(eintrag.anker));
    expect(positionen, 'unbekannter Anker').not.toContain(-1);
    expect(positionen).toEqual([...positionen].sort((a, b) => a - b));
  });

  it('jeder Eintrag hat Titel, Abschnitt, Bedingung, Pfade und «abgemeldet» wie in design.md', () => {
    for (const eintrag of HILFE_KATALOG) {
      const soll = SOLL.find((s) => s.anker === eintrag.anker);
      if (!soll) continue;
      expect(eintrag.titel, eintrag.anker).toBe(soll.titel);
      expect(eintrag.eltern, eintrag.anker).toBe(soll.eltern);
      expect(eintrag.bedingung?.eine ?? [], eintrag.anker).toEqual(soll.eine ?? []);
      expect(eintrag.bedingung?.ohne ?? [], eintrag.anker).toEqual(soll.ohne ?? []);
      expect(eintrag.pfade ?? [], eintrag.anker).toEqual(soll.pfade ?? []);
      expect(eintrag.abgemeldet === true, eintrag.anker).toBe(soll.abgemeldet === true);
    }
  });

  it('enthält alle Abschnitte der Aufgabe 22.5', () => {
    for (const anker of AUFGABE_22_5) expect(hilfeEintrag(anker), anker).toBeDefined();
  });

  it('verweist nur auf Abschnitte, die jeder sieht, der den verweisenden Abschnitt sieht (Req 6.2, 6.3)', () => {
    for (const eintrag of HILFE_KATALOG) {
      const { teile } = anleitungMarkdown(lesen(`public${eintrag.datei}`), { istAppPfad: (pfad) => istAppPfad(pfad, routes) });
      for (const ziel of verweise(teile)) {
        for (const rolle of SICHTEN) {
          if (sichtbar(eintrag, rolle)) {
            expect(sichtbar(hilfeEintrag(ziel)!, rolle), `${eintrag.anker} → ${ziel}, ${rolle ?? 'abgemeldet'}`).toBe(true);
          }
        }
      }
    }
  });

  it('nennt Knöpfe und Meldungen mit dem Wortlaut aus texte.de-CH.ts (Req 6.6, 9.3)', () => {
    const texte = lesen('src/app/gemeinsam/texte.de-CH.ts');
    for (const anker of AUFGABE_22_5) {
      const inhalt = lesen(`public/anleitung/${anker}.md`);
      for (const [, name] of inhalt.matchAll(/«([^«»]+)»/g)) {
        if (FREMDE_NAMEN.includes(name)) continue;
        expect(texte.includes(name), `${anker}: «${name}»`).toBe(true);
      }
    }
  });
});
