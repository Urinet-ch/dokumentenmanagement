import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { TEXTE } from './texte.de-CH';
import { httpFehlerMeldung, httpFehlerText } from './http-fehler';

describe('httpFehlerMeldung', () => {
  it.each([
    [0, TEXTE.httpFehler.verbindung],
    [401, TEXTE.anmeldung.abgelaufen],
    [403, TEXTE.httpFehler.keinZugriff],
    [404, TEXTE.httpFehler.nichtGefunden],
    [409, TEXTE.httpFehler.konflikt],
    [412, TEXTE.httpFehler.konflikt],
    [429, TEXTE.httpFehler.zuViele(null)],
    [503, TEXTE.httpFehler.server],
  ])('ordnet Status %s verständlich zu', (status, erwartet) => {
    expect(httpFehlerText(status)).toBe(erwartet);
  });

  it('liest Retry-After als Sekunden und HTTP-Datum', () => {
    expect(
      httpFehlerMeldung(
        new HttpErrorResponse({
          status: 429,
          headers: new HttpHeaders({ 'Retry-After': '42' }),
        }),
      ),
    ).toBe(TEXTE.httpFehler.zuViele(42));
    expect(
      httpFehlerMeldung(
        new HttpErrorResponse({
          status: 429,
          headers: new HttpHeaders({ 'Retry-After': 'Sat, 14 Nov 2026 08:10:00 GMT' }),
        }),
      ),
    ).toContain('Zu viele Anfragen.');
  });

  it('fällt bei ungültigen oder unbekannten Fehlern auf eine verständliche Meldung zurück', () => {
    expect(httpFehlerMeldung(new Error('intern'))).toBe(TEXTE.httpFehler.allgemein);
    expect(httpFehlerMeldung(new HttpErrorResponse({ status: 400 }))).toBe(TEXTE.httpFehler.allgemein);
  });
});
