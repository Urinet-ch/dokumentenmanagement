import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { BrandingService, STANDARD_BRANDING } from './branding.service';

describe('BrandingService', () => {
  let service: BrandingService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(BrandingService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lädt Branding über den Hostnamen und setzt nur geprüfte CSS-Werte', () => {
    const domain = TestBed.inject(DOCUMENT).defaultView!.location.hostname;
    http
      .expectOne((request) => request.url === '/api/v1/branding' && request.params.get('domain') === domain)
      .flush({
        name: 'VHF/GSK Archiv',
        logoUrl: '/logos/verband.webp',
        primaerfarbe: '#eeb111',
        sprache: 'de-CH',
      });

    expect(service.branding()).toEqual({
      name: 'VHF/GSK Archiv',
      logoUrl: '/logos/verband.webp',
      primaerfarbe: '#eeb111',
      sprache: 'de-CH',
    });
    expect(document.documentElement.style.getPropertyValue('--brand-primary')).toBe('#eeb111');
    expect(document.documentElement.style.getPropertyValue('--brand-text')).toBe('#05070c');
  });

  it('fällt bei unsicherem Branding auf Standard zurück', () => {
    const anfrage = http.expectOne('/api/v1/branding?domain=localhost');
    anfrage.flush({
      name: '<script>alert(1)</script>',
      logoUrl: 'data:image/svg+xml;base64,PHN2Zz4=',
      primaerfarbe: 'url(javascript:alert(1))',
      sprache: 'en-US',
    });

    expect(service.branding()).toEqual(STANDARD_BRANDING);
  });

  it('verwendet bei HTTP-500 das Standard-Branding', () => {
    http.expectOne('/api/v1/branding?domain=localhost').flush(null, { status: 500, statusText: 'Fehler' });
    expect(service.branding()).toEqual(STANDARD_BRANDING);
    expect(document.documentElement.style.getPropertyValue('--brand-primary')).toBe('#eeb111');
    expect(document.documentElement.style.getPropertyValue('--brand-text')).toBe('#05070c');
  });

  it('verwendet bei Offline-Fehler das Standard-Branding', () => {
    http.expectOne('/api/v1/branding?domain=localhost').error(new ProgressEvent('network error'));
    expect(service.branding()).toEqual(STANDARD_BRANDING);
  });

  it.each(['https://fremd.example/l.png', '//fremd.example/l.png', '/logos/l.png?x=1', '/logos/l.svg'])(
    'verwirft das Logo %s',
    (logoUrl) => {
      http
        .expectOne('/api/v1/branding?domain=localhost')
        .flush({ name: 'VHF/GSK', logoUrl, primaerfarbe: '#eeb111', sprache: 'de-CH' });
      expect(service.branding().logoUrl).toBeNull();
    },
  );

  it('behält Name bei ungültiger Farbe und nimmt die Standardfarbe', () => {
    http
      .expectOne('/api/v1/branding?domain=localhost')
      .flush({ name: 'VHF/GSK', logoUrl: null, primaerfarbe: 'rot', sprache: 'de-CH' });
    expect(service.branding().name).toBe('VHF/GSK');
    expect(service.branding().primaerfarbe).toBe('#eeb111');
  });

  it.each(['#707070', '#747474', '#777777', '#9a6a00'])(
    'nimmt bei %s ohne AA-Kontrast die Standardfarbe',
    (farbe) => {
      http
        .expectOne('/api/v1/branding?domain=localhost')
        .flush({ name: 'VHF/GSK', logoUrl: null, primaerfarbe: farbe, sprache: 'de-CH' });
      expect(service.branding().primaerfarbe).toBe('#eeb111');
    },
  );

  it('wählt helle Textfarbe auf dunkler Primärfarbe', () => {
    http
      .expectOne('/api/v1/branding?domain=localhost')
      .flush({ name: 'VHF/GSK', logoUrl: null, primaerfarbe: '#1a2b5c', sprache: 'de-CH' });
    expect(document.documentElement.style.getPropertyValue('--brand-text')).toBe('#f2efe7');
  });
});
