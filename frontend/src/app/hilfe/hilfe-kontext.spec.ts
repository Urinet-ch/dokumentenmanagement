import { Rolle } from '../gemeinsam/anmeldung/rechte';
import { HILFE_KATALOG } from './hilfe-katalog';
import { hilfeKontext } from './hilfe-kontext';

describe('hilfeKontext (Spec 07, Req 4.3)', () => {
  it('wählt den längsten passenden Pfad, auch mit Query und Fragment', () => {
    expect(hilfeKontext('/wissen/entwuerfe/import?quelle=test#formular', 'inhaber', HILFE_KATALOG)).toBe(
      'wissen-entwuerfe',
    );
    expect(hilfeKontext('/kontakte/beispiel', 'inhaber', HILFE_KATALOG)).toBe('kontakte');
    expect(hilfeKontext('/verwaltung/benutzer/beispiel', 'inhaber', HILFE_KATALOG)).toBe('verwaltung');
    expect(hilfeKontext('/anmeldung', null, HILFE_KATALOG)).toBe('erste-schritte');
  });

  it('zeigt bei unbekanntem Kontext das Inhaltsverzeichnis', () => {
    expect(hilfeKontext('/hilfe', 'inhaber', HILFE_KATALOG)).toBeNull();
    expect(hilfeKontext('/nicht-zugeordnet', 'inhaber', HILFE_KATALOG)).toBeNull();
  });

  it('liefert für einen verborgenen Abschnitt keinen Anker', () => {
    expect(hilfeKontext('/erfassen', 'treuhaender', HILFE_KATALOG)).toBeNull();
    expect(hilfeKontext('/erfassen', null, HILFE_KATALOG)).toBeNull();
  });

  it('zeigt die Verwaltungshilfe zu Einstellungen nur dem Inhaber', () => {
    expect(hilfeKontext('/verwaltung/einstellungen', 'inhaber', HILFE_KATALOG)).toBe('verwaltung');
    expect(hilfeKontext('/verwaltung/einstellungen/verlauf', 'inhaber', HILFE_KATALOG)).toBe('verwaltung');
    for (const rolle of ['mitarbeiter', 'treuhaender', 'geschaeftsstelle', null] as const) {
      expect(hilfeKontext('/verwaltung/einstellungen', rolle, HILFE_KATALOG)).toBeNull();
    }
  });

  it('zeigt beim Erfassen abgemeldet und offline die Offline-Anleitung', () => {
    expect(hilfeKontext('/erfassen?test=1', null, HILFE_KATALOG, true)).toBe('erfassen-offline');
    expect(hilfeKontext('/suche', null, HILFE_KATALOG, true)).toBeNull();
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
