import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  DokumentartenService,
  DokumenteService,
  Dokument,
  DokumentAenderung,
  Dokumentart,
  DownloadAdresse,
  Kontakt,
  KontakteService,
} from '../gemeinsam/api';
import { DokumentPort, Variante } from './dokument-port';

/** Dokument und Download-Adresse über den generierten OpenAPI-Client; die Adresse kommt als JSON (Accept). */
@Injectable()
export class HttpDokumentService extends DokumentPort {
  private readonly dokumente = inject(DokumenteService);
  private readonly arten = inject(DokumentartenService);
  private readonly kontakteApi = inject(KontakteService);

  lesen(id: string): Observable<Dokument> {
    return this.dokumente.apiV1DokumenteIdGet(id);
  }

  dokumentarten(): Observable<Dokumentart[]> {
    return this.arten.apiV1DokumentartenGet();
  }

  adresse(id: string, variante: Variante): Observable<DownloadAdresse> {
    return this.dokumente.apiV1DokumenteIdDateiGet(id, variante === 'original' ? 1 : undefined, variante, 'body', false, {
      httpHeaderAccept: 'application/json',
    });
  }

  vorschau(id: string, seite: number): Observable<Blob> {
    return this.dokumente.apiV1DokumenteIdVorschauGet(id, seite);
  }

  aendern(id: string, version: number, aenderung: DokumentAenderung): Observable<Dokument> {
    return this.dokumente.apiV1DokumenteIdPatch(id, `"${version}"`, aenderung);
  }

  kontakte(q: string): Observable<Kontakt[]> {
    return this.kontakteApi.apiV1KontakteGet(q);
  }
}
