import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApi } from '../gemeinsam/api';
import { HttpDokumentService } from './http-dokument.service';

describe('HttpDokumentService (Req 14.6)', () => {
  let dienst: HttpDokumentService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideApi(''), HttpDokumentService],
    });
    dienst = TestBed.inject(HttpDokumentService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('fragt die PDF/A-Adresse als JSON an, damit das Archiv nicht umleitet', () => {
    dienst.adresse('d1', 'pdfa').subscribe();
    const anfrage = http.expectOne((r) => r.url === '/api/v1/dokumente/d1/datei');
    expect(anfrage.request.headers.get('Accept')).toBe('application/json');
    expect(anfrage.request.params.get('variante')).toBe('pdfa');
    expect(anfrage.request.params.has('nr')).toBe(false);
    anfrage.flush({ url: 'https://s3.example/x', gueltigBis: '2026-11-08T10:05:00Z' });
  });

  it('holt beim Original die erste Datei', () => {
    dienst.adresse('d1', 'original').subscribe();
    const anfrage = http.expectOne((r) => r.url === '/api/v1/dokumente/d1/datei');
    expect(anfrage.request.params.get('variante')).toBe('original');
    expect(anfrage.request.params.get('nr')).toBe('1');
    anfrage.flush({ url: 'https://s3.example/x', gueltigBis: '2026-11-08T10:05:00Z' });
  });

  it('ändert mit If-Match aus der Version (Req 10.1)', () => {
    dienst.aendern('d1', 3, { titel: 'Neu', grund: 'Tippfehler' }).subscribe();
    const anfrage = http.expectOne((r) => r.url === '/api/v1/dokumente/d1');
    expect(anfrage.request.method).toBe('PATCH');
    expect(anfrage.request.headers.get('If-Match')).toBe('"3"');
    expect(anfrage.request.body).toEqual({ titel: 'Neu', grund: 'Tippfehler' });
    anfrage.flush({});
  });

  it('holt das Vorschaubild der Seite als Blob', () => {
    dienst.vorschau('d1', 1).subscribe();
    const anfrage = http.expectOne((r) => r.url === '/api/v1/dokumente/d1/vorschau');
    expect(anfrage.request.params.get('seite')).toBe('1');
    expect(anfrage.request.responseType).toBe('blob');
    anfrage.flush(new Blob(['png']));
  });

  it('sucht Partner über die Kontakte', () => {
    dienst.kontakte('FC').subscribe();
    const anfrage = http.expectOne((r) => r.url === '/api/v1/kontakte');
    expect(anfrage.request.params.get('q')).toBe('FC');
    anfrage.flush([]);
  });
});
