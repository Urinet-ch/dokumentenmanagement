import { Observable } from 'rxjs';

// Schnittstelle nach design.md «Upload» (POST /api/v1/dokumente, Req 3); den generierten OpenAPI-Client schliesst
// Aufgabe 2.4 mit dem Endpunkt an.

export interface UploadFehler {
  status: number;
  fehlercode?: string;
  detail?: string;
}

export interface UploadErgebnis {
  datei: string;
  status: string;
  id?: string;
  vorhandenId?: string;
  meldung?: string;
  fehler?: UploadFehler;
}

export type UploadEreignis =
  | { typ: 'fortschritt'; prozent: number }
  | { typ: 'fertig'; ergebnisse: UploadErgebnis[] }
  | { typ: 'fehler'; fehler: UploadFehler };

export abstract class UploadPort {
  /** Sendet alle Dateien in einem Vorgang; `zusammenfassen` bildet daraus ein Dokument in der gegebenen Reihenfolge. */
  abstract hochladen(dateien: readonly File[], zusammenfassen: boolean): Observable<UploadEreignis>;
}
