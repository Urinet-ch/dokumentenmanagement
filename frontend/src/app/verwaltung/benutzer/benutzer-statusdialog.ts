import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { Verwaltungsgrund } from './benutzer-loeschdialog';

export type BenutzerStatusAktion = keyof typeof TEXTE.benutzerStatusdialog.aktionen;

export interface BenutzerStatusdialogDaten {
  aktion: BenutzerStatusAktion;
  benutzername: string;
}

export interface BenutzerStatusdialogErgebnis {
  grund?: Verwaltungsgrund;
}

@Component({
  selector: 'app-benutzer-statusdialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ aktion.titel }}</h2>
    <mat-dialog-content>
      <p><bdi>{{ daten.benutzername }}</bdi></p>
      <p>{{ aktion.wirkung }}</p>
      @if (brauchtGrund) {
        <label for="status-grund">{{ texte.grund }}</label>
        <select id="status-grund" [value]="grund()" (change)="grundWaehlen($event)">
          <option value="">{{ texte.grundWaehlen }}</option>
          @for (eintrag of gruende; track eintrag) {
            <option [value]="eintrag">{{ grundTexte[eintrag] }}</option>
          }
        </select>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="null">{{ texte.abbrechen }}</button>
      <button mat-button type="button" [class.gefaehrlich]="brauchtGrund" [disabled]="brauchtGrund && !grund()"
        [mat-dialog-close]="ergebnis()">{{ texte.bestaetigen }}</button>
    </mat-dialog-actions>`,
  styles: `.gefaehrlich:not(:disabled) { color: var(--gefahr); }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerStatusdialog {
  protected readonly daten = inject<BenutzerStatusdialogDaten>(MAT_DIALOG_DATA);
  protected readonly texte = TEXTE.benutzerStatusdialog;
  protected readonly aktion = this.texte.aktionen[this.daten.aktion];
  protected readonly brauchtGrund = this.daten.aktion === 'sperren' || this.daten.aktion === 'deaktivieren';
  protected readonly grundTexte = TEXTE.benutzerLoeschdialog.gruende;
  protected readonly gruende = Object.keys(this.grundTexte) as Verwaltungsgrund[];
  protected readonly grund = signal<Verwaltungsgrund | ''>('');

  protected grundWaehlen(event: Event): void {
    const wert = (event.target as HTMLSelectElement).value;
    this.grund.set(this.gruende.find((eintrag) => eintrag === wert) ?? '');
  }

  protected ergebnis(): BenutzerStatusdialogErgebnis {
    const grund = this.grund();
    return grund ? { grund } : {};
  }
}
