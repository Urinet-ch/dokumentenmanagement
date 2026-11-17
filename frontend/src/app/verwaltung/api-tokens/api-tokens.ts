import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ApiToken, APITokensService } from '../../gemeinsam/api';
import { zeitpunkt } from '../../gemeinsam/format';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import {
  ablaufdatumGueltig,
  ablaufdatumText as zeigeAblaufdatum,
  heuteInZurich,
  langeUngenutzt,
  scopeText,
  sortiereApiTokens,
  spaetestesAblaufdatum,
  tokenStatus,
} from './api-tokens-logik';

@Component({
  selector: 'app-api-tokens',
  imports: [MatButtonModule],
  templateUrl: './api-tokens.html',
  styleUrl: './api-tokens.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApiTokens implements OnInit {
  private readonly apiTokens = inject(APITokensService);

  protected readonly texte = TEXTE.apiTokens;
  protected readonly neuLaden = TEXTE.eingangskorb.neuLaden;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly scopeText = scopeText;
  protected readonly tokenStatus = tokenStatus;
  protected readonly ablaufdatumText = zeigeAblaufdatum;
  protected readonly ablaufdatumGueltig = ablaufdatumGueltig;
  protected readonly langeUngenutzt = langeUngenutzt;
  protected readonly heute = heuteInZurich();
  protected readonly spaetestesAblaufdatum = spaetestesAblaufdatum(this.heute);
  protected readonly tokens = signal<readonly ApiToken[] | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly meldung = signal<string | null>(null);
  protected readonly bestaetigung = signal<string | null>(null);
  protected readonly widerrufLaeuft = signal<string | null>(null);
  protected readonly ablaufLaeuft = signal<string | null>(null);
  protected readonly ablaufWerte = signal<Record<string, string>>({});

  ngOnInit(): void {
    this.laden();
  }

  protected laden(): void {
    this.fehler.set(null);
    this.tokens.set(null);
    this.apiTokens.apiV1ApiTokensGet().subscribe({
      next: (tokens) => {
        this.ablaufWerte.set({});
        this.tokens.set(sortiereApiTokens(tokens));
      },
      error: (fehler: unknown) => this.fehler.set(this.ladeFehlermeldung(fehler)),
    });
  }

  protected bestaetigungAnzeigen(token: ApiToken): void {
    if (token.aktiv === true && token.id && !this.widerrufLaeuft()) {
      this.fehler.set(null);
      this.meldung.set(null);
      this.bestaetigung.set(token.id);
    }
  }

  protected abbrechen(): void {
    if (!this.widerrufLaeuft()) {
      this.bestaetigung.set(null);
    }
  }

  protected widerrufen(token: ApiToken): void {
    const id = token.id;
    if (token.aktiv !== true || !id || this.widerrufLaeuft() || this.bestaetigung() !== id) {
      return;
    }
    this.widerrufLaeuft.set(id);
    this.fehler.set(null);
    this.meldung.set(null);
    this.apiTokens.apiV1ApiTokensTokenIdDelete(id).subscribe({
      next: () => {
        this.bestaetigung.set(null);
        this.meldung.set(this.texte.widerrufErfolgreich);
        this.laden();
        this.widerrufLaeuft.set(null);
      },
      error: (fehler: unknown) => {
        this.bestaetigung.set(null);
        this.widerrufLaeuft.set(null);
        if (
          fehler instanceof HttpErrorResponse &&
          fehler.status === 404 &&
          this.problemcode(fehler) === 'NICHT_GEFUNDEN'
        ) {
          this.meldung.set(this.texte.nichtMehrVorhanden);
          this.laden();
        } else {
          this.fehler.set(this.widerrufFehlermeldung(fehler));
        }
      },
    });
  }

  protected statusText(token: ApiToken): string {
    switch (tokenStatus(token)) {
      case 'aktiv':
        return this.texte.aktiv;
      case 'abgelaufen':
        return this.texte.abgelaufen;
      default:
        return this.texte.widerrufenStatus;
    }
  }

  protected ablaufBearbeitbar(token: ApiToken): boolean {
    return (
      token.aktiv === true &&
      !!token.id &&
      (token.scope === 'generator' || token.scope === 'import')
    );
  }

  protected ablaufWert(token: ApiToken): string {
    if (!token.id) {
      return '';
    }
    return this.ablaufWerte()[token.id] ?? this.ablaufdatumText(token) ?? '';
  }

  protected ablaufdatumAendern(token: ApiToken, ereignis: Event): void {
    const eingabe = ereignis.target;
    if (!token.id || !(eingabe instanceof HTMLInputElement)) {
      return;
    }
    const wert = eingabe.value;
    this.ablaufWerte.update((werte) => ({ ...werte, [token.id!]: wert }));
    this.fehler.set(null);
  }

  protected ablaufSpeichern(token: ApiToken): void {
    const datum = this.ablaufWert(token);
    if (!this.ablaufBearbeitbar(token) || !ablaufdatumGueltig(datum, this.heute)) {
      this.fehler.set(this.texte.ablaufdatumUngueltig);
      return;
    }
    this.ablaufAendern(token, datum);
  }

  protected ablaufEntfernen(token: ApiToken): void {
    if (this.ablaufBearbeitbar(token)) {
      this.ablaufAendern(token, null);
    }
  }

  private ablaufAendern(token: ApiToken, datum: string | null): void {
    const id = token.id;
    if (!id || this.ablaufLaeuft()) {
      return;
    }
    this.ablaufLaeuft.set(id);
    this.fehler.set(null);
    this.meldung.set(null);
    const anfrage =
      datum === null
        ? this.apiTokens.apiV1ApiTokensTokenIdGueltigBisDelete(id)
        : this.apiTokens.apiV1ApiTokensTokenIdGueltigBisPut(id, { gueltigBis: datum });
    anfrage.subscribe({
      next: () => {
        this.meldung.set(
          datum === null ? this.texte.ablaufEntfernt : this.texte.ablaufGespeichert,
        );
        this.ablaufLaeuft.set(null);
        this.laden();
      },
      error: (fehler: unknown) => {
        this.ablaufLaeuft.set(null);
        if (
          fehler instanceof HttpErrorResponse &&
          fehler.status === 404 &&
          this.problemcode(fehler) === 'NICHT_GEFUNDEN'
        ) {
          this.meldung.set(this.texte.nichtMehrVorhanden);
          this.laden();
        } else if (
          fehler instanceof HttpErrorResponse &&
          fehler.status === 400 &&
          this.problemcode(fehler) === 'ANFRAGE_UNGUELTIG'
        ) {
          this.fehler.set(this.texte.ablaufdatumUngueltig);
        } else if (fehler instanceof HttpErrorResponse && fehler.status === 403) {
          this.fehler.set(this.texte.fehlerRecht);
        } else {
          this.fehler.set(this.texte.fehlerAblauf);
        }
      },
    });
  }

  private ladeFehlermeldung(fehler: unknown): string {
    if (!(fehler instanceof HttpErrorResponse)) {
      return this.texte.fehlerLaden;
    }
    if (fehler.status === 401) {
      return this.texte.fehlerSitzung;
    }
    if (fehler.status === 403) {
      return this.texte.fehlerRecht;
    }
    return fehler.status === 0 ? this.texte.fehlerVerbindung : this.texte.fehlerLaden;
  }

  private widerrufFehlermeldung(fehler: unknown): string {
    if (fehler instanceof HttpErrorResponse && fehler.status === 403) {
      return this.texte.fehlerRecht;
    }
    return this.texte.fehlerAllgemein;
  }

  private problemcode(fehler: unknown): string | null {
    if (
      !(fehler instanceof HttpErrorResponse) ||
      typeof fehler.error !== 'object' ||
      fehler.error === null
    ) {
      return null;
    }
    const code = (fehler.error as Record<string, unknown>)['fehlercode'];
    return typeof code === 'string' ? code : null;
  }
}
