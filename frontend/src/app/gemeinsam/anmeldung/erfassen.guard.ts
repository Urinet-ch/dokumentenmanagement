import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { ONLINE } from '../online';
import { AnmeldungService } from './anmeldung.service';

/**
 * «Erfassen» braucht Recht A1. Ohne Anmeldung ist die Seite nur offline zugänglich: Die Tokens liegen nur im
 * Arbeitsspeicher, nach einem Neustart ohne Verbindung landen Aufnahmen in der Warteschlange und werden erst nach der
 * Anmeldung hochgeladen (tasks.md 3.7, entschieden von Fabian am 11.11.2026). Online führt der Weg wie bei
 * `rechtGuard` über die Anmeldung.
 */
export const erfassenGuard: CanActivateFn = (_route, zustand) => {
  const anmeldung = inject(AnmeldungService);
  const router = inject(Router);
  if (anmeldung.angemeldet()) {
    return anmeldung.darf('A1') || router.createUrlTree(['/']);
  }
  return !inject(ONLINE)() || router.createUrlTree(['/'], { queryParams: { ziel: zustand.url } });
};
