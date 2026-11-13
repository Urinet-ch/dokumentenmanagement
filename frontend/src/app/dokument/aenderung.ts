import { Dokument, DokumentAenderung, Dokumentart } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';

/**
 * Formular «Metadaten bearbeiten» (Req 10.1) und der Body für `PATCH /dokumente/{id}` (design.md «Umsetzung 3.5»):
 * Merge-Semantik, gesendet werden nur geänderte Felder, ein geleertes Feld als `null`.
 */
export interface Formular {
  art: string;
  titel: string;
  datum: string;
  partnerId: string;
  partnerName: string;
  projekt: string;
  tags: string;
  vertraulichkeit: string;
  geschaeftsjahr: string;
  belegnummer: string;
  richtung: string;
  betrag: string;
  waehrung: string;
  faelligkeit: string;
  qrReferenz: string;
  konto: string;
  gueltigAb: string;
  gueltigBis: string;
  kuendigungsfrist: string;
  naechsteFrist: string;
  zusatz: Readonly<Record<string, string>>;
  grund: string;
}

export type TextFeld = Exclude<keyof Formular, 'zusatz'>;

export type ZusatzTyp = 'text' | 'datum' | 'zahl' | 'ganzzahl' | 'janein' | 'auswahl';

/** Eingabefeld für einen Eintrag unter `properties` des Zusatz-Schemas der Art. */
export interface ZusatzFeld {
  name: string;
  typ: ZusatzTyp;
  optionen: readonly string[];
  pflicht: boolean;
}

export interface Pruefung {
  meldung: string;
  felder: readonly string[];
}

export const GRUND_MAX = 1000;

export const FINANZ_FELDER = [
  'belegnummer',
  'richtung',
  'betrag',
  'waehrung',
  'faelligkeit',
  'qrReferenz',
  'konto',
] as const satisfies readonly TextFeld[];

export const VERTRAG_FELDER = [
  'gueltigAb',
  'gueltigBis',
  'kuendigungsfrist',
  'naechsteFrist',
] as const satisfies readonly TextFeld[];

const BETRAG = /^-?\d{1,10}([.,]\d{1,2})?$/;
const GANZZAHL = /^-?\d{1,15}$/;
const ZAHL = /^-?\d{1,15}([.,]\d+)?$/;
const WAEHRUNG = /^[A-Za-z]{3}$/;
const JAHR = /^\d{4}$/;

const T = TEXTE.dokument.bearbeiten;

function text(wert: unknown): string {
  if (typeof wert === 'string') {
    return wert;
  }
  if (typeof wert === 'number' && Number.isFinite(wert)) {
    return String(wert);
  }
  return typeof wert === 'boolean' ? String(wert) : '';
}

function objekt(wert: unknown): Record<string, unknown> {
  return wert && typeof wert === 'object' && !Array.isArray(wert) ? (wert as Record<string, unknown>) : {};
}

export function formularAus(d: Dokument): Formular {
  const f = d.finanz ?? null;
  const v = d.vertrag ?? null;
  const zusatz = Object.fromEntries(
    Object.entries(objekt(d.zusatz))
      .filter(([, wert]) => typeof wert !== 'object' || wert === null)
      .map(([name, wert]) => [name, text(wert)]),
  );
  return {
    art: d.art ?? '',
    titel: d.titel ?? '',
    datum: d.datum ?? '',
    partnerId: d.partnerId ?? '',
    partnerName: d.partnerName ?? '',
    projekt: d.projekt ?? '',
    tags: d.tags.join(', '),
    vertraulichkeit: d.vertraulichkeit,
    geschaeftsjahr: text(d.geschaeftsjahr),
    belegnummer: f?.belegnummer ?? '',
    richtung: f?.richtung ?? '',
    betrag: f?.betrag === null || f?.betrag === undefined ? '' : f.betrag.toFixed(2),
    waehrung: f?.waehrung ?? '',
    faelligkeit: f?.faelligkeit ?? '',
    qrReferenz: f?.qrReferenz ?? '',
    konto: f?.konto ?? '',
    gueltigAb: v?.gueltigAb ?? '',
    gueltigBis: v?.gueltigBis ?? '',
    kuendigungsfrist: v?.kuendigungsfrist ?? '',
    naechsteFrist: v?.naechsteFrist ?? '',
    zusatz,
    grund: '',
  };
}

