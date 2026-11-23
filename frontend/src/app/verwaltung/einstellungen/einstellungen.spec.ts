import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { EinstellungenService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { EinstellungenSeite } from './einstellungen';

describe('Einstellungskatalog', () => {
  const service = { apiV1MandantEinstellungenGet: vi.fn() };

  function starten() {
    TestBed.configureTestingModule({
      imports: [EinstellungenSeite],
      providers: [provideRouter([]), { provide: EinstellungenService, useValue: service }],
    });
    const fixture = TestBed.createComponent(EinstellungenSeite);
    fixture.detectChanges();
    return fixture;
  }

  it('zeigt Gruppen, Werte, Grenzen und den Wirksamkeitshinweis aus dem Katalog', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(of({
      katalog: [
        { gruppe: 'WISSEN', schluessel: 'wissen.budgetTokens', name: 'Budget', aktuellerWert: 600000,
          standard: 100000, minimum: 10000, maximum: 5000000, einheit: 'Tokens', wirksamAb: 'Beim nächsten Lauf' },
        { gruppe: 'KENNUNGEN', schluessel: 'kennungen.ibans', name: 'IBANs', aktuellerWert: ['CH00', 'CH01'] },
      ],
    }));
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
    service.apiV1MandantEinstellungenGet.mockReturnValueOnce(of({ katalog: [] }));
    element.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    fixture.detectChanges();
    expect(element.textContent).toContain(TEXTE.einstellungen.keine);
  });

  it('meldet einen fehlerhaften Katalog ausdrücklich', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(of({ katalog: [{}] }));
    const element = starten().nativeElement as HTMLElement;
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.einstellungen.ungueltig);
  });

  it('leitet bei verweigertem API-Zugriff auf «Nicht gefunden»', () => {
    service.apiV1MandantEinstellungenGet.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 403 })));
    TestBed.configureTestingModule({
      imports: [EinstellungenSeite],
      providers: [provideRouter([]), { provide: EinstellungenService, useValue: service }],
    });
    const navigieren = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(EinstellungenSeite);
    fixture.detectChanges();
    expect(navigieren).toHaveBeenCalledWith('/nicht-gefunden');
  });
});
