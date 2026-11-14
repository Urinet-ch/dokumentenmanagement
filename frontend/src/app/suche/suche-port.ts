import { Observable } from 'rxjs';
import { Dokumentart, Kontakt, Suchergebnis } from '../gemeinsam/api';

// Schnittstelle nach design.md «Suche» (GET /api/v1/dokumente, Req 14); HttpSucheService setzt sie mit dem generierten
// OpenAPI-Client um (Aufgabe 4.6).

export type Richtung = 'EINNAHME' | 'AUSGABE';
export type Status =
  | 'NEU'
  | 'VERARBEITUNG'
  | 'EINGANGSKORB'
  | 'ARCHIVIERUNG'
  | 'ARCHIVIERUNG_FEHLER'
  | 'ARCHIVIERT'
  | 'FEHLER'
  | 'VERWORFEN';
export type Sortierung = 'RELEVANZ' | 'DATUM';
export type Modus = 'hybrid' | 'volltext';

/** Bereits geprüfte Anfrage; leere Felder fehlen. Die Grenzen prüft das Archiv nochmals (400 ANFRAGE_UNGUELTIG). */
export interface Suchanfrage {
  q?: string;
  art?: string;
  partner?: string;
  datumVon?: string;
  datumBis?: string;
  betragVon?: string;
  betragBis?: string;
  richtung?: Richtung;
  status?: Status;
  tags?: string[];
  geschaeftsjahr?: number;
  belegnummer?: string;
  /** Modus `hybrid`: nur Begriff und Filter, höchstens 30 Treffer; `sortierung`, `seite` und `groesse` fehlen dann (sonst 400). */
  sortierung?: Sortierung;
  modus: Modus;
  seite?: number;
  groesse?: number;
}

export abstract class SuchePort {
  abstract suchen(anfrage: Suchanfrage): Observable<Suchergebnis>;
  abstract dokumentarten(): Observable<Dokumentart[]>;
  /** Nur mit Recht A6 (GET /kontakte verlangt es); andere Rollen filtern über den Partner eines Treffers. */
  abstract kontakte(q: string): Observable<Kontakt[]>;
}