function typAus(schema: Record<string, unknown>): ZusatzTyp | null {
  if (Array.isArray(schema['enum']) && schema['enum'].every((w) => typeof w === 'string')) {
    return 'auswahl';
  }
  const roh = schema['type'];
  const typ = Array.isArray(roh) ? roh.find((t) => t !== 'null') : roh;
  switch (typ) {
    case 'string':
      return schema['format'] === 'date' ? 'datum' : 'text';
    case 'number':
      return 'zahl';
    case 'integer':
      return 'ganzzahl';
    case 'boolean':
      return 'janein';
    default:
      return null;
  }
}

/**
 * Eingabefelder aus `properties` des Zusatz-Schemas (JSON Schema 2020-12). Verschachtelte Werte (Objekte, Listen)
 * lassen sich hier nicht bearbeiten und bleiben unverändert; die volle Prüfung macht das Archiv (422 ZUSATZ_UNGUELTIG).
 */
export function zusatzFelder(art: Dokumentart | undefined): ZusatzFeld[] {
  const schema = objekt(art?.zusatzSchema);
  const pflicht = new Set(
    (art?.pflichtfelder ?? []).filter((p) => p.startsWith('zusatz.')).map((p) => p.substring('zusatz.'.length)),
  );
  return Object.entries(objekt(schema['properties'])).flatMap(([name, eigenschaft]) => {
    const e = objekt(eigenschaft);
    const typ = typAus(e);
    if (!typ) {
      return [];
    }
    const optionen = typ === 'auswahl' ? (e['enum'] as string[]) : [];
    return [{ name, typ, optionen, pflicht: pflicht.has(name) }];
  });
}

const leerZuNull = (wert: string) => (wert.trim() === '' ? null : wert.trim());

function tagsAus(wert: string): string[] {
  return wert
    .split(',')
    .map((t) => t.trim())
    .filter((t) => t !== '');
}

