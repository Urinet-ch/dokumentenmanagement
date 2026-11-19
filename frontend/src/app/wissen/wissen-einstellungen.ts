import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { WissenEinstellungen, WissenExport, WissensbasisService } from '../gemeinsam/api';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { zeit } from '../importe/import-logik';

@Component({
  selector: 'app-wissen-einstellungen',
  imports: [RouterLink],
  templateUrl: './wissen-einstellungen.html',
  styleUrl: './wissen.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenEinstellungenSeite {
  private readonly api = inject(WissensbasisService);
  private readonly destroy = inject(DestroyRef);
  private timer: ReturnType<typeof setTimeout> | undefined;
  protected readonly texte = TEXTE.wissenEinstellungen;
  protected readonly werte = signal<WissenEinstellungen | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly gespeichert = signal(false);
  protected readonly speichert = signal(false);
  protected readonly export = signal<WissenExport | null>(null);
  protected readonly exportFehler = signal<string | null>(null);
  protected readonly startet = signal(false);
  protected readonly zeit = zeit;

  constructor() {
    this.laden();
    this.destroy.onDestroy(() => clearTimeout(this.timer));
  }

  protected laden(): void {
    this.fehler.set(null);
    this.api.apiV1WissensbasisEinstellungenGet().pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (werte) => this.werte.set(werte),
      error: (fehler: unknown) => this.fehler.set(httpFehlerMeldung(fehler)),
    });
  }

  protected speichern(form: HTMLFormElement): void {
    const daten = new FormData(form);
    const zahl = (name: string): number | null => {
      const wert = daten.get(name)?.toString().trim() ?? '';
      return wert ? Number(wert) : null;
    };
    const eingabe: WissenEinstellungen = {
      budgetTokens: zahl('budgetTokens'),
      lintBudgetTokens: zahl('lintBudgetTokens'),
      lintZeitplan: daten.get('lintZeitplan')?.toString().trim() || null,
      monatsbudgetTokens: zahl('monatsbudgetTokens'),
    };
    this.fehler.set(null);
    this.gespeichert.set(false);
    this.speichert.set(true);
    this.api.apiV1WissensbasisEinstellungenPut(eingabe).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (werte) => {
        this.werte.set(werte);
        this.speichert.set(false);
        this.gespeichert.set(true);
      },
      error: (fehler: unknown) => {
        this.speichert.set(false);
        this.fehler.set(httpFehlerMeldung(fehler));
      },
    });
  }

  protected exportStarten(): void {
    clearTimeout(this.timer);
    this.export.set(null);
    this.exportFehler.set(null);
    this.startet.set(true);
    this.api.apiV1WissensbasisExportePost().pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (exportInfo) => {
        this.startet.set(false);
        this.exportSetzen(exportInfo);
      },
      error: (fehler: unknown) => {
        this.startet.set(false);
        this.exportFehler.set(httpFehlerMeldung(fehler));
      },
    });
  }

  protected exportAbrufen(): void {
    const id = this.export()?.id;
    if (!id) {
      this.exportFehler.set(this.texte.exportUngueltig);
      return;
    }
    this.exportFehler.set(null);
    this.api.apiV1WissensbasisExporteIdGet(id).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (exportInfo) => this.exportSetzen(exportInfo),
      error: (fehler: unknown) => this.exportFehler.set(httpFehlerMeldung(fehler)),
    });
  }

  private exportSetzen(exportInfo: WissenExport): void {
    this.export.set(exportInfo);
    if (!exportInfo.id) {
      this.exportFehler.set(this.texte.exportUngueltig);
    } else if (exportInfo.status === 'OFFEN' || exportInfo.status === 'IN_ARBEIT') {
      this.timer = setTimeout(() => this.exportAbrufen(), 5000);
    } else if (exportInfo.status === 'FEHLER') {
      this.exportFehler.set(this.texte.exportFehler);
    } else if (exportInfo.status === 'ABGELAUFEN') {
      this.exportFehler.set(this.texte.exportAbgelaufen);
    } else if (exportInfo.status !== 'FERTIG' || !exportInfo.url || !exportInfo.gueltigBis || !exportInfo.sha256) {
      this.exportFehler.set(this.texte.exportUngueltig);
    }
  }
}
