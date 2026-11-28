import { anfrage, anzahlLesen, summe, titelFehler, titelLaenge } from './trennblaetter-logik';

describe('Trennblätter: Logik (Spec 09, Req 6.1, 6.5)', () => {
  it('prüft den Titel wie das Backend: leer, über 60 Zeichen, Steuer- und Formatzeichen', () => {
    expect(titelFehler('Versicherungen 2026')).toBeNull();
    expect(titelFehler('Ä'.repeat(60))).toBeNull();
    expect(titelFehler('A\u0308'.repeat(60))).toBeNull();
    expect(titelFehler('Ελληνικά – Кириллица € ẞ «Ä»')).toBeNull();
    expect(titelFehler('😀'.repeat(60))).toBe('schrift');
    expect(titelFehler('Team 👩\u200D💻')).toBe('schrift');
    expect(titelFehler('档案 2026')).toBe('schrift');
    expect(titelFehler('   ')).toBe('leer');
    expect(titelFehler('Ä'.repeat(61))).toBe('lang');
    for (const zeichen of ['\u0007', '\t', '\n', '\u202E', '\u2028', '\u2029', '\u200B', '\uFEFF', '\uD800']) {
      expect(titelFehler(`A${zeichen}B`), JSON.stringify(zeichen)).toBe('zeichen');
    }
  });

  it('zählt Zeichen nach NFC und ohne Leerraum aussen', () => {
    expect(titelLaenge('  Bu\u0308ro  ')).toBe(4);
    expect(titelLaenge('😀😀')).toBe(2);
  });

  it('liest Anzahlen von 0 bis 100; leer zählt als 0', () => {
    expect(anzahlLesen('')).toBe(0);
    expect(anzahlLesen('7')).toBe(7);
    expect(anzahlLesen('100')).toBe(100);
    expect(anzahlLesen('101')).toBeNull();
    expect(anzahlLesen('-1')).toBeNull();
    expect(anzahlLesen('1.5')).toBeNull();
    expect(summe([1, 2, 3])).toBe(6);
  });

  it('baut die Anfrage in der Reihenfolge der Seite und lässt Anzahl 0 weg', () => {
    expect(
      anfrage(
        2,
        [
          { code: 'BANK', anzahl: 0 },
          { code: 'QUITTUNG', anzahl: 3 },
        ],
        [
          { titel: 'Versicherungen 2026', anzahl: 1 },
          { titel: 'Leer', anzahl: 0 },
        ],
      ),
    ).toEqual({
      blaetter: [
        { typ: 'TRENN', anzahl: 2 },
        { typ: 'TRENN', art: 'QUITTUNG', anzahl: 3 },
        { typ: 'ORDNER', titel: 'Versicherungen 2026', anzahl: 1 },
      ],
    });
  });
});
