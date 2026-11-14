import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import {
  Subject,
  Subscription,
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  of,
  switchMap,
} from 'rxjs';
import { Kontakt, KontaktDetail, KontaktVerweise, KontaktZusammengefuehrt } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { KontaktDialog } from './kontakt-dialog';
import {
  GRUND_MAX,
  ZIEL_MIN_ZEICHEN,
  aktionFehlerText,
  fehlerArt,
  kontaktAuswahlText,
  kontaktName,
} from './kontakt-logik';
import { KontaktePort } from './kontakte-port';

/** Ergebnis für die Seite: das Ziel mit der Zahl der umgehängten Verweise. */
export interface Zusammengefuehrt {
  readonly ziel: { id: string; name: string };
  readonly zahlen: KontaktZusammengefuehrt;
}

/**
 * Zusammenführen zweier Kontakte (Req 17.7; Bedienung 17.6, 17.17): 1. Ziel unter den aktiven Kontakten suchen (ohne die
 * Quelle) und Grund angeben, 2. Rückfrage mit Ziel, den Zahlen aus `GET /kontakte/{id}/verweise` und dem Hinweis «nicht
 * umkehrbar», erst dann `POST …/zusammenfuehren`. Nie ohne Rückfrage; doppeltes Absenden verhindert `sendet`.
 */
