import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Subject, of, throwError } from 'rxjs';
import { BenutzerverwaltungService, VerwaltungRolle } from '../../gemeinsam/api';
import { TEXTE } from '../../gemeinsam/texte.de-CH';
import { BenutzerRollenaktion } from './benutzer-rollenaktion';

describe('Rollenänderung', () => {
  const etag = 'a'.repeat(64);
  const inhaber: VerwaltungRolle = { code: 'inhaber', beschreibung: 'Inhaber', rechte: [] };
  const mitarbeiter: VerwaltungRolle = { code: 'mitarbeiter', beschreibung: 'Mitarbeiter', rechte: [] };
  const api = { apiV1BenutzerBenutzerIdRollePut: vi.fn() };
  const oeffnen = vi.fn();
  let geschlossen: Subject<string | null>;

  beforeEach(() => {
    geschlossen = new Subject();
    oeffnen.mockReset().mockReturnValue({ afterClosed: () => geschlossen, close: vi.fn() });
    api.apiV1BenutzerBenutzerIdRollePut.mockReset().mockReturnValue(of(new HttpResponse({ status: 204 })));
    TestBed.configureTestingModule({
      imports: [BenutzerRollenaktion],
      providers: [
        { provide: BenutzerverwaltungService, useValue: api },
        { provide: MatDialog, useValue: { open: oeffnen } },
      ],
    });
  });

  function starten() {
    const fixture = TestBed.createComponent(BenutzerRollenaktion);
    fixture.componentRef.setInput('person', {
      id: 'ziel', benutzername: 'alex', etag, rollen: new Set(['MITARBEITER']),
    });
    fixture.componentRef.setInput('akteurId', 'akteur');
    fixture.componentRef.setInput('rollen', [mitarbeiter, inhaber]);
    fixture.detectChanges();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  it('übernimmt die Rolle erst nach Vergleich und sendet den ETag mit', () => {
    const { fixture, element } = starten();
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    element.querySelector('button')!.click();
    expect(oeffnen.mock.lastCall![1].data.bisher).toEqual([mitarbeiter]);
    geschlossen.next('inhaber');
    expect(api.apiV1BenutzerBenutzerIdRollePut).toHaveBeenCalledExactlyOnceWith(
      'ziel', { rolle: 'inhaber' }, `"${etag}"`, undefined, 'response',
    );
    expect(ergebnis).toHaveBeenCalledWith({ id: 'ziel', antrag: false });
  });

  it('unterscheidet einen Antrag von einer direkt wirksamen Änderung', () => {
    api.apiV1BenutzerBenutzerIdRollePut.mockReturnValue(
      of(new HttpResponse({ status: 202, body: { antragId: 'antrag' } })),
    );
    const { fixture, element } = starten();
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    element.querySelector('button')!.click();
    geschlossen.next('inhaber');
    expect(ergebnis).toHaveBeenCalledWith({ id: 'ziel', antrag: true });
  });

  it('führt bei Zielwechsel oder fehlendem Rechtevergleich keine Änderung aus', () => {
    const { fixture, element } = starten();
    element.querySelector('button')!.click();
    fixture.componentRef.setInput('person', {
      id: 'anderes-ziel', benutzername: 'bea', etag, rollen: new Set(['MITARBEITER']),
    });
    fixture.detectChanges();
    geschlossen.next('inhaber');
    expect(api.apiV1BenutzerBenutzerIdRollePut).not.toHaveBeenCalled();
    fixture.componentRef.setInput('rollen', [inhaber]);
    fixture.detectChanges();
    element.querySelector('button')!.click();
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.benutzerRollenaktion.unvollstaendig);
    expect(oeffnen).toHaveBeenCalledTimes(1);
  });

  it('zeigt einen Versionskonflikt statt einer Erfolgsmeldung', () => {
    api.apiV1BenutzerBenutzerIdRollePut.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 412 })),
    );
    const { fixture, element } = starten();
    const ergebnis = vi.fn();
    fixture.componentInstance.abgeschlossen.subscribe(ergebnis);
    element.querySelector('button')!.click();
    geschlossen.next('inhaber');
    fixture.detectChanges();
    expect(element.querySelector('[role=alert]')?.textContent).toContain(TEXTE.benutzerAktionsfehler.versionKonflikt);
    expect(ergebnis).not.toHaveBeenCalled();
  });
});
