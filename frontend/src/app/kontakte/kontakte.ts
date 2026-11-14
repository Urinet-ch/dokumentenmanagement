import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  BehaviorSubject,
  Subject,
  catchError,
  combineLatest,
  debounceTime,
  distinctUntilChanged,
  map,
  of,
  switchMap,
  tap,
} from 'rxjs';
import { KontaktEintrag, KontaktListe } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import {
  LISTE_GROESSE,
  LEERER_FILTER,
  ListenFilter,
  anzahlFilter,
  fehlerArt,
  filterAusParametern,
  kontaktName,
  ladenFehlerText,
  ortZeile,
  parameterAusFilter,
  seitenAnzahl,
  suchbegriffGueltig,
} from './kontakt-logik';
import { KontaktePort, ListenAnfrage } from './kontakte-port';

const STATI = ['AKTIV', 'INAKTIV', 'ALLE'] as const;
const HERKUENFTE = ['', 'CSV_IMPORT', 'MANUELL'] as const;
const VERZOEGERUNG_MS = 300;

/**
 * Seite «Kontakte» (Req 17.1, 17.2; Recht A6): Suche, Filter und Blättern über `GET /kontakte/liste`. Suchbegriff,
 * Filter und Seite stehen in den Query-Parametern der Adresse, damit die Zurück-Taste aus dem Detail zur selben Liste
 * führt. Veraltete Antworten verwirft `switchMap`.
 */
