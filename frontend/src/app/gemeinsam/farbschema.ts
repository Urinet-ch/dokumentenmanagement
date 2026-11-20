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
    const neu: Farbschema = this.dunkel() ? 'hell' : 'dunkel';
    this.wahl.set(neu);
    this.anwenden(neu);
    try {
      this.fenster?.localStorage.setItem(SCHLUESSEL, neu);
    } catch {
      // Ohne Speicher gilt die Wahl nur bis zum Neuladen.
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
