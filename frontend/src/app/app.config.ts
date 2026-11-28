import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners, isDevMode } from '@angular/core';
import { provideRouter, TitleStrategy, withComponentInputBinding } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import { routes } from './app.routes';
import { provideApi } from './gemeinsam/api';
import { anmeldungInterceptor } from './gemeinsam/anmeldung/anmeldung.interceptor';
import { BrandingTitelStrategie } from './gemeinsam/branding-titel.strategie';
import { EingangskorbZaehler, HttpEingangskorbZaehler } from './gemeinsam/eingangskorb-zaehler';
import { provideMandantFunktionen } from './gemeinsam/mandant-funktionen';
import { wissenSperreInterceptor } from './gemeinsam/wissen-sperre.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    { provide: TitleStrategy, useExisting: BrandingTitelStrategie },
    provideHttpClient(withInterceptors([anmeldungInterceptor, wissenSperreInterceptor])),
    provideMandantFunktionen(),
    // Relative Pfade, damit der Interceptor das Token nur an die eigene API hängt
    provideApi(''),
    { provide: EingangskorbZaehler, useClass: HttpEingangskorbZaehler },
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
