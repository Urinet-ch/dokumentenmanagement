import { HttpClient, HttpParams } from '@angular/common/http';
import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { forkJoin, of, switchMap } from 'rxjs';
import { WissensIndexZeile, Wissensseite, WissensseitenWebseitenfreigabe, Wissensversion, Wissensversionen } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { anzeigeTitel } from '../gemeinsam/anzeige-titel';
import { datum, zeitpunkt } from '../gemeinsam/format';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { Symbol } from '../gemeinsam/symbol';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Textteil, wissenText } from './wissen-text';

function heuteIso(): string {
  const jetzt = new Date();
  const zweistellig = (zahl: number) => String(zahl).padStart(2, '0');
  return `${jetzt.getFullYear()}-${zweistellig(jetzt.getMonth() + 1)}-${zweistellig(jetzt.getDate())}`;
}

function klartext(teil: Textteil): string {
  return teil.text ?? (teil.kinder ?? []).map(klartext).join('');
}

interface Quelle {
  schluessel: string;
  titel: string;
  dokumentId?: string;
  seite?: number;
  pfad?: string;
  datum?: string;
}

interface Inhalt {
  seite: Wissensseite;
  text: Textteil[];
  quellen: Quelle[];
  quellenNachSchluessel: Map<string, Quelle>;
  eingehend: WissensIndexZeile[];
  versionen: Wissensversionen;
}

interface Webseitenstatus {
  freigegeben: boolean;
  freigabefaehig: boolean;
  hoechsteZugriffsstufe: string;
  etag: string;
}

@Component({
  selector: 'app-webseitenfreigabe-bestaetigung',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ texte.bestaetigung }}</h2>
    <mat-dialog-content><p>{{ daten.freigeben ? texte.frageFreigeben : texte.frageEntziehen }}</p></mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="false">{{ texte.abbrechen }}</button>
      <button mat-flat-button type="button" [mat-dialog-close]="true">
        {{ daten.freigeben ? texte.freigeben : texte.entziehen }}
      </button>
    </mat-dialog-actions>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WebseitenfreigabeBestaetigung {
  protected readonly texte = TEXTE.wissen.webseite;
  protected readonly daten = inject<{ freigeben: boolean }>(MAT_DIALOG_DATA);
}

