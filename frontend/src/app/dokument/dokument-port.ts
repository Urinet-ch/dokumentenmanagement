import { Observable } from 'rxjs';
import { Dokument, Dokumentart, DownloadAdresse } from '../gemeinsam/api';

// Schnittstelle nach design.md (GET /api/v1/dokumente/{id} und /datei, Req 14.6); HttpDokumentService setzt sie mit
// dem generierten OpenAPI-Client um (Aufgabe 4.6).

export type Variante = 'pdfa' | 'original';

export abstract class DokumentPort {
  abstract lesen(id: string): Observable<Dokument>;
  abstract dokumentarten(): Observable<Dokumentart[]>;
  /** Vorsignierte Adresse (höchstens 5 Minuten gültig); jeder Abruf wird als HERUNTERLADEN protokolliert. */
  abstract adresse(id: string, variante: Variante): Observable<DownloadAdresse>;
}
