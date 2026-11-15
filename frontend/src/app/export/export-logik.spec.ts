import { HttpErrorResponse } from '@angular/common/http';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { JAHR_MIN, fehlerText, jahrGueltig } from './export-logik';

describe('Export-Logik: ungültige Eingaben und untere Grenze', () => {
  it('lehnt Jahre unterhalb der Mindestgrenze und nicht-endliche Werte ab', () => {
    expect(jahrGueltig(JAHR_MIN - 1)).toBe(false);
    expect(jahrGueltig(Number.NaN)).toBe(false);
    expect(jahrGueltig(Number.POSITIVE_INFINITY)).toBe(false);
  });

  it.each(['Serverfehler als Text', null, { fehlercode: 400 }, { fehlercode: 'UNBEKANNTER_CODE' }])(
    'verwendet für Problem Details ohne bekannten Text die allgemeine Meldung',
    (error) => {
      expect(fehlerText(new HttpErrorResponse({ status: 400, error }))).toBe(
        TEXTE.export.fehlerAllgemein,
      );
    },
  );
});
