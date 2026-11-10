import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Upload } from './upload';
import { UploadEreignis, UploadPort } from './upload-port';

class UploadAttrappe extends UploadPort {
  aufrufe: { namen: string[]; zusammenfassen: boolean }[] = [];
  antwort = new Subject<UploadEreignis>();
  hochladen(dateien: readonly File[], zusammenfassen: boolean) {
    this.aufrufe.push({ namen: dateien.map((d) => d.name), zusammenfassen });
    return this.antwort;
  }
}

const bild = (name: string) => new File(['x'], name, { type: 'image/jpeg' });
const pdf = (name: string) => new File(['%PDF'], name, { type: 'application/pdf' });

describe('Upload (Req 3.1, 3.3–3.8)', () => {
  let fixture: ComponentFixture<Upload>;
  let port: UploadAttrappe;
  let element: HTMLElement;

  beforeEach(async () => {
    port = new UploadAttrappe();
    TestBed.configureTestingModule({
      imports: [Upload],
      providers: [provideRouter([]), { provide: UploadPort, useValue: port }],
    });
    fixture = TestBed.createComponent(Upload);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  async function waehlen(...dateien: File[]) {
    const eingabe = element.querySelector<HTMLInputElement>('input[type=file]')!;
    Object.defineProperty(eingabe, 'files', { value: dateien, configurable: true });
    eingabe.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  function knopf(text: string): HTMLButtonElement {
    const treffer = [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text || b.getAttribute('aria-label') === text,
    );
    if (!treffer) {
      throw new Error(`Knopf «${text}» fehlt`);
    }
    return treffer;
  }

  const namen = () => [...element.querySelectorAll('.datei-name')].map((e) => e.textContent?.trim());
  const ergebnisse = () => [...element.querySelectorAll('[data-test=ergebnis]')].map((e) => e.textContent?.replace(/\s+/g, ' ').trim());
  const checkbox = () => element.querySelector<HTMLInputElement>('input[type=checkbox]')!;

  async function senden(...ereignisse: UploadEreignis[]) {
    knopf(TEXTE.upload.hochladen).click();
    await fixture.whenStable();
    for (const ereignis of ereignisse) {
      port.antwort.next(ereignis);
      await fixture.whenStable();
    }
  }

  it('nimmt mehrere Dateien über die Auswahl an und lädt sie in einem Vorgang hoch', async () => {
    expect(element.querySelector('input[type=file]')!.hasAttribute('multiple')).toBe(true);
    await waehlen(pdf('a.pdf'), pdf('b.pdf'));
    expect(namen()).toEqual(['a.pdf', 'b.pdf']);
    await senden();
    expect(port.aufrufe).toEqual([{ namen: ['a.pdf', 'b.pdf'], zusammenfassen: false }]);
  });

  it('nimmt Dateien per Drag & Drop an', async () => {
    const zone = element.querySelector<HTMLElement>('[data-test=ablage]')!;
    const ziehen = new Event('dragover', { cancelable: true });
    zone.dispatchEvent(ziehen);
    await fixture.whenStable();
    expect(ziehen.defaultPrevented).toBe(true);
    expect(zone.classList).toContain('ziehen');
    const ablegen = Object.assign(new Event('drop', { cancelable: true }), { dataTransfer: { files: [bild('c.jpg')] } });
    zone.dispatchEvent(ablegen);
    await fixture.whenStable();
    expect(namen()).toEqual(['c.jpg']);
    expect(zone.classList).not.toContain('ziehen');
  });

  it('fasst mehrere Bilder in der gewählten Reihenfolge zu einem Dokument zusammen (Req 3.4)', async () => {
    await waehlen(bild('seite-1.jpg'), bild('seite-2.jpg'), bild('seite-3.jpg'));
    knopf(TEXTE.upload.nachUnten('seite-1.jpg')).click();
    await fixture.whenStable();
    knopf(TEXTE.upload.entfernen('seite-3.jpg')).click();
    await fixture.whenStable();
    expect(namen()).toEqual(['seite-2.jpg', 'seite-1.jpg']);
    expect(checkbox().disabled).toBe(false);
    checkbox().click();
    await fixture.whenStable();
    await senden();
    expect(port.aufrufe[0]).toEqual({ namen: ['seite-2.jpg', 'seite-1.jpg'], zusammenfassen: true });
  });

  it('erlaubt «als ein Dokument» nicht für ein PDF oder ein einzelnes Bild', async () => {
    await waehlen(bild('a.jpg'));
    expect(checkbox().disabled).toBe(true);
    await waehlen(pdf('b.pdf'));
    expect(checkbox().disabled).toBe(true);
  });

  it('zeigt den Fortschritt und danach das Ergebnis je Datei (Req 3.5–3.7)', async () => {
    await waehlen(pdf('neu.pdf'), pdf('doppelt.pdf'), pdf('gross.pdf'), pdf('kaputt.pdf'), pdf('text.txt'));
    await senden({ typ: 'fortschritt', prozent: 40 });
    expect(element.querySelector('mat-progress-bar')?.getAttribute('aria-valuenow')).toBe('40');
    expect(element.querySelector('[role=status]')?.textContent).toContain('40 % übertragen');
    expect(knopf(TEXTE.upload.hochladen).disabled).toBe(true);

    port.antwort.next({
      typ: 'fertig',
      ergebnisse: [
        { datei: 'neu.pdf', status: 'NEU', id: 'd-1' },
        { datei: 'doppelt.pdf', status: 'DUBLETTE', vorhandenId: 'd-0' },
        { datei: 'gross.pdf', status: 'ABGELEHNT', fehler: { status: 413, fehlercode: 'DATEI_ZU_GROSS' } },
        {
          datei: 'kaputt.pdf',
          status: 'ABGELEHNT',
          fehler: { status: 422, fehlercode: 'DATEI_UNGUELTIG', detail: 'Die Datei ist beschädigt.' },
        },
        { datei: 'text.txt', status: 'ABGELEHNT', fehler: { status: 415, fehlercode: 'DATEITYP_UNBEKANNT' } },
      ],
    });
    await fixture.whenStable();

    expect(element.querySelector('mat-progress-bar')).toBeNull();
    expect(ergebnisse()).toEqual([
      `✓ ${TEXTE.upload.angenommen}`,
      `= ${TEXTE.upload.bereitsVorhanden} – ${TEXTE.upload.zumVorhandenen}`,
      `! ${TEXTE.upload.abgelehnt}: ${TEXTE.upload.fehlercode['DATEI_ZU_GROSS']} (DATEI_ZU_GROSS)`,
      `! ${TEXTE.upload.abgelehnt}: Die Datei ist beschädigt. (DATEI_UNGUELTIG)`,
      `! ${TEXTE.upload.abgelehnt}: ${TEXTE.upload.fehlercode['DATEITYP_UNBEKANNT']} (DATEITYP_UNBEKANNT)`,
    ]);
    expect(element.querySelector('a')?.getAttribute('href')).toBe('/dokument/d-0');
    expect(document.activeElement?.id).toBe('liste-titel');
    expect(document.activeElement?.textContent).toContain(TEXTE.upload.ergebnisse);
  });

  it('meldet «bereits vorhanden» auch ohne sichtbares Dokument, ohne Link', async () => {
    await waehlen(pdf('doppelt.pdf'));
    await senden({ typ: 'fertig', ergebnisse: [{ datei: 'doppelt.pdf', status: 'DUBLETTE' }] });
    expect(ergebnisse()).toEqual([`= ${TEXTE.upload.bereitsVorhanden}`]);
    expect(element.querySelector('a')).toBeNull();
  });

  it('zeigt Gesamtfehler als Meldung und behält die Dateien', async () => {
    await waehlen(pdf('a.pdf'));
    await senden({ typ: 'fehler', fehler: { status: 413, fehlercode: 'DATEI_ZU_GROSS' } });
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.upload.fehlerAnfrageZuGross);
    expect(namen()).toEqual(['a.pdf']);
    expect(knopf(TEXTE.upload.hochladen).disabled).toBe(false);

    port.antwort = new Subject();
    await senden({ typ: 'fehler', fehler: { status: 0 } });
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.upload.fehlerVerbindung);
  });

  it('beginnt mit «Weitere Dateien erfassen» eine neue Liste', async () => {
    await waehlen(pdf('a.pdf'));
    await senden({ typ: 'fertig', ergebnisse: [{ datei: 'a.pdf', status: 'NEU', id: 'd-1' }] });
    knopf(TEXTE.upload.neu).click();
    await fixture.whenStable();
    expect(namen()).toEqual([]);
    expect(element.textContent).toContain(TEXTE.upload.keineDateien);
  });
});
