import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, map, shareReplay } from 'rxjs';
import { KonfigurationService } from '../gemeinsam/api';

export interface KlassifikationWertFeld {
  code: string;
  name: string;
  aktiv: boolean;
}

export interface KlassifikationFeld {
  code: string;
  name: string;
  aktiv: boolean;
  mehrfach: boolean;
  pflicht: boolean;
  standardWert: string | null;
  werte: readonly KlassifikationWertFeld[];
}

export interface DokumentKlassifikationen {
  alle: readonly Omit<KlassifikationFeld, 'pflicht' | 'standardWert'>[];
  art: readonly KlassifikationFeld[];
}

export type KlassifikationenLadezustand =
  { status: 'laedt' } | { status: 'fehler' } | { status: 'ok'; daten: DokumentKlassifikationen };

export const LEERE_DOKUMENT_KLASSIFIKATIONEN: DokumentKlassifikationen = { alle: [], art: [] };

@Injectable({ providedIn: 'root' })
export class DokumentKlassifikationenService {
  private readonly api = inject(KonfigurationService);
  private alleCache: Observable<KlassifikationDefinition[]> | null = null;
  private readonly cache = new Map<string, Observable<DokumentKlassifikationen>>();

  aktualisieren(): void {
    this.alleCache = null;
    this.cache.clear();
  }

  fuerArt(code: string): Observable<DokumentKlassifikationen> {
    const vorhanden = this.cache.get(code);
    if (vorhanden) {
      return vorhanden;
    }
    const geladen = forkJoin({
      alle: this.alle(),
      zuordnungen: this.api
        .apiV1DokumentartenArtKlassifikationenGet(code)
        .pipe(map(zuordnungenAus)),
    }).pipe(
      map(({ alle, zuordnungen }): DokumentKlassifikationen => ({
        alle,
        art: zuordnungen.map((zuordnung) => {
          const definition = alle.find(
            (klassifikation) => klassifikation.code === zuordnung.klassifikation,
          );
          if (!definition) {
            throw new Error('Die Dokumentart verweist auf eine unbekannte Klassifikation.');
          }
          return {
            ...definition,
            pflicht: zuordnung.pflicht,
            standardWert: zuordnung.standardWert,
          };
        }),
      })),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.cache.set(code, geladen);
    return geladen;
  }

  private alle(): Observable<KlassifikationDefinition[]> {
    return (this.alleCache ??= this.api
      .apiV1KlassifikationenGet()
      .pipe(map(klassifikationenAus), shareReplay({ bufferSize: 1, refCount: false })));
  }
}

type KlassifikationDefinition = Omit<KlassifikationFeld, 'pflicht' | 'standardWert'>;

interface ArtZuordnung {
  klassifikation: string;
  pflicht: boolean;
  standardWert: string | null;
}

function klassifikationenAus(wert: unknown): KlassifikationDefinition[] {
  return array(wert, 'Klassifikationen').map((eintrag) => {
    const daten = objekt(eintrag, 'Klassifikation');
    const werte = array(daten['werte'], 'Klassifikationswerte').map((wertEintrag) => {
      const wertDaten = objekt(wertEintrag, 'Klassifikationswert');
      return {
        code: text(wertDaten['code'], 'Klassifikationscode'),
        name: text(wertDaten['name'], 'Klassifikationsname'),
        aktiv: boolean(wertDaten['aktiv'], 'Aktivstatus des Klassifikationswerts'),
      };
    });
    return {
      code: text(daten['code'], 'Klassifikationscode'),
      name: text(daten['name'], 'Klassifikationsname'),
      aktiv: boolean(daten['aktiv'], 'Aktivstatus der Klassifikation'),
      mehrfach: boolean(daten['mehrfach'], 'Mehrfachstatus der Klassifikation'),
      werte,
    };
  });
}

function zuordnungenAus(wert: unknown): ArtZuordnung[] {
  return array(wert, 'Klassifikationszuordnungen').map((eintrag) => {
    const daten = objekt(eintrag, 'Klassifikationszuordnung');
    const standardWert = daten['standardWert'];
    if (standardWert !== null && standardWert !== undefined && typeof standardWert !== 'string') {
      throw new Error('Der Standardwert einer Klassifikation ist ungültig.');
    }
    return {
      klassifikation: text(daten['klassifikation'], 'Klassifikationscode'),
      pflicht: boolean(daten['pflicht'], 'Pflichtstatus der Klassifikation'),
      standardWert: standardWert ?? null,
    };
  });
}

function array(wert: unknown, name: string): unknown[] {
  if (!Array.isArray(wert)) {
    throw new Error(`${name} konnten nicht gelesen werden.`);
  }
  return wert;
}

function objekt(wert: unknown, name: string): Record<string, unknown> {
  if (!wert || typeof wert !== 'object' || Array.isArray(wert)) {
    throw new Error(`${name} konnten nicht gelesen werden.`);
  }
  return wert as Record<string, unknown>;
}

function text(wert: unknown, name: string): string {
  if (typeof wert !== 'string' || wert.trim() === '') {
    throw new Error(`${name} konnte nicht gelesen werden.`);
  }
  return wert;
}

function boolean(wert: unknown, name: string): boolean {
  if (typeof wert !== 'boolean') {
    throw new Error(`${name} konnte nicht gelesen werden.`);
  }
  return wert;
}
