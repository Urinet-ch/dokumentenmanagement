import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Subject, of, throwError } from 'rxjs';
import { BenutzerverwaltungService } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerLoeschen } from './benutzer-loeschen';
import { Verwaltungsgrund } from './benutzer-loeschdialog';

describe('Löschvorschau und Löschauftrag', () => {
  const service = {
    apiV1BenutzerBenutzerIdLoeschvorschauGet: vi.fn(),
    apiV1BenutzerBenutzerIdLoeschenPost: vi.fn(),
  };
  const oeffnen = vi.fn();
  let geschlossen: Subject<Verwaltungsgrund | null>;
  let fixture: ComponentFixture<BenutzerLoeschen>;
  let element: HTMLElement;

  beforeEach(() => {
    geschlossen = new Subject();
    oeffnen.mockReset().mockReturnValue({ afterClosed: () => geschlossen, close: vi.fn() });
    service.apiV1BenutzerBenutzerIdLoeschvorschauGet.mockReset().mockReturnValue(
      of({ dokumente: 3, protokolleintraege: 12 }),
    );
    service.apiV1BenutzerBenutzerIdLoeschenPost.mockReset().mockReturnValue(of(new HttpResponse({ status: 204 })));
    TestBed.configureTestingModule({
      imports: [BenutzerLoeschen],
      providers: [
        { provide: BenutzerverwaltungService, useValue: service },
        { provide: MatDialog, useValue: { open: oeffnen } },
      ],
    });
    fixture = TestBed.createComponent(BenutzerLoeschen);
    fixture.componentRef.setInput('person', { id: 'probe', benutzername: 'alex', status: 'DEAKTIVIERT' });
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  it('holt die Vorschau vor der Bestätigung und meldet die ausgeführte Löschung', () => {
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    element.querySelector('button')!.click();
    fixture.detectChanges();
    expect(service.apiV1BenutzerBenutzerIdLoeschvorschauGet).toHaveBeenCalledWith('probe');
    expect(oeffnen.mock.lastCall![1].data).toEqual({ benutzername: 'alex', dokumente: 3, protokolleintraege: 12 });
    expect(element.querySelector('button')!.disabled).toBe(true);
    expect(service.apiV1BenutzerBenutzerIdLoeschenPost).not.toHaveBeenCalled();
    geschlossen.next('AUSTRITT');
    expect(service.apiV1BenutzerBenutzerIdLoeschenPost).toHaveBeenCalledWith(
      'probe', { grund: 'AUSTRITT' }, undefined, 'response',
    );
    expect(ergebnis).toHaveBeenCalledWith({ id: 'probe', antrag: false });
  });

  it('meldet einen Vier-Augen-Antrag statt einer bereits ausgeführten Löschung', () => {
    service.apiV1BenutzerBenutzerIdLoeschenPost.mockReturnValue(
      of(new HttpResponse({ status: 202, body: { antragId: 'antrag' } })),
    );
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    element.querySelector('button')!.click();
    geschlossen.next('AUSTRITT');
    expect(ergebnis).toHaveBeenCalledWith({ id: 'probe', antrag: true });
  });

  it('führt bei Abbruch, ungültiger Vorschau oder Zielwechsel keine Löschung aus', () => {
    element.querySelector('button')!.click();
    geschlossen.next(null);
    expect(service.apiV1BenutzerBenutzerIdLoeschenPost).not.toHaveBeenCalled();
    service.apiV1BenutzerBenutzerIdLoeschvorschauGet.mockReturnValueOnce(of({ dokumente: 1 }));
    fixture.detectChanges();
    element.querySelector('button')!.click();
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.benutzerLoeschen.vorschauFehler);
    expect(oeffnen).toHaveBeenCalledTimes(1);
    element.querySelector('button')!.click();
    fixture.componentRef.setInput('person', { id: 'andere', benutzername: 'bea' });
    fixture.detectChanges();
    geschlossen.next('AUSTRITT');
    expect(service.apiV1BenutzerBenutzerIdLoeschenPost).not.toHaveBeenCalled();
  });

  it('zeigt einen Löschfehler und ermöglicht keine Erfolgsmeldung', () => {
    service.apiV1BenutzerBenutzerIdLoeschenPost.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { fehlercode: 'LETZTER_INHABER' } })),
    );
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    element.querySelector('button')!.click();
    geschlossen.next('AUSTRITT');
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.benutzerAktionsfehler.letzterInhaber);
    expect(ergebnis).not.toHaveBeenCalled();
    expect(element.querySelector('button')!.disabled).toBe(false);
  });
});