@Component({
  selector: 'app-kontakt-zusammenfuehren-dialog',
  imports: [MatButtonModule, KontaktDialog],
  template: `
    <app-kontakt-dialog
      [titel]="
        phase() === 'rueckfrage' ? texte.rueckfrageTitel : texte.zusammenfuehrenTitel(quelleName())
      "
      (schliessen)="abbrechen()"
    >
      @if (phase() === 'auswahl') {
        <p>{{ texte.zusammenfuehrenEinleitung }}</p>
        @if (ziel(); as z) {
          <p class="gewaehlt text" data-test="ziel-gewaehlt">
            {{ texte.zielGewaehlt(auswahlText(z)) }}
            <button mat-button type="button" data-test="ziel-aendern" (click)="ziel.set(null)">
              {{ texte.zielAendern }}
            </button>
          </p>
        } @else {
          <label class="feld">
            <span>{{ texte.zielSuchen }}</span>
            <input
              type="search"
              name="ziel"
              maxlength="200"
              autocomplete="off"
              data-test="ziel-suche"
              [value]="zielEingabe()"
              (input)="zielTippen($any($event.target).value)"
              aria-describedby="ziel-hinweis"
            />
            <small id="ziel-hinweis">{{ texte.zielHinweis }}</small>
          </label>
          @if (vorschlaege(); as liste) {
            <ul class="vorschlaege" [attr.aria-label]="texte.zielVorschlaege">
              @for (k of liste; track k.id) {
                <li>
                  <button
                    mat-button
                    type="button"
                    class="vorschlag"
                    data-test="ziel-vorschlag"
                    (click)="ziel.set(k)"
                  >
                    {{ auswahlText(k) }}
                  </button>
                </li>
              } @empty {
                <li class="hinweis" data-test="ziel-keine">{{ texte.zielKeine }}</li>
              }
            </ul>
          }
        }
        <label class="feld">
          <span>{{ texte.grund }}</span>
          <textarea
            name="grund"
            rows="3"
            data-test="zusammenfuehren-grund"
            [value]="grund()"
            (input)="grund.set($any($event.target).value)"
            aria-describedby="grund-hinweis"
          ></textarea>
          <small id="grund-hinweis">{{ texte.grundHinweis }}</small>
        </label>
        <p class="fehler" role="alert" data-test="auswahl-fehler">{{ auswahlFehler() }}</p>
        @if (fehler(); as text) {
          <p class="fehler" role="alert" data-test="aktion-fehler">{{ text }}</p>
        }
        <div class="knoepfe">
          <button
            mat-flat-button
            type="button"
            data-test="weiter"
            [disabled]="laedtVerweise()"
            (click)="weiter()"
          >
            {{ laedtVerweise() ? texte.verweiseLaedt : texte.weiter }}
          </button>
          <button
            mat-button
            type="button"
            data-test="zusammenfuehren-abbrechen"
            (click)="abbrechen()"
          >
            {{ texte.abbrechen }}
          </button>
        </div>
      } @else {
        @let z = ziel();
        @let v = verweise();
        <p data-test="rueckfrage-text">
          {{ texte.rueckfrageText(quelleName(), z ? zielName(z) : '') }}
        </p>
        @if (v) {
          <p data-test="rueckfrage-zahlen">
            {{
              texte.rueckfrageZahlen(
                v.dokumente ?? 0,
                v.verkaufschancen ?? 0,
                v.korrekturbeispiele ?? 0
              )
            }}
          </p>
        }
        <p class="text" data-test="rueckfrage-grund">{{ texte.rueckfrageGrund(grund().trim()) }}</p>
        <p class="fehler" data-test="nicht-umkehrbar">{{ texte.nichtUmkehrbar }}</p>
        @if (fehler(); as text) {
          <p class="fehler" role="alert" data-test="aktion-fehler">{{ text }}</p>
        }
        <div class="knoepfe">
          <button
            mat-flat-button
            type="button"
            data-test="zusammenfuehren-ausfuehren"
            [disabled]="sendet()"
            (click)="ausfuehren()"
          >
            {{ sendet() ? texte.sendet : texte.zusammenfuehrenAusfuehren }}
          </button>
          <button
            mat-button
            type="button"
            data-test="rueckfrage-zurueck"
            [disabled]="sendet()"
            (click)="phase.set('auswahl')"
          >
            {{ texte.zurueck }}
          </button>
          <button
            mat-button
            type="button"
            data-test="rueckfrage-abbrechen"
            [disabled]="sendet()"
            (click)="abbrechen()"
          >
            {{ texte.abbrechen }}
          </button>
        </div>
      }
    </app-kontakt-dialog>
  `,
  styles: `
    .vorschlaege {
      list-style: none;
      margin: 0.25rem 0 0.75rem;
      padding: 0;
    }
    .vorschlag {
      max-width: 100%;
      height: auto;
      min-height: 44px;
      white-space: normal;
      text-align: left;
      overflow-wrap: anywhere;
    }
    .gewaehlt {
      margin: 0.5rem 0;
    }
    .feld {
      margin: 0.75rem 0 0.25rem;
    }
  `,
  styleUrl: './kontakte-gemeinsam.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KontaktZusammenfuehrenDialog {
  private readonly port = inject(KontaktePort);

  readonly kontakt = input.required<KontaktDetail>();
  readonly erledigt = output<Zusammengefuehrt>();
  /** Die Version war veraltet (412/428): Der Aufrufer lädt das Detail neu. */
  readonly veraltet = output<void>();
  readonly geschlossen = output<void>();

  protected readonly texte = TEXTE.kontakte;
  protected readonly phase = signal<'auswahl' | 'rueckfrage'>('auswahl');
  protected readonly zielEingabe = signal('');
  protected readonly vorschlaege = signal<Kontakt[] | null>(null);
  protected readonly ziel = signal<Kontakt | null>(null);
  protected readonly grund = signal('');
  protected readonly verweise = signal<KontaktVerweise | null>(null);
  protected readonly laedtVerweise = signal(false);
  protected readonly sendet = signal(false);
  protected readonly fehler = signal<string | null>(null);
  protected readonly auswahlFehler = signal('');
  protected readonly quelleName = computed(() => kontaktName(this.kontakt()));
  private readonly zielSuche = new Subject<string>();
  private laufend?: Subscription;

  constructor() {
    const suche = this.zielSuche
      .pipe(
        map((q) => q.trim()),
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) =>
          q.length < ZIEL_MIN_ZEICHEN
            ? of(null)
            : this.port.suchen(q).pipe(catchError(() => of([] as Kontakt[]))),
        ),
      )
      .subscribe((liste) =>
        // die Quelle ist nie ihr eigenes Ziel; das Archiv liefert nur aktive Kontakte (Req 17.7)
        this.vorschlaege.set(
          liste === null ? null : liste.filter((k) => k.id !== this.kontakt().id).slice(0, 10),
        ),
      );
    inject(DestroyRef).onDestroy(() => {
      suche.unsubscribe();
      this.laufend?.unsubscribe();
    });
  }

  protected zielTippen(text: string): void {
    this.zielEingabe.set(text);
    this.zielSuche.next(text);
  }

  protected auswahlText(k: Kontakt): string {
    return kontaktAuswahlText(k);
  }

  protected zielName(k: Kontakt): string {
    return kontaktName(k);
  }

  protected weiter(): void {
    const z = this.ziel();
    const k = this.kontakt();
    this.fehler.set(null);
    if (!z?.id) {
      this.auswahlFehler.set(this.texte.zielFehlt);
      return;
    }
    const grund = this.grund().trim();
    if (grund === '') {
      this.auswahlFehler.set(this.texte.grundFehlt);
      return;
    }
    if (grund.length > GRUND_MAX) {
      this.auswahlFehler.set(this.texte.grundZuLang);
      return;
    }
    this.auswahlFehler.set('');
    if (!k.id) {
      return;
    }
    this.laedtVerweise.set(true);
    this.laufend = this.port.verweise(k.id).subscribe({
      next: (v) => {
        this.verweise.set(v);
        this.laedtVerweise.set(false);
        this.phase.set('rueckfrage');
      },
      error: (f: unknown) => {
        this.laedtVerweise.set(false);
        this.fehler.set(aktionFehlerText(f, 'zusammenfuehren'));
      },
    });
  }

  protected ausfuehren(): void {
    const k = this.kontakt();
    const z = this.ziel();
    if (this.sendet() || !k.id || !z?.id) {
      return;
    }
    this.sendet.set(true);
    this.fehler.set(null);
    this.laufend = this.port
      .zusammenfuehren(k.id, k.version ?? 0, z.id, this.grund().trim())
      .subscribe({
        next: (zahlen) => {
          this.sendet.set(false);
          this.erledigt.emit({ ziel: { id: z.id!, name: kontaktName(z) }, zahlen });
        },
        error: (f: unknown) => {
          this.sendet.set(false);
          const art = fehlerArt(f);
          if (art === 'veraltet' || art === 'versionFehlt') {
            this.veraltet.emit();
            return;
          }
          this.fehler.set(aktionFehlerText(f, 'zusammenfuehren'));
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
