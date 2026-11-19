import { HttpClient, HttpParams } from '@angular/common/http';
import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { forkJoin, of, switchMap } from 'rxjs';
import { WissensIndexZeile, Wissensseite, Wissensversion, Wissensversionen } from '../gemeinsam/api';
import { anzeigeTitel } from '../gemeinsam/anzeige-titel';
import { zeitpunkt } from '../gemeinsam/format';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Textteil, wissenText } from './wissen-text';

function heuteIso(): string {
  const jetzt = new Date();
  const zweistellig = (zahl: number) => String(zahl).padStart(2, '0');
  return `${jetzt.getFullYear()}-${zweistellig(jetzt.getMonth() + 1)}-${zweistellig(jetzt.getDate())}`;
}

interface Quelle {
  schluessel: string;
  titel: string;
  dokumentId?: string;
  seite?: number;
  pfad?: string;
}

interface Inhalt {
  seite: Wissensseite;
  text: Textteil[];
  quellen: Quelle[];
  quellenNachSchluessel: Map<string, Quelle>;
  eingehend: WissensIndexZeile[];
  versionen: Wissensversionen;
}

@Component({
  selector: 'app-wissen-seite',
  imports: [RouterLink, NgTemplateOutlet],
  templateUrl: './wissen-seite.html',
  styleUrl: './wissen.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenSeite {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  readonly pfad = input<string>();
  readonly ordner = input<string>();
  readonly name = input<string>();
  protected readonly texte = TEXTE.wissen;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly inhalt = signal<Inhalt | null>(null);
  protected readonly fehler = signal<string | null>(null);
  protected readonly laedt = signal(false);
  protected readonly versionsText = signal<{ nummer: number; text: Textteil[] } | null>(null);
  protected readonly versionsFehler = signal<string | null>(null);
  protected readonly versionLaedt = signal(false);
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

  /** Hinweis ABGELAUFEN (8.3): «veraltet ab» erreicht, Status aber noch nicht VERALTET. */
  protected abgelaufen(seite: Wissensseite): boolean {
    return !!seite.veraltetAb && seite.status !== 'VERALTET' && seite.veraltetAb.slice(0, 10) <= heuteIso();
  }

  protected laden(): void {
    const nummer = ++this.anfrage;
    this.versionSchliessen();
    this.fehler.set(null);
    this.inhalt.set(null);
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
        this.laedt.set(false);
      },
      error: (fehler: unknown) => {
        if (nummer !== this.anfrage) return;
        this.fehler.set(httpFehlerMeldung(fehler));
        this.laedt.set(false);
      },
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
