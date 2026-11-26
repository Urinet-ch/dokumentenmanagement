import { Aktion, Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { einfuehrungSchritte } from './einfuehrung-schritte';

describe('Einführung nach Rechten', () => {
  it.each<{ rolle: Rolle; titel: string[]; bereiche: string[] }>([
    { rolle: 'inhaber', titel: ['Willkommen im Archiv', 'Dokumente erfassen', 'Prüfen und bestätigen', 'Finden', 'Bestand und Abschluss', 'Hilfe finden'], bereiche: ['Kontakte', 'Importe', 'Jahresexport', 'Dokumentarten', 'Zugriffsprotokoll', 'API-Tokens'] },
    { rolle: 'mitarbeiter', titel: ['Willkommen im Archiv', 'Dokumente erfassen', 'Ihre erfassten Dokumente', 'Finden', 'Hilfe finden'], bereiche: [] },
    { rolle: 'treuhaender', titel: ['Willkommen im Archiv', 'Eingangskorb ansehen', 'Finden', 'Bestand und Abschluss', 'Hilfe finden'], bereiche: ['Jahresexport'] },
    { rolle: 'geschaeftsstelle', titel: ['Willkommen im Archiv', 'Dokumente erfassen', 'Prüfen und bestätigen', 'Finden', 'Bestand und Abschluss', 'Hilfe finden'], bereiche: ['Kontakte'] },
  ])('zeigt für $rolle nur erlaubte Schritte und Bereiche', ({ rolle, titel, bereiche }) => {
    const schritte = einfuehrungSchritte((aktion) => darf(rolle, aktion), 'Archiv', rolle);
    expect(schritte.map((schritt) => schritt.titel)).toEqual(titel);
    expect(schritte.flatMap((schritt) => schritt.bereiche?.map((bereich) => bereich.text) ?? [])).toEqual(bereiche);
    expect(schritte.length).toBeLessThanOrEqual(6);
  });

  it.each<Rolle>(['inhaber', 'mitarbeiter', 'treuhaender', 'geschaeftsstelle'])(
    'ändert bei %s nur den Branding-Namen, nicht die Einführungstexte (Req 2.4)',
    (rolle) => {
      const aktionen = (aktion: Aktion) => darf(rolle, aktion);
      const erste = einfuehrungSchritte(aktionen, 'Muster Eins', rolle);
      const zweite = einfuehrungSchritte(aktionen, 'Muster Zwei', rolle);
      expect(erste[0].titel).toContain('Muster Eins');
      expect(zweite[0].titel).toContain('Muster Zwei');
      expect({ ...erste[0], titel: '' }).toEqual({ ...zweite[0], titel: '' });
      expect(erste.slice(1)).toEqual(zweite.slice(1));
    },
  );
});
