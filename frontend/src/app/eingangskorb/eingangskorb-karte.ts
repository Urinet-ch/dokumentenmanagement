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
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import {
  Observable,
  Subject,
  catchError,
  distinctUntilChanged,
  map,
  of,
  startWith,
  switchMap,
  tap,
  timer,
} from 'rxjs';
import {
  Dokument,
  DokumentAenderung,
  Dokumentart,
  DokumenteService,
  Kontakt,
  KontakteService,
} from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { TitelAnzeige, anzeigeTitel } from '../gemeinsam/anzeige-titel';
import { httpFehlerText } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { EingangskorbEntwurfSpeicher } from './entwurf-speicher';
import {
  ZusatzFeld,
  anzeigeText,
  istListe,
  listeAus,
  listeZu,
  zusatzFelder as schemaZusatzFelder,
  zusatzGueltig,
} from '../dokument/aenderung';
import { KlassifikationenFormular } from '../dokument/klassifikationen-formular';
import {
  DokumentKlassifikationenService,
  KlassifikationenLadezustand,
  LEERE_DOKUMENT_KLASSIFIKATIONEN,
} from '../dokument/klassifikationen';
import { Listenfeld } from '../dokument/listenfeld';
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

function gleicheKlassifikationen(
  links: Readonly<Record<string, readonly string[]>>,
  rechts: Readonly<Record<string, readonly string[]>>,
): boolean {
  const linksCodes = Object.keys(links).sort();
  const rechtsCodes = Object.keys(rechts).sort();
  return (
    linksCodes.length === rechtsCodes.length &&
    linksCodes.every((code, index) => {
      const a = links[code] ?? [];
      const b = rechts[index] ? (rechts[rechtsCodes[index]] ?? []) : [];
      return (
        code === rechtsCodes[index] && a.length === b.length && a.every((wert, i) => wert === b[i])
      );
    })
  );
}

const NEU_LADEN = new Set(['STATUS_KONFLIKT', 'VERSION_KONFLIKT', 'NICHT_GEFUNDEN']);
const IM_FORMULAR: Record<string, string> = {
  BELEGNUMMER_VERGEBEN: 'belegnummer',
  PARTNER_UNBEKANNT: 'partner',
  KLASSIFIKATION_UNGUELTIG: '',
  ANFRAGE_UNGUELTIG: '',
};

