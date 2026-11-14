import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApi } from '../gemeinsam/api';
import { HttpSucheService } from './http-suche.service';

describe('HttpSucheService (Req 14.2)', () => {
  let dienst: HttpSucheService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideApi(''), HttpSucheService],
    });
    dienst = TestBed.inject(HttpSucheService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sendet die Filter als Query-Parameter und Tags einzeln', () => {
    dienst
      
      .suchen({ modus: 'volltext', q: 'Miete', belegnummer: '2026-003', betragVon: '10.50', tags: ['a', 'b'], seite: 1, groesse: 20 })
      .subscribe();
    const anfrage = http.expectOne((r) => r.url === '/api/v1/dokumente');
    const p = anfrage.request.params;
    expect(p.get('q')).toBe('Miete');
    expect(p.get('belegnummer')).toBe('2026-003');
    expect(p.get('betragVon')).toBe('10.50');
    expect(p.getAll('tag')).toEqual(['a', 'b']);
    expect(p.get('seite')).toBe('1');
    expect(p.has('art')).toBe(false);
    expect(p.has('sortierung')).toBe(false);
    expect(p.get('modus')).toBe('volltext');
    anfrage.flush({ treffer: [], gesamt: 0, seite: 1, groesse: 20 });
  });

  it('reicht den Modus durch und lässt Seite und Grösse im Modus hybrid weg', () => {
    dienst.suchen({ modus: 'hybrid', q: 'Miete', art: 'X' }).subscribe();
    const anfrage = http.expectOne((r) => r.url === '/api/v1/dokumente');
    expect(anfrage.request.params.keys().sort()).toEqual(['art', 'modus', 'q']);
    expect(anfrage.request.params.get('modus')).toBe('hybrid');
    anfrage.flush({ treffer: [], gesamt: 0, seite: 0, groesse: 30, modus: 'hybrid' });
  });

  it('sendet im Volltext modus, seite und groesse', () => {
    dienst.suchen({ modus: 'volltext', seite: 2, groesse: 20 }).subscribe();
    const anfrage = http.expectOne((r) => r.url === '/api/v1/dokumente');
    expect(anfrage.request.params.keys().sort()).toEqual(['groesse', 'modus', 'seite']);
    expect(anfrage.request.params.get('modus')).toBe('volltext');
    anfrage.flush({ treffer: [], gesamt: 0, seite: 2, groesse: 20 });
  });

  it('sucht Kontakte mit dem Begriff', () => {
    dienst.kontakte('Schattdorf').subscribe();
    const anfrage = http.expectOne((r) => r.url === '/api/v1/kontakte');
    expect(anfrage.request.params.get('q')).toBe('Schattdorf');
    anfrage.flush([]);
  });
});
