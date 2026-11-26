import { Eintrag } from './eintrag';
import { SpeicherImArbeitsspeicher } from './speicher';

describe('SpeicherImArbeitsspeicher', () => {
  it('speichert, sortiert nach Aufnahmezeit und löscht gezielt', async () => {
    const speicher = new SpeicherImArbeitsspeicher();
    const eintrag = (id: string, aufgenommen: string): Eintrag =>
      ({ id, aufgenommen, seiten: [], fehlversuche: 0, zustand: 'wartet' });
    const spaeter = eintrag('b', '2026-01-02T00:00:00Z');
    const frueher = eintrag('a', '2026-01-01T00:00:00Z');
    await speicher.speichern(spaeter);
    await speicher.speichern(frueher);
    expect(await speicher.alle()).toEqual([frueher, spaeter]);
    await speicher.loeschen('a');
    expect(await speicher.alle()).toEqual([spaeter]);
  });
});
