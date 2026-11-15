import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { Export as ExportEintrag, ExporteService } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Export } from './export';
import {
  ABFRAGE_MS,
  fehlerText,
  inArbeit,
  jahrGueltig,
  standardJahr,
  weiterFragen,
} from './export-logik';

const FERTIG: ExportEintrag = {
  id: 'e1',
  geschaeftsjahr: 2025,
  status: 'FERTIG',
  erstellt: '2026-11-13T08:00:00Z',
  fertig: '2026-11-13T08:05:00Z',
  gueltigBis: '2026-11-14T08:05:00Z',
  anzahl: 1234,
  groesse: 5 * 1024 * 1024,
  sha256: 'a'.repeat(64),
};

describe('Export: Jahresexport anfordern und herunterladen (Spec 02, Aufgabe 6.5)', () => {
  let fixture: ComponentFixture<Export>;
  let element: HTMLElement;
  const service = {
    apiV1ExporteGet: vi.fn(),
    apiV1ExportePost: vi.fn(),
    apiV1ExporteIdGet: vi.fn(),
  };

  async function starten(liste: ExportEintrag[], stabil = true) {
    service.apiV1ExporteGet.mockReset().mockReturnValue(of(liste));
    service.apiV1ExportePost.mockReset();
    service.apiV1ExporteIdGet.mockReset();
    TestBed.configureTestingModule({
      imports: [Export],
      providers: [{ provide: ExporteService, useValue: service }],
    });
    fixture = TestBed.createComponent(Export);
    element = fixture.nativeElement as HTMLElement;
    if (stabil) {
      await fixture.whenStable();
    } else {
      fixture.detectChanges();
    }
  }

  const knopf = (text: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    )!;

  afterEach(() => vi.useRealTimers());

  it('zeigt fertige Exporte mit Zahl, Grösse, Frist und Prüfsumme', async () => {
    await starten([FERTIG]);
    const eintrag = element.querySelector('[data-test=export]')!;
    expect(eintrag.textContent).toContain(TEXTE.export.status['FERTIG']);
    expect(eintrag.textContent).toContain("1'234 Belege");
    expect(eintrag.textContent).toContain('5.0 MiB');
    expect(eintrag.textContent).toContain('a'.repeat(64));
    expect(knopf(TEXTE.export.herunterladen(2025))).toBeTruthy();
  });

  it('fordert das gewählte Geschäftsjahr an und lädt die Liste neu', async () => {
    await starten([]);
    expect(element.textContent).toContain(TEXTE.export.keine);
    const eingabe = element.querySelector<HTMLInputElement>('[data-test=jahr]')!;
    eingabe.value = '2024';
    eingabe.dispatchEvent(new Event('input'));
    service.apiV1ExportePost.mockReturnValue(of({ ...FERTIG, status: 'OFFEN' }));
    service.apiV1ExporteGet.mockReturnValue(
      of([{ ...FERTIG, id: 'e2', geschaeftsjahr: 2024, status: 'OFFEN' }]),
    );
    knopf(TEXTE.export.anfordern).click();
    await fixture.whenStable();
    expect(service.apiV1ExportePost).toHaveBeenCalledWith({ geschaeftsjahr: 2024 });
    expect(element.textContent).toContain(TEXTE.export.angefordert);
    expect(element.textContent).toContain(TEXTE.export.status['OFFEN']);
  });

  it('sperrt den Knopf bei einem ungültigen Jahr', async () => {
    await starten([]);
    const eingabe = element.querySelector<HTMLInputElement>('[data-test=jahr]')!;
    eingabe.value = '1899';
    eingabe.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(knopf(TEXTE.export.anfordern).disabled).toBe(true);
  });

  it('meldet einen laufenden Export als Konflikt', async () => {
    await starten([]);
    service.apiV1ExportePost.mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ status: 409, error: { fehlercode: 'STATUS_KONFLIKT' } }),
      ),
    );
    knopf(TEXTE.export.anfordern).click();
    await fixture.whenStable();
    expect(element.querySelector('[role=alert]')!.textContent).toContain(
      TEXTE.export.fehlercode['STATUS_KONFLIKT'],
    );
  });

  it('fragt nach, solange ein Export in Arbeit ist', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    await starten([{ ...FERTIG, status: 'OFFEN' }], false);
    expect(service.apiV1ExporteGet).toHaveBeenCalledTimes(1);
    service.apiV1ExporteGet.mockReturnValue(of([FERTIG]));
    vi.advanceTimersByTime(ABFRAGE_MS);
    expect(service.apiV1ExporteGet).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(3 * ABFRAGE_MS);
    expect(service.apiV1ExporteGet).toHaveBeenCalledTimes(2);
  });

  it('fragt nach einem vorübergehenden Fehler weiter nach und behält die Liste', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    await starten([{ ...FERTIG, status: 'OFFEN' }], false);
    service.apiV1ExporteGet.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 502 })),
    );
    vi.advanceTimersByTime(ABFRAGE_MS);
    fixture.detectChanges();
    expect(service.apiV1ExporteGet).toHaveBeenCalledTimes(2);
    expect(element.querySelector('[role=alert]')!.textContent).toContain(
      TEXTE.export.fehlerNichtErreichbar,
    );
    expect(element.querySelector('[data-test=export]')!.textContent).toContain(
      TEXTE.export.status['OFFEN'],
    );
    service.apiV1ExporteGet.mockReturnValue(of([FERTIG]));
    vi.advanceTimersByTime(ABFRAGE_MS);
    fixture.detectChanges();
    expect(service.apiV1ExporteGet).toHaveBeenCalledTimes(3);
    expect(element.querySelector('[role=alert]')).toBeNull();
    expect(element.querySelector('[data-test=export]')!.textContent).toContain(
      TEXTE.export.status['FERTIG'],
    );
  });

  it('hört bei abgelaufener Sitzung auf nachzufragen', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    await starten([{ ...FERTIG, status: 'OFFEN' }], false);
    service.apiV1ExporteGet.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 401 })),
    );
    vi.advanceTimersByTime(ABFRAGE_MS);
    vi.advanceTimersByTime(3 * ABFRAGE_MS);
    expect(service.apiV1ExporteGet).toHaveBeenCalledTimes(2);
  });

  it('zeigt abgelaufene und fehlgeschlagene Exporte ohne Download', async () => {
    await starten([
      { ...FERTIG, id: 'a', status: 'ABGELAUFEN' },
      { ...FERTIG, id: 'f', status: 'FEHLER' },
    ]);
    expect(element.textContent).toContain(TEXTE.export.abgelaufenHinweis);
    expect(element.textContent).toContain(TEXTE.export.fehlerHinweis);
    expect(element.querySelectorAll('[data-test=export] button')).toHaveLength(1);
    expect(element.querySelector('[data-test=fehler-id]')).toBeNull();
  });

  const FEHLER_ID = '14243b3c-19bd-4800-a82b-3db7506d7357';

  it('zeigt bei einem unterbrochenen Export den Satz zur Unterbrechung und die Fehler-ID', async () => {
    await starten([
      { ...FERTIG, id: 'v', status: 'FEHLER', fehlerId: FEHLER_ID, fehlerArt: 'VERWAIST' },
    ]);
    expect(element.querySelector('[data-test=fehler-art]')!.textContent).toContain(
      TEXTE.export.fehlerArt['VERWAIST'],
    );
    expect(TEXTE.export.fehlerArt['VERWAIST']).toContain('unterbrochen');
    expect(element.querySelector('[data-test=fehler-id]')!.textContent).toBe(FEHLER_ID);
    expect(element.textContent).toContain(`${TEXTE.export.fehlerId}: ${FEHLER_ID}`);
  });

  it('zeigt je Fehlerart einen eigenen Satz, ohne Art den allgemeinen Hinweis', async () => {
    await starten([
      { ...FERTIG, id: 'z', status: 'FEHLER', fehlerId: FEHLER_ID, fehlerArt: 'ZEITGRENZE' },
      { ...FERTIG, id: 'g', status: 'FEHLER', fehlerId: FEHLER_ID, fehlerArt: 'FEHLGESCHLAGEN' },
      { ...FERTIG, id: 'a', status: 'FEHLER' },
    ]);
    const saetze = [...element.querySelectorAll('[data-test=fehler-art]')].map((e) =>
      e.textContent!.trim(),
    );
    expect(saetze).toEqual([
      TEXTE.export.fehlerArt['ZEITGRENZE'],
      TEXTE.export.fehlerArt['FEHLGESCHLAGEN'],
      TEXTE.export.fehlerHinweis,
    ]);
    expect(element.querySelectorAll('[data-test=fehler-id]')).toHaveLength(2);
  });

  it('fordert einen gescheiterten Export mit «Erneut anfordern» für dasselbe Jahr neu an', async () => {
    await starten([
      {
        ...FERTIG,
        id: 'v',
        geschaeftsjahr: 2026,
        status: 'FEHLER',
        fehlerId: FEHLER_ID,
        fehlerArt: 'VERWAIST',
      },
    ]);
    service.apiV1ExportePost.mockReturnValue(of({ ...FERTIG, id: 'neu', status: 'OFFEN' }));
    const erneut = knopf(TEXTE.export.erneutAnfordern);
    expect(erneut.getAttribute('aria-label')).toBe(TEXTE.export.erneutAnfordernFuer(2026));
    erneut.click();
    await fixture.whenStable();
    expect(service.apiV1ExportePost).toHaveBeenCalledWith({ geschaeftsjahr: 2026 });
    expect(element.textContent).toContain(TEXTE.export.angefordert);
  });

  it('zeigt eine Meldung, wenn das Archiv beim Neustart nicht erreichbar ist (502, 503, 504)', () => {
    for (const status of [502, 503, 504]) {
      expect(fehlerText(new HttpErrorResponse({ status }))).toBe(
        TEXTE.export.fehlerNichtErreichbar,
      );
    }
  });

  it('Logik: Vorschlag, Grenzen, Abfrage und Fehlertexte', () => {
    expect(standardJahr(new Date('2026-11-13T12:00:00Z'))).toBe(2025);
    expect(jahrGueltig(1900)).toBe(true);
    expect(jahrGueltig(2999)).toBe(true);
    expect(jahrGueltig(3000)).toBe(false);
    expect(jahrGueltig(2025.5)).toBe(false);
    expect(jahrGueltig(null)).toBe(false);
    expect(inArbeit([{ ...FERTIG, status: 'OFFEN' }])).toBe(true);
    expect(inArbeit([FERTIG])).toBe(false);
    expect(inArbeit(null)).toBe(false);
    expect(
      fehlerText(new HttpErrorResponse({ status: 403, error: { fehlercode: 'NICHT_ERLAUBT' } })),
    ).toBe(TEXTE.export.fehlercode['NICHT_ERLAUBT']);
    expect(fehlerText(new HttpErrorResponse({ status: 401 }))).toBe(TEXTE.export.fehlerSitzung);
    expect(fehlerText(new HttpErrorResponse({ status: 0 }))).toBe(TEXTE.export.fehlerVerbindung);
    expect(fehlerText(new Error('x'))).toBe(TEXTE.export.fehlerVerbindung);
    expect(fehlerText(new HttpErrorResponse({ status: 500 }))).toBe(TEXTE.export.fehlerAllgemein);
    expect(weiterFragen(new HttpErrorResponse({ status: 502 }))).toBe(true);
    expect(weiterFragen(new Error('Netz'))).toBe(true);
    expect(weiterFragen(new HttpErrorResponse({ status: 401 }))).toBe(false);
    expect(weiterFragen(new HttpErrorResponse({ status: 403 }))).toBe(false);
  });
});
