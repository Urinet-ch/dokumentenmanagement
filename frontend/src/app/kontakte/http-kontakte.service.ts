import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  DokumentartenService,
  DokumenteService,
  Dokumentart,
  Kontakt,
  KontaktAngaben,
  KontaktDetail,
  KontaktListe,
  KontakteService,
  KontaktVerweise,
  KontaktZusammengefuehrt,
  Suchergebnis,
} from '../gemeinsam/api';
import { KontaktePort, ListenAnfrage } from './kontakte-port';

/** Kontakte über den generierten OpenAPI-Client; `If-Match` ist `"<version>"` (RFC 9110, starkes ETag). */
@Injectable()
export class HttpKontakteService extends KontaktePort {
  private readonly kontakte = inject(KontakteService);
  private readonly dokumenteApi = inject(DokumenteService);
  private readonly artenApi = inject(DokumentartenService);

  liste(a: ListenAnfrage): Observable<KontaktListe> {
    return this.kontakte.apiV1KontakteListeGet(
      a.groesse,
      a.nichtImExport,
      a.q,
      a.quelle,
      a.seite,
      a.status,
    );
  }

  lesen(id: string): Observable<KontaktDetail> {
    return this.kontakte.apiV1KontakteKontaktIdGet(id);
  }

  verweise(id: string): Observable<KontaktVerweise> {
    return this.kontakte.apiV1KontakteKontaktIdVerweiseGet(id);
  }

  suchen(q: string): Observable<Kontakt[]> {
    return this.kontakte.apiV1KontakteGet(q);
  }

  /** Ohne `q`, nach Datum sortiert; Sichtbarkeit und Gesamtzahl bestimmt allein das Archiv (Req 17.3). */
  dokumente(id: string, seite: number, groesse: number): Observable<Suchergebnis> {
    return this.dokumenteApi.apiV1DokumenteGet(
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      groesse,
      undefined,
      id,
      undefined,
      undefined,
      seite,
      'DATUM',
    );
  }

  dokumentarten(): Observable<Dokumentart[]> {
    return this.artenApi.apiV1DokumentartenGet();
  }

  aendern(id: string, version: number, angaben: KontaktAngaben): Observable<Kontakt> {
    return this.kontakte.apiV1KontakteKontaktIdPatch(id, etag(version), angaben);
  }

  zusammenfuehren(
    id: string,
    version: number,
    ziel: string,
    grund: string,
  ): Observable<KontaktZusammengefuehrt> {
    return this.kontakte.apiV1KontakteKontaktIdZusammenfuehrenPost(id, etag(version), {
      ziel,
      grund,
    });
  }

  inaktivMarkieren(id: string, version: number, grund: string | null): Observable<Kontakt> {
    return this.kontakte.apiV1KontakteKontaktIdInaktivPost(
      id,
      etag(version),
      grund ? { grund } : {},
    );
  }

  aktivieren(id: string, version: number): Observable<Kontakt> {
    return this.kontakte.apiV1KontakteKontaktIdAktivPost(id, etag(version));
  }
}

function etag(version: number): string {
  return `"${version}"`;
}
