import { Observable } from 'rxjs';
import { Dokument, DokumentAenderung, Dokumentart, DownloadAdresse, Kontakt } from '../gemeinsam/api';

// Schnittstelle nach design.md (GET/PATCH /api/v1/dokumente/{id}, /datei, /vorschau, GET /kontakte; Req 10.1, 14.6);
// HttpDokumentService setzt sie mit dem generierten OpenAPI-Client um (Aufgabe 4.6).

export type Variante = 'pdfa' | 'original';

export abstract class DokumentPort {
  abstract lesen(id: string): Observable<Dokument>;
  abstract dokumentarten(): Observable<Dokumentart[]>;
  /** Vorsignierte Adresse (höchstens 5 Minuten gültig); jeder Abruf wird als HERUNTERLADEN protokolliert. */
  abstract adresse(id: string, variante: Variante): Observable<DownloadAdresse>;
  /** PNG-Vorschau einer Seite (ab 1), mit Token geholt; wird als ANZEIGEN protokolliert. */
  abstract vorschau(id: string, seite: number): Observable<Blob>;
  /** `PATCH` mit `If-Match: "<version>"` (A6, Req 10.1); Antwort ist das geänderte Dokument. */
  abstract aendern(id: string, version: number, aenderung: DokumentAenderung): Observable<Dokument>;
  /** Partnersuche für das Formular (`GET /kontakte?q=`, verlangt A6). */
  abstract kontakte(q: string): Observable<Kontakt[]>;
}
