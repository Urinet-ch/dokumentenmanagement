// Kantenerkennung und Entzerrung (Req 4.3) mit jscanify auf OpenCV.js. Läuft im Web Worker (scanner.worker.ts);
// jscanify greift auf das globale `cv` zu, darum setzt `openCvLaden` es.
import type * as OpenCv from '@techstark/opencv-js';
import * as cvNamensraum from '@techstark/opencv-js';
import Jscanify from 'jscanify/client';
import { Ecken, LANGE_KANTE, pruefen, reihe, zielGroesse } from './ecken';

export type Cv = typeof OpenCv;
type Mat = OpenCv.Mat;

const jscanify = new Jscanify();

/**
 * Wartet, bis OpenCV.js (im Bundle, nie von einem CDN) bereit ist. Statisch importiert und in ein Objekt verpackt,
 * weil das Emscripten-Modul ein `then` hat: Als Ergebnis eines Promise oder von `import()` würde es endlos aufgelöst.
 * Nur der Worker-Chunk lädt diese Datei.
 */
export async function openCvLaden(): Promise<{ cv: Cv }> {
  // Das echte Modulobjekt steckt in `default` (CommonJS); der Namensraum kennt nur die Schlüssel beim Import.
  const cv = (cvNamensraum as unknown as { default?: Cv }).default ?? (cvNamensraum as unknown as Cv);
  if (!cv.Mat) {
    await new Promise<void>((bereit) => {
      (cv as unknown as { onRuntimeInitialized: () => void }).onRuntimeInitialized = bereit;
    });
  }
  (globalThis as unknown as { cv: Cv }).cv = cv;
  return { cv };
}

/** Sucht das Blatt im RGBA-Bild; `null`, wenn keine brauchbaren Kanten da sind (dann gilt das ganze Bild). */
export function eckenFinden(cv: Cv, rgba: Mat): Ecken | null {
  const grau = new cv.Mat();
  let kontur: Mat | null = null;
  try {
    cv.cvtColor(rgba, grau, cv.COLOR_RGBA2GRAY);
    kontur = jscanify.findPaperContour(grau);
    if (!kontur) {
      return null;
    }
    const p = jscanify.getCornerPoints(kontur);
    return pruefen(
      { obenLinks: p.topLeftCorner, obenRechts: p.topRightCorner, untenRechts: p.bottomRightCorner, untenLinks: p.bottomLeftCorner },
      rgba.cols,
      rgba.rows,
    );
  } finally {
    grau.delete();
    kontur?.delete();
  }
}

/** Schneidet zu und entzerrt (ohne Ecken: ganzes Bild); die lange Kante des Ergebnisses misst `langeKante` Pixel. */
export function entzerren(cv: Cv, rgba: Mat, ecken: Ecken | null, langeKante = LANGE_KANTE): Mat {
  const ziel = zielGroesse(ecken, rgba.cols, rgba.rows, langeKante);
  const groesse = new cv.Size(ziel.breite, ziel.hoehe);
  const aus = new cv.Mat();
  if (!ecken) {
    cv.resize(rgba, aus, groesse, 0, 0, cv.INTER_AREA);
    return aus;
  }
  const quelle = cv.matFromArray(4, 1, cv.CV_32FC2, reihe(ecken).flatMap((p) => [p.x, p.y]));
  const zielPunkte = cv.matFromArray(4, 1, cv.CV_32FC2, [0, 0, ziel.breite, 0, ziel.breite, ziel.hoehe, 0, ziel.hoehe]);
  const matrix = cv.getPerspectiveTransform(quelle, zielPunkte);
  try {
    cv.warpPerspective(rgba, aus, matrix, groesse, cv.INTER_LINEAR, cv.BORDER_REPLICATE, new cv.Scalar());
    return aus;
  } finally {
    quelle.delete();
    zielPunkte.delete();
    matrix.delete();
  }
}
