// Typen für den Teil von jscanify 1.4.3 (MIT), den der Scanner nutzt; jscanify selbst bringt keine mit.
declare module 'jscanify/client' {
  import type { Mat } from '@techstark/opencv-js';

  interface JscanifyPunkt {
    x: number;
    y: number;
  }

  export default class Jscanify {
    findPaperContour(bild: Mat): Mat | null;
    getCornerPoints(kontur: Mat): {
      topLeftCorner?: JscanifyPunkt;
      topRightCorner?: JscanifyPunkt;
      bottomLeftCorner?: JscanifyPunkt;
      bottomRightCorner?: JscanifyPunkt;
    };
  }
}
