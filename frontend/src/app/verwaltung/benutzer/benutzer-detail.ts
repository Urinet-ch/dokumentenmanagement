import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { BenutzerAnsicht, BenutzerverwaltungService, VerwaltungRolle } from '../../gemeinsam/api';
import { zeitpunkt } from '../../gemeinsam/format';
import { httpFehlerMeldung } from '../../gemeinsam/http-fehler';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { name, rollen } from './benutzer-logik';
import { BenutzerRechte } from './benutzer-rechte';

@Component({
  selector: 'app-benutzer-detail',
  imports: [MatButtonModule, RouterLink, BenutzerRechte],
  templateUrl: './benutzer-detail.html',
  styleUrl: './benutzer.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerDetail {
  private readonly api = inject(BenutzerverwaltungService);
  private readonly router = inject(Router);
  private readonly zerstoert = inject(DestroyRef);
  private laufend?: Subscription;
  private rechteAnfrage?: Subscription;
  private id = '';

  protected readonly texte = TEXTE.benutzer;
  protected readonly protokollAktionen = TEXTE.protokoll.aktionen;
  protected readonly gruppe = TEXTE.menueGruppen.verwaltung;
  protected readonly name = name;
  protected readonly rollen = rollen;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly benutzer = signal<BenutzerAnsicht | null>(null);
  protected readonly rechte = signal<VerwaltungRolle | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly rechteFehler = signal<string | null>(null);

  constructor() {
    inject(ActivatedRoute).paramMap.pipe(takeUntilDestroyed(this.zerstoert)).subscribe((parameter) => {
      this.id = parameter.get('id') ?? '';
      this.laden();
    });
  }

  protected laden(): void {
    this.laufend?.unsubscribe();
    this.rechteAnfrage?.unsubscribe();
    this.benutzer.set(null);
    this.rechte.set(null);
    this.fehler.set(null);
    this.rechteFehler.set(null);
    this.laufend = this.api.apiV1BenutzerBenutzerIdGet(this.id)
      .pipe(takeUntilDestroyed(this.zerstoert))
      .subscribe({
        next: (benutzer) => {
          this.benutzer.set(benutzer);
          if (!benutzer.rollenkonflikt) this.rechteLaden();
        },
        error: (fehler: unknown) => this.fehlerBehandeln(fehler, this.fehler),
      });
  }

  protected rechteLaden(): void {
    this.rechteAnfrage?.unsubscribe();
    this.rechte.set(null);
    this.rechteFehler.set(null);
    this.rechteAnfrage = this.api.apiV1BenutzerBenutzerIdRechteGet(this.id)
      .pipe(takeUntilDestroyed(this.zerstoert))
      .subscribe({
        next: (rechte) => this.rechte.set(rechte),
        error: (fehler: unknown) => this.fehlerBehandeln(fehler, this.rechteFehler),
      });
  }

  private fehlerBehandeln(fehler: unknown, ziel: { set(text: string): void }): void {
    if (fehler instanceof HttpErrorResponse && (fehler.status === 403 || fehler.status === 404)) {
      this.benutzer.set(null);
      this.rechte.set(null);
      void this.router.navigateByUrl('/nicht-gefunden');
    } else {
      ziel.set(httpFehlerMeldung(fehler));
    }
  }
}
