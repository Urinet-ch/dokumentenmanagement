import { Injectable, Signal, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
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

/** Beim Wechsel der Seite höchstens so oft neu zählen. */
export const ZAEHLER_PAUSE_MS = 30_000;

/**
 * Zählt über `GET /api/v1/eingangskorb?groesse=1` (Feld `gesamt`), nur mit Recht A5; das Archiv zählt nur, was der
 * Benutzer sehen darf (Req 2.3, 2.24). Bei einem Fehler zeigt die Navigation keinen Zähler statt einer Fehlermeldung.
 * Neu gezählt wird nach der Anmeldung, nach Aktionen im Eingangskorb und beim Wechsel der Seite (gedrosselt), damit
 * auch fertig verarbeitete Uploads im Zähler erscheinen.
 */
@Injectable()
export class HttpEingangskorbZaehler extends EingangskorbZaehler {
  private readonly api = inject(DokumenteService);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly wert = signal<number | null>(null);
  readonly anzahl = this.wert.asReadonly();
  private zuletzt = Number.NEGATIVE_INFINITY;

  constructor() {
    super();
    effect(() => {
      const darf = this.anmeldung.darf('A5');
      this.anmeldung.benutzer();
      untracked(() => (darf ? this.auffrischen() : this.wert.set(null)));
    });
    inject(Router)
      .events.pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        if (Date.now() - this.zuletzt >= ZAEHLER_PAUSE_MS) {
          this.auffrischen();
        }
      });
  }

  override auffrischen(): void {
    if (!this.anmeldung.darf('A5')) {
      return;
    }
    this.zuletzt = Date.now();
    this.api.apiV1EingangskorbGet(1, 0).subscribe({
      next: (seite) => this.wert.set(seite.gesamt),
      error: () => this.wert.set(null),
    });
  }
}
