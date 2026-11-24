import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { BenutzerAnsicht, BenutzerverwaltungService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { benutzerAktionsfehler } from './benutzer-aktionsfehler';
import { BenutzerFortsetzen, BenutzerFortsetzung, istStepUp } from './benutzer-fortsetzung';
import { BenutzerStatusAktion, BenutzerStatusdialog, BenutzerStatusdialogErgebnis } from './benutzer-statusdialog';
import { Verwaltungsgrund } from './benutzer-loeschdialog';

@Component({
  selector: 'app-benutzer-statusaktionen',
  imports: [MatButtonModule],
  template: `
    @if (aktionen().length) {
      <section class="karte">
        <h2>{{ texte.titel }}</h2>
        @if (fehler(); as meldung) { <p class="hinweis gefahr" role="alert">{{ meldung }}</p> }
        @if (laufend()) { <p role="status">{{ texte.laeuft }}</p> }
        <div class="knoepfe">
          @for (aktion of aktionen(); track aktion) {
            <button mat-button type="button" [disabled]="laufend()"
              [class.gefaehrlich]="aktion === 'sperren' || aktion === 'deaktivieren'"
              (click)="oeffnen(aktion)">{{ dialogTexte.aktionen[aktion].titel }}</button>
          }
        </div>
      </section>
    }`,
  styles: `.gefaehrlich:not(:disabled) { color: var(--gefahr); }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerStatusaktionen {
  readonly person = input.required<BenutzerAnsicht>();
  readonly akteurId = input.required<string>();
  readonly fortsetzung = input<BenutzerFortsetzung | null>(null);
  readonly verbraucht = output<void>();
  readonly abgeschlossen = output<{ id: string; aktion: BenutzerStatusAktion }>();

  private readonly api = inject(BenutzerverwaltungService);
  private readonly dialog = inject(MatDialog);
  private readonly zerstoert = inject(DestroyRef);
  private readonly stepUp = inject(BenutzerFortsetzen);
  private fortsetzungVerbraucht = false;
  private geoeffnet?: MatDialogRef<BenutzerStatusdialog, BenutzerStatusdialogErgebnis | null>;

  protected readonly texte = TEXTE.benutzerStatusaktionen;
  protected readonly dialogTexte = TEXTE.benutzerStatusdialog;
  protected readonly fehler = signal<string | null>(null);
  protected readonly laufend = signal(false);
  protected readonly aktionen = computed(() => {
    const person = this.person();
    if (!person.id || !person.benutzername || !this.akteurId() || person.rollenkonflikt) return [];
    const selbst = person.id === this.akteurId();
    const ergebnis: BenutzerStatusAktion[] = [];
    if (!selbst && person.status === 'AKTIV') ergebnis.push('sperren');
    if (person.status === 'GESPERRT'
        || ((person.status === 'AKTIV' || person.status === 'EINGELADEN') && person.voruebergehendGesperrt)) {
      ergebnis.push('entsperren');
    }
    if (!selbst && (person.status === 'AKTIV' || person.status === 'GESPERRT'
        || person.status === 'EINGELADEN')) ergebnis.push('deaktivieren');
    if (person.status === 'DEAKTIVIERT') ergebnis.push('reaktivieren');
    if (!selbst && (person.status === 'AKTIV' || person.status === 'EINGELADEN')) ergebnis.push('zugangZuruecksetzen');
    if (person.status === 'EINGELADEN') ergebnis.push('einladung');
    if (person.google) ergebnis.push('googleLoesen');
    return ergebnis;
  });

  constructor() {
    this.zerstoert.onDestroy(() => this.geoeffnet?.close());
    effect(() => {
      const fortsetzung = this.fortsetzung();
      const { id } = this.person();
      if (this.fortsetzungVerbraucht || !fortsetzung || fortsetzung.ziel !== id
          || !STATUS_AKTIONEN.has(fortsetzung.aktion)) return;
      this.fortsetzungVerbraucht = true;
      this.verbraucht.emit();
      const aktion = fortsetzung.aktion as BenutzerStatusAktion;
      if (!id || !this.aktionen().includes(aktion)) {
        this.fehler.set(TEXTE.benutzerFortsetzung.nichtMehrMoeglich);
        return;
      }
      this.laufend.set(true);
      this.ausfuehren(id, aktion, { grund: fortsetzung.grund as Verwaltungsgrund | undefined }, true);
    });
  }

  protected oeffnen(aktion: BenutzerStatusAktion): void {
    const { id, benutzername } = this.person();
    if (this.laufend() || !id || !benutzername || !this.aktionen().includes(aktion)) return;
    const akteurId = this.akteurId();
    this.fehler.set(null);
    this.laufend.set(true);
    this.geoeffnet = this.dialog.open(BenutzerStatusdialog, {
      data: { aktion, benutzername },
      panelClass: 'benutzer-status-dialog',
    });
    this.geoeffnet.afterClosed().pipe(takeUntilDestroyed(this.zerstoert)).subscribe((ergebnis) => {
      this.geoeffnet = undefined;
      if (!ergebnis || this.akteurId() !== akteurId || this.person().id !== id || this.person().benutzername !== benutzername
          || !this.aktionen().includes(aktion)) {
        this.laufend.set(false);
        return;
      }
      this.ausfuehren(id, aktion, ergebnis, false);
    });
  }

  private ausfuehren(id: string, aktion: BenutzerStatusAktion, ergebnis: BenutzerStatusdialogErgebnis,
                     ausFortsetzung: boolean): void {
    let anfrage: Observable<unknown>;
    switch (aktion) {
      case 'sperren':
        anfrage = this.api.apiV1BenutzerBenutzerIdSperrenPost(id, { grund: ergebnis.grund });
        break;
      case 'entsperren':
        anfrage = this.api.apiV1BenutzerBenutzerIdEntsperrenPost(id, {});
        break;
      case 'deaktivieren':
        anfrage = this.api.apiV1BenutzerBenutzerIdDeaktivierenPost(id, { grund: ergebnis.grund });
        break;
      case 'reaktivieren':
        anfrage = this.api.apiV1BenutzerBenutzerIdReaktivierenPost(id, {});
        break;
      case 'zugangZuruecksetzen':
        anfrage = this.api.apiV1BenutzerBenutzerIdZugangZuruecksetzenPost(id, {});
        break;
      case 'einladung':
        anfrage = this.api.apiV1BenutzerBenutzerIdEinladungPost(id, {});
        break;
      case 'googleLoesen':
        anfrage = this.api.apiV1BenutzerBenutzerIdGoogleVerknuepfungDelete(id, {});
        break;
    }
    anfrage.pipe(takeUntilDestroyed(this.zerstoert)).subscribe({
      next: () => {
        this.laufend.set(false);
        this.abgeschlossen.emit({ id, aktion });
      },
      error: (fehler: unknown) => {
        if (istStepUp(fehler)) {
          // genau eine Wiederholung: nach der frischen Anmeldung kein zweites Mal weiterleiten
          if (ausFortsetzung) {
            this.laufend.set(false);
            this.fehler.set(TEXTE.benutzerFortsetzung.wiederholtAbgelehnt);
          } else if (!this.stepUp.starten({ aktion, ziel: id, grund: ergebnis.grund })) {
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

const STATUS_AKTIONEN: ReadonlySet<string> = new Set(
  ['sperren', 'entsperren', 'deaktivieren', 'reaktivieren', 'zugangZuruecksetzen', 'einladung', 'googleLoesen']);
