import { Aktion, Rolle, WISSEN_AKTIONEN, darf } from '../gemeinsam/anmeldung/rechte';
import { TEXTE } from '../gemeinsam/texte.de-CH';

/** Erfüllt, wenn die Rolle mindestens eine Aktion aus «eine» darf und keine aus «ohne» (Glossar Spec 07, «Bedingung»). */
export interface Bedingung {
  readonly eine: readonly Aktion[];
  readonly ohne?: readonly Aktion[];
}

export interface HilfeEintrag {
  /** Sprungmarke «/hilfe#<anker>» und Name der Datei «<anker>.md». */
  readonly anker: string;
  readonly titel: string;
  /** Adresse der Datei, immer «/anleitung/<anker>.md» (Spec 07, Req 7.1). */
  readonly datei: string;
  /** Anker des übergeordneten Abschnitts; ohne ist es ein Abschnitt. */
  readonly eltern?: string;
  /** Ohne Bedingung ist der Eintrag allgemein. */
  readonly bedingung?: Bedingung;
  /** Präfixe der App, denen die Hilfe-Leiste diesen Eintrag zuordnet (wie «aktiverPfad» in navigation.ts). */
  readonly pfade?: readonly string[];
  /** Auch ohne Anmeldung oder ohne Rolle sichtbar (Spec 07, Req 6.3). */
  readonly abgemeldet?: boolean;
}

/**
 * Die eine Liste der Anleitung in Lesereihenfolge (Spec 07, Req 7.2; design.md «Hilfe-Katalog»). Neue Abschnitte
 * kommen mit ihrer Datei unter `frontend/public/anleitung/` (Aufgaben 22.5 bis 22.7 und die Pflegeregel, Req 7.5).
 */
