import { AufnahmeGrenzeFehler, pruefeBildPixel } from './aufnahme-grenzen';

export interface BildAbmessungen {
  breite: number;
  hoehe: number;
}

const MAX_KOPF_BYTES = 1024 * 1024;
const HEIF_CONTAINER_BOXEN = new Set(['meta', 'iprp', 'ipco']);

/** Prüft die Rastergrösse aus dem Dateikopf, ohne das Bild zu decodieren. */
export async function leseBildAbmessungen(bild: Blob): Promise<BildAbmessungen> {
  const kopf = new Uint8Array(await bild.slice(0, MAX_KOPF_BYTES).arrayBuffer());
  const abmessungen =
    jpegAbmessungen(kopf) ?? pngAbmessungen(kopf) ?? webpAbmessungen(kopf) ?? heifAbmessungen(kopf);
  if (!abmessungen) {
    throw new AufnahmeGrenzeFehler('bild');
  }
  pruefeBildPixel(abmessungen.breite, abmessungen.hoehe);
  return abmessungen;
}

function jpegAbmessungen(bytes: Uint8Array): BildAbmessungen | null {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) {
    return null;
  }
  let offset = 2;
  while (offset + 1 < bytes.length) {
    if (bytes[offset] !== 0xff) {
      offset++;
      continue;
    }
    while (bytes[offset] === 0xff) {
      offset++;
    }
    const marker = bytes[offset++];
    if (marker === undefined || marker === 0xd9 || marker === 0xda) {
      break;
    }
    if (marker === 0x00 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      continue;
    }
    if (offset + 1 >= bytes.length) {
      break;
    }
    const laenge = (bytes[offset] << 8) | bytes[offset + 1];
    if (laenge < 2 || offset + laenge > bytes.length) {
      break;
    }
    if (istJpegSof(marker) && laenge >= 7) {
      return { hoehe: u16be(bytes, offset + 3), breite: u16be(bytes, offset + 5) };
    }
    offset += laenge;
  }
  return null;
}

function istJpegSof(marker: number): boolean {
  return (
    (marker >= 0xc0 && marker <= 0xc3) ||
    (marker >= 0xc5 && marker <= 0xc7) ||
    (marker >= 0xc9 && marker <= 0xcb) ||
    (marker >= 0xcd && marker <= 0xcf)
  );
}

function pngAbmessungen(bytes: Uint8Array): BildAbmessungen | null {
  if (
    bytes.length < 24 ||
    !gleich(bytes, 0, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]) ||
    !gleich(bytes, 12, [0x49, 0x48, 0x44, 0x52])
  ) {
    return null;
  }
  return { breite: u32be(bytes, 16), hoehe: u32be(bytes, 20) };
}

function webpAbmessungen(bytes: Uint8Array): BildAbmessungen | null {
  if (bytes.length < 20 || text(bytes, 0, 4) !== 'RIFF' || text(bytes, 8, 4) !== 'WEBP') {
    return null;
  }
  let offset = 12;
  while (offset + 8 <= bytes.length) {
    const art = text(bytes, offset, 4);
    const laenge = u32le(bytes, offset + 4);
    const inhalt = offset + 8;
    if (laenge > bytes.length - inhalt) {
      return null;
    }
    if (art === 'VP8X' && laenge >= 10) {
      return { breite: 1 + u24le(bytes, inhalt + 4), hoehe: 1 + u24le(bytes, inhalt + 7) };
    }
    if (art === 'VP8L' && laenge >= 5 && bytes[inhalt] === 0x2f) {
      return {
        breite: 1 + bytes[inhalt + 1] + ((bytes[inhalt + 2] & 0x3f) << 8),
        hoehe:
          1 +
          ((bytes[inhalt + 2] >> 6) |
            (bytes[inhalt + 3] << 2) |
            ((bytes[inhalt + 4] & 0x0f) << 10)),
      };
    }
    if (
      art === 'VP8 ' &&
      laenge >= 10 &&
      bytes[inhalt + 3] === 0x9d &&
      bytes[inhalt + 4] === 0x01 &&
      bytes[inhalt + 5] === 0x2a
    ) {
      return {
        breite: u16le(bytes, inhalt + 6) & 0x3fff,
        hoehe: u16le(bytes, inhalt + 8) & 0x3fff,
      };
    }
    offset = inhalt + laenge + (laenge % 2);
  }
  return null;
}

