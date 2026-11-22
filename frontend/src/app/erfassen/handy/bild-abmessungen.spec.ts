import { AufnahmeGrenzeFehler } from './aufnahme-grenzen';
import { leseBildAbmessungen } from './bild-abmessungen';

describe('Bildabmessungen aus dem Dateikopf', () => {
  it('lehnt ein kleines JPEG mit mehr als 12 Megapixeln anhand des SOF-Headers ab', async () => {
    const bild = jpeg(4001, 3000);
    expect(bild.size).toBeLessThan(10 * 1024 * 1024);
    await expect(leseBildAbmessungen(bild)).rejects.toThrow(new AufnahmeGrenzeFehler('pixel'));
  });

  it('überspringt JPEG-Metadaten über 1 MiB und prüft auch danach die Pixelgrenze', async () => {
    const app = new Uint8Array(65_537);
    app.set([0xff, 0xe1, 0xff, 0xff]);
    const metadata = Array.from({ length: 17 }, () => app);
    const bild = jpeg(1024, 1024);
    const gross = jpeg(4001, 3000);
    const mitMetadaten = async (original: Blob) =>
      new Blob([original.slice(0, 2), ...metadata, original.slice(2)], { type: 'image/jpeg' });
    const klein = await mitMetadaten(bild);
    expect(klein.size).toBeGreaterThan(1024 * 1024);
    await expect(leseBildAbmessungen(klein)).resolves.toEqual({ breite: 1024, hoehe: 1024 });
    await expect(leseBildAbmessungen(await mitMetadaten(gross))).rejects.toThrow(
      new AufnahmeGrenzeFehler('pixel'),
    );
  });

  it('liest VP8- und VP8L-Kopf unabhängig von der Länge des Bild-Chunks', async () => {
    for (const bild of [webpVp8(1024, 1024, 2 * 1024 * 1024), webpVp8l(1024, 1024, 3 * 1024 * 1024)]) {
      expect(bild.size).toBeGreaterThan(1024 * 1024);
      await expect(leseBildAbmessungen(bild)).resolves.toEqual({ breite: 1024, hoehe: 1024 });
    }
    await expect(leseBildAbmessungen(webpVp8l(4001, 3000, 2 * 1024 * 1024))).rejects.toThrow(
      new AufnahmeGrenzeFehler('pixel'),
    );
  });

  it('weist einen abgeschnittenen WebP-Chunk ab', async () => {
    const bild = webpVp8l(1024, 1024, 1024);
    await expect(leseBildAbmessungen(bild.slice(0, bild.size - 1))).rejects.toThrow(
      new AufnahmeGrenzeFehler('bild'),
    );
  });

  it('liest PNG-, WebP- und HEIC-Abmessungen ohne Bilddecodierung', async () => {
    await expect(leseBildAbmessungen(png(4000, 3000))).resolves.toEqual({
      breite: 4000,
      hoehe: 3000,
    });
    await expect(leseBildAbmessungen(webp(1600, 1200))).resolves.toEqual({
      breite: 1600,
      hoehe: 1200,
    });
    await expect(leseBildAbmessungen(webpVp8l(640, 480))).resolves.toEqual({
      breite: 640,
      hoehe: 480,
    });
    await expect(leseBildAbmessungen(webpVp8(320, 240))).resolves.toEqual({
      breite: 320,
      hoehe: 240,
    });
    await expect(leseBildAbmessungen(heic(800, 600))).resolves.toEqual({ breite: 800, hoehe: 600 });
  });

  it('weist nicht lesbare Bildköpfe sicher ab', async () => {
    await expect(leseBildAbmessungen(new Blob(['kein Bild']))).rejects.toThrow(
      new AufnahmeGrenzeFehler('bild'),
    );
  });
});

