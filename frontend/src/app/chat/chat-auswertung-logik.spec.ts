import { zeitraumFehler } from './chat-auswertung-logik';
import { TEXTE } from '../gemeinsam/texte.de-CH';

describe('Zeitraum der Chat-Auswertung', () => {
  const heute = new Date('2026-11-24T12:00:00');

  it('akzeptiert einen Zeitraum bis zu 12 Monaten, aber keine ungültigen oder zukünftigen Daten', () => {
    expect(zeitraumFehler('2025-11-24', '2026-11-24', heute)).toBeNull();
    expect(zeitraumFehler('2025-11-23', '2026-11-24', heute)).toBe(TEXTE.chat.auswertung.zeitraumZuLang);
    expect(zeitraumFehler('2026-02-30', '2026-03-01', heute)).toBe(TEXTE.chat.auswertung.zeitraumUngueltig);
    expect(zeitraumFehler('2026-11-24', '2026-11-25', heute)).toBe(TEXTE.chat.auswertung.zukunft);
  });

  it('gleicht 12 Monate bei einem Schalttag wie LocalDate.minusMonths ab', () => {
    const schalttag = new Date('2024-02-29T12:00:00');
    expect(zeitraumFehler('2023-02-28', '2024-02-29', schalttag)).toBeNull();
    expect(zeitraumFehler('2023-02-27', '2024-02-29', schalttag)).toBe(TEXTE.chat.auswertung.zeitraumZuLang);
  });
});
