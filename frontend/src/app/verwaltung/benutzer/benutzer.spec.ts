import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';
import { BenutzerAnsicht, BenutzerverwaltungService, VerwaltungRolle } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerDetail } from './benutzer-detail';
import { BenutzerListe } from './benutzer-liste';

const PERSON: BenutzerAnsicht = JSON.parse(`{
  "id":"probe", "benutzername":"probe", "vorname":"Alex", "nachname":"Beispiel",
  "rollen":["MITARBEITER"], "status":"AKTIV", "totp":true, "google":false,
  "einladungOffenSeit":"2026-11-22T20:00:00Z"
}`);
const ROLLE: VerwaltungRolle = {
  code: 'mitarbeiter',
  beschreibung: 'Mitarbeiterin oder Mitarbeiter',
  rechte: [
    { aktion: 'A2', bezeichnung: 'Dokumente lesen', ergebnis: 'NUR_EIGENE', fussnoten: [{ zeichen: '1', text: 'Nur eigene Dokumente' }] },
    { aktion: 'A7', bezeichnung: 'Bestätigen', ergebnis: 'VERBOTEN', fussnoten: [] },
    { aktion: 'A21', bezeichnung: 'Benutzer ansehen', ergebnis: 'VERBOTEN', fussnoten: [] },
  ],
};

describe('Benutzerübersicht (Spec 06, Req 8.1, 8.3, 8.7)', () => {
  const service = {
    apiV1BenutzerGet: vi.fn(),
    apiV1RollenGet: vi.fn(),
    apiV1BenutzerAntraegeGet: vi.fn(),
    apiV1BenutzerBenutzerIdGet: vi.fn(),
    apiV1BenutzerBenutzerIdRechteGet: vi.fn(),
  };
  let fixture: ComponentFixture<BenutzerListe>;
  let element: HTMLElement;

  async function starten(): Promise<void> {
    service.apiV1BenutzerGet.mockReset().mockReturnValue(of([PERSON]));
    service.apiV1RollenGet.mockReset().mockReturnValue(of([ROLLE]));
    service.apiV1BenutzerAntraegeGet.mockReset().mockReturnValue(of([
      { id: 'antrag', art: 'INHABER_ROLLE', ziel: 'probe', beantragtVon: 'andere', gueltigBis: '2026-11-24T20:00:00Z' },
    ]));
    TestBed.configureTestingModule({
      imports: [BenutzerListe],
      providers: [provideRouter([]), { provide: BenutzerverwaltungService, useValue: service }],
    });
    fixture = TestBed.createComponent(BenutzerListe);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('zeigt Benutzer, Status, Antrag und Rechte aus dem REST-Vertrag', async () => {
    await starten();
    expect(element.textContent).toContain('Alex Beispiel');
    expect(element.textContent).toContain('Mitarbeiter');
    expect(element.textContent).toContain('Aktiv');
    expect(element.textContent).toContain('Rolle Inhaber vergeben');
    expect(element.textContent).toContain('24.11.2026');
    expect(element.textContent).toContain('Nur eigene Dokumente');
    expect(element.textContent).toContain(TEXTE.benutzer.gruppen.lesen);
    expect(element.textContent).toContain(TEXTE.benutzer.gruppen.bestaetigen);
    expect(element.textContent).toContain(TEXTE.benutzer.gruppen.verwalten);
    expect(element.querySelector('a[href="/verwaltung/benutzer/probe"]')).not.toBeNull();
  });

  it('verwirft eine veraltete Suchantwort und setzt die Seitennummer zurück', async () => {
    await starten();
    const alt = new Subject<BenutzerAnsicht[]>();
    service.apiV1BenutzerGet.mockReturnValueOnce(alt).mockReturnValueOnce(of([]));
    const input = element.querySelector<HTMLInputElement>('#benutzer-suche')!;
    const form = element.querySelector<HTMLFormElement>('form')!;
    input.value = 'alte Suche';
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    input.value = 'neue Suche';
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    alt.next([PERSON]);
    fixture.detectChanges();
    expect(service.apiV1BenutzerGet).toHaveBeenLastCalledWith(50, 'neue Suche', 0);
    expect(element.textContent).toContain(TEXTE.benutzer.keine);
    expect(element.textContent).not.toContain('Alex Beispiel');
  });

  it('zeigt einen Fehler getrennt von einer leeren Liste und erlaubt erneutes Laden', async () => {
    await starten();
    service.apiV1BenutzerGet.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
    element.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.httpFehler.server);
    expect(element.textContent).not.toContain(TEXTE.benutzer.keine);
    service.apiV1BenutzerGet.mockReturnValueOnce(of([PERSON]));
    element.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    fixture.detectChanges();
    expect(element.textContent).toContain('Alex Beispiel');
  });

  it('wiederholt nach einem Fehler die angeforderte Seite', async () => {
    await starten();
    service.apiV1BenutzerGet.mockReturnValueOnce(of(Array.from({ length: 50 }, (_, i) => ({ ...PERSON, id: `${i}` }))));
    element.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    service.apiV1BenutzerGet.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
    const weiter = [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.benutzer.weiter)!;
    weiter.click();
    fixture.detectChanges();
    service.apiV1BenutzerGet.mockReturnValueOnce(of([PERSON]));
    element.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    fixture.detectChanges();
    expect(service.apiV1BenutzerGet).toHaveBeenLastCalledWith(50, undefined, 1);
    expect(element.textContent).toContain('Seite 2');
  });
});

