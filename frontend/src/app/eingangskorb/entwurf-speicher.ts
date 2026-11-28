import { Injectable, inject } from '@angular/core';
import { FENSTER } from '../gemeinsam/anmeldung/anmeldung.service';
import { Entwurf } from './entwurf';

const PRAEFIX = 'archiv.eingangskorb.entwurf.';
const ENTWURF_PRAEFIX = `${PRAEFIX}v1:`;
const BENUTZER_SCHLUESSEL = `${PRAEFIX}benutzer`;
const MAX_GROESSE = 16_384;

const ENTWURF_FELDER: readonly (keyof Entwurf)[] = [
  'art',
  'titel',
  'datum',
  'partnerId',
  'partnerName',
  'projekt',
  'belegnummer',
  'richtung',
  'betrag',
  'waehrung',
  'faelligkeit',
  'qrReferenz',
  'gueltigAb',
  'gueltigBis',
  'vertraulichkeit',
  'tags',
];

export interface GespeicherterEntwurf {
  entwurf: Entwurf;
  zusatz: Record<string, string>;
}

interface SpeicherEintrag extends GespeicherterEntwurf {
  version: 1;
}

@Injectable({ providedIn: 'root' })
export class EingangskorbEntwurfSpeicher {
  private readonly fenster = inject(FENSTER);

  benutzerWechsel(benutzerId: string): void {
    if (!benutzerId) {
      return;
    }
    const speicher = this.speicher();
    if (speicher) {
      this.fremdeEintraegeLoeschen(speicher, benutzerId);
    }
  }

  speichern(
    benutzerId: string,
    dokumentId: string,
    entwurf: Entwurf,
    zusatz: Readonly<Record<string, string>>,
  ): void {
    if (!benutzerId || !dokumentId) {
      return;
    }
    const eintrag: SpeicherEintrag = {
      version: 1,
      entwurf: { ...entwurf, tags: [...entwurf.tags] },
      zusatz: Object.fromEntries(
        Object.entries(zusatz).filter(([, wert]) => typeof wert === 'string'),
      ),
    };
    const json = JSON.stringify(eintrag);
    if (json.length > MAX_GROESSE) {
      return;
    }
    const speicher = this.speicher();
    if (!speicher) {
      return;
    }
    const aktuellerBenutzer = this.lesenWert(speicher, BENUTZER_SCHLUESSEL);
    if (aktuellerBenutzer !== null && aktuellerBenutzer !== benutzerId) {
      return;
    }
    if (aktuellerBenutzer === null && !this.schreiben(speicher, BENUTZER_SCHLUESSEL, benutzerId)) {
      return;
    }
    const schluessel = this.entwurfSchluessel(benutzerId, dokumentId);
    if (schluessel) {
      this.schreiben(speicher, schluessel, json);
    }
  }

  lesen(benutzerId: string, dokumentId: string): GespeicherterEntwurf | null {
    if (!benutzerId || !dokumentId) {
      return null;
    }
    const speicher = this.speicher();
    if (!speicher) {
      return null;
    }
    this.fremdeEintraegeLoeschen(speicher, benutzerId);
    const schluessel = this.entwurfSchluessel(benutzerId, dokumentId);
    if (!schluessel) {
      return null;
    }
    const json = this.lesenWert(speicher, schluessel);
    if (!json) {
      return null;
    }
    if (json.length > MAX_GROESSE) {
      this.entfernen(speicher, schluessel);
      return null;
    }
    let wert: unknown;
    try {
      wert = JSON.parse(json);
    } catch {
      this.entfernen(speicher, schluessel);
      return null;
    }
    if (!istSpeicherEintrag(wert)) {
      this.entfernen(speicher, schluessel);
      return null;
    }
    return {
      entwurf: { ...wert.entwurf, tags: [...wert.entwurf.tags] },
      zusatz: { ...wert.zusatz },
    };
  }

  loeschen(benutzerId: string, dokumentId: string): void {
    if (!benutzerId || !dokumentId) {
      return;
    }
    const schluessel = this.entwurfSchluessel(benutzerId, dokumentId);
    const speicher = this.speicher();
    if (schluessel && speicher) {
      this.entfernen(speicher, schluessel);
    }
  }

