import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { MENUE } from '../gemeinsam/navigation';
import { ONLINE } from '../gemeinsam/online';
import { TEXTE } from '../gemeinsam/texte.de-CH';

@Component({
  selector: 'app-start',
  imports: [MatButtonModule, RouterLink],
  template: `
    <h1>{{ texte.appTitel }}</h1>
    @if (anmeldung.angemeldet()) {
      @if (funktionen().length > 0) {
        <p>{{ texte.willkommen }}</p>
        <h2>{{ texte.anmeldung.funktionen }}</h2>
        <ul class="funktionen">
          @for (punkt of funktionen(); track punkt.pfad) {
            <li><a [routerLink]="punkt.pfad">{{ punkt.text }}</a></li>
          }
        </ul>
      } @else {
        <p role="alert">{{ texte.anmeldung.ohneZugang }}</p>
      }
    } @else {
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
    }
  `,
  styles: `
    h1 {
      font: var(--mat-sys-headline-small);
      margin-top: 0;
    }
    .funktionen {
      padding-left: 1.25rem;
      line-height: 2;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Start {
  protected readonly anmeldung = inject(AnmeldungService);
  protected readonly texte = TEXTE;
  /** Ohne Verbindung ist keine Anmeldung möglich; «Erfassen» nimmt dann Aufnahmen in die Warteschlange (Req 4.4). */
  protected readonly online = inject(ONLINE);
  /** Ziel nach der Anmeldung (vom Guard gesetzt). */
  readonly ziel = input<string>();

  protected readonly funktionen = computed(() => MENUE.filter((punkt) => this.anmeldung.darf(punkt.aktion)));

  protected anmelden(): void {
    void this.anmeldung.anmelden(this.ziel() ?? '/');
  }
}
