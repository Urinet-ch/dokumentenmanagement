import { HttpClient, HttpParams } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { forkJoin, map, of, switchMap } from 'rxjs';
import { Dokument, WissensIndexZeile, Wissensseite, Wissensversion, Wissensversionen } from '../gemeinsam/api';
import { anzeigeTitel } from '../gemeinsam/anzeige-titel';
import { zeitpunkt } from '../gemeinsam/format';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Textteil, wissenText } from './wissen-text';

interface Quelle {
  schluessel: string;
  titel: string;
  dokumentId?: string;
  seite?: number;
  pfad?: string;
}

interface Inhalt {
  seite: Wissensseite;
  text: Textteil[][];
  quellen: Quelle[];
  eingehend: WissensIndexZeile[];
  versionen: Wissensversionen;
}

@Component({
  selector: 'app-wissen-seite',
  imports: [RouterLink],
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
  protected readonly versionsText = signal<{ nummer: number; text: Textteil[][] } | null>(null);
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
        const quellen = (seite.quellen ?? []).map((quelle) => {
          if (quelle.dokumentId) {
            return this.http.get<Dokument>(`/api/v1/dokumente/${encodeURIComponent(quelle.dokumentId)}`).pipe(
              map((dokument): Quelle => ({
                schluessel: quelle.schluessel ?? '',
                titel: anzeigeTitel(dokument.titel, null, dokument.dateiname).text,
                dokumentId: quelle.dokumentId,
                seite: quelle.seiteVon,
              })),
            );
          }
          if (quelle.quellSeiteId) {
            return this.http.get<Wissensseite>(`/api/v1/wissensseiten/${encodeURIComponent(quelle.quellSeiteId)}`).pipe(
              map((quelleSeite): Quelle => ({
                schluessel: quelle.schluessel ?? '',
                titel: quelleSeite.titel ?? '',
                pfad: quelleSeite.pfad,
              })),
            );
          }
          throw new Error('Quelleneintrag ohne Ziel');
        });
        return forkJoin({
          seite: of(seite),
          quellen: quellen.length ? forkJoin(quellen) : of([]),
          eingehend: this.http.get<WissensIndexZeile[]>(`/api/v1/wissensseiten/${id}/eingehend`),
          versionen: this.http.get<Wissensversionen>(`/api/v1/wissensseiten/${id}/versionen`),
        });
      }),
      takeUntilDestroyed(this.destroy),
    ).subscribe({
      next: (ergebnis) => {
        if (nummer !== this.anfrage) return;
        this.inhalt.set({ ...ergebnis, text: wissenText(ergebnis.seite.inhaltHtml ?? '') });
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
