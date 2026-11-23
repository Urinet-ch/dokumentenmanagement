import { HttpErrorResponse, HttpHeaders, HttpResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { EinstellungenService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { EinstellungenSeite } from './einstellungen';

describe('Einstellungskatalog', () => {
  const service = { apiV1MandantEinstellungenGet: vi.fn() };
  const antwort = (katalog: unknown[]) => new HttpResponse({ body: { katalog }, headers: new HttpHeaders({ ETag: '"3"' }) });

  function starten() {
    TestBed.configureTestingModule({
      imports: [EinstellungenSeite],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: EinstellungenService, useValue: service }],
    });
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
        { provide: EinstellungenService, useValue: service }],
    });
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
});
