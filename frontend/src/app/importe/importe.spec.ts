import { HttpEventType, HttpResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';
import { ImporteService } from '../gemeinsam/api';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { Importe } from './importe';
import { MAX_GROESSE } from './import-logik';

describe('Importe: Upload und Liste (Aufgabe 4.4)', () => {
  let fixture: ComponentFixture<Importe>;
  let element: HTMLElement;
  let upload: Subject<unknown>;
  const service = {
    apiV1ImporteGet: vi.fn(),
    apiV1ImporteCrmPost: vi.fn(),
  };

  beforeEach(async () => {
    upload = new Subject();
    service.apiV1ImporteGet.mockReset().mockReturnValue(
      of({
        eintraege: [
          {
            id: 'l1',
            art: 'kontakt',
            vollexport: true,
            dateiname: 'kontakte.csv',
            zeilen: 1234,
            status: 'VORSCHAU',
            erstellt: '2026-11-11T08:00:00Z',
          },
        ],
        gesamt: 1,
      }),
    );
    service.apiV1ImporteCrmPost.mockReset().mockReturnValue(upload);
    TestBed.configureTestingModule({
      imports: [Importe],
      providers: [provideRouter([]), { provide: ImporteService, useValue: service }],
    });
    fixture = TestBed.createComponent(Importe);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  async function waehlen(datei: File) {
    const eingabe = element.querySelector<HTMLInputElement>('input[type=file]')!;
    Object.defineProperty(eingabe, 'files', { value: [datei], configurable: true });
    eingabe.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  const knopf = (text: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === text)!;

  it('zeigt die bisherigen Läufe mit Status und Zählern', () => {
    const lauf = element.querySelector('[data-test=lauf]')!;
    expect(lauf.querySelector('a')!.getAttribute('href')).toBe('/importe/l1');
    expect(lauf.textContent).toContain(TEXTE.importe.status['VORSCHAU']);
    expect(lauf.textContent).toContain("1'234 Datenzeilen");
    expect(lauf.textContent).toContain(TEXTE.importe.vollexportMarke);
  });

  it('lehnt Dateien über 5 MiB ab, ohne sie hochzuladen (Req 13.10)', async () => {
    const gross = new File(['x'], 'gross.csv', { type: 'text/csv' });
    Object.defineProperty(gross, 'size', { value: MAX_GROESSE + 1 });
    await waehlen(gross);
    expect(element.querySelector('[role=alert]')!.textContent).toContain('grösser als 5 MiB');
    expect(knopf(TEXTE.importe.hochladen).disabled).toBe(true);
    expect(service.apiV1ImporteCrmPost).not.toHaveBeenCalled();
  });

  it('lädt mit Importart und Vollexport hoch und öffnet danach den Lauf (Req 13.7)', async () => {
    const router = TestBed.inject(Router);
    const navigieren = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const datei = new File(['Name\nA'], 'kontakte.csv', { type: 'text/csv' });
    await waehlen(datei);
    element.querySelector<HTMLInputElement>('[data-test=vollexport] input')!.click();
    await fixture.whenStable();
    knopf(TEXTE.importe.hochladen).click();
    await fixture.whenStable();
    expect(service.apiV1ImporteCrmPost).toHaveBeenCalledWith('kontakt', 'true', datei, 'events', true);

    upload.next({ type: HttpEventType.UploadProgress, loaded: 1, total: 2 });
    await fixture.whenStable();
    expect(element.querySelector('[role=status]')!.textContent).toContain('50 %');
    upload.next(new HttpResponse({ status: 202, body: { lauf: { id: 'neu' } } }));
    expect(navigieren).toHaveBeenCalledWith(['/importe', 'neu']);
  });

  it('kennt bei Verkaufschancen keinen Vollexport', async () => {
    const auswahl = element.querySelector<HTMLSelectElement>('[data-test=art]')!;
    auswahl.value = 'verkaufschance';
    auswahl.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(element.querySelector('[data-test=vollexport]')).toBeNull();
    await waehlen(new File(['Titel\nA'], 'chancen.csv'));
    knopf(TEXTE.importe.hochladen).click();
    expect(service.apiV1ImporteCrmPost).toHaveBeenCalledWith('verkaufschance', 'false', expect.any(File), 'events', true);
  });
});
