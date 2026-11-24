import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DOCUMENT, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { ChatAuswertung as ChatAuswertungDaten } from '../gemeinsam/api/model/chat-auswertung';
import { ChatBewertungExportEingabe } from '../gemeinsam/api/model/chat-bewertung-export-eingabe';
import { ChatService } from '../gemeinsam/api/api/chat.service';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { httpFehlercode, httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { Symbol } from '../gemeinsam/symbol';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { ChatBewertungExportDialog, ChatBewertungExportDaten } from './chat-auswertung-export-dialog';
import { datumEingabe, datumVorZwoelfMonaten, zeitraumFehler } from './chat-auswertung-logik';

function standardZeitraum(): { von: string; bis: string } {
  const bis = datumEingabe(new Date());
  return { von: datumVorZwoelfMonaten(bis), bis };
}

function datumText(iso: string | undefined): string {
  if (!iso || !/^\d{4}-\d{2}-\d{2}$/.test(iso)) return '–';
  const [jahr, monat, tag] = iso.split('-');
  return `${tag}.${monat}.${jahr}`;
}

function bewertungsgrund(grund: string | undefined): string {
  return TEXTE.chat.bewertung.gruende.find((eintrag) => eintrag.wert === grund)?.text
    ?? TEXTE.chat.auswertung.keinGrund;
}

@Component({
  selector: 'app-chat-auswertung',
  imports: [MatButtonModule, MatDialogModule, Symbol],
  templateUrl: './chat-auswertung.html',
  styleUrl: './chat-auswertung.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatAuswertung implements OnInit {
  private readonly api = inject(ChatService);
  private readonly dialog = inject(MatDialog);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly router = inject(Router);
  private readonly dokument = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly texte = TEXTE.chat.auswertung;
  protected readonly grunde = bewertungsgrund;
  protected readonly datum = datumText;
  protected readonly zeitraum = signal(standardZeitraum());
  protected readonly daten = signal<ChatAuswertungDaten | null>(null);
  protected readonly laedt = signal(false);
  protected readonly fehler = signal<string | null>(null);
  protected readonly exportLaeuft = signal(false);
  protected readonly exportMeldung = signal<string | null>(null);
  protected readonly exportFehler = signal<string | null>(null);
  protected readonly stepUpNoetig = signal(false);
  protected readonly stepUpLaeuft = signal(false);

  ngOnInit(): void {
    this.laden();
  }

  protected feldSetzen(feld: 'von' | 'bis', wert: string): void {
    this.zeitraum.update((bisher) => ({ ...bisher, [feld]: wert }));
  }

  protected laden(): void {
    const { von, bis } = this.zeitraum();
    const fehler = zeitraumFehler(von, bis);
    if (fehler) {
      this.daten.set(null);
      this.fehler.set(fehler);
      return;
    }

    this.fehler.set(null);
    this.daten.set(null);
    this.laedt.set(true);
    this.api.apiV1ChatAuswertungGet(bis, von, 'body', false, { transferCache: false })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (daten) => {
          this.daten.set(daten);
          this.laedt.set(false);
        },
        error: (fehler: unknown) => {
          this.laedt.set(false);
          if (fehler instanceof HttpErrorResponse && fehler.status === 403) {
            void this.router.navigateByUrl('/nicht-gefunden');
          } else {
            this.fehler.set(httpFehlerMeldung(fehler));
          }
        },
      });
  }

  protected exportDialogOeffnen(): void {
    const { von, bis } = this.zeitraum();
    const ref = this.dialog.open(ChatBewertungExportDialog, {
      data: { von, bis },
      width: 'calc(100vw - 2rem)',
      maxWidth: '38rem',
      ariaLabelledBy: 'chat-bewertung-export-titel',
    });
    ref.afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((eingabe: ChatBewertungExportDaten | undefined) => {
        if (eingabe) this.exportieren(eingabe);
      });
  }

  protected erneutAnmelden(): void {
    this.stepUpLaeuft.set(true);
    void this.anmeldung.anmelden('/chat/auswertung', true).catch(() => {
      this.stepUpLaeuft.set(false);
      this.exportFehler.set(this.texte.stepUpFehler);
    });
  }

  protected anteil(anzahl: number | undefined): number {
    const gesamt = this.daten()?.anzahl ?? 0;
    return gesamt > 0 ? Math.round(((anzahl ?? 0) / gesamt) * 100) : 0;
  }

  protected frageKurz(frage: string | undefined): string {
    return Array.from(frage ?? '').slice(0, 120).join('');
  }

  protected durchschnitt(wert: number | undefined): string {
    return new Intl.NumberFormat('de-CH', {
      minimumFractionDigits: 1,
      maximumFractionDigits: 2,
    }).format(wert ?? 0);
  }

  private exportieren(eingabe: ChatBewertungExportDaten): void {
    this.exportLaeuft.set(true);
    this.exportMeldung.set(null);
    this.exportFehler.set(null);
    this.stepUpNoetig.set(false);
    const body: ChatBewertungExportEingabe = {
      ...eingabe,
      klartextBestaetigt: eingabe.schwaerzen ? false : eingabe.klartextBestaetigt,
    };
    this.api.apiV1ChatAuswertungExportPost(body, 'response', false, {
      httpHeaderAccept: 'application/zip',
      transferCache: false,
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (antwort) => {
        this.exportLaeuft.set(false);
        const zip = antwort.body;
        if (!(zip instanceof Blob) || zip.size === 0) {
          this.exportFehler.set(this.texte.exportUngueltig);
          return;
        }
        this.download(zip);
        this.exportMeldung.set(this.texte.exportFertig);
      },
      error: (fehler: unknown) => {
        this.exportLaeuft.set(false);
        void this.exportFehlerMelden(fehler);
      },
    });
  }

  private async exportFehlerMelden(fehler: unknown): Promise<void> {
    const code = await httpFehlercode(fehler);
    if (code === 'STEP_UP_NOETIG') {
      this.stepUpNoetig.set(true);
      this.exportFehler.set(this.texte.stepUpHinweis);
    } else if (code === 'BEWERTUNG_EXPORT_ZU_GROSS') {
      this.exportFehler.set(this.texte.exportZuGross);
    } else {
      this.exportFehler.set(httpFehlerMeldung(fehler));
    }
  }

  private download(zip: Blob): void {
    const url = URL.createObjectURL(zip);
    const link = this.dokument.createElement('a');
    link.href = url;
    link.download = 'chat-bewertungen.zip';
    link.hidden = true;
    this.dokument.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url));
  }

}
