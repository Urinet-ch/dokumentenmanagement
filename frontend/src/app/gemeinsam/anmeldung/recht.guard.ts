import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AnmeldungService } from './anmeldung.service';
import { Aktion } from './rechte';

/** Blendet Seiten ohne Recht aus (Req 2.6); ohne Anmeldung zur Startseite, das Ziel bleibt für nach der Anmeldung. */
export function rechtGuard(aktion: Aktion): CanActivateFn {
  return (_route, zustand) => {
    const anmeldung = inject(AnmeldungService);
    const router = inject(Router);
    if (!anmeldung.angemeldet()) {
      return router.createUrlTree(['/'], { queryParams: { ziel: zustand.url } });
    }
    return anmeldung.darf(aktion) || router.createUrlTree(['/']);
  };
}
