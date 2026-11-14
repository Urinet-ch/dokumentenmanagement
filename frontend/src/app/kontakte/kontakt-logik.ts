import { HttpErrorResponse } from '@angular/common/http';
import { ParamMap, Params } from '@angular/router';
import { Kontakt, KontaktAngaben, KontaktDetail } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { KontaktQuelle, KontaktStatus } from './kontakte-port';

export const LISTE_GROESSE = 25;
export const DOKUMENTE_GROESSE = 20;
export const SEITE_MAX = 100000;
export const Q_MAX = 200;
export const GRUND_MAX = 1000;
export const ZIEL_MIN_ZEICHEN = 2;

// eslint-disable-next-line no-control-regex
const STEUERZEICHEN = /[\u0000-\u001f\u007f-\u009f]/;

/** Suchbegriff, wie ihn `GET /kontakte/liste` annimmt: 1–200 Zeichen ohne Steuerzeichen (Req 17.2). */
export function suchbegriffGueltig(q: string): boolean {
  const t = q.trim();
  return t.length >= 1 && t.length <= Q_MAX && !STEUERZEICHEN.test(t);
}

/** Zustand der Liste, der in der Adresse steht (Req 17.2): Suchbegriff, Filter und Seite. */
export interface ListenFilter {
  readonly q: string;
  readonly status: KontaktStatus;
  readonly quelle: KontaktQuelle | '';
  readonly nichtImExport: boolean;
  readonly seite: number;
}

export const LEERER_FILTER: ListenFilter = {
  q: '',
  status: 'AKTIV',
  quelle: '',
  nichtImExport: false,
  seite: 0,
};

/** Liest den Zustand aus den Query-Parametern; Ungültiges fällt auf den Standard zurück. */
export function filterAusParametern(p: ParamMap): ListenFilter {
  const status = p.get('status');
  const quelle = p.get('quelle');
  const seite = Number(p.get('seite') ?? 0);
  return {
    q: p.get('q') ?? '',
    status: status === 'INAKTIV' || status === 'ALLE' ? status : 'AKTIV',
    quelle: quelle === 'CSV_IMPORT' || quelle === 'MANUELL' ? quelle : '',
    nichtImExport: p.get('nichtImExport') === 'true',
    seite: Number.isInteger(seite) && seite >= 0 && seite <= SEITE_MAX ? seite : 0,
  };
}

/** Query-Parameter für den Zustand; Standardwerte bleiben weg, damit die Adresse kurz bleibt. */
export function parameterAusFilter(f: ListenFilter): Params {
  return {
    q: f.q.trim() || null,
    status: f.status === 'AKTIV' ? null : f.status,
    quelle: f.quelle || null,
    nichtImExport: f.nichtImExport ? 'true' : null,
    seite: f.seite > 0 ? f.seite : null,
  };
}

/** Anzahl gesetzter Filter ohne Suchbegriff (Status «aktiv» ist der Standard). */
export function anzahlFilter(f: ListenFilter): number {
  return (f.status !== 'AKTIV' ? 1 : 0) + (f.quelle ? 1 : 0) + (f.nichtImExport ? 1 : 0);
}

export function seitenAnzahl(gesamt: number, groesse: number): number {
  return Math.max(1, Math.ceil(gesamt / Math.max(1, groesse)));
}

/** «Vorname Name» für Überschriften, Rückfragen und Attribute; Namen bleiben reiner Text. */
export function kontaktName(k: { name?: string | null; vorname?: string | null }): string {
  return [k.vorname, k.name].filter(Boolean).join(' ');
}

export function ortZeile(k: { plz?: string | null; ort?: string | null }): string {
  return [k.plz, k.ort].filter(Boolean).join(' ');
}

/** Name mit Ort für die Zielwahl. */
export function kontaktAuswahlText(k: Kontakt): string {
  return [kontaktName(k), ortZeile(k)].filter(Boolean).join(', ');
}

// ---------------------------------------------------------------- Formular «Bearbeiten»

export type Feld =
  | 'name'
  | 'vorname'
  | 'strasse'
  | 'plz'
  | 'ort'
  | 'email'
  | 'telefon'
  | 'uid'
  | 'iban'
  | 'kategorie';
export const FELDER: readonly Feld[] = [
  'name',
  'vorname',
  'strasse',
  'plz',
  'ort',
  'email',
  'telefon',
  'uid',
  'iban',
  'kategorie',
];

