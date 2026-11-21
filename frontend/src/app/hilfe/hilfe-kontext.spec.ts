import { Rolle } from '../gemeinsam/anmeldung/rechte';
import { HILFE_KATALOG } from './hilfe-katalog';
import { hilfeKontext } from './hilfe-kontext';

describe('hilfeKontext (Spec 07, Req 4.3)', () => {
  it('wählt den längsten passenden Pfad, auch mit Query und Fragment', () => {
    expect(hilfeKontext('/wissen/entwuerfe/import?quelle=test#formular', 'inhaber', HILFE_KATALOG)).toBe(
      'wissen-entwuerfe',
    );
    expect(hilfeKontext('/kontakte/beispiel', 'inhaber', HILFE_KATALOG)).toBe('kontakte');
  });

  it('zeigt bei unbekanntem Kontext das Inhaltsverzeichnis', () => {
    expect(hilfeKontext('/hilfe', 'inhaber', HILFE_KATALOG)).toBeNull();
    expect(hilfeKontext('/nicht-zugeordnet', 'inhaber', HILFE_KATALOG)).toBeNull();
  });

  it('liefert für einen verborgenen Abschnitt keinen Anker', () => {
    expect(hilfeKontext('/erfassen', 'treuhaender', HILFE_KATALOG)).toBeNull();
    expect(hilfeKontext('/erfassen', null, HILFE_KATALOG)).toBeNull();
  });

  it.each<Rolle>(['inhaber', 'mitarbeiter', 'treuhaender', 'geschaeftsstelle'])(
    'ordnet für %s Suche und Chat zu',
    (rolle) => {
      expect(hilfeKontext('/suche?q=test', rolle, HILFE_KATALOG)).toBe('suche');
      expect(hilfeKontext('/chat', rolle, HILFE_KATALOG)).toBe('chat');
    },
  );
});
