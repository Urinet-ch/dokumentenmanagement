/// <reference lib="webworker" />
// Scanner der Handy-Erfassung (Req 4.3): erkennt das Blatt, schneidet zu, entzerrt und liefert die Arbeitsfassung als
// JPEG. Läuft im Worker, damit OpenCV.js die Oberfläche nicht blockiert; Chunk und WebAssembly kommen aus dem Bundle.
import { eckenFinden, entzerren, openCvLaden } from './kanten';
import type { Cv } from './kanten';
import type { ScannerAuftrag } from './scanner-nachricht';
import { scannerVerarbeiten } from './scanner-verarbeitung';
import type { ScannerVerarbeitungsWerkzeuge } from './scanner-verarbeitung';

let opencv: Promise<{ cv: Cv }> | undefined;

const werkzeuge: ScannerVerarbeitungsWerkzeuge = {
  openCvLaden: () => (opencv ??= openCvLaden()),
  bildErzeugen: (bild) => createImageBitmap(bild),
  eckenFinden,
  entzerren,
  leinwandErzeugen: (breite, hoehe) => new OffscreenCanvas(breite, hoehe),
  bilddatenErzeugen: (daten, breite, hoehe) => new ImageData(daten, breite, hoehe),
};

addEventListener('message', ({ data }: MessageEvent<ScannerAuftrag>) => {
  void scannerVerarbeiten(data, werkzeuge).then((antwort) => postMessage(antwort));
});
