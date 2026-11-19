import { HttpClient, HttpParams } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { WissensIndex } from '../gemeinsam/api';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';

@Component({
  selector: 'app-wissen-index',
  imports: [RouterLink],
  templateUrl: './wissen-index.html',
  styleUrl: './wissen.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenIndex {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly texte = TEXTE.wissen;
  protected readonly typen = Object.entries(this.texte.seitentypen);
  protected readonly ergebnis = signal<WissensIndex | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly laedt = signal(false);
  private anfrage = 0;
  protected q = '';
  protected typ = '';
  protected tag = '';
  protected offset = 0;

  constructor() {
    this.laden();
  }

  protected suchen(event: Event): void {
    event.preventDefault();
    this.offset = 0;
    this.laden();
  }

  protected wert(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected wechseln(offset: number): void {
    this.offset = offset;
    this.laden();
  }

  protected laden(): void {
    const nummer = ++this.anfrage;
    this.ladenStart();
    let params = new HttpParams().set('offset', this.offset).set('groesse', 50);
    if (this.q.trim()) params = params.set('q', this.q.trim());
    if (this.typ) params = params.set('typ', this.typ);
    if (this.tag.trim()) params = params.set('tag', this.tag.trim());
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

  private ladenStart(): void {
    this.fehler.set(null);
    this.ergebnis.set(null);
    this.laedt.set(true);
  }
}
