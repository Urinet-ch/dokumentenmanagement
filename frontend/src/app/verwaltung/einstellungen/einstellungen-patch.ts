import { EinstellungskatalogEintrag } from '../../gemeinsam/api';

export class EinstellungenEingabeFehler extends Error {
  constructor(readonly schluessel: string) {
    super('Ungültige Eingabe');
  }
}

type JsonObjekt = { [name: string]: JsonObjekt | string | number | string[] | null };

function wert(eintrag: EinstellungskatalogEintrag, eingabe: string | null): string | number | string[] | null {
  if (eingabe === null) {
    return null;
  }
  switch (eintrag.typ) {
    case 'GANZZAHL': {
      const text = eingabe.trim();
      const zahl = Number(text);
      if (!/^-?\d+$/.test(text) || !Number.isSafeInteger(zahl) || zahl < -2147483648 || zahl > 2147483647) {
        throw new EinstellungenEingabeFehler(eintrag.schluessel ?? '');
      }
      return zahl;
    }
    case 'IBANS':
      return eingabe.split(/\r?\n/).map((teil) => teil.trim()).filter((teil) => teil.length > 0);
    case 'DATUM':
    case 'UID':
    case 'TEXT':
    case 'FARBE':
    case 'WOCHENTAG':
      return eingabe;
    default:
      throw new EinstellungenEingabeFehler(eintrag.schluessel ?? '');
  }
}

export function einstellungenPatch(
  eintraege: readonly EinstellungskatalogEintrag[],
  eingaben: Readonly<Record<string, string | null>>,
): JsonObjekt {
  const katalog = new Map(eintraege.map((eintrag) => [eintrag.schluessel, eintrag]));
  const patch: JsonObjekt = {};
  for (const [schluessel, eingabe] of Object.entries(eingaben)) {
    const eintrag = katalog.get(schluessel);
    const teile = schluessel.split('.');
    if (!eintrag || !teile.every((teil) => /^[a-zA-Z][a-zA-Z0-9]*$/.test(teil)
        && teil !== 'constructor' && teil !== 'prototype')) {
      throw new EinstellungenEingabeFehler(schluessel);
    }
    let ziel = patch;
    for (const teil of teile.slice(0, -1)) {
      if (!Object.hasOwn(ziel, teil)) {
        ziel[teil] = {};
      }
      const kind = ziel[teil];
      if (kind === null || typeof kind !== 'object' || Array.isArray(kind)) {
        throw new EinstellungenEingabeFehler(schluessel);
      }
      ziel = kind;
    }
    ziel[teile[teile.length - 1]] = wert(eintrag, eingabe);
  }
  return patch;
}
