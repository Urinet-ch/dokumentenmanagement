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
      TEXTE.export.fehlerAllgemein,
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
    expect(element.querySelectorAll('[data-test=export] button')).toHaveLength(0);
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
