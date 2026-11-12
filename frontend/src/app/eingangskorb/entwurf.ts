import { HttpErrorResponse } from '@angular/common/http';
import { Bestaetigung, Dokument } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';

/** Felder, die der Eingangskorb anzeigt und beim Bestätigen sendet; Texte so, wie sie im Formular stehen. */
export interface Entwurf {
  art: string;
  titel: string;
  datum: string;
  partnerId: string;
  partnerName: string;
  projekt: string;
  belegnummer: string;
  richtung: string;
  betrag: string;
  waehrung: string;
  faelligkeit: string;
  qrReferenz: string;
  gueltigAb: string;
  gueltigBis: string;
  vertraulichkeit: string;
  tags: readonly string[];
}

export type EntwurfFeld = Exclude<keyof Entwurf, 'tags' | 'partnerId'>;

/** Fehler einer Aktion, aus den Problem Details (RFC 9457) gelesen. */
export interface AktionFehler {
  status: number;
  fehlercode?: string;
  detail?: string;
  felder: readonly string[];
}

type Vorschlag = Record<string, unknown>;

const BETRAG = /^-?\d{1,10}([.,]\d{1,2})?$/;

function text(wert: unknown): string {
  if (typeof wert === 'string') {
    return wert;
  }
  return typeof wert === 'number' && Number.isFinite(wert) ? String(wert) : '';
}

function vorschlag(dokument: Dokument): Vorschlag {
  const wert = dokument.vorschlag;
  return wert && typeof wert === 'object' && !Array.isArray(wert) ? (wert as Vorschlag) : {};
}

/** Werte, die schon am Dokument stehen (Grundfelder, gespeicherte Finanz- und Vertragsangaben). */
function amDokument(dokument: Dokument): Record<string, unknown> {
  return {
    art: dokument.art,
    titel: dokument.titel,
    datum: dokument.datum,
    partnerName: dokument.partnerName,
    projekt: dokument.projekt,
    ...(dokument.finanz ?? {}),
    ...(dokument.vertrag ?? {}),
  };
}

/** Wert am Dokument, sonst aus dem Vorschlag. */
function wert(dokument: Dokument, feld: string): string {
  return text(amDokument(dokument)[feld]) || text(vorschlag(dokument)[feld]);
}

/**
 * Entwurf aus dem Dokument und dem Vorschlag der KI: Was am Dokument schon steht, gilt; der Vorschlag füllt nur Lücken.
 * Die Vertraulichkeit bleibt leer («Standard der Art», Req 2.22), ausser der Vorschlag nennt eine; das Dokument zeigt
 * vor der Bestätigung immer `PERSONAL` (Req 2.21) und taugt darum nicht als Vorgabe.
 */
export function entwurfAus(dokument: Dokument): Entwurf {
  const v = vorschlag(dokument);
  const tagsVorschlag = Array.isArray(v['tags'])
    ? v['tags'].filter((t): t is string => typeof t === 'string')
    : [];
  return {
    art: dokument.art ?? text(v['art']),
    titel: dokument.titel ?? text(v['titel']),
    datum: dokument.datum ?? text(v['datum']),
    partnerId: dokument.partnerId ?? '',
    partnerName: dokument.partnerName ?? '',
    projekt: dokument.projekt ?? '',
    belegnummer: wert(dokument, 'belegnummer'),
    richtung: wert(dokument, 'richtung'),
    betrag: wert(dokument, 'betrag'),
    waehrung: wert(dokument, 'waehrung'),
    faelligkeit: wert(dokument, 'faelligkeit'),
    qrReferenz: wert(dokument, 'qrReferenz'),
    gueltigAb: wert(dokument, 'gueltigAb'),
    gueltigBis: wert(dokument, 'gueltigBis'),
    vertraulichkeit: text(v['vertraulichkeit']),
    tags: dokument.tags.length > 0 ? dokument.tags : tagsVorschlag,
  };
}

/** Partner, den die KI erkannt hat, der aber noch keinem Kontakt zugeordnet ist (Req 8.1). */
export function erkannterPartner(dokument: Dokument): string | null {
  if (dokument.partnerId) {
    return null;
  }
  const name = text(vorschlag(dokument)['partnerName']).trim();
  return name || null;
}

/** Kam der Wert des Felds aus dem Vorschlag (und nicht vom Dokument)? */
export function ausVorschlag(dokument: Dokument, feld: EntwurfFeld): boolean {
  return text(amDokument(dokument)[feld]) === '' && text(vorschlag(dokument)[feld]) !== '';
}

/** «bitte prüfen» (Req 7.6): Das Archiv nennt das Feld in `unsicher`, und sein Wert kam aus dem Vorschlag. */
export function bittePruefen(dokument: Dokument, feld: EntwurfFeld): boolean {
  return dokument.unsicher.includes(feld) && ausVorschlag(dokument, feld);
}

