import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AnmeldungService } from '../../gemeinsam/anmeldung/anmeldung.service';

export const verwaltungGuard: CanActivateFn = (_route, zustand) => {
  const anmeldung = inject(AnmeldungService);
  const router = inject(Router);
  if (!anmeldung.angemeldet()) {
    return router.createUrlTree(['/'], { queryParams: { ziel: zustand.url } });
  }
  return anmeldung.darf('A11') || router.createUrlTree(['/nicht-gefunden']);
};
