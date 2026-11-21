import { Injectable, computed, inject, signal } from '@angular/core';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Rolle } from '../gemeinsam/anmeldung/rechte';

/** Zustand der Hilfe-Leiste im App-Rahmen; die geöffnete Seite bleibt dabei unverändert. */
@Injectable({ providedIn: 'root' })
export class HilfeLeiste {
  readonly offen = signal(false);
  readonly anker = signal<string | null>(null);
  private ausloeser: HTMLElement | null = null;
  private readonly anmeldung = inject(AnmeldungService);

  /** Rolle für Kontext und Inhaltsverzeichnis (Req 4.3, 6.2): nur mit Anmeldung, Mandant und Rolle, sonst `null`. */
  readonly rolle = computed<Rolle | null>(() => {
    const benutzer = this.anmeldung.benutzer();
    return this.anmeldung.angemeldet() && benutzer?.mandant && benutzer.rolle ? benutzer.rolle : null;
  });

  oeffnen(anker: string | null, ausloeser: HTMLElement): void {
    this.anker.set(anker);
    this.ausloeser = ausloeser;
    this.offen.set(true);
  }

  wechseln(anker: string): void {
    this.anker.set(anker);
  }

  schliessen(): void {
    this.offen.set(false);
  }

  geaendert(offen: boolean): void {
    if (!offen) this.offen.set(false);
  }

  geschlossen(): void {
    const ausloeser = this.ausloeser;
    this.ausloeser = null;
    ausloeser?.focus();
  }
}
