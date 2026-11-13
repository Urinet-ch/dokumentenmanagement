import { Ecken } from './ecken';

/** Auftrag an den Scanner-Worker. `ecken` fehlt: erkennen; `null`: ganzes Bild; sonst die angepassten Ecken. */
export interface ScannerAuftrag {
  nr: number;
  bild: Blob;
  ecken?: Ecken | null;
}

export type ScannerAntwort =
  | { nr: number; ok: true; ecken: Ecken | null; breite: number; hoehe: number; arbeitsfassung: Blob }
  | { nr: number; ok: false; fehler: string };