function heifRaster(
  bytes: Uint8Array,
  start: number,
  ende: number,
  tiefe: number,
): BildAbmessungen | null {
  if (tiefe > 4) {
    return null;
  }
  let groesstes: BildAbmessungen | null = null;
  let offset = start;
  while (offset + 8 <= ende) {
    const size32 = u32be(bytes, offset);
    const art = text(bytes, offset + 4, 4);
    let kopf = 8;
    let boxLaenge = size32;
    if (size32 === 1) {
      if (offset + 16 > ende) {
        break;
      }
      const extended =
        (BigInt(u32be(bytes, offset + 8)) << 32n) | BigInt(u32be(bytes, offset + 12));
      if (extended > BigInt(Number.MAX_SAFE_INTEGER)) {
        break;
      }
      boxLaenge = Number(extended);
      kopf = 16;
    } else if (size32 === 0) {
      boxLaenge = ende - offset;
    }
    if (boxLaenge < kopf || boxLaenge > ende - offset) {
      break;
    }
    const inhalt = offset + kopf;
    const boxEnde = offset + boxLaenge;
    if (art === 'ispe' && boxLaenge >= kopf + 12) {
      groesstes = groesseresRaster(groesstes, {
        breite: u32be(bytes, inhalt + 4),
        hoehe: u32be(bytes, inhalt + 8),
      });
    } else if (HEIF_CONTAINER_BOXEN.has(art)) {
      const kinderStart = inhalt + (art === 'meta' ? 4 : 0);
      groesstes = groesseresRaster(groesstes, heifRaster(bytes, kinderStart, boxEnde, tiefe + 1));
    }
    offset = boxEnde;
  }
  return groesstes;
}

function heifAbmessungen(bytes: Uint8Array): BildAbmessungen | null {
  if (bytes.length < 16 || text(bytes, 4, 4) !== 'ftyp') {
    return null;
  }
  const ftypLaenge = u32be(bytes, 0);
  if (ftypLaenge < 16 || ftypLaenge > bytes.length) {
    return null;
  }
  return heifRaster(bytes, 0, bytes.length, 0);
}

function groesseresRaster(
  bisher: BildAbmessungen | null,
  kandidat: BildAbmessungen | null,
): BildAbmessungen | null {
  return kandidat && (!bisher || kandidat.breite * kandidat.hoehe > bisher.breite * bisher.hoehe)
    ? kandidat
    : bisher;
}

function gleich(bytes: Uint8Array, offset: number, erwartet: number[]): boolean {
  return erwartet.every((wert, index) => bytes[offset + index] === wert);
}

function text(bytes: Uint8Array, offset: number, laenge: number): string {
  return String.fromCharCode(...bytes.subarray(offset, offset + laenge));
}

function u16be(bytes: Uint8Array, offset: number): number {
  return (bytes[offset] << 8) | bytes[offset + 1];
}

function u16le(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8);
}

function u24le(bytes: Uint8Array, offset: number): number {
  return (bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16)) >>> 0;
}

function u32be(bytes: Uint8Array, offset: number): number {
  return (
    (bytes[offset] * 0x1000000 +
      (bytes[offset + 1] << 16) +
      (bytes[offset + 2] << 8) +
      bytes[offset + 3]) >>>
    0
  );
}

function u32le(bytes: Uint8Array, offset: number): number {
  return (
    (bytes[offset] +
      (bytes[offset + 1] << 8) +
      (bytes[offset + 2] << 16) +
      bytes[offset + 3] * 0x1000000) >>>
    0
  );
}
