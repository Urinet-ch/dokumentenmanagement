import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ChatBewertungExportEingabe } from '../gemeinsam/api/model/chat-bewertung-export-eingabe';
import { Symbol } from '../gemeinsam/symbol';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { zeitraumFehler } from './chat-auswertung-logik';

export interface ChatBewertungExportDaten extends ChatBewertungExportEingabe {
  von: string;
  bis: string;
  minSterne: number;
  maxSterne: number;
  schwaerzen: boolean;
  klartextBestaetigt: boolean;
}

interface ChatBewertungExportStart {
  von: string;
  bis: string;
}

@Component({
  selector: 'app-chat-bewertung-export-dialog',
  imports: [MatButtonModule, MatDialogModule, Symbol],
  templateUrl: './chat-auswertung-export-dialog.html',
  styleUrl: './chat-auswertung-export-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatBewertungExportDialog {
  private readonly ref = inject(MatDialogRef<ChatBewertungExportDialog, ChatBewertungExportDaten>);
  private readonly start = inject<ChatBewertungExportStart>(MAT_DIALOG_DATA);
  protected readonly texte = TEXTE.chat.auswertung;
  protected readonly von = signal(this.start.von);
  protected readonly bis = signal(this.start.bis);
  protected readonly minSterne = signal(1);
  protected readonly maxSterne = signal(5);
  protected readonly schwaerzen = signal(true);
  protected readonly klartextBestaetigt = signal(false);

  protected gueltig(): boolean {
    return zeitraumFehler(this.von(), this.bis()) === null
      && this.minSterne() >= 1 && this.maxSterne() <= 5 && this.minSterne() <= this.maxSterne()
      && (this.schwaerzen() || this.klartextBestaetigt());
  }

  protected exportieren(): void {
    if (!this.gueltig()) return;
    this.ref.close({
      von: this.von(),
      bis: this.bis(),
      minSterne: this.minSterne(),
      maxSterne: this.maxSterne(),
      schwaerzen: this.schwaerzen(),
      klartextBestaetigt: this.klartextBestaetigt(),
    });
  }
}
