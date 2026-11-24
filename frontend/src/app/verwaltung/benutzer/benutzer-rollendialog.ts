import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { VerwaltungRolle } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerRechte } from './benutzer-rechte';

export interface BenutzerRollendialogDaten {
  benutzername: string;
  bisher: readonly VerwaltungRolle[];
  verfuegbar: readonly VerwaltungRolle[];
}

@Component({
  selector: 'app-benutzer-rollendialog',
  imports: [MatButtonModule, MatDialogModule, BenutzerRechte],
  template: `
    <h2 mat-dialog-title>{{ texte.titel }}</h2>
    <mat-dialog-content>
      <p><bdi>{{ daten.benutzername }}</bdi></p>
      <p>{{ texte.hinweis }}</p>
      <label for="neue-rolle">{{ texte.auswahl }}</label>
      <select id="neue-rolle" [value]="rolle()" (change)="waehlen($event)">
        <option value="">{{ texte.waehlen }}</option>
        @for (eintrag of daten.verfuegbar; track eintrag.code) {
          <option [value]="eintrag.code">{{ eintrag.beschreibung }}</option>
        }
      </select>
      <h3>{{ texte.bisher }}</h3>
      @for (eintrag of daten.bisher; track eintrag.code) {
        <h4>{{ eintrag.beschreibung }}</h4>
        <app-benutzer-rechte [rolle]="eintrag" />
      } @empty {
        <p>{{ texte.keine }}</p>
      }
      @if (neueRolle(); as neue) {
        <h3>{{ texte.neu }}</h3>
        <h4>{{ neue.beschreibung }}</h4>
        <app-benutzer-rechte [rolle]="neue" />
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="null">{{ texte.abbrechen }}</button>
      <button mat-button type="button" [disabled]="!geaendert()" [mat-dialog-close]="rolle()">
        {{ texte.bestaetigen }}
      </button>
    </mat-dialog-actions>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerRollendialog {
  protected readonly daten = inject<BenutzerRollendialogDaten>(MAT_DIALOG_DATA);
  protected readonly texte = TEXTE.benutzerRollendialog;
  protected readonly rolle = signal('');
  protected readonly neueRolle = computed(() => this.daten.verfuegbar.find((eintrag) => eintrag.code === this.rolle()));
  protected readonly geaendert = computed(() => !!this.neueRolle()
    && (this.daten.bisher.length !== 1 || this.daten.bisher[0].code !== this.rolle()));

  protected waehlen(event: Event): void {
    const wert = (event.target as HTMLSelectElement).value;
    this.rolle.set(this.daten.verfuegbar.some((eintrag) => eintrag.code === wert) ? wert : '');
  }
}
