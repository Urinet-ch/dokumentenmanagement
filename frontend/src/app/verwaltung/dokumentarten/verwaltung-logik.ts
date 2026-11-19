import { HttpErrorResponse } from '@angular/common/http';
import { Dokumentart } from '../../gemeinsam/api';
import { httpFehlerMeldung } from '../../gemeinsam/http-fehler';
import { ZusatzFeld, zusatzFelder } from '../../dokument/aenderung';

export type ZusatzTyp = ZusatzFeld['typ'];

export interface FeldOption {
  wert: string;
  beschriftung: string;
}

export interface FeldEntwurf {
  code: string;
  name: string;
  typ: ZusatzTyp;
  pflicht: boolean;
  beschreibung: string;
  optionen: FeldOption[];
  minEintraege?: number;
  maxEintraege?: number;
  eindeutig: boolean;
}

export type SchemaEditor =
  { modus: 'felder'; felder: FeldEntwurf[] } | { modus: 'json'; inhalt: string };

export interface Versionsaenderung {
  pfad: string;
  alt: string;
  neu: string;
  werteVerdeckt: boolean;
}

const WURZEL_SCHLUESSEL = new Set([
  '$schema',
  'type',
  'properties',
  'required',
  'additionalProperties',
]);
const FELD_SCHLUESSEL = new Set([
  'type',
  'title',
  'description',
  'format',
  'enum',
  'x-labels',
  'items',
  'minItems',
  'maxItems',
  'uniqueItems',
]);
const ITEM_SCHLUESSEL = new Set(['type', 'enum', 'x-labels']);

function objekt(wert: unknown): Record<string, unknown> | null {
  return wert !== null && typeof wert === 'object' && !Array.isArray(wert)
    ? (wert as Record<string, unknown>)
    : null;
}

function stringListe(wert: unknown): wert is string[] {
  return Array.isArray(wert) && wert.every((eintrag) => typeof eintrag === 'string');
}

function labelsGueltig(wert: unknown, optionen: readonly string[]): boolean {
  if (wert === undefined) {
    return true;
  }
  const labels = objekt(wert);
  return (
    labels !== null &&
    Object.entries(labels).every(
      ([schluessel, text]) => optionen.includes(schluessel) && typeof text === 'string',
    )
  );
}

function schemaFeldDarstellbar(schema: unknown): boolean {
  const feld = objekt(schema);
  if (feld === null || Object.keys(feld).some((schluessel) => !FELD_SCHLUESSEL.has(schluessel))) {
    return false;
  }
  if (feld['type'] === 'array') {
    const items = objekt(feld['items']);
    if (
      items === null ||
      Object.keys(items).some((schluessel) => !ITEM_SCHLUESSEL.has(schluessel)) ||
      items['type'] !== 'string' ||
      (items['enum'] !== undefined && !stringListe(items['enum'])) ||
      !labelsGueltig(items['x-labels'], stringListe(items['enum']) ? items['enum'] : []) ||
      !labelsGueltig(feld['x-labels'], stringListe(items['enum']) ? items['enum'] : [])
    ) {
      return false;
    }
    return (
      (feld['minItems'] === undefined || Number.isInteger(feld['minItems'])) &&
      (feld['maxItems'] === undefined || Number.isInteger(feld['maxItems'])) &&
      (feld['uniqueItems'] === undefined || typeof feld['uniqueItems'] === 'boolean')
    );
  }
  if (
    feld['items'] !== undefined ||
    feld['minItems'] !== undefined ||
    feld['maxItems'] !== undefined
  ) {
    return false;
  }
  const typ = feld['type'];
  if (typ !== 'string' && typ !== 'integer' && typ !== 'number' && typ !== 'boolean') {
    return false;
  }
  if (feld['format'] !== undefined && !(typ === 'string' && feld['format'] === 'date')) {
    return false;
  }
  if (feld['enum'] !== undefined && !(typ === 'string' && stringListe(feld['enum']))) {
    return false;
  }
  const optionen = stringListe(feld['enum']) ? feld['enum'] : [];
  return labelsGueltig(feld['x-labels'], optionen) && feld['uniqueItems'] === undefined;
}