export const HILFE_KATALOG: readonly HilfeEintrag[] = [
  {
    anker: 'erste-schritte',
    titel: TEXTE.hilfe.abschnitte['erste-schritte'],
    datei: '/anleitung/erste-schritte.md',
    pfade: ['/', '/anmeldung'],
    abgemeldet: true,
  },
  // Mit Passkey anmelden (24.3, Req 10): alle angemeldeten Rollen, ohne Pfad
  {
    anker: 'erste-schritte-passkey',
    titel: TEXTE.hilfe.abschnitte['erste-schritte-passkey'],
    datei: '/anleitung/erste-schritte-passkey.md',
    eltern: 'erste-schritte',
  },
  // Mein Konto (Spec 06, Req 11): alle angemeldeten Rollen, Seite /konto
  {
    anker: 'erste-schritte-konto',
    titel: TEXTE.hilfe.abschnitte['erste-schritte-konto'],
    datei: '/anleitung/erste-schritte-konto.md',
    eltern: 'erste-schritte',
    pfade: ['/konto'],
  },
  // Erfassen und Eingangskorb (Aufgabe 22.5)
  {
    anker: 'erfassen',
    titel: TEXTE.hilfe.abschnitte['erfassen'],
    datei: '/anleitung/erfassen.md',
    bedingung: { eine: ['A1'] },
    pfade: ['/erfassen'],
  },
  {
    anker: 'erfassen-handy',
    titel: TEXTE.hilfe.abschnitte['erfassen-handy'],
    datei: '/anleitung/erfassen-handy.md',
    eltern: 'erfassen',
    bedingung: { eine: ['A1'] },
  },
  {
    anker: 'erfassen-dateien',
    titel: TEXTE.hilfe.abschnitte['erfassen-dateien'],
    datei: '/anleitung/erfassen-dateien.md',
    eltern: 'erfassen',
    bedingung: { eine: ['A1'] },
  },
  {
    anker: 'erfassen-offline',
    titel: TEXTE.hilfe.abschnitte['erfassen-offline'],
    datei: '/anleitung/erfassen-offline.md',
    eltern: 'erfassen',
    bedingung: { eine: ['A1'] },
    abgemeldet: true,
  },
  // Trennblätter (Spec 09, Aufgabe 27.5): wie der Menüpunkt mit A1
  {
    anker: 'erfassen-trennblaetter',
    titel: TEXTE.hilfe.abschnitte['erfassen-trennblaetter'],
    datei: '/anleitung/erfassen-trennblaetter.md',
    eltern: 'erfassen',
    bedingung: { eine: ['A1'] },
    pfade: ['/trennblaetter'],
  },
  {
    anker: 'eingangskorb',
    titel: TEXTE.hilfe.abschnitte['eingangskorb'],
    datei: '/anleitung/eingangskorb.md',
    bedingung: { eine: ['A5'] },
    pfade: ['/eingangskorb'],
  },
  {
    anker: 'eingangskorb-bestaetigen',
    titel: TEXTE.hilfe.abschnitte['eingangskorb-bestaetigen'],
    datei: '/anleitung/eingangskorb-bestaetigen.md',
    eltern: 'eingangskorb',
    bedingung: { eine: ['A7'] },
  },
  {
    anker: 'eingangskorb-eigene',
    titel: TEXTE.hilfe.abschnitte['eingangskorb-eigene'],
    datei: '/anleitung/eingangskorb-eigene.md',
    eltern: 'eingangskorb',
    bedingung: { eine: ['A1'], ohne: ['A7'] },
  },
  {
    anker: 'suche',
    titel: TEXTE.hilfe.abschnitte['suche'],
    datei: '/anleitung/suche.md',
    bedingung: { eine: ['A2'] },
    pfade: ['/suche'],
  },
  {
    anker: 'chat',
    titel: TEXTE.hilfe.abschnitte['chat'],
    datei: '/anleitung/chat.md',
    bedingung: { eine: ['A2'] },
    pfade: ['/chat'],
  },
  {
    anker: 'chat-bewerten',
    titel: TEXTE.hilfe.abschnitte['chat-bewerten'],
    datei: '/anleitung/chat-bewerten.md',
    eltern: 'chat',
    bedingung: { eine: ['A2'] },
  },
  {
    anker: 'chat-auswertung',
    titel: TEXTE.chat.auswertung.titel,
    datei: '/anleitung/chat-auswertung.md',
    eltern: 'chat',
    bedingung: { eine: ['A24'] },
    pfade: ['/chat/auswertung'],
  },
  // Dokumentansicht (Aufgabe 22.5), zwischen «chat» und «kontakte»
  {
    anker: 'dokument',
    titel: TEXTE.hilfe.abschnitte['dokument'],
    datei: '/anleitung/dokument.md',
    bedingung: { eine: ['A2'] },
    pfade: ['/dokument'],
  },
  {
    anker: 'dokument-aendern',
    titel: TEXTE.hilfe.abschnitte['dokument-aendern'],
    datei: '/anleitung/dokument-aendern.md',
    eltern: 'dokument',
    bedingung: { eine: ['A6'] },
  },
  {
    anker: 'kontakte',
    titel: TEXTE.hilfe.abschnitte['kontakte'],
    datei: '/anleitung/kontakte.md',
    bedingung: { eine: ['A6'] },
    pfade: ['/kontakte'],
  },
  {
    anker: 'wissen',
    titel: TEXTE.hilfe.abschnitte['wissen'],
    datei: '/anleitung/wissen.md',
    bedingung: { eine: ['A15'] },
    // «Meine Analysevorschläge» (A18) und die Einstellungen (A19) beschreibt der Abschnitt selbst (Aufgabe 22.6).
    pfade: ['/wissen', '/wissen/entwuerfe/meine', '/wissensbasis'],
  },
  {
    anker: 'wissen-entwuerfe',
    titel: TEXTE.hilfe.abschnitte['wissen-entwuerfe'],
    datei: '/anleitung/wissen-entwuerfe.md',
    eltern: 'wissen',
    bedingung: { eine: ['A17'] },
    pfade: ['/wissen/entwuerfe'],
  },
  {
    anker: 'wissen-quellen',
    titel: TEXTE.hilfe.abschnitte['wissen-quellen'],
    datei: '/anleitung/wissen-quellen.md',
    eltern: 'wissen',
    bedingung: { eine: ['A16'] },
  },
  // Bestand und Verwaltung (Spec 07, Aufgabe 22.7)
  {
    anker: 'importe',
    titel: TEXTE.hilfe.abschnitte['importe'],
    datei: '/anleitung/importe.md',
    bedingung: { eine: ['A12'] },
    pfade: ['/importe'],
  },
  {
    anker: 'jahresexport',
    titel: TEXTE.hilfe.abschnitte['jahresexport'],
    datei: '/anleitung/jahresexport.md',
    bedingung: { eine: ['EXPORT'] },
    pfade: ['/export'],
  },
  {
    anker: 'verwaltung',
    titel: TEXTE.hilfe.abschnitte['verwaltung'],
    datei: '/anleitung/verwaltung.md',
    bedingung: { eine: ['A11', 'A13', 'A14'] },
    pfade: ['/verwaltung/benutzer', '/verwaltung/einstellungen'],
  },
  {
    anker: 'verwaltung-dokumentarten',
    titel: TEXTE.hilfe.abschnitte['verwaltung-dokumentarten'],
    datei: '/anleitung/verwaltung-dokumentarten.md',
    eltern: 'verwaltung',
    bedingung: { eine: ['A11'] },
    pfade: ['/verwaltung/dokumentarten'],
  },
  {
    anker: 'verwaltung-protokoll',
    titel: TEXTE.hilfe.abschnitte['verwaltung-protokoll'],
    datei: '/anleitung/verwaltung-protokoll.md',
    eltern: 'verwaltung',
    bedingung: { eine: ['A13'] },
    pfade: ['/verwaltung/protokoll'],
  },
  {
    anker: 'verwaltung-api-tokens',
    titel: TEXTE.hilfe.abschnitte['verwaltung-api-tokens'],
    datei: '/anleitung/verwaltung-api-tokens.md',
    eltern: 'verwaltung',
    bedingung: { eine: ['A14'] },
    pfade: ['/verwaltung/api-tokens'],
  },
  {
    anker: 'probleme',
    titel: TEXTE.hilfe.abschnitte['probleme'],
    datei: '/anleitung/probleme.md',
    pfade: ['/nicht-gefunden'],
    abgemeldet: true,
  },
];

/**
 * Dieselbe Prüfung wie das Menü («darf» in rechte.ts und `AnmeldungService`); ohne Bedingung immer erfüllt. `wissen`:
 * Ist Wissen für den Mandanten freigeschaltet? Sonst gelten A15–A19 als nicht erlaubt (Spec 04, Req 22.5). Die Seiten
 * übergeben den geladenen Stand; der Standard `true` prüft nur die Rolle (Katalogprüfungen).
 */
export function bedingungErfuellt(bedingung: Bedingung | undefined, rolle: Rolle | null, wissen = true): boolean {
  if (!bedingung) return true;
  const erlaubt = (aktion: Aktion) => darf(rolle, aktion) && (wissen || !WISSEN_AKTIONEN.has(aktion));
  return bedingung.eine.some(erlaubt) && !(bedingung.ohne ?? []).some(erlaubt);
}

export function hilfeEintrag(anker: string): HilfeEintrag | undefined {
  return HILFE_KATALOG.find((eintrag) => eintrag.anker === anker);
}
