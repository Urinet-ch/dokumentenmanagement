import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { BenutzerverwaltungService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerEinladen } from './benutzer-einladen';

describe('Benutzer einladen', () => {
  const api = { apiV1BenutzerPost: vi.fn() };

  beforeEach(() => {
    api.apiV1BenutzerPost.mockReset().mockReturnValue(of({ id: 'neuer-benutzer' }));
    TestBed.configureTestingModule({
      imports: [BenutzerEinladen],
      providers: [{ provide: BenutzerverwaltungService, useValue: api }],
    });
  });

  function starten() {
    const fixture = TestBed.createComponent(BenutzerEinladen);
    fixture.componentRef.setInput('rollen', [{ code: 'mitarbeiter', beschreibung: 'Mitarbeiter', rechte: [] }]);
    fixture.detectChanges();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  function ausfuellen(element: HTMLElement) {
    for (const [feld, wert] of Object.entries({
      benutzername: 'alex', email: 'alex@example.invalid', vorname: 'Alex', nachname: 'Beispiel',
    })) {
      const eingabe = element.querySelector<HTMLInputElement>('#einladung-' + feld)!;
      eingabe.value = wert;
      eingabe.dispatchEvent(new Event('input'));
    }
    const rolle = element.querySelector<HTMLSelectElement>('#einladung-rolle')!;
    rolle.value = 'mitarbeiter';
    rolle.dispatchEvent(new Event('change'));
  }

  it('zeigt ungültige Eingaben am Feld und sendet nichts', () => {
    const { fixture, element } = starten();
    element.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    expect(api.apiV1BenutzerPost).not.toHaveBeenCalled();
    expect(element.querySelector('#einladung-benutzername')?.getAttribute('aria-invalid')).toBe('true');
    expect(element.querySelector('#einladung-fehler-email')?.textContent).toContain(TEXTE.benutzerEinladen.ungueltig.email);
  });

  it('sendet gültige Angaben und unterscheidet einen Vier-Augen-Antrag', () => {
    api.apiV1BenutzerPost.mockReturnValue(of({ id: 'neuer-benutzer', antragId: 'antrag' }));
    const { fixture, element } = starten();
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    ausfuellen(element);
    element.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(api.apiV1BenutzerPost).toHaveBeenCalledExactlyOnceWith({
      benutzername: 'alex', email: 'alex@example.invalid', vorname: 'Alex', nachname: 'Beispiel', rolle: 'mitarbeiter',
    });
    expect(ergebnis).toHaveBeenCalledWith({ id: 'neuer-benutzer', antrag: true });
    fixture.detectChanges();
    expect(element.querySelector<HTMLInputElement>('#einladung-email')!.value).toBe('');
  });

  it('zeigt Befunde vom Server am Feld und erhält die Eingaben', () => {
    api.apiV1BenutzerPost.mockReturnValue(throwError(() => new HttpErrorResponse({
      status: 422, error: { befunde: [{ pfad: 'email', meldung: 'E-Mail-Adresse prüfen.' }] },
    })));
    const { fixture, element } = starten();
    ausfuellen(element);
    element.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    expect(element.querySelector('#einladung-fehler-email')?.textContent).toContain('E-Mail-Adresse prüfen.');
    expect(element.querySelector<HTMLInputElement>('#einladung-email')!.value).toBe('alex@example.invalid');
  });
});
