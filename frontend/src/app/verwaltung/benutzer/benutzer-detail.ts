import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { AnmeldungService } from '../../gemeinsam/anmeldung/anmeldung.service';
import { BenutzerAnsicht, BenutzerverwaltungService, VerwaltungRolle } from '../../gemeinsam/api';
import { zeitpunkt } from '../../gemeinsam/format';
import { httpFehlerMeldung } from '../../gemeinsam/http-fehler';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerFortsetzen, BenutzerFortsetzung } from './benutzer-fortsetzung';
import { BenutzerLoeschen } from './benutzer-loeschen';
import { BenutzerProfil } from './benutzer-profil';
import { einladungAblauf, name, rollen } from './benutzer-logik';
import { BenutzerRechte } from './benutzer-rechte';
import { BenutzerRollenaktion } from './benutzer-rollenaktion';
import { BenutzerStatusaktionen } from './benutzer-statusaktionen';

@Component({
  selector: 'app-benutzer-detail',
  imports: [
    MatButtonModule, RouterLink, BenutzerLoeschen, BenutzerProfil, BenutzerRechte, BenutzerRollenaktion,
    BenutzerStatusaktionen,
  ],
  templateUrl: './benutzer-detail.html',
  styleUrl: './benutzer.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerDetail {
  private readonly api = inject(BenutzerverwaltungService);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly router = inject(Router);
  private readonly zerstoert = inject(DestroyRef);
  private readonly fortsetzen = inject(BenutzerFortsetzen);
  private laufend?: Subscription;
  private rechteAnfrage?: Subscription;
  private rollenAnfrage?: Subscription;
  private id = '';

  protected readonly texte = TEXTE.benutzer;
  protected readonly loeschdialogTexte = TEXTE.benutzerLoeschdialog;
  protected readonly rollenaktionTexte = TEXTE.benutzerRollenaktion;
  protected readonly protokollAktionen = TEXTE.protokoll.aktionen;
  protected readonly gruppe = TEXTE.menueGruppen.verwaltung;
  protected readonly aktuellerBenutzerId = computed(() => this.anmeldung.benutzer()?.id ?? '');
  protected readonly darfVerwalten = computed(() => this.anmeldung.darf('A22'));
  protected readonly darfRolleAendern = computed(() =>
    this.anmeldung.darf('A22') && this.anmeldung.darf('A23'));
  protected readonly name = name;
  protected readonly rollen = rollen;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly einladungAblauf = einladungAblauf;
  protected readonly benutzer = signal<BenutzerAnsicht | null>(null);
  protected readonly rechte = signal<VerwaltungRolle | null>(null);
  protected readonly rollenListe = signal<readonly VerwaltungRolle[] | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly rechteFehler = signal<string | null>(null);
  protected readonly rollenFehler = signal<string | null>(null);
  protected readonly aktionMeldung = signal<string | null>(null);
  protected readonly fortsetzung = signal<BenutzerFortsetzung | null>(null);
  protected readonly fortsetzungFehler = signal<string | null>(null);

  constructor() {
    // frische Anmeldung nach STEP_UP_NOETIG: nur der erfolgreich geprüfte Rückruf liefert die Fortsetzung, einmalig
    const fortsetzung = this.fortsetzen.uebernehmen();
    if (fortsetzung === 'ungueltig') {
      this.fortsetzungFehler.set(TEXTE.benutzerFortsetzung.ungueltig);
    } else {
      this.fortsetzung.set(fortsetzung);
    }
    inject(ActivatedRoute).paramMap.pipe(takeUntilDestroyed(this.zerstoert)).subscribe((parameter) => {
      this.id = parameter.get('id') ?? '';
      if (this.fortsetzung() && this.fortsetzung()?.ziel !== this.id) this.fortsetzung.set(null);
      this.aktionMeldung.set(null);
      this.laden();
    });
  }

  protected laden(): void {
    this.laufend?.unsubscribe();
    this.rechteAnfrage?.unsubscribe();
    this.rollenAnfrage?.unsubscribe();
    this.benutzer.set(null);
    this.rechte.set(null);
    this.rollenListe.set(null);
    this.fehler.set(null);
    this.rechteFehler.set(null);
    this.rollenFehler.set(null);
    this.laufend = this.api.apiV1BenutzerBenutzerIdGet(this.id)
      .pipe(takeUntilDestroyed(this.zerstoert))
      .subscribe({
        next: (benutzer) => {
          this.benutzer.set(benutzer);
          if (!benutzer.rollenkonflikt) this.rechteLaden();
          if (this.darfRolleAendern()) this.verfuegbareRollenLaden();
        },
        error: (fehler: unknown) => this.fehlerBehandeln(fehler, this.fehler),
      });
  }

  protected verfuegbareRollenLaden(): void {
    this.rollenAnfrage?.unsubscribe();
    this.rollenListe.set(null);
    this.rollenFehler.set(null);
    this.rollenAnfrage = this.api.apiV1RollenGet()
      .pipe(takeUntilDestroyed(this.zerstoert))
      .subscribe({
        next: (rollen) => this.rollenListe.set(rollen),
        error: (fehler: unknown) => this.fehlerBehandeln(fehler, this.rollenFehler),
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

  protected aktionAbgeschlossen(antrag = false): void {
    this.aktionMeldung.set(antrag ? this.texte.antragOffen : this.texte.aktionErfolgreich);
    this.laden();
  }

  protected loeschenAbgeschlossen(ergebnis: { id: string; antrag: boolean }): void {
    if (ergebnis.antrag) {
      this.aktionAbgeschlossen(true);
    } else {
      void this.router.navigateByUrl('/verwaltung/benutzer');
    }
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
