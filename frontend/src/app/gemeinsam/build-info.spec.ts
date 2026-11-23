import { BUILD_INFO, gebautAm } from './build-info';

describe('Build-Info', () => {
  it('fällt ohne Konstanten auf «Entwicklung» und «lokal» zurück', () => {
    expect(BUILD_INFO).toEqual({ version: 'Entwicklung', build: 'lokal', gebaut: null });
  });

  it('zeigt den Zeitpunkt in Zürcher Zeit', () => {
    expect(gebautAm('2026-11-23T10:02:00.000Z')).toBe('23.11.2026, 11:02');
    expect(gebautAm('2026-07-01T10:02:00.000Z')).toBe('01.07.2026, 12:02');
  });

  it('ignoriert fehlende oder ungültige Zeitpunkte', () => {
    expect(gebautAm(null)).toBeNull();
    expect(gebautAm('gestern')).toBeNull();
  });
});
