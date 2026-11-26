import { Aktion } from '../gemeinsam/anmeldung/rechte';
import { MENUE } from '../gemeinsam/navigation';
import { TEXTE } from '../gemeinsam/texte.de-CH';

export interface EinfuehrungSchritt {
  readonly titel: string;
  readonly text: string;
  readonly anker: string;
  readonly bereiche?: readonly { text: string; beschreibung: string }[];
}

export function einfuehrungSchritte(
  darf: (aktion: Aktion) => boolean,
  name: string,
  rolle: string,
): EinfuehrungSchritt[] {
  const texte = TEXTE.einfuehrung;
  const schritte: EinfuehrungSchritt[] = [
    { titel: texte.willkommen(name), text: texte.rolle(rolle), anker: 'erste-schritte' },
  ];
  if (darf('A1')) schritte.push({ titel: texte.titel.erfassen, text: texte.erfassen, anker: 'erfassen' });
  if (darf('A5')) {
    const bestaetigen = darf('A7');
    const eigene = !bestaetigen && darf('A1');
    schritte.push({
      titel: bestaetigen ? texte.titel.pruefen : eigene ? texte.titel.eigene : texte.titel.ansehen,
      text: bestaetigen ? texte.pruefen : eigene ? texte.eigene : texte.ansehen,
      anker: 'eingangskorb',
    });
  }
  if (darf('A2')) {
    schritte.push({
      titel: texte.titel.finden,
      text: darf('A15') ? `${texte.finden} ${texte.wissen}` : texte.finden,
      anker: 'suche',
    });
  }
  const pfade = ['/kontakte', '/importe', '/export', '/verwaltung/dokumentarten', '/verwaltung/protokoll', '/verwaltung/api-tokens'];
  const bereiche = pfade.flatMap((pfad) => {
    const punkt = MENUE.find((eintrag) => eintrag.pfad === pfad);
    return punkt && darf(punkt.aktion) ? [{ text: punkt.text, beschreibung: TEXTE.start.beschreibung[pfad] }] : [];
  });
  if (bereiche.length) {
    const anker = darf('A6') ? 'kontakte' : darf('A12') ? 'importe' : darf('EXPORT') ? 'jahresexport' : 'verwaltung';
    schritte.push({ titel: texte.titel.bestand, text: '', anker, bereiche });
  }
  schritte.push({ titel: texte.titel.hilfe, text: texte.hilfe, anker: 'erste-schritte' });
  return schritte;
}