function schemaDarstellbar(schema: unknown): schema is Record<string, unknown> {
  const wurzel = objekt(schema);
  if (
    wurzel === null ||
    Object.keys(wurzel).some((schluessel) => !WURZEL_SCHLUESSEL.has(schluessel)) ||
    (wurzel['type'] !== undefined && wurzel['type'] !== 'object') ||
    (wurzel['$schema'] !== undefined && typeof wurzel['$schema'] !== 'string') ||
    (wurzel['additionalProperties'] !== undefined &&
      typeof wurzel['additionalProperties'] !== 'boolean') ||
    (wurzel['required'] !== undefined && !stringListe(wurzel['required']))
  ) {
    return false;
  }
  const properties = wurzel['properties'] === undefined ? {} : objekt(wurzel['properties']);
  if (properties === null) {
    return false;
  }
  const required = stringListe(wurzel['required']) ? wurzel['required'] : [];
  return (
    required.every((name) => Object.hasOwn(properties, name)) &&
    Object.values(properties).every(schemaFeldDarstellbar)
  );
}

export function schemaEditor(art: Dokumentart): SchemaEditor {
  const schema = art.zusatzSchema;
  if (!schemaDarstellbar(schema)) {
    return { modus: 'json', inhalt: JSON.stringify(schema ?? {}, null, 2) };
  }
  const parsed = zusatzFelder(art);
  const properties = objekt(schema['properties']) ?? {};
  if (Object.keys(properties).length !== parsed.length) {
    return { modus: 'json', inhalt: JSON.stringify(schema, null, 2) };
  }
  return {
    modus: 'felder',
    felder: parsed.map((feld) => ({
      code: feld.name,
      name: feld.titel ?? feld.name,
      typ: feld.typ,
      pflicht: feld.pflicht,
      beschreibung: feld.beschreibung ?? '',
      optionen: feld.optionen.map((wert) => ({
        wert,
        beschriftung: feld.beschriftungen?.[wert] ?? wert,
      })),
      minEintraege: feld.minEintraege,
      maxEintraege: feld.maxEintraege,
      eindeutig: feld.eindeutig ?? false,
    })),
  };
}

export function schemaAusFeldern(
  felder: readonly FeldEntwurf[],
  bestehend: unknown,
): Record<string, unknown> {
  const vorher = objekt(bestehend) ?? {};
  const properties: Record<string, unknown> = {};
  for (const feld of felder) {
    const name = feld.code.trim();
    const titel = feld.name.trim();
    const beschreibung = feld.beschreibung.trim();
    const optionen = feld.optionen
      .map((option) => option.wert.trim())
      .filter((wert) => wert !== '');
    const labels = Object.fromEntries(
      feld.optionen
        .map((option) => [option.wert.trim(), option.beschriftung.trim()] as const)
        .filter(
          ([wert, beschriftung]) => wert !== '' && beschriftung !== '' && wert !== beschriftung,
        ),
    );
    const definition: Record<string, unknown> = {};
    if (titel !== '' && titel !== name) {
      definition['title'] = titel;
    }
    if (beschreibung !== '') {
      definition['description'] = beschreibung;
    }
    switch (feld.typ) {
      case 'datum':
        definition['type'] = 'string';
        definition['format'] = 'date';
        break;
      case 'ganzzahl':
        definition['type'] = 'integer';
        break;
      case 'zahl':
        definition['type'] = 'number';
        break;
      case 'janein':
        definition['type'] = 'boolean';
        break;
      case 'auswahl':
        definition['type'] = 'string';
        definition['enum'] = optionen;
        if (Object.keys(labels).length > 0) {
          definition['x-labels'] = labels;
        }
        break;
      case 'mehrfach': {
        const items: Record<string, unknown> = { type: 'string', enum: optionen };
        if (Object.keys(labels).length > 0) {
          items['x-labels'] = labels;
        }
        definition['type'] = 'array';
        definition['items'] = items;
        break;
      }
      case 'liste':
        definition['type'] = 'array';
        definition['items'] = { type: 'string' };
        break;
      default:
        definition['type'] = 'string';
        break;
    }
    if (feld.typ === 'liste' || feld.typ === 'mehrfach') {
      if (feld.minEintraege !== undefined) {
        definition['minItems'] = feld.minEintraege;
      }
      if (feld.maxEintraege !== undefined) {
        definition['maxItems'] = feld.maxEintraege;
      }
      if (feld.eindeutig) {
        definition['uniqueItems'] = true;
      }
    }
    properties[name] = definition;
  }
  const schema: Record<string, unknown> = {
    ...vorher,
    type: 'object',
    properties,
  };
  delete schema['required'];
  const required = felder.filter((feld) => feld.pflicht).map((feld) => feld.code.trim());
  if (required.length > 0) {
    schema['required'] = required;
  }
  return schema;
}

