import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Subscription } from 'rxjs';
import { KontaktDetail } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { KontaktDialog } from './kontakt-dialog';
import { GRUND_MAX, aktionFehlerText, fehlerArt, kontaktName } from './kontakt-logik';
import { KontaktePort } from './kontakte-port';

/**
 * Rückfrage vor «Als inaktiv markieren» (Req 17.12, 17.17): optionaler Grund, `POST /kontakte/{id}/inaktiv` mit
 * `If-Match`. Nie ohne Rückfrage; doppeltes Absenden verhindert `sendet`.
 */
@Component({
  selector: 'app-kontakt-inaktiv-dialog',
  imports: [MatButtonModule, KontaktDialog],
  template: `
    <app-kontakt-dialog [titel]="texte.inaktivTitel(name())" (schliessen)="abbrechen()">
      <p>{{ texte.inaktivText }}</p>
      <label class="feld">
        <span>{{ texte.grundOptional }}</span>
        <textarea
          name="grund"
          rows="3"
          data-test="inaktiv-grund"
          [value]="grund()"
          (input)="grund.set($any($event.target).value)"
          aria-describedby="inaktiv-grund-fehler"
        ></textarea>
      </label>
      <p id="inaktiv-grund-fehler" class="fehler" role="alert" data-test="grund-fehler">
        @if (grundZuLang()) {
          {{ texte.grundZuLang }}
        }
      </p>
      @if (fehler(); as text) {
        <p class="fehler" role="alert" data-test="aktion-fehler">{{ text }}</p>
      }
      <div class="knoepfe">
        <button
          mat-flat-button
          type="button"
          data-test="inaktiv-ausfuehren"
          [disabled]="sendet() || grundZuLang()"
          (click)="ausfuehren()"
        >
          {{ sendet() ? texte.sendet : texte.inaktivAusfuehren }}
        </button>
        <button
          mat-button
          type="button"
          data-test="inaktiv-abbrechen"
          [disabled]="sendet()"
          (click)="abbrechen()"
        >
          {{ texte.abbrechen }}
        </button>
      </div>
    </app-kontakt-dialog>
  `,
  styleUrl: './kontakte-gemeinsam.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KontaktInaktivDialog {
  private readonly port = inject(KontaktePort);

  readonly kontakt = input.required<KontaktDetail>();
  readonly erledigt = output<void>();
  /** Die Version war veraltet (412/428): Der Aufrufer lädt das Detail neu. */
  readonly veraltet = output<void>();
  readonly geschlossen = output<void>();

  protected readonly texte = TEXTE.kontakte;
  protected readonly grund = signal('');
  protected readonly sendet = signal(false);
  protected readonly fehler = signal<string | null>(null);
  protected readonly name = computed(() => kontaktName(this.kontakt()));
  protected readonly grundZuLang = computed(() => this.grund().trim().length > GRUND_MAX);
  private laufend?: Subscription;

  protected ausfuehren(): void {
    const k = this.kontakt();
    if (this.sendet() || this.grundZuLang() || !k.id) {
      return;
    }
    this.sendet.set(true);
    this.fehler.set(null);
    this.laufend = this.port
      .inaktivMarkieren(k.id, k.version ?? 0, this.grund().trim() || null)
      .subscribe({
        next: () => {
          this.sendet.set(false);
          this.erledigt.emit();
        },
        error: (f: unknown) => {
          this.sendet.set(false);
          const art = fehlerArt(f);
          if (art === 'veraltet' || art === 'versionFehlt') {
            this.veraltet.emit();
            return;
          }
          this.fehler.set(aktionFehlerText(f, 'inaktiv'));
        },
      });
  }

  protected abbrechen(): void {
    if (!this.sendet()) {
      this.laufend?.unsubscribe();
      this.geschlossen.emit();
    }
  }
}
