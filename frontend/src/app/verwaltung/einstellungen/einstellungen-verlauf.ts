import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { zeitpunkt } from '../../gemeinsam/format';
import { httpFehlerMeldung } from '../../gemeinsam/http-fehler';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { EinstellungsVergleich, EinstellungenVergleichFehler, einstellungenVergleichen } from './einstellungen-vergleich';

interface Version {
  version: number;
  zeit: string;
}

function istObjekt(wert: unknown): wert is Record<string, unknown> {
  return wert !== null && typeof wert === 'object' && !Array.isArray(wert);
}

function istVersion(wert: unknown): wert is Version {
  return istObjekt(wert) && typeof wert['version'] === 'number' && Number.isInteger(wert['version'])
    && wert['version'] > 0 && typeof wert['zeit'] === 'string' && !Number.isNaN(Date.parse(wert['zeit']));
}

@Component({
  selector: 'app-einstellungen-verlauf',
  imports: [MatButtonModule],
  template: `
    <section aria-labelledby="einstellungen-verlauf">
      <h2 id="einstellungen-verlauf">{{ texte.verlauf }}</h2>
      @if (fehler(); as meldung) {
        <div class="hinweis gefahr" role="alert">
          <p>{{ meldung }}</p>
          <button mat-button type="button" (click)="laden()">{{ texte.erneut }}</button>
        </div>
      } @else if (versionen(); as liste) {
        @if (liste.length === 0) {
          <p class="leer" role="status">{{ texte.keineVersionen }}</p>
        } @else {
          <div class="karte auswahl">
            <label for="version-alt">{{ texte.alteVersion }}</label>
            <select id="version-alt" #alt>
              @for (version of liste; track version.version) {
                <option [value]="version.version">{{ version.version }} · {{ zeitpunkt(version.zeit) }}</option>
              }
            </select>
            <label for="version-neu">{{ texte.neueVersion }}</label>
            <select id="version-neu" #neu>
              @for (version of liste; track version.version) {
                <option [value]="version.version" [selected]="$last">{{ version.version }} · {{ zeitpunkt(version.zeit) }}</option>
              }
            </select>
            <button mat-stroked-button type="button" [disabled]="vergleicht()"
                    (click)="vergleichen(alt.value, neu.value)">{{ texte.vergleichen }}</button>
          </div>
          @if (vergleicht()) {
            <p role="status">{{ texte.vergleichLaeuft }}</p>
          } @else if (vergleich(); as aenderungen) {
            @if (aenderungen.length === 0) {
              <p class="leer" role="status">{{ texte.keineAenderung }}</p>
            } @else {
              @for (aenderung of aenderungen; track aenderung.pfad) {
                <article class="karte aenderung">
                  <h3 class="mono">{{ aenderung.pfad }}</h3>
                  <dl>
                    <dt>{{ texte.vorher }}</dt><dd>{{ aenderung.alt }}</dd>
                    <dt>{{ texte.nachher }}</dt><dd>{{ aenderung.neu }}</dd>
                  </dl>
                </article>
              }
            }
          }
        }
      } @else {
        <p role="status">{{ texte.verlaufLaden }}</p>
      }
    </section>
  `,
  styles: `
    :host, section { display: block; min-width: 0; }
    h2 { margin: 0 0 1rem; }
    h3 { overflow-wrap: anywhere; }
    .auswahl { display: grid; gap: .5rem; }
    select { font: inherit; max-width: 100%; min-height: 2.75rem; }
    .aenderung { margin-top: 1rem; overflow-wrap: anywhere; }
    dl { display: grid; gap: .5rem; }
    dd { margin: 0; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EinstellungenVerlauf implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly zerstoert = inject(DestroyRef);
  private readonly pfad = '/api/v1/konfiguration/versionen';

  protected readonly texte = TEXTE.einstellungen;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly versionen = signal<Version[] | null>(null);
  protected readonly vergleich = signal<EinstellungsVergleich[] | null>(null);
  protected readonly vergleicht = signal(false);
  protected readonly fehler = signal<string | null>(null);

  ngOnInit(): void {
    this.laden();
  }

  protected laden(): void {
    this.fehler.set(null);
    this.versionen.set(null);
    this.vergleich.set(null);
    this.http.get<unknown>(this.pfad).pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
      next: (antwort) => {
        if (!Array.isArray(antwort) || !antwort.every(istVersion)
            || new Set(antwort.map((version) => version.version)).size !== antwort.length) {
          this.fehler.set(this.texte.vergleichFehler);
          return;
        }
        this.versionen.set([...antwort].sort((a, b) => a.version - b.version));
      },
      error: (fehler: unknown) => this.fehlerBehandeln(fehler),
    });
  }

  protected vergleichen(alt: string, neu: string): void {
    const altNummer = Number(alt);
    const neuNummer = Number(neu);
    const versionen = this.versionen();
    if (!versionen?.some((version) => version.version === altNummer)
        || !versionen.some((version) => version.version === neuNummer)) {
      this.fehler.set(this.texte.vergleichFehler);
      return;
    }
    this.vergleicht.set(true);
    this.vergleich.set(null);
    forkJoin([
      this.http.get<unknown>(`${this.pfad}/${altNummer}`),
      this.http.get<unknown>(`${this.pfad}/${neuNummer}`),
    ]).pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
      next: ([vorher, nachher]) => {
        this.vergleicht.set(false);
        if (!istObjekt(vorher) || !istObjekt(nachher)) {
          this.fehler.set(this.texte.vergleichFehler);
          return;
        }
        try {
          this.vergleich.set(einstellungenVergleichen(vorher['inhalt'], nachher['inhalt']));
        } catch (fehler: unknown) {
          if (!(fehler instanceof EinstellungenVergleichFehler)) {
            throw fehler;
          }
          this.fehler.set(this.texte.vergleichFehler);
        }
      },
      error: (fehler: unknown) => {
        this.vergleicht.set(false);
        this.fehlerBehandeln(fehler);
      },
    });
  }

  private fehlerBehandeln(fehler: unknown): void {
    if (fehler instanceof HttpErrorResponse && fehler.status === 403) {
      void this.router.navigateByUrl('/nicht-gefunden');
    } else {
      this.fehler.set(httpFehlerMeldung(fehler));
    }
  }
}
