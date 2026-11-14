import { zeitpunkt } from '../gemeinsam/format';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { zahl } from '../importe/import-logik';
import { ChatFehler, DatenstandQuelle, Quelle } from './chat-port';

// Fachlogik der Seite «Chat» ohne Angular (Spec 02, Aufgabe 7.4; Req 4.3, 6.1–6.3)

/**
 * Ohne `grund` in den Problem Details gilt ein 429 mit höchstens so vielen Sekunden in `Retry-After` als «schon eine
 * Frage in Arbeit»: 7.3 sendet dafür fest `Retry-After: 5`. Das Stundenlimit zählt rollend über 60 Minuten (Req 5.5)
 * und kann 1–3'600 Sekunden melden; nur in seinen letzten 5 Sekunden zeigt die Seite darum den falschen Text.
 */
export const LAUFEND_BIS_SEKUNDEN = 5;

/** Längste Frage nach dem Trimmen (Vertrag Welle 7, `frage` 1–2'000 Zeichen). */
export const FRAGE_MAX = 2000;

/** Ohne lesbares `Retry-After` die längste mögliche Wartezeit des Stundenlimits. */
const STUNDE_SEKUNDEN = 3600;

const TAG = new Intl.DateTimeFormat('de-CH', {
  timeZone: 'Europe/Zurich',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

const UHRZEIT = new Intl.DateTimeFormat('de-CH', {
  timeZone: 'Europe/Zurich',
  hour: '2-digit',
  minute: '2-digit',
});

/** Datum eines Zeitpunkts als `TT.MM.JJJJ` in Europe/Zurich. */
export function tag(iso: string): string {
  const zeit = new Date(iso);
  return Number.isNaN(zeit.getTime()) ? '' : TAG.format(zeit);
}

export function uhrzeit(zeit: Date): string {
  return UHRZEIT.format(zeit);
}

export function werkzeugHinweis(name: string): string {
  return Object.hasOwn(TEXTE.chat.werkzeuge, name)
    ? TEXTE.chat.werkzeuge[name]
    : TEXTE.chat.werkzeugUnbekannt;
}

/**
 * Nur ein relativer Pfad auf genau ein Dokument (`/dokument/<id>`) wird zum Link; alles andere (fremde Adressen,
 * andere Seiten, Pfade mit `..`) bleibt ein Chip ohne Link. Die Ansicht öffnet das Dokument mit Protokolleintrag.
 */
export function dokumentLink(link: string): string | null {
  return /^\/dokument\/[A-Za-z0-9-]+$/.test(link) ? link : null;
}

export function istDatenstand(quelle: Quelle): quelle is DatenstandQuelle {
  return 'datenstand' in quelle;
}

export function datenstandText(quelle: DatenstandQuelle): string {
  const { zeitpunkt: wann, anzahl } = quelle.datenstand;
  return TEXTE.chat.datenstand(zeitpunkt(wann) || wann, zahl(anzahl));
}

/** Meldung zu einem Fehler vor oder während des Stroms. */
export function fehlerText(fehler: ChatFehler, jetzt: Date): string {
  if (fehler.art === 'ZU_VIELE') {
    const laeuft =
      fehler.grund !== null
        ? fehler.grund === 'LAEUFT'
        : fehler.sekunden !== null && fehler.sekunden <= LAUFEND_BIS_SEKUNDEN;
    if (laeuft) {
      return TEXTE.chat.laufend;
    }
    // auf die volle Minute aufgerundet: Wer zur angezeigten Uhrzeit fragt, ist sicher wieder dran
    const ende = jetzt.getTime() + (fehler.sekunden ?? STUNDE_SEKUNDEN) * 1000;
    return TEXTE.chat.zuViele(uhrzeit(new Date(Math.ceil(ende / 60_000) * 60_000)));
  }
  return TEXTE.chat.fehler[fehler.art] ?? TEXTE.chat.fehler['ALLGEMEIN'];
}