function betragLesen(wert: string): number | null {
  const roh = wert.trim().replace(/'/g, '');
  return roh === '' ? null : Number(roh.replace(',', '.'));
}

function zusatzWert(feld: ZusatzFeld, wert: string): unknown {
  const roh = wert.trim();
  switch (feld.typ) {
    case 'zahl':
      return Number(roh.replace(/'/g, '').replace(',', '.'));
    case 'ganzzahl':
      return Number.parseInt(roh.replace(/'/g, ''), 10);
    case 'janein':
      return roh === 'true';
    default:
      return roh;
  }
}

function zusatzGueltig(feld: ZusatzFeld, wert: string): boolean {
  const roh = wert.trim().replace(/'/g, '');
  if (roh === '') {
    return true;
  }
  switch (feld.typ) {
    case 'zahl':
      return ZAHL.test(roh);
    case 'ganzzahl':
      return GANZZAHL.test(roh);
    case 'janein':
      return roh === 'true' || roh === 'false';
    case 'auswahl':
      return feld.optionen.includes(wert.trim());
    default:
      return true;
  }
}

export interface Kontext {
  art: Dokumentart | undefined;
  /** Recht A8: nur dann geht die Vertraulichkeit mit. */
  darfVertraulichkeit: boolean;
}

export type Ergebnis = { aenderung: DokumentAenderung } | { pruefung: Pruefung };

/**
 * Body für den PATCH oder eine Prüfmeldung. Finanz- und Vertragsfelder gehen nur mit, wenn die (gewählte) Art die
 * Gruppe hat (sonst 400); `zusatz` ersetzt alle Zusatzwerte und geht darum ganz mit, sobald sich einer ändert.
 */
export function aenderungAus(dokument: Dokument, start: Formular, neu: Formular, kontext: Kontext): Ergebnis {
  const archiviert = dokument.status === 'ARCHIVIERT';
  const gruppen = kontext.art?.gruppen ?? [];
  const felder: string[] = [];
  const aenderung: Record<string, unknown> = {};
  const geaendert = (feld: TextFeld) => neu[feld].trim() !== start[feld].trim();

  if (geaendert('art')) {
    if (!neu.art) {
      return { pruefung: { meldung: T.artWaehlen, felder: ['art'] } };
    }
    aenderung['art'] = neu.art;
  }
  for (const feld of ['titel', 'datum', 'projekt', 'partnerId'] as const) {
    if (geaendert(feld)) {
      aenderung[feld] = leerZuNull(neu[feld]);
    }
  }
  const tags = tagsAus(neu.tags);
  if (tags.join('\n') !== tagsAus(start.tags).join('\n')) {
    aenderung['tags'] = tags;
  }
  if (kontext.darfVertraulichkeit && neu.vertraulichkeit && geaendert('vertraulichkeit')) {
    aenderung['vertraulichkeit'] = neu.vertraulichkeit;
  }
  if (geaendert('geschaeftsjahr')) {
    const jahr = neu.geschaeftsjahr.trim();
    if ((jahr === '' && archiviert) || (jahr !== '' && !JAHR.test(jahr))) {
      felder.push('geschaeftsjahr');
    } else {
      aenderung['geschaeftsjahr'] = jahr === '' ? null : Number(jahr);
    }
  }
  if (gruppen.includes('finanz')) {
    for (const feld of FINANZ_FELDER) {
      if (!geaendert(feld)) {
        continue;
      }
      if (feld === 'betrag') {
        if (!BETRAG.test(neu.betrag.trim().replace(/'/g, '')) && neu.betrag.trim() !== '') {
          felder.push('betrag');
        } else {
          aenderung['betrag'] = betragLesen(neu.betrag);
        }
      } else if (feld === 'waehrung') {
        if (!WAEHRUNG.test(neu.waehrung.trim())) {
          felder.push('waehrung');
        } else {
          aenderung['waehrung'] = neu.waehrung.trim().toUpperCase();
        }
      } else {
        aenderung[feld] = leerZuNull(neu[feld]);
      }
    }
  }
  if (gruppen.includes('vertrag')) {
    for (const feld of VERTRAG_FELDER) {
      if (geaendert(feld)) {
        aenderung[feld] = leerZuNull(neu[feld]);
      }
    }
  }
  const zusatz = zusatzFelder(kontext.art);
  const zusatzGeaendert = zusatz.filter((f) => (neu.zusatz[f.name] ?? '').trim() !== (start.zusatz[f.name] ?? '').trim());
  const artGewechselt = aenderung['art'] !== undefined;
  if (zusatzGeaendert.length > 0 || artGewechselt) {
    const ungueltig = zusatz.filter((f) => !zusatzGueltig(f, neu.zusatz[f.name] ?? ''));
    if (ungueltig.length > 0) {
      felder.push(...ungueltig.map((f) => `zusatz.${f.name}`));
    } else {
      // Bei einem Artwechsel nur Schlüssel der neuen Art behalten (additionalProperties false im Schema).
      const bekannt = new Set(Object.keys(objekt(objekt(kontext.art?.zusatzSchema)['properties'])));
      const werte: Record<string, unknown> = artGewechselt
        ? Object.fromEntries(Object.entries(objekt(dokument.zusatz)).filter(([k]) => bekannt.has(k)))
        : { ...objekt(dokument.zusatz) };
      for (const f of zusatz) {
        const wert = neu.zusatz[f.name] ?? '';
        if (wert.trim() === '') {
          delete werte[f.name];
        } else {
          werte[f.name] = zusatzWert(f, wert);
        }
      }
      aenderung['zusatz'] = werte;
    }
  }
  if (felder.length > 0) {
    return { pruefung: { meldung: T.ungueltig(felder.map(feldText).join(', ')), felder } };
  }
  if (Object.keys(aenderung).length === 0) {
    return { pruefung: { meldung: T.keineAenderung, felder: [] } };
  }
  const grund = neu.grund.trim();
  if (grund.length > GRUND_MAX) {
    return { pruefung: { meldung: T.grundZuLang, felder: ['grund'] } };
  }
  if (archiviert && grund === '') {
    return { pruefung: { meldung: T.grundFehlt, felder: ['grund'] } };
  }
  if (grund !== '') {
    aenderung['grund'] = grund;
  }
  return { aenderung: aenderung as DokumentAenderung };
}

/** Anzeigename eines Felds, auch für `felder` aus den Problem Details (`snake_case`, `zusatz.<name>`). */
export function feldText(feld: string): string {
  if (feld.startsWith('zusatz.')) {
    return TEXTE.eingangskorb.zusatzFeld(feld.substring('zusatz.'.length));
  }
  const camel = feld.replace(/_([a-z])/g, (_, b: string) => b.toUpperCase());
  return T.feld[camel] ?? TEXTE.eingangskorb.feld[camel] ?? feld;
}

/** Feldname im Formular zu einem Eintrag aus `felder` der Problem Details. */
export function formularFeld(feld: string): string {
  if (feld.startsWith('zusatz.')) {
    return feld;
  }
  const camel = feld.replace(/_([a-z])/g, (_, b: string) => b.toUpperCase());
  return camel === 'partner' ? 'partnerId' : camel;
}
