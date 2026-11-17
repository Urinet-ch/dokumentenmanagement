import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router } from '@angular/router';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';

/** Rückkehr von Keycloak (redirect_uri `/anmeldung`): Code gegen Tokens tauschen, Code aus der Adresse entfernen. */
@Component({
  selector: 'app-rueckruf',
  imports: [MatButtonModule],
  template: `
    @if (fehlgeschlagen()) {
      <p role="alert">{{ fehlermeldung() }}</p>
      <button mat-flat-button type="button" (click)="anmelden()">{{ texte.anmelden }}</button>
    } @else {
      <p role="status">{{ texte.laeuft }}</p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Rueckruf implements OnInit {
  private readonly anmeldung = inject(AnmeldungService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly texte = TEXTE.anmeldung;
  protected readonly fehlgeschlagen = signal(false);
  protected readonly fehlermeldung = signal<string>(TEXTE.anmeldung.fehlgeschlagen);

  async ngOnInit(): Promise<void> {
    const parameter = new URLSearchParams();
    const abfrage = this.route.snapshot.queryParamMap;
    for (const name of abfrage.keys) {
      parameter.set(name, abfrage.get(name) ?? '');
    }
    try {
      const ziel = await this.anmeldung.rueckrufVerarbeiten(parameter);
      await this.router.navigateByUrl(ziel, { replaceUrl: true });
    } catch (fehler: unknown) {
      this.fehlgeschlagen.set(true);
      this.fehlermeldung.set(
        fehler instanceof HttpErrorResponse ? httpFehlerMeldung(fehler) : this.texte.fehlgeschlagen,
      );
      await this.router.navigate([], { replaceUrl: true, queryParams: {} });
    }
  }

  protected anmelden(): void {
    void this.anmeldung.anmelden('/');
  }
}
