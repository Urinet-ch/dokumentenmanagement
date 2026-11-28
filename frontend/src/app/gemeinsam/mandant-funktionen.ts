import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { Mandantenfunktionen } from './api';

export type FunktionenStand = 'offen' | 'laedt' | 'geladen' | 'fehler';

/**
 * Funktionen des eigenen Mandanten aus `GET /api/v1/mandant/funktionen` (Spec 05, Req 8.8; Spec 04, Req 22.4, 22.5).
 * Geladen wird einmal je Anmeldung (Schlüssel aus Mandant und Benutzer, gesetzt von `AnmeldungService`). Solange die
 * Funktionen nicht geladen sind oder die Abfrage fehlschlägt, gilt «nicht verfügbar» (fail closed); die Prüfung im
 * Archiv bleibt massgebend.
 */
@Injectable({ providedIn: 'root' })
export class MandantFunktionen {
  private readonly http = inject(HttpClient);
  private readonly standIntern = signal<FunktionenStand>('offen');
  private readonly werte = signal<Mandantenfunktionen | null>(null);
  private schluessel: string | null = null;
  private laufend?: Subscription;
  private wartende: (() => void)[] = [];

  readonly stand = this.standIntern.asReadonly();
  readonly wissenVerfuegbar = computed(() => this.standIntern() === 'geladen' && this.werte()?.wissenVerfuegbar === true);

  /** Lädt die Funktionen für diese Anmeldung, falls noch nicht geschehen; ein anderer Schlüssel lädt neu. */
  fuer(schluessel: string): void {
    if (this.schluessel === schluessel && this.standIntern() !== 'offen') return;
    this.zuruecksetzen();
    this.schluessel = schluessel;
    this.standIntern.set('laedt');
    this.laufend = this.http.get<Mandantenfunktionen>('/api/v1/mandant/funktionen').subscribe({
      next: (werte) => this.uebernehmen(werte),
      error: () => this.abschliessen('fehler'),
    });
  }

  /** Abmelden oder Anmeldung ohne Mandant: nichts verfügbar, eine laufende Abfrage endet. */
  zuruecksetzen(): void {
    this.laufend?.unsubscribe();
    this.laufend = undefined;
    this.schluessel = null;
    this.werte.set(null);
    this.abschliessen('offen');
  }

  /** Übernimmt geladene Funktionen (auch für Tests der Seiten, die davon abhängen). */
  uebernehmen(werte: Mandantenfunktionen): void {
    this.werte.set(werte);
    this.abschliessen('geladen');
  }

  /** Erfüllt sich, sobald keine Abfrage mehr läuft (geladen, fehlgeschlagen oder nicht gestartet). */
  bereit(): Promise<void> {
    if (this.standIntern() !== 'laedt') return Promise.resolve();
    return new Promise((erfuellen) => this.wartende.push(erfuellen));
  }

  private abschliessen(stand: Exclude<FunktionenStand, 'laedt'>): void {
    this.standIntern.set(stand);
    const wartende = this.wartende;
    this.wartende = [];
    wartende.forEach((erfuellen) => erfuellen());
  }
}

/** Schlüssel der Anmeldung für {@link MandantFunktionen.fuer}; ohne Mandant keiner. */
export function funktionenSchluessel(benutzer: { id: string; mandant: string | null } | null): string | null {
  return benutzer?.mandant ? `${benutzer.mandant}|${benutzer.id}` : null;
}
