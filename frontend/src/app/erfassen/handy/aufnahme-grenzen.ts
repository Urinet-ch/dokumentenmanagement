import { TEXTE } from '../../gemeinsam/texte.de-CH';

export const MAX_AUFNAHME_SEITEN = 20;
export const MAX_AUFNAHME_BYTES = 10 * 1024 * 1024;
export const MAX_BILD_PIXEL = 12_000_000;

export type AufnahmeGrenze = 'seiten' | 'groesse' | 'pixel';

export class AufnahmeGrenzeFehler extends Error {
  constructor(readonly grenze: AufnahmeGrenze) {
    super(grenze);
  }
}

export function aufnahmeGrenzeMeldung(fehler: AufnahmeGrenzeFehler): string {
  switch (fehler.grenze) {
    case 'seiten':
      return TEXTE.handy.aufnahmeZuVieleSeiten(MAX_AUFNAHME_SEITEN);
    case 'groesse':
      return TEXTE.handy.aufnahmeZuGross;
    case 'pixel':
      return TEXTE.handy.aufnahmeZuVielePixel;
  }
}

export function istAufnahmeGrenze(wert: string): wert is AufnahmeGrenze {
  return wert === 'seiten' || wert === 'groesse' || wert === 'pixel';
}

export function pruefeAufnahme(
  seiten: readonly { original: Blob; arbeitsfassung?: Blob }[],
): void {
  if (seiten.length > MAX_AUFNAHME_SEITEN) {
    throw new AufnahmeGrenzeFehler('seiten');
  }
  const bytes = seiten.reduce((summe, seite) => summe + seite.original.size + (seite.arbeitsfassung?.size ?? 0), 0);
  if (bytes > MAX_AUFNAHME_BYTES) {
    throw new AufnahmeGrenzeFehler('groesse');
  }
}

export function pruefeBildPixel(breite: number, hoehe: number): void {
  if (!Number.isSafeInteger(breite) || !Number.isSafeInteger(hoehe) || breite < 1 || hoehe < 1 || breite * hoehe > MAX_BILD_PIXEL) {
    throw new AufnahmeGrenzeFehler('pixel');
  }
}