/** Ein Dokument im Eingangskorb mit Vorschau, Vorschlag und den Aktionen nach Rechtematrix (Req 8.1, 8.5, 8.7). */
@Component({
  selector: 'app-eingangskorb-karte',
  imports: [MatButtonModule, RouterLink, TitelAnzeige, Listenfeld, KlassifikationenFormular],
  templateUrl: './eingangskorb-karte.html',
  styleUrl: './eingangskorb-karte.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EingangskorbKarte implements OnInit, OnDestroy {
  private readonly api = inject(DokumenteService);
  private readonly kontakte = inject(KontakteService);
  private readonly klassifikationenService = inject(DokumentKlassifikationenService);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly entwurfSpeicher = inject(EingangskorbEntwurfSpeicher);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);

  readonly dokument = input.required<Dokument>();
  readonly arten = input<ReadonlyMap<string, Dokumentart>>(new Map());
  /** Aktion gelungen; die Seite zeigt die Meldung und lädt neu. */
  readonly erledigt = output<string>();
  /** Der Benutzer hat nach einem Konflikt das Neuladen bestätigt. */
  readonly veraltet = output<string>();

  protected readonly texte = TEXTE.eingangskorb;
  protected readonly richtungen = ['EINNAHME', 'AUSGABE'];
  protected readonly vertraulichkeiten = ['', 'NORMAL', 'INTERN', 'PERSONAL'];

  protected readonly entwurf = linkedSignal<Entwurf>(() => entwurfAus(this.dokument()));
  protected readonly zusatzEingaben = linkedSignal<Record<string, string>>(() =>
    this.zusatzStart(),
  );
  protected readonly klassifikationEingaben = linkedSignal<
    Readonly<Record<string, readonly string[]>>
  >(() =>
    Object.fromEntries(
      Object.entries(this.dokument().klassifikationen ?? {}).map(([code, werte]) => [
        code,
        [...werte],
      ]),
    ),
  );
  private readonly aktuelleVersion = linkedSignal(() => this.dokument().version);
  private readonly aenderungGespeichert = signal(false);
  protected readonly modus = signal<Modus>('ansicht');
  protected readonly laeuft = signal(false);
  protected readonly fehler = signal('');
  protected readonly konfliktNeuLaden = signal(false);
  protected readonly entwurfWiederhergestellt = signal(false);
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
  protected readonly klassifikationenStatus = toSignal(
    toObservable(this.art).pipe(
      map((art) => art?.code ?? ''),
      distinctUntilChanged(),
      switchMap((code): Observable<KlassifikationenLadezustand> =>
        code
          ? this.klassifikationenService.fuerArt(code).pipe(
              map((daten) => ({ status: 'ok', daten }) as const),
              catchError(() => of({ status: 'fehler' }) as Observable<KlassifikationenLadezustand>),
              startWith({ status: 'laedt' } as const),
            )
          : of({ status: 'ok', daten: LEERE_DOKUMENT_KLASSIFIKATIONEN } as const),
      ),
    ),
    { initialValue: { status: 'laedt' } as const },
  );
  protected readonly klassifikationenWerte = computed(() => {
    const werte = { ...this.klassifikationEingaben() };
    const status = this.klassifikationenStatus();
    if (status.status === 'ok') {
      for (const feld of status.daten.art) {
        if (!Object.hasOwn(werte, feld.code) && feld.standardWert) {
          werte[feld.code] = [feld.standardWert];
        }
      }
    }
    return werte;
  });
  protected readonly klassifikationenAlt = computed(() => {
    const status = this.klassifikationenStatus();
    if (status.status !== 'ok') {
      return [];
    }
    const zugeordnet = new Set(status.daten.art.map((feld) => feld.code));
    return Object.entries(this.klassifikationenWerte())
      .filter(([code, werte]) => werte.length > 0 && !zugeordnet.has(code))
      .map(([code, werte]) => {
        const definition = status.daten.alle.find((feld) => feld.code === code);
        const anzeigename = new Map(definition?.werte.map((wert) => [wert.code, wert.name]) ?? []);
        return {
          name: definition?.name ?? code,
          werte: werte.map((wert) => anzeigename.get(wert) ?? wert).join(', '),
        };
      });
  });
  protected readonly klassifikationUngueltigeCodes = computed(() => [
    ...new Set([
      ...(this.dokument().klassifikationenUngueltig ?? []),
      ...this.fehlerFelder()
        .filter((feld) => feld.startsWith('klassifikationen.'))
        .map((feld) => feld.substring('klassifikationen.'.length)),
    ]),
  ]);
  protected readonly klassifikationFehlt = computed(() => {
    const status = this.klassifikationenStatus();
    if (status.status !== 'ok') {
      return [];
    }
    return status.daten.art
      .filter(
        (feld) => feld.pflicht && (this.klassifikationenWerte()[feld.code] ?? []).length === 0,
      )
      .map((feld) => `klassifikationen.${feld.code}`);
  });
  protected readonly mitFinanz = computed(() => this.art()?.gruppen.includes('finanz') ?? false);
  protected readonly zusatzFelder = computed(() =>
    schemaZusatzFelder(this.art()).filter((feld) => feld.name !== 'veroeffentlicht'),
  );
  protected readonly zusatzWerte = computed(() => {
    const werte = { ...this.zusatzEingaben() };
    const eigenschaften = objekt(objekt(this.art()?.zusatzSchema)['properties']);
    for (const feld of schemaZusatzFelder(this.art())) {
      if (Object.hasOwn(werte, feld.name)) {
        continue;
      }
      const standard = objekt(eigenschaften[feld.name])['default'];
      const wert =
        standard ??
        (feld.name === 'zugriff' ? 'mitglieder' : feld.name === 'veroeffentlicht' ? false : '');
      werte[feld.name] =
        wert === null || wert === undefined
          ? ''
          : Array.isArray(wert)
            ? listeZu(wert.map(String))
            : String(wert);
    }
    return werte;
  });
  protected readonly istListe = istListe;
  protected readonly zugriffOptionen = ['oeffentlich', 'mitglieder', 'vorstand'];
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
  protected readonly zusatzFehlt = computed(() => {
    const werte = this.zusatzWerte();
    // Auch Pflicht nur über `required` im Schema (Seed vhf-gsk) zählt.
    const ausSchema = this.zusatzFelder()
      .filter((f) => f.pflicht)
      .map((f) => `zusatz.${f.name}`);
    const pflicht = new Set([
      ...(this.art()?.pflichtfelder ?? []).filter((f) => f.startsWith('zusatz.')),
      ...ausSchema,
    ]);
    return [...pflicht].filter((f) => {
      const name = f.substring('zusatz.'.length);
      const wert = werte[name];
      return (
        wert === undefined ||
        wert.trim() === '' ||
        (name === 'zugriff' && !this.zugriffOptionen.includes(wert))
      );
    });
  });
  protected readonly zusatzFehltImFormular = computed(() => {
    const bearbeitbar = new Set(schemaZusatzFelder(this.art()).map((feld) => feld.name));
    return this.zusatzFehlt().filter((feld) => bearbeitbar.has(feld.substring('zusatz.'.length)));
  });
  protected readonly zusatzUngueltig = computed(() => {
    const werte = this.zusatzWerte();
    return schemaZusatzFelder(this.art())
      .filter((feld) => feld.name !== 'veroeffentlicht' && feld.name !== 'zugriff')
      .filter((feld) => !zusatzGueltig(feld, werte[feld.name] ?? ''))
      .map((feld) => `zusatz.${feld.name}`);
  });
  protected readonly zusatzAlt = computed(() => {
    const bekannt = new Set(this.zusatzFelder().map((feld) => feld.name));
    const alteFelder = new Map(
      schemaZusatzFelder(this.arten().get(this.dokument().art ?? '')).map((feld) => [
        feld.name,
        feld,
      ]),
    );
    return Object.entries(this.zusatzWerte())
      .filter(([name, wert]) => wert.trim() !== '' && !bekannt.has(name))
      .map(([name, wert]) => {
        const feld = alteFelder.get(name);
        const werte =
          feld && istListe(feld)
            ? listeAus(wert)
                .map((eintrag) => anzeigeText(feld, eintrag))
                .join(', ')
            : feld
              ? anzeigeText(feld, wert)
              : wert;
        return { name: feld ? this.zusatzBeschriftung(feld) : name, wert: werte };
      });
  });
  protected readonly regelWarnungen = computed(() =>
    this.dokument().warnungen.filter((warnung) => warnung.code !== 'BELEGNUMMER_BEKANNT'),
  );
  /**
   * Steht die Art nur im KI-Vorschlag oder ist sie hier ungespeichert gewählt, geht sie als `?art=` an die
   * Dokumentansicht; dort gilt sie als Artwechsel, damit die Zusatzfelder dieser Art erscheinen (Review #106). Die
   * Dokumentansicht übernimmt nur eine aktive Art; eine inaktive geht nicht mit (Review #107).
   */
  private readonly artNeu = computed(() => {
    const art = this.entwurf().art;
    return art && art !== (this.dokument().art ?? '') ? art : null;
  });
  protected readonly artUebernehmen = computed(() => {
    const art = this.artNeu();
    return art && this.art()?.aktiv ? { art } : null;
  });
  protected readonly artInaktiv = computed(() => !!this.artNeu() && this.art()?.aktiv === false);
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
      ...(this.mitFinanz()
        ? [
            { feld: 'belegnummer' as const, wert: e.belegnummer },
            {
              feld: 'richtung' as const,
              wert: e.richtung ? (this.texte.richtung[e.richtung] ?? e.richtung) : '',
            },
            { feld: 'betrag' as const, wert: e.betrag ? betragAnzeige(e.betrag, e.waehrung) : '' },
            { feld: 'faelligkeit' as const, wert: datumAnzeige(e.faelligkeit) },
          ]
        : []),
    ];
    return zeilen.filter((z) => z.wert !== '');
  });

  protected readonly weitereFelder = computed(() => {
    const felder: { feld: EntwurfFeld; typ: string; modus?: string }[] = [
      { feld: 'projekt', typ: 'text' },
    ];
    if (this.mitFinanz()) {
      felder.unshift(
        { feld: 'belegnummer', typ: 'text' },
        { feld: 'betrag', typ: 'text', modus: 'decimal' },
        { feld: 'waehrung', typ: 'text' },
        { feld: 'faelligkeit', typ: 'date' },
        { feld: 'qrReferenz', typ: 'text' },
      );
    }

    if (this.mitGueltigkeit()) {
      felder.push({ feld: 'gueltigAb', typ: 'date' }, { feld: 'gueltigBis', typ: 'date' });
    }
    return felder;
  });

  constructor() {
    this.suche
      .pipe(
        // Gleiches Muster wie im Bearbeiten-Formular (design.md «Umsetzung 4.6»): Abbruch vor dem Warten, damit jeder
        // neue Begriff (auch '' nach «Wählen»/«Entfernen») eine laufende Wartezeit oder einen Abruf verwirft (Review #117).
        switchMap((q): Observable<readonly Kontakt[] | null> =>
          q.length < 2
            ? of(null)
            : timer(300).pipe(
                switchMap(() => this.kontakte.apiV1KontakteGet(q).pipe(catchError(() => of([])))),
              ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((liste) => this.treffer.set(liste));
  }

  ngOnInit(): void {
    const dokument = this.dokument();
    const benutzerId = this.anmeldung.benutzer()?.id;
    if (benutzerId) {
      const gespeichert = this.entwurfSpeicher.lesen(benutzerId, dokument.id);
      if (gespeichert) {
        this.entwurf.set(gespeichert.entwurf);
        this.zusatzEingaben.set(gespeichert.zusatz);
        this.entwurfWiederhergestellt.set(true);
        this.vertraulichkeitGewaehlt = gespeichert.entwurf.vertraulichkeit !== '';
      }
    }
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

  protected klassifikationNamen(felder: readonly string[]): string {
    const status = this.klassifikationenStatus();
    return felder
      .map((feld) => {
        const code = feld.substring('klassifikationen.'.length);
        return status.status === 'ok'
          ? (status.daten.art.find((klassifikation) => klassifikation.code === code)?.name ?? code)
          : code;
      })
      .join(', ');
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
    if (feld === 'art') {
      this.aenderungGespeichert.set(false);
    }
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

  protected setzeZusatz(name: string, wert: string): void {
    this.aenderungGespeichert.set(false);
    this.zusatzEingaben.update((werte) => ({ ...werte, [name]: wert }));
  }

  protected setzeKlassifikationen(werte: Readonly<Record<string, readonly string[]>>): void {
    this.aenderungGespeichert.set(false);
    this.klassifikationEingaben.set(werte);
  }

  protected zusatzBeschriftung(feld: ZusatzFeld): string {
    const namen: Record<string, string> = {
      reihe: 'Reihe',
      ausgabe: 'Ausgabe-Nr.',
      jahr: 'Erscheinungsjahr',
      fachgebiet: 'Fachgebiet',
      zugriff: 'Zugriffsstufe',
    };
    return namen[feld.name] ?? this.texte.feld[feld.name] ?? this.texte.zusatzFeld(feld.name);
  }

  protected zusatzOption(feld: ZusatzFeld, wert: string): string {
    if (feld.name !== 'zugriff') {
      return wert;
    }
    return this.texte.zugriff[wert] ?? wert;
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
    this.suche.next('');
    this.treffer.set(null);
    this.fokus('[data-test="partner-gewaehlt"]');
  }

  protected partnerEntfernen(): void {
    this.entwurf.update((e) => ({ ...e, partnerId: '', partnerName: '' }));
    this.suche.next('');
    this.treffer.set(null);
    this.fokus('[data-test="partner-suche"]');
  }

  protected kontaktName(kontakt: Kontakt): string {
    const name = [kontakt.vorname, kontakt.name].filter(Boolean).join(' ');
    return kontakt.ort ? `${name}, ${kontakt.ort}` : name;
  }

  protected oeffnen(modus: Modus): void {
    this.fehler.set('');
    this.konfliktNeuLaden.set(false);
    this.fehlerFelder.set([]);
    this.modus.set(modus);
    this.fokus(`[data-fokus="${modus}"]`);
  }

  protected schliessen(): void {
    if (this.modus() === 'formular') {
      this.entwurf.set(entwurfAus(this.dokument()));
      this.zusatzEingaben.set(this.zusatzStart());
      this.klassifikationEingaben.set(this.dokument().klassifikationen ?? {});
      this.aenderungGespeichert.set(false);
      this.vertraulichkeitGewaehlt = false;
    }
    const ziel =
      this.modus() === 'rueckfragePdfa' || this.modus() === 'rueckfrageFrist'
        ? this.zurueck
        : 'ansicht';
    this.fehler.set('');
    this.konfliktNeuLaden.set(false);
    this.fehlerFelder.set([]);
    this.modus.set(ziel);
    this.fokus(ziel === 'formular' ? '[data-fokus="formular"]' : '[data-test="aktionen"] button');
  }

  protected entwurfVerwerfen(): void {
    const benutzerId = this.anmeldung.benutzer()?.id;
    if (benutzerId) {
      this.entwurfSpeicher.loeschen(benutzerId, this.id());
    }
    this.entwurf.set(entwurfAus(this.dokument()));
    this.zusatzEingaben.set(this.zusatzStart());
    this.aenderungGespeichert.set(false);
    this.vertraulichkeitGewaehlt = false;
    this.entwurfWiederhergestellt.set(false);
  }

  protected bestaetigen(zusatz: Zusatz = {}): void {
    if (this.klassifikationenStatus().status !== 'ok') {
      this.imFormular(this.texte.klassifikationenFehler, []);
      return;
    }
    const benutzerId = this.anmeldung.benutzer()?.id ?? '';
    const entwurf = this.entwurf();
    if (!entwurf.art) {
      this.imFormular(this.texte.artWaehlen, ['art']);
      return;
    }
    if (this.mitFinanz() && !betragGueltig(entwurf.betrag)) {
      this.imFormular(this.texte.betragUngueltig, ['betrag']);
      return;
    }
    const fehlendeZusatzfelder = this.zusatzFehltImFormular();
    if (fehlendeZusatzfelder.length > 0) {
      this.imFormular(
        this.texte.fehlerPflichtfelder(fehlendeZusatzfelder.map(feldName).join(', ')),
        fehlendeZusatzfelder,
      );
      return;
    }
    const ungueltigeZusatzfelder = this.zusatzUngueltig();
    if (ungueltigeZusatzfelder.length > 0) {
      this.imFormular(
        this.texte.zusatzUngueltig(ungueltigeZusatzfelder.map(feldName).join(', ')),
        ungueltigeZusatzfelder,
      );
      return;
    }
    const fehlendeKlassifikationen = this.klassifikationFehlt();
    if (fehlendeKlassifikationen.length > 0) {
      this.imFormular(
        this.texte.fehlerPflichtfelder(this.klassifikationNamen(fehlendeKlassifikationen)),
        fehlendeKlassifikationen,
      );
      return;
    }
    if (this.dokument().pdfaFehlt && !zusatz.ohnePdfa) {
      this.rueckfrage('rueckfragePdfa', zusatz);
      return;
    }
    const artNeu = this.artNeu();
    const zusatzBody = this.zusatzBody();
    const klassifikationenBody = this.klassifikationenBody();
    const bisherigerZusatz = objekt(this.dokument().zusatz);
    const bisherigeKlassifikationen = this.dokument().klassifikationen ?? {};
    const klassifikationenGeaendert = !gleicheKlassifikationen(
      this.klassifikationenWerte(),
      bisherigeKlassifikationen,
    );
    const aendern = this.aenderungGespeichert()
      ? of(this.dokument())
      : JSON.stringify(zusatzBody) === JSON.stringify(bisherigerZusatz) &&
          !klassifikationenGeaendert
        ? of(this.dokument())
        : this.api
            .apiV1DokumenteIdPatch(this.id(), this.etag(), {
              ...(artNeu ? { art: artNeu } : {}),
              zusatz: zusatzBody,
              ...(klassifikationenGeaendert ? { klassifikationen: klassifikationenBody } : {}),
            } as DokumentAenderung)
            .pipe(
              tap((dokument) => {
                this.aktuelleVersion.set(dokument.version);
                this.aenderungGespeichert.set(true);
              }),
            );
    this.ausfuehren(
      aendern.pipe(
        switchMap(() =>
          this.api.apiV1DokumenteIdBestaetigenPost(
            this.id(),
            `"${this.aktuelleVersion()}"`,
            bestaetigungAus(entwurf, this.dokument(), { ...zusatz, mitFinanz: this.mitFinanz() }),
          ),
        ),
      ),
      this.texte.erfolgBestaetigt(this.titel()),
      zusatz,
      benutzerId,
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
    return `"${this.aktuelleVersion()}"`;
  }

  private zusatzStart(): Record<string, string> {
    const dokument = this.dokument();
    const vorhandene = objekt(dokument.zusatz);
    return Object.fromEntries(
      Object.entries(vorhandene).map(([name, wert]) => [
        name,
        typeof wert === 'string' || typeof wert === 'number' || typeof wert === 'boolean'
          ? String(wert)
          : Array.isArray(wert) && wert.every((e) => typeof e === 'string')
            ? listeZu(wert)
            : '',
      ]),
    );
  }

  private klassifikationenBody(): Record<string, string[]> {
    const status = this.klassifikationenStatus();
    if (status.status !== 'ok') {
      return {};
    }
    const result: Record<string, string[]> = {};
    for (const feld of status.daten.art) {
      const werte = this.klassifikationenWerte()[feld.code] ?? [];
      if (werte.length > 0) {
        result[feld.code] = [...werte];
      }
    }
    return result;
  }

  private zusatzBody(): Record<string, unknown> {
    // Bei abweichender Art nur Schlüssel der gewählten Art behalten (additionalProperties false im Schema).
    const bekannt = new Set(Object.keys(objekt(objekt(this.art()?.zusatzSchema)['properties'])));
    const body = Object.fromEntries(
      Object.entries(objekt(this.dokument().zusatz)).filter(
        ([k]) => !this.artNeu() || bekannt.has(k),
      ),
    );
    const werte = this.zusatzWerte();
    for (const feld of schemaZusatzFelder(this.art())) {
      if (feld.name === 'veroeffentlicht') {
        body[feld.name] = false;
        continue;
      }
      const roh = (werte[feld.name] ?? '').trim();
      if (roh === '') {
        delete body[feld.name];
        continue;
      }
      if (feld.name === 'zugriff') {
        if (this.zugriffOptionen.includes(roh)) {
          body[feld.name] = roh;
        } else {
          delete body[feld.name];
        }
        continue;
      }
      switch (feld.typ) {
        case 'zahl':
          body[feld.name] = Number(roh.replace(/'/g, '').replace(',', '.'));
          break;
        case 'ganzzahl':
          body[feld.name] = Number(roh.replace(/'/g, ''));
          break;
        case 'janein':
          body[feld.name] = roh === 'true';
          break;
        case 'mehrfach':
        case 'liste':
          body[feld.name] = listeAus(roh);
          break;
        default:
          body[feld.name] = roh;
      }
    }
    return body;
  }

  private ausfuehren(
    aufruf: Observable<unknown>,
    erfolg: string,
    zusatz: Zusatz = {},
    benutzerId = '',
  ): void {
    this.laeuft.set(true);
    this.fehler.set('');
    this.konfliktNeuLaden.set(false);
    aufruf.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.laeuft.set(false);
        if (benutzerId) {
          this.entwurfSpeicher.loeschen(benutzerId, this.id());
          this.entwurfWiederhergestellt.set(false);
        }
        this.erledigt.emit(erfolg);
      },
      error: (fehler: unknown) => {
        this.laeuft.set(false);
        const aktionFehler = fehlerAus(fehler);
        if (
          aktionFehler.status === 401 &&
          benutzerId &&
          this.anmeldung.zustand() === 'abgelaufen'
        ) {
          this.entwurfSpeicher.speichern(
            benutzerId,
            this.id(),
            this.entwurf(),
            this.zusatzEingaben(),
          );
        }
        this.fehlerBehandeln(aktionFehler, zusatz);
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
    if (
      NEU_LADEN.has(code) ||
      fehler.status === 404 ||
      fehler.status === 412 ||
      (fehler.status === 409 && (!code || NEU_LADEN.has(code)))
    ) {
      this.konfliktNeuLaden.set(true);
      this.fehler.set(this.texte.fehlercode[code] ?? this.texte.fehlercode['VERSION_KONFLIKT']);
      return;
    }
    if (code in IM_FORMULAR && this.modus() !== 'verwerfen') {
      const feld = IM_FORMULAR[code];
      const felder = fehler.felder.length > 0 ? fehler.felder.map(camel) : feld ? [feld] : [];
      this.imFormular(this.texte.fehlercode[code], felder);
      return;
    }
    this.fehler.set(this.meldung(fehler));
  }

  private meldung(fehler: AktionFehler): string {
    // 409 mit fachlichem Code: dessen Text statt «Bitte neu laden», denn hier gibt es keinen «Neu laden»-Knopf
    const fachlich =
      fehler.status === 409 && fehler.fehlercode
        ? this.texte.fehlercode[fehler.fehlercode]
        : undefined;
    if (fachlich) {
      return fachlich;
    }
    if ([0, 401, 403, 404, 409, 412, 429].includes(fehler.status) || fehler.status >= 500) {
      return httpFehlerText(fehler.status, fehler.retryAfter ?? null);
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

  protected neuLadenNachKonflikt(): void {
    const meldung = this.fehler();
    this.fehler.set('');
    this.konfliktNeuLaden.set(false);
    this.veraltet.emit(meldung);
  }

  private rueckfrage(modus: 'rueckfragePdfa' | 'rueckfrageFrist', zusatz: Zusatz): void {
    if (this.modus() !== 'rueckfragePdfa' && this.modus() !== 'rueckfrageFrist') {
      this.zurueck = this.modus();
    }
    this.zusatz = zusatz;
    this.fehler.set('');
    this.konfliktNeuLaden.set(false);
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

function objekt(wert: unknown): Record<string, unknown> {
  return wert && typeof wert === 'object' && !Array.isArray(wert)
    ? (wert as Record<string, unknown>)
    : {};
}