@Component({
  selector: 'app-kontakte',
  imports: [MatButtonModule, MatProgressBarModule, RouterLink],
  templateUrl: './kontakte.html',
  styleUrls: ['./kontakte-gemeinsam.scss', './kontakte.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Kontakte {
  private readonly port = inject(KontaktePort);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly zerstoert = inject(DestroyRef);

  protected readonly texte = TEXTE.kontakte;
  protected readonly stati = STATI;
  protected readonly herkuenfte = HERKUENFTE;

  protected readonly mobil = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((z) => z.matches)),
    { initialValue: true },
  );

  protected readonly filter = toSignal(this.route.queryParamMap.pipe(map(filterAusParametern)), {
    initialValue: filterAusParametern(this.route.snapshot.queryParamMap),
  });
  protected readonly eingabe = signal(this.filter().q);
  protected readonly ergebnis = signal<KontaktListe | null>(null);
  protected readonly laedt = signal(false);
  protected readonly fehler = signal<string | null>(null);
  private readonly fehlerStatus = signal<ReturnType<typeof fehlerArt> | null>(null);

  protected readonly suchbegriffOk = computed(() => {
    const q = this.filter().q.trim();
    return q === '' || suchbegriffGueltig(q);
  });
  protected readonly anzahlFilter = computed(() => anzahlFilter(this.filter()));
  private readonly filterGeoeffnet = signal<boolean | null>(null);
  protected readonly filterOffen = computed(
    () => this.filterGeoeffnet() ?? (this.anzahlFilter() > 0 || !this.mobil()),
  );
  protected readonly zeilen = computed<readonly KontaktEintrag[]>(
    () => this.ergebnis()?.eintraege ?? [],
  );
  protected readonly seiten = computed(() =>
    seitenAnzahl(this.ergebnis()?.gesamt ?? 0, LISTE_GROESSE),
  );
  protected readonly wiederholbar = computed(() =>
    ['verbindung', 'sonst'].includes(this.fehlerStatus() ?? ''),
  );

  private readonly neu = new BehaviorSubject<void>(undefined);
  private readonly getippt = new Subject<string>();
  private readonly ueberschrift = viewChild<ElementRef<HTMLElement>>('ueberschrift');
  private fokusNachLaden = false;

  constructor() {
    const laden = combineLatest([
      this.route.queryParamMap.pipe(
        map(filterAusParametern),
        distinctUntilChanged((a, b) => JSON.stringify(a) === JSON.stringify(b)),
      ),
      this.neu,
    ])
      .pipe(
        tap(() => {
          this.laedt.set(true);
          this.fehler.set(null);
          this.fehlerStatus.set(null);
        }),
        // switchMap verwirft die Antwort einer veralteten Anfrage (Req 17.2)
        switchMap(([f]) =>
          this.port.liste(anfrage(f)).pipe(
            map((liste) => ({ liste, fehler: null as unknown })),
            catchError((fehler: unknown) => of({ liste: null, fehler })),
          ),
        ),
      )
      .subscribe(({ liste, fehler }) => {
        this.laedt.set(false);
        if (liste) {
          this.ergebnis.set(liste);
          if (this.fokusNachLaden) {
            queueMicrotask(() => this.ueberschrift()?.nativeElement.focus());
          }
        } else {
          // nie Kontaktdaten zeigen, wenn das Archiv ablehnt (Req 17.5)
          this.ergebnis.set(null);
          this.fehler.set(ladenFehlerText(fehler));
          this.fehlerStatus.set(fehlerArt(fehler));
        }
        this.fokusNachLaden = false;
      });
    const suche = this.getippt
      .pipe(
        map((q) => q.trim()),
        debounceTime(VERZOEGERUNG_MS),
        distinctUntilChanged(),
      )
      .subscribe((q) => {
        if (q !== this.filter().q.trim()) {
          this.navigieren({ ...this.filter(), q, seite: 0 }, true);
        }
      });
    this.zerstoert.onDestroy(() => {
      laden.unsubscribe();
      suche.unsubscribe();
    });
    // Zurück-Taste und Links ändern die Adresse: das Suchfeld folgt, ohne eine Eingabe zu stören
    effect(() => {
      const q = this.filter().q;
      if (q !== untracked(this.eingabe).trim()) {
        this.eingabe.set(q);
      }
    });
  }

  protected wert(ereignis: Event): string {
    return (ereignis.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected tippen(text: string): void {
    this.eingabe.set(text);
    this.getippt.next(text);
  }

  protected aendern(teil: Partial<ListenFilter>): void {
    this.navigieren({ ...this.filter(), ...teil }, false);
  }

  protected zuruecksetzen(): void {
    this.navigieren({ ...LEERER_FILTER, q: this.filter().q }, false);
  }

  protected blaettern(richtung: -1 | 1): void {
    this.fokusNachLaden = true;
    this.navigieren(
      { ...this.filter(), seite: Math.max(0, this.filter().seite + richtung) },
      false,
    );
  }

  protected filterUmschalten(): void {
    this.filterGeoeffnet.set(!this.filterOffen());
  }

  protected neuLaden(): void {
    this.neu.next();
  }

  protected name(k: KontaktEintrag): string {
    return kontaktName(k);
  }

  protected ort(k: KontaktEintrag): string {
    return ortZeile(k);
  }

  protected marken(k: KontaktEintrag): { text: string; inaktiv: boolean }[] {
    const t = this.texte;
    return [
      ...(k.aktiv === false ? [{ text: t.kennzeichenInaktiv, inaktiv: true }] : []),
      {
        text: k.quelle === 'CSV_IMPORT' ? t.kennzeichenImport : t.kennzeichenManuell,
        inaktiv: false,
      },
      ...(k.nichtImExport ? [{ text: t.kennzeichenNichtImExport, inaktiv: false }] : []),
    ];
  }

  private navigieren(filter: ListenFilter, ersetzen: boolean): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: parameterAusFilter(filter),
      replaceUrl: ersetzen,
    });
  }
}

/** Nur ein gültiger Suchbegriff geht mit (1–200 Zeichen ohne Steuerzeichen); leer = ohne `q`. */
function anfrage(f: ListenFilter): ListenAnfrage {
  const q = f.q.trim();
  return {
    q: q !== '' && suchbegriffGueltig(q) ? q : undefined,
    status: f.status,
    quelle: f.quelle || undefined,
    nichtImExport: f.nichtImExport ? true : undefined,
    seite: f.seite,
    groesse: LISTE_GROESSE,
  };
}
