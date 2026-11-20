import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideApi } from '../gemeinsam/api';
import { AnmeldungService } from '../gemeinsam/anmeldung/anmeldung.service';
import { TEXTE } from '../gemeinsam/texte.de-CH';
import { WissenEntwurfZaehler } from './wissen-entwurf-zaehler';
import { WissenImport } from './wissen-import';

describe('Wissenspaket importieren', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<WissenImport>;
  const benutzer = signal({ id: 'u1', mandant: 'urinet', rolle: 'inhaber', name: 'Test' });
  const auffrischen = vi.fn();
  const bericht = { entwurfId: 'e1', status: 'OFFEN', paketSha256: 'a'.repeat(64),
    anzahl: { neu: 1, aendern: 2, unveraendert: 3, ignoriert: 4 },
    befunde: [{ code: 'QUELLE_UNBEKANNT', pfad: 'konzepte/probe.md' }], hinweise: [] };

  beforeEach(async () => {
    benutzer.set({ id: 'u1', mandant: 'urinet', rolle: 'inhaber', name: 'Test' });
    auffrischen.mockClear();
    TestBed.configureTestingModule({ providers: [
      provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideApi(''),
      { provide: AnmeldungService, useValue: { benutzer, darf: () => benutzer().rolle === 'inhaber' } },
      { provide: WissenEntwurfZaehler, useValue: { auffrischen } },
    ] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(WissenImport);
    fixture.detectChanges();
    await fixture.whenStable();
  });
  afterEach(() => http.verify());

  function hochladen() {
    const file = new File(['synthetisch'], 'probe.zip', { type: 'application/zip' });
    const input = fixture.nativeElement.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: { item: () => file }, configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    return http.expectOne('/api/v1/wissensentwuerfe/import');
  }

  it('sendet ein Multipart-Paket und zeigt alle Zähler und Befundpfade ohne Annahme', async () => {
    const request = hochladen();
    expect(request.request.method).toBe('POST');
    expect([...request.request.body.keys()]).toEqual(['paket']);
    expect(request.request.body.get('paket').name).toBe('probe.zip');
    request.flush(bericht, { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('QUELLE_UNBEKANNT');
    expect(fixture.nativeElement.textContent).toContain('konzepte/probe.md');
    expect(fixture.nativeElement.querySelector('a[href="/wissen/entwuerfe/e1"]')).toBeTruthy();
    expect(auffrischen).toHaveBeenCalledOnce();
    http.expectNone((r) => r.method === 'POST');
  });

  it('meldet einen unvollständigen Erfolgsbericht statt fehlende Zähler zu erfinden', async () => {
    hochladen().flush({ ...bericht, anzahl: { neu: 1 } }, { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(TEXTE.wissen.import.berichtFehlt);
    expect(auffrischen).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('a[href="/wissen/entwuerfe/e1"]')).toBeNull();
  });

  it('verlinkt einen vorhandenen Entwurf bei 409 ohne automatischen zweiten Upload', async () => {
    hochladen().flush({ entwurfId: 'alt', detail: 'Paket schon importiert.' },
      { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('a[href="/wissen/entwuerfe/alt"]')).toBeTruthy();
    http.expectNone((r) => r.method === 'POST');
  });

  it('zeigt 422-Befunde mit Pfaden', async () => {
    hochladen().flush({ detail: 'Paket ungültig.', befunde: [{ code: 'FRONTMATTER_UNGUELTIG', pfad: 'konzepte/x.md' }] },
      { status: 422, statusText: 'Unprocessable Content' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('FRONTMATTER_UNGUELTIG');
    expect(fixture.nativeElement.textContent).toContain('konzepte/x.md');
    expect(auffrischen).not.toHaveBeenCalled();
  });

  it('bricht bei Mandantenwechsel den Upload ab und entfernt die Dateiauswahl', async () => {
    const request = hochladen();
    benutzer.set({ ...benutzer(), mandant: 'vhf-gsk' });
    await fixture.whenStable();
    expect(request.cancelled).toBe(true);
    expect(fixture.nativeElement.textContent).not.toContain('probe.zip');
  });

  it('bricht bei einer Aktualisierung desselben Benutzerkontexts nicht ab', async () => {
    const request = hochladen();
    benutzer.set({ ...benutzer(), name: 'Aktualisiert' });
    await fixture.whenStable();
    expect(request.cancelled).toBe(false);
    request.flush(bericht, { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    expect(auffrischen).toHaveBeenCalledOnce();
  });
});
