import { HttpClient } from '@angular/common/http';
import {
  EnvironmentProviders,
  Injectable,
  Injector,
  computed,
  inject,
  provideEnvironmentInitializer,
  signal,
} from '@angular/core';
import { ActivatedRouteSnapshot, NavigationStart, Router } from '@angular/router';
import { Subscription, filter } from 'rxjs';
import { Mandantenfunktionen } from './api';

export type FunktionenStand = 'offen' | 'laedt' | 'geladen' | 'fehler';

/** Höchstalter des geladenen Stands: so lange wie der Zwischenspeicher im Archiv (Spec 04, Req 22.2). */
export const FUNKTIONEN_HOECHSTALTER_MS = 60_000;

/** Fehlercode des Archivs, wenn Wissen für den Mandanten nicht freigeschaltet ist (Spec 04, Req 22.6). */
export const WISSEN_NICHT_FREIGESCHALTET = 'WISSEN_NICHT_FREIGESCHALTET';

/** Kennzeichen in `data` der Routen, die zur Wissensbasis gehören (Wächter `wissenGuard`). */
export const WISSENSROUTE = 'wissen';

/**
 * Funktionen des eigenen Mandanten aus `GET /api/v1/mandant/funktionen` (Spec 05, Req 8.8; Spec 04, Req 22.4, 22.5).
 * Geladen wird je Anmeldung (Schlüssel aus Mandant und Benutzer, gesetzt von `AnmeldungService`) und bei jeder
 * Navigation erneut, sobald der Stand älter als {@link FUNKTIONEN_HOECHSTALTER_MS} ist; meldet das Archiv
 * `WISSEN_NICHT_FREIGESCHALTET`, gilt Wissen sofort als ausgeschaltet und der Stand wird neu geladen. Fällt Wissen
 * weg, wechselt eine offene Wissensroute auf «Seite nicht gefunden». Solange die Funktionen nicht geladen sind oder die
 * Abfrage fehlschlägt, gilt «nicht verfügbar» (fail closed); die Prüfung im Archiv bleibt massgebend.
 */
@Injectable({ providedIn: 'root' })
export class MandantFunktionen {
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  private navigationGebunden = false;
  private readonly standIntern = signal<FunktionenStand>('offen');
  private readonly werte = signal<Mandantenfunktionen | null>(null);
  private schluessel: string | null = null;
  private laufend?: Subscription;
  private geladenAm = 0;
  private wartende: (() => void)[] = [];

  readonly stand = this.standIntern.asReadonly();
  readonly wissenVerfuegbar = computed(() => this.standIntern() === 'geladen' && this.werte()?.wissenVerfuegbar === true);

  /**
   * Der Router wird erst hier geholt, nicht im Konstruktor: Den Dienst erzeugen auch Anfragen aus dem Aufbau des Routers
   * (Branding über die Titelstrategie → `anmeldungInterceptor` → `AnmeldungService`); ein Router im Konstruktor ergäbe
   * dort einen Zirkel. Gebunden wird über {@link provideMandantFunktionen}.
   */
  private get router(): Router {
    return this.injector.get(Router);
  }

  /** Prüft bei jedem Navigationsbeginn, ob der Stand aufzufrischen ist (einmalig). */
  anNavigationBinden(): void {
    if (this.navigationGebunden) return;
    this.navigationGebunden = true;
    this.router.events
      .pipe(filter((ereignis) => ereignis instanceof NavigationStart))
      .subscribe(() => this.auffrischen());
  }

  /** Lädt die Funktionen für diese Anmeldung, falls noch nicht geschehen; ein anderer Schlüssel lädt neu. */
  fuer(schluessel: string): void {
    if (this.schluessel === schluessel && this.standIntern() !== 'offen') return;
    this.zuruecksetzen();
    this.schluessel = schluessel;
    this.standIntern.set('laedt');
    this.abfragen();
  }

  /** Lädt neu, wenn der Stand älter als das Höchstalter ist; der bisherige Stand gilt bis zur Antwort weiter. */
  auffrischen(jetzt = Date.now()): void {
    if (this.schluessel === null || this.laufend || this.standIntern() === 'offen') return;
    if (jetzt - this.geladenAm < FUNKTIONEN_HOECHSTALTER_MS) return;
    this.abfragen();
  }

  /** Das Archiv meldete `WISSEN_NICHT_FREIGESCHALTET`: sofort ausblenden und den Stand neu laden. */
  gesperrt(): void {
    const bisher = this.werte();
    if (bisher?.wissenVerfuegbar) {
      this.werte.set({ ...bisher, wissenVerfuegbar: false });
      this.wissenrouteVerlassen();
    }
    if (this.schluessel !== null && !this.laufend) this.abfragen();
  }

  /** Abmelden oder Anmeldung ohne Mandant: nichts verfügbar, eine laufende Abfrage endet. */
  zuruecksetzen(): void {
    this.laufend?.unsubscribe();
    this.laufend = undefined;
    this.schluessel = null;
    this.geladenAm = 0;
    this.werte.set(null);
    this.abschliessen('offen');
  }

  /** Übernimmt geladene Funktionen (auch für Tests der Seiten, die davon abhängen). */
  uebernehmen(werte: Mandantenfunktionen): void {
    const vorher = this.wissenVerfuegbar();
    this.werte.set(werte);
    this.geladenAm = Date.now();
    this.abschliessen('geladen');
    if (vorher && !this.wissenVerfuegbar()) this.wissenrouteVerlassen();
  }

  /** Erfüllt sich, sobald keine Abfrage mehr läuft (geladen, fehlgeschlagen oder nicht gestartet). */
  bereit(): Promise<void> {
    if (!this.laufend && this.standIntern() !== 'laedt') return Promise.resolve();
    return new Promise((erfuellen) => this.wartende.push(erfuellen));
  }

  private abfragen(): void {
    this.laufend?.unsubscribe();
    this.laufend = this.http.get<Mandantenfunktionen>('/api/v1/mandant/funktionen').subscribe({
      next: (werte) => {
        this.laufend = undefined;
        this.uebernehmen(werte);
      },
      error: () => {
        this.laufend = undefined;
        const vorher = this.wissenVerfuegbar();
        this.geladenAm = Date.now();
        this.abschliessen('fehler');
        if (vorher) this.wissenrouteVerlassen();
      },
    });
  }

  /** Steht die App auf einer Wissensroute, wechselt sie auf «Seite nicht gefunden» (Spec 04, Req 22.5). */
  private wissenrouteVerlassen(): void {
    let route: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    let wissen = false;
    while (route) {
      wissen ||= route.data?.[WISSENSROUTE] === true;
      route = route.firstChild;
    }
    if (wissen) void this.router.navigateByUrl('/nicht-gefunden');
  }

  private abschliessen(stand: Exclude<FunktionenStand, 'laedt'>): void {
    this.standIntern.set(stand);
    const wartende = this.wartende;
    this.wartende = [];
    wartende.forEach((erfuellen) => erfuellen());
  }
}

/** Bindet {@link MandantFunktionen} an die Navigation des Routers (Auffrischen nach dem Höchstalter, Req 22.5). */
export function provideMandantFunktionen(): EnvironmentProviders {
  return provideEnvironmentInitializer(() => inject(MandantFunktionen).anNavigationBinden());
}

/** Schlüssel der Anmeldung für {@link MandantFunktionen.fuer}; ohne Mandant keiner. */
export function funktionenSchluessel(benutzer: { id: string; mandant: string | null } | null): string | null {
  return benutzer?.mandant ? `${benutzer.mandant}|${benutzer.id}` : null;
}
