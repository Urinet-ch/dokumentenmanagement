import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners, isDevMode } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import { routes } from './app.routes';
import { provideApi } from './gemeinsam/api';
import { anmeldungInterceptor } from './gemeinsam/anmeldung/anmeldung.interceptor';
import { EingangskorbZaehler, OhneEingangskorbZaehler } from './gemeinsam/eingangskorb-zaehler';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([anmeldungInterceptor])),
    // Relative Pfade, damit der Interceptor das Token nur an die eigene API hängt
    provideApi(''),
    { provide: EingangskorbZaehler, useClass: OhneEingangskorbZaehler },
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
