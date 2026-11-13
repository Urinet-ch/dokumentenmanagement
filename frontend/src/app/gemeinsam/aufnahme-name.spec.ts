import { aufnahmeName } from './aufnahme-name';

describe('aufnahmeName (Req 4.9)', () => {
  it('liefert genau «Aufnahme TT.MM.JJJJ HH:MM» in Europe/Zurich', () => {
    expect(aufnahmeName(new Date('2026-11-13T07:15:00Z'))).toBe('Aufnahme 13.11.2026 08:15');
  });

  it('folgt der Zeitumstellung am 29.03. und 25.10.2026', () => {
    expect(aufnahmeName(new Date('2026-03-29T00:59:00Z'))).toBe('Aufnahme 29.03.2026 01:59');
    expect(aufnahmeName(new Date('2026-03-29T01:00:00Z'))).toBe('Aufnahme 29.03.2026 03:00');
    expect(aufnahmeName(new Date('2026-10-25T00:30:00Z'))).toBe('Aufnahme 25.10.2026 02:30');
    expect(aufnahmeName(new Date('2026-10-25T01:30:00Z'))).toBe('Aufnahme 25.10.2026 02:30');
    expect(aufnahmeName(new Date('2026-10-25T02:00:00Z'))).toBe('Aufnahme 25.10.2026 03:00');
  });

  it('zeigt Mitternacht als 00:00 und wechselt das Datum nach Ortszeit', () => {
    expect(aufnahmeName(new Date('2026-11-12T23:00:00Z'))).toBe('Aufnahme 13.11.2026 00:00');
    expect(aufnahmeName(new Date('2026-12-31T23:30:00Z'))).toBe('Aufnahme 01.01.2027 00:30');
  });

  it('hängt nicht von der Zeitzone des Geräts ab', () => {
    // Derselbe Zeitpunkt, als Ortszeit eines Geräts in New York (UTC−5) geschrieben
    expect(aufnahmeName(new Date('2026-11-13T02:15:00-05:00'))).toBe('Aufnahme 13.11.2026 08:15');
  });
});
