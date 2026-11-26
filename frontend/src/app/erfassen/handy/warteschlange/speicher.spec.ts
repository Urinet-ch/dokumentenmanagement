import { Eintrag } from './eintrag';

const daten = vi.hoisted(() => new Map<string, { id: string; aufgenommen: string }>());
vi.mock('idb', () => ({
  openDB: vi.fn(async () => ({
    getAll: async () => [...daten.values()],
    put: async (_ablage: string, eintrag: { id: string; aufgenommen: string }) => daten.set(eintrag.id, eintrag),
    delete: async (_ablage: string, id: string) => daten.delete(id),
  })),
}));

import { IndexedDbSpeicher } from './speicher';

describe('IndexedDbSpeicher', () => {
  beforeEach(() => daten.clear());

  it('speichert, sortiert nach Aufnahmezeit und löscht gezielt', async () => {
    const speicher = new IndexedDbSpeicher();
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
