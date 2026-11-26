import type { Eintrag } from './eintrag';
import { IndexedDbSpeicher } from './speicher';

describe('IndexedDbSpeicher', () => {
  it('speichert, sortiert nach Aufnahmezeit und löscht gezielt', async () => {
    const daten = new Map<string, Eintrag>();
    const datenbank = {
      getAll: vi.fn(async () => [...daten.values()]),
      put: vi.fn(async (_ablage: string, eintrag: Eintrag) => daten.set(eintrag.id, eintrag)),
      delete: vi.fn(async (_ablage: string, id: string) => daten.delete(id)),
    };
    const speicher = new IndexedDbSpeicher(Promise.resolve(datenbank));
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
