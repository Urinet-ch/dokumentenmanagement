import { MAX_AUFNAHME_BYTES, AufnahmeGrenzeFehler } from '../aufnahme-grenzen';
import { ScannerAntwort, ScannerAuftrag } from './scanner-nachricht';
import { WorkerScanner } from './scanner.service';

class WorkerAttrappe {
  onmessage: ((ereignis: MessageEvent<ScannerAntwort>) => void) | null = null;
  onerror: ((ereignis: ErrorEvent) => void) | null = null;
  readonly auftraege: ScannerAuftrag[] = [];

  postMessage(auftrag: ScannerAuftrag): void {
    this.auftraege.push(auftrag);
  }

  antworten(antwort: ScannerAntwort): void {
    this.onmessage?.({ data: antwort } as MessageEvent<ScannerAntwort>);
  }
}

describe('WorkerScanner', () => {
  let worker: WorkerAttrappe;

  beforeEach(() => {
    vi.stubGlobal(
      'Worker',
      class extends WorkerAttrappe {
        constructor() {
          super();
          worker = this;
        }
      },
    );
  });

  afterEach(() => vi.unstubAllGlobals());

  it('übergibt eine nächste Seite erst nach Abschluss der vorherigen an den Worker', async () => {
    const scanner = new WorkerScanner();
    const erste = scanner.aufbereiten(new Blob(['erste']));
    const zweite = scanner.aufbereiten(new Blob(['zweite']));
    await vi.waitFor(() => expect(worker.auftraege).toHaveLength(1));

    worker.antworten({ nr: 1, ok: true, ecken: null, breite: 10, hoehe: 10, arbeitsfassung: new Blob(['fertig']) });
    await erste;
    await vi.waitFor(() => expect(worker.auftraege).toHaveLength(2));
    worker.antworten({ nr: 2, ok: true, ecken: null, breite: 10, hoehe: 10, arbeitsfassung: new Blob(['fertig']) });
    await expect(zweite).resolves.toMatchObject({ breite: 10, hoehe: 10 });
  });

  it('weist ein übergrosses Bild ab, ohne einen Worker zu starten', async () => {
    const scanner = new WorkerScanner();
    await expect(scanner.aufbereiten(new Blob([new Uint8Array(MAX_AUFNAHME_BYTES + 1)]))).rejects.toThrow(
      new AufnahmeGrenzeFehler('groesse'),
    );
  });
});
