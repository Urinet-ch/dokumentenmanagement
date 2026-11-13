import { Injectable } from '@angular/core';
import { Ecken } from './ecken';
import { ScannerAntwort } from './scanner-nachricht';

export interface Aufbereitet {
  /** Erkannte bzw. verwendete Ecken in Pixeln des Originals; `null` heisst ganzes Bild. */
  ecken: Ecken | null;
  breite: number;
  hoehe: number;
  /** Arbeitsfassung als JPEG (Req 4.3, 4.5). */
  arbeitsfassung: Blob;
}

/** Port zum Scanner; in Tests durch eine Attrappe ersetzt. */
@Injectable({ providedIn: 'root', useFactory: () => new WorkerScanner() })
export abstract class ScannerPort {
  /** Lädt den Scanner vorab, solange eine Verbindung besteht (ohne Service Worker sonst offline nicht verfügbar). */
  abstract vorbereiten(): void;
  /** `ecken` weglassen: erkennen; `null`: ganzes Bild; sonst zuschneiden auf die angepassten Ecken. */
  abstract aufbereiten(bild: Blob, ecken?: Ecken | null): Promise<Aufbereitet>;
}

/** Scanner im Web Worker; der Worker (mit OpenCV.js, rund 10 MB) startet beim Öffnen der Seite «Erfassen». */
export class WorkerScanner extends ScannerPort {
  private worker?: Worker;
  private nr = 0;
  private readonly offen = new Map<number, (antwort: ScannerAntwort) => void>();

  vorbereiten(): void {
    this.worker ??= this.starten();
  }

  aufbereiten(bild: Blob, ecken?: Ecken | null): Promise<Aufbereitet> {
    const worker = (this.worker ??= this.starten());
    const nr = ++this.nr;
    return new Promise((erledigt, abgelehnt) => {
      this.offen.set(nr, (antwort) =>
        antwort.ok
          ? erledigt({ ecken: antwort.ecken, breite: antwort.breite, hoehe: antwort.hoehe, arbeitsfassung: antwort.arbeitsfassung })
          : abgelehnt(new Error(antwort.fehler)),
      );
      worker.postMessage(ecken === undefined ? { nr, bild } : { nr, bild, ecken });
    });
  }

  private starten(): Worker {
    const worker = new Worker(new URL('./scanner.worker', import.meta.url), { type: 'module' });
    worker.onmessage = ({ data }: MessageEvent<ScannerAntwort>) => {
      this.offen.get(data.nr)?.(data);
      this.offen.delete(data.nr);
    };
    worker.onerror = (ereignis) => {
      ereignis.preventDefault();
      for (const melden of this.offen.values()) {
        melden({ nr: 0, ok: false, fehler: ereignis.message || 'Scanner nicht verfügbar' });
      }
      this.offen.clear();
      worker.terminate();
      this.worker = undefined;
    };
    return worker;
  }
}
