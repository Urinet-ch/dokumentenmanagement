import { AufnahmeGrenzeFehler, MAX_AUFNAHME_BYTES, pruefeBildPixel } from '../aufnahme-grenzen';
import { leseBildAbmessungen } from '../bild-abmessungen';
import { skalieren } from './ecken';
import type { Ecken } from './ecken';
import type { Cv } from './kanten';
import type { ScannerAntwort, ScannerAuftrag } from './scanner-nachricht';

const ERKENNUNG_KANTE = 1000;
const JPEG_QUALITAET = 0.85;

type Mat = InstanceType<Cv['Mat']>;

export interface ScannerVerarbeitungsWerkzeuge {
  openCvLaden(): Promise<{ cv: Cv }>;
  bildErzeugen(bild: Blob): Promise<ImageBitmap>;
  eckenFinden(cv: Cv, rgba: Mat): Ecken | null;
  entzerren(cv: Cv, rgba: Mat, ecken: Ecken | null): Mat;
  leinwandErzeugen(breite: number, hoehe: number): OffscreenCanvas;
  bilddatenErzeugen(daten: Uint8ClampedArray, breite: number, hoehe: number): ImageData;
}

export async function scannerVerarbeiten(
  auftrag: ScannerAuftrag,
  werkzeuge: ScannerVerarbeitungsWerkzeuge,
): Promise<ScannerAntwort> {
  try {
    return { nr: auftrag.nr, ok: true, ...(await aufbereiten(auftrag, werkzeuge)) };
  } catch (fehler) {
    return {
      nr: auftrag.nr,
      ok: false,
      fehler:
        fehler instanceof AufnahmeGrenzeFehler
          ? fehler.grenze
          : fehler instanceof Error
            ? fehler.message
            : String(fehler),
    };
  }
}

async function aufbereiten(auftrag: ScannerAuftrag, werkzeuge: ScannerVerarbeitungsWerkzeuge) {
  if (auftrag.bild.size > MAX_AUFNAHME_BYTES) {
    throw new AufnahmeGrenzeFehler('groesse');
  }
  await leseBildAbmessungen(auftrag.bild);
  const { cv } = await werkzeuge.openCvLaden();
  const bild = await werkzeuge.bildErzeugen(auftrag.bild);
  try {
    const { width: breite, height: hoehe } = bild;
    pruefeBildPixel(breite, hoehe);
    let ecken: Ecken | null | undefined = auftrag.ecken;
    if (ecken === undefined) {
      const faktor = Math.min(1, ERKENNUNG_KANTE / Math.max(breite, hoehe));
      const klein = pixel(
        werkzeuge,
        cv,
        bild,
        Math.round(breite * faktor),
        Math.round(hoehe * faktor),
      );
      try {
        const gefunden = werkzeuge.eckenFinden(cv, klein);
        ecken = gefunden && skalieren(gefunden, 1 / faktor);
      } finally {
        klein.delete();
      }
    }
    const voll = pixel(werkzeuge, cv, bild, breite, hoehe);
    let aus: Mat;
    try {
      aus = werkzeuge.entzerren(cv, voll, ecken ?? null);
    } finally {
      voll.delete();
    }
    try {
      const leinwand = werkzeuge.leinwandErzeugen(aus.cols, aus.rows);
      const kontext = leinwand.getContext('2d');
      if (!kontext) {
        throw new Error('Bild kann auf diesem Gerät nicht verarbeitet werden');
      }
      kontext.putImageData(
        werkzeuge.bilddatenErzeugen(new Uint8ClampedArray(aus.data), aus.cols, aus.rows),
        0,
        0,
      );
      const arbeitsfassung = await leinwand.convertToBlob({
        type: 'image/jpeg',
        quality: JPEG_QUALITAET,
      });
      return { ecken: ecken ?? null, breite, hoehe, arbeitsfassung };
    } finally {
      aus.delete();
    }
  } finally {
    bild.close();
  }
}

function pixel(
  werkzeuge: ScannerVerarbeitungsWerkzeuge,
  cv: Cv,
  bild: ImageBitmap,
  breite: number,
  hoehe: number,
): Mat {
  const leinwand = werkzeuge.leinwandErzeugen(breite, hoehe);
  const kontext = leinwand.getContext('2d', { willReadFrequently: true });
  if (!kontext) {
    throw new Error('Bild kann auf diesem Gerät nicht verarbeitet werden');
  }
  kontext.drawImage(bild, 0, 0, breite, hoehe);
  return cv.matFromImageData(kontext.getImageData(0, 0, breite, hoehe));
}