function jpeg(breite: number, hoehe: number): Blob {
  return new Blob(
    [
      new Uint8Array([
        0xff,
        0xd8,
        0xff,
        0xc0,
        0x00,
        0x11,
        0x08,
        (hoehe >> 8) & 0xff,
        hoehe & 0xff,
        (breite >> 8) & 0xff,
        breite & 0xff,
        0x03,
        0x01,
        0x11,
        0x00,
        0x02,
        0x11,
        0x00,
        0x03,
        0x11,
        0x00,
      ]),
    ],
    { type: 'image/jpeg' },
  );
}

function png(breite: number, hoehe: number): Blob {
  const bytes = new Uint8Array(24);
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  bytes.set([0x49, 0x48, 0x44, 0x52], 12);
  new DataView(bytes.buffer).setUint32(16, breite);
  new DataView(bytes.buffer).setUint32(20, hoehe);
  return new Blob([bytes], { type: 'image/png' });
}

function webp(breite: number, hoehe: number): Blob {
  return webpChunk('VP8X', [
    0,
    0,
    0,
    0,
    (breite - 1) & 0xff,
    ((breite - 1) >> 8) & 0xff,
    ((breite - 1) >> 16) & 0xff,
    (hoehe - 1) & 0xff,
    ((hoehe - 1) >> 8) & 0xff,
    ((hoehe - 1) >> 16) & 0xff,
  ]);
}

function webpVp8l(breite: number, hoehe: number, fuellung = 0): Blob {
  const w = breite - 1;
  const h = hoehe - 1;
  return webpChunk('VP8L', [
    0x2f,
    w & 0xff,
    ((w >> 8) & 0x3f) | ((h & 0x03) << 6),
    (h >> 2) & 0xff,
    (h >> 10) & 0x0f,
  ], fuellung);
}

function webpVp8(breite: number, hoehe: number, fuellung = 0): Blob {
  return webpChunk('VP8 ', [
    0,
    0,
    0,
    0x9d,
    0x01,
    0x2a,
    breite & 0xff,
    (breite >> 8) & 0x3f,
    hoehe & 0xff,
    (hoehe >> 8) & 0x3f,
  ], fuellung);
}

function webpChunk(art: string, inhalt: number[], fuellung = 0): Blob {
  const laenge = inhalt.length + fuellung;
  const bytes = new Uint8Array(20 + laenge + (laenge % 2));
  bytes.set([0x52, 0x49, 0x46, 0x46]);
  new DataView(bytes.buffer).setUint32(4, bytes.length - 8, true);
  bytes.set([0x57, 0x45, 0x42, 0x50], 8);
  bytes.set(
    [...art].map((zeichen) => zeichen.charCodeAt(0)),
    12,
  );
  new DataView(bytes.buffer).setUint32(16, laenge, true);
  bytes.set(inhalt, 20);
  return new Blob([bytes], { type: 'image/webp' });
}

function heic(breite: number, hoehe: number): Blob {
  const ispe = new Uint8Array(20);
  new DataView(ispe.buffer).setUint32(0, 20);
  ispe.set([0x69, 0x73, 0x70, 0x65], 4);
  new DataView(ispe.buffer).setUint32(12, breite);
  new DataView(ispe.buffer).setUint32(16, hoehe);

  const ipco = box('ipco', ispe);
  const iprp = box('iprp', ipco);
  const meta = box('meta', new Uint8Array([0, 0, 0, 0, ...iprp]));
  const ftyp = box(
    'ftyp',
    new Uint8Array([0x68, 0x65, 0x69, 0x63, 0, 0, 0, 0, 0x68, 0x65, 0x69, 0x63]),
  );
  return new Blob([ftyp, meta], { type: 'image/heic' });
}

function box(art: string, inhalt: Uint8Array): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(8 + inhalt.length);
  new DataView(bytes.buffer).setUint32(0, bytes.length);
  bytes.set(
    [...art].map((zeichen) => zeichen.charCodeAt(0)),
    4,
  );
  bytes.set(inhalt, 8);
  return bytes;
}
