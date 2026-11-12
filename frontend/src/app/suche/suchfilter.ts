import { Suchanfrage, Richtung, Sortierung, Status } from './suche-port';

// Eingaben der Filterleiste als Text, wie sie im Formular stehen; `pruefen` macht daraus eine Suchanfrage nach den
// Grenzen des Archivs (design.md «Suche», Umsetzung 4.5), damit eine Tippfehler-Anfrage nicht erst als 400 zurückkommt.

export interface Filterwerte {
  q: string;
  art: string;
  partnerId: string;
  partnerName: string;
  datumVon: string;
  datumBis: string;
  betragVon: string;
  betragBis: string;
  richtung: '' | Richtung;
  status: '' | Status;
  tags: string;
  geschaeftsjahr: string;
  belegnummer: string;
  sortierung: Sortierung;
}

export const LEERE_FILTER: Filterwerte = {
  q: '',
  art: '',
  partnerId: '',
  partnerName: '',
  datumVon: '',
  datumBis: '',
  betragVon: '',
  betragBis: '',
  richtung: '',
  status: '',
  tags: '',
  geschaeftsjahr: '',
  belegnummer: '',
  sortierung: 'RELEVANZ',
};

export const GROESSE = 20;
export const SEITE_MAX = 9_999;
const BEGRIFF_MAX = 200;
const TAGS_MAX = 10;
const TAG_MAX = 100;
const BELEGNUMMER_MAX = 50;
const DATUM = /^\d{4}-\d{2}-\d{2}$/;
// NUMERIC(12,2): höchstens zehn Stellen vor dem Komma; Apostroph als Tausendertrennzeichen und Komma sind erlaubt
const BETRAG = /^-?\d{1,10}(\.\d{1,2})?$/;
// eslint-disable-next-line no-control-regex
const STEUERZEICHEN = /[\u0000-\u001f\u007f-\u009f]/;

export type Pruefung = 'begriff' | 'datum' | 'betrag' | 'tags' | 'geschaeftsjahr' | 'belegnummer';

export type Ergebnis = { anfrage: Suchanfrage; fehler?: undefined } | { anfrage?: undefined; fehler: Pruefung[] };

/** Anzahl gesetzter Filter ohne Suchbegriff und Sortierung (für «Filter (n)» auf dem Handy). */
export function aktiveFilter(f: Filterwerte): number {
  return [
    f.art,
    f.partnerId,
    f.datumVon || f.datumBis,
    f.betragVon || f.betragBis,
    f.richtung,
    f.status,
    f.tags.trim(),
    f.geschaeftsjahr.trim(),
    f.belegnummer.trim(),
  ].filter(Boolean).length;
}

export function pruefen(f: Filterwerte, seite: number): Ergebnis {
  const fehler = new Set<Pruefung>();
  const anfrage: Suchanfrage = { seite: Math.min(Math.max(seite, 0), SEITE_MAX), groesse: GROESSE };

  const q = f.q.trim();
  if (q) {
    if (q.length > BEGRIFF_MAX || STEUERZEICHEN.test(q)) {
      fehler.add('begriff');
    }
    anfrage.q = q;
    anfrage.sortierung = f.sortierung;
  }
  if (f.art) {
    anfrage.art = f.art;
  }
  if (f.partnerId) {
    anfrage.partner = f.partnerId;
  }

  if ((f.datumVon && !DATUM.test(f.datumVon)) || (f.datumBis && !DATUM.test(f.datumBis))) {
    fehler.add('datum');
  } else if (f.datumVon && f.datumBis && f.datumVon > f.datumBis) {
    fehler.add('datum');
  }
  if (f.datumVon) {
    anfrage.datumVon = f.datumVon;
  }
  if (f.datumBis) {
    anfrage.datumBis = f.datumBis;
  }

  const von = betragText(f.betragVon);
  const bis = betragText(f.betragBis);
  if (von === null || bis === null || (von && bis && vergleichen(von, bis) > 0)) {
    fehler.add('betrag');
  } else {
    if (von) {
      anfrage.betragVon = von;
    }
    if (bis) {
      anfrage.betragBis = bis;
    }
  }

  if (f.richtung) {
    anfrage.richtung = f.richtung;
  }
  if (f.status) {
    anfrage.status = f.status;
  }

  const tags = f.tags
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean);
  if (tags.length > TAGS_MAX || tags.some((t) => t.length > TAG_MAX || STEUERZEICHEN.test(t))) {
    fehler.add('tags');
  } else if (tags.length > 0) {
    anfrage.tags = [...new Set(tags)];
  }

  const jahr = f.geschaeftsjahr.trim();
  if (jahr) {
    if (/^\d{4}$/.test(jahr) && Number(jahr) >= 1000) {
      anfrage.geschaeftsjahr = Number(jahr);
    } else {
      fehler.add('geschaeftsjahr');
    }
  }

  const belegnummer = f.belegnummer.trim();
  if (belegnummer) {
    if (belegnummer.length > BELEGNUMMER_MAX || STEUERZEICHEN.test(belegnummer)) {
      fehler.add('belegnummer');
    }
    anfrage.belegnummer = belegnummer;
  }

  return fehler.size > 0 ? { fehler: [...fehler] } : { anfrage };
}

/** '' für leer, null für ungültig, sonst der Betrag mit Punkt und ohne Apostroph. */
function betragText(eingabe: string): string | null {
  const text = eingabe.replace(/['’\s]/g, '').replace(',', '.');
  if (!text) {
    return '';
  }
  return BETRAG.test(text) ? text : null;
}

// Vergleich als Dezimaltext ohne Gleitkomma (tech.md, Regel 9)
function vergleichen(a: string, b: string): number {
  const [ag, an = ''] = a.replace('-', '').split('.');
  const [bg, bn = ''] = b.replace('-', '').split('.');
  const aw = BigInt(ag + an.padEnd(2, '0')) * (a.startsWith('-') ? -1n : 1n);
  const bw = BigInt(bg + bn.padEnd(2, '0')) * (b.startsWith('-') ? -1n : 1n);
  return aw === bw ? 0 : aw > bw ? 1 : -1;
}
