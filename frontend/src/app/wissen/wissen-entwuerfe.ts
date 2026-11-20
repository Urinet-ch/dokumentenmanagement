import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Quelleneintrag } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { zeitpunkt } from '../gemeinsam/format';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { WissenEntwurfZaehler } from './wissen-entwurf-zaehler';

interface Kopf {
  id: string; anlass: string; status: string; erstellt: string; erstelltVon: string;
  dokumentId?: string; unvollstaendig: boolean; hinweis?: string;
  herkunft?: { paketSha256: string; unveraendert: number; ignoriert: number;
    kopf: { erzeugtVon: string; erzeugtMit: string; kontextStufe: string; erzeugtAm: string } };
}
interface Befund { code: string; meldung?: string; text?: string }
interface Fassung {
  titel: string; beschreibung: string; tags: string[]; veraltetAb?: string;
  quellen: Quelleneintrag[]; inhalt: string;
}
interface Aussage { schluessel: string; text: string; seiten: number[]; auszug: string; belegt: boolean; zugeordnet: boolean }
interface Zeile { art: string; alt: string[]; neu: string[] }
interface Feld { alt: unknown; neu: unknown }
interface Aenderung {
  nr: number; pfad: string; aktion: string; status: string; etag: number; basisVersion?: number;
  daten: Fassung & { quellenPruefung?: { schluessel: string; zuordnung: string; auszug?: string | null }[] };
  inhalt: string; begruendung?: string; vertraulichkeitKontext: string; grund?: string;
  befunde: Befund[]; hinweise: Befund[];
  widersprueche: { text?: string; schluessel?: string[] }[];
  entfernteAussagen: { schluessel: string; begruendung: string }[];
  diff: { zeilen?: Zeile[]; titel?: Feld; beschreibung?: Feld; tags?: Feld; quellen?: Feld;
    widersprueche?: { alt: { text: string; fussnoten: string[] }[]; neu: { text: string; fussnoten: string[] }[] };
    entfernteWidersprueche?: { text: string; fussnoten: string[] }[] };
}
interface Entwurf { entwurf: Kopf; aenderungen: Aenderung[]; kernaussagen: Aussage[] }
type Aktion = 'annehmen' | 'verwerfen' | 'neu-erzeugen';

