import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import {
  Dokument,
  DokumentAenderung,
  Dokumentart,
  DownloadAdresse,
  Kontakt,
} from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { Aktion, Rolle, darf } from '../gemeinsam/anmeldung/rechte';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { DokumentAnsicht, sichereAdresse } from './dokument-ansicht';
import { Ansicht, DokumentPort, Variante } from './dokument-port';

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
  warnungen: [],
  pdfaFehlt: false,
  unsicher: [],
  versionen: [],
  klassifikationen: {},
  klassifikationenUngueltig: [],
};

const ART: Dokumentart = {
  code: 'AUSGANGSRECHNUNG',
  name: 'Ausgangsrechnung',
  gruppen: ['finanz'],
  pflichtfelder: ['titel', 'datum'],
  zusatzSchema: {},
  vertraulichkeit: 'NORMAL',
  gesetzlichePflicht: true,
  aktiv: true,
  selbstAusgestellt: true,
  belegnummerMuster: [],
  kiVorschlag: true,
  kiFelder: [],
  reihenfolge: 0,
};

class DokumentAttrappe extends DokumentPort {
  dokument: Observable<Dokument> = of(DOKUMENT);
  pdfa: Observable<DownloadAdresse> = of({
    url: 'https://s3.example/pdfa?sig=1',
    gueltigBis: zukunft(),
  });
  original: () => DownloadAdresse = () => ({
    url: 'https://s3.example/original',
    gueltigBis: zukunft(),
  });
  /** Adresse der PDF/A-Fassung für den Download (attachment), getrennt von der Vorschau (inline). */
  pdfaDownload: () => DownloadAdresse = () => ({
    url: 'https://s3.example/pdfa-dl?sig=2',
    gueltigBis: zukunft(),
  });
  aufrufe: Variante[] = [];
  ansichten: Ansicht[] = [];
  gelesen = 0;
  bilder: number[] = [];
  bild: Observable<Blob> = of(new Blob(['png'], { type: 'image/png' }));
  geaendert: Observable<Dokument> = of(DOKUMENT);
  aenderungen: { version: number; aenderung: DokumentAenderung }[] = [];
  arten: Dokumentart[] = [ART];
  lesen(): Observable<Dokument> {
    this.gelesen++;
    return this.dokument;
  }
  dokumentarten(): Observable<Dokumentart[]> {
    return of(this.arten);
  }
  vorschau(_id: string, seite: number): Observable<Blob> {
    this.bilder.push(seite);
    return this.bild;
  }
  aendern(_id: string, version: number, aenderung: DokumentAenderung): Observable<Dokument> {
    this.aenderungen.push({ version, aenderung });
    return this.geaendert;
  }
  kontakte(): Observable<Kontakt[]> {
    return of([]);
  }
  adresse(_id: string, variante: Variante, ansicht: Ansicht): Observable<DownloadAdresse> {
    this.aufrufe.push(variante);
    this.ansichten.push(ansicht);
    if (variante === 'pdfa') {
      return ansicht === 'vorschau' ? this.pdfa : of(this.pdfaDownload());
    }
    return of(this.original());
  }
}

