import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnDestroy,
  OnInit,
  afterNextRender,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import {
  Observable,
  Subject,
  debounceTime,
  distinctUntilChanged,
  of,
  switchMap,
  catchError,
} from 'rxjs';
import {
  Dokument,
  Dokumentart,
  DokumenteService,
  Kontakt,
  KontakteService,
} from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { TitelAnzeige, anzeigeTitel } from '../gemeinsam/anzeige-titel';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import {
  AktionFehler,
  Entwurf,
  EntwurfFeld,
  bestaetigungAus,
  betragAnzeige,
  betragGueltig,
  bittePruefen,
  datumAnzeige,
  eingangAnzeige,
  entwurfAus,
  erkannterPartner,
  feldName,
  fehlerAus,
} from './entwurf';

type Modus = 'ansicht' | 'formular' | 'rueckfragePdfa' | 'rueckfrageFrist' | 'verwerfen';
type Zusatz = { ohnePdfa?: boolean; fristAbgelaufenAkzeptiert?: boolean };

const NEU_LADEN = new Set(['STATUS_KONFLIKT', 'VERSION_KONFLIKT', 'NICHT_GEFUNDEN']);
const IM_FORMULAR: Record<string, string> = {
  BELEGNUMMER_VERGEBEN: 'belegnummer',
  PARTNER_UNBEKANNT: 'partner',
  ANFRAGE_UNGUELTIG: '',
};

