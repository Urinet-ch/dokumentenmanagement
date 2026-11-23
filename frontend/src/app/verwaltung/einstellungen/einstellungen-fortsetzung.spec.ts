import { TestBed } from '@angular/core/testing';
import { FENSTER } from '../../gemeinsam/anmeldung/anmeldung.service';
import { EinstellungsFortsetzung, EinstellungsFortsetzungFehler, EinstellungenFortsetzen } from './einstellungen-fortsetzung';

describe('Einmalige Fortsetzung nach Step-up', () => {
  let dienst: EinstellungenFortsetzen;
  const werte = new Map<string, string>();
  const speicher = {
    getItem: (name: string) => werte.get(name) ?? null,
    setItem: (name: string, wert: string) => werte.set(name, wert),
    removeItem: (name: string) => werte.delete(name),
  };
  const fortsetzung: EinstellungsFortsetzung = {
    benutzer: 'test-id', mandant: 'test-mandant', etag: '"3"',
    eingaben: { 'chat.aufbewahrungMonate': '6' },
    bestaetigt: ['chat.aufbewahrungMonate'], erstellt: 1000,
  };

  beforeEach(() => {
    werte.clear();
    TestBed.configureTestingModule({ providers: [{ provide: FENSTER, useValue: { sessionStorage: speicher } }] });
    dienst = TestBed.inject(EinstellungenFortsetzen);
  });

  it('gibt eine bestätigte Änderung nur einmal an denselben Benutzer und Mandanten zurück', () => {
    dienst.merken(fortsetzung);
    expect(dienst.entnehmen('test-id', 'test-mandant', 2000)).toEqual(fortsetzung);
    expect(dienst.entnehmen('test-id', 'test-mandant', 2000)).toBeNull();
  });

  it('verwirft abgelaufene oder fremde Änderungen ohne Wiederholung', () => {
    for (const [benutzer, mandant, zeit] of [
      ['fremd', 'test-mandant', 2000], ['test-id', 'fremd', 2000], ['test-id', 'test-mandant', 602000],
    ] as const) {
      dienst.merken(fortsetzung);
      expect(() => dienst.entnehmen(benutzer, mandant, zeit)).toThrow(EinstellungsFortsetzungFehler);
      expect(dienst.entnehmen('test-id', 'test-mandant', 2000)).toBeNull();
    }
  });

  it('speichert keine fremden Bestätigungsschlüssel oder übergrossen Eingaben', () => {
    expect(() => dienst.merken({ ...fortsetzung, bestaetigt: ['fremd'] })).toThrow(EinstellungsFortsetzungFehler);
    expect(() => dienst.merken({ ...fortsetzung, eingaben: { 'branding.name': 'x'.repeat(17000) }, bestaetigt: [] }))
      .toThrow(EinstellungsFortsetzungFehler);
    expect(werte.size).toBe(0);
  });
});
