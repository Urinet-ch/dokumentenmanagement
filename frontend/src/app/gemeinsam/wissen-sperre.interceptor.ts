import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injector, inject } from '@angular/core';
import { tap } from 'rxjs';
import { MandantFunktionen, WISSEN_NICHT_FREIGESCHALTET } from './mandant-funktionen';

/** Fehlercode der Problem Details, auch wenn der Körper als Text kommt (z. B. beim Chat-Stream). */
export function fehlercodeAus(koerper: unknown): string | null {
  let wert = koerper;
  if (typeof wert === 'string') {
    try {
      wert = JSON.parse(wert);
    } catch {
      return null;
    }
  }
  const code = typeof wert === 'object' && wert !== null ? (wert as { fehlercode?: unknown }).fehlercode : null;
  return typeof code === 'string' ? code : null;
}

/**
 * Meldet das Archiv 403 `WISSEN_NICHT_FREIGESCHALTET` (Spec 04, Req 22.6), wurde Wissen während der Sitzung
 * abgeschaltet: Die App blendet den Bereich sofort aus und lädt die Funktionen neu (Req 22.5). Der Fehler geht
 * unverändert an den Aufrufer weiter. `MandantFunktionen` (braucht den Router) wird erst im Fehlerfall geholt: Anfragen
 * aus Diensten, die der Router beim Aufbau erzeugt (Branding über die Titelstrategie), ergäben sonst einen Zirkel.
 */
export const wissenSperreInterceptor: HttpInterceptorFn = (anfrage, weiter) => {
  const injector = inject(Injector);
  return weiter(anfrage).pipe(
    tap({
      error: (fehler: unknown) => {
        if (fehler instanceof HttpErrorResponse && fehler.status === 403
            && fehlercodeAus(fehler.error) === WISSEN_NICHT_FREIGESCHALTET) {
          injector.get(MandantFunktionen).gesperrt();
        }
      },
    }),
  );
};