describe('DokumentAnsicht (Req 14.6)', () => {
  let fixture: ComponentFixture<DokumentAnsicht>;
  let port: DokumentAttrappe;
  let element: HTMLElement;
  const rolle = signal<Rolle>('inhaber');

  beforeEach(() => {
    port = new DokumentAttrappe();
    rolle.set('inhaber');
    TestBed.configureTestingModule({
      imports: [DokumentAnsicht],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DokumentPort, useValue: port },
        {
          provide: AnmeldungService,
          useValue: {
            darf: (a: Aktion) => darf(rolle(), a),
            benutzer: () => ({
              id: 'sub-inhaber',
              name: 'Inhaber',
              mandant: 'urinet',
              rolle: rolle(),
            }),
          },
        },
      ],
    });
  });

  afterEach(() => vi.restoreAllMocks());

  async function oeffnen(art?: string) {
    fixture = TestBed.createComponent(DokumentAnsicht);
    fixture.componentRef.setInput('id', 'd1');
    if (art) {
      fixture.componentRef.setInput('art', art);
    }
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  const knopf = (text: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    )!;

  it('zeigt Listenwerte mit Anzeigetext aus x-labels, ohne Beschriftung den Rohwert', async () => {
    const schema = (labels: object) => ({
      type: 'object',
      properties: {
        verband: { type: 'array', items: { type: 'string', enum: ['vhf', 'gsk'] }, ...labels },
      },
    });
    port.arten = [{ ...ART, zusatzSchema: schema({ 'x-labels': { vhf: 'VHF', gsk: 'GSK' } }) }];
    port.dokument = of({ ...DOKUMENT, zusatz: { verband: ['vhf', 'gsk'] } });
    await oeffnen();
    expect(element.textContent).toContain('VHF, GSK');
    expect(element.textContent).not.toContain('vhf');
  });

  it('zeigt Listenwerte ohne x-labels als Rohwert', async () => {
    port.arten = [
      {
        ...ART,
        zusatzSchema: {
          type: 'object',
          properties: {
            verband: { type: 'array', items: { type: 'string', enum: ['vhf', 'gsk'] } },
          },
        },
      },
    ];
    port.dokument = of({ ...DOKUMENT, zusatz: { verband: ['vhf', 'gsk'] } });
    await oeffnen();
    expect(element.textContent).toContain('vhf, gsk');
  });

  it('zeigt Metadaten, Vorschau und Download-Link der PDF/A-Fassung', async () => {
    await oeffnen();
    expect(element.querySelector('[data-test=titel]')!.textContent).toBe('Rechnung 2026-003');
    expect(element.querySelector('[data-test=art]')!.textContent).toBe('Ausgangsrechnung');
    expect(element.querySelector('[data-test=datum]')!.textContent).toBe('08.11.2026');
    expect(element.textContent).toContain('belegnummer');
    expect(element.textContent).not.toContain('ohneWert');
    expect(element.querySelector<HTMLAnchorElement>('[data-test=pdf-laden]')!.href).toBe(
      'https://s3.example/pdfa?sig=1',
    );
    expect(element.querySelector<HTMLAnchorElement>('[data-test=pdf-laden]')!.rel).toBe(
      'noopener noreferrer',
    );
    expect(element.querySelector('[data-test=download]')).toBeTruthy();
    // nur die Vorschau-Adresse (inline) ist geholt; der Download holt seine eigene erst beim Klick
    expect(port.aufrufe).toEqual(['pdfa']);
    expect(port.ansichten).toEqual(['vorschau']);
  });

  it('lädt beim Klick auf Herunterladen eine eigene Download-Adresse (attachment), nicht die der Vorschau', async () => {
    await oeffnen();
    const offen = vi.spyOn(window, 'open').mockReturnValue(null);
    element.querySelector<HTMLButtonElement>('[data-test=download]')!.click();
    expect(port.ansichten).toEqual(['vorschau', 'download']);
    expect(offen).toHaveBeenCalledWith(
      'https://s3.example/pdfa-dl?sig=2',
      '_blank',
      'noopener,noreferrer',
    );
    expect(offen).not.toHaveBeenCalledWith(
      'https://s3.example/pdfa?sig=1',
      expect.anything(),
      expect.anything(),
    );
  });

  it('verlinkt den Partner nur mit A6 auf die Kontaktseite (Req 17.1)', async () => {
    port.dokument = of({ ...DOKUMENT, partnerId: 'k1', partnerName: 'Muster Garage AG' });
    await oeffnen();
    expect(element.querySelector('[data-test=partner-link]')!.getAttribute('href')).toBe(
      '/kontakte/k1',
    );
    expect(element.querySelector('[data-test=partner]')!.textContent).toContain('Muster Garage AG');
  });

  it('zeigt den Partner ohne A6 nur als Text', async () => {
    rolle.set('mitarbeiter');
    port.dokument = of({ ...DOKUMENT, partnerId: 'k1', partnerName: 'Muster Garage AG' });
    await oeffnen();
    expect(element.querySelector('[data-test=partner-link]')).toBeNull();
    expect(element.querySelector('[data-test=partner]')!.textContent).toContain('Muster Garage AG');
  });

  it('zeigt die Zeile «Dateiname» nur mit gespeichertem Namen (Req 14.10)', async () => {
    await oeffnen();
    expect(element.querySelector('[data-test=dateiname]')).toBeNull();
    expect(element.textContent).not.toContain(TEXTE.dokument.dateiname);
  });

  it('setzt ohne Titel den Dateinamen als Platzhalter, in jedem Status (Req 14.10)', async () => {
    port.dokument = of({
      ...DOKUMENT,
      titel: null,
      status: 'VERARBEITUNG',
      dateiname: 'Scan_Rechnung-2026.pdf',
    });
    await oeffnen();
    const titel = element.querySelector('[data-test=titel]')!;
    expect(titel.querySelector('bdi')!.textContent).toBe('Scan_Rechnung-2026.pdf');
    expect(titel.querySelector('[data-test=titel-platzhalter]')!.getAttribute('title')).toBe(
      'Scan_Rechnung-2026.pdf',
    );
    expect(element.querySelector('[data-test=dateiname]')!.textContent).toBe(
      'Scan_Rechnung-2026.pdf',
    );
  });

  it('bietet ohne PDF/A-Fassung das Original an', async () => {
    port.pdfa = throwError(() => new HttpErrorResponse({ status: 404 }));
    await oeffnen();
    expect(element.querySelector('[data-test=vorschau-fehlt]')!.textContent).toBe(
      TEXTE.dokument.vorschauFehlt,
    );
    const offen = vi.spyOn(window, 'open').mockReturnValue(null);
    knopf(TEXTE.dokument.originalHerunterladen).click();
    expect(offen).toHaveBeenCalledWith(
      'https://s3.example/original',
      '_blank',
      'noopener,noreferrer',
    );
  });

  it('ermöglicht erneutes Laden nach einem Fehler beim Dokumentabruf', async () => {
    port.dokument = throwError(() => new HttpErrorResponse({ status: 503 }));
    await oeffnen();
    expect(element.querySelector('[role=alert]')?.textContent).toBe(TEXTE.dokument.fehlerAllgemein);

    port.dokument = of(DOKUMENT);
    element.querySelector<HTMLButtonElement>('[data-test=erneut-laden]')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(element.querySelector('[data-test=titel]')).not.toBeNull();
    expect(port.gelesen).toBe(2);
  });

  it('ermöglicht erneutes Laden einer fehlgeschlagenen Vorschau', async () => {
    port.pdfa = throwError(() => new HttpErrorResponse({ status: 503 }));
    await oeffnen();

    port.pdfa = of({ url: 'https://s3.example/pdfa?sig=1', gueltigBis: zukunft() });
    element.querySelector<HTMLButtonElement>('[data-test=vorschau-neu-laden]')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(element.querySelector('[data-test=vorschau-neu-laden]')).toBeNull();
    expect(port.ansichten).toEqual(['vorschau', 'vorschau']);
  });

  it('verlinkt keine nicht-http(s)-Adresse als PDF-Vorschau', async () => {
    port.pdfa = of({ url: 'javascript:alert(1)', gueltigBis: zukunft() });
    await oeffnen();

    expect(element.querySelector('[data-test=pdf-laden]')).toBeNull();
    expect(element.querySelector('[data-test=vorschau-neu-laden]')).not.toBeNull();
  });

  it('bietet nach dem Abruf einen Link als eigene Geste an, falls der Browser das Fenster blockiert', async () => {
    port.pdfa = throwError(() => new HttpErrorResponse({ status: 404 }));
    await oeffnen();
    expect(element.querySelector('[data-test=datei-bereit]')).toBeNull();
    vi.spyOn(window, 'open').mockReturnValue(null);
    knopf(TEXTE.dokument.originalHerunterladen).click();
    await fixture.whenStable();
    const link = element.querySelector<HTMLAnchorElement>('[data-test=datei-bereit] a')!;
    expect(link.href).toBe('https://s3.example/original');
    expect(link.rel).toBe('noopener noreferrer');
    expect(link.target).toBe('_blank');
    expect(port.aufrufe).toEqual(['pdfa', 'original']);
  });

  it('holt beim Klick auf den Ersatz-Link eine abgelaufene Adresse neu', async () => {
    port.pdfa = throwError(() => new HttpErrorResponse({ status: 404 }));
    port.original = () => ({
      url: 'https://s3.example/original',
      gueltigBis: new Date(Date.now() - 1000).toISOString(),
    });
    await oeffnen();
    vi.spyOn(window, 'open').mockReturnValue(null);
    knopf(TEXTE.dokument.originalHerunterladen).click();
    await fixture.whenStable();
    element.querySelector<HTMLAnchorElement>('[data-test=datei-bereit] a')!.click();
    expect(port.aufrufe).toEqual(['pdfa', 'original', 'original']);
  });

  it('öffnet den PDF-Link mit noch gültiger Adresse ohne neuen Abruf', async () => {
    await oeffnen();
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    const klick = new MouseEvent('click', { cancelable: true, bubbles: true });
    element.querySelector<HTMLAnchorElement>('[data-test=pdf-laden]')!.dispatchEvent(klick);
    expect(klick.defaultPrevented).toBe(false);
    expect(open).not.toHaveBeenCalled();
    expect(port.ansichten).toEqual(['vorschau']);
  });

  it('holt beim Klick auf den PDF-Link eine abgelaufene Adresse neu und öffnet die frische', async () => {
    port.pdfa = of({
      url: 'https://s3.example/pdfa?sig=alt',
      gueltigBis: new Date(Date.now() - 1000).toISOString(),
    });

    await oeffnen();
    port.pdfa = of({ url: 'https://s3.example/pdfa?sig=neu', gueltigBis: zukunft() });
    const fenster = { opener: 'x', location: { href: '' }, close: vi.fn() };
    const open = vi.spyOn(window, 'open').mockReturnValue(fenster as unknown as Window);
    const klick = new MouseEvent('click', { cancelable: true, bubbles: true });
    element.querySelector<HTMLAnchorElement>('[data-test=pdf-laden]')!.dispatchEvent(klick);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(klick.defaultPrevented).toBe(true);
    // Fenster noch in der Klick-Geste geöffnet, ohne noopener-Merkmal (sonst immer null), Verbindung zur PWA getrennt
    expect(open).toHaveBeenCalledWith('', '_blank');
    expect(fenster.opener).toBeNull();
    expect(fenster.location.href).toBe('https://s3.example/pdfa?sig=neu');
    expect(element.querySelector('[data-test=pdf-bereit]')).toBeNull();
    expect(port.ansichten).toEqual(['vorschau', 'vorschau']);
  });

  it('behält die angeforderte Seite beim Erneuern des PDF-Links', async () => {
    port.pdfa = of({ url: 'https://s3.example/pdfa?sig=alt', gueltigBis: '2020-01-01T00:00:00Z' });
    await oeffnen();
    fixture.componentRef.setInput('seite', '2');
    await fixture.whenStable();
    port.pdfa = of({ url: 'https://s3.example/pdfa?sig=neu', gueltigBis: zukunft() });
    const fenster = { opener: null, location: { href: '' }, close: vi.fn() };
    vi.spyOn(window, 'open').mockReturnValue(fenster as unknown as Window);
    element.querySelector<HTMLAnchorElement>('[data-test=pdf-laden]')!
      .dispatchEvent(new MouseEvent('click', { cancelable: true, bubbles: true }));
    await fixture.whenStable();
    expect(fenster.location.href).toBe('https://s3.example/pdfa?sig=neu#page=2');
  });

  it('lässt beim Neuholen der PDF-Adresse nur eine Anfrage laufen und zeigt einen Fehler ohne die Vorschau zu verlieren', async () => {
    port.pdfa = of({
      url: 'https://s3.example/pdfa?sig=alt',
      gueltigBis: new Date(Date.now() - 1000).toISOString(),
    });
    await oeffnen();
    port.pdfa = new Subject<DownloadAdresse>();
    const fenster = { opener: 'x', location: { href: '' }, close: vi.fn() };
    vi.spyOn(window, 'open').mockReturnValue(fenster as unknown as Window);
    const link = element.querySelector<HTMLAnchorElement>('[data-test=pdf-laden]')!;
    link.click();
    link.click();
    expect(port.ansichten).toEqual(['vorschau', 'vorschau']);
    (port.pdfa as Subject<DownloadAdresse>).error(new HttpErrorResponse({ status: 0 }));
    await fixture.whenStable();
    expect(element.querySelector('[data-test=datei-fehler]')).not.toBeNull();
    expect(element.querySelector('[data-test=download]')).not.toBeNull();
  });

  it('weist nur dann auf den zweiten Klick hin, wenn der Browser das Fenster wirklich sperrt', async () => {
    port.pdfa = of({
      url: 'https://s3.example/pdfa?sig=alt',
      gueltigBis: new Date(Date.now() - 1000).toISOString(),
    });
    await oeffnen();
    port.pdfa = of({ url: 'https://s3.example/pdfa?sig=neu', gueltigBis: zukunft() });
    vi.spyOn(window, 'open').mockReturnValue(null);
    element.querySelector<HTMLAnchorElement>('[data-test=pdf-laden]')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(element.querySelector('[data-test=pdf-bereit]')).not.toBeNull();
    expect(port.ansichten).toEqual(['vorschau']);
  });

  it('schliesst das leere Fenster, wenn der Neuabruf der PDF-Adresse scheitert', async () => {
    port.pdfa = of({
      url: 'https://s3.example/pdfa?sig=alt',
      gueltigBis: new Date(Date.now() - 1000).toISOString(),
    });
    await oeffnen();
    port.pdfa = throwError(() => new HttpErrorResponse({ status: 503 }));
    const fenster = { opener: 'x', location: { href: '' }, close: vi.fn() };
    vi.spyOn(window, 'open').mockReturnValue(fenster as unknown as Window);
    element.querySelector<HTMLAnchorElement>('[data-test=pdf-laden]')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fenster.close).toHaveBeenCalled();
    expect(element.querySelector('[data-test=datei-fehler]')).not.toBeNull();
    expect(element.querySelector('[data-test=pdf-bereit]')).toBeNull();
  });

  it('sagt bei 404 nur, dass es das Dokument nicht gibt oder es nicht sichtbar ist (Req 2.3)', async () => {
    port.dokument = throwError(() => new HttpErrorResponse({ status: 404 }));
    await oeffnen();
    expect(element.querySelector('[data-test=fehler]')!.textContent).toBe(
      TEXTE.dokument.nichtGefunden,
    );
    expect(port.aufrufe).toEqual([]);
  });

  it('holt die Datei-Adresse erst, wenn das Dokument geladen ist', async () => {
    port.dokument = new Subject<Dokument>();
    await oeffnen();
    expect(element.querySelector('[data-test=titel]')).toBeNull();
    expect(port.aufrufe).toEqual([]);
  });

  describe('Metadaten bearbeiten (Req 10.1)', () => {
    const bearbeiten = () => element.querySelector<HTMLButtonElement>('[data-test=bearbeiten]');

    it('zeigt den Knopf nur mit A6 und in einem änderbaren Status', async () => {
      await oeffnen();
      expect(bearbeiten()!.textContent!.trim()).toBe(TEXTE.dokument.bearbeiten.knopf);
      fixture.destroy();
      rolle.set('treuhaender');
      await oeffnen();
      expect(bearbeiten()).toBeNull();
      fixture.destroy();
      rolle.set('inhaber');
      port.dokument = of({ ...DOKUMENT, status: 'ARCHIVIERUNG' });
      await oeffnen();
      expect(bearbeiten()).toBeNull();
    });

    it('speichert mit Grund und zeigt danach das geänderte Dokument mit der neuen Fassung', async () => {
      port.geaendert = of({
        ...DOKUMENT,
        titel: 'Rechnung 2026-003 korrigiert',
        version: 2,
        versionen: [
          {
            version: 1,
            konfigurationVersion: 1,
            zeit: '2026-11-09T09:30:00Z',
            geaendertVon: 'sub-inhaber',
            grund: 'Tippfehler',
          },
        ],
      });
      await oeffnen();
      bearbeiten()!.click();
      await fixture.whenStable();
      const titel = element.querySelector<HTMLInputElement>('#bearbeiten-titel')!;
      titel.value = 'Rechnung 2026-003 korrigiert';
      titel.dispatchEvent(new Event('input'));
      const grund = element.querySelector<HTMLTextAreaElement>('#bearbeiten-grund')!;
      grund.value = 'Tippfehler';
      grund.dispatchEvent(new Event('input'));
      element.querySelector<HTMLButtonElement>('[data-test=speichern]')!.click();
      await fixture.whenStable();
      expect(port.aenderungen).toEqual([
        { version: 1, aenderung: { titel: 'Rechnung 2026-003 korrigiert', grund: 'Tippfehler' } },
      ]);
      expect(element.querySelector('[data-test=titel]')!.textContent).toBe(
        'Rechnung 2026-003 korrigiert',
      );
      expect(element.querySelector('[data-test=meldung]')!.textContent!.trim()).toBe(
        TEXTE.dokument.gespeichert,
      );
      expect(element.querySelector('[data-test=bearbeiten-formular]')).toBeNull();
      const version = element.querySelector('[data-test=version]')!.textContent!;
      expect(version).toContain(TEXTE.dokument.version(1));
      expect(version).toContain('09.11.2026');
      expect(version).toContain(`${TEXTE.dokument.geaendertVon} ${TEXTE.dokument.sie}`);
      expect(version).toContain('Tippfehler');
    });

    it('lädt bei einem Konflikt neu und sagt warum', async () => {
      port.geaendert = throwError(
        () => new HttpErrorResponse({ status: 412, error: { fehlercode: 'VERSION_KONFLIKT' } }),
      );
      await oeffnen();
      bearbeiten()!.click();
      await fixture.whenStable();
      const titel = element.querySelector<HTMLInputElement>('#bearbeiten-titel')!;
      titel.value = 'Neu';
      titel.dispatchEvent(new Event('input'));
      const grund = element.querySelector<HTMLTextAreaElement>('#bearbeiten-grund')!;
      grund.value = 'Korrektur';
      grund.dispatchEvent(new Event('input'));
      element.querySelector<HTMLButtonElement>('[data-test=speichern]')!.click();
      await fixture.whenStable();
      expect(port.gelesen).toBe(2);
      expect(element.querySelector('[data-test=meldung]')!.textContent!.trim()).toBe(
        TEXTE.dokument.bearbeiten.fehlercode['VERSION_KONFLIKT'],
      );
      expect(element.querySelector('[data-test=bearbeiten-formular]')).toBeNull();
    });

    describe('Art aus dem Eingangskorb (?art=, Review #106)', () => {
      const LOHN: Dokumentart = {
        ...ART,
        code: 'LOHNABRECHNUNG',
        name: 'Lohnabrechnung',
        gruppen: [],
        pflichtfelder: ['zusatz.periode'],
        zusatzSchema: {
          type: 'object',
          properties: { periode: { type: 'string' } },
          required: ['periode'],
        },
        vertraulichkeit: 'PERSONAL',
      };
      const IM_KORB: Dokument = {
        ...DOKUMENT,
        art: null,
        status: 'EINGANGSKORB',
        zusatz: null,
        vorschlag: { art: 'LOHNABRECHNUNG' },
      };

      it('öffnet das Formular mit der Art als Artwechsel und sendet sie mit den Zusatzfeldern', async () => {
        port.arten = [ART, LOHN];
        port.dokument = of(IM_KORB);
        await oeffnen('LOHNABRECHNUNG');
        expect(element.querySelector<HTMLSelectElement>('#bearbeiten-art')!.value).toBe(
          'LOHNABRECHNUNG',
        );
        expect(element.querySelector('[data-test=art-vorbelegt]')!.textContent).toBe(
          TEXTE.dokument.bearbeiten.artVorbelegt('Lohnabrechnung'),
        );
        const periode = element.querySelector<HTMLInputElement>('#bearbeiten-zusatz-periode')!;
        periode.value = '2026-10';
        periode.dispatchEvent(new Event('input'));
        element.querySelector<HTMLButtonElement>('[data-test=speichern]')!.click();
        await fixture.whenStable();
        expect(port.aenderungen).toEqual([
          { version: 1, aenderung: { art: 'LOHNABRECHNUNG', zusatz: { periode: '2026-10' } } },
        ]);
      });

      it('übernimmt nach «Abbrechen» die Art nicht mehr', async () => {
        port.arten = [ART, LOHN];
        port.dokument = of(IM_KORB);
        await oeffnen('LOHNABRECHNUNG');
        knopf(TEXTE.dokument.bearbeiten.abbrechen).click();
        await fixture.whenStable();
        bearbeiten()!.click();
        await fixture.whenStable();
        expect(element.querySelector<HTMLSelectElement>('#bearbeiten-art')!.value).toBe('');
        expect(element.querySelector('[data-test=art-vorbelegt]')).toBeNull();
      });

      it('übergeht eine unbekannte oder inaktive Art und öffnet das Formular nicht', async () => {
        port.arten = [ART, { ...LOHN, aktiv: false }];
        port.dokument = of(IM_KORB);
        await oeffnen('LOHNABRECHNUNG');
        expect(element.querySelector('[data-test=bearbeiten-formular]')).toBeNull();
        fixture.destroy();
        await oeffnen('GIBT_ES_NICHT');
        expect(element.querySelector('[data-test=bearbeiten-formular]')).toBeNull();
      });

      it('übernimmt nach einem Konflikt (412) und Neuladen die Art nicht mehr (Review #107)', async () => {
        port.arten = [ART, LOHN];
        port.dokument = of(IM_KORB);
        port.geaendert = throwError(
          () => new HttpErrorResponse({ status: 412, error: { fehlercode: 'VERSION_KONFLIKT' } }),
        );
        await oeffnen('LOHNABRECHNUNG');
        const periode = element.querySelector<HTMLInputElement>('#bearbeiten-zusatz-periode')!;
        periode.value = '2026-10';
        periode.dispatchEvent(new Event('input'));
        port.dokument = of({ ...IM_KORB, art: 'AUSGANGSRECHNUNG', version: 2 });
        element.querySelector<HTMLButtonElement>('[data-test=speichern]')!.click();
        await fixture.whenStable();
        expect(port.gelesen).toBe(2);
        expect(element.querySelector('[data-test=bearbeiten-formular]')).toBeNull();
        bearbeiten()!.click();
        await fixture.whenStable();
        expect(element.querySelector<HTMLSelectElement>('#bearbeiten-art')!.value).toBe(
          'AUSGANGSRECHNUNG',
        );
        expect(element.querySelector('[data-test=art-vorbelegt]')).toBeNull();
      });

      it('öffnet ohne Recht A6 kein Formular', async () => {
        rolle.set('treuhaender');
        port.arten = [ART, LOHN];
        port.dokument = of(IM_KORB);
        await oeffnen('LOHNABRECHNUNG');
        expect(element.querySelector('[data-test=bearbeiten-formular]')).toBeNull();
        expect(bearbeiten()).toBeNull();
      });

      it('öffnet bei der gespeicherten Art kein Formular und zeigt keinen Hinweis', async () => {
        port.arten = [ART, LOHN];
        port.dokument = of({ ...IM_KORB, art: 'LOHNABRECHNUNG', zusatz: { periode: '2026-10' } });
        await oeffnen('LOHNABRECHNUNG');
        expect(element.querySelector('[data-test=bearbeiten-formular]')).toBeNull();
        bearbeiten()!.click();
        await fixture.whenStable();
        expect(element.querySelector<HTMLSelectElement>('#bearbeiten-art')!.value).toBe(
          'LOHNABRECHNUNG',
        );
        expect(element.querySelector('[data-test=art-vorbelegt]')).toBeNull();
      });

      it('öffnet in einem Status ausserhalb der änderbaren kein Formular', async () => {
        port.arten = [ART, LOHN];
        port.dokument = of({ ...IM_KORB, status: 'ARCHIVIERUNG' });
        await oeffnen('LOHNABRECHNUNG');
        expect(element.querySelector('[data-test=bearbeiten-formular]')).toBeNull();
        expect(bearbeiten()).toBeNull();
      });
    });
  });

  it('nennt fremde Änderer mit Kennung und ohne Fassungen einen Hinweis', async () => {
    await oeffnen();
    expect(element.querySelector('[data-test=versionen]')!.textContent).toContain(
      TEXTE.dokument.versionenLeer,
    );
    fixture.destroy();
    port.dokument = of({
      ...DOKUMENT,
      versionen: [
        {
          version: 1,
          konfigurationVersion: 1,
          zeit: '2026-11-09T09:30:00Z',
          geaendertVon: 'sub-anderer',
          grund: null,
        },
      ],
    });
    await oeffnen();
    const version = element.querySelector('[data-test=version]')!.textContent!;
    expect(version).toContain('sub-anderer');
    expect(version).toContain(TEXTE.dokument.ohneGrund);
  });

  it('zeigt auf schmalen Bildschirmen das Vorschaubild der ersten Seite und gibt es wieder frei', async () => {
    const erstellen = vi.fn(() => 'blob:bild-1');
    const freigeben = vi.fn();
    Object.assign(URL, { createObjectURL: erstellen, revokeObjectURL: freigeben });
    port.dokument = of({ ...DOKUMENT, seiten: 2 });
    await oeffnen();
    const bild = element.querySelector<HTMLImageElement>('[data-test=bild]')!;
    expect(bild.getAttribute('src')).toBe('blob:bild-1');
    expect(bild.alt).toBe(TEXTE.dokument.bild('Rechnung 2026-003'));
    expect(element.querySelector('[data-test=pdf-laden]')).toBeNull();
    expect(port.bilder).toEqual([1]);
    fixture.destroy();
    expect(freigeben).toHaveBeenCalledWith('blob:bild-1');
  });

  it('öffnet die angeforderte PDF-Seite auch in der mobilen Vorschau', async () => {
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:bild'), revokeObjectURL: vi.fn() });
    port.dokument = of({ ...DOKUMENT, seiten: 3 });
    fixture = TestBed.createComponent(DokumentAnsicht);
    fixture.componentRef.setInput('id', 'd1');
    fixture.componentRef.setInput('seite', '2');
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
    expect(port.bilder).toEqual([2]);
    expect(element.querySelector<HTMLImageElement>('[data-test=bild]')!.alt)
      .toBe(TEXTE.dokument.bildSeite('Rechnung 2026-003', 2));
    fixture.componentRef.setInput('seite', '3');
    await fixture.whenStable();
    expect(port.bilder).toEqual([2, 3]);
  });

  it('hängt die angeforderte Seite an den PDF-Vorschaulink', async () => {
    fixture = TestBed.createComponent(DokumentAnsicht);
    fixture.componentRef.setInput('id', 'd1');
    fixture.componentRef.setInput('seite', '2');
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
    expect(element.querySelector<HTMLAnchorElement>('[data-test=pdf-laden]')!.href)
      .toBe('https://s3.example/pdfa?sig=1#page=2');
  });

  it('bestellt den Abruf des Vorschaubilds beim Verlassen ab und legt keine Object-URL mehr an', async () => {
    const erstellen = vi.fn(() => 'blob:spaet');
    Object.assign(URL, { createObjectURL: erstellen, revokeObjectURL: vi.fn() });
    const blob = new Subject<Blob>();
    port.bild = blob;
    port.dokument = of({ ...DOKUMENT, seiten: 2 });
    await oeffnen();
    expect(port.bilder).toEqual([1]);
    fixture.destroy();
    blob.next(new Blob(['png'], { type: 'image/png' }));
    expect(erstellen).not.toHaveBeenCalled();
  });

  it('holt ohne Seiten kein Vorschaubild', async () => {
    await oeffnen();
    expect(port.bilder).toEqual([]);
    expect(element.querySelector('[data-test=pdf-laden]')).not.toBeNull();
  });

  it('lässt nur http(s)-Adressen in Vorschau und Link', () => {
    expect(sichereAdresse('javascript' + ':void(0)')).toBeNull();
    expect(sichereAdresse('data:application/pdf;base64,AA')).toBeNull();
    expect(sichereAdresse('kaputt')).toBeNull();
    expect(sichereAdresse('https://s3.example/x')).toBe('https://s3.example/x');
  });
});
