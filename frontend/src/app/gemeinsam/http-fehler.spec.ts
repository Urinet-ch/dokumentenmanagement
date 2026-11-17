import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { TEXTE } from './texte.de-CH';
import { httpFehlerMeldung, httpFehlerText } from './http-fehler';
import { retryAfterSekunden } from './retry-after';

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

  it('liest Retry-After als Sekunden und zeigt die Wartezeit an', () => {
    expect(
      httpFehlerMeldung(
        new HttpErrorResponse({
          status: 429,
          headers: new HttpHeaders({ 'Retry-After': '42' }),
        }),
      ),
    ).toBe(TEXTE.httpFehler.zuViele(42));
  });

  it('liest HTTP-Daten deterministisch und begrenzt die Wartezeit', () => {
    const jetzt = Date.parse('Sat, 14 Nov 2026 08:09:18 GMT');
    expect(retryAfterSekunden('Sat, 14 Nov 2026 08:10:00 GMT', jetzt)).toBe(42);
    expect(retryAfterSekunden('99999', jetzt)).toBe(3600);
    expect(retryAfterSekunden('keine Zeit', jetzt)).toBeNull();
  });

  it('fällt bei ungültigen oder unbekannten Fehlern auf eine verständliche Meldung zurück', () => {
    expect(httpFehlerMeldung(new Error('intern'))).toBe(TEXTE.httpFehler.allgemein);
    expect(httpFehlerMeldung(new HttpErrorResponse({ status: 400 }))).toBe(TEXTE.httpFehler.allgemein);
  });
});