export function feldEditorFehler(
  felder: readonly FeldEntwurf[],
): 'code' | 'optionen' | 'liste' | null {
  const codes = felder.map((feld) => feld.code.trim());
  if (
    codes.some((code) => !/^[a-z][a-z0-9_]{0,39}$/.test(code)) ||
    new Set(codes).size !== codes.length
  ) {
    return 'code';
  }
  for (const feld of felder) {
    if (feld.typ === 'auswahl' || feld.typ === 'mehrfach') {
      const werte = feld.optionen.map((option) => option.wert.trim());
      if (
        werte.length === 0 ||
        werte.some((wert) => wert === '') ||
        new Set(werte).size !== werte.length
      ) {
        return 'optionen';
      }
    }
    if (
      (feld.typ === 'liste' || feld.typ === 'mehrfach') &&
      feld.minEintraege !== undefined &&
      feld.maxEintraege !== undefined &&
      feld.minEintraege > feld.maxEintraege
    ) {
      return 'liste';
    }
  }
  return null;
}

function sensiblerPfad(pfad: string): boolean {
  return /(^|[./_-])(kennungen|iban|uid)([./_-]|$)/i.test(pfad);
}

function wertText(wert: unknown): string {
  if (wert === undefined) {
    return '—';
  }
  if (typeof wert === 'string') {
    return wert;
  }
  return JSON.stringify(wert) ?? String(wert);
}

export function versionsvergleich(alt: unknown, neu: unknown): Versionsaenderung[] {
  const aenderungen: Versionsaenderung[] = [];
  const vergleichen = (links: unknown, rechts: unknown, pfad: string): void => {
    if (Object.is(links, rechts)) {
      return;
    }
    const linksObjekt = objekt(links);
    const rechtsObjekt = objekt(rechts);
    if (linksObjekt !== null && rechtsObjekt !== null) {
      for (const schluessel of new Set([
        ...Object.keys(linksObjekt),
        ...Object.keys(rechtsObjekt),
      ])) {
        vergleichen(linksObjekt[schluessel], rechtsObjekt[schluessel], `${pfad}/${schluessel}`);
      }
      return;
    }
    const verdeckt = sensiblerPfad(pfad);
    aenderungen.push({
      pfad: pfad || '/',
      alt: verdeckt ? '' : wertText(links),
      neu: verdeckt ? '' : wertText(rechts),
      werteVerdeckt: verdeckt,
    });
  };
  vergleichen(alt, neu, '');
  return aenderungen;
}

export function verwaltungsFehlerart(
  fehler: unknown,
): 'konflikt' | 'gebrauch' | 'dateiVeraltet' | 'ungueltig' | null {
  if (!(fehler instanceof HttpErrorResponse)) {
    return null;
  }
  if (fehler.status === 412) {
    return 'konflikt';
  }
  const code = objekt(fehler.error)?.['fehlercode'];
  if (fehler.status === 409 && code === 'KONFIGURATION_VERALTET') {
    return 'dateiVeraltet';
  }
  if (fehler.status === 409 && code === 'KONFIGURATION_IN_GEBRAUCH') {
    return 'gebrauch';
  }
  return fehler.status === 422 ? 'ungueltig' : null;
}

export function allgemeineFehlermeldung(fehler: unknown): string {
  return httpFehlerMeldung(fehler);
}
