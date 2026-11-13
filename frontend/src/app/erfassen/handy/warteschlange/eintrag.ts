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

/** Ausgang eines Upload-Versuchs, ohne Angular-Typen. */
export type Ausgang =
  | { art: 'angenommen'; ergebnisse: UploadErgebnis[] }
  | { art: 'abgelehnt'; status: number; fehlercode?: string; detail?: string; ergebnisse?: UploadErgebnis[] };

export type Bewertung =
  | { art: 'loeschen' }
  /** 401: Eintrag bleibt unverändert, der Benutzer meldet sich neu an (Req 4.8). */
  | { art: 'anmelden' }
  | { art: 'behalten'; eintrag: Eintrag };

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
  if (status >= 400 && status < 500) {
    const meldung =
      ausgang.detail ??
      (ausgang.fehlercode && meldungen.fehlercode[ausgang.fehlercode]) ??
      meldungAusErgebnissen(ausgang.ergebnisse ?? [], meldungen);
    return fehlerhaft(eintrag, meldung || meldungen.unbekannt);
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
