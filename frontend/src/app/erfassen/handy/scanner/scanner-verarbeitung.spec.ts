import type { Ecken } from './ecken';
import type { Cv } from './kanten';
import { scannerVerarbeiten } from './scanner-verarbeitung';
import type { ScannerVerarbeitungsWerkzeuge } from './scanner-verarbeitung';

describe('Scannerverarbeitung', () => {
  it('erkennt Ecken, entzerrt das Bild und gibt Worker-Antwort und Ressourcen frei', async () => {
    const bitmap = bild(100, 200);
    const matten = [matte(), matte()];
    const aus = matte(300, 400);
    const cv = { matFromImageData: vi.fn(() => matten.shift() ?? matte()) } as unknown as Cv;
    const ecken = {
      obenLinks: { x: 5, y: 10 },
      obenRechts: { x: 95, y: 10 },
      untenRechts: { x: 95, y: 190 },
      untenLinks: { x: 5, y: 190 },
    };
    const werkzeuge = werkzeugeErstellen(bitmap, cv, ecken, aus);

    await expect(scannerVerarbeiten({ nr: 4, bild: png() }, werkzeuge)).resolves.toMatchObject({
      nr: 4,
      ok: true,
      ecken,
      breite: 100,
      hoehe: 200,
      arbeitsfassung: expect.any(Blob),
    });

    expect(werkzeuge.eckenFinden).toHaveBeenCalledOnce();
    expect(werkzeuge.entzerren).toHaveBeenCalledWith(cv, expect.anything(), ecken);
    expect(matten).toHaveLength(0);
    expect(aus.delete).toHaveBeenCalledOnce();
    expect(bitmap.close).toHaveBeenCalledOnce();
  });

  it('meldet einen fehlenden 2D-Kontext und gibt Bitmap sowie entzerrte Matrix frei', async () => {
    const bitmap = bild(100, 200);
    const aus = matte(300, 400);
    const cv = { matFromImageData: vi.fn(() => matte()) } as unknown as Cv;
    const werkzeuge = werkzeugeErstellen(bitmap, cv, null, aus);
    vi.mocked(werkzeuge.leinwandErzeugen)
      .mockImplementationOnce(() => leinwand(false))
      .mockImplementationOnce(() => leinwand(true));

    await expect(
      scannerVerarbeiten({ nr: 9, bild: png(), ecken: null }, werkzeuge),
    ).resolves.toEqual({
      nr: 9,
      ok: false,
      fehler: 'Bild kann auf diesem Gerät nicht verarbeitet werden',
    });

    expect(bitmap.close).toHaveBeenCalledOnce();
    expect(aus.delete).toHaveBeenCalledOnce();
  });

  it('meldet einen fehlenden Lesekontext, bevor OpenCV-Matrizen angelegt werden', async () => {
    const bitmap = bild(100, 200);
    const cv = { matFromImageData: vi.fn(() => matte()) } as unknown as Cv;
    const werkzeuge = werkzeugeErstellen(bitmap, cv, null);
    vi.mocked(werkzeuge.leinwandErzeugen).mockImplementationOnce(() => leinwand(true));

    await expect(scannerVerarbeiten({ nr: 3, bild: png() }, werkzeuge)).resolves.toMatchObject({
      nr: 3,
      ok: false,
      fehler: 'Bild kann auf diesem Gerät nicht verarbeitet werden',
    });

    expect(cv.matFromImageData).not.toHaveBeenCalled();
    expect(bitmap.close).toHaveBeenCalledOnce();
  });

  it('lehnt eine zu grosse Aufnahme ab, bevor Bilddekodierung oder OpenCV startet', async () => {
    const bitmapErzeugen = vi.fn();
    const opencvLaden = vi.fn();
    const werkzeuge = {
      ...werkzeugeErstellen(bild(100, 200), {} as Cv, null),
      bildErzeugen: bitmapErzeugen,
      openCvLaden: opencvLaden,
    };

    const antwort = await scannerVerarbeiten(
      { nr: 2, bild: new Blob([new Uint8Array(10 * 1024 * 1024 + 1)]) },
      werkzeuge,
    );

    expect(antwort).toEqual({ nr: 2, ok: false, fehler: 'groesse' });
    expect(bitmapErzeugen).not.toHaveBeenCalled();
    expect(opencvLaden).not.toHaveBeenCalled();
  });
});

function werkzeugeErstellen(
  bitmap: ImageBitmap,
  cv: Cv,
  ecken: Ecken | null,
  entzerrtesBild = matte(300, 400),
): ScannerVerarbeitungsWerkzeuge {
  return {
    openCvLaden: vi.fn().mockResolvedValue({ cv }),
    bildErzeugen: vi.fn().mockResolvedValue(bitmap),
    eckenFinden: vi.fn().mockReturnValue(ecken),
    entzerren: vi.fn().mockReturnValue(entzerrtesBild),
    leinwandErzeugen: vi.fn(() => leinwand(false)),
    bilddatenErzeugen: vi.fn(() => ({}) as ImageData),
  };
}

function bild(breite: number, hoehe: number): ImageBitmap {
  return { width: breite, height: hoehe, close: vi.fn() } as unknown as ImageBitmap;
}

function matte(cols = 100, rows = 200) {
  return { cols, rows, data: new Uint8Array(4), delete: vi.fn() } as unknown as InstanceType<
    Cv['Mat']
  >;
}

function leinwand(ohneKontext: boolean): OffscreenCanvas {
  const kontext = {
    drawImage: vi.fn(),
    getImageData: vi.fn(() => ({ data: new Uint8ClampedArray(4) })),
    putImageData: vi.fn(),
  };
  return {
    getContext: vi.fn(() => (ohneKontext ? null : kontext)),
    convertToBlob: vi.fn().mockResolvedValue(new Blob(['arbeitsfassung'])),
  } as unknown as OffscreenCanvas;
}

function png(): Blob {
  const bytes = new Uint8Array(24);
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  bytes.set([0x49, 0x48, 0x44, 0x52], 12);
  new DataView(bytes.buffer).setUint32(16, 100);
  new DataView(bytes.buffer).setUint32(20, 200);
  return new Blob([bytes]);
}
