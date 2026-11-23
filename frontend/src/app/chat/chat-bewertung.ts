import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Subscription } from 'rxjs';
import { ChatService } from '../gemeinsam/api/api/chat.service';
import { ChatBewertung as Bewertung } from '../gemeinsam/api/model/chat-bewertung';
import { TEXTE } from '../gemeinsam/texte.de-CH';

@Component({
  selector: 'app-chat-bewertung',
  imports: [MatButtonModule],
  templateUrl: './chat-bewertung.html',
  styleUrl: './chat-bewertung.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatBewertung {
  readonly nachrichtId = input.required<string>();
  protected readonly texte = TEXTE.chat.bewertung;
  protected readonly zahlen = [1, 2, 3, 4, 5];
  protected readonly sterne = signal(0);
  protected readonly grund = signal('');
  protected readonly freitext = signal('');
  protected readonly gespeichert = signal(false);
  protected readonly laedt = signal(true);
  protected readonly sendet = signal(false);
  protected readonly fehler = signal<string | null>(null);
  private readonly api = inject(ChatService);
  private anfrage?: Subscription;

  constructor() {
    effect((cleanup) => {
      const id = this.nachrichtId();
      this.laden(id);
      cleanup(() => this.anfrage?.unsubscribe());
    });
  }

  protected laden(id = this.nachrichtId()): void {
    this.anfrage?.unsubscribe();
    this.sterne.set(0);
    this.grund.set('');
    this.freitext.set('');
    this.gespeichert.set(false);
    this.sendet.set(false);
    this.laedt.set(true);
    this.fehler.set(null);
    this.anfrage = this.api.apiV1ChatNachrichtenIdBewertungGet(id).subscribe({
      next: (bewertung) => {
        this.uebernehmen(bewertung);
        this.laedt.set(false);
      },
      error: (fehler: unknown) => {
        this.laedt.set(false);
        if (!(fehler instanceof HttpErrorResponse && fehler.status === 404)) {
          this.fehler.set(this.texte.ladenFehler);
        }
      },
    });
  }

  protected speichern(): void {
    if (!this.sterne() || this.sendet()) return;
    this.sendet.set(true);
    this.fehler.set(null);
    this.anfrage = this.api.apiV1ChatNachrichtenIdBewertungPut(this.nachrichtId(), {
      sterne: this.sterne(),
      grund: this.grund() || null,
      freitext: this.freitext() || null,
    }).subscribe({
      next: (bewertung) => {
        this.uebernehmen(bewertung);
        this.sendet.set(false);
      },
      error: () => {
        this.sendet.set(false);
        this.fehler.set(this.texte.speichernFehler);
      },
    });
  }

  protected entfernen(): void {
    if (!this.gespeichert() || this.sendet()) return;
    this.sendet.set(true);
    this.fehler.set(null);
    this.anfrage = this.api.apiV1ChatNachrichtenIdBewertungDelete(this.nachrichtId()).subscribe({
      next: () => {
        this.sterne.set(0);
        this.grund.set('');
        this.freitext.set('');
        this.gespeichert.set(false);
        this.sendet.set(false);
      },
      error: () => {
        this.sendet.set(false);
        this.fehler.set(this.texte.entfernenFehler);
      },
    });
  }

  private uebernehmen(bewertung: Bewertung): void {
    this.sterne.set(bewertung.sterne);
    this.grund.set(bewertung.grund ?? '');
    this.freitext.set(bewertung.freitext ?? '');
    this.gespeichert.set(true);
  }
}