describe('Benutzerdetail (Spec 06, Req 8.7)', () => {
  const service = {
    apiV1BenutzerBenutzerIdGet: vi.fn(),
    apiV1BenutzerBenutzerIdRechteGet: vi.fn(),
  };
  const parameter = new BehaviorSubject(convertToParamMap({ id: 'probe' }));

  async function starten(person: BenutzerAnsicht = PERSON) {
    service.apiV1BenutzerBenutzerIdGet.mockReset().mockReturnValue(of(person));
    service.apiV1BenutzerBenutzerIdRechteGet.mockReset().mockReturnValue(of(ROLLE));
    parameter.next(convertToParamMap({ id: 'probe' }));
    TestBed.configureTestingModule({
      imports: [BenutzerDetail],
      providers: [
        provideRouter([]),
        { provide: BenutzerverwaltungService, useValue: service },
        { provide: ActivatedRoute, useValue: { paramMap: parameter } },
      ],
    });
    const fixture = TestBed.createComponent(BenutzerDetail);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('zeigt Rechte, Anmeldeverfahren und den Hinweis auf separat verwaltete API-Tokens', async () => {
    const fixture = await starten();
    const element = fixture.nativeElement as HTMLElement;
    expect(service.apiV1BenutzerBenutzerIdRechteGet).toHaveBeenCalledWith('probe');
    expect(element.textContent).toContain('Nur eigene Dokumente');
    expect(element.textContent).toContain(TEXTE.benutzer.apiTokenHinweis);
    expect(element.textContent).toContain('22.11.2026');
    expect(element.querySelector('a[href="/verwaltung/api-tokens"]')).not.toBeNull();
  });

  it('fragt bei Rollenkonflikt keine falschen Rechte ab', async () => {
    const fixture = await starten({ ...PERSON, rollenkonflikt: true });
    expect(service.apiV1BenutzerBenutzerIdRechteGet).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(TEXTE.benutzer.rollenkonflikt);
  });

  it('verwirft das vorherige Detail und zeigt bei 404 die Nicht-gefunden-Seite', async () => {
    const fixture = await starten();
    const router = TestBed.inject(Router);
    const navigieren = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    service.apiV1BenutzerBenutzerIdGet.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })),
    );
    parameter.next(convertToParamMap({ id: 'fremd' }));
    fixture.detectChanges();
    expect(navigieren).toHaveBeenCalledWith('/nicht-gefunden');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Alex Beispiel');
  });
});
