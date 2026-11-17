// Lokale Warteschlange der Handy-Erfassung (Req 4.4–4.8): Modell und reine Auswertung einer Antwort des Archivs.
import { UploadErgebnis } from '../../upload-port';

export interface Seite {
  /** Unbearbeitete Rohaufnahme (Req 4.7). */
  original: Blob;
  /** Zugeschnittene, entzerrte Fassung als JPEG (Req 4.3, 4.7). */
  arbeitsfassung: Blob;
}

export type Zustand = 'wartet' | 'fehlerhaft';

export interface Eintrag {
  id: string;
  /** Zeitpunkt der ersten Seite (ISO, UTC); daraus entsteht der Dateiname nach Req 4.9. */
  aufgenommen: string;
  seiten: Seite[];
  /** Fehlgeschlagene Versuche wegen Netzwerkfehler oder 5xx im Online-Zustand (Req 4.6). */
  fehlversuche: number;
  zustand: Zustand;
  /** Meldung des Servers bzw. Grund bei «fehlerhaft». */
  meldung?: string;
  /**
   * Benutzer (`sub`) und Mandant, für die die Seiten erfasst sind; nur dieser Benutzer lädt sie in diesen Mandanten hoch
   * und sieht sie. Fehlen beide (erfasst ohne Anmeldung), ordnet die nächste Anmeldung mit Recht A1 den Eintrag zu.
   */
  benutzer?: string;
  mandant?: string;
}

export const MAX_FEHLVERSUCHE = 3;

/** Wartezeit nach 429 ohne lesbaren `Retry-After`. */
export const STANDARD_WARTEN_MS = 30_000;

/** Ausgang eines Upload-Versuchs, ohne Angular-Typen. */
export type Ausgang =
  | { art: 'angenommen'; ergebnisse: UploadErgebnis[] }
  | { art: 'abgelehnt'; status: number; fehlercode?: string; detail?: string; ergebnisse?: UploadErgebnis[]; retryAfter?: number | null };

export type Bewertung =
  | { art: 'loeschen' }
  /** 401: Eintrag bleibt unverändert, der Benutzer meldet sich neu an (Req 4.8). */
  | { art: 'anmelden' }
  /** `wartenMs`: 429 mit `Retry-After`; die Schlange wartet so lange, ohne den Versuch als Fehlversuch zu zählen. */
  | { art: 'behalten'; eintrag: Eintrag; wartenMs?: number };

/**
 * Wertet einen Upload-Versuch aus. `online` ist der Zustand des Geräts beim Fehler: Versuche im Offline-Zustand zählen
 * nicht (Req 4.6).
 */
export function bewerten(eintrag: Eintrag, ausgang: Ausgang, online: boolean, meldungen: Meldungen): Bewertung {
  if (ausgang.art === 'angenommen') {
    const abgelehnt = ausgang.ergebnisse.filter((e) => e.status !== 'NEU' && e.status !== 'DUBLETTE');
    // Nur angenommen oder Dublette (3.4): übertragen, lokal löschen (Req 4.5)
    if (abgelehnt.length === 0) {
      return { art: 'loeschen' };
    }
    return fehlerhaft(eintrag, meldungAusErgebnissen(abgelehnt, meldungen));
  }
  const { status } = ausgang;
  if (status === 401) {
    return { art: 'anmelden' };
  }
  if (status === 429) {
    // Zu viele Anfragen: vorübergehend, der Eintrag bleibt wartend und wird nach `Retry-After` erneut gesendet
    const sekunden = ausgang.retryAfter ?? 0;
    return { art: 'behalten', eintrag, wartenMs: sekunden > 0 ? sekunden * 1000 : STANDARD_WARTEN_MS };
  }
  if (status >= 400 && status < 500) {
    const meldung =
      ausgang.detail ??
      (ausgang.fehlercode && meldungen.fehlercode[ausgang.fehlercode]) ??
      meldungAusErgebnissen(ausgang.ergebnisse ?? [], meldungen);
    const statusText = [403, 404, 409, 412].includes(status) ? meldungen.status?.(status) : undefined;
    return fehlerhaft(eintrag, meldung || statusText || meldungen.unbekannt);
  }
  if (!online) {
    return { art: 'behalten', eintrag };
  }
  const fehlversuche = eintrag.fehlversuche + 1;
  if (fehlversuche >= MAX_FEHLVERSUCHE) {
    return { art: 'behalten', eintrag: { ...eintrag, fehlversuche, zustand: 'fehlerhaft', meldung: meldungen.wiederholtFehlgeschlagen } };
  }
  return { art: 'behalten', eintrag: { ...eintrag, fehlversuche } };
}

export interface Meldungen {
  unbekannt: string;
  wiederholtFehlgeschlagen: string;
  fehlercode: Record<string, string>;
  /** Text zum HTTP-Status (403, 404, 409, 412), wenn der Server weder Detail noch Fehlercode liefert. */
  status?: (status: number) => string;
}

function fehlerhaft(eintrag: Eintrag, meldung: string): Bewertung {
  return { art: 'behalten', eintrag: { ...eintrag, zustand: 'fehlerhaft', meldung } };
}

function meldungAusErgebnissen(ergebnisse: UploadErgebnis[], meldungen: Meldungen): string {
  const texte = ergebnisse.map(
    (e) => e.fehler?.detail ?? (e.fehler?.fehlercode && meldungen.fehlercode[e.fehler.fehlercode]) ?? e.meldung ?? meldungen.unbekannt,
  );
  return [...new Set(texte)].join(' ');
}
