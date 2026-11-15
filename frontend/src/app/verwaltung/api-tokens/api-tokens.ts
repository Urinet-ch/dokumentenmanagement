import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ApiToken, APITokensService } from '../../gemeinsam/api';
import { zeitpunkt } from '../../gemeinsam/format';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { scopeText, sortiereApiTokens, tokenStatus } from './api-tokens-logik';

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
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly scopeText = scopeText;
  protected readonly tokenStatus = tokenStatus;
  protected readonly tokens = signal<readonly ApiToken[] | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly meldung = signal<string | null>(null);
  protected readonly bestaetigung = signal<string | null>(null);
  protected readonly widerrufLaeuft = signal<string | null>(null);

  ngOnInit(): void {
    this.laden();
  }

  protected laden(): void {
    this.fehler.set(null);
    this.tokens.set(null);
    this.apiTokens.apiV1ApiTokensGet().subscribe({
      next: (tokens) => this.tokens.set(sortiereApiTokens(tokens)),
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
    return tokenStatus(token) === 'aktiv' ? this.texte.aktiv : this.texte.widerrufenStatus;
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