export function betragGueltig(betrag: string): boolean {
  const wert = betrag.trim().replace(/'/g, '');
  return wert === '' || BETRAG.test(wert);
}

function betragLesen(betrag: string): number | null {
  const wert = betrag.trim().replace(/'/g, '');
  return wert === '' || !BETRAG.test(wert) ? null : Number(wert.replace(',', '.'));
}

const leerZuNull = (wert: string) => (wert.trim() === '' ? null : wert.trim());

/**
 * Body für `POST /dokumente/{id}/bestaetigen`. Der Body ersetzt die Grundfelder sowie die Finanz- und Vertragsangaben
 * ganz (design.md «Umsetzung 3.4»): Projekt, zugeordnete Person, Geschäftsjahr, Konto, Kündigungsfrist und nächste
 * Frist, die das Formular nicht zeigt, gehen darum unverändert vom Dokument (sonst aus dem Vorschlag) mit.
 */
export function bestaetigungAus(
  entwurf: Entwurf,
  dokument: Dokument,
  zusatz: { ohnePdfa?: boolean; fristAbgelaufenAkzeptiert?: boolean } = {},
): Bestaetigung {
  const vertraulichkeit = leerZuNull(
    entwurf.vertraulichkeit,
  ) as Bestaetigung.VertraulichkeitEnum | null;
  return {
    art: entwurf.art,
    titel: leerZuNull(entwurf.titel),
    datum: leerZuNull(entwurf.datum),
    partnerId: leerZuNull(entwurf.partnerId),
    projekt: leerZuNull(entwurf.projekt),
    tags: [...entwurf.tags],
    ...(vertraulichkeit ? { vertraulichkeit } : {}),
    personBenutzer: dokument.personBenutzer ?? null,
    geschaeftsjahr: dokument.geschaeftsjahr ?? null,
    belegnummer: leerZuNull(entwurf.belegnummer),
    richtung: leerZuNull(entwurf.richtung) as Bestaetigung.RichtungEnum | null,
    betrag: betragLesen(entwurf.betrag),
    waehrung: leerZuNull(entwurf.waehrung)?.toUpperCase() ?? null,
    faelligkeit: leerZuNull(entwurf.faelligkeit),
    qrReferenz: leerZuNull(entwurf.qrReferenz),
    konto: leerZuNull(wert(dokument, 'konto')),
    gueltigAb: leerZuNull(entwurf.gueltigAb),
    gueltigBis: leerZuNull(entwurf.gueltigBis),
    kuendigungsfrist: leerZuNull(wert(dokument, 'kuendigungsfrist')),
    naechsteFrist: leerZuNull(wert(dokument, 'naechsteFrist')),
    ...(zusatz.ohnePdfa ? { ohnePdfa: true } : {}),
    ...(zusatz.fristAbgelaufenAkzeptiert ? { fristAbgelaufenAkzeptiert: true } : {}),
  };
}

export function fehlerAus(fehler: unknown): AktionFehler {
  if (!(fehler instanceof HttpErrorResponse)) {
    return { status: -1, felder: [] };
  }
  const problem =
    fehler.error && typeof fehler.error === 'object'
      ? (fehler.error as Record<string, unknown>)
      : {};
  const felder = Array.isArray(problem['felder'])
    ? problem['felder'].filter((f): f is string => typeof f === 'string')
    : [];
  return {
    status: fehler.status,
    fehlercode: typeof problem['fehlercode'] === 'string' ? problem['fehlercode'] : undefined,
    detail: typeof problem['detail'] === 'string' ? problem['detail'] : undefined,
    felder,
  };
}

/** Name eines Felds aus `felder` (Pflichtfeld der Art, z. B. `qr_referenz`, `partner` oder `zusatz.<name>`). */
export function feldName(feld: string): string {
  if (feld.startsWith('zusatz.')) {
    return TEXTE.eingangskorb.zusatzFeld(feld.substring('zusatz.'.length));
  }
  const camel = feld.replace(/_([a-z])/g, (_, b: string) => b.toUpperCase());
  return TEXTE.eingangskorb.feld[camel] ?? feld;
}

/** `CHF 1'234.50` (product.md, Sprache und Ton). */
export function betragAnzeige(betrag: string, waehrung: string): string {
  const wert = betragLesen(betrag);
  if (wert === null) {
    return betrag;
  }
  const [ganz, rest] = Math.abs(wert).toFixed(2).split('.');
  const gruppiert = ganz.replace(/\B(?=(\d{3})+(?!\d))/g, "'");
  return `${(waehrung || 'CHF').toUpperCase()} ${wert < 0 ? '-' : ''}${gruppiert}.${rest}`;
}

/** `08.11.2026` aus `2026-11-08`; andere Werte unverändert. */
export function datumAnzeige(datum: string): string {
  const treffer = /^(\d{4})-(\d{2})-(\d{2})$/.exec(datum);
  return treffer ? `${treffer[3]}.${treffer[2]}.${treffer[1]}` : datum;
}

/** Eingang (UTC) in `Europe/Zurich` als `08.11.2026` (tech.md, Regel 10). */
export function eingangAnzeige(zeitpunkt: string): string {
  const datum = new Date(zeitpunkt);
  if (Number.isNaN(datum.getTime())) {
    return zeitpunkt;
  }
  return new Intl.DateTimeFormat('de-CH', {
    timeZone: 'Europe/Zurich',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(datum);
}
