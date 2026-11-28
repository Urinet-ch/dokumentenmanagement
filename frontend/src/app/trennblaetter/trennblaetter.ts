import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { DokumentartenService, TrennblaetterService } from '../gemeinsam/api';
import { httpFehlerMeldung, httpFehlercode } from '../gemeinsam/http-fehler';
import { Symbol } from '../gemeinsam/symbol';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import {
  Deckblatt,
  MAX_BLAETTER,
  TITEL_MAX,
  anfrage,
  anzahlLesen,
  summe,
  titelFehler,
  titelLaenge,
} from './trennblaetter-logik';

interface ArtZeile {
  readonly code: string;
  readonly name: string;
}

/**
 * Seite «Trennblätter» (Spec 09, Aufgabe 27.5; Req 6.1–6.3, 6.5): Anzahl Trennblätter ohne Art und je aktiver
 * Dokumentart, Ordner-Deckblätter mit Titel, PDF herunterladen. Recht A1 (Route und Menü); das Backend prüft dasselbe
 * und bleibt für den Titel massgeblich.
 */
@Component({
  selector: 'app-trennblaetter',
  imports: [MatButtonModule, Symbol],
  templateUrl: './trennblaetter.html',
  styleUrl: './trennblaetter.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Trennblaetter implements OnInit {
  private readonly artenApi = inject(DokumentartenService);
  private readonly api = inject(TrennblaetterService);
  private readonly dokument = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly texte = TEXTE.trennblaetter;
  protected readonly gruppe = TEXTE.menueGruppen.archiv;
  protected readonly titelMax = TITEL_MAX;
  protected readonly max = MAX_BLAETTER;

  protected readonly arten = signal<readonly ArtZeile[] | null>(null);
  protected readonly artenFehler = signal(false);
  protected readonly ohneArt = signal(0);
  protected readonly jeArt = signal<Readonly<Record<string, number>>>({});
  protected readonly deckblaetter = signal<readonly Deckblatt[]>([]);
  protected readonly titel = signal('');
  protected readonly titelGeprueft = signal(false);
  /** Felder mit ungültiger Anzahl, nach Schlüssel («ohne», Code der Art oder «deckblatt-<n>»). */
  protected readonly ungueltig = signal<ReadonlySet<string>>(new Set());
  protected readonly laeuft = signal(false);
  protected readonly meldung = signal<string | null>(null);
  protected readonly fehler = signal<string | null>(null);

  protected readonly titelZeichen = computed(() => titelLaenge(this.titel()));
  protected readonly titelMeldung = computed(() => {
    if (!this.titelGeprueft()) return null;
    switch (titelFehler(this.titel())) {
      case 'leer':
        return this.texte.titelLeer;
      case 'lang':
        return this.texte.titelLang;
      case 'zeichen':
        return this.texte.titelZeichen;
      default:
        return null;
    }
  });
  protected readonly blaetter = computed(() =>
    summe([
      this.ohneArt(),
      ...Object.values(this.jeArt()),
      ...this.deckblaetter().map((d) => d.anzahl),
    ]),
  );
  protected readonly bereit = computed(
    () =>
      this.blaetter() > 0 &&
      this.blaetter() <= MAX_BLAETTER &&
      this.ungueltig().size === 0 &&
      !this.laeuft(),
  );

  ngOnInit(): void {
    this.artenLaden();
  }

  protected artenLaden(): void {
    this.artenFehler.set(false);
    this.artenApi
      .apiV1DokumentartenGet()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (liste) =>
          this.arten.set(
            liste
              .filter((art) => art.aktiv)
              .map((art) => ({ code: art.code, name: art.name }))
              .sort((a, b) => a.name.localeCompare(b.name, 'de-CH')),
          ),
        error: () => {
          this.arten.set([]);
          this.artenFehler.set(true);
        },
      });
  }

  protected anzahlSetzen(schluessel: string, wert: string): void {
    const zahl = anzahlLesen(wert);
    this.ungueltig.update((alt) => {
      const neu = new Set(alt);
      if (zahl === null) neu.add(schluessel);
      else neu.delete(schluessel);
      return neu;
    });
    if (zahl === null) return;
    if (schluessel === 'ohne') {
      this.ohneArt.set(zahl);
    } else if (schluessel.startsWith('deckblatt-')) {
      const index = Number(schluessel.slice('deckblatt-'.length));
      this.deckblaetter.update((liste) =>
        liste.map((blatt, i) => (i === index ? { ...blatt, anzahl: zahl } : blatt)),
      );
    } else {
      this.jeArt.update((alt) => ({ ...alt, [schluessel]: zahl }));
    }
    this.meldung.set(null);
  }

  protected titelSetzen(wert: string): void {
    this.titel.set(wert);
    if (this.titelGeprueft() && titelFehler(wert) === null) this.titelGeprueft.set(false);
  }

  protected deckblattHinzufuegen(): void {
    this.titelGeprueft.set(true);
    if (titelFehler(this.titel()) !== null) return;
    const titel = this.titel().normalize('NFC').trim();
    this.deckblaetter.update((liste) => [...liste, { titel, anzahl: 1 }]);
    this.titel.set('');
    this.titelGeprueft.set(false);
    this.meldung.set(null);
  }

  protected deckblattEntfernen(index: number): void {
    this.deckblaetter.update((liste) => liste.filter((_, i) => i !== index));
    // Schlüssel der Deckblätter folgen dem Index; nach dem Entfernen gelten nur noch gültige Werte
    this.ungueltig.update((alt) => new Set([...alt].filter((s) => !s.startsWith('deckblatt-'))));
  }

  protected erstellen(): void {
    if (!this.bereit()) return;
    const arten = this.arten() ?? [];
    const jeArt = this.jeArt();
    this.laeuft.set(true);
    this.meldung.set(null);
    this.fehler.set(null);
    this.api
      .apiV1TrennblaetterPost(
        anfrage(
          this.ohneArt(),
          arten.map((art) => ({ code: art.code, anzahl: jeArt[art.code] ?? 0 })),
          this.deckblaetter(),
        ),
        'response',
        false,
        { httpHeaderAccept: 'application/pdf', transferCache: false },
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (antwort) => {
          this.laeuft.set(false);
          const pdf = antwort.body;
          if (!(pdf instanceof Blob) || pdf.size === 0) {
            this.fehler.set(this.texte.pdfUngueltig);
            return;
          }
          this.herunterladen(pdf);
          this.meldung.set(this.texte.fertig);
        },
        error: (fehler: unknown) => {
          this.laeuft.set(false);
          void this.fehlerMelden(fehler);
        },
      });
  }

  private async fehlerMelden(fehler: unknown): Promise<void> {
    const code = await httpFehlercode(fehler);
    this.fehler.set(
      code === 'TRENNBLATT_UNGUELTIG' ? this.texte.titelUngueltig : httpFehlerMeldung(fehler),
    );
  }

  private herunterladen(pdf: Blob): void {
    const url = URL.createObjectURL(pdf);
    const link = this.dokument.createElement('a');
    link.href = url;
    link.download = 'trennblaetter.pdf';
    link.hidden = true;
    this.dokument.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url));
  }
}
