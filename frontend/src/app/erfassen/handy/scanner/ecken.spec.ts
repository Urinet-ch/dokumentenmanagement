import { Ecken, ganzesBild, pruefen, skalieren, zielGroesse } from './ecken';

const A4: Ecken = {
  obenLinks: { x: 10, y: 10 },
  obenRechts: { x: 220, y: 10 },
  untenRechts: { x: 220, y: 307 },
  untenLinks: { x: 10, y: 307 },
};

describe('Ecken der Arbeitsfassung (Req 4.3)', () => {
  it('bringt die lange Kante auf 2480 px und behält das Seitenverhältnis', () => {
    expect(zielGroesse(A4, 400, 400)).toEqual({ breite: 1754, hoehe: 2480 });
    expect(zielGroesse(null, 4032, 3024)).toEqual({ breite: 2480, hoehe: 1860 });
  });

  it('verwirft unvollständige, gekreuzte, zu kleine oder ausserhalb liegende Ecken', () => {
    expect(pruefen(A4, 400, 400)).toEqual(A4);
    expect(pruefen({ ...A4, untenLinks: undefined }, 400, 400)).toBeNull();
    expect(pruefen({ ...A4, obenRechts: A4.untenRechts, untenRechts: A4.obenRechts }, 400, 400)).toBeNull();
    expect(pruefen(A4, 2000, 2000)).toBeNull();
    expect(pruefen(A4, 200, 400)).toBeNull();
    expect(pruefen(null, 400, 400)).toBeNull();
  });

  it('skaliert Ecken und liefert die Ecken des ganzen Bilds', () => {
    expect(skalieren(A4, 2).untenRechts).toEqual({ x: 440, y: 614 });
    expect(ganzesBild(30, 20).untenRechts).toEqual({ x: 30, y: 20 });
  });
});
