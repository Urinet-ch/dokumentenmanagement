import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { UploadErgebnis, UploadFehler, UploadPort } from './upload-port';

export const ERLAUBTE_TYPEN =
  '.pdf,.jpg,.jpeg,.png,.heic,.heif,.tif,.tiff,application/pdf,image/jpeg,image/png,image/heic,image/heif,image/tiff';

const BILD_TYP = /^image\/(jpeg|png|heic|heif|tiff)$/;
const BILD_ENDUNG = /\.(jpe?g|png|heic|heif|tiff?)$/i;

export type Ausgang = 'angenommen' | 'vorhanden' | 'abgelehnt';

export interface Zeile {
  datei: File;
  ausgang?: Ausgang;
  meldung?: string;
  fehlercode?: string;
  vorhandenId?: string;
}

/** Seite «Erfassen»: Upload im Browser (Req 3.1, 3.3–3.8). Die Typprüfung macht das Archiv am Inhalt (Req 3.3). */
@Component({
  selector: 'app-upload',
  imports: [MatButtonModule, MatCheckboxModule, MatProgressBarModule, RouterLink],
  templateUrl: './upload.html',
  styleUrl: './upload.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Upload {
  private readonly upload = inject(UploadPort);
  private readonly injector = inject(Injector);
  private laufend?: Subscription;

  protected readonly texte = TEXTE.upload;
  protected readonly erlaubteTypen = ERLAUBTE_TYPEN;

  protected readonly dateien = signal<readonly File[]>([]);
  protected readonly zusammenfassenGewaehlt = signal(false);
  protected readonly laeuft = signal(false);
  protected readonly prozent = signal(0);
  protected readonly ergebnisse = signal<readonly UploadErgebnis[] | null>(null);
  protected readonly gesamtFehler = signal<string | null>(null);
  protected readonly ziehen = signal(false);

  protected readonly zusammenfassenMoeglich = computed(
    () => this.dateien().length >= 2 && this.dateien().every(istBild),
  );
  protected readonly zusammenfassen = computed(() => this.zusammenfassenMoeglich() && this.zusammenfassenGewaehlt());
  protected readonly abgeschlossen = computed(() => this.ergebnisse() !== null);
  protected readonly zeilen = computed<Zeile[]>(() => {
    const ergebnisse = this.ergebnisse();
    return this.dateien().map((datei, index) => ({ datei, ...auswerten(zuordnen(ergebnisse, datei, index), !!ergebnisse) }));
  });

  private readonly dateiwahl = viewChild.required<ElementRef<HTMLInputElement>>('dateiwahl');
  private readonly ergebnisUeberschrift = viewChild<ElementRef<HTMLElement>>('ergebnisUeberschrift');

  constructor() {
    inject(DestroyRef).onDestroy(() => this.laufend?.unsubscribe());
  }

  protected auswaehlen(): void {
    this.dateiwahl().nativeElement.click();
  }

  protected gewaehlt(eingabe: HTMLInputElement): void {
    this.hinzufuegen(eingabe.files);
    eingabe.value = '';
  }

  protected ueberZiehen(ereignis: DragEvent): void {
    ereignis.preventDefault();
    if (!this.laeuft()) {
      this.ziehen.set(true);
    }
  }

  protected abgelegt(ereignis: DragEvent): void {
    ereignis.preventDefault();
    this.ziehen.set(false);
    this.hinzufuegen(ereignis.dataTransfer?.files ?? null);
  }

  protected verschieben(index: number, richtung: -1 | 1): void {
    const ziel = index + richtung;
    const liste = [...this.dateien()];
    if (ziel < 0 || ziel >= liste.length) {
      return;
    }
    [liste[index], liste[ziel]] = [liste[ziel], liste[index]];
    this.dateien.set(liste);
  }

  protected entfernen(index: number): void {
    this.dateien.update((liste) => liste.filter((_, i) => i !== index));
  }

  protected neu(): void {
    this.dateien.set([]);
    this.ergebnisse.set(null);
    this.gesamtFehler.set(null);
    this.zusammenfassenGewaehlt.set(false);
  }

  protected hochladen(): void {
    if (this.laeuft() || this.dateien().length === 0) {
      return;
    }
    this.laeuft.set(true);
    this.prozent.set(0);
    this.gesamtFehler.set(null);
    this.laufend = this.upload.hochladen(this.dateien(), this.zusammenfassen()).subscribe({
      next: (ereignis) => {
        if (ereignis.typ === 'fortschritt') {
          this.prozent.set(ereignis.prozent);
        } else if (ereignis.typ === 'fertig') {
          this.ergebnisse.set(ereignis.ergebnisse);
          this.beenden();
        } else {
          this.gesamtFehler.set(gesamtMeldung(ereignis.fehler));
          this.beenden();
        }
      },
      error: () => {
        this.gesamtFehler.set(TEXTE.upload.fehlerAllgemein);
        this.beenden();
      },
    });
  }

  private beenden(): void {
    this.laeuft.set(false);
    afterNextRender(() => this.ergebnisUeberschrift()?.nativeElement.focus(), { injector: this.injector });
  }

  private hinzufuegen(neue: FileList | null): void {
    if (!neue?.length || this.laeuft()) {
      return;
    }
    if (this.abgeschlossen()) {
      this.neu();
    }
    this.gesamtFehler.set(null);
    this.dateien.update((liste) => [...liste, ...Array.from(neue)]);
  }
}

function istBild(datei: File): boolean {
  return BILD_TYP.test(datei.type) || BILD_ENDUNG.test(datei.name);
}

// Die Antwort führt die Dateien in der gesendeten Reihenfolge; passt der Name nicht, wird über den Namen gesucht.
function zuordnen(ergebnisse: readonly UploadErgebnis[] | null, datei: File, index: number): UploadErgebnis | undefined {
  if (!ergebnisse) {
    return undefined;
  }
  return ergebnisse[index]?.datei === datei.name ? ergebnisse[index] : ergebnisse.find((e) => e.datei === datei.name);
}

function auswerten(ergebnis: UploadErgebnis | undefined, abgeschlossen: boolean): Omit<Zeile, 'datei'> {
  if (!abgeschlossen) {
    return {};
  }
  if (!ergebnis) {
    return { ausgang: 'abgelehnt', meldung: TEXTE.upload.fehlerUnbekannt };
  }
  if (ergebnis.status === 'DUBLETTE') {
    return { ausgang: 'vorhanden', meldung: TEXTE.upload.bereitsVorhanden, vorhandenId: ergebnis.vorhandenId };
  }
  if (ergebnis.status === 'ABGELEHNT' || ergebnis.fehler) {
    return {
      ausgang: 'abgelehnt',
      meldung: dateiMeldung(ergebnis.fehler),
      fehlercode: ergebnis.fehler?.fehlercode,
    };
  }
  return { ausgang: 'angenommen', meldung: TEXTE.upload.angenommen };
}

function dateiMeldung(fehler: UploadFehler | undefined): string {
  const code = fehler?.fehlercode;
  return fehler?.detail || (code && TEXTE.upload.fehlercode[code]) || TEXTE.upload.fehlerUnbekannt;
}

function gesamtMeldung(fehler: UploadFehler): string {
  if (fehler.status === 401) {
    return TEXTE.upload.fehlerSitzung;
  }
  if (fehler.status === 0) {
    return TEXTE.upload.fehlerVerbindung;
  }
  // 413 ohne Ergebnisse je Datei: die ganze Anfrage liegt über der Gesamtgrenze (design.md «Upload»)
  if (fehler.status === 413) {
    return TEXTE.upload.fehlerAnfrageZuGross;
  }
  const code = fehler.fehlercode;
  return fehler.detail || (code && TEXTE.upload.fehlercode[code]) || TEXTE.upload.fehlerAllgemein;
}
