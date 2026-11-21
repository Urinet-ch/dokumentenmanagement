import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Aktion, Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { WissenEntwurfZaehler } from './wissen-entwurf-zaehler';

describe('Wissensentwurfszähler', () => {
  let http: HttpTestingController;
  const benutzer = signal<{ id: string; rolle: Rolle; mandant: string }>({ id: '1', rolle: 'mitarbeiter', mandant: 'urinet' });
  beforeEach(() => {
    benutzer.set({ id: '1', rolle: 'mitarbeiter', mandant: 'urinet' });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: AnmeldungService, useValue: { benutzer, darf: (aktion: Aktion) => darf(benutzer().rolle, aktion) } }],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('zählt nur für A17 und verwirft laufende Ergebnisse beim Rollen- oder Mandantenwechsel', () => {
    const zaehler = TestBed.inject(WissenEntwurfZaehler);
    TestBed.tick();
    http.expectNone('/api/v1/wissensentwuerfe');
    benutzer.set({ id: '2', rolle: 'inhaber', mandant: 'urinet' });
    TestBed.tick();
    const alte = http.expectOne((r) => r.url === '/api/v1/wissensentwuerfe');
    expect(alte.request.params.get('status')).toBe('OFFEN');
    benutzer.set({ id: '3', rolle: 'geschaeftsstelle', mandant: 'vhf-gsk' });
    TestBed.tick();
    expect(alte.cancelled).toBe(true);
    http.expectOne((r) => r.url === '/api/v1/wissensentwuerfe').flush([{ entwurf: { id: 'e1' } }]);
    expect(zaehler.anzahl()).toBe(1);
    benutzer.set({ id: '4', rolle: 'treuhaender', mandant: 'vhf-gsk' });
    TestBed.tick();
    expect(zaehler.anzahl()).toBeNull();
    http.expectNone('/api/v1/wissensentwuerfe');
  });

  it('zeigt bei Ausfall keine falsche Null und lässt den Zähler erneut laden', () => {
    benutzer.set({ id: '2', rolle: 'inhaber', mandant: 'urinet' });
    const zaehler = TestBed.inject(WissenEntwurfZaehler);
    TestBed.tick();
    http.expectOne((r) => r.url === '/api/v1/wissensentwuerfe')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    expect(zaehler.anzahl()).toBeNull();
    expect(zaehler.fehler()).toBeTruthy();
    zaehler.auffrischen();
    http.expectOne((r) => r.url === '/api/v1/wissensentwuerfe').flush([]);
    expect(zaehler.anzahl()).toBe(0);
    expect(zaehler.fehler()).toBeNull();
  });
});
