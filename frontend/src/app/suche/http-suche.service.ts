import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DokumentartenService, DokumenteService, Dokumentart, Kontakt, KontakteService, Suchergebnis } from '../gemeinsam/api';
import { Suchanfrage, SuchePort } from './suche-port';

/** Suche über den generierten OpenAPI-Client (GET /api/v1/dokumente, /dokumentarten, /kontakte). */
@Injectable()
export class HttpSucheService extends SuchePort {
  private readonly dokumente = inject(DokumenteService);
  private readonly arten = inject(DokumentartenService);
  private readonly kontakteApi = inject(KontakteService);

  suchen(a: Suchanfrage): Observable<Suchergebnis> {
    return this.dokumente.apiV1DokumenteGet(
      a.art,
      a.belegnummer,
      a.betragBis,
      a.betragVon,
      a.datumBis,
      a.datumVon,
      a.geschaeftsjahr,
      a.groesse,
      undefined,
      a.partner,
      a.q,
      a.richtung,
      a.seite,
      a.sortierung,
      a.status,
      a.tags,
    );
  }

  dokumentarten(): Observable<Dokumentart[]> {
    return this.arten.apiV1DokumentartenGet();
  }

  kontakte(q: string): Observable<Kontakt[]> {
    return this.kontakteApi.apiV1KontakteGet(q);
  }
}
