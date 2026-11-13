import { HttpErrorResponse } from '@angular/common/http';
import { Export } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';

// Fachlogik der Seite «Export» ohne Angular (Spec 02, Aufgabe 6.5; Req 7.1, 7.2, 7.5)

/** Abstand der Abfrage, solange ein Export in Arbeit ist. */
export const ABFRAGE_MS = 5000;

/** Grenzen des Servers (`Geschaeftsjahr`, 400 ANFRAGE_UNGUELTIG ausserhalb). */
export const JAHR_MIN = 1900;
export const JAHR_MAX = 2999;

/** Vorschlag: das zuletzt abgeschlossene Kalenderjahr (Geschäftsjahresende 31.12. bei Urinet). */
export function standardJahr(heute: Date): number {
  return heute.getFullYear() - 1;
}

export function jahrGueltig(jahr: number | null): jahr is number {
  return jahr !== null && Number.isInteger(jahr) && jahr >= JAHR_MIN && jahr <= JAHR_MAX;
}

/** Ob die Seite weiter nachfragen soll (ein Export ist noch in Arbeit). */
export function inArbeit(liste: readonly Export[] | null): boolean {
  return (liste ?? []).some((e) => e.status === 'OFFEN');
}

/** Nach einem Fehler weiter nachfragen, ausser die Sitzung ist abgelaufen oder das Recht fehlt (401, 403). */
export function weiterFragen(fehler: unknown): boolean {
  const status = fehler instanceof HttpErrorResponse ? fehler.status : 0;
  return status !== 401 && status !== 403;
}

export function fehlerText(fehler: unknown): string {
  const status = fehler instanceof HttpErrorResponse ? fehler.status : 0;
  if (status === 401) {
    return TEXTE.export.fehlerSitzung;
  }
  if (status === 0) {
    return TEXTE.export.fehlerVerbindung;
  }
  const body = fehler instanceof HttpErrorResponse ? fehler.error : null;
  const code =
    body && typeof body === 'object' ? (body as Record<string, unknown>)['fehlercode'] : undefined;
  return (
    (typeof code === 'string' && TEXTE.export.fehlercode[code]) || TEXTE.export.fehlerAllgemein
  );
}
