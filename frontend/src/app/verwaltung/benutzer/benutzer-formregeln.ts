import { HttpErrorResponse } from '@angular/common/http';

export type BenutzerFeld = 'benutzername' | 'email' | 'vorname' | 'nachname' | 'rolle';
export type BenutzerFeldfehler = Partial<Record<BenutzerFeld, string>>;
const FELDER: readonly BenutzerFeld[] = ['benutzername', 'email', 'vorname', 'nachname', 'rolle'];

function laengeUndSteuerzeichen(wert: string, maximum: number): boolean {
  const zeichen = Array.from(wert);
  return zeichen.length > 0 && zeichen.length <= maximum
    && !zeichen.some((zeichen) => /[\u0000-\u001f\u007f-\u009f]/.test(zeichen));
}

export function benutzernameGueltig(wert: string): boolean {
  return /^[a-z0-9._-]{3,40}$/.test(wert);
}

export function emailGueltig(wert: string): boolean {
  return laengeUndSteuerzeichen(wert, 254) && wert.indexOf('@') >= 0
    && wert.indexOf('@') === wert.lastIndexOf('@');
}

export function nameGueltig(wert: string): boolean {
  return laengeUndSteuerzeichen(wert, 60);
}

export function benutzerFeldbefunde(fehler: unknown): BenutzerFeldfehler {
  const ergebnis: BenutzerFeldfehler = {};
  if (!(fehler instanceof HttpErrorResponse) || fehler.status !== 422
      || !Array.isArray(fehler.error?.befunde)) return ergebnis;
  for (const befund of fehler.error.befunde as unknown[]) {
    if (!befund || typeof befund !== 'object' || !('pfad' in befund) || !('meldung' in befund)
        || typeof befund.pfad !== 'string' || typeof befund.meldung !== 'string') continue;
    const feld = FELDER.find((eintrag) => eintrag === befund.pfad);
    if (feld) ergebnis[feld] = befund.meldung;
  }
  return ergebnis;
}
