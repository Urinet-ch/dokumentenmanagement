import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { Auswirkung } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';

@Component({
  selector: 'app-einstellungen-bestaetigung',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ texte.bestaetigenTitel }}</h2>
    <mat-dialog-content>
      <p>{{ texte.bestaetigenHinweis }}</p>
      @if (auswirkungen.length > 0) {
        <ul>
          @for (auswirkung of auswirkungen; track $index) {
            <li>{{ auswirkung.text }}</li>
          }
        </ul>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="false">{{ texte.abbrechen }}</button>
      <button mat-flat-button type="button" [mat-dialog-close]="true">{{ texte.bestaetigen }}</button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EinstellungenBestaetigung {
  protected readonly texte = TEXTE.einstellungen;
  protected readonly auswirkungen = inject<readonly Auswirkung[]>(MAT_DIALOG_DATA);
}
