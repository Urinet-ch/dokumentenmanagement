// Synthetische Testbilder der Handy-Erfassung (Aufgabe 3.7): ein helles, schräg liegendes Blatt auf dunklem Grund
// («ein Blatt», Kanten erkennbar) und eine gleichmässig graue Fläche («eine leere Fläche», keine Kanten). Keine echten
// Belege. Erzeugt als PNG (Dateiwahl) und als Y4M (Video der Fake-Kamera von Chromium, --use-file-for-fake-video-capture).
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { deflateSync } from 'node:zlib';

export const BREITE = 640;
export const HOEHE = 480;

/** Ecken des Blatts im Bild (Uhrzeigersinn ab oben links), leicht gedreht wie ein fotografiertes A4-Blatt. */
const BLATT = [
  [215, 40],
  [455, 62],
  [430, 440],
  [190, 420],
];

const MOTIVE = {
  'ein Blatt': (x, y) => (innen(x, y) ? zeile(x, y) : [34, 38, 44]),
  'eine leere Fläche': () => [128, 128, 128],
};

function innen(x, y) {
  // Punkt liegt rechts jeder Kante (Uhrzeigersinn im Bildkoordinatensystem)
  return BLATT.every(([x1, y1], i) => {
    const [x2, y2] = BLATT[(i + 1) % BLATT.length];
    return (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1) >= 0;
  });
}

/** Etwas «Text» auf dem Blatt, damit es nicht nur eine weisse Fläche ist; die Kanten bleiben die des Blatts. */
function zeile(x, y) {
  const textzeile = y > 110 && y < 360 && Math.floor((y - 110) / 18) % 2 === 0 && (y - 110) % 18 < 5;
  return textzeile && x > 250 && x < 400 ? [70, 70, 70] : [240, 238, 230];
}

/**
 * Kennung und Nummer des Videobilds als kleine, kaum sichtbare Felder am unteren Rand (ausserhalb des Blatts). Jedes
 * Szenario wählt eine eigene Kennung, und jedes Videobild ist anders, damit das Archiv im Testmodus keine Aufnahme als
 * Dublette einer früheren erkennt.
 */
function mitKennung(farbe, kennung, bild) {
  const gesetzt = (wert, x) => {
    const bit = Math.floor((x - 8) / 6);
    return x >= 8 && bit < 32 && (wert >>> bit) & 1;
  };
  return (x, y) => {
    if ((y >= 464 && y < 470 && gesetzt(kennung, x)) || (y >= 456 && y < 462 && gesetzt(bild, x))) {
      return farbe(x, y).map((wert) => wert + 12);
    }
    return farbe(x, y);
  };
}

function raster(motiv, kennung = 0, bild = 0) {
  const farbe = MOTIVE[motiv] && mitKennung(MOTIVE[motiv], kennung, bild);
  if (!farbe) {
    throw new Error(`Unbekanntes Motiv «${motiv}»; bekannt: ${Object.keys(MOTIVE).join(', ')}`);
  }
  const rgb = Buffer.alloc(BREITE * HOEHE * 3);
  for (let y = 0; y < HOEHE; y++) {
    for (let x = 0; x < BREITE; x++) {
      rgb.set(farbe(x, y), (y * BREITE + x) * 3);
    }
  }
  return rgb;
}

const CRC = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  return c >>> 0;
});

function crc32(daten) {
  let c = 0xffffffff;
  for (const byte of daten) {
    c = CRC[(c ^ byte) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(typ, daten) {
  const laenge = Buffer.alloc(4);
  laenge.writeUInt32BE(daten.length);
  const inhalt = Buffer.concat([Buffer.from(typ, 'ascii'), daten]);
  const pruefsumme = Buffer.alloc(4);
  pruefsumme.writeUInt32BE(crc32(inhalt));
  return Buffer.concat([laenge, inhalt, pruefsumme]);
}

/** Motiv als PNG (RGB, 8 Bit). */
export function png(motiv, kennung) {
  const rgb = raster(motiv, kennung);
  const kopf = Buffer.alloc(13);
  kopf.writeUInt32BE(BREITE, 0);
  kopf.writeUInt32BE(HOEHE, 4);
  kopf.set([8, 2, 0, 0, 0], 8);
  const zeilen = Buffer.alloc(HOEHE * (BREITE * 3 + 1));
  for (let y = 0; y < HOEHE; y++) {
    rgb.copy(zeilen, y * (BREITE * 3 + 1) + 1, y * BREITE * 3, (y + 1) * BREITE * 3);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', kopf),
    chunk('IDAT', deflateSync(zeilen)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** Ein Videobild als Y4M-Rahmen (4:2:0, volle Spanne). */
function rahmen(motiv, kennung, bild) {
  const rgb = raster(motiv, kennung, bild);
  const y = Buffer.alloc(BREITE * HOEHE);
  const u = Buffer.alloc((BREITE / 2) * (HOEHE / 2));
  const v = Buffer.alloc(u.length);
  const pixel = (px, py) => rgb.subarray((py * BREITE + px) * 3, (py * BREITE + px) * 3 + 3);
  for (let py = 0; py < HOEHE; py++) {
    for (let px = 0; px < BREITE; px++) {
      const [r, g, b] = pixel(px, py);
      y[py * BREITE + px] = Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    }
  }
  for (let py = 0; py < HOEHE; py += 2) {
    for (let px = 0; px < BREITE; px += 2) {
      let [r, g, b] = [0, 0, 0];
      for (const [dx, dy] of [[0, 0], [1, 0], [0, 1], [1, 1]]) {
        const [pr, pg, pb] = pixel(px + dx, py + dy);
        r += pr / 4;
        g += pg / 4;
        b += pb / 4;
      }
      const i = (py / 2) * (BREITE / 2) + px / 2;
      u[i] = Math.round(128 - 0.168736 * r - 0.331264 * g + 0.5 * b);
      v[i] = Math.round(128 + 0.5 * r - 0.418688 * g - 0.081312 * b);
    }
  }
  return Buffer.concat([Buffer.from('FRAME\n', 'ascii'), y, u, v]);
}

/** Motiv als Y4M-Video mit 10 Bildern pro Sekunde, die sich nur in der Bildnummer unterscheiden; Chromium wiederholt es. */
export function y4m(motiv, kennung, bilder = 10) {
  return Buffer.concat([
    Buffer.from(`YUV4MPEG2 W${BREITE} H${HOEHE} F10:1 Ip A1:1 C420jpeg\n`, 'ascii'),
    ...Array.from({ length: bilder }, (_, bild) => rahmen(motiv, kennung, bild + 1)),
  ]);
}

/** Schreibt das Motiv als Y4M in ein neues temporäres Verzeichnis; der Aufrufer räumt `verzeichnis` auf. */
export function kameraVideo(motiv, kennung) {
  const verzeichnis = mkdtempSync(join(tmpdir(), 'archiv-kamera-'));
  const datei = join(verzeichnis, 'kamera.y4m');
  writeFileSync(datei, y4m(motiv, kennung));
  return { verzeichnis, datei };
}
