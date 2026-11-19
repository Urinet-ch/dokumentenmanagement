import { HttpClient, HttpParams } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { WissensIndex } from '../gemeinsam/api';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';

@Component({
  selector: 'app-wissen-verwendung',
  imports: [RouterLink],
  templateUrl: './wissen-verwendung.html',
  styleUrl: './wissen.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenVerwendung {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  readonly dokumentId = input.required<string>();
  protected readonly texte = TEXTE.wissen;
  protected readonly ergebnis = signal<WissensIndex | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly laedt = signal(false);
  protected readonly offset = signal(0);
  private anfrage = 0;

  constructor() {
    effect(() => {
      const dokumentId = this.dokumentId();
      untracked(() => {
        this.offset.set(0);
        this.laden(dokumentId, 0);
      });
    });
  }

  protected erneut(): void {
    this.laden(this.dokumentId(), this.offset());
  }

  protected wechseln(offset: number): void {
    this.offset.set(offset);
    this.laden(this.dokumentId(), offset);
  }

  private laden(dokumentId: string, offset: number): void {
    const nummer = ++this.anfrage;
    this.ergebnis.set(null);
    this.fehler.set(null);
    this.laedt.set(true);
    const params = new HttpParams().set('dokumentId', dokumentId).set('offset', offset).set('groesse', 50);
    this.http.get<WissensIndex>('/api/v1/wissensseiten', { params })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (ergebnis) => {
          if (nummer !== this.anfrage) return;
          this.ergebnis.set(ergebnis);
          this.laedt.set(false);
        },
        error: (fehler: unknown) => {
          if (nummer !== this.anfrage) return;
          this.fehler.set(httpFehlerMeldung(fehler));
          this.laedt.set(false);
        },
      });
  }
}
