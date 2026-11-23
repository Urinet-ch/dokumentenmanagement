import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { WissenEinstellungen, WissenExport, WissensbasisService } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { zeit } from '../importe/import-logik';

@Component({
  selector: 'app-wissen-einstellungen',
  imports: [RouterLink, MatButtonModule],
  templateUrl: './wissen-einstellungen.html',
  styleUrl: './wissen.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenEinstellungenSeite {
  private readonly api = inject(WissensbasisService);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly destroy = inject(DestroyRef);
  private timer: ReturnType<typeof setTimeout> | undefined;
  private etag: string | null = null;
  private ausstehendeEingabe: WissenEinstellungen | null = null;
  protected readonly texte = TEXTE.wissenEinstellungen;
  protected readonly werte = signal<WissenEinstellungen | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly gespeichert = signal(false);
  protected readonly speichert = signal(false);
  protected readonly bestaetigung = signal<string[]>([]);
  protected readonly erneuteAnmeldung = signal(false);
  protected readonly neuLaden = signal(false);
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
    this.neuLaden.set(false);
    this.etag = null;
    this.bestaetigungVerwerfen();
    this.api.apiV1WissensbasisEinstellungenGet('response').pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (antwort) => this.antwortUebernehmen(antwort),
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
    this.bestaetigungVerwerfen();
    this.absenden(eingabe, false);
  }

  protected bestaetigungVerwerfen(): void {
    this.ausstehendeEingabe = null;
    this.bestaetigung.set([]);
  }

  protected bestaetigen(): void {
    if (this.ausstehendeEingabe) {
      this.absenden(this.ausstehendeEingabe, true);
    }
  }

  protected frischAnmelden(): void {
    void this.anmeldung.anmelden('/wissensbasis/einstellungen', true)
      .catch(() => this.fehler.set(TEXTE.anmeldung.fehlgeschlagen));
  }

  private absenden(eingabe: WissenEinstellungen, bestaetigt: boolean): void {
    if (!this.etag) {
      this.fehler.set(this.texte.versionFehlt);
      this.neuLaden.set(true);
      return;
    }
    this.fehler.set(null);
    this.erneuteAnmeldung.set(false);
    this.gespeichert.set(false);
    this.speichert.set(true);
    this.api.apiV1WissensbasisEinstellungenPut(eingabe, bestaetigt ? 'true' : undefined, this.etag, 'response')
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (antwort) => {
        const uebernommen = this.antwortUebernehmen(antwort);
        this.bestaetigungVerwerfen();
        this.speichert.set(false);
        this.gespeichert.set(uebernommen);
      },
      error: (fehler: unknown) => {
        this.speichert.set(false);
        if (fehler instanceof HttpErrorResponse && fehler.error?.fehlercode === 'BESTAETIGUNG_NOETIG'
            && Array.isArray(fehler.error.auswirkung)) {
          const texte = fehler.error.auswirkung
            .map((eintrag: unknown) => typeof eintrag === 'object' && eintrag !== null && 'text' in eintrag
              && typeof eintrag.text === 'string' ? eintrag.text : null)
            .filter((text: string | null): text is string => text !== null);
          if (texte.length) {
            this.ausstehendeEingabe = eingabe;
            this.bestaetigung.set(texte);
            return;
          }
        }
        if (fehler instanceof HttpErrorResponse && fehler.error?.fehlercode === 'STEP_UP_NOETIG') {
          this.erneuteAnmeldung.set(true);
          this.fehler.set(this.texte.frischAnmeldenHinweis);
        } else {
          this.neuLaden.set(fehler instanceof HttpErrorResponse && fehler.status === 412);
          if (this.neuLaden()) this.etag = null;
          this.fehler.set(httpFehlerMeldung(fehler));
        }
      },
    });
  }

  private antwortUebernehmen(antwort: HttpResponse<WissenEinstellungen>): boolean {
    this.etag = antwort.headers.get('ETag');
    if (!this.etag || !antwort.body) {
      this.fehler.set(this.texte.versionFehlt);
      this.neuLaden.set(true);
      this.etag = null;
      return false;
    }
    this.werte.set(antwort.body);
    return true;
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
