import { DOCUMENT, InjectionToken, Signal, inject, signal } from '@angular/core';

/** Ist das Gerät online (`navigator.onLine` mit den Ereignissen «online» und «offline»)? In Tests ersetzbar. */
export const ONLINE = new InjectionToken<Signal<boolean>>('ONLINE', {
  providedIn: 'root',
  factory: () => {
    const fenster = inject(DOCUMENT).defaultView;
    const online = signal(fenster?.navigator.onLine ?? true);
    const pruefen = () => online.set(fenster?.navigator.onLine ?? true);
    fenster?.addEventListener('online', pruefen);
    fenster?.addEventListener('offline', pruefen);
    return online.asReadonly();
  },
});
