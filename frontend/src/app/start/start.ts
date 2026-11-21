import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { BrandingService } from '../gemeinsam/branding.service';
import { EingangskorbZaehler } from '../gemeinsam/eingangskorb-zaehler';
import { MENUE, abschnitte } from '../gemeinsam/navigation';
import { Symbol } from '../gemeinsam/symbol';
import { ONLINE } from '../gemeinsam/online';
import { TEXTE } from '../gemeinsam/texte.de-CH';

@Component({
  selector: 'app-start',
  imports: [MatButtonModule, RouterLink, Symbol],
  template: `
    @if (anmeldung.angemeldet()) {
      <div class="seitenkopf">
        <p class="vorzeile">{{ branding.branding().name }}</p>
        <h1>{{ texte.start.gruss(vorname()) }}</h1>
        @if (abschnitte().length > 0) {
          <p>{{ texte.start.untertitel }}</p>
        }
      </div>
      @if (abschnitte().length > 0) {
        <h2 class="nur-screenreader">{{ texte.anmeldung.funktionen }}</h2>
        @for (abschnitt of abschnitte(); track abschnitt.gruppe) {
          <section class="abschnitt" [attr.aria-labelledby]="'abschnitt-' + abschnitt.gruppe">
            <h3 class="gruppe" [id]="'abschnitt-' + abschnitt.gruppe">{{ abschnitt.titel }}</h3>
            <ul class="kacheln">
              @for (punkt of abschnitt.punkte; track punkt.pfad) {
                <li>
                  <a class="karte funktion" [class.hervor]="punkt.eingangskorbZaehler && (anzahl() ?? 0) > 0" [routerLink]="punkt.pfad">
                    <span class="symbol"><app-symbol [name]="punkt.symbol" /></span>
                    <span class="text">
                      <b>{{ punkt.text }}</b>
                      <small>
                        @if (punkt.eingangskorbZaehler && anzahl() !== null) {
                          {{ texte.start.imEingangskorb(anzahl()!) }}
                        } @else {
                          {{ texte.start.beschreibung[punkt.pfad] }}
                        }
                      </small>
                    </span>
                  </a>
                </li>
              }
            </ul>
          </section>
        }
      } @else {
        <p role="alert">{{ texte.anmeldung.ohneZugang }}</p>
      }
    } @else {
      <div class="karte willkommen">
        <span class="zeichen"><app-symbol name="archiv" /></span>
        <h1>{{ branding.branding().name }}</h1>
        @switch (anmeldung.zustand()) {
          @case ('abgelaufen') {
            <p role="alert">{{ texte.anmeldung.abgelaufen }}</p>
          }
          @default {
            <p>{{ texte.anmeldung.hinweis }}</p>
          }
        }
        <button mat-flat-button type="button" (click)="anmelden()">{{ texte.anmeldung.anmelden }}</button>
        @if (!online()) {
          <p><a routerLink="/erfassen">{{ texte.handy.offlineErfassen }}</a></p>
        }
      </div>
    }
  `,
  styles: `
    .abschnitt {
      margin-bottom: 1.5rem;
    }
    .gruppe {
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.09em;
      text-transform: uppercase;
      color: var(--text-gedaempft);
      margin: 0 0 0.625rem;
    }
    .kacheln {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 240px), 1fr));
      gap: 14px;
    }
    .funktion {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      height: 100%;
      box-sizing: border-box;
      color: var(--text);
      text-decoration: none;
      transition: border-color 0.15s;
      &:hover {
        border-color: var(--linie-stark);
      }
    }
    .funktion.hervor {
      background: var(--text);
      color: var(--flaeche);
      border-color: var(--text);
      small {
        color: inherit;
        opacity: 0.75;
      }
      .symbol {
        background: var(--urinet-gelb);
        border-color: var(--urinet-gelb);
        color: var(--urinet-schwarz);
      }
    }
    .symbol {
      width: 34px;
      height: 34px;
      flex: none;
      display: grid;
      place-items: center;
      border-radius: 9px;
      border: 1px solid var(--linie);
      background: var(--karte-2);
    }
    .text {
      display: grid;
      gap: 2px;
      min-width: 0;
      b {
        font-weight: 600;
      }
      small {
        color: var(--text-gedaempft);
        font-size: 12.5px;
      }
    }
    .willkommen {
      max-width: 420px;
      margin: 8vh auto 0;
      padding: 2rem 1.5rem;
      display: grid;
      justify-items: center;
      gap: 0.75rem;
      text-align: center;
      h1 {
        margin: 0;
        font-size: 24px;
      }
      p {
        margin: 0;
        color: var(--text-2);
      }
    }
    .zeichen {
      width: 52px;
      height: 52px;
      border-radius: 14px;
      display: grid;
      place-items: center;
      background: var(--urinet-gelb);
      color: var(--urinet-schwarz);
      --symbol-groesse: 28px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Start {
  protected readonly anmeldung = inject(AnmeldungService);
  protected readonly texte = TEXTE;
  protected readonly branding = inject(BrandingService);
  /** Ohne Verbindung ist keine Anmeldung möglich; «Erfassen» nimmt dann Aufnahmen in die Warteschlange (Req 4.4). */
  protected readonly online = inject(ONLINE);
  /** Ziel nach der Anmeldung (vom Guard gesetzt). */
  readonly ziel = input<string>();

  protected readonly abschnitte = computed(() => abschnitte(MENUE.filter((punkt) => this.anmeldung.darf(punkt.aktion))));
  /** Anzahl im Eingangskorb, wo vorhanden (Req 8.6); fehlt sie, zeigt die Kachel ihre Beschreibung. */
  protected readonly anzahl = computed(() => this.zaehler?.anzahl() ?? null);
  protected readonly vorname = computed(() => this.anmeldung.benutzer()?.name.trim().split(/\s+/)[0] ?? '');
  private readonly zaehler = inject(EingangskorbZaehler, { optional: true });

  protected anmelden(): void {
    void this.anmeldung.anmelden(this.ziel() ?? '/');
  }
}
