import { AufnahmeGrenzeFehler, pruefeBildPixel } from './aufnahme-grenzen';

export interface BildAbmessungen {
  breite: number;
  hoehe: number;
}

const MAX_KOPF_BYTES = 1024 * 1024;
const MAX_POSITION = 16 * 1024 * 1024;
const MAX_SEGMENTE = 4096;
const HEIF_CONTAINER_BOXEN = new Set(['meta', 'iprp', 'ipco']);

/** Prüft die Rastergrösse aus dem Dateikopf, ohne das Bild zu decodieren. */
export async function leseBildAbmessungen(bild: Blob): Promise<BildAbmessungen> {
  const signatur = await lesen(bild, 0, 24);
  let abmessungen: BildAbmessungen | null;
  if (signatur[0] === 0xff && signatur[1] === 0xd8) {
    abmessungen = await jpegAbmessungen(bild);
  } else if (text(signatur, 0, 4) === 'RIFF' && text(signatur, 8, 4) === 'WEBP') {
    abmessungen = await webpAbmessungen(bild);
  } else {
    abmessungen = pngAbmessungen(signatur);
    if (!abmessungen) {
      abmessungen = heifAbmessungen(await lesen(bild, 0, MAX_KOPF_BYTES));
    }
  }
  if (!abmessungen) {
    throw new AufnahmeGrenzeFehler('bild');
  }
  pruefeBildPixel(abmessungen.breite, abmessungen.hoehe);
  return abmessungen;
}

async function lesen(bild: Blob, offset: number, laenge: number): Promise<Uint8Array> {
  return new Uint8Array(await bild.slice(offset, offset + laenge).arrayBuffer());
}

async function jpegAbmessungen(bild: Blob): Promise<BildAbmessungen | null> {
  let offset = 2;
  const ende = Math.min(bild.size, MAX_POSITION);
  for (let segment = 0; segment < MAX_SEGMENTE && offset + 2 <= ende; segment++) {
    const kopf = await lesen(bild, offset, Math.min(9, ende - offset));
    if (kopf[0] !== 0xff) {
      return null;
    }
    const marker = kopf[1];
    if (marker === 0xff) {
      offset++;
      continue;
    }
    if (marker === 0x00 || marker === 0xd9 || marker === 0xda) {
      return null;
    }
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      offset += 2;
      continue;
    }
    if (kopf.length < 4) {
      return null;
    }
    const laenge = u16be(kopf, 2);
    if (laenge < 2 || offset + 2 + laenge > ende) {
      return null;
    }
    if (istJpegSof(marker)) {
      return laenge >= 7 && kopf.length >= 9
        ? { hoehe: u16be(kopf, 5), breite: u16be(kopf, 7) }
        : null;
    }
    offset += 2 + laenge;
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

async function webpAbmessungen(bild: Blob): Promise<BildAbmessungen | null> {
  const riff = await lesen(bild, 0, 12);
  const ende = u32le(riff, 4) + 8;
  if (riff.length < 12 || ende > bild.size || ende > MAX_POSITION || ende < 20) {
    return null;
  }
  let offset = 12;
  for (let chunk = 0; chunk < MAX_SEGMENTE && offset + 8 <= ende; chunk++) {
    const kopf = await lesen(bild, offset, Math.min(18, ende - offset));
    const art = text(kopf, 0, 4);
    const laenge = u32le(kopf, 4);
    if (laenge + (laenge % 2) > ende - offset - 8) {
      return null;
    }
    if (art === 'VP8X' && laenge >= 10) {
      return { breite: 1 + u24le(kopf, 12), hoehe: 1 + u24le(kopf, 15) };
    }
    if (art === 'VP8L' && laenge >= 5 && kopf[8] === 0x2f) {
      return {
        breite: 1 + kopf[9] + ((kopf[10] & 0x3f) << 8),
        hoehe:
          1 + ((kopf[10] >> 6) | (kopf[11] << 2) | ((kopf[12] & 0x0f) << 10)),
      };
    }
    if (
      art === 'VP8 ' &&
      laenge >= 10 &&
      kopf[11] === 0x9d &&
      kopf[12] === 0x01 &&
      kopf[13] === 0x2a
    ) {
      return {
        breite: u16le(kopf, 14) & 0x3fff,
        hoehe: u16le(kopf, 16) & 0x3fff,
      };
    }
    offset += 8 + laenge + (laenge % 2);
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
