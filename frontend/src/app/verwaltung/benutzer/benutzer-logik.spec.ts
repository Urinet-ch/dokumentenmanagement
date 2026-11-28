import { Aktion, Ergebnis, VerwaltungRollenrecht } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { rechteGruppen } from './benutzer-logik';

function recht(aktion: Aktion): VerwaltungRollenrecht {
  return { aktion, bezeichnung: aktion, ergebnis: Ergebnis.Erlaubt, fussnoten: [] };
}

describe('rechteGruppen', () => {
  it('ordnet die Rechte aus Spec 09 (A25 bis A29) den Gruppen zu', () => {
    const gruppen = rechteGruppen(['A25', 'A26', 'A27', 'A28', 'A29'].map((a) => recht(a as Aktion)));
    const inGruppe = (titel: string) =>
      gruppen.find((g) => g.titel === titel)?.rechte.map((r) => r.aktion) ?? [];
    expect(inGruppe(TEXTE.benutzer.gruppen.lesen)).toEqual(['A25', 'A27']);
    expect(inGruppe(TEXTE.benutzer.gruppen.erfassen)).toEqual(['A26']);
    expect(inGruppe(TEXTE.benutzer.gruppen.verwalten)).toEqual(['A28', 'A29']);
  });
});
