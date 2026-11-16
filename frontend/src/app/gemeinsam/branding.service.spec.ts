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
    expect(document.title).toBe('VHF/GSK Archiv');
  });

  it('fällt bei API-Fehler und unsicherem Branding auf Standard zurück', () => {
    const anfrage = http.expectOne('/api/v1/branding?domain=localhost');
    anfrage.flush({
      name: '<script>alert(1)</script>',
      logoUrl: 'data:image/svg+xml;base64,PHN2Zz4=',
      primaerfarbe: 'url(javascript:alert(1))',
      sprache: 'en-US',
    });

    expect(service.branding()).toEqual(STANDARD_BRANDING);
  });

  it('verwendet bei Offline-Fehler das Standard-Branding', () => {
    http.expectOne('/api/v1/branding?domain=localhost').error(new ProgressEvent('network error'));
    expect(service.branding()).toEqual(STANDARD_BRANDING);
  });
});
