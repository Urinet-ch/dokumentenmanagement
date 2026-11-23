import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { TEXTE } from '../../gemeinsam/texte.de-CH';

export interface BenutzerLoeschdialogDaten {
  benutzername: string;
  dokumente: number;
  protokolleintraege: number;
}

export type Verwaltungsgrund = keyof typeof TEXTE.benutzerLoeschdialog.gruende;

@Component({
  selector: 'app-benutzer-loeschdialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ texte.titel }}</h2>
    <mat-dialog-content>
      <p>{{ texte.folgen }}</p>
      <p>{{ texte.erhalten }}</p>
      <dl>
        <dt>{{ texte.dokumente }}</dt><dd>{{ daten.dokumente }}</dd>
        <dt>{{ texte.protokolle }}</dt><dd>{{ daten.protokolleintraege }}</dd>
      </dl>
      <label for="loeschen-grund">{{ texte.grund }}</label>
      <select id="loeschen-grund" [value]="grund()" (change)="grundWaehlen($event)">
        <option value="">{{ texte.grundWaehlen }}</option>
        @for (eintrag of gruende; track eintrag) {
          <option [value]="eintrag">{{ texte.gruende[eintrag] }}</option>
        }
      </select>
      <label for="loeschen-bestaetigung">{{ texte.bestaetigung }}</label>
      <input id="loeschen-bestaetigung" autocomplete="off" [value]="eingabe()"
        (input)="eingabeAendern($event)" />
      <p><bdi>{{ daten.benutzername }}</bdi></p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="null">{{ texte.abbrechen }}</button>
      <button mat-button class="loeschen" type="button" [disabled]="!gueltig()"
        [mat-dialog-close]="grund()">{{ texte.loeschen }}</button>
    </mat-dialog-actions>`,
  styles: `.loeschen:not(:disabled) { color: var(--gefahr); }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenutzerLoeschdialog {
  protected readonly daten = inject<BenutzerLoeschdialogDaten>(MAT_DIALOG_DATA);
  protected readonly texte = TEXTE.benutzerLoeschdialog;
  protected readonly gruende = Object.keys(this.texte.gruende) as Verwaltungsgrund[];
  protected readonly eingabe = signal('');
  protected readonly grund = signal<Verwaltungsgrund | ''>('');

  protected eingabeAendern(event: Event): void {
    this.eingabe.set((event.target as HTMLInputElement).value);
  }

  protected grundWaehlen(event: Event): void {
    const wert = (event.target as HTMLSelectElement).value;
    this.grund.set(this.gruende.find((eintrag) => eintrag === wert) ?? '');
  }

  protected gueltig(): boolean {
    return this.daten.benutzername.length > 0
      && this.eingabe() === this.daten.benutzername && this.grund() !== '';
  }
}
