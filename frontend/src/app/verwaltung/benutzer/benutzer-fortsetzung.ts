import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, Injector, inject } from '@angular/core';
import { AnmeldungService, FENSTER } from '../../gemeinsam/anmeldung/anmeldung.service';
import { zufall } from '../../gemeinsam/anmeldung/pkce';

/**
 * Aktionen, die nach `STEP_UP_NOETIG` fortgesetzt werden können. Die ersten (Status, Rolle) tragen nur Codes und werden
 * nach der frischen Anmeldung genau einmal selbst gesendet; Löschen, Profil und Einladen brauchen persönliche Eingaben
 * oder die Namensbestätigung und werden nur wieder geöffnet (Eingaben neu, nichts davon im Browser-Speicher).
 */
export type FortsetzungAktion =
  | 'sperren' | 'entsperren' | 'deaktivieren' | 'reaktivieren' | 'zugangZuruecksetzen' | 'einladung' | 'googleLoesen'
  | 'rolle' | 'loeschen' | 'profil' | 'einladen';

export interface BenutzerFortsetzung {
  id: string;
  benutzer: string;
  mandant: string;
  aktion: FortsetzungAktion;
  /** Kennung des Ziel-Benutzers; leer beim Einladen. */
  ziel: string;
  etag?: string;
  grund?: string;
  rolle?: string;
  /** Nur der Code der Option «Passkeys löschen» (Wahrheitswert), nie Gerätedaten. */
  passkeys?: boolean;
  erstellt: number;
}

export type FortsetzungEingabe = Pick<BenutzerFortsetzung, 'aktion' | 'ziel' | 'etag' | 'grund' | 'rolle' | 'passkeys'>;

const SCHLUESSEL = 'archiv.benutzer.fortsetzung';
const MAX_ALTER_MS = 10 * 60 * 1000;
const MAX_BYTES = 2048;
const AKTIONEN: readonly string[] = ['sperren', 'entsperren', 'deaktivieren', 'reaktivieren', 'zugangZuruecksetzen',
  'einladung', 'googleLoesen', 'rolle', 'loeschen', 'profil', 'einladen'];
const KENNUNG = /^[A-Za-z0-9._-]{1,64}$/;

function objekt(wert: unknown): wert is Record<string, unknown> {
  return wert !== null && typeof wert === 'object' && !Array.isArray(wert);
}

function text(wert: unknown, muster: RegExp): wert is string {
  return typeof wert === 'string' && muster.test(wert);
}

function gueltig(wert: unknown): wert is BenutzerFortsetzung {
  if (!objekt(wert)) return false;
  const erlaubt = ['id', 'benutzer', 'mandant', 'aktion', 'ziel', 'etag', 'grund', 'rolle', 'passkeys', 'erstellt'];
  if (Object.keys(wert).some((schluessel) => !erlaubt.includes(schluessel))) return false;
  const aktion = wert['aktion'];
  const einladen = aktion === 'einladen';
  return text(wert['id'], KENNUNG) && text(wert['benutzer'], KENNUNG) && text(wert['mandant'], /^[\w.:@-]{1,128}$/)
    && typeof aktion === 'string' && AKTIONEN.includes(aktion)
    && (einladen ? wert['ziel'] === '' : text(wert['ziel'], KENNUNG))
    && (wert['etag'] === undefined || text(wert['etag'], /^[a-f0-9]{64}$/))
    && (wert['grund'] === undefined || text(wert['grund'], /^[A-Z_a-z]{1,40}$/))
    && (wert['rolle'] === undefined || text(wert['rolle'], /^[a-z_]{1,40}$/))
    && (wert['passkeys'] === undefined || (wert['passkeys'] === true && aktion === 'zugangZuruecksetzen'))
    && (aktion !== 'rolle' || (wert['etag'] !== undefined && wert['rolle'] !== undefined))
    && typeof wert['erstellt'] === 'number' && Number.isFinite(wert['erstellt']);
}

