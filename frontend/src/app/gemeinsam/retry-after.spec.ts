import { retryAfterSekunden } from './retry-after';

describe('retryAfterSekunden', () => {
  const jetzt = Date.parse('2026-11-17T10:00:00Z');

  it('liest Sekunden und begrenzt auf 3600', () => {
    expect(retryAfterSekunden('90')).toBe(90);
    expect(retryAfterSekunden('99999')).toBe(3600);
  });

  it('liest ein HTTP-Datum relativ zu jetzt, Vergangenheit ergibt 0', () => {
    expect(retryAfterSekunden('Tue, 17 Nov 2026 10:01:00 GMT', jetzt)).toBe(60);
    expect(retryAfterSekunden('Tue, 17 Nov 2026 09:00:00 GMT', jetzt)).toBe(0);
  });

  it('wertet unlesbare Werte als null, auch Zahlen mit Vorzeichen oder Dezimalstellen', () => {
    for (const wert of ['-1', '1.5', '+5', 'bald', '', '  ', null]) {
      expect(retryAfterSekunden(wert, jetzt)).toBeNull();
    }
  });
});
