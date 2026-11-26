const scan = vi.hoisted(() => ({
  findPaperContour: vi.fn(),
  getCornerPoints: vi.fn(),
}));
vi.mock('@techstark/opencv-js', () => ({ default: {} }));
vi.mock('jscanify/client', () => ({ default: class {
  findPaperContour = scan.findPaperContour;
  getCornerPoints = scan.getCornerPoints;
} }));

import { Cv, eckenFinden, entzerren } from './kanten';

describe('Kantenerkennung', () => {
  it('gibt ohne Kontur null zurück und gibt die temporäre Matrix frei', () => {
    const freigeben = vi.fn();
    const cv = {
      Mat: class { delete = freigeben; },
      cvtColor: vi.fn(),
      COLOR_RGBA2GRAY: 1,
    } as unknown as Cv;
    const bild = { cols: 100, rows: 200 } as InstanceType<Cv['Mat']>;
    scan.findPaperContour.mockReturnValue(null);
    expect(eckenFinden(cv, bild)).toBeNull();
    expect(freigeben).toHaveBeenCalledOnce();
  });

  it('skaliert das ganze Bild, wenn keine Ecken gefunden wurden', () => {
    const resize = vi.fn();
    const cv = {
      Mat: class {},
      Size: class { constructor(public width: number, public height: number) {} },
      resize,
      INTER_AREA: 3,
    } as unknown as Cv;
    const bild = { cols: 100, rows: 200 } as InstanceType<Cv['Mat']>;
    entzerren(cv, bild, null, 1000);
    expect(resize).toHaveBeenCalledWith(bild, expect.anything(), expect.objectContaining({ width: 500, height: 1000 }), 0, 0, 3);
  });
});
