import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { Subject, Subscription, catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { Dokumentart, Kontakt, Suchtreffer } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { AnzeigeTitel, TitelAnzeige, anzeigeTitel } from '../gemeinsam/anzeige-titel';
import { betrag, datum } from '../gemeinsam/format';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { SucheZustand } from './suche-zustand';
import { Suchanfrage, SuchePort } from './suche-port';
import { Filterwerte, GROESSE, HYBRID_MAX, LEERE_FILTER, Pruefung, SEITE_MAX, aktiveFilter, imModus, pruefen, wirksamerModus } from './suchfilter';

const STATI = [
  'NEU',
  'VERARBEITUNG',
  'EINGANGSKORB',
  'ARCHIVIERUNG',
  'ARCHIVIERUNG_FEHLER',
  'ARCHIVIERT',
  'FEHLER',
  'VERWORFEN',
] as const;

/**
 * Seite «Suche» (Req 14.1, 14.2, 14.4, 14.7): Suchfeld, Filterleiste und Trefferliste mit hervorgehobener Textstelle.
 * Was sichtbar ist, entscheidet allein das Archiv (Req 2.3); die Seite zeigt nur, was die API liefert.
 */
@Component({
  selector: 'app-suche',
  imports: [MatButtonModule, MatProgressBarModule, RouterLink, TitelAnzeige],
  templateUrl: './suche.html',
  styleUrl: './suche.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Suche {
  private readonly port = inject(SuchePort);
  private readonly zustand = inject(SucheZustand);
  private readonly anmeldung = inject(AnmeldungService);
  private readonly zerstoert = inject(DestroyRef);

  protected readonly texte = TEXTE.suche;
  protected readonly stati = STATI;
  protected readonly datum = datum;
  protected readonly betrag = betrag;

  protected readonly mobil = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((z) => z.matches)),
    { initialValue: true },
  );
  private readonly filterGeoeffnet = signal<boolean | null>(null);
  protected readonly filterOffen = computed(() => this.filterGeoeffnet() ?? !this.mobil());

  protected readonly filter = this.zustand.filter;
  protected readonly ergebnis = this.zustand.ergebnis;
  protected readonly arten = signal<Dokumentart[]>([]);
  protected readonly laeuft = signal(false);
  protected readonly pruefFehler = signal<Pruefung[]>([]);
  protected readonly fehler = signal<string | null>(null);

  protected readonly darfKontakte = computed(() => this.anmeldung.darf('A6'));
  protected readonly partnerEingabe = signal('');
  protected readonly partnerVorschlaege = signal<Kontakt[] | null>(null);
  private readonly partnerSuche = new Subject<string>();

  protected readonly anzahlFilter = computed(() => aktiveFilter(this.filter()));
  protected readonly artName = computed(() => {
    const namen = new Map(this.arten().map((a) => [a.code, a.name]));
    return (code: string | null | undefined) => (code ? (namen.get(code) ?? code) : this.texte.ohneArt);
  });
  /** Massgebend ist der Modus der gesendeten Anfrage, nicht die Antwort allein. */
  protected readonly hybrid = computed(() => this.zustand.gesendet()?.modus === 'hybrid');
  protected readonly angezeigt = computed(() => {
    const e = this.ergebnis();
    return e ? (this.hybrid() ? e.treffer.slice(0, HYBRID_MAX) : e.treffer) : [];
  });
  protected readonly volltextAn = computed(() => wirksamerModus(this.filter()) === 'volltext');
  protected readonly seiten = computed(() => {
    const e = this.ergebnis();
    return e ? Math.max(1, Math.min(Math.ceil(e.gesamt / (e.groesse || GROESSE)), SEITE_MAX + 1)) : 0;
  });

  private readonly trefferUeberschrift = viewChild<ElementRef<HTMLElement>>('trefferUeberschrift');
  private laufend?: Subscription;

  constructor() {
    this.zustand.fuer(`${this.anmeldung.benutzer()?.mandant ?? ''}/${this.anmeldung.benutzer()?.id ?? ''}`);
    const arten = this.port
      .dokumentarten()
      .pipe(catchError(() => of([] as Dokumentart[])))
      .subscribe((liste) => this.arten.set([...liste].sort((a, b) => a.name.localeCompare(b.name, 'de-CH'))));
    const partner = this.partnerSuche
      .pipe(
        map((q) => q.trim()),
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) =>
          q.length < 2 ? of(null) : this.port.kontakte(q).pipe(catchError(() => of([] as Kontakt[]))),
        ),
      )
      .subscribe((liste) => this.partnerVorschlaege.set(liste === null ? null : liste.slice(0, 10)));
    this.zerstoert.onDestroy(() => {
      arten.unsubscribe();
      partner.unsubscribe();
      this.laufend?.unsubscribe();
    });
    if (this.ergebnis() === null) {
      this.suchen(0, false);
    }
  }

  protected setzen<K extends keyof Filterwerte>(feld: K, wert: Filterwerte[K]): void {
    this.filter.update((f) => ({ ...f, [feld]: wert }));
  }

  /** Wer nach Datum sortiert, sucht im Volltext (Req 2.7); die Oberfläche zeigt den Modus, der gesendet wird. */
  protected sortierungSetzen(sortierung: Filterwerte['sortierung']): void {
    this.filter.update((f) => ({ ...f, sortierung, volltext: sortierung === 'DATUM' ? true : f.volltext }));
  }

  /** Sendet die zuletzt gesendete Anfrage im neuen Modus ab Seite 0; wer «Volltext» ausschaltet, sortiert nach Relevanz. */
  protected volltextSetzen(an: boolean, fokus = false): void {
    this.filter.update((f) => ({ ...f, volltext: an, sortierung: 'RELEVANZ' }));
    const letzte = this.zustand.gesendet();
    if (letzte?.q) {
      this.senden(imModus(letzte, an), fokus);
    }
  }

  protected wert(ereignis: Event): string {
    return (ereignis.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected filterUmschalten(): void {
    this.filterGeoeffnet.set(!this.filterOffen());
  }

  protected absenden(ereignis: Event): void {
    ereignis.preventDefault();
    this.suchen(0, true);
  }

  protected zuruecksetzen(): void {
    this.filter.update((f) => ({ ...LEERE_FILTER, q: f.q, sortierung: f.sortierung, volltext: f.volltext }));
    this.partnerEingabe.set('');
    this.partnerVorschlaege.set(null);
    this.suchen(0, true);
  }

  protected partnerTippen(text: string): void {
    this.partnerEingabe.set(text);
    this.partnerSuche.next(text);
  }

  protected partnerWaehlen(id: string | null | undefined, name: string | null | undefined): void {
    if (!id) {
      return;
    }
    this.filter.update((f) => ({ ...f, partnerId: id, partnerName: name ?? '' }));
    this.partnerEingabe.set('');
    this.partnerVorschlaege.set(null);
    this.suchen(0, true);
  }

  protected partnerEntfernen(): void {
    this.filter.update((f) => ({ ...f, partnerId: '', partnerName: '' }));
    this.suchen(0, true);
  }

  protected kontaktName(k: Kontakt): string {
    return [k.vorname, k.name].filter(Boolean).join(' ') + (k.ort ? `, ${k.ort}` : '');
  }

  protected blaettern(richtung: -1 | 1): void {
    const letzte = this.zustand.gesendet();
    if (!letzte) {
      this.suchen(this.zustand.seite() + richtung, true);
      return;
    }
    this.senden({ ...letzte, seite: Math.min(Math.max((letzte.seite ?? 0) + richtung, 0), SEITE_MAX) }, true);
  }

  /** Ohne Titel der Dateiname als Platzhalter, auch in NEU, VERARBEITUNG und FEHLER (Req 14.9). */
  protected titel(t: Suchtreffer): AnzeigeTitel {
    return anzeigeTitel(t.titel, null, t.dateiname);
  }

  private suchen(seite: number, fokus: boolean): void {
    const geprueft = pruefen(this.filter(), seite);
    if (geprueft.fehler) {
      this.pruefFehler.set(geprueft.fehler);
      this.filterGeoeffnet.set(true);
      return;
    }
    this.pruefFehler.set([]);
    this.senden(geprueft.anfrage, fokus);
  }

  private senden(anfrage: Suchanfrage, fokus: boolean): void {
    this.fehler.set(null);
    this.laeuft.set(true);
    this.laufend?.unsubscribe();
    this.laufend = this.port.suchen(anfrage).subscribe({
      next: (ergebnis) => {
        this.zustand.seite.set(anfrage.seite ?? 0);
        this.zustand.gesendet.set(anfrage);
        this.ergebnis.set(ergebnis);
        this.laeuft.set(false);
        if (fokus) {
          queueMicrotask(() => this.trefferUeberschrift()?.nativeElement.focus());
        }
      },
      error: (fehler: unknown) => {
        this.laeuft.set(false);
        this.fehler.set(meldung(fehler));
      },
    });
  }
}

function meldung(fehler: unknown): string {
  const status = fehler instanceof HttpErrorResponse ? fehler.status : -1;
  if (status === 0) {
    return TEXTE.suche.fehlerVerbindung;
  }
  return status === 400 ? TEXTE.suche.fehlerAnfrage : TEXTE.suche.fehlerAllgemein;
}
