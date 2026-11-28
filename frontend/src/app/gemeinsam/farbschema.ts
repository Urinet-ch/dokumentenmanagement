import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';

export type Farbschema = 'system' | 'hell' | 'dunkel';

const SCHLUESSEL = 'archiv.farbschema';

/**
 * Hell oder dunkel: Ohne Wahl gilt das Farbschema des Systems. Die Wahl bleibt nur in diesem Browser
 * (localStorage, ohne Daten des Archivs) und setzt `data-farbschema` auf <html>, siehe styles.scss.
 */
@Injectable({ providedIn: 'root' })
export class FarbschemaService {
  private readonly dokument = inject(DOCUMENT);
  private readonly fenster = this.dokument.defaultView;
  private readonly systemDunkel = signal(
    this.fenster?.matchMedia?.('(prefers-color-scheme: dark)').matches ?? true,
  );
  readonly wahl = signal<Farbschema>(this.gespeichert());
  readonly dunkel = computed(() =>
    this.wahl() === 'system' ? this.systemDunkel() : this.wahl() === 'dunkel',
  );

  constructor() {
    this.fenster
      ?.matchMedia?.('(prefers-color-scheme: dark)')
      .addEventListener?.('change', (e) => this.systemDunkel.set(e.matches));
    this.anwenden(this.wahl());
  }

  /** Wechselt zum jeweils anderen Farbschema und merkt sich die Wahl. */
  umschalten(): void {
    this.waehlen(this.dunkel() ? 'hell' : 'dunkel');
  }

  /**
   * Wendet die Wahl sofort an und merkt sie sich; «system» entfernt die gemerkte Wahl (Spec 06, Req 11.6, 11.9).
   * Liefert `false`, wenn der Browser nicht speichert (die Wahl gilt dann nur bis zum Neuladen).
   */
  waehlen(wahl: Farbschema): boolean {
    this.wahl.set(wahl);
    this.anwenden(wahl);
    try {
      if (wahl === 'system') {
        this.fenster?.localStorage.removeItem(SCHLUESSEL);
      } else {
        this.fenster?.localStorage.setItem(SCHLUESSEL, wahl);
      }
      return true;
    } catch {
      // Ohne Speicher gilt die Wahl nur bis zum Neuladen.
      return false;
    }
  }

  private anwenden(wahl: Farbschema): void {
    const html = this.dokument.documentElement;
    if (wahl === 'system') {
      delete html.dataset['farbschema'];
    } else {
      html.dataset['farbschema'] = wahl;
    }
  }

  private gespeichert(): Farbschema {
    try {
      const wert = this.fenster?.localStorage.getItem(SCHLUESSEL);
      return wert === 'hell' || wert === 'dunkel' ? wert : 'system';
    } catch {
      return 'system';
    }
  }
}
