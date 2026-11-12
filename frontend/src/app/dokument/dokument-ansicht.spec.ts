import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { Dokument, Dokumentart, DownloadAdresse } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DokumentAnsicht, sichereAdresse } from './dokument-ansicht';
import { DokumentPort, Variante } from './dokument-port';

const zukunft = () => new Date(Date.now() + 5 * 60_000).toISOString();

const DOKUMENT: Dokument = {
  id: 'd1',
  art: 'AUSGANGSRECHNUNG',
  titel: 'Rechnung 2026-003',
  datum: '2026-11-08',
  eingang: '2026-11-08T08:00:00Z',
  quelle: 'UPLOAD',
  tags: ['verein'],
  vertraulichkeit: 'NORMAL',
  status: 'ARCHIVIERT',
  fristAbgelaufen: false,
  zusatz: { belegnummer: '2026-003', ohneWert: null },
  version: 1,
};

class DokumentAttrappe extends DokumentPort {
  dokument: Observable<Dokument> = of(DOKUMENT);
  pdfa: Observable<DownloadAdresse> = of({ url: 'https://s3.example/pdfa?sig=1', gueltigBis: zukunft() });
  aufrufe: Variante[] = [];
  lesen(): Observable<Dokument> {
    return this.dokument;
  }
  dokumentarten(): Observable<Dokumentart[]> {
    return of([{ code: 'AUSGANGSRECHNUNG', name: 'Ausgangsrechnung' } as Dokumentart]);
  }
  adresse(_id: string, variante: Variante): Observable<DownloadAdresse> {
    this.aufrufe.push(variante);
    return variante === 'pdfa' ? this.pdfa : of({ url: 'https://s3.example/original', gueltigBis: zukunft() });
  }
}

describe('DokumentAnsicht (Req 14.6)', () => {
  let fixture: ComponentFixture<DokumentAnsicht>;
  let port: DokumentAttrappe;
  let element: HTMLElement;

  beforeEach(() => {
    port = new DokumentAttrappe();
    TestBed.configureTestingModule({
      imports: [DokumentAnsicht],
      providers: [provideRouter([]), { provide: DokumentPort, useValue: port }],
    });
  });

  afterEach(() => vi.restoreAllMocks());

  async function oeffnen() {
    fixture = TestBed.createComponent(DokumentAnsicht);
    fixture.componentRef.setInput('id', 'd1');
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  const knopf = (text: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === text)!;

  it('zeigt Metadaten, Vorschau und Download-Link der PDF/A-Fassung', async () => {
    await oeffnen();
    expect(element.querySelector('[data-test=titel]')!.textContent).toBe('Rechnung 2026-003');
    expect(element.querySelector('[data-test=art]')!.textContent).toBe('Ausgangsrechnung');
    expect(element.querySelector('[data-test=datum]')!.textContent).toBe('08.11.2026');
    expect(element.textContent).toContain('belegnummer');
    expect(element.textContent).not.toContain('ohneWert');
    expect(element.querySelector('[data-test=vorschau]')!.getAttribute('type')).toBe('application/pdf');
    const link = element.querySelector<HTMLAnchorElement>('[data-test=download]')!;
    expect(link.href).toBe('https://s3.example/pdfa?sig=1');
    expect(link.rel).toBe('noopener noreferrer');
    expect(port.aufrufe).toEqual(['pdfa']);
  });

  it('holt eine abgelaufene Adresse beim Klick neu', async () => {
    port.pdfa = of({ url: 'https://s3.example/alt', gueltigBis: new Date(Date.now() - 1000).toISOString() });
    await oeffnen();
    const offen = vi.spyOn(window, 'open').mockReturnValue(null);
    element.querySelector<HTMLAnchorElement>('[data-test=download]')!.click();
    expect(port.aufrufe).toEqual(['pdfa', 'pdfa']);
    expect(offen).toHaveBeenCalledWith('https://s3.example/alt', '_blank', 'noopener,noreferrer');
  });

  it('bietet ohne PDF/A-Fassung das Original an', async () => {
    port.pdfa = throwError(() => new HttpErrorResponse({ status: 404 }));
    await oeffnen();
    expect(element.querySelector('[data-test=vorschau-fehlt]')!.textContent).toBe(TEXTE.dokument.vorschauFehlt);
    const offen = vi.spyOn(window, 'open').mockReturnValue(null);
    knopf(TEXTE.dokument.originalHerunterladen).click();
    expect(offen).toHaveBeenCalledWith('https://s3.example/original', '_blank', 'noopener,noreferrer');
  });

  it('sagt bei 404 nur, dass es das Dokument nicht gibt oder es nicht sichtbar ist (Req 2.3)', async () => {
    port.dokument = throwError(() => new HttpErrorResponse({ status: 404 }));
    await oeffnen();
    expect(element.querySelector('[data-test=fehler]')!.textContent).toBe(TEXTE.dokument.nichtGefunden);
    expect(port.aufrufe).toEqual([]);
  });

  it('holt die Datei-Adresse erst, wenn das Dokument geladen ist', async () => {
    port.dokument = new Subject<Dokument>();
    await oeffnen();
    expect(element.querySelector('[data-test=titel]')).toBeNull();
    expect(port.aufrufe).toEqual([]);
  });

  it('lässt nur http(s)-Adressen in Vorschau und Link', () => {
    expect(sichereAdresse('javascript' + ':void(0)')).toBeNull();
    expect(sichereAdresse('data:application/pdf;base64,AA')).toBeNull();
    expect(sichereAdresse('kaputt')).toBeNull();
    expect(sichereAdresse('https://s3.example/x')).toBe('https://s3.example/x');
  });
});
