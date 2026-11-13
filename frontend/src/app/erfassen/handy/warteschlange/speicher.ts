import { Injectable } from '@angular/core';
import { openDB } from 'idb';
import { Eintrag } from './eintrag';

/** Speicher der Warteschlange; im Browser IndexedDB (Req 4.4), in Tests im Arbeitsspeicher. */
@Injectable({ providedIn: 'root', useFactory: () => new IndexedDbSpeicher() })
export abstract class WarteschlangeSpeicher {
  abstract alle(): Promise<Eintrag[]>;
  abstract speichern(eintrag: Eintrag): Promise<void>;
  abstract loeschen(id: string): Promise<void>;
}

const DATENBANK = 'urinet-archiv';
const ABLAGE = 'warteschlange';

export class IndexedDbSpeicher extends WarteschlangeSpeicher {
  private readonly db = openDB(DATENBANK, 1, {
    upgrade: (db) => db.createObjectStore(ABLAGE, { keyPath: 'id' }),
  });

  async alle(): Promise<Eintrag[]> {
    const eintraege = (await (await this.db).getAll(ABLAGE)) as Eintrag[];
    return eintraege.sort((a, b) => a.aufgenommen.localeCompare(b.aufgenommen));
  }

  async speichern(eintrag: Eintrag): Promise<void> {
    await (await this.db).put(ABLAGE, eintrag);
  }

  async loeschen(id: string): Promise<void> {
    await (await this.db).delete(ABLAGE, id);
  }
}

export class SpeicherImArbeitsspeicher extends WarteschlangeSpeicher {
  readonly inhalt = new Map<string, Eintrag>();

  async alle(): Promise<Eintrag[]> {
    return [...this.inhalt.values()].sort((a, b) => a.aufgenommen.localeCompare(b.aufgenommen));
  }

  async speichern(eintrag: Eintrag): Promise<void> {
    this.inhalt.set(eintrag.id, eintrag);
  }

  async loeschen(id: string): Promise<void> {
    this.inhalt.delete(id);
  }
}
