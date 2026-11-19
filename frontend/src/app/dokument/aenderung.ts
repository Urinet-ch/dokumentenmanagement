import { Dokument, DokumentAenderung, Dokumentart } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { KlassifikationFeld } from './klassifikationen';

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
  klassifikationen: Readonly<Record<string, readonly string[]>>;
  grund: string;
}

export type TextFeld = Exclude<keyof Formular, 'zusatz' | 'klassifikationen'>;

/** `mehrfach`: Liste aus festen Werten (`items.enum`), `liste`: Liste freier Texte (`items.type` string). */
export type ZusatzTyp =
  'text' | 'datum' | 'zahl' | 'ganzzahl' | 'janein' | 'auswahl' | 'mehrfach' | 'liste';

/** Eingabefeld für einen Eintrag unter `properties` des Zusatz-Schemas der Art. */
export interface ZusatzFeld {
  name: string;
  titel?: string;
  beschreibung?: string;
  typ: ZusatzTyp;
  optionen: readonly string[];
  /** Optionale Anzeigetexte je Enum-Wert (`x-labels` im Schema); fehlt ein Wert, gilt der Rohwert. */
  beschriftungen?: Readonly<Record<string, string>>;
  pflicht: boolean;
  /** Nur Listen: `minItems`, `maxItems` und `uniqueItems` des Schemas. */
  minEintraege?: number;
  maxEintraege?: number;
  eindeutig?: boolean;
}

/** Listenwerte stehen im Formular als ein Text, ein Eintrag je Zeile (wie die übrigen Zusatzwerte als Text). */
export const istListe = (feld: ZusatzFeld): boolean =>
  feld.typ === 'mehrfach' || feld.typ === 'liste';

export function listeAus(wert: string): string[] {
  return wert
    .split('\n')
    .map((e) => e.trim())
    .filter((e) => e !== '');
}

export const listeZu = (eintraege: readonly string[]): string => eintraege.join('\n');

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
  return wert && typeof wert === 'object' && !Array.isArray(wert)
    ? (wert as Record<string, unknown>)
    : {};
}

function stringListe(wert: unknown): wert is string[] {
  return Array.isArray(wert) && wert.every((e) => typeof e === 'string');
}

export function formularAus(d: Dokument): Formular {
  const f = d.finanz ?? null;
  const v = d.vertrag ?? null;
  const zusatz = Object.fromEntries(
    Object.entries(objekt(d.zusatz))
      .filter(([, wert]) => typeof wert !== 'object' || wert === null || stringListe(wert))
      .map(([name, wert]) => [name, stringListe(wert) ? listeZu(wert) : text(wert)]),
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
    klassifikationen: Object.fromEntries(
      Object.entries(d.klassifikationen ?? {}).map(([code, werte]) => [code, [...werte]]),
    ),
    grund: '',
  };
}

const BESCHRIFTUNG_MAX = 80;

/**
 * Liest `x-labels` (Eigenschaft oder `items`) als Anzeigetexte je Wert. Nur Texte, gekürzt auf {@link BESCHRIFTUNG_MAX};
 * Schlüssel ausserhalb der Optionen und Nicht-Texte werden ignoriert. Angezeigt wird nur als Text, nie als HTML.
 */
function beschriftungenAus(
  e: Record<string, unknown>,
  optionen: readonly string[],
): Record<string, string> | undefined {
  const roh = { ...objekt(objekt(e['items'])['x-labels']), ...objekt(e['x-labels']) };
  const ergebnis: Record<string, string> = {};
  for (const option of optionen) {
    const text = Object.hasOwn(roh, option) ? roh[option] : undefined;
    if (typeof text === 'string' && text.trim() !== '') {
      ergebnis[option] = text.trim().substring(0, BESCHRIFTUNG_MAX);
    }
  }
  return Object.keys(ergebnis).length > 0 ? ergebnis : undefined;
}

/** Anzeigetext eines gespeicherten Werts: die Beschriftung aus dem Schema, sonst der Rohwert. */
export function anzeigeText(
  feld: Pick<ZusatzFeld, 'beschriftungen'> | undefined,
  wert: string,
): string {
  const b = feld?.beschriftungen;
  return b && Object.hasOwn(b, wert) ? b[wert] : wert;
}

