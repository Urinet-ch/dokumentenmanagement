import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { BenutzerAnsicht, BenutzerverwaltungService, VerwaltungRolle } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { benutzerAktionsfehler } from './benutzer-aktionsfehler';
import { BenutzerFortsetzen, BenutzerFortsetzung, istStepUp } from './benutzer-fortsetzung';
import { BenutzerRollendialog } from './benutzer-rollendialog';

@Component({
  selector: 'app-benutzer-rollenaktion',
  imports: [MatButtonModule],
  template: `
    @if (fehler(); as text) { <p class="hinweis gefahr" role="alert">{{ text }}</p> }
    @if (laufend()) { <p role="status">{{ texte.laeuft }}</p> }
    <button mat-button type="button" [disabled]="laufend()" (click)="oeffnen()">{{ texte.oeffnen }}</button>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerRollenaktion {
  readonly person = input.required<BenutzerAnsicht>();
  readonly akteurId = input.required<string>();
  readonly rollen = input.required<readonly VerwaltungRolle[]>();
  readonly fortsetzung = input<BenutzerFortsetzung | null>(null);
  readonly verbraucht = output<void>();
  readonly abgeschlossen = output<{ id: string; antrag: boolean }>();

  private readonly api = inject(BenutzerverwaltungService);
  private readonly dialog = inject(MatDialog);
  private readonly zerstoert = inject(DestroyRef);
  private readonly stepUp = inject(BenutzerFortsetzen);
  private readonly stepUpFehlgeschlagen = (): void => {
    this.laufend.set(false);
    this.fehler.set(TEXTE.benutzerAktionsfehler.erneuteAnmeldung);
  };
  private fortsetzungVerbraucht = false;
  private geoeffnet?: MatDialogRef<BenutzerRollendialog, string | null>;

  protected readonly texte = TEXTE.benutzerRollenaktion;
  protected readonly laufend = signal(false);
  protected readonly fehler = signal<string | null>(null);

  constructor() {
    this.zerstoert.onDestroy(() => this.geoeffnet?.close());
    effect(() => {
      const fortsetzung = this.fortsetzung();
      const { id, etag } = this.person();
      if (this.fortsetzungVerbraucht || fortsetzung?.aktion !== 'rolle' || fortsetzung.ziel !== id) return;
      this.fortsetzungVerbraucht = true;
      this.verbraucht.emit();
      const { rolle } = fortsetzung;
      if (!id || !rolle || id === this.akteurId() || !this.rollen().some((eintrag) => eintrag.code === rolle)) {
        this.fehler.set(TEXTE.benutzerFortsetzung.nichtMehrMoeglich);
      } else if (!etag || etag !== fortsetzung.etag) {
        this.fehler.set(TEXTE.benutzerAktionsfehler.versionKonflikt);
      } else {
        this.laufend.set(true);
        this.aendern(id, etag, rolle, true);
      }
    });
  }

  protected oeffnen(): void {
    if (this.laufend()) return;
    this.fehler.set(null);
    const { id, benutzername, etag, rollen } = this.person();
    const akteurId = this.akteurId();
    const bisher = Array.from(rollen ?? []).map((rolle) =>
      this.rollen().find((eintrag) => eintrag.code === rolle.toLowerCase()));
    if (!id || id === akteurId || !akteurId || !benutzername || !etag || !rollen
        || !/^[a-f0-9]{64}$/.test(etag) || bisher.some((eintrag) => !eintrag) || !this.rollen().length) {
      this.fehler.set(this.texte.unvollstaendig);
      return;
    }
    const bekannteRollen = bisher.filter((eintrag): eintrag is VerwaltungRolle => !!eintrag);
    this.laufend.set(true);
    this.geoeffnet = this.dialog.open(BenutzerRollendialog, {
      data: { benutzername, bisher: bekannteRollen, verfuegbar: this.rollen() },
    });
    this.geoeffnet.afterClosed().pipe(takeUntilDestroyed(this.zerstoert)).subscribe((rolle) => {
      this.geoeffnet = undefined;
      if (!rolle || this.akteurId() !== akteurId || this.person().id !== id || this.person().benutzername !== benutzername
          || (bekannteRollen.length === 1 && bekannteRollen[0].code === rolle)
          || this.person().etag !== etag || !this.rollen().some((eintrag) => eintrag.code === rolle)) {
        this.laufend.set(false);
        return;
      }
      this.aendern(id, etag, rolle, false);
    });
  }

  private aendern(id: string, etag: string, rolle: string, ausFortsetzung: boolean): void {
    this.api.apiV1BenutzerBenutzerIdRollePut(id, { rolle }, `"${etag}"`, undefined, 'response')
      .pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
        next: (antwort) => {
          this.laufend.set(false);
          if (antwort.status === 204 || (antwort.status === 202 && !!antwort.body?.antragId)) {
            this.abgeschlossen.emit({ id, antrag: antwort.status === 202 });
          } else {
            this.fehler.set(TEXTE.httpFehler.allgemein);
          }
        },
        error: (fehler: unknown) => {
          if (istStepUp(fehler)) {
            // genau eine Wiederholung: nach der frischen Anmeldung kein zweites Mal weiterleiten
            if (ausFortsetzung) {
              this.laufend.set(false);
              this.fehler.set(TEXTE.benutzerFortsetzung.wiederholtAbgelehnt);
            } else if (!this.stepUp.starten({ aktion: 'rolle', ziel: id, etag, rolle }, () => this.stepUpFehlgeschlagen())) {
              this.laufend.set(false);
              this.fehler.set(TEXTE.benutzerAktionsfehler.erneuteAnmeldung);
            }
            return;
          }
          this.laufend.set(false);
          this.fehler.set(benutzerAktionsfehler(fehler));
        },
      });
  }
}
