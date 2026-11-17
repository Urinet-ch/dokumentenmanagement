import { Component } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, TitleStrategy, provideRouter } from '@angular/router';
import { BrandingService } from './branding.service';
import { BrandingTitelStrategie } from './branding-titel.strategie';
import { TEXTE } from './texte.de-CH';

@Component({ template: '' })
class Leer {}

describe('BrandingTitelStrategie', () => {
  it('ersetzt den Standardtitel nach einer Navigation durch den Mandantennamen', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'suche', component: Leer, title: `Suche · ${TEXTE.appTitel}` }]),
        { provide: TitleStrategy, useExisting: BrandingTitelStrategie },
      ],
    });
    TestBed.inject(BrandingService);
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/branding?domain=localhost')
      .flush({ name: 'VHF/GSK Archiv', logoUrl: null, primaerfarbe: '#eeb111', sprache: 'de-CH' });
    await TestBed.inject(Router).navigateByUrl('/suche');
    TestBed.tick();
    expect(document.title).toBe('Suche · VHF/GSK Archiv');
  });
});
