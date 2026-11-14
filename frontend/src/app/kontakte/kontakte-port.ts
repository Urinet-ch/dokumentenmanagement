import { Observable } from 'rxjs';
import {
  Dokumentart,
  Kontakt,
  KontaktAngaben,
  KontaktDetail,
  KontaktListe,
  KontaktVerweise,
  KontaktZusammengefuehrt,
  Suchergebnis,
} from '../gemeinsam/api';

export type KontaktStatus = 'AKTIV' | 'INAKTIV' | 'ALLE';
export type KontaktQuelle = 'CSV_IMPORT' | 'MANUELL';

/** Parameter von `GET /kontakte/liste` (Req 17.1, 17.2); `q` nur gesetzt, wenn gültig. */
export interface ListenAnfrage {
  readonly q?: string;
  readonly status: KontaktStatus;
  readonly quelle?: KontaktQuelle;
  readonly nichtImExport?: boolean;
  readonly seite: number;
  readonly groesse: number;
}

/** Zugriff auf die Kontakte-Schnittstelle (Req 17); alle schreibenden Aufrufe tragen `If-Match` mit der Version. */
export abstract class KontaktePort {
  abstract liste(anfrage: ListenAnfrage): Observable<KontaktListe>;
  abstract lesen(id: string): Observable<KontaktDetail>;
  abstract verweise(id: string): Observable<KontaktVerweise>;
  /** Aktive Kontakte für die Zielwahl (`GET /kontakte?q=`, höchstens 20). */
  abstract suchen(q: string): Observable<Kontakt[]>;
  /** Die Dokumente des Kontakts, wie das Archiv sie dem Benutzer zeigt (Req 17.3, 2.3). */
  abstract dokumente(id: string, seite: number, groesse: number): Observable<Suchergebnis>;
  abstract dokumentarten(): Observable<Dokumentart[]>;
  abstract aendern(id: string, version: number, angaben: KontaktAngaben): Observable<Kontakt>;
  abstract zusammenfuehren(
    id: string,
    version: number,
    ziel: string,
    grund: string,
  ): Observable<KontaktZusammengefuehrt>;
  abstract inaktivMarkieren(id: string, version: number, grund: string | null): Observable<Kontakt>;
  abstract aktivieren(id: string, version: number): Observable<Kontakt>;
}
