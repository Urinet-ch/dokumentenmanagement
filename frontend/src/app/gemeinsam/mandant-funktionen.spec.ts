import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MandantFunktionen, funktionenSchluessel } from './mandant-funktionen';

describe('MandantFunktionen (Spec 04, Req 22.4, 22.5)', () => {
  let funktionen: MandantFunktionen;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    funktionen = TestBed.inject(MandantFunktionen);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('gilt bis zum Laden als nicht verfügbar und übernimmt dann wissenVerfuegbar', async () => {
    expect(funktionen.wissenVerfuegbar()).toBe(false);
    funktionen.fuer('urinet|u1');
    expect(funktionen.stand()).toBe('laedt');
    expect(funktionen.wissenVerfuegbar()).toBe(false);
    const bereit = funktionen.bereit();
    http.expectOne('/api/v1/mandant/funktionen').flush({ finanzVerfuegbar: true, wissenVerfuegbar: true });
    await bereit;
    expect(funktionen.stand()).toBe('geladen');
    expect(funktionen.wissenVerfuegbar()).toBe(true);
  });

  it('bleibt ausgeschaltet, wenn das Archiv nein meldet', () => {
    funktionen.fuer('vhf-gsk|u2');
    http.expectOne('/api/v1/mandant/funktionen').flush({ finanzVerfuegbar: false, wissenVerfuegbar: false });
    expect(funktionen.wissenVerfuegbar()).toBe(false);
  });

  it('blendet bei einem Fehler der Abfrage aus (fail closed)', async () => {
    funktionen.fuer('urinet|u1');
    const bereit = funktionen.bereit();
    http.expectOne('/api/v1/mandant/funktionen').flush(null, { status: 503, statusText: 'Service Unavailable' });
    await bereit;
    expect(funktionen.stand()).toBe('fehler');
    expect(funktionen.wissenVerfuegbar()).toBe(false);
  });

  it('lädt je Anmeldung nur einmal und nach einem Wechsel neu', () => {
    funktionen.fuer('urinet|u1');
    http.expectOne('/api/v1/mandant/funktionen').flush({ finanzVerfuegbar: true, wissenVerfuegbar: true });
    funktionen.fuer('urinet|u1');
    http.expectNone('/api/v1/mandant/funktionen');
    funktionen.fuer('vhf-gsk|u2');
    expect(funktionen.wissenVerfuegbar()).toBe(false);
    http.expectOne('/api/v1/mandant/funktionen').flush({ finanzVerfuegbar: false, wissenVerfuegbar: false });
    expect(funktionen.wissenVerfuegbar()).toBe(false);
  });

  it('vergisst beim Abmelden den Stand und bricht eine laufende Abfrage ab', async () => {
    funktionen.fuer('urinet|u1');
    const anfrage = http.expectOne('/api/v1/mandant/funktionen');
    const bereit = funktionen.bereit();
    funktionen.zuruecksetzen();
    await bereit;
    expect(anfrage.cancelled).toBe(true);
    expect(funktionen.stand()).toBe('offen');
    expect(funktionen.wissenVerfuegbar()).toBe(false);
  });

  it('bildet den Schlüssel nur mit Mandant', () => {
    expect(funktionenSchluessel({ id: 'u1', mandant: 'urinet' })).toBe('urinet|u1');
    expect(funktionenSchluessel({ id: 'u1', mandant: null })).toBeNull();
    expect(funktionenSchluessel(null)).toBeNull();
  });
});
