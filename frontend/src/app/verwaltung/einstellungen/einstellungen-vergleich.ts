import { TEXTE } from '../../gemeinsam/texte.de-CH';

export interface EinstellungsVergleich {
  pfad: string;
  alt: string;
  neu: string;
}

export class EinstellungenVergleichFehler extends Error {}

function objekt(wert: unknown): wert is Record<string, unknown> {
  return wert !== null && typeof wert === 'object' && !Array.isArray(wert);
}

function felder(wert: unknown, pfad: string, ergebnis: Map<string, unknown>): void {
  if (objekt(wert)) {
    for (const [schluessel, feld] of Object.entries(wert)) {
      felder(feld, `${pfad}.${schluessel}`, ergebnis);
    }
  } else {
    ergebnis.set(pfad, wert);
  }
}

function einstellungen(abbild: unknown): Map<string, unknown> {
  if (!objekt(abbild) || (abbild['einstellungen'] != null && !objekt(abbild['einstellungen']))) {
    throw new EinstellungenVergleichFehler('Ungültiges Konfigurationsabbild');
  }
  const ergebnis = new Map<string, unknown>();
  if (abbild['einstellungen'] != null) {
    felder(abbild['einstellungen'], 'einstellungen', ergebnis);
  }
  return ergebnis;
}

function anzeigen(wert: unknown): string {
  if (wert == null) {
    return TEXTE.einstellungen.nichtGesetzt;
  }
  if (typeof wert === 'number' && Number.isFinite(wert)) {
    return new Intl.NumberFormat('de-CH').format(wert).replaceAll('’', "'");
  }
  if (typeof wert === 'string' || typeof wert === 'boolean') {
    return String(wert);
  }
  if (Array.isArray(wert) && wert.every((element) => typeof element === 'string')) {
    return wert.join(', ');
  }
  throw new EinstellungenVergleichFehler('Ungültiger Einstellungswert');
}

/** Vergleicht nur Einstellungen; Kennungen werden vor der Rückgabe unkenntlich gemacht. */
export function einstellungenVergleichen(alt: unknown, neu: unknown): EinstellungsVergleich[] {
  const alteWerte = einstellungen(alt);
  const neueWerte = einstellungen(neu);
  const pfade = [...new Set([...alteWerte.keys(), ...neueWerte.keys()])].sort();
  return pfade.flatMap((pfad) => {
    const vorher = alteWerte.get(pfad) ?? null;
    const nachher = neueWerte.get(pfad) ?? null;
    if (JSON.stringify(vorher) === JSON.stringify(nachher)) {
      return [];
    }
    if (pfad === 'einstellungen.kennungen' || pfad.startsWith('einstellungen.kennungen.')) {
      return [{ pfad, alt: TEXTE.einstellungen.verborgen, neu: TEXTE.einstellungen.verborgen }];
    }
    return [{ pfad, alt: anzeigen(vorher), neu: anzeigen(nachher) }];
  });
}
