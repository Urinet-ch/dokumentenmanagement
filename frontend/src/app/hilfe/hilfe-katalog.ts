import { Aktion, Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { TEXTE } from '../gemeinsam/texte.de-CH';

/** Erfüllt, wenn die Rolle mindestens eine Aktion aus «eine» darf und keine aus «ohne» (Glossar Spec 07, «Bedingung»). */
export interface Bedingung {
  readonly eine: readonly Aktion[];
  readonly ohne?: readonly Aktion[];
}

export interface HilfeEintrag {
  /** Sprungmarke «/hilfe#<anker>» und Name der Datei «<anker>.md». */
  readonly anker: string;
  readonly titel: string;
  /** Adresse der Datei, immer «/anleitung/<anker>.md» (Spec 07, Req 7.1). */
  readonly datei: string;
  /** Anker des übergeordneten Abschnitts; ohne ist es ein Abschnitt. */
  readonly eltern?: string;
  /** Ohne Bedingung ist der Eintrag allgemein. */
  readonly bedingung?: Bedingung;
  /** Präfixe der App, denen die Hilfe-Leiste diesen Eintrag zuordnet (wie «aktiverPfad» in navigation.ts). */
  readonly pfade?: readonly string[];
  /** Auch ohne Anmeldung oder ohne Rolle sichtbar (Spec 07, Req 6.3). */
  readonly abgemeldet?: boolean;
}

/**
 * Die eine Liste der Anleitung in Lesereihenfolge (Spec 07, Req 7.2; design.md «Hilfe-Katalog»). Neue Abschnitte
 * kommen mit ihrer Datei unter `frontend/public/anleitung/` (Aufgaben 22.5 bis 22.7 und die Pflegeregel, Req 7.5).
 */
export const HILFE_KATALOG: readonly HilfeEintrag[] = [
  {
    anker: 'erste-schritte',
    titel: TEXTE.hilfe.abschnitte['erste-schritte'],
    datei: '/anleitung/erste-schritte.md',
    pfade: ['/'],
    abgemeldet: true,
  },
  {
    anker: 'probleme',
    titel: TEXTE.hilfe.abschnitte['probleme'],
    datei: '/anleitung/probleme.md',
    pfade: ['/nicht-gefunden'],
    abgemeldet: true,
  },
];

/** Dieselbe Prüfung wie das Menü («darf» in rechte.ts); ohne Bedingung immer erfüllt. */
export function bedingungErfuellt(bedingung: Bedingung | undefined, rolle: Rolle | null): boolean {
  if (!bedingung) return true;
  return bedingung.eine.some((aktion) => darf(rolle, aktion)) && !(bedingung.ohne ?? []).some((aktion) => darf(rolle, aktion));
}

export function hilfeEintrag(anker: string): HilfeEintrag | undefined {
  return HILFE_KATALOG.find((eintrag) => eintrag.anker === anker);
}
