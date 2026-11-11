import { HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, filter, map, of } from 'rxjs';
import { DokumenteService } from '../gemeinsam/api';
import { UploadEreignis, UploadErgebnis, UploadPort } from './upload-port';

export const UPLOAD_PFAD = '/api/v1/dokumente';

/**
 * Multipart-Upload an POST /api/v1/dokumente mit Fortschritt (design.md «Upload») über den generierten OpenAPI-Client;
 * der Dateiname reist im Multipart-Teil mit (File), «zusammenfassen» nur, wenn gewählt.
 */
@Injectable()
export class HttpUploadService extends UploadPort {
  private readonly dokumente = inject(DokumenteService);

  hochladen(dateien: readonly File[], zusammenfassen: boolean): Observable<UploadEreignis> {
    return this.dokumente
      .apiV1DokumentePost([...dateien], undefined, zusammenfassen ? true : undefined, undefined, 'events', true)
      .pipe(
        map((ereignis): UploadEreignis | null => {
          if (ereignis.type === HttpEventType.UploadProgress) {
            const prozent = ereignis.total ? Math.round((100 * ereignis.loaded) / ereignis.total) : 0;
            return { typ: 'fortschritt', prozent };
          }
          if (ereignis.type === HttpEventType.Response) {
            return { typ: 'fertig', ergebnisse: ereignis.body ?? [] };
          }
          return null;
        }),
        filter((ereignis): ereignis is UploadEreignis => ereignis !== null),
        catchError((fehler: unknown) => of(fehlerAlsEreignis(fehler))),
      );
  }
}

// Sind alle Dateien abgelehnt, kommt Problem Details mit der Erweiterung `ergebnisse` (design.md «Upload»)
function fehlerAlsEreignis(fehler: unknown): UploadEreignis {
  const antwort = fehler instanceof HttpErrorResponse ? fehler : null;
  const inhalt: unknown = antwort?.error;
  const problem = typeof inhalt === 'object' && inhalt !== null ? (inhalt as Record<string, unknown>) : {};
  if (Array.isArray(problem['ergebnisse'])) {
    return { typ: 'fertig', ergebnisse: problem['ergebnisse'] as UploadErgebnis[] };
  }
  return {
    typ: 'fehler',
    fehler: {
      status: antwort?.status ?? 0,
      fehlercode: typeof problem['fehlercode'] === 'string' ? problem['fehlercode'] : undefined,
      detail: typeof problem['detail'] === 'string' ? problem['detail'] : undefined,
    },
  };
}