/** Ein Dokument im Eingangskorb mit Vorschau, Vorschlag und den Aktionen nach Rechtematrix (Req 8.1, 8.5, 8.7). */
@Component({
  selector: 'app-eingangskorb-karte',
  imports: [MatButtonModule, RouterLink, TitelAnzeige],
  templateUrl: './eingangskorb-karte.html',
  styleUrl: './eingangskorb-karte.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EingangskorbKarte implements OnInit, OnDestroy {
  private readonly api = inject(DokumenteService);
  private readonly kontakte = inject(KontakteService);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);

  readonly dokument = input.required<Dokument>();
  readonly arten = input<ReadonlyMap<string, Dokumentart>>(new Map());
  /** Aktion gelungen; die Seite zeigt die Meldung und lädt neu. */
  readonly erledigt = output<string>();
  /** Das Dokument hat sich inzwischen geändert; die Seite zeigt die Meldung und lädt neu. */
  readonly veraltet = output<string>();

  protected readonly texte = TEXTE.eingangskorb;
  protected readonly richtungen = ['EINNAHME', 'AUSGABE'];
  protected readonly vertraulichkeiten = ['', 'NORMAL', 'INTERN', 'PERSONAL'];

  protected readonly entwurf = linkedSignal<Entwurf>(() => entwurfAus(this.dokument()));
  protected readonly modus = signal<Modus>('ansicht');
  protected readonly laeuft = signal(false);
  protected readonly fehler = signal('');
  protected readonly fehlerFelder = signal<readonly string[]>([]);
  protected readonly grund = signal('');
  protected readonly vorschauUrl = signal<string | null>(null);
  protected readonly treffer = signal<readonly Kontakt[] | null>(null);
  private zusatz: Zusatz = {};
  /** Hat der Benutzer die Vertraulichkeit im Formular selbst gewählt? Sonst folgt sie der Art (Req 2.22). */
  private vertraulichkeitGewaehlt = false;
  private zurueck: Modus = 'ansicht';
  private readonly suche = new Subject<string>();

  protected readonly id = computed(() => this.dokument().id);
  /** Titel, Vorschlag oder Dateiname als Platzhalter (Req 8.11); der Dateiname geht nie in `titel` des Entwurfs. */
  protected readonly anzeige = computed(() =>
    anzeigeTitel(this.dokument().titel, this.entwurf().titel, this.dokument().dateiname),
  );
  protected readonly titel = computed(() => this.anzeige().text);
  protected readonly status = computed(() => this.dokument().status);
  protected readonly imKorb = computed(() => this.status() === 'EINGANGSKORB');
  protected readonly darfBestaetigen = computed(() => this.imKorb() && this.anmeldung.darf('A7'));
  protected readonly darfAendern = computed(
    () => this.darfBestaetigen() && this.anmeldung.darf('A6'),
  );
  protected readonly darfVertraulichkeit = computed(() => this.anmeldung.darf('A8'));
  protected readonly darfVerwerfen = computed(
    () => (this.imKorb() || this.status() === 'FEHLER') && this.anmeldung.darf('A9'),
  );
  protected readonly darfWiederholen = computed(
    () =>
      (this.status() === 'FEHLER' || this.status() === 'ARCHIVIERUNG_FEHLER') &&
      this.anmeldung.darf('A10'),
  );
  protected readonly ohneAktion = computed(
    () => !this.darfBestaetigen() && !this.darfVerwerfen() && !this.darfWiederholen(),
  );
  protected readonly art = computed(() => this.arten().get(this.entwurf().art));
  protected readonly artenWahl = computed(() =>
    [...this.arten().values()].filter((a) => a.aktiv || a.code === this.entwurf().art),
  );
  protected readonly mitGueltigkeit = computed(() => {
    const art = this.art();
    return (
      !!art &&
      (art.aufbewahrungAb === 'GUELTIG_BIS' ||
        art.pflichtfelder.includes('gueltig_ab') ||
        art.pflichtfelder.includes('gueltig_bis'))
    );
  });
  /**
   * Pflicht-Zusatzfelder der Art, die am Dokument fehlen. Der Eingangskorb hat dafür kein Eingabefeld, und der Body der
   * Bestätigung nimmt keine Zusatzfelder an (design.md «Umsetzung 3.6», bekannte Grenze).
   */
  protected readonly zusatzFehlt = computed(() => {
    const vorhanden = (this.dokument().zusatz ?? {}) as Record<string, unknown>;
    return (this.art()?.pflichtfelder ?? [])
      .filter((f) => f.startsWith('zusatz.'))
      .filter((f) => {
        const wert = vorhanden[f.substring('zusatz.'.length)];
        return wert === undefined || wert === null || wert === '';
      });
  });
  protected readonly erkannterPartner = computed(() =>
    this.entwurf().partnerId ? null : erkannterPartner(this.dokument()),
  );
  protected readonly belegnummerWarnungen = computed(() =>
    this.dokument().warnungen.filter((w) => w.code === 'BELEGNUMMER_BEKANNT'),
  );
  /** Zeilen der Übersicht; leere Felder fallen weg. */
  protected readonly angaben = computed(() => {
    const e = this.entwurf();
    const zeilen: { feld: EntwurfFeld; wert: string }[] = [
      { feld: 'art', wert: this.art()?.name ?? e.art },
      { feld: 'datum', wert: datumAnzeige(e.datum) },
      { feld: 'partnerName', wert: e.partnerName },
      { feld: 'belegnummer', wert: e.belegnummer },
      { feld: 'richtung', wert: e.richtung ? (this.texte.richtung[e.richtung] ?? e.richtung) : '' },
      { feld: 'betrag', wert: e.betrag ? betragAnzeige(e.betrag, e.waehrung) : '' },
      { feld: 'faelligkeit', wert: datumAnzeige(e.faelligkeit) },
    ];
    return zeilen.filter((z) => z.wert !== '');
  });

  protected readonly weitereFelder = computed(() => {
    const felder: { feld: EntwurfFeld; typ: string; modus?: string }[] = [
      { feld: 'belegnummer', typ: 'text' },
      { feld: 'betrag', typ: 'text', modus: 'decimal' },
      { feld: 'waehrung', typ: 'text' },
      { feld: 'faelligkeit', typ: 'date' },
      { feld: 'qrReferenz', typ: 'text' },
      { feld: 'projekt', typ: 'text' },
    ];
    if (this.mitGueltigkeit()) {
      felder.push({ feld: 'gueltigAb', typ: 'date' }, { feld: 'gueltigBis', typ: 'date' });
    }
    return felder;
  });

  constructor() {
    this.suche
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q): Observable<readonly Kontakt[] | null> =>
          q.length < 2
            ? of(null)
            : this.kontakte.apiV1KontakteGet(q).pipe(catchError(() => of([]))),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((liste) => this.treffer.set(liste));
  }

  ngOnInit(): void {
    const dokument = this.dokument();
    if (!dokument.seiten || dokument.seiten < 1) {
      return;
    }
    this.api
      .apiV1DokumenteIdVorschauGet(dokument.id, 1)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (bild) => this.vorschauUrl.set(URL.createObjectURL(bild)),
        error: () => this.vorschauUrl.set(null),
      });
  }

  ngOnDestroy(): void {
    const url = this.vorschauUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
  }

  protected feldText(feld: string): string {
    return feld === 'partnerName' ? this.texte.feld['partner'] : this.texte.feld[feld];
  }

  protected zusatzNamen(): string {
    return this.zusatzFehlt().map(feldName).join(', ');
  }

  protected bittePruefen(feld: EntwurfFeld): boolean {
    return bittePruefen(this.dokument(), feld);
  }

  protected eingang(): string {
    return this.texte.eingang(eingangAnzeige(this.dokument().eingang));
  }

  protected pflicht(feld: string): boolean {
    const snake = feld.replace(/[A-Z]/g, (b) => `_${b.toLowerCase()}`);
    return (
      this.art()?.pflichtfelder.includes(snake === 'partner_name' ? 'partner' : snake) ?? false
    );
  }

  protected ungueltig(feld: string): boolean {
    return this.fehlerFelder().includes(feld);
  }

  protected setze(feld: EntwurfFeld, wert: string): void {
    if (feld === 'vertraulichkeit') {
      this.vertraulichkeitGewaehlt = true;
    }
    this.entwurf.update((e) => {
      const neu = { ...e, [feld]: wert };
      if (feld === 'art' && !this.vertraulichkeitGewaehlt) {
        // Der Vorschlag zur Vertraulichkeit gilt nur für die vorgeschlagene Art; sonst greift der Standard der Art.
        const start = entwurfAus(this.dokument());
        neu.vertraulichkeit = wert === start.art ? start.vertraulichkeit : '';
      }
      return neu;
    });
  }

  protected wert(ereignis: Event): string {
    return (ereignis.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement).value;
  }

  protected partnerSuchen(q: string): void {
    this.suche.next(q.trim());
  }

  protected partnerWaehlen(kontakt: Kontakt): void {
    const name = [kontakt.vorname, kontakt.name].filter(Boolean).join(' ');
    this.entwurf.update((e) => ({ ...e, partnerId: kontakt.id ?? '', partnerName: name }));
    this.treffer.set(null);
    this.fokus('[data-test="partner-gewaehlt"]');
  }

  protected partnerEntfernen(): void {
    this.entwurf.update((e) => ({ ...e, partnerId: '', partnerName: '' }));
    this.fokus('[data-test="partner-suche"]');
  }

  protected kontaktName(kontakt: Kontakt): string {
    const name = [kontakt.vorname, kontakt.name].filter(Boolean).join(' ');
    return kontakt.ort ? `${name}, ${kontakt.ort}` : name;
  }

  protected oeffnen(modus: Modus): void {
    this.fehler.set('');
    this.fehlerFelder.set([]);
    this.modus.set(modus);
    this.fokus(`[data-fokus="${modus}"]`);
  }

  protected schliessen(): void {
    if (this.modus() === 'formular') {
      this.entwurf.set(entwurfAus(this.dokument()));
      this.vertraulichkeitGewaehlt = false;
    }
    const ziel =
      this.modus() === 'rueckfragePdfa' || this.modus() === 'rueckfrageFrist'
        ? this.zurueck
        : 'ansicht';
    this.fehler.set('');
    this.fehlerFelder.set([]);
    this.modus.set(ziel);
    this.fokus(ziel === 'formular' ? '[data-fokus="formular"]' : '[data-test="aktionen"] button');
  }

  protected bestaetigen(zusatz: Zusatz = {}): void {
    const entwurf = this.entwurf();
    if (!entwurf.art) {
      this.imFormular(this.texte.artWaehlen, ['art']);
      return;
    }
    if (!betragGueltig(entwurf.betrag)) {
      this.imFormular(this.texte.betragUngueltig, ['betrag']);
      return;
    }
    if (this.dokument().pdfaFehlt && !zusatz.ohnePdfa) {
      this.rueckfrage('rueckfragePdfa', zusatz);
      return;
    }
    this.ausfuehren(
      this.api.apiV1DokumenteIdBestaetigenPost(
        this.id(),
        this.etag(),
        bestaetigungAus(entwurf, this.dokument(), zusatz),
      ),
      this.texte.erfolgBestaetigt(this.titel()),
      zusatz,
    );
  }

  protected rueckfrageJa(): void {
    if (this.modus() === 'rueckfragePdfa') {
      this.bestaetigen({ ...this.zusatz, ohnePdfa: true });
    } else {
      this.bestaetigen({ ...this.zusatz, fristAbgelaufenAkzeptiert: true });
    }
  }

  protected verwerfen(): void {
    const grund = this.grund().trim();
    if (!grund) {
      this.fehler.set(this.texte.grundFehlt);
      this.fehlerFelder.set(['grund']);
      this.fokus('[data-fokus="verwerfen"]');
      return;
    }
    this.ausfuehren(
      this.api.apiV1DokumenteIdVerwerfenPost(this.id(), this.etag(), { grund }),
      this.texte.erfolgVerworfen(this.titel()),
    );
  }

  protected wiederholen(): void {
    const meldung =
      this.status() === 'ARCHIVIERUNG_FEHLER'
        ? this.texte.erfolgArchivierung(this.titel())
        : this.texte.erfolgWiederholt(this.titel());
    this.ausfuehren(this.api.apiV1DokumenteIdWiederholenPost(this.id(), this.etag()), meldung);
  }

  private etag(): string {
    return `"${this.dokument().version}"`;
  }

  private ausfuehren(aufruf: Observable<unknown>, erfolg: string, zusatz: Zusatz = {}): void {
    this.laeuft.set(true);
    this.fehler.set('');
    aufruf.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.laeuft.set(false);
        this.erledigt.emit(erfolg);
      },
      error: (fehler: unknown) => {
        this.laeuft.set(false);
        this.fehlerBehandeln(fehlerAus(fehler), zusatz);
      },
    });
  }

  private fehlerBehandeln(fehler: AktionFehler, zusatz: Zusatz): void {
    const code = fehler.fehlercode ?? '';
    if (code === 'PFLICHTFELD_FEHLT') {
      const felder = fehler.felder.filter((f) => f !== 'ohnePdfa');
      if (felder.length === 0 && fehler.felder.includes('ohnePdfa')) {
        this.rueckfrage('rueckfragePdfa', zusatz);
      } else {
        const namen = felder.map(feldName).join(', ');
        const meldung = this.texte.fehlerPflichtfelder(namen);
        this.imFormular(
          felder.some((f) => f.startsWith('zusatz.'))
            ? `${meldung} ${this.texte.zusatzBleibtImKorb}`
            : meldung,
          felder.map(camel),
        );
      }
      return;
    }
    if (code === 'FRIST_ABGELAUFEN') {
      this.rueckfrage('rueckfrageFrist', zusatz);
      return;
    }
    if (NEU_LADEN.has(code) || fehler.status === 404 || fehler.status === 412) {
      this.veraltet.emit(this.texte.fehlercode[code] ?? this.texte.fehlercode['STATUS_KONFLIKT']);
      return;
    }
    if (code in IM_FORMULAR && this.modus() !== 'verwerfen') {
      const feld = IM_FORMULAR[code];
      this.imFormular(this.texte.fehlercode[code], feld ? [feld] : []);
      return;
    }
    this.fehler.set(this.meldung(fehler));
  }

  private meldung(fehler: AktionFehler): string {
    if (fehler.status === 0) {
      return this.texte.fehlerVerbindung;
    }
    if (fehler.status === 401) {
      return this.texte.fehlerSitzung;
    }
    if (fehler.status === 403) {
      return this.texte.fehlercode['NICHT_ERLAUBT'];
    }
    return (
      (fehler.fehlercode && this.texte.fehlercode[fehler.fehlercode]) || this.texte.fehlerAllgemein
    );
  }

  private imFormular(meldung: string, felder: readonly string[]): void {
    if (this.darfAendern()) {
      this.modus.set('formular');
    }
    this.fehler.set(meldung);
    this.fehlerFelder.set(felder);
    this.fokus('[data-test="fehler"]');
  }

  private rueckfrage(modus: 'rueckfragePdfa' | 'rueckfrageFrist', zusatz: Zusatz): void {
    if (this.modus() !== 'rueckfragePdfa' && this.modus() !== 'rueckfrageFrist') {
      this.zurueck = this.modus();
    }
    this.zusatz = zusatz;
    this.fehler.set('');
    this.modus.set(modus);
    this.fokus(`[data-fokus="${modus}"]`);
  }

  private fokus(selektor: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selektor)?.focus(), {
      injector: this.injector,
    });
  }
}

function camel(feld: string): string {
  return feld.replace(/_([a-z])/g, (_, b: string) => b.toUpperCase());
}
