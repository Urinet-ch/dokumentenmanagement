import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Ansicht, BenutzerAnsicht, BenutzerverwaltungService, VerwaltungRolle } from '../../gemeinsam/api';
import { zeitpunkt } from '../../gemeinsam/format';
import { httpFehlerMeldung } from '../../gemeinsam/http-fehler';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { name, rollen } from './benutzer-logik';
import { BenutzerRechte } from './benutzer-rechte';

const SEITENGROESSE = 50;

@Component({
  selector: 'app-benutzer-liste',
  imports: [MatButtonModule, RouterLink, BenutzerRechte],
  templateUrl: './benutzer-liste.html',
  styleUrl: './benutzer.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerListe implements OnInit {
  private readonly api = inject(BenutzerverwaltungService);
  private readonly router = inject(Router);
  private readonly zerstoert = inject(DestroyRef);
  private laufend?: Subscription;
  private suche = '';

  protected readonly texte = TEXTE.benutzer;
  protected readonly gruppe = TEXTE.menueGruppen.verwaltung;
  protected readonly name = name;
  protected readonly rollen = rollen;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly benutzer = signal<readonly BenutzerAnsicht[] | null>(null);
  protected readonly rollenListe = signal<readonly VerwaltungRolle[] | null>(null);
  protected readonly antraege = signal<readonly Ansicht[] | null>(null);
  protected readonly seite = signal(0);
  protected readonly listenFehler = signal<string | null>(null);
  protected readonly rollenFehler = signal<string | null>(null);
  protected readonly antraegeFehler = signal<string | null>(null);

  ngOnInit(): void {
    this.laden(0);
    this.rollenLaden();
    this.antraegeLaden();
  }

  protected suchen(wert: string): void {
    this.suche = wert.trim();
    this.laden(0);
  }

  protected laden(seite: number): void {
    this.laufend?.unsubscribe();
    this.listenFehler.set(null);
    this.benutzer.set(null);
    this.seite.set(seite);
    this.laufend = this.api.apiV1BenutzerGet(SEITENGROESSE, this.suche || undefined, seite)
      .pipe(takeUntilDestroyed(this.zerstoert))
      .subscribe({
        next: (benutzer) => {
          this.benutzer.set(benutzer);
        },
        error: (fehler: unknown) => this.fehlerBehandeln(fehler, this.listenFehler),
      });
  }

  protected hatWeitere(): boolean {
    return this.benutzer()?.length === SEITENGROESSE;
  }

  protected rollenLaden(): void {
    this.rollenFehler.set(null);
    this.rollenListe.set(null);
    this.api.apiV1RollenGet().pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
      next: (rollen) => this.rollenListe.set(rollen),
      error: (fehler: unknown) => this.fehlerBehandeln(fehler, this.rollenFehler),
    });
  }

  protected antraegeLaden(): void {
    this.antraegeFehler.set(null);
    this.antraege.set(null);
    this.api.apiV1BenutzerAntraegeGet().pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
      next: (antraege) => this.antraege.set(antraege),
      error: (fehler: unknown) => this.fehlerBehandeln(fehler, this.antraegeFehler),
    });
  }

  private fehlerBehandeln(fehler: unknown, ziel: { set(text: string): void }): void {
    if (fehler instanceof HttpErrorResponse && fehler.status === 403) {
      void this.router.navigateByUrl('/nicht-gefunden');
    } else {
      ziel.set(httpFehlerMeldung(fehler));
    }
  }
}
