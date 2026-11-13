import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { ProtokollService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { Protokoll } from './protokoll';

describe('Zugriffsprotokoll: Seite (Aufgabe 5.1)', () => {
  let fixture: ComponentFixture<Protokoll>;
  let element: HTMLElement;
  const service = {
    apiV1ProtokollGet: vi.fn(),
    apiV1ProtokollBenutzerGet: vi.fn(),
    apiV1ProtokollBenutzerBenutzerAustrittPost: vi.fn(),
  };

  beforeEach(async () => {
    service.apiV1ProtokollGet.mockReset().mockReturnValue(
      of({
        eintraege: [
          {
            id: 2,
            zeit: '2026-11-12T09:30:00Z',
            benutzer: 'sub-1',
            name: 'Anna Probe',
            rolle: 'MITARBEITER',
            aktion: 'ANZEIGEN',
            dokumentId: '0b6c3f5e-2a4d-4c1e-9f7a-1d2e3f4a5b6c',
            ip: '203.0.113.7',
            details: '{}',
          },
        ],
        gesamt: 1,
        seite: 0,
        groesse: 50,
      }),
    );
    service.apiV1ProtokollBenutzerGet
      .mockReset()
      .mockReturnValue(
        of([
          { id: 1, zeit: '', benutzer: 'sub-1', name: 'Anna Probe', rolle: 'MITARBEITER', gueltigVon: '2026-11-01T08:00:00Z', anlass: 'AKTION' },
        ]),
      );
    service.apiV1ProtokollBenutzerBenutzerAustrittPost.mockReset().mockReturnValue(of(undefined));
    TestBed.configureTestingModule({
      imports: [Protokoll],
      providers: [provideRouter([]), { provide: ProtokollService, useValue: service }],
    });
    fixture = TestBed.createComponent(Protokoll);
    element = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  const knopf = (text: string) =>
    [...element.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === text)!;

  async function eingeben(feld: string, wert: string) {
    const eingabe = element.querySelector<HTMLInputElement | HTMLSelectElement>(`[data-test=${feld}]`)!;
    eingabe.value = wert;
    eingabe.dispatchEvent(new Event(eingabe instanceof HTMLSelectElement ? 'change' : 'input'));
    await fixture.whenStable();
  }

  it('zeigt die Einträge mit Name und Rolle aus dem Verzeichnis, Zeit in Europe/Zurich', () => {
    const eintrag = element.querySelector('[data-test=eintrag]')!;
    expect(eintrag.textContent).toContain('12.11.2026 10:30');
    expect(eintrag.textContent).toContain('Anna Probe (Mitarbeiter)');
    expect(eintrag.textContent).toContain(TEXTE.protokoll.aktionen['ANZEIGEN']);
    expect(eintrag.querySelector('a')!.getAttribute('href')).toBe('/dokument/0b6c3f5e-2a4d-4c1e-9f7a-1d2e3f4a5b6c');
    expect(service.apiV1ProtokollGet).toHaveBeenCalledWith(
      undefined,
      undefined,
      undefined,
      undefined,
      50,
      0,
      undefined,
    );
  });

  it('fragt mit den Filtern ab (Zeitraum in Europe/Zurich, Bis-Tag eingeschlossen)', async () => {
    await eingeben('von', '2026-11-01');
    await eingeben('bis', '2026-11-12');
    await eingeben('benutzer', 'sub-1');
    await eingeben('aktion', 'VERWEIGERT');
    knopf(TEXTE.protokoll.anzeigen).click();
    await fixture.whenStable();
    expect(service.apiV1ProtokollGet).toHaveBeenLastCalledWith(
      'VERWEIGERT',
      'sub-1',
      '2026-11-12T23:00:00.000Z',
      undefined,
      50,
      0,
      '2026-10-31T23:00:00.000Z',
    );
  });

  it('blättert mit dem übernommenen Filter, nicht mit gerade eingetippten Werten', async () => {
    service.apiV1ProtokollGet.mockReturnValue(
      of({ eintraege: [{ id: 9, zeit: '2026-11-12T09:30:00Z', aktion: 'ANZEIGEN' }], gesamt: 120, seite: 0, groesse: 50 }),
    );
    await eingeben('benutzer', 'sub-1');
    knopf(TEXTE.protokoll.anzeigen).click();
    await fixture.whenStable();
    await eingeben('benutzer', 'sub-2');
    await eingeben('aktion', 'VERWEIGERT');
    knopf(TEXTE.protokoll.weiter).click();
    await fixture.whenStable();
    expect(service.apiV1ProtokollGet).toHaveBeenLastCalledWith(
      undefined,
      'sub-1',
      undefined,
      undefined,
      50,
      1,
      undefined,
    );
  });

  it('verwirft die Antwort einer überholten Anfrage', async () => {
    const langsam = new Subject<unknown>();
    service.apiV1ProtokollGet.mockReturnValueOnce(langsam);
    await eingeben('benutzer', 'sub-1');
    knopf(TEXTE.protokoll.anzeigen).click();
    await fixture.whenStable();
    knopf(TEXTE.protokoll.zuruecksetzen).click();
    await fixture.whenStable();
    expect(langsam.observed).toBe(false);
    langsam.next({ eintraege: [], gesamt: 0, seite: 0, groesse: 50 });
    await fixture.whenStable();
    expect(element.querySelectorAll('[data-test=eintrag]').length).toBe(1);
  });

  it('fragt bei ungültiger Dokument-ID nicht ab und sagt, was zu tun ist', async () => {
    service.apiV1ProtokollGet.mockClear();
    await eingeben('dokument', 'abc');
    knopf(TEXTE.protokoll.anzeigen).click();
    await fixture.whenStable();
    expect(service.apiV1ProtokollGet).not.toHaveBeenCalled();
    expect(element.querySelector('[role=alert]')!.textContent).toContain(TEXTE.protokoll.dokumentUngueltig);
  });

  it('erfasst den Austritt erst nach Bestätigung und lädt das Verzeichnis neu', async () => {
    const zeile = element.querySelector('[data-test=verzeichnis]')!;
    expect(zeile.textContent).toContain('Anna Probe (Mitarbeiter)');
    knopf(TEXTE.protokoll.austritt).click();
    await fixture.whenStable();
    expect(service.apiV1ProtokollBenutzerBenutzerAustrittPost).not.toHaveBeenCalled();
    knopf(TEXTE.protokoll.austrittBestaetigen).click();
    await fixture.whenStable();
    expect(service.apiV1ProtokollBenutzerBenutzerAustrittPost).toHaveBeenCalledWith('sub-1');
    expect(service.apiV1ProtokollBenutzerGet).toHaveBeenCalledTimes(2);
    expect(element.querySelector('[role=status]')!.textContent).toContain(TEXTE.protokoll.austrittErfasst('Anna Probe'));
  });

  it('meldet einen schon erfassten Austritt verständlich', async () => {
    service.apiV1ProtokollBenutzerBenutzerAustrittPost.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { fehlercode: 'STATUS_KONFLIKT' } })),
    );
    knopf(TEXTE.protokoll.austritt).click();
    await fixture.whenStable();
    knopf(TEXTE.protokoll.austrittBestaetigen).click();
    await fixture.whenStable();
    expect(element.querySelector('[role=alert]')!.textContent).toContain(TEXTE.protokoll.fehlercode['STATUS_KONFLIKT']);
  });
});
