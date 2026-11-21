import { Injectable, signal } from '@angular/core';

/** Zustand der Hilfe-Leiste im App-Rahmen; die geöffnete Seite bleibt dabei unverändert. */
@Injectable({ providedIn: 'root' })
export class HilfeLeiste {
  readonly offen = signal(false);
  readonly anker = signal<string | null>(null);
  private ausloeser: HTMLElement | null = null;

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
