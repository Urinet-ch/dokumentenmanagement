import { Suchergebnis } from '../gemeinsam/api';
import { LEERE_FILTER, Filterwerte } from './suchfilter';
import { Suchanfrage } from './suche-port';
import { SucheZustand } from './suche-zustand';

afterEach(() => vi.restoreAllMocks());

const FILTER: Filterwerte = { ...LEERE_FILTER, q: '2026', art: 'QUITTUNG' };
const ANFRAGE: Suchanfrage = { modus: 'volltext', q: '2026', seite: 4, groesse: 20 };
const ERGEBNIS: Suchergebnis = { treffer: [], gesamt: 1, seite: 4, groesse: 20 };

describe('SucheZustand', () => {
  it('setzt den vor dem ersten Besitzer gesetzten Stand zurück', () => {
    const zustand = new SucheZustand();
    zustand.filter.set(FILTER);
    zustand.seite.set(4);
    zustand.ergebnis.set(ERGEBNIS);
    zustand.gesendet.set(ANFRAGE);

    zustand.fuer('benutzer-1');

    expect(zustand.filter()).toEqual(LEERE_FILTER);
    expect(zustand.seite()).toBe(0);
    expect(zustand.ergebnis()).toBeNull();
    expect(zustand.gesendet()).toBeNull();
  });

  it('behält für denselben Besitzer Filter, Seite, Ergebnis und gesendete Anfrage', () => {
    const zustand = new SucheZustand();
    zustand.fuer('benutzer-1');
    zustand.filter.set(FILTER);
    zustand.seite.set(4);
    zustand.ergebnis.set(ERGEBNIS);
    zustand.gesendet.set(ANFRAGE);

    zustand.fuer('benutzer-1');

    expect(zustand.filter()).toBe(FILTER);
    expect(zustand.seite()).toBe(4);
    expect(zustand.ergebnis()).toBe(ERGEBNIS);
    expect(zustand.gesendet()).toBe(ANFRAGE);
  });

  it('verwirft den Stand beim Wechsel des Besitzers', () => {
    const zustand = new SucheZustand();
    zustand.fuer('benutzer-1');
    zustand.filter.set(FILTER);
    zustand.seite.set(4);
    zustand.ergebnis.set(ERGEBNIS);
    zustand.gesendet.set(ANFRAGE);

    zustand.fuer('benutzer-2');

    expect(zustand.filter()).toEqual(LEERE_FILTER);
    expect(zustand.seite()).toBe(0);
    expect(zustand.ergebnis()).toBeNull();
    expect(zustand.gesendet()).toBeNull();
  });

  it('verwendet Local Storage und Session Storage nicht', () => {
    const spies = [
      vi.spyOn(Storage.prototype, 'getItem'),
      vi.spyOn(Storage.prototype, 'setItem'),
      vi.spyOn(Storage.prototype, 'removeItem'),
      vi.spyOn(Storage.prototype, 'clear'),
      vi.spyOn(Storage.prototype, 'key'),
    ];
    const zustand = new SucheZustand();

    zustand.fuer('benutzer-1');
    zustand.fuer('benutzer-1');
    zustand.fuer('benutzer-2');

    expect(spies.every((spy) => spy.mock.calls.length === 0)).toBe(true);
  });
});
