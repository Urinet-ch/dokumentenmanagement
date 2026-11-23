import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { BenutzerAnsicht, BenutzerverwaltungService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { benutzerAktionsfehler } from './benutzer-aktionsfehler';
import { BenutzerStatusAktion, BenutzerStatusdialog, BenutzerStatusdialogErgebnis } from './benutzer-statusdialog';

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
  readonly abgeschlossen = output<{ id: string; aktion: BenutzerStatusAktion }>();

  private readonly api = inject(BenutzerverwaltungService);
  private readonly dialog = inject(MatDialog);
  private readonly zerstoert = inject(DestroyRef);
  private geoeffnet?: MatDialogRef<BenutzerStatusdialog, BenutzerStatusdialogErgebnis | null>;

  protected readonly texte = TEXTE.benutzerStatusaktionen;
  protected readonly dialogTexte = TEXTE.benutzerStatusdialog;
  protected readonly fehler = signal<string | null>(null);
  protected readonly laufend = signal(false);
  protected readonly aktionen = computed(() => {
    const person = this.person();
    if (!person.id || !person.benutzername || person.rollenkonflikt) return [];
    const selbst = person.id === this.akteurId();
    const ergebnis: BenutzerStatusAktion[] = [];
    if (!selbst && person.status === 'AKTIV') ergebnis.push('sperren');
    if (person.status === 'GESPERRT' || person.voruebergehendGesperrt) ergebnis.push('entsperren');
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
  }

  protected oeffnen(aktion: BenutzerStatusAktion): void {
    const { id, benutzername } = this.person();
    if (this.laufend() || !id || !benutzername || !this.aktionen().includes(aktion)) return;
    this.fehler.set(null);
    this.laufend.set(true);
    this.geoeffnet = this.dialog.open(BenutzerStatusdialog, { data: { aktion, benutzername } });
    this.geoeffnet.afterClosed().pipe(takeUntilDestroyed(this.zerstoert)).subscribe((ergebnis) => {
      this.geoeffnet = undefined;
      if (!ergebnis || this.person().id !== id || this.person().benutzername !== benutzername
          || !this.aktionen().includes(aktion)) {
        this.laufend.set(false);
        return;
      }
      this.ausfuehren(id, aktion, ergebnis);
    });
  }

  private ausfuehren(id: string, aktion: BenutzerStatusAktion, ergebnis: BenutzerStatusdialogErgebnis): void {
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
        this.laufend.set(false);
        this.fehler.set(benutzerAktionsfehler(fehler));
      },
    });
  }
}
