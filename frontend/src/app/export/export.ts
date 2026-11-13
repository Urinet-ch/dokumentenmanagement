import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Export as ExportEintrag, ExporteService } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { groesse, zahl, zeit } from '../importe/import-logik';
import {
  ABFRAGE_MS,
  JAHR_MAX,
  JAHR_MIN,
  fehlerText,
  inArbeit,
  jahrGueltig,
  standardJahr,
} from './export-logik';

/**
 * Seite «Export» (Spec 02, Aufgabe 6.5; Req 7.1, 7.2, 7.5): Geschäftsjahr wählen, Export anfordern, Status verfolgen
 * und das ZIP herunterladen. Nur für Inhaber und Treuhänder (Route und Menü); das Backend prüft dasselbe.
 */
@Component({
  selector: 'app-export',
  imports: [MatButtonModule],
  templateUrl: './export.html',
  styleUrl: './export.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Export implements OnInit {
  private readonly exporte = inject(ExporteService);
  private abfrage?: ReturnType<typeof setTimeout>;
  private beendet = false;

  protected readonly texte = TEXTE.export;
  protected readonly zahl = zahl;
  protected readonly zeit = zeit;
  protected readonly groesse = groesse;
  protected readonly min = JAHR_MIN;
  protected readonly max = JAHR_MAX;

  protected readonly jahr = signal<number | null>(standardJahr(new Date()));
  protected readonly laeuft = signal(false);
  protected readonly meldung = signal<string | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly liste = signal<readonly ExportEintrag[] | null>(null);
  protected readonly listeFehler = signal<string | null>(null);
  protected readonly downloadFehler = signal<string | null>(null);

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.beendet = true;
      clearTimeout(this.abfrage);
    });
  }

  ngOnInit(): void {
    this.laden();
  }

  protected jahrSetzen(wert: string): void {
    const zahlwert = Number(wert);
    this.jahr.set(wert.trim() === '' || Number.isNaN(zahlwert) ? null : zahlwert);
  }

  protected jahrOk(): boolean {
    return jahrGueltig(this.jahr());
  }

  protected anfordern(): void {
    const jahr = this.jahr();
    if (!jahrGueltig(jahr) || this.laeuft()) {
      return;
    }
    this.laeuft.set(true);
    this.meldung.set(null);
    this.fehler.set(null);
    this.exporte.apiV1ExportePost({ geschaeftsjahr: jahr }).subscribe({
      next: () => {
        this.laeuft.set(false);
        this.meldung.set(this.texte.angefordert);
        this.laden();
      },
      error: (fehler: unknown) => {
        this.laeuft.set(false);
        this.fehler.set(fehlerText(fehler));
      },
    });
  }

  protected herunterladen(eintrag: ExportEintrag): void {
    this.downloadFehler.set(null);
    this.exporte.apiV1ExporteIdGet(eintrag.id).subscribe({
      next: (abruf) => {
        if (abruf.url) {
          window.location.assign(abruf.url);
        } else {
          this.laden();
        }
      },
      error: (fehler: unknown) => this.downloadFehler.set(fehlerText(fehler)),
    });
  }

  protected laden(): void {
    clearTimeout(this.abfrage);
    this.listeFehler.set(null);
    this.exporte.apiV1ExporteGet().subscribe({
      next: (liste) => {
        this.liste.set(liste);
        if (inArbeit(liste) && !this.beendet) {
          this.abfrage = setTimeout(() => this.laden(), ABFRAGE_MS);
        }
      },
      error: (fehler: unknown) => this.listeFehler.set(fehlerText(fehler)),
    });
  }
}
