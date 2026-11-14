import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Subscription } from 'rxjs';
import { KontaktDetail } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import {
  FELDER,
  Feld,
  FormularWerte,
  Fehlerliste,
  MAX,
  Typ,
  aenderungen,
  aktionFehlerText,
  fehlerArt,
  formularAusDetail,
  pruefen,
} from './kontakt-logik';
import { KontaktePort } from './kontakte-port';

/**
 * Formular «Bearbeiten» (Req 17.6; Recht A12): die Felder von `PATCH /kontakte/{id}` mit denselben Prüfungen wie das
 * Archiv. Gesendet werden nur geänderte Felder, mit `If-Match` der Version des Details; 412/428 meldet `veraltet`.
 */
@Component({
  selector: 'app-kontakt-bearbeiten',
  imports: [MatButtonModule],
  templateUrl: './kontakt-bearbeiten.html',
  styleUrls: ['./kontakte-gemeinsam.scss', './kontakt-bearbeiten.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KontaktBearbeiten {
  private readonly port = inject(KontaktePort);

  readonly kontakt = input.required<KontaktDetail>();
  readonly gespeichert = output<void>();
  readonly veraltet = output<void>();
  readonly abgebrochen = output<void>();

  protected readonly texte = TEXTE.kontakte;
  protected readonly felder = FELDER;
  protected readonly max = MAX;
  /** Nur die vom Benutzer angefassten Felder; alle übrigen kommen immer aus dem aktuellen Detail. */
  protected readonly eingaben = signal<Partial<FormularWerte>>({});
  protected readonly gezeigteFehler = signal<Fehlerliste>({});
  protected readonly sendet = signal(false);
  protected readonly meldung = signal<string | null>(null);
  protected readonly typen: readonly Typ[] = ['PERSON', 'ORGANISATION'];
  protected readonly aktuell = computed(
    () => ({ ...formularAusDetail(this.kontakt()), ...this.eingaben() }) as FormularWerte,
  );
  private readonly titel = viewChild<ElementRef<HTMLElement>>('titel');
  private laufend?: Subscription;

  constructor() {
    queueMicrotask(() => this.titel()?.nativeElement.focus());
  }

  protected setzen(feld: Feld | 'typ', wert: string): void {
    const neu = { ...this.eingaben(), [feld]: wert };
    // zurückgetippt auf den Stand des Details: nicht mehr als Eingabe führen
    if (wert === formularAusDetail(this.kontakt())[feld]) {
      delete neu[feld];
    }
    this.eingaben.set(neu);
  }

  protected pruefText(feld: Feld | 'typ'): string {
    const p = this.gezeigteFehler()[feld];
    if (!p) {
      return '';
    }
    return p === 'zuLang'
      ? this.texte.pruefung.zuLang(feld === 'typ' ? 0 : MAX[feld])
      : this.texte.pruefung[p];
  }

  protected speichern(ereignis: Event): void {
    ereignis.preventDefault();
    const k = this.kontakt();
    if (this.sendet() || !k.id) {
      return;
    }
    const jetzt = this.aktuell();
    const fehler = pruefen(jetzt);
    this.gezeigteFehler.set(fehler);
    this.meldung.set(null);
    if (Object.keys(fehler).length > 0) {
      return;
    }
    const diff = aenderungen(formularAusDetail(k), jetzt);
    if (Object.keys(diff).length === 0) {
      this.meldung.set(this.texte.keineAenderung);
      return;
    }
    this.sendet.set(true);
    this.laufend?.unsubscribe();
    this.laufend = this.port.aendern(k.id, k.version ?? 0, diff).subscribe({
      next: () => {
        this.sendet.set(false);
        this.gespeichert.emit();
      },
      error: (f: unknown) => {
        this.sendet.set(false);
        const art = fehlerArt(f);
        if (art === 'veraltet' || art === 'versionFehlt') {
          this.veraltet.emit();
          return;
        }
        // 400: das Archiv nennt kein Feld; die Meldung steht beim Formular
        this.meldung.set(aktionFehlerText(f, 'aendern'));
      },
    });
  }
}
