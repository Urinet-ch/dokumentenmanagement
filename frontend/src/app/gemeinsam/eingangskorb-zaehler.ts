import { Injectable, Signal, effect, inject, signal, untracked } from '@angular/core';
import { DokumenteService } from './api';
import { AnmeldungService } from './anmeldung/anmeldung.service';

/**
 * Anzahl Dokumente im Eingangskorb für die Navigation (Req 8.6); `null` heisst «keine Angabe», dann zeigt die
 * Navigation keinen Zähler.
 */
export abstract class EingangskorbZaehler {
  abstract readonly anzahl: Signal<number | null>;

  /** Nach einer Aktion im Eingangskorb neu zählen. */
  auffrischen(): void {}
}

/**
 * Zählt über `GET /api/v1/eingangskorb?groesse=1` (Feld `gesamt`), nur mit Recht A5; das Archiv zählt nur, was der
 * Benutzer sehen darf (Req 2.3, 2.24). Bei einem Fehler zeigt die Navigation keinen Zähler statt einer Fehlermeldung.
 */
@Injectable()
export class HttpEingangskorbZaehler extends EingangskorbZaehler {
  private readonly api = inject(DokumenteService);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly wert = signal<number | null>(null);
  readonly anzahl = this.wert.asReadonly();

  constructor() {
    super();
    effect(() => {
      const darf = this.anmeldung.darf('A5');
      this.anmeldung.benutzer();
      untracked(() => (darf ? this.auffrischen() : this.wert.set(null)));
    });
  }

  override auffrischen(): void {
    if (!this.anmeldung.darf('A5')) {
      return;
    }
    this.api.apiV1EingangskorbGet(1, 0).subscribe({
      next: (seite) => this.wert.set(seite.gesamt),
      error: () => this.wert.set(null),
    });
  }
}
