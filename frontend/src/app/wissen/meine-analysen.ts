import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';

interface Eintrag { id: string; status: string }
interface Entscheidung { nr: number; status: string; grund?: string | null }
interface VollerEintrag { entwurf: Eintrag & { anlass: string; erstelltVon: string } }
interface Vollansicht extends VollerEintrag { aenderungen: Entscheidung[] }
interface Statusansicht extends Eintrag { entscheidungen: Entscheidung[] }

@Component({
  selector: 'app-meine-analysen',
  imports: [RouterLink, MatButtonModule],
  template: `
    <a routerLink="/chat">{{ texte.titel }}</a>
    <div class="seitenkopf"><h1>{{ texte.analysen }}</h1></div>
    @if (id()) { <a routerLink="/wissen/entwuerfe/meine">{{ texte.analyseZurListe }}</a> }
    @if (laedt()) { <p role="status">{{ wissen.laedt }}</p> }
    @if (fehler(); as meldung) {
      <p role="alert">{{ meldung }}</p>
      <button mat-button type="button" (click)="laden()">{{ wissen.erneut }}</button>
    }
    @if (!id() && !laedt() && !fehler()) {
      @if (liste().length === 0) { <p>{{ texte.analyseLeer }}</p> }
      <ul>
        @for (eintrag of liste(); track eintrag.id) {
          <li><a [routerLink]="['/wissen/entwuerfe/meine', eintrag.id]">{{ eintrag.id }}</a> · {{ eintrag.status }}</li>
        }
      </ul>
    }
    @if (ansicht(); as daten) {
      <p>{{ wissen.entwuerfe.status }}: {{ daten.status }}</p>
      <ul>
        @for (entscheid of daten.entscheidungen; track entscheid.nr) {
          <li>{{ texte.analyseEntscheid }} {{ entscheid.nr }}: {{ entscheid.status }}
            @if (entscheid.grund) { <p>{{ texte.analyseGrund }}: {{ entscheid.grund }}</p> }
          </li>
        }
      </ul>
    }
  `,
  styleUrl: './wissen.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MeineAnalysen {
  private readonly http = inject(HttpClient);
  private readonly anmeldung = inject(AnmeldungService);
  private laufend?: Subscription;
  readonly id = input<string>();
  protected readonly texte = TEXTE.chat;
  protected readonly wissen = TEXTE.wissen;
  protected readonly liste = signal<Eintrag[]>([]);
  protected readonly ansicht = signal<Statusansicht | null>(null);
  protected readonly laedt = signal(false);
  protected readonly fehler = signal<string | null>(null);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.laufend?.unsubscribe());
    effect(() => {
      this.id();
      this.anmeldung.benutzer();
      untracked(() => this.laden());
    });
  }

  protected laden(): void {
    this.laufend?.unsubscribe();
    this.liste.set([]);
    this.ansicht.set(null);
    this.fehler.set(null);
    this.laedt.set(true);
    const id = this.id();
    if (id) {
      this.laufend = this.http.get<Statusansicht | Vollansicht>(`/api/v1/wissensentwuerfe/${encodeURIComponent(id)}`)
        .subscribe({
          next: (daten) => {
            this.ansicht.set('entwurf' in daten
              ? { ...daten.entwurf, entscheidungen: daten.aenderungen }
              : daten);
            this.laedt.set(false);
          },
          error: (fehler: unknown) => this.gescheitert(fehler),
        });
    } else {
      this.laufend = this.http.get<(Eintrag | VollerEintrag)[]>('/api/v1/wissensentwuerfe').subscribe({
        next: (daten) => {
          this.liste.set(daten.flatMap((eintrag) => {
            if (!('entwurf' in eintrag)) return [eintrag];
            const kopf = eintrag.entwurf;
            return kopf.anlass === 'ANALYSE' && kopf.erstelltVon === this.anmeldung.benutzer()?.id ? [kopf] : [];
          }));
          this.laedt.set(false);
        },
        error: (fehler: unknown) => this.gescheitert(fehler),
      });
    }
  }

  private gescheitert(fehler: unknown): void {
    this.laedt.set(false);
    this.fehler.set(httpFehlerMeldung(fehler));
  }
}
