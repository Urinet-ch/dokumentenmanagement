import { DOCUMENT } from '@angular/common';
import { afterNextRender, inject, Injectable, Injector, computed, signal } from '@angular/core';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Rolle } from '../gemeinsam/anmeldung/rechte';

/** Zustand der Hilfe-Leiste im App-Rahmen; die geöffnete Seite bleibt dabei unverändert. */
@Injectable({ providedIn: 'root' })
export class HilfeLeiste {
  readonly offen = signal(false);
  readonly anker = signal<string | null>(null);
  private ausloeser: HTMLElement | null = null;
  private fokusGeneration = 0;
  private readonly dokument = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly anmeldung = inject(AnmeldungService);

  /** Rolle für Kontext und Inhaltsverzeichnis (Req 4.3, 6.2): nur mit Anmeldung, Mandant und Rolle, sonst `null`. */
  readonly rolle = computed<Rolle | null>(() => {
    const benutzer = this.anmeldung.benutzer();
    return this.anmeldung.angemeldet() && benutzer?.mandant && benutzer.rolle ? benutzer.rolle : null;
  });

  oeffnen(anker: string | null, ausloeser: HTMLElement): void {
    this.fokusGeneration++;
    this.anker.set(anker);
    this.ausloeser = ausloeser;
    this.offen.set(true);
  }

  wechseln(anker: string): void {
    this.anker.set(anker);
  }

  schliessen(): void {
    if (!this.offen()) return;

    this.offen.set(false);
    const generation = ++this.fokusGeneration;
    const ausloeser = this.ausloeser;
    afterNextRender(
      () => {
        if (generation !== this.fokusGeneration || this.offen()) return;

        this.ausloeser = null;
        const ziel = ausloeser?.isConnected
          ? ausloeser
          : this.dokument.querySelector<HTMLElement>('main .seitenkopf h1, main h1');
        if (!ziel) return;

        if (ziel !== ausloeser && !ziel.hasAttribute('tabindex')) ziel.tabIndex = -1;
        if (!ziel.closest('[inert]')) ziel.focus({ preventScroll: true });
      },
      { injector: this.injector },
    );
  }

  drawerGeschlossen(): void {
    this.schliessen();
  }
}
