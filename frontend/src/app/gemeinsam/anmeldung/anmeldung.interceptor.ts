import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap, tap } from 'rxjs';
import { AnmeldungService } from './anmeldung.service';

/**
 * Bearer-Token nur an die eigene API (relative Pfade unter /api/), nie an fremde Adressen; ein bald ablaufendes Token
 * wird vorher erneuert; 401 beendet die Sitzung.
 */
export const anmeldungInterceptor: HttpInterceptorFn = (anfrage, weiter) => {
  if (!anfrage.url.startsWith('/api/')) {
    return weiter(anfrage);
  }
  const anmeldung = inject(AnmeldungService);
  return from(anmeldung.gueltigesAccessToken()).pipe(
    switchMap((token) =>
      weiter(token ? anfrage.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : anfrage),
    ),
    tap({
      error: (fehler: unknown) => {
        if (fehler instanceof HttpErrorResponse && fehler.status === 401) {
          anmeldung.sitzungAbgelaufen();
        }
      },
    }),
  );
};
