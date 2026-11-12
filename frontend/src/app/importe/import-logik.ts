import { HttpErrorResponse } from '@angular/common/http';
import { ImportKontakt, ImportZuordnungBestaetigen } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';

// Fachlogik der Import-Oberfläche ohne Angular (Aufgabe 4.4; Req 13.2, 13.5–13.7, 13.10)

export type ImportArt = 'kontakt' | 'verkaufschance';

/**
 * Grenze des Servers (`archiv.import.max-groesse`, Req 13.10); grössere Dateien lehnt schon die PWA ab. Ändert sich
 * die Konfiguration, hier und in `texte.de-CH.ts` («5 MiB») nachziehen.
 */
export const MAX_GROESSE = 5 * 1024 * 1024;

export const PROFIL_21CRM = ImportZuordnungBestaetigen.ProfilEnum._21Crm;

/** Zielfelder in der Reihenfolge des Servers (`ImportFeld.erlaubt`). */
export const FELDER: Record<ImportArt, readonly string[]> = {
  kontakt: [
    'crm_id',
    'typ',
    'name',
    'organisation',
    'vorname',
    'strasse',
    'plz',
    'ort',
    'email',
    'telefon',
    'uid',
    'iban',
    'kategorie',
    'geloescht',
    'standard_email',
    'email_geschaeft',
    'email_geschaeft_2',
    'email_privat',
    'email_zentrale',
    'standard_telefon',
    'telefon_geschaeft',
    'telefon_zentrale',
    'mobil_geschaeft',
  ],
  verkaufschance: ['crm_id', 'kontakt_crm_id', 'titel', 'phase', 'betrag', 'datum', 'geloescht'],
};

export type Felder = Readonly<Record<string, number>>;

/** Ordnet der Spalte das Feld zu (oder keines); jedes Feld zeigt danach auf höchstens eine Spalte. */
export function zuordnen(felder: Felder, position: number, feld: string | null): Felder {
  const neu: Record<string, number> = {};
  for (const [f, p] of Object.entries(felder)) {
    if (p !== position && f !== feld) {
      neu[f] = p;
    }
  }
  if (feld) {
    neu[feld] = position;
  }
  return neu;
}

export function feldDerSpalte(felder: Felder, position: number): string | null {
  return Object.entries(felder).find(([, p]) => p === position)?.[0] ?? null;
}

/** Pflichtfeld zugeordnet (Req 13.10): Name oder Organisation bzw. Titel. */
export function pflichtErfuellt(art: ImportArt, felder: Felder): boolean {
  return art === 'kontakt' ? 'name' in felder || 'organisation' in felder : 'titel' in felder;
}

/**
 * Das Profil gilt nur, solange jedes Feld des Profil-Vorschlags unverändert zugeordnet ist: Der Server rechnet mit
 * `profil` die Positionen aus der Kopfzeile neu und legt `felder` nur darüber – ein entferntes Feld käme sonst zurück.
 */
export function profilGilt(art: ImportArt, vorschlag: Felder | null, felder: Felder): boolean {
  return (
    art === 'kontakt' && !!vorschlag && Object.entries(vorschlag).every(([feld, position]) => felder[feld] === position)
  );
}

export function zuordnungAnfrage(
  laufId: string,
  art: ImportArt,
  profilVorschlag: Felder | null,
  profilGewaehlt: boolean,
  felder: Felder,
): ImportZuordnungBestaetigen {
  const profil = profilGewaehlt && profilGilt(art, profilVorschlag, felder) ? PROFIL_21CRM : null;
  return { laufId, profil, felder: { ...felder } };
}

export type Entscheid = 'NEU' | 'UEBERSPRINGEN' | `ZUSAMMENFUEHREN:${string}`;

/** Nur getroffene Entscheide gehen mit; Dubletten ohne Entscheid lässt der Server aus (Req 13.5). */
export function entscheideAnfrage(entscheide: ReadonlyMap<number, Entscheid>): Record<string, string> {
  const ergebnis: Record<string, string> = {};
  for (const [nr, entscheid] of entscheide) {
    ergebnis[String(nr)] = entscheid;
  }
  return ergebnis;
}

/** Getroffene Entscheide je Art, für die Bestätigung der Übernahme. */
export function entscheideZaehlen(entscheide: ReadonlyMap<number, Entscheid>): {
  neu: number;
  zusammen: number;
  ueberspringen: number;
} {
  let neu = 0;
  let zusammen = 0;
  let ueberspringen = 0;
  for (const entscheid of entscheide.values()) {
    if (entscheid === 'NEU') {
      neu++;
    } else if (entscheid === 'UEBERSPRINGEN') {
      ueberspringen++;
    } else {
      zusammen++;
    }
  }
  return { neu, zusammen, ueberspringen };
}

/** Ganze Zahlen mit Apostroph als Tausendertrennzeichen (`1'234`). */
export function zahl(wert: number | null | undefined): string {
  return String(wert ?? 0).replace(/\B(?=(\d{3})+(?!\d))/g, "'");
}

const ZEIT = new Intl.DateTimeFormat('de-CH', {
  timeZone: 'Europe/Zurich',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

export function zeit(iso: string | null | undefined): string {
  return iso ? ZEIT.format(new Date(iso)) : '';
}

export function groesse(bytes: number): string {
  const mib = bytes / (1024 * 1024);
  return mib >= 1 ? `${mib.toFixed(1)} MiB` : `${Math.max(1, Math.round(bytes / 1024))} KiB`;
}

export function kontaktText(kontakt: ImportKontakt | undefined, id: string): string {
  if (!kontakt) {
    return `Kontakt ${id.slice(0, 8)}`;
  }
  const name = [kontakt.vorname, kontakt.name].filter(Boolean).join(' ');
  const ort = [kontakt.plz, kontakt.ort].filter(Boolean).join(' ');
  return [`«${name}»`, ort, kontakt.email, kontakt.crmId ? `ID ${kontakt.crmId}` : null].filter(Boolean).join(', ');
}

function problem(fehler: unknown): Record<string, unknown> {
  const inhalt: unknown = fehler instanceof HttpErrorResponse ? fehler.error : null;
  return typeof inhalt === 'object' && inhalt !== null ? (inhalt as Record<string, unknown>) : {};
}

export function fehlercode(fehler: unknown): string | undefined {
  const code = problem(fehler)['fehlercode'];
  return typeof code === 'string' ? code : undefined;
}

/** Vorübergehende Störung (keine Verbindung, Proxy oder Archiv kurz nicht bereit): Die Statusabfrage versucht es weiter. */
export function voruebergehend(fehler: unknown): boolean {
  const status = fehler instanceof HttpErrorResponse ? fehler.status : 0;
  return status === 0 || status === 502 || status === 503 || status === 504;
}

/**
 * Meldung zu einer Fehlerantwort (Problem Details) ohne technische Einzelheiten. Der eigene Text zum Fehlercode geht vor
 * `detail`, weil der Server dort einen allgemeinen Text sendet (z. B. bei `STATUS_KONFLIKT` über «das Dokument»).
 */
export function fehlerText(fehler: unknown): string {
  const status = fehler instanceof HttpErrorResponse ? fehler.status : 0;
  if (status === 401) {
    return TEXTE.importe.fehlerSitzung;
  }
  if (status === 0) {
    return TEXTE.importe.fehlerVerbindung;
  }
  const detail = problem(fehler)['detail'];
  const code = fehlercode(fehler);
  return (
    (code && TEXTE.importe.fehlercode[code]) || (typeof detail === 'string' && detail) || TEXTE.importe.fehlerAllgemein
  );
}
