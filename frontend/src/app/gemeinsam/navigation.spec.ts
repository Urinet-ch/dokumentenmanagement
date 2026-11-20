import { aktiverPfad } from './navigation';

describe('aktiverPfad', () => {
  const pfade = ['/wissen', '/wissen/entwuerfe', '/suche', '/verwaltung/protokoll'];

  it('wählt den genauesten Menüpunkt', () => {
    expect(aktiverPfad('/wissen/entwuerfe', pfade)).toBe('/wissen/entwuerfe');
    expect(aktiverPfad('/wissen/entwuerfe/7', pfade)).toBe('/wissen/entwuerfe');
  });

  it('lässt den übergeordneten Punkt auf seinen Unterseiten aktiv', () => {
    expect(aktiverPfad('/wissen', pfade)).toBe('/wissen');
    expect(aktiverPfad('/wissen/konzepte/spesen', pfade)).toBe('/wissen');
    expect(aktiverPfad('/suche?q=Hostpoint', pfade)).toBe('/suche');
  });

  it('erkennt keinen Punkt bei blossem gleichem Wortanfang oder fremder Adresse', () => {
    expect(aktiverPfad('/wissensbasis', pfade)).toBeNull();
    expect(aktiverPfad('/', pfade)).toBeNull();
    expect(aktiverPfad('/dokument/3', pfade)).toBeNull();
  });
});