@Component({
  selector: 'app-wissen-entscheid-bestaetigung',
  imports: [MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ texte.bestaetigung }}</h2>
    <mat-dialog-content><p>{{ texte.einzelpruefung }}</p><p>{{ daten.pfad }}</p></mat-dialog-content>
    <mat-dialog-actions>
      <button type="button" [mat-dialog-close]="true">{{ daten.aktion }}</button>
      <button type="button" [mat-dialog-close]="false">{{ texte.abbrechen }}</button>
    </mat-dialog-actions>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenEntscheidBestaetigung {
  protected readonly texte = TEXTE.wissen.entwuerfe;
  protected readonly daten = inject<{ pfad: string; aktion: string }>(MAT_DIALOG_DATA);
}

@Component({
  selector: 'app-wissen-entwuerfe',
  imports: [RouterLink, FormsModule, MatDialogModule],
  templateUrl: './wissen-entwuerfe.html',
  styleUrls: ['./wissen.scss', './wissen-entwuerfe.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WissenEntwuerfe {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly destroy = inject(DestroyRef);
  private readonly zaehler = inject(WissenEntwurfZaehler);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly kontext = computed(() => {
    const benutzer = this.anmeldung.benutzer();
    return `${benutzer?.id}/${benutzer?.mandant}/${benutzer?.rolle}/${this.anmeldung.darf('A17')}`;
  });
  readonly id = input<string>();
  protected readonly texte = TEXTE.wissen.entwuerfe;
  protected readonly wissen = TEXTE.wissen;
  protected readonly importe = TEXTE.wissen.import;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly diffFelder = ['titel', 'beschreibung', 'tags', 'quellen'] as const;
  protected readonly liste = signal<{ entwurf: Kopf }[]>([]);
  protected readonly entwurf = signal<Entwurf | null>(null);
  protected readonly laedt = signal(false);
  protected readonly sendet = signal(false);
  protected readonly fehler = signal<string | null>(null);
  protected readonly aktionsFehler = signal<string | null>(null);
  protected readonly aktionsBefunde = signal<string[]>([]);
  protected readonly prueft = signal(false);
  protected readonly pruefFehler = signal<string | null>(null);
  protected status = 'OFFEN';
  protected bearbeitet?: number;
  protected auswahl?: number;
  protected fassung?: Fassung;
  protected quellenText = '';
  protected entfernteText = '';
  protected tagsText = '';
  protected grund = '';
  protected stufe = '';
  private laufend?: Subscription;
  private aktionen = new Subscription();
  private bestaetigung?: MatDialogRef<WissenEntscheidBestaetigung, boolean>;

  constructor() {
    this.destroy.onDestroy(() => {
      this.aktionen.unsubscribe();
      this.bestaetigung?.close(false);
    });
    effect(() => {
      this.id();
      this.kontext();
      untracked(() => {
        this.aktionen.unsubscribe();
        this.aktionen = new Subscription();
        this.bestaetigung?.close(false);
        this.sendet.set(false);
        this.prueft.set(false);
        this.pruefFehler.set(null);
        this.aktionsFehler.set(null);
        this.aktionsBefunde.set([]);
        this.laden();
      });
    });
  }

  protected laden(eingabenBehalten = false): void {
    this.laufend?.unsubscribe();
    this.liste.set([]);
    this.entwurf.set(null);
    this.fehler.set(null);
    this.laedt.set(true);
    if (!eingabenBehalten) {
      this.bearbeitet = undefined;
      this.auswahl = undefined;
      this.fassung = undefined;
      this.quellenText = '';
      this.entfernteText = '';
      this.tagsText = '';
      this.grund = '';
      this.stufe = '';
    }
    if (!this.anmeldung.darf('A17')) {
      this.laedt.set(false);
      this.fehler.set(this.importe.nichtErlaubt);
      return;
    }
    const id = this.id();
    if (id) {
      this.laufend = this.http.get<Entwurf>(`/api/v1/wissensentwuerfe/${encodeURIComponent(id)}`)
        .pipe(takeUntilDestroyed(this.destroy)).subscribe({
          next: (entwurf) => { this.entwurf.set(entwurf); this.laedt.set(false); },
          error: (fehler: unknown) => { this.fehler.set(httpFehlerMeldung(fehler)); this.laedt.set(false); },
        });
    } else {
      this.laufend = this.http.get<{ entwurf: Kopf }[]>('/api/v1/wissensentwuerfe',
        { params: this.status ? { status: this.status } : {} }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
          next: (liste) => { this.liste.set(liste); this.laedt.set(false); },
          error: (fehler: unknown) => { this.fehler.set(httpFehlerMeldung(fehler)); this.laedt.set(false); },
        });
    }
  }

  protected bearbeiten(aenderung: Aenderung): void {
    this.auswahl = aenderung.nr;
    this.bearbeitet = aenderung.nr;
    this.fassung = { titel: aenderung.daten.titel, beschreibung: aenderung.daten.beschreibung,
      tags: [...(aenderung.daten.tags ?? [])], quellen: aenderung.daten.quellen,
      veraltetAb: aenderung.daten.veraltetAb, inhalt: aenderung.inhalt };
    this.quellenText = JSON.stringify(aenderung.daten.quellen ?? [], null, 2);
    this.entfernteText = JSON.stringify(aenderung.entfernteAussagen ?? [], null, 2);
    this.tagsText = this.fassung.tags.join(', ');
    this.grund = '';
    this.stufe = '';
    this.aktionsFehler.set(null);
    this.aktionsBefunde.set([]);
  }

  protected bestaetigen(aenderung: Aenderung, aktion: Aktion): void {
    if (this.sendet()) return;
    this.aktionsFehler.set(null);
    this.aktionsBefunde.set([]);
    if (aktion === 'verwerfen' && !this.grund.trim()) {
      this.aktionsFehler.set(this.texte.grundFehlt);
      return;
    }
    let angaben: object = { grund: this.grund || null, vertraulichkeit: this.stufe || null };
    if (aktion === 'annehmen' && this.bearbeitet === aenderung.nr && this.fassung) {
      try {
        const quellen: unknown = JSON.parse(this.quellenText);
        const entfernt: unknown = JSON.parse(this.entfernteText);
        if (!Array.isArray(quellen) || !Array.isArray(entfernt)) {
          this.aktionsFehler.set(this.texte.jsonUngueltig);
          return;
        }
        angaben = { ...angaben, fassung: { ...this.fassung, quellen, entfernteAussagen: entfernt,
          veraltetAb: this.fassung.veraltetAb || null,
          tags: this.tagsText.split(',').map((tag) => tag.trim()).filter(Boolean) } };
      } catch (fehler: unknown) {
        if (!(fehler instanceof SyntaxError)) throw fehler;
        this.aktionsFehler.set(this.texte.jsonUngueltig);
        return;
      }
    }
    const id = this.id();
    if (!id) throw new Error('Wissensentwurf ohne ID');
    const referenz = this.dialog.open(WissenEntscheidBestaetigung,
      { data: { pfad: aenderung.pfad, aktion: this.aktionsTitel(aktion) }, maxWidth: '95vw' });
    this.bestaetigung = referenz;
    this.sendet.set(true);
    this.aktionen.add(referenz.afterClosed().pipe(takeUntilDestroyed(this.destroy)).subscribe((ja: boolean) => {
      if (!ja) { this.sendet.set(false); return; }
      this.aktionen.add(this.http.post(`/api/v1/wissensentwuerfe/${encodeURIComponent(id)}/aenderungen/${aenderung.nr}/${aktion}`,
        aktion === 'neu-erzeugen' ? null : angaben, { headers: { 'If-Match': `"${aenderung.etag}"` } })
        .pipe(takeUntilDestroyed(this.destroy)).subscribe({
          next: () => { this.sendet.set(false); this.zaehler.auffrischen(); this.laden(); },
          error: (fehler: unknown) => {
            this.sendet.set(false);
            this.aktionsFehler.set(httpFehlerMeldung(fehler));
            const antwort: unknown = fehler instanceof HttpErrorResponse ? fehler.error : null;
            if (antwort && typeof antwort === 'object' && 'befunde' in antwort && Array.isArray(antwort.befunde)) {
              this.aktionsBefunde.set(antwort.befunde.filter((code): code is string => typeof code === 'string'));
            }
          },
        }));
    }));
  }

  protected neuPruefen(): void {
    if (this.sendet()) return;
    this.pruefFehler.set(null);
    const kopf = this.entwurf()?.entwurf;
    const id = this.id();
    if (!id || !this.anmeldung.darf('A17') || kopf?.anlass !== 'IMPORT' || kopf.status !== 'OFFEN') {
      this.pruefFehler.set(this.importe.pruefungNichtErlaubt);
      return;
    }
    this.sendet.set(true);
    this.prueft.set(true);
    this.aktionen.add(this.http.post<void>(`/api/v1/wissensentwuerfe/${encodeURIComponent(id)}/neu-pruefen`, null)
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: () => { this.sendet.set(false); this.prueft.set(false); this.laden(true); },
        error: (fehler: unknown) => {
          this.sendet.set(false);
          this.prueft.set(false);
          this.pruefFehler.set(httpFehlerMeldung(fehler));
        },
      }));
  }

  protected auszugText(code: string | null | undefined): string {
    return code == null ? this.importe.auszugNichtGeprueft : this.importe.auszugsErgebnisse[code] ?? code;
  }

  protected aktionsTitel(aktion: Aktion): string {
    return aktion === 'annehmen' ? this.texte.annehmen
      : aktion === 'verwerfen' ? this.texte.verwerfen : this.texte.neuErzeugen;
  }

  protected feld(wert: unknown): string {
    return typeof wert === 'string' ? wert : JSON.stringify(wert ?? null);
  }

  protected stufeWaehlen(aenderung: Aenderung): void {
    this.auswahl = aenderung.nr;
    this.bearbeitet = undefined;
    this.grund = '';
    this.stufe = '';
  }
}
