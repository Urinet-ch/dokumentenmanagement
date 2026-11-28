import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import {
  Observable,
  Subscription,
  catchError,
  distinctUntilChanged,
  map,
  of,
  startWith,
  switchMap,
} from 'rxjs';
import { Dokument, Dokumentart, DownloadAdresse, Quelle, WissensbasisService } from '../gemeinsam/api';
import { anzeigeText, listeZu, zusatzFelder, zusatzGueltig } from './aenderung';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { TitelAnzeige, anzeigeTitel } from '../gemeinsam/anzeige-titel';
import { betrag, datum, zeitpunkt } from '../gemeinsam/format';
import { httpFehlerMeldung } from '../gemeinsam/http-fehler';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DokumentPort, Variante } from './dokument-port';
import { MetadatenFormular } from './metadaten-formular';
import {
  DokumentKlassifikationenService,
  KlassifikationenLadezustand,
  LEERE_DOKUMENT_KLASSIFIKATIONEN,
} from './klassifikationen';
import { WissenVerwendung } from '../wissen/wissen-verwendung';
import { Symbol } from '../gemeinsam/symbol';

type Laden = 'laedt' | 'ok' | 'fehlt' | 'fehler' | 'verbindung';
type Vorschau = 'laedt' | 'ok' | 'fehlt' | 'fehler';

// Reserve, damit ein Link nicht genau beim Klick abläuft
const RESERVE_MS = 15_000;

/** Status, in denen `PATCH /dokumente/{id}` Änderungen annimmt (design.md «Umsetzung 3.5»). */
const AENDERBAR = new Set(['EINGANGSKORB', 'FEHLER', 'ARCHIVIERT']);
const BREIT = '(min-width: 960px)';

/** Nur http(s) darf als vorsignierter Link verwendet werden; so werden etwa javascript:-Adressen ausgeschlossen. */
export function sichereAdresse(url: string | null | undefined): string | null {
  try {
    const adresse = new URL(url ?? '');
    return adresse.protocol === 'https:' || adresse.protocol === 'http:' ? adresse.href : null;
  } catch {
    return null;
  }
}

interface Zusatzfeld {
  name: string;
  wert: string;
  ungueltig: boolean;
}

/**
 * Dokumentansicht (Req 14.6): Metadaten, Link zur PDF/A-Fassung und Download über eine vorsignierte Adresse,
 * die beim Öffnen geholt und vom Archiv protokolliert wird; Metadaten bearbeiten mit Grund (A6, Req 10.1) und die
 * gesicherten Fassungen. Das Vorschaubild der ersten Seite ersetzt die PDF-Vorschau auf schmalen Bildschirmen und
 * ohne PDF/A-Fassung.
 */
