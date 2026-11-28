import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { Component, Injectable, inject, signal } from '@angular/core';
import {
  FUNKTIONEN_HOECHSTALTER_MS,
  MandantFunktionen,
  WISSENSROUTE,
  funktionenSchluessel,
  provideMandantFunktionen,
} from './mandant-funktionen';
import { wissenSperreInterceptor, fehlercodeAus } from './wissen-sperre.interceptor';
import { HttpClient, HttpInterceptorFn, withInterceptors } from '@angular/common/http';

@Component({ template: '' })
class Leer {}

describe('MandantFunktionen (Spec 04, Req 22.4, 22.5)', () => {
  let funktionen: MandantFunktionen;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([wissenSperreInterceptor])),
        provideHttpClientTesting(),
        provideMandantFunktionen(),
        provideRouter([
          { path: 'wissen', component: Leer, data: { [WISSENSROUTE]: true } },
          { path: 'chat', component: Leer },
          { path: 'nicht-gefunden', component: Leer },
        ]),
      ],
    });
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

  describe('Abschalten während der Sitzung (Req 22.2, 22.5)', () => {
    const ein = { finanzVerfuegbar: true, wissenVerfuegbar: true };
    const aus = { finanzVerfuegbar: true, wissenVerfuegbar: false };

    it('lädt bei einer Navigation erst nach dem Höchstalter neu und behält bis dahin den Stand', () => {
      funktionen.fuer('urinet|u1');
      http.expectOne('/api/v1/mandant/funktionen').flush(ein);
      funktionen.auffrischen(Date.now() + FUNKTIONEN_HOECHSTALTER_MS - 1000);
      http.expectNone('/api/v1/mandant/funktionen');
      funktionen.auffrischen(Date.now() + FUNKTIONEN_HOECHSTALTER_MS);
      const anfrage = http.expectOne('/api/v1/mandant/funktionen');
      expect(funktionen.wissenVerfuegbar()).toBe(true);
      anfrage.flush(aus);
      expect(funktionen.wissenVerfuegbar()).toBe(false);
    });

    it('wartet im Wächter auf eine laufende Auffrischung', async () => {
      funktionen.fuer('urinet|u1');
      http.expectOne('/api/v1/mandant/funktionen').flush(ein);
      funktionen.auffrischen(Date.now() + FUNKTIONEN_HOECHSTALTER_MS);
      let fertig = false;
      const bereit = funktionen.bereit().then(() => (fertig = true));
      await Promise.resolve();
      expect(fertig).toBe(false);
      http.expectOne('/api/v1/mandant/funktionen').flush(aus);
      await bereit;
      expect(funktionen.wissenVerfuegbar()).toBe(false);
    });

    it('blendet bei 403 WISSEN_NICHT_FREIGESCHALTET sofort aus, lädt neu und verlässt die Wissensroute', async () => {
      const router = TestBed.inject(Router);
      funktionen.fuer('urinet|u1');
      http.expectOne('/api/v1/mandant/funktionen').flush(ein);
      await router.navigateByUrl('/wissen');
      TestBed.inject(HttpClient).get('/api/v1/wissensseiten').subscribe({ error: () => undefined });
      http.expectOne('/api/v1/wissensseiten').flush({ fehlercode: 'WISSEN_NICHT_FREIGESCHALTET' },
        { status: 403, statusText: 'Forbidden' });
      expect(funktionen.wissenVerfuegbar()).toBe(false);
      http.expectOne('/api/v1/mandant/funktionen').flush(aus);
      await new Promise((r) => setTimeout(r));
      expect(router.url).toBe('/nicht-gefunden');
    });

    it('lässt andere 403 und andere Seiten unberührt', async () => {
      const router = TestBed.inject(Router);
      funktionen.fuer('urinet|u1');
      http.expectOne('/api/v1/mandant/funktionen').flush(ein);
      await router.navigateByUrl('/chat');
      TestBed.inject(HttpClient).get('/api/v1/wissensseiten').subscribe({ error: () => undefined });
      http.expectOne('/api/v1/wissensseiten').flush({ fehlercode: 'NICHT_ERLAUBT' }, { status: 403, statusText: 'Forbidden' });
      expect(funktionen.wissenVerfuegbar()).toBe(true);
      funktionen.gesperrt();
      http.expectOne('/api/v1/mandant/funktionen').flush(aus);
      await new Promise((r) => setTimeout(r));
      expect(router.url).toBe('/chat');
    });

    it('liest den Fehlercode auch aus einem Textkörper', () => {
      expect(fehlercodeAus('{"fehlercode":"WISSEN_NICHT_FREIGESCHALTET"}')).toBe('WISSEN_NICHT_FREIGESCHALTET');
      expect(fehlercodeAus('kein json')).toBeNull();
      expect(fehlercodeAus(null)).toBeNull();
    });
  });
});

/** Wie `BrandingTitelStrategie`: Der Router erzeugt die Titelstrategie, deren Dienst beim Aufbau eine Anfrage stellt. */
@Injectable()
class TitelMitAnfrage extends TitleStrategy {
  readonly antwort = signal<string | null>(null);
  constructor() {
    super();
    inject(HttpClient).get<string>('/api/v1/branding').subscribe({
      next: (wert) => this.antwort.set(wert),
      error: (fehler: unknown) => this.antwort.set(`Fehler: ${String(fehler)}`),
    });
  }
  override updateTitle(_: RouterStateSnapshot): void {}
}

describe('wissenSperreInterceptor beim Aufbau des Routers', () => {
  it('lässt Anfragen aus Diensten des Routers ohne Zirkel durch', () => {
    // wie `anmeldungInterceptor` über `AnmeldungService`: braucht die Funktionen schon bei jeder Anfrage
    const wieAnmeldung: HttpInterceptorFn = (anfrage, weiter) => {
      inject(MandantFunktionen);
      return weiter(anfrage);
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([wieAnmeldung, wissenSperreInterceptor])),
        provideHttpClientTesting(),
        provideMandantFunktionen(),
        provideRouter([]),
        { provide: TitleStrategy, useClass: TitelMitAnfrage },
      ],
    });
    TestBed.inject(Router);
    const titel = TestBed.inject(TitleStrategy) as TitelMitAnfrage;
    TestBed.inject(HttpTestingController).expectOne('/api/v1/branding').flush('Musterarchiv');
    expect(titel.antwort()).toBe('Musterarchiv');
  });
});
