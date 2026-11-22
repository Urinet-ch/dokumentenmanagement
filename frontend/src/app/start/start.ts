import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { BrandingService } from '../gemeinsam/branding.service';
import { EingangskorbZaehler } from '../gemeinsam/eingangskorb-zaehler';
import { MENUE, abschnitte } from '../gemeinsam/navigation';
import { Symbol } from '../gemeinsam/symbol';
import { ONLINE } from '../gemeinsam/online';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { einfuehrungSchritte } from '../hilfe/einfuehrung-schritte';
import { EinfuehrungVermerk } from '../hilfe/einfuehrung-vermerk';
import { HilfeLeiste } from '../hilfe/hilfe-leiste.service';
import { VermerkArt } from '../hilfe/einfuehrung-vermerk-speicher';

@Component({
  selector: 'app-start',
  imports: [MatButtonModule, RouterLink, Symbol],
  template: `
    @if (anmeldung.angemeldet()) {
      <div class="seitenkopf">
        <p class="vorzeile">{{ branding.branding().name }}</p>
        <h1 tabindex="-1">{{ texte.start.gruss(vorname()) }}</h1>
        @if (abschnitte().length > 0) {
          <p>{{ texte.start.untertitel }}</p>
        }
      </div>
      <div role="status">
        @if (meldung()) {
          <div class="hinweis ok"><p>{{ meldung() }}</p></div>
        }
      </div>
      @if ((vermerk.bekannt() === false || vermerk.erneut()) && schritte()[vermerk.schritt()]; as schritt) {
        <section class="karte einfuehrung" aria-labelledby="einfuehrung-titel">
          @if (branding.branding().logoUrl; as logoUrl) {
            <img class="einfuehrung-logo" [src]="logoUrl" alt="" />
          }
          <p>{{ texte.einfuehrung.fortschritt(vermerk.schritt() + 1, schritte().length) }}</p>
          <h2 id="einfuehrung-titel" tabindex="-1">{{ schritt.titel }}</h2>
          <p>{{ schritt.text }}</p>
          @if (schritt.bereiche; as bereiche) {
            <ul>
              @for (bereich of bereiche; track bereich.text) {
                <li><b>{{ bereich.text }}</b>: {{ bereich.beschreibung }}</li>
              }
            </ul>
          }
          <button mat-button type="button" (click)="anleitungOeffnen(schritt.anker, $event)">{{ texte.einfuehrung.anleitung }}</button>
          <div class="einfuehrung-aktionen">
            <button mat-button type="button" (click)="beenden('UEBERSPRUNGEN')">{{ texte.einfuehrung.ueberspringen }}</button>
            @if (vermerk.schritt() > 0) {
              <button mat-button type="button" (click)="wechseln(-1)">{{ texte.einfuehrung.zurueck }}</button>
            }
            @if (vermerk.schritt() + 1 < schritte().length) {
              <button mat-flat-button type="button" (click)="wechseln(1)">{{ texte.einfuehrung.weiter }}</button>
            } @else {
              <button mat-flat-button type="button" (click)="beenden('ABGESCHLOSSEN')">{{ texte.einfuehrung.fertig }}</button>
            }
          </div>
        </section>
      }
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
    .einfuehrung {
      margin-bottom: 1.5rem;
    }
    .einfuehrung-logo {
      max-width: 120px;
      max-height: 60px;
      object-fit: contain;
    }
    .einfuehrung-aktionen {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      margin-top: 1rem;
    }
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
  protected readonly vermerk = inject(EinfuehrungVermerk);
  protected readonly hilfe = inject(HilfeLeiste);
  protected readonly meldung = signal('');
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly fokus = signal<{ ziel: string } | null>(null);
  private readonly wiederaufruf = signal(false);
  /** Ohne Verbindung ist keine Anmeldung möglich; «Erfassen» nimmt dann Aufnahmen in die Warteschlange (Req 4.4). */
  protected readonly online = inject(ONLINE);
  /** Ziel nach der Anmeldung (vom Guard gesetzt). */
  readonly ziel = input<string>();

  protected readonly abschnitte = computed(() => abschnitte(MENUE.filter((punkt) => this.anmeldung.darf(punkt.aktion))));
  /** Anzahl im Eingangskorb, wo vorhanden (Req 8.6); fehlt sie, zeigt die Kachel ihre Beschreibung. */
  protected readonly anzahl = computed(() => this.zaehler?.anzahl() ?? null);
  protected readonly vorname = computed(() => this.anmeldung.benutzer()?.name.trim().split(/\s+/)[0] ?? '');
  protected readonly schritte = computed(() => {
    const rolle = this.anmeldung.benutzer()?.rolle;
    return rolle ? einfuehrungSchritte((aktion) => this.anmeldung.darf(aktion), this.branding.branding().name, this.texte.kopf.rollen[rolle]) : [];
  });
  private readonly zaehler = inject(EingangskorbZaehler, { optional: true });

  constructor() {
    inject(ActivatedRoute).queryParamMap.pipe(takeUntilDestroyed()).subscribe((parameter) => {
      this.wiederaufruf.set(parameter.has('einfuehrung'));
    });
    effect(() => {
      const benutzer = this.anmeldung.benutzer();
      if (this.wiederaufruf() && this.anmeldung.angemeldet() && benutzer?.id && benutzer.mandant && benutzer.rolle) {
        untracked(() => this.vermerk.neuBeginnen());
        this.wiederaufruf.set(false);
      }
    });
    afterRenderEffect(() => {
      const fokus = this.fokus();
      if (fokus) {
        untracked(() => {
          this.host.nativeElement.querySelector<HTMLElement>(fokus.ziel)?.focus();
          this.fokus.set(null);
        });
      }
    });
  }

  protected wechseln(richtung: number): void {
    this.vermerk.schrittSetzen(this.vermerk.schritt() + richtung);
    this.fokus.set({ ziel: '#einfuehrung-titel' });
  }

  protected anleitungOeffnen(anker: string, ereignis: MouseEvent): void {
    if (ereignis.currentTarget instanceof HTMLElement) this.hilfe.oeffnen(anker, ereignis.currentTarget);
  }

  protected beenden(art: VermerkArt): void {
    void this.vermerk.ablegen(art);
    this.meldung.set(art === 'ABGESCHLOSSEN' ? this.texte.einfuehrung.abgeschlossen : this.texte.einfuehrung.uebersprungen);
    this.fokus.set({ ziel: '.seitenkopf h1' });
  }

  protected anmelden(): void {
    void this.anmeldung.anmelden(this.ziel() ?? '/');
  }
}
