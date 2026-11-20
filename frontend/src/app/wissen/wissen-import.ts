import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Anzahl, Antwort1, WissensbasisService } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { WissenEntwurfZaehler } from './wissen-entwurf-zaehler';

type ImportBefund = { code: string; pfad: string };
interface ImportBericht extends Antwort1 {
  entwurfId: string; status: 'OFFEN'; paketSha256: string; anzahl: Required<Anzahl>;
  befunde: ImportBefund[]; hinweise: ImportBefund[];
}

function istBefund(wert: unknown): wert is ImportBefund {
  return wert !== null && typeof wert === 'object'
    && 'code' in wert && typeof wert.code === 'string'
    && 'pfad' in wert && typeof wert.pfad === 'string';
}

function istBericht(wert: Antwort1): wert is ImportBericht {
  if (!wert || typeof wert !== 'object') return false;
  const anzahl = wert.anzahl;
  return typeof wert.entwurfId === 'string' && !!wert.entwurfId.trim() && wert.status === 'OFFEN'
    && typeof wert.paketSha256 === 'string' && /^[a-f0-9]{64}$/i.test(wert.paketSha256)
    && !!anzahl && [anzahl.neu, anzahl.aendern, anzahl.unveraendert, anzahl.ignoriert]
      .every((zahl) => typeof zahl === 'number' && Number.isInteger(zahl) && zahl >= 0)
    && Array.isArray(wert.befunde) && wert.befunde.every(istBefund)
    && Array.isArray(wert.hinweise) && wert.hinweise.every(istBefund);
}

@Component({
  selector: 'app-wissen-import',
  imports: [RouterLink, FormsModule, MatButtonModule],
  templateUrl: './wissen-import.html',
  styleUrls: ['./wissen.scss', './wissen-import.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenImport {
  private readonly api = inject(WissensbasisService);
  private readonly destroy = inject(DestroyRef);
  private readonly zaehler = inject(WissenEntwurfZaehler);
  protected readonly anmeldung = inject(AnmeldungService);
  protected readonly texte = TEXTE.wissen.import;
  protected readonly datei = signal<File | null>(null);
  protected readonly sendet = signal(false);
  protected readonly bericht = signal<ImportBericht | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly fehlerBefunde = signal<ImportBefund[]>([]);
  protected readonly vorhandenerEntwurf = signal<string | null>(null);
  private readonly kontext = computed(() => {
    const benutzer = this.anmeldung.benutzer();
    return `${benutzer?.id}/${benutzer?.mandant}/${benutzer?.rolle}/${this.anmeldung.darf('A17')}`;
  });
  private laufend?: Subscription;
  private readonly dateiFeld = viewChild<ElementRef<HTMLInputElement>>('paket');

  constructor() {
    effect(() => {
      this.kontext();
      untracked(() => {
        this.laufend?.unsubscribe();
        this.datei.set(null);
        const feld = this.dateiFeld();
        if (feld) feld.nativeElement.value = '';
        this.sendet.set(false);
        this.zuruecksetzen();
      });
    });
  }

  protected waehlen(datei: File | null): void {
    this.laufend?.unsubscribe();
    this.sendet.set(false);
    this.datei.set(datei);
    this.zuruecksetzen();
  }

  protected importieren(): void {
    if (this.sendet()) return;
    this.zuruecksetzen();
    if (!this.anmeldung.darf('A17')) {
      this.fehler.set(this.texte.nichtErlaubt);
      return;
    }
    const datei = this.datei();
    if (!datei) {
      this.fehler.set(this.texte.dateiFehlt);
      return;
    }
    this.sendet.set(true);
    this.laufend = this.api.apiV1WissensentwuerfeImportPost(datei)
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (antwort) => {
          this.sendet.set(false);
          if (!istBericht(antwort)) {
            this.fehler.set(this.texte.berichtFehlt);
            return;
          }
          this.bericht.set(antwort);
          this.zaehler.auffrischen();
        },
        error: (fehler: unknown) => {
          this.sendet.set(false);
          this.fehler.set(httpFehlerMeldung(fehler));
          const antwort: unknown = fehler instanceof HttpErrorResponse ? fehler.error : null;
          if (antwort && typeof antwort === 'object') {
            if ('befunde' in antwort && Array.isArray(antwort.befunde)) {
              this.fehlerBefunde.set(antwort.befunde.filter(istBefund));
            }
            if (fehler instanceof HttpErrorResponse && fehler.status === 409
              && 'entwurfId' in antwort && typeof antwort.entwurfId === 'string' && antwort.entwurfId.trim()) {
              this.vorhandenerEntwurf.set(antwort.entwurfId);
            }
          }
        },
      });
  }

  private zuruecksetzen(): void {
    this.bericht.set(null);
    this.fehler.set(null);
    this.fehlerBefunde.set([]);
    this.vorhandenerEntwurf.set(null);
  }
}
