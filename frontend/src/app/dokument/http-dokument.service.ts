import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DokumentartenService, DokumenteService, Dokument, Dokumentart, DownloadAdresse } from '../gemeinsam/api';
import { DokumentPort, Variante } from './dokument-port';

/** Dokument und Download-Adresse über den generierten OpenAPI-Client; die Adresse kommt als JSON (Accept). */
@Injectable()
export class HttpDokumentService extends DokumentPort {
  private readonly dokumente = inject(DokumenteService);
  private readonly arten = inject(DokumentartenService);

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
}
