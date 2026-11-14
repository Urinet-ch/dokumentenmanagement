import { Aktion } from './anmeldung/rechte';
import { TEXTE } from './texte.de-CH';

export interface Menuepunkt {
  pfad: string;
  text: string;
  aktion: Aktion;
  eingangskorbZaehler?: boolean;
}

// Routen aus design.md «Frontend»; sichtbar nur mit dem Recht der Rechtematrix (Req 2.6)
export const MENUE: readonly Menuepunkt[] = [
  { pfad: '/erfassen', text: TEXTE.menue.erfassen, aktion: 'A1' },
  {
    pfad: '/eingangskorb',
    text: TEXTE.menue.eingangskorb,
    aktion: 'A5',
    eingangskorbZaehler: true,
  },
  { pfad: '/suche', text: TEXTE.menue.suche, aktion: 'A2' },
  { pfad: '/kontakte', text: TEXTE.menue.kontakte, aktion: 'A6' },
  { pfad: '/importe', text: TEXTE.menue.importe, aktion: 'A12' },
  { pfad: '/verwaltung/dokumentarten', text: TEXTE.menue.dokumentarten, aktion: 'A11' },
  { pfad: '/verwaltung/protokoll', text: TEXTE.menue.protokoll, aktion: 'A13' },
  { pfad: '/verwaltung/api-tokens', text: TEXTE.menue.apiTokens, aktion: 'A14' },
  { pfad: '/export', text: TEXTE.menue.export, aktion: 'EXPORT' },
];
