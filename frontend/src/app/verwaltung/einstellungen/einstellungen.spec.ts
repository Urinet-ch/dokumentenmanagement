import { HttpErrorResponse, HttpHeaders, HttpResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { EinstellungenService } from '../../gemeinsam/api';
import { AnmeldungService, FENSTER } from '../../gemeinsam/anmeldung/anmeldung.service';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { EinstellungenSeite } from './einstellungen';

describe('Einstellungskatalog', () => {
  const service = { apiV1MandantEinstellungenGet: vi.fn() };
  const wissen = { verfuegbar: true };
  const anmeldung = { benutzer: () => ({ id: 'test-id', mandant: 'test-mandant' }),
    darf: (aktion: string) => aktion !== 'A19' || wissen.verfuegbar,
    anmelden: vi.fn().mockResolvedValue(undefined), fortsetzungEntnehmen: vi.fn().mockReturnValue(null) };
  const dialog = { open: vi.fn().mockReturnValue({ afterClosed: () => of(true) }) };
  const inhalt = new Map<string, string>();
  const speicher = {
    getItem: (name: string) => inhalt.get(name) ?? null,
    setItem: (name: string, wert: string) => inhalt.set(name, wert),
    removeItem: (name: string) => inhalt.delete(name),
  };
  const antwort = (katalog: unknown[]) => new HttpResponse({ body: { katalog }, headers: new HttpHeaders({ ETag: '"3"' }) });

  beforeEach(() => {
    inhalt.clear();
    anmeldung.anmelden.mockClear();
    anmeldung.fortsetzungEntnehmen.mockReset().mockReturnValue(null);
    dialog.open.mockClear();
  });

  function starten() {
    TestBed.configureTestingModule({
      imports: [EinstellungenSeite],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: EinstellungenService, useValue: service }, { provide: AnmeldungService, useValue: anmeldung },
        { provide: FENSTER, useValue: { sessionStorage: speicher } }, { provide: MatDialog, useValue: dialog }],
    });
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    const fixture = TestBed.createComponent(EinstellungenSeite);
    fixture.detectChanges();
    return fixture;
  }

  it('zeigt Gruppen, Werte, Grenzen und den Wirksamkeitshinweis aus dem Katalog', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(of(antwort([
        { gruppe: 'WISSEN', schluessel: 'wissen.budgetTokens', name: 'Budget', aktuellerWert: 600000,
          standard: 100000, minimum: 10000, maximum: 5000000, einheit: 'Tokens', wirksamAb: 'Beim nächsten Lauf',
          typ: 'GANZZAHL' },
        { gruppe: 'KENNUNGEN', schluessel: 'kennungen.ibans', name: 'IBANs', aktuellerWert: ['CH00', 'CH01'], typ: 'IBANS' },
      ])));
    const element = starten().nativeElement as HTMLElement;
    expect(element.textContent).toContain('Wissensbasis');
    expect(element.textContent).toContain("600'000");
    expect(element.textContent).toContain("10'000 – 5'000'000");
    expect(element.textContent).toContain('Beim nächsten Lauf');
    expect(element.textContent).toContain('CH00, CH01');
  });

  it('blendet die Gruppe der Wissensbasis ohne freigeschaltetes Wissen aus (Spec 04, Req 22.5)', () => {
    wissen.verfuegbar = false;
    try {
      service.apiV1MandantEinstellungenGet.mockReturnValue(of(antwort([
          { gruppe: 'WISSEN', schluessel: 'wissen.budgetTokens', name: 'Budget', aktuellerWert: 600000,
            standard: 100000, minimum: 10000, maximum: 5000000, einheit: 'Tokens', typ: 'GANZZAHL' },
          { gruppe: 'KENNUNGEN', schluessel: 'kennungen.ibans', name: 'IBANs', aktuellerWert: ['CH00'], typ: 'IBANS' },
        ])));
      const element = starten().nativeElement as HTMLElement;
      expect(element.textContent).not.toContain('Wissensbasis');
      expect(element.textContent).not.toContain("600'000");
      expect(element.textContent).toContain('CH00');
    } finally {
      wissen.verfuegbar = true;
    }
  });

  it('zeigt einen Fehler mit erneutem Laden statt einen leeren Katalog', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
    const fixture = starten();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.httpFehler.server);
    service.apiV1MandantEinstellungenGet.mockReturnValueOnce(of(antwort([])));
    element.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    fixture.detectChanges();
    expect(element.textContent).toContain(TEXTE.einstellungen.keine);
  });

  it('meldet einen fehlerhaften Katalog ausdrücklich', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(of(antwort([{}])));
    const element = starten().nativeElement as HTMLElement;
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.einstellungen.ungueltig);
  });

  it('leitet bei verweigertem API-Zugriff auf «Nicht gefunden»', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 403 })));
    TestBed.configureTestingModule({
      imports: [EinstellungenSeite],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: EinstellungenService, useValue: service }, { provide: AnmeldungService, useValue: anmeldung },
        { provide: FENSTER, useValue: { sessionStorage: speicher } }, { provide: MatDialog, useValue: dialog }],
    });
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    const navigieren = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(EinstellungenSeite);
    fixture.detectChanges();
    expect(navigieren).toHaveBeenCalledWith('/nicht-gefunden');
  });

  it('prüft nur geänderte Felder mit dem ETag und zeigt Auswirkungen', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(of(antwort([
      { gruppe: 'CHAT', schluessel: 'chat.aufbewahrungMonate', name: 'Chat', typ: 'GANZZAHL',
        aktuellerWert: 12, minimum: 1, maximum: 60 },
    ])));
    const fixture = starten();
    const element = fixture.nativeElement as HTMLElement;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/konfiguration/versionen').flush([]);
    const input = element.querySelector<HTMLInputElement>('[id="eingabe-chat.aufbewahrungMonate"]')!;
    input.value = '6';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.einstellungen.vorschau)!.click();
    const request = http.expectOne('/api/v1/mandant/einstellungen/vorschau');
    expect(request.request.headers.get('If-Match')).toBe('"3"');
    expect(request.request.body).toEqual({ chat: { aufbewahrungMonate: 6 } });
    request.flush({ auswirkungen: [{ schluessel: 'chat.aufbewahrungMonate', text: 'Weniger Aufbewahrung' }] });
    fixture.detectChanges();
    expect(element.textContent).toContain('Weniger Aufbewahrung');
    http.verify();
  });

  it('zeigt Validierungsbefunde am Feld und behält die Eingabe nach Versionskonflikt', () => {
    const katalog = [
      { gruppe: 'CHAT', schluessel: 'chat.aufbewahrungMonate', name: 'Chat', typ: 'GANZZAHL', aktuellerWert: 12 },
    ];
    service.apiV1MandantEinstellungenGet.mockReturnValue(of(antwort(katalog)));
    const fixture = starten();
    const element = fixture.nativeElement as HTMLElement;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/konfiguration/versionen').flush([]);
    const input = element.querySelector<HTMLInputElement>('[id="eingabe-chat.aufbewahrungMonate"]')!;
    input.value = '6';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const vorschau = () => [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.einstellungen.vorschau)!;
    vorschau().click();
    http.expectOne('/api/v1/mandant/einstellungen/vorschau').flush(
      { befunde: [{ pfad: 'einstellungen.chat.aufbewahrungMonate', meldung: 'Wert prüfen' }] },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    fixture.detectChanges();
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(element.querySelector('[id="fehler-chat.aufbewahrungMonate"]')?.textContent).toContain('Wert prüfen');
    vorschau().click();
    http.expectOne('/api/v1/mandant/einstellungen/vorschau').flush(null, { status: 412, statusText: 'Precondition Failed' });
    fixture.detectChanges();
    expect(element.textContent).toContain(TEXTE.einstellungen.versionGeaendert);
    element.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    fixture.detectChanges();
    http.expectOne('/api/v1/konfiguration/versionen').flush([]);
    expect(element.querySelector<HTMLInputElement>('[id="eingabe-chat.aufbewahrungMonate"]')?.value).toBe('6');
    http.verify();
  });

  it('verwirft eine veraltete Vorschau, wenn die Eingabe währenddessen geändert wird', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(of(antwort([
      { gruppe: 'CHAT', schluessel: 'chat.aufbewahrungMonate', name: 'Chat', typ: 'GANZZAHL', aktuellerWert: 12 },
    ])));
    const fixture = starten();
    const element = fixture.nativeElement as HTMLElement;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/konfiguration/versionen').flush([]);
    const input = element.querySelector<HTMLInputElement>('[id="eingabe-chat.aufbewahrungMonate"]')!;
    input.value = '6';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.einstellungen.vorschau)!.click();
    const request = http.expectOne('/api/v1/mandant/einstellungen/vorschau');
    input.value = '7';
    input.dispatchEvent(new Event('input'));
    request.flush({ auswirkungen: [{ text: 'Veraltete Auswirkung' }] });
    fixture.detectChanges();
    expect(element.textContent).not.toContain('Veraltete Auswirkung');
    http.verify();
  });

  it('merkt die ganze Gruppe als Standard vor und verlangt vor dem Speichern die Vorschau', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(of(antwort([
      { gruppe: 'CHAT', schluessel: 'chat.aufbewahrungMonate', name: 'Chat', typ: 'GANZZAHL', aktuellerWert: 12 },
    ])));
    const fixture = starten();
    const element = fixture.nativeElement as HTMLElement;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/konfiguration/versionen').flush([]);
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.einstellungen.gruppeAufStandard)!.click();
    fixture.detectChanges();
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.einstellungen.vorschau)!.click();
    const vorschau = http.expectOne('/api/v1/mandant/einstellungen/vorschau');
    expect(vorschau.request.body).toEqual({ chat: { aufbewahrungMonate: null } });
    vorschau.flush({ auswirkungen: [] });
    fixture.detectChanges();
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.einstellungen.speichern)!.click();
    expect(dialog.open).toHaveBeenCalledOnce();
    http.expectOne((request) => request.method === 'PATCH').flush({ version: 4, geaendert: true });
    http.verify();
  });

  it('übernimmt eine frühere Version nur als Entwurf für die Vorschau', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(of(antwort([
      { gruppe: 'CHAT', schluessel: 'chat.aufbewahrungMonate', name: 'Chat', typ: 'GANZZAHL', aktuellerWert: 12 },
      { gruppe: 'KENNUNGEN', schluessel: 'kennungen.ibans', name: 'IBANs', typ: 'IBANS', aktuellerWert: [] },
    ])));
    const fixture = starten();
    const element = fixture.nativeElement as HTMLElement;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/konfiguration/versionen').flush([{ version: 2, zeit: '2026-11-23T12:00:00Z' }]);
    fixture.detectChanges();
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.einstellungen.versionVormerken)!.click();
    http.expectOne('/api/v1/konfiguration/versionen/2').flush({
      inhalt: { einstellungen: { chat: { aufbewahrungMonate: 6 }, kennungen: { ibans: ['CH00'] } } },
    });
    fixture.detectChanges();
    expect(element.textContent).toContain(TEXTE.einstellungen.versionVorgemerkt);
    http.expectNone((request) => request.method === 'PATCH');
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.einstellungen.vorschau)!.click();
    const vorschau = http.expectOne('/api/v1/mandant/einstellungen/vorschau');
    expect(vorschau.request.body).toEqual({ chat: { aufbewahrungMonate: 6 }, kennungen: { ibans: ['CH00'] } });
    vorschau.flush({ auswirkungen: [] });
    http.verify();
  });

  it('bestätigt eine riskante Änderung und wiederholt sie nach Step-up genau einmal', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(of(antwort([
      { gruppe: 'CHAT', schluessel: 'chat.aufbewahrungMonate', name: 'Chat', typ: 'GANZZAHL', aktuellerWert: 12 },
    ])));
    const fixture = starten();
    const element = fixture.nativeElement as HTMLElement;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/konfiguration/versionen').flush([]);
    const input = element.querySelector<HTMLInputElement>('[id="eingabe-chat.aufbewahrungMonate"]')!;
    input.value = '6';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.einstellungen.vorschau)!.click();
    http.expectOne('/api/v1/mandant/einstellungen/vorschau').flush({
      auswirkungen: [{ schluessel: 'chat.aufbewahrungMonate', text: 'Nachrichten werden gelöscht.' }],
    });
    fixture.detectChanges();
    [...element.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === TEXTE.einstellungen.speichern)!.click();
    expect(dialog.open).toHaveBeenCalledOnce();
    const erste = http.expectOne((request) => request.method === 'PATCH');
    expect(erste.request.params.get('bestaetigt')).toBe('chat.aufbewahrungMonate');
    erste.flush({ fehlercode: 'STEP_UP_NOETIG' }, { status: 401, statusText: 'Unauthorized' });
    expect(anmeldung.anmelden).toHaveBeenCalledWith('/verwaltung/einstellungen', true, expect.any(String));
    const id = anmeldung.anmelden.mock.calls[0][2];
    fixture.destroy();

    const vorRueckruf = TestBed.createComponent(EinstellungenSeite);
    vorRueckruf.detectChanges();
    http.expectNone((request) => request.method === 'PATCH');
    http.expectOne('/api/v1/konfiguration/versionen').flush([]);
    vorRueckruf.destroy();

    anmeldung.fortsetzungEntnehmen.mockReturnValueOnce(id);
    const erneut = TestBed.createComponent(EinstellungenSeite);
    erneut.detectChanges();
    http.expectOne('/api/v1/konfiguration/versionen').flush([]);
    const wiederholung = http.expectOne((request) => request.method === 'PATCH');
    expect(wiederholung.request.body).toEqual({ chat: { aufbewahrungMonate: 6 } });
    expect(wiederholung.request.headers.get('If-Match')).toBe('"3"');
    wiederholung.flush({ fehlercode: 'STEP_UP_NOETIG' }, { status: 401, statusText: 'Unauthorized' });
    erneut.detectChanges();
    expect((erneut.nativeElement as HTMLElement).textContent).toContain(TEXTE.einstellungen.erneuteAnmeldungFehler);
    expect(anmeldung.anmelden).toHaveBeenCalledOnce();
    http.verify();
  });
});