/** Höchstlängen aus OpenAPI (Zeichen ohne Leerraum am Rand), wie das Archiv sie prüft. */
export const MAX: Record<Feld, number> = {
  name: 200,
  vorname: 200,
  strasse: 200,
  plz: 20,
  ort: 200,
  email: 200,
  telefon: 50,
  uid: 30,
  iban: 40,
  kategorie: 100,
};

export type Typ = 'PERSON' | 'ORGANISATION';
export interface FormularWerte extends Record<Feld, string> {
  typ: Typ | '';
}

export function formularAusDetail(d: KontaktDetail): FormularWerte {
  return {
    typ: d.typ ?? '',
    name: d.name ?? '',
    vorname: d.vorname ?? '',
    strasse: d.strasse ?? '',
    plz: d.plz ?? '',
    ort: d.ort ?? '',
    email: d.email ?? '',
    telefon: d.telefon ?? '',
    uid: d.uid ?? '',
    iban: d.iban ?? '',
    kategorie: d.kategorie ?? '',
  };
}

export type Pruefung = 'name' | 'typ' | 'zuLang';
export type Fehlerliste = Partial<Record<Feld | 'typ', Pruefung>>;

/** Dieselben Prüfungen wie das Archiv: Typ und Name Pflicht, Höchstlängen ohne Leerraum am Rand (Req 17.6). */
export function pruefen(w: FormularWerte): Fehlerliste {
  const fehler: Fehlerliste = {};
  if (w.typ === '') {
    fehler.typ = 'typ';
  }
  if (w.name.trim() === '') {
    fehler.name = 'name';
  }
  for (const feld of FELDER) {
    if (!fehler[feld] && w[feld].trim().length > MAX[feld]) {
      fehler[feld] = 'zuLang';
    }
  }
  return fehler;
}

/** Nur geänderte Felder; ein geleertes Feld geht als leerer Text (das Archiv leert es), `null` liesse es unverändert. */
export function aenderungen(vorher: FormularWerte, nachher: FormularWerte): KontaktAngaben {
  const aus: Record<string, string> = {};
  for (const feld of FELDER) {
    if (nachher[feld].trim() !== vorher[feld].trim()) {
      aus[feld] = nachher[feld].trim();
    }
  }
  if (nachher.typ !== vorher.typ && nachher.typ !== '') {
    aus['typ'] = nachher.typ;
  }
  return aus as KontaktAngaben;
}

// ---------------------------------------------------------------- Fehler

export type FehlerArt =
  | 'verbindung'
  | 'sitzung'
  | 'verboten'
  | 'nichtGefunden'
  | 'veraltet'
  | 'versionFehlt'
  | 'konflikt'
  | 'anfrage'
  | 'sonst';

export function fehlerArt(fehler: unknown): FehlerArt {
  const status = fehler instanceof HttpErrorResponse ? fehler.status : -1;
  switch (status) {
    case 0:
      return 'verbindung';
    case 401:
      return 'sitzung';
    case 403:
      return 'verboten';
    case 404:
      return 'nichtGefunden';
    case 412:
      return 'veraltet';
    case 428:
      return 'versionFehlt';
    case 409:
      return 'konflikt';
    case 400:
    case 422:
      return 'anfrage';
    default:
      return 'sonst';
  }
}

/** Meldung zu einem Fehler beim Lesen (Liste, Detail, Dokumente). */
export function ladenFehlerText(fehler: unknown): string {
  const t = TEXTE.kontakte;
  switch (fehlerArt(fehler)) {
    case 'verbindung':
      return t.fehlerVerbindung;
    case 'sitzung':
      return t.fehlerSitzung;
    case 'verboten':
      return t.fehlerVerboten;
    case 'anfrage':
      return t.fehlerAnfrage;
    default:
      return t.fehlerAllgemein;
  }
}

/** Meldung zu einem Fehler bei einer Aktion; `veraltet` und `versionFehlt` behandelt der Aufrufer (Detail neu laden). */
export function aktionFehlerText(
  fehler: unknown,
  aktion: 'aendern' | 'zusammenfuehren' | 'inaktiv' | 'aktiv',
): string {
  const t = TEXTE.kontakte;
  switch (fehlerArt(fehler)) {
    case 'konflikt':
      return t.konflikt[aktion];
    case 'anfrage':
      return t.anfrage[aktion];
    case 'nichtGefunden':
      return t.nichtGefunden;
    default:
      return ladenFehlerText(fehler);
  }
}
