import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import {
  DokumentartenService,
  DokumenteService,
  Dokumentart,
  Kontakt,
  KontakteService,
  WissensbasisService,
  Suchergebnis,
  WissensSuchergebnis,
} from '../gemeinsam/api';
import { Modus, Suchanfrage, SuchePort } from './suche-port';

/** Höchstzahl Wissenstreffer je Suche (Req 9.1, Server-Obergrenze). */
const WISSEN_TREFFER_MAX = 30;

/** Suche über den generierten OpenAPI-Client (GET /api/v1/dokumente, /dokumentarten, /kontakte). */
@Injectable()
export class HttpSucheService extends SuchePort {
  private readonly dokumente = inject(DokumenteService);
  private readonly arten = inject(DokumentartenService);
  private readonly kontakteApi = inject(KontakteService);
  private readonly wissensbasis = inject(WissensbasisService);

  wissen(q: string, modus: Modus): Observable<WissensSuchergebnis> {
    return this.wissensbasis
      .apiV1WissensseitenGet(undefined, WISSEN_TREFFER_MAX, modus, undefined, undefined, q)
      .pipe(map((antwort) => antwort as WissensSuchergebnis));
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
