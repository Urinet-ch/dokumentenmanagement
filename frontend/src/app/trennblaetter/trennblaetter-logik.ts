import { TrennblattAnfrage, TrennblattWunsch } from '../gemeinsam/api';
import { SCHRIFT_ABDECKUNG } from './schrift-abdeckung';

/** Höchstens so viele Blätter je PDF (Spec 09, design.md «Trennblatt-Generator»). */
export const MAX_BLAETTER = 100;
/** Höchstens so viele Zeichen hat der Titel eines Ordner-Deckblatts (Spec 09, Req 6.1). */
export const TITEL_MAX = 60;

// Zeichen nach Spec 01, Req 3.10 wie im Backend: Cc, Cs, Zl, Zp und Cf ausser den Verbindern U+200C/U+200D
const VERBOTEN = /[\p{Cc}\p{Cs}\p{Zl}\p{Zp}]|(?![\u200C\u200D])\p{Cf}/u;

export type TitelFehler = 'leer' | 'lang' | 'zeichen' | 'schrift';

export interface Deckblatt {
  readonly titel: string;
  readonly anzahl: number;
}

/** Was das Backend mit 400 TRENNBLATT_UNGUELTIG ablehnen würde; die Prüfung dort bleibt massgeblich. */
export function titelFehler(titel: string): TitelFehler | null {
  if (VERBOTEN.test(titel)) return 'zeichen';
  const sauber = titel.normalize('NFC').trim();
  if (sauber === '') return 'leer';
  const zeichen = [...sauber];
  if (zeichen.length > TITEL_MAX) return 'lang';
  return zeichen.every((z) => darstellbar(z.codePointAt(0)!)) ? null : 'schrift';
}

/** Ob die Schrift der Trennblätter das Zeichen enthält (wie `darstellbar` im Backend). */
export function darstellbar(zeichen: number): boolean {
  return SCHRIFT_ABDECKUNG.some(([von, bis]) => zeichen >= von && zeichen <= bis);
}

/** Zeichen nach NFC und ohne Leerraum aussen, wie das Backend zählt. */
export function titelLaenge(titel: string): number {
  return [...titel.normalize('NFC').trim()].length;
}

/** Ganze Zahl von 0 bis 100; ein leeres Feld zählt als 0. */
export function anzahlLesen(wert: string): number | null {
  if (wert.trim() === '') return 0;
  const zahl = Number(wert);
  return Number.isInteger(zahl) && zahl >= 0 && zahl <= MAX_BLAETTER ? zahl : null;
}

export function summe(anzahlen: readonly number[]): number {
  return anzahlen.reduce((a, b) => a + b, 0);
}

/**
 * Die Anfrage in der Reihenfolge der Seite: Trennblätter ohne Art, je Art in der Reihenfolge der Arten, dann die
 * Deckblätter; Einträge mit Anzahl 0 entfallen.
 */
export function anfrage(
  ohneArt: number,
  jeArt: readonly { code: string; anzahl: number }[],
  deckblaetter: readonly Deckblatt[],
): TrennblattAnfrage {
  const blaetter: TrennblattWunsch[] = [];
  if (ohneArt > 0) blaetter.push({ typ: 'TRENN', anzahl: ohneArt });
  for (const art of jeArt) {
    if (art.anzahl > 0) blaetter.push({ typ: 'TRENN', art: art.code, anzahl: art.anzahl });
  }
  for (const blatt of deckblaetter) {
    if (blatt.anzahl > 0) blaetter.push({ typ: 'ORDNER', titel: blatt.titel, anzahl: blatt.anzahl });
  }
  return { blaetter };
}