export function istStepUp(fehler: unknown): boolean {
  return fehler instanceof HttpErrorResponse && fehler.status === 401
    && (fehler.error as { fehlercode?: unknown } | null)?.fehlercode === 'STEP_UP_NOETIG';
}

export function zielPfad(ziel: string): string {
  return ziel ? `/verwaltung/benutzer/${encodeURIComponent(ziel)}` : '/verwaltung/benutzer';
}

@Injectable({ providedIn: 'root' })
export class BenutzerFortsetzen {
  private readonly injector = inject(Injector);
  private readonly speicher = inject(FENSTER).sessionStorage;

  /**
   * Merkt Aktion, Ziel-Kennung, ETag und Codes (nie Namen, Adressen oder Freitext) und leitet zur frischen Anmeldung.
   * `false`, wenn das nicht möglich ist; die Aufrufer zeigen dann die Fehlermeldung. Scheitert der Start der Anmeldung erst
   * asynchron (vor der Weiterleitung), ruft der Dienst `beiFehler` auf: Die Aufrufer lösen den Laufzustand und zeigen dieselbe
   * Meldung zur Wiederholung.
   */
  starten(eingabe: FortsetzungEingabe, beiFehler: () => void = () => undefined): boolean {
    const anmeldung = this.injector.get(AnmeldungService);
    const benutzer = anmeldung.benutzer();
    if (!benutzer?.id || !benutzer.mandant) return false;
    const fortsetzung: BenutzerFortsetzung = { id: zufall(), benutzer: benutzer.id, mandant: benutzer.mandant,
      erstellt: Date.now(), ...eingabe };
    const serialisiert = JSON.stringify(fortsetzung);
    if (!gueltig(fortsetzung) || new TextEncoder().encode(serialisiert).length > MAX_BYTES) return false;
    try {
      this.speicher.setItem(SCHLUESSEL, serialisiert);
    } catch (fehler: unknown) {
      if (!(fehler instanceof DOMException)) throw fehler;
      return false;
    }
    void anmeldung.anmelden(zielPfad(fortsetzung.ziel), true, fortsetzung.id).catch((fehler: unknown) => {
      if (!(fehler instanceof Error)) throw fehler;
      this.verwerfen();
      beiFehler();
    });
    return true;
  }

  /**
   * Übernimmt die Fortsetzung des erfolgreich geprüften OIDC-Rückrufs, genau einmal (der Speicher wird dabei geleert).
   * `null`: keine Fortsetzung; `'ungueltig'`: abgelaufen, fremder Benutzer oder Mandant, beschädigt.
   */
  uebernehmen(jetzt = Date.now()): BenutzerFortsetzung | 'ungueltig' | null {
    const anmeldung = this.injector.get(AnmeldungService);
    const id = anmeldung.fortsetzungEntnehmen();
    if (!id) return null;
    const gespeichert = this.speicher.getItem(SCHLUESSEL);
    this.verwerfen();
    if (gespeichert === null || new TextEncoder().encode(gespeichert).length > MAX_BYTES) return 'ungueltig';
    let gelesen: unknown;
    try {
      gelesen = JSON.parse(gespeichert);
    } catch (fehler: unknown) {
      if (!(fehler instanceof SyntaxError)) throw fehler;
      return 'ungueltig';
    }
    const benutzer = anmeldung.benutzer();
    if (!gueltig(gelesen) || gelesen.id !== id || !benutzer || gelesen.benutzer !== benutzer.id
        || gelesen.mandant !== benutzer.mandant || gelesen.erstellt > jetzt || jetzt - gelesen.erstellt > MAX_ALTER_MS) {
      return 'ungueltig';
    }
    return gelesen;
  }

  verwerfen(): void {
    try {
      this.speicher.removeItem(SCHLUESSEL);
    } catch (fehler: unknown) {
      if (!(fehler instanceof DOMException)) throw fehler;
    }
  }
}
