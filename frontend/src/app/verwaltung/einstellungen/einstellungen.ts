import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { Router } from '@angular/router';
import { EinstellungskatalogEintrag, EinstellungenService } from '../../gemeinsam/api';
import { httpFehlerMeldung } from '../../gemeinsam/http-fehler';
import { TEXTE } from '../../gemeinsam/texte.de-CH';

interface Einstellungsgruppe {
  code: string;
  eintraege: EinstellungskatalogEintrag[];
}

@Component({
  selector: 'app-einstellungen',
  imports: [MatButtonModule],
  templateUrl: './einstellungen.html',
  styleUrl: './einstellungen.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EinstellungenSeite implements OnInit {
  private readonly api = inject(EinstellungenService);
  private readonly router = inject(Router);
  private readonly zerstoert = inject(DestroyRef);

  protected readonly texte = TEXTE.einstellungen;
  protected readonly bereich = TEXTE.menueGruppen.verwaltung;
  protected readonly gruppen = signal<Einstellungsgruppe[] | null>(null);
  protected readonly fehler = signal<string | null>(null);

  ngOnInit(): void {
    this.laden();
  }

  protected laden(): void {
    this.fehler.set(null);
    this.gruppen.set(null);
    this.api.apiV1MandantEinstellungenGet().pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
      next: (antwort) => {
        if (!Array.isArray(antwort.katalog)) {
          this.fehler.set(this.texte.ungueltig);
          return;
        }
        const gruppen = new Map<string, EinstellungskatalogEintrag[]>();
        for (const eintrag of antwort.katalog) {
          if (!eintrag.gruppe) {
            this.fehler.set(this.texte.ungueltig);
            return;
          }
          const eintraege = gruppen.get(eintrag.gruppe) ?? [];
          eintraege.push(eintrag);
          gruppen.set(eintrag.gruppe, eintraege);
        }
        this.gruppen.set([...gruppen].map(([code, eintraege]) => ({ code, eintraege })));
      },
      error: (fehler: unknown) => {
        if (fehler instanceof HttpErrorResponse && fehler.status === 403) {
          void this.router.navigateByUrl('/nicht-gefunden');
        } else {
          this.fehler.set(httpFehlerMeldung(fehler));
        }
      },
    });
  }

  protected wert(wert: unknown): string {
    if (wert === null || wert === undefined) {
      return this.texte.nichtGesetzt;
    }
    if (typeof wert === 'number' && Number.isFinite(wert)) {
      return new Intl.NumberFormat('de-CH').format(wert).replaceAll('’', "'");
    }
    if (typeof wert === 'string') {
      return wert;
    }
    if (Array.isArray(wert) && wert.every((element) => typeof element === 'string')) {
      return wert.join(', ');
    }
    return this.texte.ungueltig;
  }
}
