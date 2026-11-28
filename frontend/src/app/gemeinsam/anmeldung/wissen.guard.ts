import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { MandantFunktionen, funktionenSchluessel } from '../mandant-funktionen';
import { AnmeldungService } from './anmeldung.service';
import { Aktion } from './rechte';

/**
 * Wächter der Wissensrouten (Spec 04, Req 22.5): Er wartet, bis die Funktionen des Mandanten geladen sind. Ist Wissen
 * nicht freigeschaltet oder scheitert die Abfrage, zeigt ein Direktaufruf «Seite nicht gefunden»; sonst gilt das Recht
 * der Aktion wie bei {@link rechtGuard}. Ohne Anmeldung zur Startseite, das Ziel bleibt für nach der Anmeldung.
 */
export function wissenGuard(aktion: Aktion, ohneRecht = '/'): CanActivateFn {
  return async (_route, zustand) => {
    const anmeldung = inject(AnmeldungService);
    const funktionen = inject(MandantFunktionen);
    const router = inject(Router);
    if (!anmeldung.angemeldet()) {
      return router.createUrlTree(['/'], { queryParams: { ziel: zustand.url } });
    }
    const schluessel = funktionenSchluessel(anmeldung.benutzer());
    if (schluessel) funktionen.fuer(schluessel);
    await funktionen.bereit();
    if (!funktionen.wissenVerfuegbar()) {
      return router.createUrlTree(['/nicht-gefunden']);
    }
    return anmeldung.darf(aktion) || router.createUrlTree([ohneRecht]);
  };
}
