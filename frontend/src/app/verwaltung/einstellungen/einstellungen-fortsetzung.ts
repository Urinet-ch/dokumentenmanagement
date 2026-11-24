import { Injectable, inject } from '@angular/core';
import { FENSTER } from '../../gemeinsam/anmeldung/anmeldung.service';

export interface EinstellungsFortsetzung {
  id: string;
  benutzer: string;
  mandant: string;
  etag: string;
  eingaben: Record<string, string | null>;
  bestaetigt: string[];
  erstellt: number;
}

export class EinstellungsFortsetzungFehler extends Error {}

const SCHLUESSEL = 'archiv.einstellungen.fortsetzung';
const MAX_ALTER_MS = 10 * 60 * 1000;
const MAX_BYTES = 16 * 1024;

function objekt(wert: unknown): wert is Record<string, unknown> {
  return wert !== null && typeof wert === 'object' && !Array.isArray(wert);
}

function gueltig(wert: unknown): wert is EinstellungsFortsetzung {
  if (!objekt(wert) || typeof wert['id'] !== 'string' || !wert['id']
      || typeof wert['benutzer'] !== 'string' || !wert['benutzer']
      || typeof wert['mandant'] !== 'string' || !wert['mandant']
      || typeof wert['etag'] !== 'string' || !/^"[1-9]\d*"$/.test(wert['etag'])
      || typeof wert['erstellt'] !== 'number' || !Number.isFinite(wert['erstellt'])
      || !objekt(wert['eingaben']) || !Array.isArray(wert['bestaetigt'])) {
    return false;
  }
  const gespeicherteEingaben = wert['eingaben'];
  const eingaben = Object.entries(gespeicherteEingaben);
  return eingaben.length > 0 && eingaben.length <= 20
    && eingaben.every(([name, eingabe]) => /^[a-zA-Z][a-zA-Z0-9]*(?:\.[a-zA-Z][a-zA-Z0-9]*)*$/.test(name)
      && (typeof eingabe === 'string' || eingabe === null))
    && wert['bestaetigt'].every((schluessel: unknown) =>
      typeof schluessel === 'string' && Object.hasOwn(gespeicherteEingaben, schluessel));
}

@Injectable({ providedIn: 'root' })
export class EinstellungenFortsetzen {
  private readonly speicher = inject(FENSTER).sessionStorage;

  merken(fortsetzung: EinstellungsFortsetzung): void {
    const text = JSON.stringify(fortsetzung);
    if (!gueltig(fortsetzung) || new TextEncoder().encode(text).length > MAX_BYTES) {
      throw new EinstellungsFortsetzungFehler('Änderung nicht speicherbar');
    }
    this.speicher.setItem(SCHLUESSEL, text);
  }

  entnehmen(id: string, benutzer: string, mandant: string, jetzt = Date.now()): EinstellungsFortsetzung | null {
    const text = this.speicher.getItem(SCHLUESSEL);
    this.speicher.removeItem(SCHLUESSEL);
    if (text === null) {
      return null;
    }
    if (new TextEncoder().encode(text).length > MAX_BYTES) {
      throw new EinstellungsFortsetzungFehler('Änderung ungültig');
    }
    let gelesen: unknown;
    try {
      gelesen = JSON.parse(text);
    } catch (fehler: unknown) {
      if (!(fehler instanceof SyntaxError)) {
        throw fehler;
      }
      throw new EinstellungsFortsetzungFehler('Änderung ungültig');
    }
    if (!gueltig(gelesen) || gelesen.id !== id || gelesen.benutzer !== benutzer || gelesen.mandant !== mandant
        || gelesen.erstellt > jetzt || jetzt - gelesen.erstellt > MAX_ALTER_MS) {
      throw new EinstellungsFortsetzungFehler('Änderung abgelaufen oder Sitzung gewechselt');
    }
    return gelesen;
  }

  verwerfen(): void {
    this.speicher.removeItem(SCHLUESSEL);
  }
}
