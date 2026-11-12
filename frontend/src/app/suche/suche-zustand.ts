import { Injectable, signal } from '@angular/core';
import { Suchergebnis } from '../gemeinsam/api';
import { Suchanfrage } from './suche-port';
import { Filterwerte, LEERE_FILTER } from './suchfilter';

/**
 * Letzte Suche nur im Arbeitsspeicher, damit «Zurück» aus der Dokumentansicht die Treffer wieder zeigt. Suchbegriffe
 * und Treffer kommen weder in die Adresse (Verlauf) noch in Local oder Session Storage; ein Wechsel des Benutzers oder
 * Mandanten verwirft den Stand.
 */
@Injectable({ providedIn: 'root' })
export class SucheZustand {
  private besitzer: string | null = null;
  readonly filter = signal<Filterwerte>(LEERE_FILTER);
  readonly seite = signal(0);
  readonly ergebnis = signal<Suchergebnis | null>(null);
  /** Zuletzt gesendete Anfrage; Blättern ändert nur deren Seite, nicht abgeschickte Filter bleiben aussen vor. */
  readonly gesendet = signal<Suchanfrage | null>(null);

  /** Gibt den Stand nur dem Benutzer zurück, der ihn angelegt hat; sonst beginnt die Suche leer. */
  fuer(besitzer: string): void {
    if (this.besitzer !== besitzer) {
      this.besitzer = besitzer;
      this.filter.set(LEERE_FILTER);
      this.seite.set(0);
      this.ergebnis.set(null);
      this.gesendet.set(null);
    }
  }
}
