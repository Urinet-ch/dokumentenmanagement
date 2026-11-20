import { HttpClient, HttpParams } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { WissensLogSeite } from '../gemeinsam/api';
import { zeitpunkt } from '../gemeinsam/format';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { Symbol } from '../gemeinsam/symbol';
import { TEXTE } from '../gemeinsam/texte.de-CH';

@Component({
  selector: 'app-wissen-log',
  imports: [RouterLink, MatButtonModule],
  templateUrl: './wissen-log.html',
  styleUrls: ['./wissen.scss', './wissen-log.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenLog {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly texte = TEXTE.wissen;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly seite = signal<WissensLogSeite | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly laedt = signal(false);
  private cursor: string | null = null;
  private anfrage = 0;

  constructor() { this.laden(); }

  protected weiter(cursor: string): void {
    this.cursor = cursor;
    this.laden();
  }

  protected laden(): void {
    const nummer = ++this.anfrage;
    this.fehler.set(null);
    this.seite.set(null);
    this.laedt.set(true);
    let params = new HttpParams().set('groesse', 50);
    if (this.cursor) params = params.set('vor', this.cursor);
    this.http.get<WissensLogSeite>('/api/v1/wissensbasis/log', { params })
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (seite) => {
          if (nummer !== this.anfrage) return;
          this.seite.set(seite);
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
