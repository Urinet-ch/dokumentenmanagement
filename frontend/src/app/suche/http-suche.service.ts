import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  DokumentartenService,
  DokumenteService,
  Dokumentart,
  Kontakt,
  KontakteService,
  Suchergebnis,
  WissensSuchergebnis,
} from '../gemeinsam/api';
import { Modus, Suchanfrage, SuchePort } from './suche-port';

/** Suche über den generierten OpenAPI-Client (GET /api/v1/dokumente, /dokumentarten, /kontakte). */
@Injectable()
export class HttpSucheService extends SuchePort {
  private readonly dokumente = inject(DokumenteService);
  private readonly arten = inject(DokumentartenService);
  private readonly kontakteApi = inject(KontakteService);
  private readonly http = inject(HttpClient);

  wissen(q: string, modus: Modus): Observable<WissensSuchergebnis> {
    return this.http.get<WissensSuchergebnis>('/api/v1/wissensseiten', {
      params: { q, modus, groesse: 30 },
    });
  }

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
      a.modus,
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
