import { Injectable, Signal, signal } from '@angular/core';

/**
 * Anzahl Dokumente im Eingangskorb für die Navigation (Req 8.6); `null` heisst «keine Angabe», dann zeigt die
 * Navigation keinen Zähler.
 */
export abstract class EingangskorbZaehler {
  abstract readonly anzahl: Signal<number | null>;
}

/**
 * Bis der Eingangskorb-Endpunkt besteht (Aufgaben 2.2 und 3.6), ruft die App nichts auf und zeigt keinen Zähler,
 * damit der Benutzer keine Fehlermeldung sieht. 3.6 ersetzt diese Implementierung.
 */
@Injectable()
export class OhneEingangskorbZaehler extends EingangskorbZaehler {
  readonly anzahl = signal<number | null>(null).asReadonly();
}
