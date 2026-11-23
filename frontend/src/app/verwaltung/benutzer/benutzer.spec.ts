import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';
import { AnmeldungService } from '../../gemeinsam/anmeldung/anmeldung.service';
import { Ansicht, BenutzerAnsicht, BenutzerverwaltungService, VerwaltungRolle } from '../../gemeinsam/api';
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
const anmeldung = {
  benutzer: vi.fn((): { id: string; name: string; mandant: string; rolle: 'inhaber' } | null => null),
  darf: vi.fn().mockReturnValue(false),
};

describe('Benutzerübersicht (Spec 06, Req 8.1, 8.3, 8.7)', () => {
  const service = {
    apiV1BenutzerGet: vi.fn(),
    apiV1BenutzerPost: vi.fn(),
    apiV1RollenGet: vi.fn(),
    apiV1BenutzerAntraegeGet: vi.fn(),
    apiV1BenutzerBenutzerIdGet: vi.fn(),
    apiV1BenutzerBenutzerIdRechteGet: vi.fn(),
  };
  let fixture: ComponentFixture<BenutzerListe>;
  let element: HTMLElement;

  async function starten(vorbereiten?: () => void): Promise<void> {
    service.apiV1BenutzerGet.mockReset().mockReturnValue(of([PERSON]));
    service.apiV1RollenGet.mockReset().mockReturnValue(of([ROLLE]));
    service.apiV1BenutzerAntraegeGet.mockReset().mockReturnValue(of([
      { id: 'antrag', art: 'INHABER_ROLLE', ziel: 'probe', beantragtVon: 'andere', gueltigBis: '2026-11-24T20:00:00Z' },
    ]));
    anmeldung.benutzer.mockReturnValue(null);
    anmeldung.darf.mockReset().mockReturnValue(false);
    vorbereiten?.();
    TestBed.configureTestingModule({
      imports: [BenutzerListe],
      providers: [
        provideRouter([]),
        { provide: BenutzerverwaltungService, useValue: service },
        { provide: AnmeldungService, useValue: anmeldung },
      ],
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
    expect([...element.querySelectorAll('h1, h2')]
      .filter((heading) => heading.textContent?.trim() === TEXTE.benutzer.titel)).toHaveLength(1);
    expect(element.querySelector('.pille.info')?.textContent).toContain('Nur eigene');
    expect(element.querySelector('.pille.gefahr')?.textContent).toContain('Nicht erlaubt');
  });

  it('zeigt das Einladungsformular nur bei A22 und A23', async () => {
    await starten(() => anmeldung.darf.mockImplementation((aktion: string) => aktion === 'A22' || aktion === 'A23'));
    expect(element.querySelector('app-benutzer-einladen')).not.toBeNull();
  });

  it('zeigt unbekannte Rechte-Ergebnisse mit dem sicheren Fallbacktext', async () => {
    await starten(() => service.apiV1RollenGet.mockReturnValueOnce(of([JSON.parse(`{
      "code":"inhaber", "beschreibung":"Inhaberin oder Inhaber",
      "rechte":[{"aktion":"A21","bezeichnung":"Benutzer ansehen","ergebnis":"ZUKUENFTIG","fussnoten":[]}]
    }`)])));
    expect(element.textContent).toContain(TEXTE.benutzer.unbekannt);
    expect(element.textContent).not.toContain('ZUKUENFTIG');
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
    expect(element.querySelector('.leer')?.textContent).toContain(TEXTE.benutzer.keine);
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
    const vorher = [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.benutzer.vor)!;
    const weiterNachSeite2 = [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.benutzer.weiter)!;
    expect(vorher.disabled).toBe(false);
    expect(weiterNachSeite2.disabled).toBe(true);
    vorher.click();
    fixture.detectChanges();
    expect(service.apiV1BenutzerGet).toHaveBeenLastCalledWith(50, undefined, 0);
    expect(element.textContent).toContain('Seite 1');
  });

  it('leitet bei fehlendem Listenzugriff zu «Nicht gefunden» weiter', async () => {
    await starten();
    const router = TestBed.inject(Router);
    const navigieren = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    service.apiV1BenutzerGet.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ status: 403 })),
    );
    element.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    expect(navigieren).toHaveBeenCalledWith('/nicht-gefunden');
  });

  it('wiederholt das Laden der Anträge nach einem Fehler', async () => {
    await starten(() => service.apiV1BenutzerAntraegeGet.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ status: 503 })),
    ));
    const antraege = element.querySelector('section[aria-labelledby="antraege-titel"]')!;
    expect(antraege.querySelector('[role=alert]')?.textContent).toContain(TEXTE.httpFehler.server);
    service.apiV1BenutzerAntraegeGet.mockReturnValueOnce(of([]));
    antraege.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    fixture.detectChanges();
    expect(antraege.textContent).toContain(TEXTE.benutzer.keineAntraege);
  });

  it('wiederholt das Laden der Rollen nach einem Fehler', async () => {
    await starten(() => service.apiV1RollenGet.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ status: 503 })),
    ));
    const rollen = element.querySelector('section[aria-labelledby="rollen-titel"]')!;
    expect(rollen.querySelector('[role=alert]')?.textContent).toContain(TEXTE.httpFehler.server);
    service.apiV1RollenGet.mockReturnValueOnce(of([ROLLE]));
    rollen.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    fixture.detectChanges();
    expect(rollen.querySelector('[role=alert]')).toBeNull();
    expect(rollen.textContent).toContain(ROLLE.beschreibung);
  });

  it('zeigt Leerzustände für Anträge und Rollen mit dem globalen Baustein', async () => {
    await starten(() => {
      service.apiV1BenutzerAntraegeGet.mockReturnValueOnce(of([]));
      service.apiV1RollenGet.mockReturnValueOnce(of([]));
    });
    expect(element.querySelector('section[aria-labelledby="antraege-titel"] .leer')?.textContent)
      .toContain(TEXTE.benutzer.keineAntraege);
    expect(element.querySelector('section[aria-labelledby="rollen-titel"] .leer')?.textContent)
      .toContain(TEXTE.benutzer.keineRollen);
  });

  it('zeigt Fortschrittsbalken, solange Liste, Anträge und Rollen laden', async () => {
    await starten(() => {
      service.apiV1BenutzerGet.mockReturnValueOnce(new Subject<BenutzerAnsicht[]>());
      service.apiV1BenutzerAntraegeGet.mockReturnValueOnce(new Subject<Ansicht[]>());
      service.apiV1RollenGet.mockReturnValueOnce(new Subject<VerwaltungRolle[]>());
    });
    expect(element.querySelectorAll('mat-progress-bar')).toHaveLength(3);
  });
});

