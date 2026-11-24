import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { BenutzerverwaltungService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerProfil } from './benutzer-profil';

describe('Benutzerangaben ändern', () => {
  const etag = 'a'.repeat(64);
  const person = { id: 'ziel', etag, vorname: 'Alex', nachname: 'Beispiel', email: 'alex@example.invalid' };
  const api = { apiV1BenutzerBenutzerIdPatch: vi.fn() };

  beforeEach(() => {
    api.apiV1BenutzerBenutzerIdPatch.mockReset().mockReturnValue(of(null));
    TestBed.configureTestingModule({
      imports: [BenutzerProfil],
      providers: [{ provide: BenutzerverwaltungService, useValue: api }],
    });
  });

  function starten() {
    const fixture = TestBed.createComponent(BenutzerProfil);
    fixture.componentRef.setInput('person', person);
    fixture.detectChanges();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  function setzen(element: HTMLElement, feld: string, wert: string) {
    const input = element.querySelector<HTMLInputElement>('#profil-' + feld)!;
    input.value = wert;
    input.dispatchEvent(new Event('input'));
  }

  function senden(element: HTMLElement) {
    element.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  }

  it('sendet nur geänderte Felder mit dem ETag', () => {
    const { fixture, element } = starten();
    const abgeschlossen = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(abgeschlossen);
    setzen(element, 'email', 'neu@example.invalid');
    senden(element);
    expect(api.apiV1BenutzerBenutzerIdPatch).toHaveBeenCalledExactlyOnceWith(
      'ziel', { email: 'neu@example.invalid' }, `"${etag}"`,
    );
    expect(abgeschlossen).toHaveBeenCalledWith('ziel');
  });

  it('erhält Eingaben nach Versionskonflikt und übernimmt beim Nachladen nur unveränderte Felder', () => {
    api.apiV1BenutzerBenutzerIdPatch.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 412 })));
    const { fixture, element } = starten();
    setzen(element, 'email', 'neu@example.invalid');
    senden(element);
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.benutzerAktionsfehler.versionKonflikt);
    const nachladen = vi.fn();
    fixture.componentInstance.nachladen.subscribe(nachladen);
    [...element.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === TEXTE.benutzerProfil.laden)!.click();
    expect(nachladen).toHaveBeenCalledOnce();
    fixture.componentRef.setInput('person', { ...person, etag: 'b'.repeat(64), vorname: 'Neu' });
    fixture.detectChanges();
    expect(element.querySelector<HTMLInputElement>('#profil-email')!.value).toBe('neu@example.invalid');
    expect(element.querySelector<HTMLInputElement>('#profil-vorname')!.value).toBe('Neu');
  });

  it('übernimmt beim Zielwechsel keine Eingaben des vorherigen Benutzers', () => {
    const { fixture, element } = starten();
    const antwort = new Subject<null>();
    api.apiV1BenutzerBenutzerIdPatch.mockReturnValue(antwort);
    const abgeschlossen = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(abgeschlossen);
    setzen(element, 'email', 'neu@example.invalid');
    senden(element);
    fixture.componentRef.setInput('person', { ...person, id: 'andere', email: 'bea@example.invalid' });
    fixture.detectChanges();
    antwort.next(null);
    expect(element.querySelector<HTMLInputElement>('#profil-email')!.value).toBe('bea@example.invalid');
    expect(abgeschlossen).not.toHaveBeenCalled();
  });
});
