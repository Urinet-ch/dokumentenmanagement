import { Aktion } from './anmeldung/rechte';
import { SymbolName } from './symbol';
import { TEXTE } from './texte.de-CH';

export type Menuegruppe = keyof typeof TEXTE.menueGruppen;

export interface Menuepunkt {
  pfad: string;
  text: string;
  aktion: Aktion;
  gruppe: Menuegruppe;
  symbol: SymbolName;
  eingangskorbZaehler?: boolean;
  wissensentwurfZaehler?: boolean;
}

// Routen aus design.md «Frontend»; sichtbar nur mit dem Recht der Rechtematrix (Req 2.6).
// Gruppen und Reihenfolge nach dem Prototyp der Oberfläche (docs/oberflaeche/, Entscheid Fabian 20.11.2026).
export const MENUE: readonly Menuepunkt[] = [
  {
    pfad: '/eingangskorb',
    text: TEXTE.menue.eingangskorb,
    aktion: 'A5',
    gruppe: 'archiv',
    symbol: 'eingangskorb',
    eingangskorbZaehler: true,
  },
  {
    pfad: '/erfassen',
    text: TEXTE.menue.erfassen,
    aktion: 'A1',
    gruppe: 'archiv',
    symbol: 'erfassen',
  },
  { pfad: '/suche', text: TEXTE.menue.suche, aktion: 'A2', gruppe: 'archiv', symbol: 'suche' },
  { pfad: '/chat', text: TEXTE.menue.chat, aktion: 'A2', gruppe: 'archiv', symbol: 'chat' },
  {
    pfad: '/kontakte',
    text: TEXTE.menue.kontakte,
    aktion: 'A6',
    gruppe: 'bestand',
    symbol: 'kontakte',
  },
  { pfad: '/wissen', text: TEXTE.menue.wissen, aktion: 'A15', gruppe: 'bestand', symbol: 'wissen' },
  {
    pfad: '/wissen/entwuerfe',
    text: TEXTE.wissen.entwuerfe.titel,
    aktion: 'A17',
    gruppe: 'bestand',
    symbol: 'wissen',
    wissensentwurfZaehler: true,
  },
  {
    pfad: '/importe',
    text: TEXTE.menue.importe,
    aktion: 'A12',
    gruppe: 'bestand',
    symbol: 'importe',
  },
  {
    pfad: '/export',
    text: TEXTE.menue.export,
    aktion: 'EXPORT',
    gruppe: 'bestand',
    symbol: 'export',
  },
  {
    pfad: '/verwaltung/einstellungen',
    text: TEXTE.menue.einstellungen,
    aktion: 'A20',
    gruppe: 'verwaltung',
    symbol: 'dokumentarten',
  },
  {
    pfad: '/verwaltung/benutzer',
    text: TEXTE.menue.benutzer,
    aktion: 'A21',
    gruppe: 'verwaltung',
    symbol: 'kontakte',
  },
  {
    pfad: '/verwaltung/dokumentarten',
    text: TEXTE.menue.dokumentarten,
    aktion: 'A11',
    gruppe: 'verwaltung',
    symbol: 'dokumentarten',
  },
  {
    pfad: '/verwaltung/protokoll',
    text: TEXTE.menue.protokoll,
    aktion: 'A13',
    gruppe: 'verwaltung',
    symbol: 'protokoll',
  },
  {
    pfad: '/verwaltung/api-tokens',
    text: TEXTE.menue.apiTokens,
    aktion: 'A14',
    gruppe: 'verwaltung',
    symbol: 'schluessel',
  },
];

export interface Menueabschnitt {
  gruppe: Menuegruppe;
  titel: string;
  punkte: Menuepunkt[];
}

/** Teilt die sichtbaren Menüpunkte in Abschnitte; leere Abschnitte entfallen. */
export function abschnitte(punkte: readonly Menuepunkt[]): Menueabschnitt[] {
  return (Object.keys(TEXTE.menueGruppen) as Menuegruppe[])
    .map((gruppe) => ({
      gruppe,
      titel: TEXTE.menueGruppen[gruppe],
      punkte: punkte.filter((p) => p.gruppe === gruppe),
    }))
    .filter((a) => a.punkte.length > 0);
}

/**
 * Der Menüpunkt zur aktuellen Adresse: der längste Pfad, der die Adresse ist oder ihr Anfang bis zu «/», «?» oder «#».
 * So bleibt «Wissen» auf einer Wissensseite aktiv, auf «/wissen/entwuerfe» aber nur «Wissensentwürfe».
 */
export function aktiverPfad(url: string, pfade: readonly string[]): string | null {
  const passend = pfade.filter(
    (pfad) =>
      url === pfad || (['/', '?', '#'].includes(url.charAt(pfad.length)) && url.startsWith(pfad)),
  );
  return passend.reduce<string | null>(
    (laengster, pfad) => (laengster === null || pfad.length > laengster.length ? pfad : laengster),
    null,
  );
}
