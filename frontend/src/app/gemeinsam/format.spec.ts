import { betrag, datum, zeitpunkt } from './format';

describe('Anzeigeformate (product.md «Sprache und Ton»)', () => {
  it('zeigt Beträge mit Apostroph und zwei Stellen', () => {
    expect(betrag(1234.5, 'CHF')).toBe("CHF 1'234.50");
    expect(betrag(1234567.891, null)).toBe("CHF 1'234'567.89");
    expect(betrag(-0.5, 'EUR')).toBe('EUR -0.50');
    expect(betrag(null)).toBe('');
  });

  it('zeigt das Belegdatum ohne Zeitzonen-Verschiebung', () => {
    expect(datum('2026-11-08')).toBe('08.11.2026');
    expect(datum('unsinn')).toBe('');
  });

  it('zeigt Zeitpunkte in Europe/Zurich', () => {
    expect(zeitpunkt('2026-11-07T23:30:00Z')).toBe('08.11.2026 00:30');
    expect(zeitpunkt('2026-07-01T10:00:00Z')).toBe('01.07.2026 12:00');
    expect(zeitpunkt(null)).toBe('');
  });
});
