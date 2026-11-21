// Einführungsvermerk im localStorage (Spec 07, Req 1.3–1.6; design.md «Einführungsvermerk»). Diese Datei kommt ohne
// Angular und ohne Importe aus: Die Browser-Szenarien (features/hilfe/einfuehrung-vermerk.feature) laden sie unverändert
// in Chromium auf den Ursprung der App. Jeder Zugriff auf den Speicher steht in try/catch wie beim Farbschema.

export const VERMERK_SCHLUESSEL = 'archiv.einfuehrung';
/** Fassung der Einführung; eine höhere zeigt die Einführung nicht erneut an, sie hält nur fest, welche jemand sah. */
export const EINFUEHRUNG_VERSION = 1;
/** Höchstens so viele Vermerke (geteilte Geräte); beim Schreiben eines weiteren fällt der älteste weg (Req 1.6). */
export const MAX_VERMERKE = 20;
const FORMAT = 1;

export type VermerkArt = 'ABGESCHLOSSEN' | 'UEBERSPRUNGEN';

export interface Vermerk {
  /** SHA-256 (hex) von `<mandant>:<sub>`, nie Benutzer-ID, Name oder Rolle im Klartext. */
  readonly k: string;
  readonly version: number;
  readonly art: VermerkArt;
  /** Datum `JJJJ-MM-TT` in Europe/Zurich, ohne Uhrzeit. */
  readonly am: string;
}

/** Kennung des Vermerks; `null` ohne `crypto.subtle` (kein sicherer Kontext) oder bei einem Fehler (Req 1.5). */
export async function vermerkKennung(
  mandant: string,
  sub: string,
  subtle: SubtleCrypto | undefined,
): Promise<string | null> {
  if (!subtle) {
    return null;
  }
  try {
    const hash = await subtle.digest('SHA-256', new TextEncoder().encode(`${mandant}:${sub}`));
    return Array.from(new Uint8Array(hash), (byte) => byte.toString(16).padStart(2, '0')).join('');
  } catch {
    return null;
  }
}

/** Lesbare Vermerke, ältester zuerst; ein fehlender, gesperrter oder unlesbarer Wert gilt als leer (Req 1.5). */
export function vermerkeLesen(speicher: Storage | null): Vermerk[] {
  let json: string | null;
  try {
    json = speicher?.getItem(VERMERK_SCHLUESSEL) ?? null;
  } catch {
    return [];
  }
  if (!json) {
    return [];
  }
  let wert: unknown;
  try {
    wert = JSON.parse(json);
  } catch {
    return [];
  }
  const inhalt = objekt(wert);
  const eintraege = inhalt['v'] === FORMAT ? inhalt['eintraege'] : undefined;
  return Array.isArray(eintraege)
    ? eintraege.filter(istVermerk).map(({ k, version, art, am }) => ({ k, version, art, am }))
    : [];
}

/**
 * Legt den Vermerk ab: ersetzt den Vermerk derselben Kennung, hängt ihn als neuesten an und behält die neuesten
 * {@link MAX_VERMERKE}; ein unlesbarer Wert wird dabei ersetzt, lesbare Vermerke anderer bleiben (Req 1.4–1.6).
 * Liefert `false`, wenn der Browser nicht speichert.
 */
export function vermerkAblegen(speicher: Storage | null, vermerk: Vermerk): boolean {
  if (!speicher) {
    return false;
  }
  const eintraege = [...vermerkeLesen(speicher).filter((e) => e.k !== vermerk.k), vermerk].slice(
    -MAX_VERMERKE,
  );
  try {
    speicher.setItem(VERMERK_SCHLUESSEL, JSON.stringify({ v: FORMAT, eintraege }));
    return true;
  } catch {
    return false;
  }
}

/** Kalenderdatum in Europe/Zurich als `JJJJ-MM-TT`. */
export function vermerkDatum(jetzt: Date): string {
  const teile = new Intl.DateTimeFormat('de-CH', {
    timeZone: 'Europe/Zurich',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(jetzt);
  const teil = (typ: Intl.DateTimeFormatPartTypes) =>
    teile.find((t) => t.type === typ)?.value ?? '';
  return `${teil('year')}-${teil('month')}-${teil('day')}`;
}

function istVermerk(wert: unknown): wert is Vermerk {
  const e = objekt(wert);
  return (
    typeof e['k'] === 'string' &&
    /^[0-9a-f]{64}$/.test(e['k']) &&
    Number.isInteger(e['version']) &&
    (e['version'] as number) >= 1 &&
    (e['art'] === 'ABGESCHLOSSEN' || e['art'] === 'UEBERSPRUNGEN') &&
    typeof e['am'] === 'string' &&
    /^\d{4}-\d{2}-\d{2}$/.test(e['am'])
  );
}

function objekt(wert: unknown): Record<string, unknown> {
  return wert && typeof wert === 'object' && !Array.isArray(wert)
    ? (wert as Record<string, unknown>)
    : {};
}
