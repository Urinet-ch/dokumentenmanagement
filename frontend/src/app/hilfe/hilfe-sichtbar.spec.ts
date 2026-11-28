import { Rolle } from '../gemeinsam/anmeldung/rechte';
import { HILFE_KATALOG, HilfeEintrag } from './hilfe-katalog';
import { sichtbareAbschnitte, sichtbareAnker } from './hilfe-sichtbar';

const eintrag = (anker: string, mehr: Partial<HilfeEintrag> = {}): HilfeEintrag => ({
  anker,
  titel: anker,
  datei: `/anleitung/${anker}.md`,
  ...mehr,
});

// Ausschnitt aus design.md «Hilfe-Katalog», synthetisch: Der echte Katalog wächst mit den Aufgaben 22.5 bis 22.7
const KATALOG: readonly HilfeEintrag[] = [
  eintrag('erste-schritte', { abgemeldet: true }),
  eintrag('erfassen', { bedingung: { eine: ['A1'] } }),
  eintrag('erfassen-handy', { eltern: 'erfassen', bedingung: { eine: ['A1'] } }),
  eintrag('erfassen-offline', { eltern: 'erfassen', bedingung: { eine: ['A1'] }, abgemeldet: true }),
  eintrag('eingangskorb', { bedingung: { eine: ['A5'] } }),
  eintrag('eingangskorb-bestaetigen', { eltern: 'eingangskorb', bedingung: { eine: ['A7'] } }),
  eintrag('eingangskorb-eigene', { eltern: 'eingangskorb', bedingung: { eine: ['A1'], ohne: ['A7'] } }),
  eintrag('suche', { bedingung: { eine: ['A2'] } }),
  eintrag('dokument', { bedingung: { eine: ['A2'] } }),
  eintrag('dokument-aendern', { eltern: 'dokument', bedingung: { eine: ['A6'] } }),
  eintrag('importe', { bedingung: { eine: ['A12'] } }),
  eintrag('jahresexport', { bedingung: { eine: ['EXPORT'] } }),
  eintrag('verwaltung', { bedingung: { eine: ['A11', 'A13', 'A14'] } }),
  eintrag('verwaltung-protokoll', { eltern: 'verwaltung', bedingung: { eine: ['A13'] } }),
  eintrag('probleme', { abgemeldet: true }),
];

const gliederung = (rolle: Rolle | null) =>
  sichtbareAbschnitte(KATALOG, rolle).map(({ eintrag, unter }) =>
    unter.length ? `${eintrag.anker}[${unter.map((u) => u.anker).join(' ')}]` : eintrag.anker,
  );

