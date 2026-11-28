import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AnmeldungService } from './anmeldung.service';

/** Nur mit Anmeldung, ohne Recht der Rechtematrix (Spec 06, Req 11.11); sonst zur Anmeldung wie bei `rechtGuard`. */
export const anmeldungGuard: CanActivateFn = (_route, zustand) =>
  inject(AnmeldungService).angemeldet() || inject(Router).createUrlTree(['/'], { queryParams: { ziel: zustand.url } });
