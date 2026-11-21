import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { Subscription, filter } from 'rxjs';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { ZAEHLER_PAUSE_MS } from '../gemeinsam/eingangskorb-zaehler';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';

@Injectable({ providedIn: 'root' })
export class WissenEntwurfZaehler {
  private readonly http = inject(HttpClient);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly destroy = inject(DestroyRef);
  readonly anzahl = signal<number | null>(null);
  readonly fehler = signal<string | null>(null);
  private laufend?: Subscription;
  private zuletzt = Number.NEGATIVE_INFINITY;

  constructor() {
    effect(() => {
      this.anmeldung.benutzer();
      const darf = this.anmeldung.darf('A17');
      untracked(() => {
        this.laufend?.unsubscribe();
        this.anzahl.set(null);
        this.fehler.set(null);
        if (darf) this.auffrischen();
      });
    });
    inject(Router).events.pipe(filter((e) => e instanceof NavigationEnd), takeUntilDestroyed())
      .subscribe(() => {
        if (Date.now() - this.zuletzt >= ZAEHLER_PAUSE_MS) this.auffrischen();
      });
  }

  auffrischen(): void {
    if (!this.anmeldung.darf('A17')) return;
    this.laufend?.unsubscribe();
    this.zuletzt = Date.now();
    this.fehler.set(null);
    this.laufend = this.http.get<unknown[]>('/api/v1/wissensentwuerfe', { params: { status: 'OFFEN' } })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (liste) => this.anzahl.set(liste.length),
        error: (fehler: unknown) => {
          this.anzahl.set(null);
          this.fehler.set(httpFehlerMeldung(fehler));
        },
      });
  }
}
