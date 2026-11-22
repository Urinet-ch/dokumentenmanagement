import { AufnahmeGrenzeFehler, MAX_AUFNAHME_BYTES, MAX_AUFNAHME_SEITEN, pruefeAufnahme, pruefeBildPixel } from './aufnahme-grenzen';

const blob = (groesse: number) => new Blob([new Uint8Array(groesse)]);

describe('Grenzen der Handy-Erfassung', () => {
  it('akzeptiert genau 20 Seiten und höchstens 10 MiB für Originale und Arbeitsfassungen', () => {
    expect(() => pruefeAufnahme(Array.from({ length: MAX_AUFNAHME_SEITEN }, () => ({ original: new Blob() })))).not.toThrow();
    expect(() => pruefeAufnahme([{ original: blob(MAX_AUFNAHME_BYTES) }])).not.toThrow();
  });

  it('weist eine zusätzliche Seite oder mehr als 10 MiB vor dem Speichern ab', () => {
    expect(() =>
      pruefeAufnahme(Array.from({ length: MAX_AUFNAHME_SEITEN + 1 }, () => ({ original: new Blob() }))),
    ).toThrow(new AufnahmeGrenzeFehler('seiten'));
    expect(() => pruefeAufnahme([{ original: blob(MAX_AUFNAHME_BYTES + 1) }])).toThrow(new AufnahmeGrenzeFehler('groesse'));
    expect(() =>
      pruefeAufnahme([{ original: blob(MAX_AUFNAHME_BYTES / 2), arbeitsfassung: blob(MAX_AUFNAHME_BYTES / 2 + 1) }]),
    ).toThrow(new AufnahmeGrenzeFehler('groesse'));
  });

  it('lässt 12 Megapixel zu und lehnt grössere oder ungültige Raster ab', () => {
    expect(() => pruefeBildPixel(4000, 3000)).not.toThrow();
    expect(() => pruefeBildPixel(4001, 3000)).toThrow(new AufnahmeGrenzeFehler('pixel'));
    expect(() => pruefeBildPixel(0, 1)).toThrow(new AufnahmeGrenzeFehler('pixel'));
  });
});
