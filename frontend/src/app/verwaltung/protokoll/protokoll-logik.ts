import { HttpErrorResponse } from '@angular/common/http';
import { ProtokollEintrag, VerzeichnisEintrag } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';

// Fachlogik der Seite «Zugriffsprotokoll» ohne Angular (Aufgabe 5.1; Req 10.5, 10.10)

export const SEITE = 50;
export const AKTIONEN = Object.values(ProtokollEintrag.AktionEnum);

const UHR = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Europe/Zurich',
  hourCycle: 'h23',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});

/** Versatz von Europe/Zurich gegenüber UTC zum Zeitpunkt `t` (ms). */
function versatz(t: number): number {
  const teil = Object.fromEntries(UHR.formatToParts(new Date(t)).map((p) => [p.type, Number(p.value)]));
  return Date.UTC(teil['year'], teil['month'] - 1, teil['day'], teil['hour'], teil['minute'], teil['second']) - t;
}

/** Beginn des Kalendertags (`YYYY-MM-DD`, + `tage`) in Europe/Zurich als UTC-Zeitpunkt (ISO 8601). */
export function tagesbeginn(datum: string, tage = 0): string | null {
  const teile = /^(\d{4})-(\d{2})-(\d{2})$/.exec(datum);
  if (!teile) {
    return null;
  }
  const mitternacht = Date.UTC(Number(teile[1]), Number(teile[2]) - 1, Number(teile[3]) + tage);
  // zweimal, weil der Versatz am Tag der Zeitumstellung vom Ergebnis abhängt
  const ungefaehr = mitternacht - versatz(mitternacht);
  return new Date(mitternacht - versatz(ungefaehr)).toISOString();
}

export interface Filter {
  von: string;
  bis: string;
  benutzer: string;
  dokument: string;
  aktion: string;
}

export const LEERER_FILTER: Filter = { von: '', bis: '', benutzer: '', dokument: '', aktion: '' };

/** Parameter der Abfrage; «bis» schliesst den gewählten Tag ein (Server: `[von, bis)`). */
export function parameter(filter: Filter): {
  von?: string;
  bis?: string;
  benutzer?: string;
  dokument?: string;
  aktion?: ProtokollEintrag.AktionEnum;
} {
  return {
    von: tagesbeginn(filter.von) ?? undefined,
    bis: tagesbeginn(filter.bis, 1) ?? undefined,
    benutzer: filter.benutzer.trim() || undefined,
    dokument: filter.dokument.trim() || undefined,
    aktion: AKTIONEN.find((a) => a === filter.aktion),
  };
}

/** Prüft die Eingaben vor der Abfrage; der Server prüft sie ebenfalls (ANFRAGE_UNGUELTIG). */
export function filterFehler(filter: Filter): string | null {
  if (filter.von && filter.bis && filter.von > filter.bis) {
    return TEXTE.protokoll.vonNachBis;
  }
  const dokument = filter.dokument.trim();
  if (dokument && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(dokument)) {
    return TEXTE.protokoll.dokumentUngueltig;
  }
  return null;
}

export function rolleText(rolle: string | null | undefined): string {
  return rolle ? (TEXTE.protokoll.rollen[rolle] ?? rolle) : '';
}

/** Name und Rolle zur Kennung, wie sie zur Zeit des Eintrags galten (Req 10.10). */
export function wer(eintrag: ProtokollEintrag): string {
  return eintrag.benutzer ? person(eintrag.name, eintrag.rolle) : TEXTE.protokoll.system;
}

export function person(name: string | null | undefined, rolle: string | null | undefined): string {
  const text = rolleText(rolle);
  return text ? `${name || TEXTE.protokoll.unbekannt} (${text})` : name || TEXTE.protokoll.unbekannt;
}

/**
 * Austritt nur für Personen (Tokens endet der Widerruf, A14) und nur an der letzten Zeile der Kennung, solange sie
 * kein Ende hat; die Liste kommt je Kennung in der Reihenfolge des Einfügens.
 */
export function austrittMoeglich(eintrag: VerzeichnisEintrag, alle: readonly VerzeichnisEintrag[]): boolean {
  const letzter = alle.filter((e) => e.benutzer === eintrag.benutzer).at(-1);
  return letzter === eintrag && !eintrag.gueltigBis && !eintrag.benutzer.startsWith('token:');
}

function problem(fehler: unknown): Record<string, unknown> {
  const inhalt: unknown = fehler instanceof HttpErrorResponse ? fehler.error : null;
  return typeof inhalt === 'object' && inhalt !== null ? (inhalt as Record<string, unknown>) : {};
}

/** Meldung zu einer Fehlerantwort (Problem Details) ohne technische Einzelheiten. */
export function fehlerText(fehler: unknown): string {
  const status = fehler instanceof HttpErrorResponse ? fehler.status : 0;
  if (status === 401) {
    return TEXTE.protokoll.fehlerSitzung;
  }
  if (status === 0) {
    return TEXTE.protokoll.fehlerVerbindung;
  }
  const code = problem(fehler)['fehlercode'];
  return (typeof code === 'string' && TEXTE.protokoll.fehlercode[code]) || TEXTE.protokoll.fehlerAllgemein;
}
