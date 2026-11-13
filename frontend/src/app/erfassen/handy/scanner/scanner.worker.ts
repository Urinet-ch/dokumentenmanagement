/// <reference lib="webworker" />
// Scanner der Handy-Erfassung (Req 4.3): erkennt das Blatt, schneidet zu, entzerrt und liefert die Arbeitsfassung als
// JPEG. Läuft im Worker, damit OpenCV.js die Oberfläche nicht blockiert; Chunk und WebAssembly kommen aus dem Bundle.
import { Ecken, skalieren } from './ecken';
import { Cv, eckenFinden, entzerren, openCvLaden } from './kanten';
import { ScannerAntwort, ScannerAuftrag } from './scanner-nachricht';

/** Die Kanten werden auf einer Verkleinerung gesucht; das spart Zeit und dämpft Rauschen. */
const ERKENNUNG_KANTE = 1000;
const JPEG_QUALITAET = 0.85;

let opencv: Promise<{ cv: Cv }> | undefined;

addEventListener('message', async ({ data }: MessageEvent<ScannerAuftrag>) => {
  let antwort: ScannerAntwort;
  try {
    antwort = { nr: data.nr, ok: true, ...(await aufbereiten(data)) };
  } catch (fehler) {
    antwort = { nr: data.nr, ok: false, fehler: fehler instanceof Error ? fehler.message : String(fehler) };
  }
  postMessage(antwort);
});

async function aufbereiten(auftrag: ScannerAuftrag) {
  const { cv } = await (opencv ??= openCvLaden());
  const bild = await createImageBitmap(auftrag.bild);
  const { width: breite, height: hoehe } = bild;
  try {
    let ecken: Ecken | null | undefined = auftrag.ecken;
    if (ecken === undefined) {
      const faktor = Math.min(1, ERKENNUNG_KANTE / Math.max(breite, hoehe));
      const klein = pixel(cv, bild, Math.round(breite * faktor), Math.round(hoehe * faktor));
      try {
        const gefunden = eckenFinden(cv, klein);
        ecken = gefunden && skalieren(gefunden, 1 / faktor);
      } finally {
        klein.delete();
      }
    }
    const voll = pixel(cv, bild, breite, hoehe);
    const aus = entzerren(cv, voll, ecken);
    voll.delete();
    const leinwand = new OffscreenCanvas(aus.cols, aus.rows);
    leinwand.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(aus.data), aus.cols, aus.rows), 0, 0);
    aus.delete();
    const arbeitsfassung = await leinwand.convertToBlob({ type: 'image/jpeg', quality: JPEG_QUALITAET });
    return { ecken, breite, hoehe, arbeitsfassung };
  } finally {
    bild.close();
  }
}

function pixel(cv: Cv, bild: ImageBitmap, breite: number, hoehe: number) {
  const leinwand = new OffscreenCanvas(breite, hoehe);
  const kontext = leinwand.getContext('2d', { willReadFrequently: true });
  kontext.drawImage(bild, 0, 0, breite, hoehe);
  return cv.matFromImageData(kontext.getImageData(0, 0, breite, hoehe));
}