@Component({
  selector: 'app-wissen-seite',
  imports: [RouterLink, NgTemplateOutlet, MatButtonModule, Symbol],
  templateUrl: './wissen-seite.html',
  styleUrls: ['./wissen.scss', './wissen-seite.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenSeite {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private readonly dialog = inject(MatDialog);
  protected readonly anmeldung = inject(AnmeldungService);
  readonly pfad = input<string>();
  readonly ordner = input<string>();
  readonly name = input<string>();
  protected readonly texte = TEXTE.wissen;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly datum = datum;
  protected readonly inhalt = signal<Inhalt | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly laedt = signal(false);
  protected readonly versionsText = signal<{ nummer: number; text: Textteil[] } | null>(null);
  protected readonly versionsFehler = signal<string | null>(null);
  protected readonly versionLaedt = signal(false);
  protected readonly webseitenstatus = signal<Webseitenstatus | null>(null);
  protected readonly webseitenfehler = signal<string | null>(null);
  protected readonly webseiteSendet = signal(false);
  private webseitenAnfrage = 0;
  private anfrage = 0;
  private versionsAnfrage = 0;
  private aktuellerPfad = '';

  constructor() {
    effect(() => {
      const pfad = this.pfad() ?? `${this.ordner() ?? ''}/${this.name() ?? ''}`;
      untracked(() => {
        this.aktuellerPfad = pfad;
        this.laden();
      });
    });
  }

  /** Widerspruchsblock `> ⚠️ Widerspruch: …` (Glossar Spec 04) erscheint als Warn-Hinweis. */
  protected widerspruch(teil: Textteil): boolean {
    return /^\s*⚠\uFE0F?\s*Widerspruch:/.test(klartext(teil));
  }

  /** Hinweis ABGELAUFEN (8.3): «veraltet ab» erreicht, Status aber noch nicht VERALTET. */
  protected abgelaufen(seite: Wissensseite): boolean {
    return !!seite.veraltetAb && seite.status !== 'VERALTET' && seite.veraltetAb.slice(0, 10) <= heuteIso();
  }

  protected laden(): void {
    const nummer = ++this.anfrage;
    this.versionSchliessen();
    this.fehler.set(null);
    this.inhalt.set(null);
    this.webseitenAnfrage++;
    this.webseitenstatus.set(null);
    this.webseitenfehler.set(null);
    this.laedt.set(true);
    this.http.get<Wissensseite>('/api/v1/wissensseiten', {
      params: new HttpParams().set('pfad', this.aktuellerPfad),
    }).pipe(
      switchMap((seite) => {
        if (!seite.id) throw new Error('Wissensseite ohne ID');
        const id = encodeURIComponent(seite.id);
        // Titel und Ziele der Quellen liefert der Server mit (unter derselben Sichtregel): kein Nachladen je Quelle.
        const quellen = (seite.quellen ?? []).map((quelle): Quelle => {
          const schluessel = quelle.schluessel ?? '';
          if (quelle.dokumentId) {
            const bekannt = quelle.dokumentTitel || quelle.dokumentDateiname;
            return {
              schluessel,
              titel: bekannt ? anzeigeTitel(quelle.dokumentTitel, null, quelle.dokumentDateiname).text : schluessel,
              dokumentId: quelle.dokumentId,
              seite: quelle.seiteVon,
              datum: quelle.dokumentDatum,
            };
          }
          return { schluessel, titel: quelle.quellSeiteTitel || schluessel, pfad: quelle.quellSeitePfad };
        });
        return forkJoin({
          seite: of(seite),
          quellen: of(quellen),
          eingehend: this.http.get<WissensIndexZeile[]>(`/api/v1/wissensseiten/${id}/eingehend`),
          versionen: this.http.get<Wissensversionen>(`/api/v1/wissensseiten/${id}/versionen`),
        });
      }),
      takeUntilDestroyed(this.destroy),
    ).subscribe({
      next: (ergebnis) => {
        if (nummer !== this.anfrage) return;
        this.inhalt.set({
          ...ergebnis,
          text: wissenText(ergebnis.seite.inhaltHtml ?? ''),
          quellenNachSchluessel: new Map(ergebnis.quellen.map((quelle) => [quelle.schluessel, quelle])),
        });
        this.webseitenstatusLaden();
        this.laedt.set(false);
      },
      error: (fehler: unknown) => {
        if (nummer !== this.anfrage) return;
        this.fehler.set(httpFehlerMeldung(fehler));
        this.laedt.set(false);
      },
    });
  }

  protected webseitenstatusLaden(): void {
    const id = this.inhalt()?.seite.id;
    if (!id) return;
    const nummer = ++this.webseitenAnfrage;
    this.webseitenstatus.set(null);
    this.webseitenfehler.set(null);
    this.http.get<WissensseitenWebseitenfreigabe>(
      `/api/v1/wissensseiten/${encodeURIComponent(id)}/webseitenfreigabe`, { observe: 'response' },
    ).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (antwort) => {
        if (nummer !== this.webseitenAnfrage) return;
        const status = antwort.body;
        const etag = antwort.headers.get('ETag');
        if (!status || typeof status.freigegeben !== 'boolean' || typeof status.freigabefaehig !== 'boolean'
            || typeof status.hoechsteZugriffsstufe !== 'string' || !etag) {
          this.webseitenfehler.set(this.texte.webseite.fehler);
          return;
        }
        this.webseitenstatus.set({ freigegeben: status.freigegeben, freigabefaehig: status.freigabefaehig,
          hoechsteZugriffsstufe: status.hoechsteZugriffsstufe, etag });
      },
      error: (fehler: unknown) => {
        if (nummer === this.webseitenAnfrage) this.webseitenfehler.set(httpFehlerMeldung(fehler));
      },
    });
  }

  protected webseitenfreigabeAendern(): void {
    const status = this.webseitenstatus();
    const id = this.inhalt()?.seite.id;
    if (!id || !status || !this.anmeldung.darf('A16') || this.webseiteSendet()
        || (!status.freigegeben && !status.freigabefaehig)) return;
    const freigeben = !status.freigegeben;
    this.dialog.open(WebseitenfreigabeBestaetigung, { data: { freigeben } }).afterClosed()
      .pipe(takeUntilDestroyed(this.destroy)).subscribe((bestaetigt: boolean) => {
        if (!bestaetigt || this.inhalt()?.seite.id !== id || this.webseitenstatus() !== status) return;
        this.webseiteSendet.set(true);
        this.webseitenfehler.set(null);
        this.http.post(`/api/v1/wissensseiten/${encodeURIComponent(id)}/webseitenfreigabe`, { freigeben },
          { headers: { 'If-Match': status.etag } }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
            next: () => {
              this.webseiteSendet.set(false);
              this.webseitenstatusLaden();
            },
            error: (fehler: unknown) => {
              this.webseiteSendet.set(false);
              this.webseitenstatus.set(null);
              this.webseitenfehler.set(httpFehlerMeldung(fehler));
            },
          });
      });
  }

  protected versionOeffnen(nummer: number): void {
    const id = this.inhalt()?.seite.id;
    if (!id) return;
    const anfrage = ++this.versionsAnfrage;
    this.versionsText.set(null);
    this.versionsFehler.set(null);
    this.versionLaedt.set(true);
    this.http.get<Wissensversion>(`/api/v1/wissensseiten/${encodeURIComponent(id)}/versionen/${nummer}`)
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: (version) => {
          if (anfrage !== this.versionsAnfrage) return;
          this.versionsText.set({ nummer, text: wissenText(version.inhaltHtml ?? '') });
          this.versionLaedt.set(false);
        },
        error: (fehler: unknown) => {
          if (anfrage !== this.versionsAnfrage) return;
          this.versionsFehler.set(httpFehlerMeldung(fehler));
          this.versionLaedt.set(false);
        },
      });
  }

  protected versionSchliessen(): void {
    this.versionsAnfrage++;
    this.versionsText.set(null);
    this.versionsFehler.set(null);
    this.versionLaedt.set(false);
  }
}