  alleLoeschen(): void {
    const speicher = this.speicher();
    if (!speicher) {
      return;
    }
    for (const schluessel of this.schluesselMitPraefix(speicher)) {
      this.entfernen(speicher, schluessel);
    }
  }

  /**
   * Löscht nur die Entwürfe dieses Benutzers und seinen Marker (Spec 06, Req 11.9); Fremdes bleibt. Liefert `false`, wenn
   * der Browser den Speicher sperrt oder das Löschen scheitert.
   */
  benutzerLoeschen(benutzerId: string): boolean {
    const speicher = this.speicher();
    if (!benutzerId) {
      return true;
    }
    if (!speicher) {
      return false;
    }
    const eigene = `${ENTWURF_PRAEFIX}${encodeURIComponent(benutzerId)}:`;
    try {
      const schluessel = Array.from({ length: speicher.length }, (_, index) => speicher.key(index));
      for (const eintrag of schluessel) {
        if (eintrag?.startsWith(eigene)) {
          speicher.removeItem(eintrag);
        }
      }
      if (speicher.getItem(BENUTZER_SCHLUESSEL) === benutzerId) {
        speicher.removeItem(BENUTZER_SCHLUESSEL);
      }
      return true;
    } catch {
      return false;
    }
  }

  /** Löscht Einträge eines anderen Benutzers. Schreibt nie (der Marker entsteht erst in speichern()). */
  private fremdeEintraegeLoeschen(speicher: Storage, benutzerId: string): void {
    const vorher = this.lesenWert(speicher, BENUTZER_SCHLUESSEL);
    if (vorher === null || vorher === benutzerId) {
      return;
    }
    for (const schluessel of this.schluesselMitPraefix(speicher)) {
      this.entfernen(speicher, schluessel);
    }
  }

  private speicher(): Storage | null {
    try {
      return this.fenster.sessionStorage;
    } catch {
      return null;
    }
  }

  private lesenWert(speicher: Storage, schluessel: string): string | null {
    try {
      return speicher.getItem(schluessel);
    } catch {
      return null;
    }
  }

  private schreiben(speicher: Storage, schluessel: string, wert: string): boolean {
    try {
      speicher.setItem(schluessel, wert);
      return true;
    } catch {
      return false;
    }
  }

  private entfernen(speicher: Storage, schluessel: string): void {
    try {
      speicher.removeItem(schluessel);
    } catch {
      // Browser-Speicher ist optional.
    }
  }

  private schluesselMitPraefix(speicher: Storage): string[] {
    try {
      return Array.from({ length: speicher.length }, (_, index) => speicher.key(index)).filter(
        (schluessel): schluessel is string => !!schluessel && schluessel.startsWith(PRAEFIX),
      );
    } catch {
      return [];
    }
  }

  private entwurfSchluessel(benutzerId: string, dokumentId: string): string | null {
    try {
      return `${ENTWURF_PRAEFIX}${encodeURIComponent(benutzerId)}:${encodeURIComponent(dokumentId)}`;
    } catch {
      return null;
    }
  }
}

function istSpeicherEintrag(wert: unknown): wert is SpeicherEintrag {
  const eintrag = objekt(wert);
  const entwurf = objekt(eintrag['entwurf']);
  const zusatz = objekt(eintrag['zusatz']);
  return (
    eintrag['version'] === 1 &&
    ENTWURF_FELDER.every((feld) =>
      feld === 'tags'
        ? Array.isArray(entwurf[feld]) &&
          (entwurf[feld] as unknown[]).every((tag) => typeof tag === 'string')
        : typeof entwurf[feld] === 'string',
    ) &&
    Object.values(zusatz).every((feld) => typeof feld === 'string')
  );
}

function objekt(wert: unknown): Record<string, unknown> {
  return wert && typeof wert === 'object' && !Array.isArray(wert)
    ? (wert as Record<string, unknown>)
    : {};
}