describe('Benutzerdetail (Spec 06, Req 8.7)', () => {
  const service = {
    apiV1BenutzerBenutzerIdGet: vi.fn(),
    apiV1BenutzerBenutzerIdRechteGet: vi.fn(),
    apiV1RollenGet: vi.fn(),
  };
  const parameter = new BehaviorSubject(convertToParamMap({ id: 'probe' }));

  async function starten(person: BenutzerAnsicht = PERSON, vorbereiten?: () => void) {
    service.apiV1BenutzerBenutzerIdGet.mockReset().mockReturnValue(of(person));
    service.apiV1BenutzerBenutzerIdRechteGet.mockReset().mockReturnValue(of(ROLLE));
    service.apiV1RollenGet.mockReset().mockReturnValue(of([ROLLE]));
    anmeldung.benutzer.mockReturnValue(null);
    anmeldung.darf.mockReset().mockReturnValue(false);
    vorbereiten?.();
    parameter.next(convertToParamMap({ id: 'probe' }));
    TestBed.configureTestingModule({
      imports: [BenutzerDetail],
      providers: [
        provideRouter([]),
        { provide: BenutzerverwaltungService, useValue: service },
        { provide: AnmeldungService, useValue: anmeldung },
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

  it('bindet Profil-, Status- und Rollenaktionen nur mit den passenden Rechten ein', async () => {
    const fixture = await starten(PERSON, () => {
      anmeldung.benutzer.mockReturnValue({ id: 'inhaber', name: 'Inhaberin', mandant: 'urinet', rolle: 'inhaber' });
      anmeldung.darf.mockReturnValue(true);
    });
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('app-benutzer-profil')).not.toBeNull();
    expect(element.querySelector('app-benutzer-statusaktionen')).not.toBeNull();
    expect(element.querySelector('app-benutzer-rollenaktion')).not.toBeNull();
    expect(service.apiV1RollenGet).toHaveBeenCalledOnce();
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

  it('zeigt einen Rechtefehler getrennt vom Benutzerdetail und erlaubt erneutes Laden', async () => {
    const fixture = await starten(PERSON, () => service.apiV1BenutzerBenutzerIdRechteGet.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ status: 503 })),
    ));
    const element = fixture.nativeElement as HTMLElement;
    const warnung = element.querySelector('[role=alert]')!;
    expect(warnung.textContent).toContain(TEXTE.httpFehler.server);
    expect(element.textContent).toContain('Alex Beispiel');
    service.apiV1BenutzerBenutzerIdRechteGet.mockReturnValueOnce(of(ROLLE));
    warnung.querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')).toBeNull();
    expect(element.textContent).toContain('Nur eigene Dokumente');
  });
});
