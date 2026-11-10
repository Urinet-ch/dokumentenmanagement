import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';
import { AnmeldungService } from './anmeldung.service';

/** Bearer-Token nur an die eigene API (relative Pfade unter /api/), nie an fremde Adressen; 401 beendet die Sitzung. */
export const anmeldungInterceptor: HttpInterceptorFn = (anfrage, weiter) => {
  if (!anfrage.url.startsWith('/api/')) {
    return weiter(anfrage);
  }
  const anmeldung = inject(AnmeldungService);
  const token = anmeldung.accessToken();
  const mitToken = token ? anfrage.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : anfrage;
  return weiter(mitToken).pipe(
    tap({
      error: (fehler: unknown) => {
        if (fehler instanceof HttpErrorResponse && fehler.status === 401) {
          anmeldung.sitzungAbgelaufen();
        }
      },
    }),
  );
};