describe('Sichtbare Abschnitte der Anleitung (Spec 07, Req 6.2, 6.3)', () => {
  it('zeigt dem Inhaber alle Abschnitte, ohne die Hinweise für Rollen ohne Bestätigen', () => {
    expect(gliederung('inhaber')).toEqual([
      'erste-schritte',
      'erfassen[erfassen-handy erfassen-offline]',
      'eingangskorb[eingangskorb-bestaetigen]',
      'suche',
      'dokument[dokument-aendern]',
      'importe',
      'jahresexport',
      'verwaltung[verwaltung-protokoll]',
      'probleme',
    ]);
  });

  it('zeigt dem Mitarbeiter den Eingangskorb mit «ohne A7» und keine Abschnitte fremder Rechte', () => {
    expect(gliederung('mitarbeiter')).toEqual([
      'erste-schritte',
      'erfassen[erfassen-handy erfassen-offline]',
      'eingangskorb[eingangskorb-eigene]',
      'suche',
      'dokument',
      'probleme',
    ]);
  });

  it('zeigt dem Treuhänder Eingangskorb, Suche, Dokument und Jahresexport, nichts zum Erfassen', () => {
    expect(gliederung('treuhaender')).toEqual(['erste-schritte', 'eingangskorb', 'suche', 'dokument', 'jahresexport', 'probleme']);
  });

  it('zeigt der Geschäftsstelle Erfassen, Bestätigen und Ändern, nicht Importe, Jahresexport und Verwaltung', () => {
    expect(gliederung('geschaeftsstelle')).toEqual([
      'erste-schritte',
      'erfassen[erfassen-handy erfassen-offline]',
      'eingangskorb[eingangskorb-bestaetigen]',
      'suche',
      'dokument[dokument-aendern]',
      'probleme',
    ]);
  });

  it('zeigt ohne Rolle nur die allgemeinen Abschnitte und «erfassen-offline» an seiner Stelle', () => {
    expect(gliederung(null)).toEqual(['erste-schritte', 'erfassen-offline', 'probleme']);
  });

  it('zeigt einen Unterabschnitt mit Rolle nie ohne seinen Abschnitt', () => {
    const katalog = [eintrag('eltern', { bedingung: { eine: ['A12'] } }), eintrag('kind', { eltern: 'eltern' })];
    expect(sichtbareAbschnitte(katalog, 'mitarbeiter')).toEqual([]);
  });

  it('nennt alle Anker aus Abschnitten und Unterabschnitten', () => {
    expect([...sichtbareAnker(sichtbareAbschnitte(KATALOG, 'treuhaender'))]).toEqual([
      'erste-schritte',
      'eingangskorb',
      'suche',
      'dokument',
      'jahresexport',
      'probleme',
    ]);
  });

  // Gegen die Tabellen in requirements.md (6.1) und design.md: was eine Rolle nie sieht, gleich wie viele Einträge
  // der echte Katalog schon hat
  const VERBORGEN: Record<Rolle, readonly string[]> = {
    inhaber: ['eingangskorb-eigene'],
    mitarbeiter: [
      'eingangskorb-bestaetigen', 'dokument-aendern', 'kontakte', 'wissen-entwuerfe', 'importe', 'jahresexport',
      'quellen', 'verwaltung', 'verwaltung-dokumentarten', 'verwaltung-protokoll', 'verwaltung-api-tokens',
    ],
    treuhaender: [
      'erfassen', 'erfassen-handy', 'erfassen-dateien', 'erfassen-offline', 'eingangskorb-bestaetigen',
      'eingangskorb-eigene', 'dokument-aendern', 'kontakte', 'wissen-entwuerfe', 'importe', 'verwaltung',
      'verwaltung-dokumentarten', 'verwaltung-protokoll', 'verwaltung-api-tokens',
    ],
    geschaeftsstelle: [
      'eingangskorb-eigene', 'importe', 'jahresexport', 'verwaltung', 'verwaltung-dokumentarten',
      'verwaltung-protokoll', 'verwaltung-api-tokens',
    ],
  };

  // Alle Anker der Tabelle in design.md; spätere Einträge (z. B. aus Spec 06) prüft dieser Test nicht
  const TABELLE = new Set([
    'erste-schritte', 'erfassen', 'erfassen-handy', 'erfassen-dateien', 'erfassen-offline', 'eingangskorb',
    'eingangskorb-bestaetigen', 'eingangskorb-eigene', 'suche', 'chat', 'chat-bewerten', 'dokument', 'dokument-aendern', 'kontakte',
    'wissen', 'wissen-entwuerfe', 'importe', 'quellen', 'jahresexport', 'verwaltung', 'verwaltung-dokumentarten',
    'verwaltung-protokoll', 'verwaltung-api-tokens', 'probleme',
  ]);

  for (const [rolle, verborgen] of Object.entries(VERBORGEN) as [Rolle, readonly string[]][]) {
    it(`zeigt «${rolle}» im echten Katalog genau die Einträge der Tabelle ausser den verborgenen`, () => {
      const anker = sichtbareAnker(sichtbareAbschnitte(HILFE_KATALOG, rolle));
      for (const { anker: a } of HILFE_KATALOG.filter((e) => TABELLE.has(e.anker))) {
        expect(anker.has(a), `${rolle}: ${a}`).toBe(!verborgen.includes(a));
      }
    });
  }

  it('zeigt abgemeldet im echten Katalog nur Einträge mit «abgemeldet»', () => {
    const anker = sichtbareAnker(sichtbareAbschnitte(HILFE_KATALOG, null));
    expect([...anker]).toEqual(HILFE_KATALOG.filter((e) => e.abgemeldet).map((e) => e.anker));
    expect(anker.has('erste-schritte') && anker.has('probleme')).toBe(true);
  });
});