function typAus(schema: Record<string, unknown>): ZusatzTyp | null {
  if (schema['type'] === 'array') {
    const items = objekt(schema['items']);
    if (Array.isArray(items['enum']) && items['enum'].every((w) => typeof w === 'string')) {
      return 'mehrfach';
    }
    return items['type'] === 'string' ? 'liste' : null;
  }
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
  // Pflicht ist, was in `pflichtfelder` als «zusatz.<name>» steht oder im Schema unter `required` (so im Seed vhf-gsk).
  const required = Array.isArray(schema['required'])
    ? schema['required'].filter((n) => typeof n === 'string')
    : [];
  const pflicht = new Set([
    ...(art?.pflichtfelder ?? [])
      .filter((p) => p.startsWith('zusatz.'))
      .map((p) => p.substring('zusatz.'.length)),
    ...required,
  ]);
  return Object.entries(objekt(schema['properties'])).flatMap(([name, eigenschaft]) => {
    const e = objekt(eigenschaft);
    const typ = typAus(e);
    if (!typ) {
      return [];
    }
    const optionen =
      typ === 'auswahl'
        ? (e['enum'] as string[])
        : typ === 'mehrfach'
          ? (objekt(e['items'])['enum'] as string[])
          : [];
    const feld: ZusatzFeld = { name, typ, optionen, pflicht: pflicht.has(name) };
    if (typeof e['title'] === 'string') {
      feld.titel = e['title'];
    }
    if (typeof e['description'] === 'string') {
      feld.beschreibung = e['description'];
    }
    const beschriftungen = beschriftungenAus(e, optionen);
    if (beschriftungen) {
      feld.beschriftungen = beschriftungen;
    }
    if (typ === 'mehrfach' || typ === 'liste') {
      feld.minEintraege = typeof e['minItems'] === 'number' ? e['minItems'] : 0;
      feld.eindeutig = e['uniqueItems'] === true;
      if (typeof e['maxItems'] === 'number') {
        feld.maxEintraege = e['maxItems'];
      }
    }
    return [feld];
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

function gleicheKlassifikationen(
  links: Readonly<Record<string, readonly string[]>>,
  rechts: Readonly<Record<string, readonly string[]>>,
): boolean {
  const codes = new Set([...Object.keys(links), ...Object.keys(rechts)]);
  return [...codes].every((code) => {
    const a = links[code] ?? [];
    const b = rechts[code] ?? [];
    return a.length === b.length && a.every((wert, index) => wert === b[index]);
  });
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
    case 'mehrfach':
    case 'liste':
      return listeAus(wert);
    default:
      return roh;
  }
}

/** `leerOk`: eine leere Pflichtliste ist erlaubt (Altbestand, das Archiv prüft Pflichtfelder beim Bestätigen). */
export function zusatzGueltig(feld: ZusatzFeld, wert: string, leerOk = false): boolean {
  if (istListe(feld)) {
    const eintraege = listeAus(wert);
    if (eintraege.length === 0) {
      return !feld.pflicht || leerOk;
    }
    return (
      eintraege.length >= (feld.minEintraege ?? 0) &&
      eintraege.length <= (feld.maxEintraege ?? Infinity) &&
      (!feld.eindeutig || new Set(eintraege).size === eintraege.length) &&
      (feld.typ !== 'mehrfach' || eintraege.every((e) => feld.optionen.includes(e)))
    );
  }
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
  klassifikationen?: readonly KlassifikationFeld[];
}

export type Ergebnis = { aenderung: DokumentAenderung } | { pruefung: Pruefung };

/**
 * Body für den PATCH oder eine Prüfmeldung. Finanz- und Vertragsfelder gehen nur mit, wenn die (gewählte) Art die
 * Gruppe hat (sonst 400); `zusatz` ersetzt alle Zusatzwerte und geht darum ganz mit, sobald sich einer ändert.
 */
export function aenderungAus(
  dokument: Dokument,
  start: Formular,
  neu: Formular,
  kontext: Kontext,
): Ergebnis {
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
  const zusatzGeaendert = zusatz.filter(
    (f) => (neu.zusatz[f.name] ?? '').trim() !== (start.zusatz[f.name] ?? '').trim(),
  );
  const artGewechselt = aenderung['art'] !== undefined;
  const klassifikationenGeaendert = !gleicheKlassifikationen(
    start.klassifikationen,
    neu.klassifikationen,
  );
  if (Object.keys(aenderung).length > 0 || klassifikationenGeaendert || artGewechselt) {
    for (const feld of kontext.klassifikationen ?? []) {
      const werte =
        neu.klassifikationen[feld.code] ??
        (artGewechselt && feld.standardWert ? [feld.standardWert] : []);
      if (feld.pflicht && werte.length === 0) {
        felder.push(`klassifikationen.${feld.code}`);
      }
    }
  }
  if (zusatzGeaendert.length > 0 || artGewechselt) {
    // Altbestand: Eine Pflichtliste, die schon vorher leer war, bleibt erlaubt (wie im Archiv, DokumentAendern).
    const ungueltig = zusatz.filter(
      (f) =>
        !zusatzGueltig(
          f,
          neu.zusatz[f.name] ?? '',
          !artGewechselt && (start.zusatz[f.name] ?? '').trim() === '',
        ),
    );
    if (ungueltig.length > 0) {
      felder.push(...ungueltig.map((f) => `zusatz.${f.name}`));
    } else {
      // Bei einem Artwechsel nur Schlüssel der neuen Art behalten (additionalProperties false im Schema).
      const bekannt = new Set(Object.keys(objekt(objekt(kontext.art?.zusatzSchema)['properties'])));
      const werte: Record<string, unknown> = artGewechselt
        ? Object.fromEntries(
            Object.entries(objekt(dokument.zusatz)).filter(([k]) => bekannt.has(k)),
          )
        : { ...objekt(dokument.zusatz) };
      for (const f of zusatz) {
        const wert = neu.zusatz[f.name] ?? '';
        // Unveränderte Listen bleiben im Originalwert (kein stilles Trimmen oder Aufteilen von Bestandsdaten).
        if (
          istListe(f) &&
          wert.trim() === (start.zusatz[f.name] ?? '').trim() &&
          werte[f.name] !== undefined
        ) {
          continue;
        }
        if (wert.trim() === '') {
          delete werte[f.name];
        } else {
          werte[f.name] = zusatzWert(f, wert);
        }
      }
      aenderung['zusatz'] = werte;
    }
  }
  if (klassifikationenGeaendert || artGewechselt) {
    const klassifikationen: Record<string, string[]> = {};
    for (const feld of kontext.klassifikationen ?? []) {
      const werte = neu.klassifikationen[feld.code] ?? [];
      const vorbelegt =
        artGewechselt &&
        !Object.hasOwn(neu.klassifikationen, feld.code) &&
        werte.length === 0 &&
        feld.standardWert
          ? [feld.standardWert]
          : [...werte];
      if (vorbelegt.length > 0) {
        klassifikationen[feld.code] = vorbelegt;
      }
    }
    aenderung['klassifikationen'] = klassifikationen;
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
  if (feld.startsWith('klassifikationen.')) {
    return TEXTE.eingangskorb.klassifikationFeld(feld.substring('klassifikationen.'.length));
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
