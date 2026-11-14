import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Observable } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideApi } from '../gemeinsam/api';
import { HttpKontakteService } from './http-kontakte.service';

describe('HttpKontakteService (Req 17)', () => {
  let dienst: HttpKontakteService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApi(''),
        HttpKontakteService,
      ],
    });
    dienst = TestBed.inject(HttpKontakteService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('liest die Liste mit Suchbegriff, Filtern und Seite; fehlende Filter gehen nicht mit (Req 17.2)', () => {
    dienst
      .liste({
        q: 'garage',
        status: 'ALLE',
        quelle: 'CSV_IMPORT',
        nichtImExport: true,
        seite: 2,
        groesse: 25,
      })
      .subscribe();
    const a = http.expectOne((r) => r.url === '/api/v1/kontakte/liste');
    expect(a.request.method).toBe('GET');
    expect(
      Object.fromEntries(a.request.params.keys().map((k) => [k, a.request.params.get(k)])),
    ).toEqual({
      q: 'garage',
      status: 'ALLE',
      quelle: 'CSV_IMPORT',
      nichtImExport: 'true',
      seite: '2',
      groesse: '25',
    });
    a.flush({ gesamt: 0, seite: 2, groesse: 25, eintraege: [] });

    dienst.liste({ status: 'AKTIV', seite: 0, groesse: 25 }).subscribe();
    const b = http.expectOne((r) => r.url === '/api/v1/kontakte/liste');
    expect(b.request.params.keys().sort()).toEqual(['groesse', 'seite', 'status']);
    b.flush({ gesamt: 0, seite: 0, groesse: 25, eintraege: [] });
  });

  it('liest Detail und Verweise', () => {
    dienst.lesen('k1').subscribe();
    http.expectOne('/api/v1/kontakte/k1').flush({});
    dienst.verweise('k1').subscribe();
    http.expectOne('/api/v1/kontakte/k1/verweise').flush({});
  });

  it('sucht Zielkontakte unter den aktiven mit q', () => {
    dienst.suchen('ziel').subscribe();
    const a = http.expectOne((r) => r.url === '/api/v1/kontakte');
    expect(a.request.params.get('q')).toBe('ziel');
    a.flush([]);
  });

  it('holt die Dokumente mit Partner, nach Datum, mit Blättern und ohne q (Req 17.3)', () => {
    dienst.dokumente('k1', 1, 20).subscribe();
    const a = http.expectOne((r) => r.url === '/api/v1/dokumente');
    expect(
      Object.fromEntries(a.request.params.keys().map((k) => [k, a.request.params.get(k)])),
    ).toEqual({
      partner: 'k1',
      sortierung: 'DATUM',
      seite: '1',
      groesse: '20',
    });
    a.flush({ treffer: [], gesamt: 0, seite: 1, groesse: 20 });
  });

  it.each([
    [
      'ändern',
      (d: HttpKontakteService) => d.aendern('k1', 3, { ort: 'Luzern' }),
      'PATCH',
      '/api/v1/kontakte/k1',
      { ort: 'Luzern' },
    ],
    [
      'zusammenführen',
      (d: HttpKontakteService) => d.zusammenfuehren('k1', 3, 'k2', 'Dublette'),
      'POST',
      '/api/v1/kontakte/k1/zusammenfuehren',
      { ziel: 'k2', grund: 'Dublette' },
    ],
    [
      'inaktiv mit Grund',
      (d: HttpKontakteService) => d.inaktivMarkieren('k1', 3, 'Falsch'),
      'POST',
      '/api/v1/kontakte/k1/inaktiv',
      { grund: 'Falsch' },
    ],
    [
      'inaktiv ohne Grund',
      (d: HttpKontakteService) => d.inaktivMarkieren('k1', 3, null),
      'POST',
      '/api/v1/kontakte/k1/inaktiv',
      {},
    ],
    [
      'aktiv',
      (d: HttpKontakteService) => d.aktivieren('k1', 3),
      'POST',
      '/api/v1/kontakte/k1/aktiv',
      null,
    ],
  ])(
    'sendet beim Aktion «%s» If-Match mit der Version (Req 17.6)',
    (_name, aufruf, methode, pfad, body) => {
      (aufruf(dienst) as Observable<unknown>).subscribe();
      const a = http.expectOne(pfad);
      expect(a.request.method).toBe(methode);
      expect(a.request.headers.get('If-Match')).toBe('"3"');
      expect(a.request.body).toEqual(body);
      a.flush({});
    },
  );
});
