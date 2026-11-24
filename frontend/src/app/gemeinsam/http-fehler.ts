import { HttpErrorResponse } from '@angular/common/http';
import { TEXTE } from './texte.de-CH';
import { retryAfterSekunden } from './retry-after';

export function httpFehlerMeldung(fehler: unknown): string {
  if (!(fehler instanceof HttpErrorResponse)) {
    return TEXTE.httpFehler.allgemein;
  }
  return httpFehlerText(
    fehler.status,
    fehler.status === 429 ? retryAfterSekunden(fehler.headers.get('Retry-After')) : null,
  );
}

export async function httpFehlercode(fehler: unknown): Promise<string | undefined> {
  if (!(fehler instanceof HttpErrorResponse)) return undefined;
  const inhalt: unknown = fehler.error;
  if (typeof Blob !== 'undefined' && inhalt instanceof Blob) {
    try {
      return problemFehlercode(JSON.parse(await inhalt.text()));
    } catch {
      return undefined;
    }
  }
  return problemFehlercode(inhalt);
}

function problemFehlercode(inhalt: unknown): string | undefined {
  return typeof inhalt === 'object' && inhalt !== null
    && 'fehlercode' in inhalt && typeof inhalt.fehlercode === 'string'
    ? inhalt.fehlercode
    : undefined;
}

export function httpFehlerText(status: number, retryAfter: number | null = null): string {
  switch (status) {
    case 0:
      return TEXTE.httpFehler.verbindung;
    case 401:
      return TEXTE.anmeldung.abgelaufen;
    case 403:
      return TEXTE.httpFehler.keinZugriff;
    case 404:
      return TEXTE.httpFehler.nichtGefunden;
    case 409:
    case 412:
      return TEXTE.httpFehler.konflikt;
    case 429:
      return TEXTE.httpFehler.zuViele(retryAfter);
    default:
      return status >= 500 ? TEXTE.httpFehler.server : TEXTE.httpFehler.allgemein;
  }
}
