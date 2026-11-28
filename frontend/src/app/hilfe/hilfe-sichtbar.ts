import { InjectionToken } from '@angular/core';
import { Rolle } from '../gemeinsam/anmeldung/rechte';
import { HILFE_KATALOG, HilfeEintrag, bedingungErfuellt } from './hilfe-katalog';

/** Ein sichtbarer Abschnitt mit seinen sichtbaren Unterabschnitten, beide in der Reihenfolge des Katalogs. */
export interface HilfeAbschnitt {
  readonly eintrag: HilfeEintrag;
  readonly unter: readonly HilfeEintrag[];
}

/** Der Katalog, den Seite und Leiste zeigen; in Tests ersetzbar (Standard: {@link HILFE_KATALOG}). */
export const HILFE_EINTRAEGE = new InjectionToken<readonly HilfeEintrag[]>('HILFE_EINTRAEGE', {
  providedIn: 'root',
  factory: () => HILFE_KATALOG,
});

/**
 * Die Abschnitte, die eine Rolle sieht (Spec 07, Req 6.2, 6.3). `rolle` ist nur gesetzt, wenn jemand mit Mandant und
 * Rolle angemeldet ist; sonst (abgemeldet, ohne Mandant oder Rolle) zählen nur Einträge mit `abgemeldet`.
 *
 * Mit Rolle erscheint ein Unterabschnitt nur, wenn auch sein Abschnitt erscheint (design.md «Hilfe-Katalog»). Ohne
 * Rolle steht ein Unterabschnitt mit `abgemeldet`, dessen Abschnitt verborgen ist, als eigener Abschnitt an seiner
 * Stelle (Req 6.3: `erfassen-offline` ohne `erfassen`). Ohne freigeschaltetes Wissen (`wissen`) entfallen die
 * Abschnitte der Wissensbasis (Spec 04, Req 22.5).
 */
export function sichtbareAbschnitte(katalog: readonly HilfeEintrag[], rolle: Rolle | null, wissen = true): HilfeAbschnitt[] {
  const sichtbar = katalog.filter((eintrag) => (rolle ? bedingungErfuellt(eintrag.bedingung, rolle, wissen) : !!eintrag.abgemeldet));
  const anker = new Set(sichtbar.map((eintrag) => eintrag.anker));
  const abschnitte: { eintrag: HilfeEintrag; unter: HilfeEintrag[] }[] = [];
  for (const eintrag of sichtbar) {
    if (eintrag.eltern && anker.has(eintrag.eltern)) {
      abschnitte.find((abschnitt) => abschnitt.eintrag.anker === eintrag.eltern)?.unter.push(eintrag);
    } else if (!eintrag.eltern || !rolle) {
      abschnitte.push({ eintrag, unter: [] });
    }
  }
  return abschnitte;
}

/** Alle Anker, die in den Abschnitten erscheinen (Abschnitte und Unterabschnitte). */
export function sichtbareAnker(abschnitte: readonly HilfeAbschnitt[]): Set<string> {
  return new Set(abschnitte.flatMap(({ eintrag, unter }) => [eintrag.anker, ...unter.map((u) => u.anker)]));
}