@Component({
  selector: 'app-dokument-ansicht',
  imports: [
    MatButtonModule,
    MatProgressBarModule,
    RouterLink,
    MetadatenFormular,
    TitelAnzeige,
    WissenVerwendung,
    Symbol,
  ],
  templateUrl: './dokument-ansicht.html',
  styleUrl: './dokument-ansicht.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DokumentAnsicht {
  private readonly port = inject(DokumentPort);
  private readonly wissensbasis = inject(WissensbasisService);
  private readonly klassifikationenService = inject(DokumentKlassifikationenService);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly id = input.required<string>();
  /** Art aus dem Eingangskorb (`?art=`, Review #106): öffnet das Formular mit dieser Art als Artwechsel. */
  readonly art = input<string | undefined>();
  readonly seite = input<string | undefined>();

  protected readonly texte = TEXTE.dokument;
  protected readonly korb = TEXTE.eingangskorb;
  protected readonly neuLadenText = TEXTE.eingangskorb.neuLaden;
  protected readonly stati = TEXTE.suche.stati;
  protected readonly datum = datum;
  protected readonly zeitpunkt = zeitpunkt;
  protected readonly betrag = betrag;
  protected readonly richtungen = TEXTE.eingangskorb.richtung;

  protected readonly laden = signal<Laden>('laedt');
  protected readonly dokument = signal<Dokument | null>(null);
  protected readonly vorschau = signal<Vorschau>('laedt');
  private readonly pdfa = signal<DownloadAdresse | null>(null);
  protected readonly pdfaUrl = computed(() => this.adresseMitSeite(this.pdfa()?.url));
  protected readonly dateiFehler = signal(false);
  /**
   * Zuletzt per Klick geholte Adresse: `window.open` läuft erst nach der Antwort und damit ausserhalb der Klick-Geste;
   * blockiert der Browser das Fenster, bleibt dieser Link als zweite, eigene Geste.
   */
  private readonly pdfaLaedt = signal(false);
  protected readonly pdfaHinweis = signal(false);
  private readonly geholt = signal<{ adresse: DownloadAdresse; variante: Variante } | null>(null);
  protected readonly geholtUrl = computed(() => sichereAdresse(this.geholt()?.adresse.url));

  protected readonly arten = signal<readonly Dokumentart[]>([]);
  protected readonly artName = computed(() => {
    const code = this.dokument()?.art;
    return code ? (this.arten().find((a) => a.code === code)?.name ?? code) : TEXTE.suche.ohneArt;
  });
  /** Knopf nur mit A6 und in einem änderbaren Status; das Archiv prüft trotzdem selbst (Req 2.6). */
  /** Der Partner verlinkt auf den Kontakt nur mit A6 (Req 17.1). */
  protected readonly darfKontakt = computed(() => this.anmeldung.darf('A6'));
  protected readonly darfWissensquelle = computed(() => this.anmeldung.darf('A16'));
  /** Wissen freigeschaltet (Spec 04, Req 22.5): sonst keine Wissensquellen, keine Wissensseiten in der Ansicht. */
  protected readonly wissen = computed(() => this.anmeldung.darf('A15'));
  /** Zählt jedes geladene Dokument, damit die Wissensquellen auch nach dem Neuladen desselben Dokuments folgen. */
  private readonly geladenNr = signal(0);
  private readonly wissensquellen = signal<Quelle[]>([]);
  protected readonly wissensquellenLadezustand = signal<'laedt' | 'ok' | 'fehler'>('laedt');
  protected readonly wissensquelleFehler = signal<string | null>(null);
  protected readonly wissensquelleMeldung = signal<string | null>(null);
  protected readonly wissensquelleAktionLaeuft = signal(false);
  protected readonly istWissensquelle = computed(() => {
    const id = this.dokument()?.id;
    return !!id && this.wissen() && this.wissensquellen().some((quelle) => quelle.dokumentId === id);
  });
  protected readonly darfBearbeiten = computed(() => {
    const d = this.dokument();
    return !!d && AENDERBAR.has(d.status) && this.anmeldung.darf('A6');
  });
  protected readonly bearbeiten = signal(false);
  /** Nur eine bekannte, aktive Art, die sich von der gespeicherten unterscheidet; sonst gilt `?art=` nicht. */
  protected readonly artVorbelegung = computed(() => {
    const code = this.art();
    const d = this.dokument();
    if (!code || !d || code === d.art) {
      return null;
    }
    return this.arten().some((a) => a.code === code && a.aktiv) ? code : null;
  });
  /** Gilt nur für das automatisch geöffnete Formular; Abbrechen, Speichern oder Neuladen verwirft sie. */
  protected readonly vorbelegung = signal<string | null>(null);
  private vorbelegtFuer: string | null = null;
  protected readonly meldung = signal('');
  protected readonly ich = computed(() => this.anmeldung.benutzer()?.id ?? '');

  /** Auf schmalen Bildschirmen das Vorschaubild statt der PDF-Datei zeigen. */
  protected readonly breit = signal(
    typeof window.matchMedia === 'function' && window.matchMedia(BREIT).matches,
  );
  protected readonly gewuenschteSeite = computed(() => {
    const wert = this.seite();
    if (!wert || !/^[1-9]\d*$/.test(wert)) return null;
    const nummer = Number(wert);
    return Number.isSafeInteger(nummer) ? nummer : null;
  });
  private readonly bild = signal<{ id: string; seite: number; url: string } | null>(null);
  protected readonly bildUrl = computed(() => {
    const bild = this.bild();
    return bild && bild.id === this.dokument()?.id && bild.seite === (this.gewuenschteSeite() ?? 1)
      ? bild.url
      : null;
  });
  private readonly bildNoetig = computed(() => {
    const d = this.dokument();
    const v = this.vorschau();
    return !!d && (d.seiten ?? 0) >= 1 && (!this.breit() || v === 'fehlt' || v === 'fehler');
  });
  private bildAbo = new Subscription();
  /** Ohne Titel der Dateiname als Platzhalter, in jedem Status (Req 14.10). */
  protected readonly anzeige = computed(() =>
    anzeigeTitel(this.dokument()?.titel, null, this.dokument()?.dateiname),
  );
  protected readonly titel = computed(() => this.anzeige().text);
  protected readonly zusatz = computed<Zusatzfeld[]>(() => {
    const dokument = this.dokument();
    if (!dokument) {
      return [];
    }
    const roh = dokument.zusatz;
    const z =
      roh && typeof roh === 'object' && !Array.isArray(roh) ? (roh as Record<string, unknown>) : {};
    const art = this.arten().find((a) => a.code === dokument.art);
    const schema = zusatzFelder(art);
    const felder = new Map(schema.map((feld) => [feld.name, feld]));
    const ergebnis: Zusatzfeld[] = Object.entries(z)
      .filter(([, wert]) => wert !== null && wert !== undefined && wert !== '')
      .map(([name, wert]) => {
        const feld = felder.get(name);
        const roh =
          Array.isArray(wert) && wert.every((eintrag) => typeof eintrag === 'string')
            ? listeZu(wert)
            : typeof wert === 'object'
              ? JSON.stringify(wert)
              : String(wert);
        return {
          name,
          wert:
            Array.isArray(wert) && wert.every((eintrag) => typeof eintrag === 'string')
              ? wert.map((eintrag) => anzeigeText(feld, eintrag)).join(', ')
              : typeof wert === 'object'
                ? JSON.stringify(wert)
                : String(wert),
          ungueltig: !feld || !zusatzGueltig(feld, roh, true),
        };
      });
    for (const feld of schema) {
      if (feld.pflicht && !ergebnis.some((eintrag) => eintrag.name === feld.name)) {
        ergebnis.push({
          name: feld.name,
          wert: this.korb.zusatzNichtAngegeben,
          ungueltig: true,
        });
      }
    }
    return ergebnis;
  });
  protected readonly klassifikationenStatus = toSignal(
    toObservable(this.dokument).pipe(
      map((dokument) => dokument?.art ?? ''),
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
  protected readonly klassifikationenAnzeige = computed(() => {
    const dokument = this.dokument();
    if (!dokument) {
      return [];
    }
    const status = this.klassifikationenStatus();
    const daten = status.status === 'ok' ? status.daten : LEERE_DOKUMENT_KLASSIFIKATIONEN;
    const zugeordnet = new Set(daten.art.map((feld) => feld.code));
    const definitionen = new Map(daten.alle.map((feld) => [feld.code, feld]));
    const ungueltig = new Set(dokument.klassifikationenUngueltig ?? []);
    return Object.entries(dokument.klassifikationen ?? {})
      .filter(([, werte]) => werte.length > 0)
      .map(([code, werte]) => {
        const definition = definitionen.get(code);
        const wertNamen = new Map(definition?.werte.map((wert) => [wert.code, wert.name]) ?? []);
        return {
          name: definition?.name ?? code,
          werte: werte.map((wert) => wertNamen.get(wert) ?? wert).join(', '),
          ungueltig: ungueltig.has(code) || (status.status === 'ok' && !zugeordnet.has(code)),
        };
      });
  });
  protected readonly regelWarnungen = computed(
    () =>
      this.dokument()?.warnungen.filter((warnung) => warnung.code !== 'BELEGNUMMER_BEKANNT') ?? [],
  );

  private abos = new Subscription();

  constructor() {
    const arten = this.port.dokumentarten().subscribe({
      next: (liste) => this.arten.set(liste),
      error: () => undefined,
    });
    const medien = typeof window.matchMedia === 'function' ? window.matchMedia(BREIT) : null;
    const wechsel = (e: MediaQueryListEvent) => this.breit.set(e.matches);
    medien?.addEventListener?.('change', wechsel);
    inject(DestroyRef).onDestroy(() => {
      arten.unsubscribe();
      this.abos.unsubscribe();
      this.bildAbo.unsubscribe();
      medien?.removeEventListener?.('change', wechsel);
      this.bildFreigeben();
    });
    effect(() => {
      const id = this.id();
      untracked(() => this.oeffnen(id));
    });
    // Wissensquellen erst, wenn Dokument und Freischaltung bekannt sind; ohne Freischaltung keine Anfrage (Req 22.5)
    effect(() => {
      this.geladenNr();
      const id = untracked(() => this.dokument()?.id);
      if (id && this.wissen()) {
        untracked(() => this.wissensquellenLaden(id));
      }
    });
    effect(() => {
      const code = this.artVorbelegung();
      const d = this.dokument();
      if (code && d && this.darfBearbeiten() && this.vorbelegtFuer !== d.id) {
        this.vorbelegtFuer = d.id;
        untracked(() => {
          this.vorbelegung.set(code);
          this.bearbeitenOeffnen();
        });
      }
    });
    effect(() => {
      this.gewuenschteSeite();
      if (this.bildNoetig()) {
        untracked(() => this.bildLaden());
      }
    });
  }

  protected bearbeitenOeffnen(): void {
    this.meldung.set('');
    this.bearbeiten.set(true);
  }

  protected bearbeitenSchliessen(): void {
    this.vorbelegung.set(null);
    this.bearbeiten.set(false);
    this.fokus('[data-test="bearbeiten"]');
  }

  protected gespeichert(dokument: Dokument): void {
    this.dokument.set(dokument);
    this.vorbelegung.set(null);
    this.bearbeiten.set(false);
    this.melden(this.texte.gespeichert);
  }

  /** 409/412/404 beim Speichern: Ansicht neu laden, damit die Änderung auf dem aktuellen Stand neu erfasst wird. */
  protected veraltet(meldung: string): void {
    this.oeffnen(this.id());
    this.melden(meldung);
  }

  protected neuLaden(): void {
    this.oeffnen(this.id());
  }

  protected wissensquellenNeuLaden(): void {
    const id = this.dokument()?.id;
    if (id) this.wissensquellenLaden(id);
  }

  protected wissensquelleMarkieren(): void {
    this.wissensquelleAendern(true);
  }

  protected wissensquelleEntfernen(): void {
    this.wissensquelleAendern(false);
  }

  protected vorschauNeuLaden(): void {
    const dokument = this.dokument();
    if (dokument) {
      this.vorschau.set('laedt');
      this.vorschauLaden(dokument.id);
    }
  }

  protected wer(sub: string): string {
    return sub && sub === this.ich() ? this.texte.sie : sub;
  }

  private melden(meldung: string): void {
    this.meldung.set(meldung);
    this.fokus('[data-test="meldung"]');
  }

  private fokus(selektor: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selektor)?.focus(), {
      injector: this.injector,
    });
  }

  private bildLaden(): void {
    const id = this.dokument()?.id;
    const seite = this.gewuenschteSeite() ?? 1;
    if (!id || (this.bild()?.id === id && this.bild()?.seite === seite)) {
      return;
    }
    this.bildAbo.unsubscribe();
    this.bildAbo = this.port.vorschau(id, seite).subscribe({
      next: (blob) => {
        this.bildFreigeben();
        this.bild.set({ id, seite, url: URL.createObjectURL(blob) });
      },
      error: () => undefined,
    });
  }

  private bildFreigeben(): void {
    const bild = this.bild();
    if (bild) {
      URL.revokeObjectURL(bild.url);
      this.bild.set(null);
    }
  }

  private oeffnen(id: string): void {
    this.abos.unsubscribe();
    this.abos = new Subscription();
    this.bildAbo.unsubscribe();
    if (this.bild()?.id !== id) {
      this.bildFreigeben();
    }
    this.bearbeiten.set(false);
    // Nach dem Neuladen (auch 409/412/404 beim Speichern) gilt `?art=` nicht mehr; der Effekt setzt sie für ein
    // anderes Dokument neu (Review #107).
    this.vorbelegung.set(null);
    this.meldung.set('');
    this.laden.set('laedt');
    this.dokument.set(null);
    this.wissensquellen.set([]);
    this.wissensquellenLadezustand.set('laedt');
    this.wissensquelleFehler.set(null);
    this.wissensquelleMeldung.set(null);
    this.wissensquelleAktionLaeuft.set(false);
    this.pdfa.set(null);
    this.vorschau.set('laedt');
    this.dateiFehler.set(false);
    this.geholt.set(null);
    this.abos.add(
      this.port.lesen(id).subscribe({
        next: (dokument) => {
          this.dokument.set(dokument);
          this.laden.set('ok');
          this.vorschauLaden(id);
          this.geladenNr.update((nr) => nr + 1);
        },
        error: (fehler: unknown) => this.laden.set(ladeFehler(fehler)),
      }),
    );
  }

  private wissensquellenLaden(id: string): void {
    this.wissensquellenLadezustand.set('laedt');
    this.wissensquelleFehler.set(null);
    this.abos.add(this.wissensbasis.apiV1WissenQuellenGet().subscribe({
      next: (quellen) => {
        if (this.dokument()?.id !== id) return;
        this.wissensquellen.set(quellen);
        this.wissensquellenLadezustand.set('ok');
      },
      error: (fehler: unknown) => {
        if (this.dokument()?.id !== id) return;
        this.wissensquellenLadezustand.set('fehler');
        this.wissensquelleFehler.set(httpFehlerMeldung(fehler));
      },
    }));
  }

  private wissensquelleAendern(markieren: boolean): void {
    const dokument = this.dokument();
    if (
      !dokument ||
      !this.darfWissensquelle() ||
      this.wissensquellenLadezustand() !== 'ok' ||
      this.wissensquelleAktionLaeuft() ||
      (markieren && (dokument.status !== 'ARCHIVIERT' || this.istWissensquelle())) ||
      (!markieren && !this.istWissensquelle())
    ) {
      return;
    }
    this.wissensquelleFehler.set(null);
    this.wissensquelleMeldung.set(null);
    this.wissensquelleAktionLaeuft.set(true);
    const anfrage = markieren
      ? this.wissensbasis.apiV1DokumenteIdWissensquellePost(dokument.id)
      : this.wissensbasis.apiV1DokumenteIdWissensquelleDelete(dokument.id);
    this.abos.add(anfrage.subscribe({
      next: () => {
        this.wissensquelleAktionLaeuft.set(false);
        this.wissensquelleMeldung.set(
          markieren ? this.texte.wissensquelleMarkiert : this.texte.wissensquelleEntfernt,
        );
        this.wissensquellenLaden(dokument.id);
      },
      error: (fehler: unknown) => {
        this.wissensquelleAktionLaeuft.set(false);
        this.wissensquelleFehler.set(wissensquelleFehlerMeldung(fehler));
      },
    }));
  }

  private vorschauLaden(id: string): void {
    this.abos.add(
      this.port.adresse(id, 'pdfa', 'vorschau').subscribe({
        next: (adresse) => {
          this.pdfa.set(adresse);
          this.vorschau.set(sichereAdresse(adresse.url) ? 'ok' : 'fehler');
        },
        error: (fehler: unknown) => this.vorschau.set(status(fehler) === 404 ? 'fehlt' : 'fehler'),
      }),
    );
  }

  /** Die Vorschau-Adresse zeigt inline an; der Download holt eine eigene Adresse mit attachment (jeder Abruf wird protokolliert). */
  protected pdfaHerunterladen(): void {
    this.holenUndOeffnen('pdfa');
  }

  protected originalHerunterladen(): void {
    this.holenUndOeffnen('original');
  }

  protected geholtKlick(ereignis: MouseEvent): void {
    const geholt = this.geholt();
    if (geholt && gueltig(geholt.adresse)) {
      return;
    }
    ereignis.preventDefault();
    if (geholt) {
      this.holenUndOeffnen(geholt.variante);
    }
  }

  /** Die Vorschau-Adresse wird beim Öffnen der Ansicht geholt; ist sie beim Klick abgelaufen, wird vor dem Öffnen eine frische geholt. */
  protected pdfaKlick(ereignis: MouseEvent): void {
    const pdfa = this.pdfa();
    if (pdfa && gueltig(pdfa)) {
      return;
    }
    ereignis.preventDefault();
    if (this.pdfaLaedt()) {
      return;
    }
    this.dateiFehler.set(false);
    this.pdfaHinweis.set(false);
    // Das Fenster wird noch in der Klick-Geste geöffnet (sonst sperren Popup-Blocker, v. a. iOS Safari) und erst nach
    // der Antwort auf die Adresse gesetzt. Mit `noopener` liefe `window.open` immer `null`; darum ohne und `opener` trennen.
    const fenster = window.open('', '_blank');
    if (!fenster) {
      this.pdfaHinweis.set(true);
      return;
    }
    fenster.opener = null;
    this.pdfaLaedt.set(true);
    this.abos.add(
      this.port.adresse(this.id(), 'pdfa', 'vorschau').subscribe({
        next: (adresse) => {
          this.pdfaLaedt.set(false);
          const url = this.adresseMitSeite(adresse.url);
          if (!url) {
            fenster.close();
            this.dateiFehler.set(true);
            return;
          }
          this.pdfa.set(adresse);
          fenster.location.href = url;
        },
        error: () => {
          this.pdfaLaedt.set(false);
          fenster.close();
          this.dateiFehler.set(true);
        },
      }),
    );
  }

  private holenUndOeffnen(variante: Variante): void {
    this.dateiFehler.set(false);
    this.geholt.set(null);
    this.abos.add(
      this.port.adresse(this.id(), variante, 'download').subscribe({
        next: (adresse) => {
          const url = sichereAdresse(adresse.url);
          if (!url) {
            this.dateiFehler.set(true);
            return;
          }
          this.geholt.set({ adresse, variante });
          window.open(url, '_blank', 'noopener,noreferrer');
        },
        error: () => this.dateiFehler.set(true),
      }),
    );
  }

  private adresseMitSeite(adresse: string | null | undefined): string | null {
    const sicher = sichereAdresse(adresse);
    const seite = this.gewuenschteSeite();
    if (!sicher || !seite) return sicher;
    const url = new URL(sicher);
    url.hash = `page=${seite}`;
    return url.href;
  }
}

function gueltig(adresse: DownloadAdresse): boolean {
  const bis = Date.parse(adresse.gueltigBis);
  return Number.isFinite(bis) && bis - Date.now() > RESERVE_MS;
}

function status(fehler: unknown): number {
  return fehler instanceof HttpErrorResponse ? fehler.status : -1;
}

function ladeFehler(fehler: unknown): Laden {
  const s = status(fehler);
  if (s === 404 || s === 400) {
    return 'fehlt';
  }
  return s === 0 ? 'verbindung' : 'fehler';
}

function wissensquelleFehlerMeldung(fehler: unknown): string {
  if (fehler instanceof HttpErrorResponse && fehler.status === 422) {
    const problem = objekt(fehler.error);
    if (problem?.['fehlercode'] === 'KEINE_WISSENSQUELLE' && typeof problem['grund'] === 'string') {
      return TEXTE.dokument.wissensquelleGruende[problem['grund']] ?? httpFehlerMeldung(fehler);
    }
  }
  return httpFehlerMeldung(fehler);
}

function objekt(wert: unknown): Record<string, unknown> | null {
  return typeof wert === 'object' && wert !== null && !Array.isArray(wert)
    ? (wert as Record<string, unknown>)
    : null;
}
